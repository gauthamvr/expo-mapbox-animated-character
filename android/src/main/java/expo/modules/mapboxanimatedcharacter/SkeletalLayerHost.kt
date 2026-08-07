package expo.modules.mapboxanimatedcharacter

import android.graphics.BitmapFactory
import android.opengl.GLES30
import android.opengl.GLUtils
import android.opengl.Matrix
import com.mapbox.maps.CustomLayerHost
import com.mapbox.maps.CustomLayerRenderParameters
import com.mapbox.maps.Projection
import com.mapbox.geojson.Point
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/**
 * CustomLayerHost that renders an animated skinned GLB character inside the map's own
 * GL frame (same depth buffer as the 3D buildings → real occlusion), equivalent to a
 * three.js custom layer on web Mapbox GL.
 *
 * Flicker-free positioning: when [followCamera] is on (the player), the model is anchored
 * to THIS frame's camera center (parameters.longitude/latitude) instead of the JS-pushed
 * coordinate. The game camera is always centered on the player, so model and camera can
 * never desync by a frame — which is what caused the visible jitter/flicker while moving.
 *
 * Color-accurate shading: baseColor textures are decoded sRGB→linear, lit with a
 * hemisphere ambient + sun (world Z-up), softly tone-mapped, and re-encoded to sRGB,
 * with day/dawn/dusk/night presets that track the map's lightPreset.
 */
class SkeletalLayerHost(
    private val scene: GlbScene?,
    longitude: Double,
    latitude: Double,
    private val sizeMeters: Double = 12.0,
    headingOffsetDeg: Float = BASE_HEADING_OFFSET_DEG,
) : CustomLayerHost {

    private var initialized = false
    private val headingOffset = headingOffsetDeg

    // Live transform — updated from JS each movement tick, read on the GL thread.
    @Volatile private var curLng: Double = longitude
    @Volatile private var curLat: Double = latitude
    @Volatile private var targetHeadingDeg: Float = 0f
    @Volatile private var animSpeed: Float = 1f
    @Volatile private var followCamera: Boolean = true
    @Volatile private var light: LightParams = LIGHT_PRESETS.getValue("day")
    // Tunable clip-space depth nudge toward the camera (0 = off). Kept as a knob in
    // case a device's custom-layer matrix doesn't depth-match the map's 3D content.
    @Volatile private var depthBias: Float = 0f
    private var curHeadingDeg: Float = 0f

    fun setDepthBias(bias: Float) { depthBias = bias }

    /** Push the live player position + facing heading (degrees, 0=N, clockwise) + anim playback rate. */
    fun updateTransform(lng: Double, lat: Double, headingDeg: Float, speed: Float) {
        curLng = lng
        curLat = lat
        targetHeadingDeg = headingDeg
        if (speed > 0f) animSpeed = speed
    }

    fun setFollowCamera(enabled: Boolean) { followCamera = enabled }

    fun setLightPreset(name: String) {
        LIGHT_PRESETS[name.lowercase()]?.let { light = it }
    }

    // ── Shader programs + cached uniform locations ───────────

    private var simpleProgram = 0
    private var meshProgram = 0
    private var skinnedProgram = 0

    private class Locs(p: Int) {
        val uMatrix = GLES30.glGetUniformLocation(p, "u_matrix")
        val uModelMatrix = GLES30.glGetUniformLocation(p, "u_modelMatrix")
        val uJoints = GLES30.glGetUniformLocation(p, "u_jointMatrices")
        val uBaseColor = GLES30.glGetUniformLocation(p, "u_baseColorFactor")
        val uTexture = GLES30.glGetUniformLocation(p, "u_texture")
        val uHasTexture = GLES30.glGetUniformLocation(p, "u_hasTexture")
        val uDepthBias = GLES30.glGetUniformLocation(p, "u_depthBias")
        val uSunDir = GLES30.glGetUniformLocation(p, "u_sunDir")
        val uSunColor = GLES30.glGetUniformLocation(p, "u_sunColor")
        val uAmbientSky = GLES30.glGetUniformLocation(p, "u_ambientSky")
        val uAmbientGround = GLES30.glGetUniformLocation(p, "u_ambientGround")
        val uEmissive = GLES30.glGetUniformLocation(p, "u_emissive")
        val uNormalTexture = GLES30.glGetUniformLocation(p, "u_normalTexture")
        val uHasNormalMap = GLES30.glGetUniformLocation(p, "u_hasNormalMap")
        val uNormalScale = GLES30.glGetUniformLocation(p, "u_normalScale")
        val uAlphaCutoff = GLES30.glGetUniformLocation(p, "u_alphaCutoff")
        val uMatEmissive = GLES30.glGetUniformLocation(p, "u_matEmissive")
    }
    private var meshLocs: Locs? = null
    private var skinnedLocs: Locs? = null

    // Fallback triangle
    private var fallbackVao = 0
    private var fallbackVbo = 0

    // GPU mesh data
    private data class GpuMesh(
        val vao: Int, val vbo: Int, val ebo: Int,
        val vertexCount: Int, val indexCount: Int, val hasIndices: Boolean,
        val isSkinned: Boolean = false, val skinIndex: Int = -1,
        val hasTexCoords: Boolean = false, val hasNormals: Boolean = false,
        val materialTextureIndex: Int = -1,
        val materialBaseColorFactor: FloatArray = floatArrayOf(1f, 1f, 1f, 1f),
        val materialDoubleSided: Boolean = false,
        val normalTextureIndex: Int = -1,
        val normalScale: Float = 1f,
        val emissiveFactor: FloatArray = floatArrayOf(0f, 0f, 0f),
        val alphaMode: Int = 0, // 0=OPAQUE 1=MASK 2=BLEND
        val alphaCutoff: Float = 0.5f,
    )
    private val gpuMeshes = mutableListOf<GpuMesh>()

    private data class GpuSkin(val jointMatrices: FloatArray, val jointCount: Int)
    private val gpuSkins = mutableListOf<GpuSkin>()
    private val glTextures = mutableMapOf<Int, Int>()

    // ── Animation state ──────────────────────────────────────

    private var nodeInfos: List<GlbNodeInfo> = emptyList()
    private var animationClips: List<GlbAnimation> = emptyList()

    private data class SkinAnimData(
        val jointCount: Int,
        val jointNodeIndices: IntArray,
        val inverseBindMatrices: FloatArray,
    )
    private val skinAnimData = mutableListOf<SkinAnimData>()

    // Playback + cross-fade. pending* is written from the main thread; the rest is
    // owned by the GL thread.
    @Volatile var pendingAnimationIndex: Int = -1
    private var currentAnimationIndex: Int = -1
    private var animationTime = 0f
    private var fadeFromIndex = -1
    private var fadeFromTime = 0f
    private var fadeAlpha = 1f // 1 = fully on current clip
    private var lastFrameTimeNs = 0L
    var isAnimating = false
        private set

    // Preallocated per-frame animation buffers (sized to the node count on first use).
    private var bufTA: Array<FloatArray> = emptyArray()
    private var bufRA: Array<FloatArray> = emptyArray()
    private var bufSA: Array<FloatArray> = emptyArray()
    private var bufTB: Array<FloatArray> = emptyArray()
    private var bufRB: Array<FloatArray> = emptyArray()
    private var bufSB: Array<FloatArray> = emptyArray()
    private var bufAnimatedA: BooleanArray = BooleanArray(0)
    private var bufAnimatedB: BooleanArray = BooleanArray(0)
    private var bufLocal: Array<FloatArray> = emptyArray()
    private var bufGlobal: Array<FloatArray> = emptyArray()
    private var bufVisited: BooleanArray = BooleanArray(0)
    private val tmpQuat = FloatArray(4)
    private val tmpMatA = FloatArray(16)
    private val tmpMatB = FloatArray(16)
    private val tmpMatC = FloatArray(16)

    // Repaint callback (set by module to trigger map repaint)
    var repaintCallback: Runnable? = null

    // ── Shaders ──────────────────────────────────────────────

    private val litFragmentShader = """
        #version 300 es
        // highp is mandatory in ES3 fragment shaders and required here: the
        // derivative-based TBN works on per-pixel UV deltas (~1e-5 when zoomed in)
        // whose squares underflow fp16 — on real GPUs (Adreno honours mediump as
        // fp16, unlike the emulator) that blew the normals out into white patches.
        precision highp float;
        uniform vec4 u_baseColorFactor;
        uniform sampler2D u_texture;
        uniform sampler2D u_normalTexture;
        uniform bool u_hasTexture;
        uniform bool u_hasNormalMap;
        uniform float u_normalScale; // glTF normalTexture.scale
        uniform float u_alphaCutoff; // < 0 disables the MASK test
        uniform vec3 u_matEmissive;  // material emissiveFactor (linear)
        uniform vec3 u_sunDir;
        uniform vec3 u_sunColor;
        uniform vec3 u_ambientSky;
        uniform vec3 u_ambientGround;
        uniform float u_emissive;
        in vec3 v_normal;
        in vec3 v_worldPos;
        in vec2 v_texCoord;
        in vec4 v_color;
        out vec4 fragColor;
        vec3 srgbToLinear(vec3 c) { return pow(c, vec3(2.2)); }
        vec3 linearToSrgb(vec3 c) { return pow(c, vec3(1.0 / 2.2)); }
        // Screen-space-derivative TBN: normal maps without TANGENT attributes
        // (most game GLBs, incl. the soldier, ship a normal map but no tangents).
        vec3 perturbNormal(vec3 N, vec3 P, vec2 uv) {
            vec3 mapN = texture(u_normalTexture, uv).xyz * 2.0 - 1.0;
            mapN.xy *= u_normalScale;
            vec3 dp1 = dFdx(P); vec3 dp2 = dFdy(P);
            vec2 duv1 = dFdx(uv); vec2 duv2 = dFdy(uv);
            vec3 dp2perp = cross(dp2, N); vec3 dp1perp = cross(N, dp1);
            vec3 T = dp2perp * duv1.x + dp1perp * duv2.x;
            vec3 B = dp2perp * duv1.y + dp1perp * duv2.y;
            float det = max(dot(T, T), dot(B, B));
            if (det <= 0.0) return N;
            return normalize(mat3(T * inversesqrt(det), B * inversesqrt(det), N) * mapN);
        }
        void main() {
            vec4 base = u_hasTexture ? texture(u_texture, v_texCoord) : vec4(1.0);
            float alpha = base.a * u_baseColorFactor.a * v_color.a;
            if (u_alphaCutoff >= 0.0 && alpha < u_alphaCutoff) discard;
            // glTF baseColorFactor + vertex colors are linear; the texture is sRGB.
            vec3 albedo = srgbToLinear(base.rgb) * u_baseColorFactor.rgb * v_color.rgb;
            // Hemisphere ambient from the GEOMETRIC normal: perturbed normals lower
            // the average n.z, which visibly dulls the whole model in ambient-heavy
            // day light. The normal map should add sun-light detail, not darken.
            vec3 gn = normalize(v_normal);
            vec3 n = u_hasNormalMap ? perturbNormal(gn, v_worldPos, v_texCoord) : gn;
            float ndl = max(dot(n, normalize(u_sunDir)), 0.0);
            vec3 ambient = mix(u_ambientGround, u_ambientSky, clamp(gn.z * 0.5 + 0.5, 0.0, 1.0));
            vec3 c = albedo * (ambient + u_sunColor * ndl + vec3(u_emissive)) + u_matEmissive;
            c = c / (1.0 + 0.08 * c); // soft highlight rolloff
            fragColor = vec4(linearToSrgb(c), alpha);
        }
    """.trimIndent()

    private val vertexShaderSource = """
        #version 300 es
        precision highp float;
        uniform mat4 u_matrix;
        uniform mat4 u_modelMatrix;
        uniform float u_depthBias;
        layout(location = 0) in vec3 a_position;
        layout(location = 1) in vec3 a_normal;
        layout(location = 2) in vec2 a_texCoord;
        layout(location = 3) in vec4 a_color;
        out vec3 v_normal;
        out vec3 v_worldPos;
        out vec2 v_texCoord;
        out vec4 v_color;
        void main() {
            gl_Position = u_matrix * vec4(a_position, 1.0);
            // The custom-layer projection uses different near/far planes than the map's
            // tile matrices, so raw depth loses to every building. Nudge toward the
            // camera to land in the same depth neighbourhood as map 3D content.
            gl_Position.z -= u_depthBias * gl_Position.w;
            v_normal = mat3(u_modelMatrix) * a_normal;
            // Same rotated space as v_normal — only used for derivative-based TBN.
            v_worldPos = mat3(u_modelMatrix) * a_position;
            v_texCoord = a_texCoord;
            v_color = a_color;
        }
    """.trimIndent()

    private val simpleVertexShader = """
        #version 300 es
        precision highp float;
        uniform mat4 u_matrix;
        layout(location = 0) in vec2 a_position;
        layout(location = 1) in vec3 a_color;
        out vec3 v_color;
        void main() { gl_Position = u_matrix * vec4(a_position, 0.0, 1.0); v_color = a_color; }
    """.trimIndent()

    private val simpleFragmentShader = """
        #version 300 es
        precision mediump float;
        in vec3 v_color;
        out vec4 fragColor;
        void main() { fragColor = vec4(v_color, 1.0); }
    """.trimIndent()

    private val skinnedVertexShader = """
        #version 300 es
        precision highp float;
        uniform mat4 u_matrix;
        uniform mat4 u_modelMatrix;
        uniform float u_depthBias;
        uniform mat4 u_jointMatrices[$MAX_JOINTS];
        layout(location = 0) in vec3 a_position;
        layout(location = 1) in vec4 a_joints;
        layout(location = 2) in vec4 a_weights;
        layout(location = 3) in vec2 a_texCoord;
        layout(location = 4) in vec3 a_normal;
        layout(location = 5) in vec4 a_color;
        out vec2 v_texCoord;
        out vec3 v_normal;
        out vec3 v_worldPos;
        out vec4 v_color;
        void main() {
            // Clamp: rigs beyond the joint cap must not index past the uniform array
            // (out-of-bounds UB on some GPUs); overflow joints just skin wrong.
            const int MAXJ = $MAX_JOINTS - 1;
            int j0 = clamp(int(a_joints.x), 0, MAXJ); int j1 = clamp(int(a_joints.y), 0, MAXJ);
            int j2 = clamp(int(a_joints.z), 0, MAXJ); int j3 = clamp(int(a_joints.w), 0, MAXJ);
            mat4 skinMatrix = a_weights.x * u_jointMatrices[j0]
                            + a_weights.y * u_jointMatrices[j1]
                            + a_weights.z * u_jointMatrices[j2]
                            + a_weights.w * u_jointMatrices[j3];
            vec4 skinnedPos = skinMatrix * vec4(a_position, 1.0);
            gl_Position = u_matrix * skinnedPos;
            gl_Position.z -= u_depthBias * gl_Position.w;
            vec3 skinnedNormal = mat3(skinMatrix) * a_normal;
            v_normal = mat3(u_modelMatrix) * skinnedNormal;
            v_worldPos = mat3(u_modelMatrix) * skinnedPos.xyz;
            v_texCoord = a_texCoord;
            v_color = a_color;
        }
    """.trimIndent()

    // ── Initialization (GL thread) ───────────────────────────

    override fun initialize() {
        android.util.Log.d(TAG, "initialize()")
        simpleProgram = buildProgram(simpleVertexShader, simpleFragmentShader)
        meshProgram = buildProgram(vertexShaderSource, litFragmentShader)
        skinnedProgram = buildProgram(skinnedVertexShader, litFragmentShader)
        meshLocs = if (meshProgram != 0) Locs(meshProgram) else null
        skinnedLocs = if (skinnedProgram != 0) Locs(skinnedProgram) else null

        if (simpleProgram == 0 && meshProgram == 0 && skinnedProgram == 0) {
            android.util.Log.e(TAG, "All shader programs failed"); return
        }
        setupFallbackTriangle()
        if (scene != null && (meshProgram != 0 || skinnedProgram != 0)) {
            uploadScene(scene)
        }
        // Re-trigger any in-flight animation after a context loss re-init.
        currentAnimationIndex = -1
        lastFrameTimeNs = 0L
        fadeFromIndex = -1
        fadeAlpha = 1f
        initialized = true
        android.util.Log.d(TAG, "Initialized. meshes=${gpuMeshes.size} skins=${gpuSkins.size} anims=${animationClips.size} nodes=${nodeInfos.size}")
        val err = GLES30.glGetError()
        if (err != GLES30.GL_NO_ERROR) android.util.Log.e(TAG, "GL error after init: $err")
    }

    private fun setupFallbackTriangle() {
        if (simpleProgram == 0) return
        val vaos = IntArray(1); GLES30.glGenVertexArrays(1, vaos, 0); fallbackVao = vaos[0]
        val vbos = IntArray(1); GLES30.glGenBuffers(1, vbos, 0); fallbackVbo = vbos[0]
        GLES30.glBindVertexArray(fallbackVao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, fallbackVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, 3 * 5 * 4, null, GLES30.GL_DYNAMIC_DRAW)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 5 * 4, 0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, 5 * 4, 2 * 4)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glBindVertexArray(0)
    }

    private fun uploadScene(scene: GlbScene) {
        nodeInfos = scene.nodes
        animationClips = scene.animations

        gpuSkins.clear(); skinAnimData.clear(); gpuMeshes.clear(); glTextures.clear()

        for (skin in scene.skins) {
            if (skin.jointCount > MAX_JOINTS) {
                android.util.Log.w(TAG, "Skin has ${skin.jointCount} joints > cap $MAX_JOINTS — joints past the cap " +
                    "are clamped and will deform wrong. Reduce the rig (e.g. drop finger bones) to fit $MAX_JOINTS.")
            }
            gpuSkins.add(GpuSkin(jointMatrices = skin.jointMatrices.copyOf(), jointCount = skin.jointCount))
            skinAnimData.add(SkinAnimData(
                jointCount = skin.jointCount,
                jointNodeIndices = skin.jointNodeIndices,
                inverseBindMatrices = skin.inverseBindMatrices,
            ))
        }

        for ((imgIdx, imgBytes) in scene.textureImages.withIndex()) {
            if (imgBytes.isEmpty()) continue
            val opts = BitmapFactory.Options().apply { inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888 }
            val bitmap = BitmapFactory.decodeByteArray(imgBytes, 0, imgBytes.size, opts) ?: continue
            val texIds = IntArray(1)
            GLES30.glGenTextures(1, texIds, 0)
            val texId = texIds[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
            // Mipmaps kill the texture shimmer/sparkle when the model is small on screen.
            GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            // glTF sampler wrap modes (10497 REPEAT / 33071 CLAMP / 33648 MIRROR)
            val wrap = scene.textureWraps.getOrNull(imgIdx) ?: intArrayOf(10497, 10497)
            fun glWrap(w: Int) = when (w) { 33071 -> GLES30.GL_CLAMP_TO_EDGE; 33648 -> GLES30.GL_MIRRORED_REPEAT; else -> GLES30.GL_REPEAT }
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, glWrap(wrap[0]))
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, glWrap(wrap[1]))
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
            glTextures[imgIdx] = texId
            bitmap.recycle()
        }

        for (mesh in scene.meshes) {
            val gpuMesh = if (mesh.isSkinned) uploadSkinnedMesh(mesh) else uploadMesh(mesh)
            if (gpuMesh != null) gpuMeshes.add(gpuMesh)
        }

        // Preallocate animation evaluation buffers.
        val n = nodeInfos.size
        if (n > 0 && bufLocal.size != n) {
            bufTA = Array(n) { FloatArray(3) }
            bufRA = Array(n) { FloatArray(4) }
            bufSA = Array(n) { FloatArray(3) }
            bufTB = Array(n) { FloatArray(3) }
            bufRB = Array(n) { FloatArray(4) }
            bufSB = Array(n) { FloatArray(3) }
            bufAnimatedA = BooleanArray(n)
            bufAnimatedB = BooleanArray(n)
            bufLocal = Array(n) { FloatArray(16) }
            bufGlobal = Array(n) { FloatArray(16) }
            bufVisited = BooleanArray(n)
        }

        android.util.Log.d(TAG, "Uploaded ${gpuMeshes.size} meshes, ${gpuSkins.size} skins, ${glTextures.size} textures")
        animationClips.forEachIndexed { i, a -> android.util.Log.d(TAG, "  Animation [$i]: '${a.name}' (${a.duration}s, ${a.channels.size} channels)") }
    }

    // ── Animation control ───────────────────────────────────

    fun setAnimation(index: Int) {
        pendingAnimationIndex = index
    }

    // ── Animation evaluation ─────────────────────────────────

    /** Evaluate a clip at [time] into translation/rotation/scale buffers (defaults pre-filled). */
    private fun evaluateClip(clip: GlbAnimation, time: Float, outT: Array<FloatArray>, outR: Array<FloatArray>, outS: Array<FloatArray>, animated: BooleanArray) {
        val nodeCount = nodeInfos.size
        for (i in 0 until nodeCount) {
            val ni = nodeInfos[i]
            outT[i][0] = ni.translation[0]; outT[i][1] = ni.translation[1]; outT[i][2] = ni.translation[2]
            outR[i][0] = ni.rotation[0]; outR[i][1] = ni.rotation[1]; outR[i][2] = ni.rotation[2]; outR[i][3] = ni.rotation[3]
            outS[i][0] = ni.scale[0]; outS[i][1] = ni.scale[1]; outS[i][2] = ni.scale[2]
            animated[i] = false
        }
        for (channel in clip.channels) {
            val node = channel.targetNode
            if (node !in 0 until nodeCount) continue
            val sampler = clip.samplers.getOrNull(channel.samplerIndex) ?: continue
            when (channel.targetPath) {
                "translation" -> { interpolateInto(sampler, time, outT[node], 3); animated[node] = true }
                "rotation" -> { interpolateInto(sampler, time, outR[node], 4); animated[node] = true }
                "scale" -> { interpolateInto(sampler, time, outS[node], 3); animated[node] = true }
            }
        }
    }

    /** Keyframe interpolation writing into [out] (size [cc]); slerp for quaternions. */
    private fun interpolateInto(sampler: GlbAnimationSampler, time: Float, out: FloatArray, cc: Int) {
        val times = sampler.times; val values = sampler.values
        if (times.isEmpty() || values.isEmpty()) return

        val t = time.coerceIn(times.first(), times.last())
        if (t <= times.first()) { for (c in 0 until cc) out[c] = values[c]; return }
        if (t >= times.last()) { val off = (times.size - 1) * cc; for (c in 0 until cc) out[c] = values[off + c]; return }

        var lo = 0; var hi = times.size - 1
        while (lo < hi - 1) { val mid = (lo + hi) / 2; if (times[mid] <= t) lo = mid else hi = mid }
        val alpha = if (times[hi] > times[lo]) (t - times[lo]) / (times[hi] - times[lo]) else 0f

        val off0 = lo * cc; val off1 = hi * cc
        if (sampler.interpolation == "STEP") { for (c in 0 until cc) out[c] = values[off0 + c]; return }
        if (cc == 4) {
            slerpInto(values, off0, values, off1, alpha, out)
        } else {
            for (c in 0 until cc) out[c] = values[off0 + c] * (1f - alpha) + values[off1 + c] * alpha
        }
    }

    private fun slerpInto(a: FloatArray, aOff: Int, b: FloatArray, bOff: Int, t: Float, out: FloatArray) {
        val ax = a[aOff]; val ay = a[aOff + 1]; val az = a[aOff + 2]; val aw = a[aOff + 3]
        var bx = b[bOff]; var by = b[bOff + 1]; var bz = b[bOff + 2]; var bw = b[bOff + 3]
        var dot = ax * bx + ay * by + az * bz + aw * bw
        if (dot < 0f) { dot = -dot; bx = -bx; by = -by; bz = -bz; bw = -bw }
        val s0: Float; val s1: Float
        if (dot > 0.9995f) { s0 = 1f - t; s1 = t }
        else {
            val theta = acos(dot.coerceIn(-1f, 1f)); val sinT = sin(theta)
            s0 = sin((1f - t) * theta) / sinT; s1 = sin(t * theta) / sinT
        }
        val rx = s0 * ax + s1 * bx; val ry = s0 * ay + s1 * by; val rz = s0 * az + s1 * bz; val rw = s0 * aw + s1 * bw
        val len = sqrt(rx * rx + ry * ry + rz * rz + rw * rw)
        if (len > 0f) { out[0] = rx / len; out[1] = ry / len; out[2] = rz / len; out[3] = rw / len }
        else { out[0] = 0f; out[1] = 0f; out[2] = 0f; out[3] = 1f }
    }

    /**
     * Build joint matrices for the current playback state — evaluates the active clip,
     * cross-fades from the previous clip while fading, walks the node hierarchy, and
     * writes jointGlobal × IBM into each skin's matrix array.
     */
    private fun updateJointMatrices() {
        if (nodeInfos.isEmpty() || bufLocal.isEmpty()) return
        val nodeCount = nodeInfos.size
        val clip = animationClips.getOrNull(currentAnimationIndex) ?: return

        evaluateClip(clip, animationTime, bufTA, bufRA, bufSA, bufAnimatedA)

        val fadeClip = animationClips.getOrNull(fadeFromIndex)
        val blending = fadeClip != null && fadeAlpha < 1f
        if (blending) {
            evaluateClip(fadeClip!!, fadeFromTime, bufTB, bufRB, bufSB, bufAnimatedB)
            // smoothstep the blend for a natural ease
            val w = fadeAlpha * fadeAlpha * (3f - 2f * fadeAlpha)
            for (i in 0 until nodeCount) {
                if (!bufAnimatedA[i] && !bufAnimatedB[i]) continue
                val ta = bufTA[i]; val tb = bufTB[i]
                ta[0] = tb[0] + (ta[0] - tb[0]) * w
                ta[1] = tb[1] + (ta[1] - tb[1]) * w
                ta[2] = tb[2] + (ta[2] - tb[2]) * w
                val sa = bufSA[i]; val sb = bufSB[i]
                sa[0] = sb[0] + (sa[0] - sb[0]) * w
                sa[1] = sb[1] + (sa[1] - sb[1]) * w
                sa[2] = sb[2] + (sa[2] - sb[2]) * w
                slerpInto(bufRB[i], 0, bufRA[i], 0, w, tmpQuat)
                bufRA[i][0] = tmpQuat[0]; bufRA[i][1] = tmpQuat[1]; bufRA[i][2] = tmpQuat[2]; bufRA[i][3] = tmpQuat[3]
                bufAnimatedA[i] = true
            }
        }

        // Local matrices
        for (i in 0 until nodeCount) {
            if (bufAnimatedA[i]) {
                val t = bufTA[i]; val r = bufRA[i]; val s = bufSA[i]
                Matrix.setIdentityM(tmpMatA, 0); tmpMatA[12] = t[0]; tmpMatA[13] = t[1]; tmpMatA[14] = t[2]
                quaternionToMatrixInto(r[0], r[1], r[2], r[3], tmpMatB)
                tmpMatB[0] *= s[0]; tmpMatB[1] *= s[0]; tmpMatB[2] *= s[0]
                tmpMatB[4] *= s[1]; tmpMatB[5] *= s[1]; tmpMatB[6] *= s[1]
                tmpMatB[8] *= s[2]; tmpMatB[9] *= s[2]; tmpMatB[10] *= s[2]
                Matrix.multiplyMM(bufLocal[i], 0, tmpMatA, 0, tmpMatB, 0)
            } else {
                System.arraycopy(nodeInfos[i].defaultLocalMatrix, 0, bufLocal[i], 0, 16)
            }
        }

        // Global transforms (hierarchy walk)
        java.util.Arrays.fill(bufVisited, false)
        Matrix.setIdentityM(tmpMatC, 0)
        for (i in 0 until nodeCount) {
            if (nodeInfos[i].parentIndex == -1 && !bufVisited[i]) {
                walkGlobalTransforms(i, tmpMatC, bufLocal, bufGlobal, bufVisited)
            }
        }
        for (i in 0 until nodeCount) {
            if (!bufVisited[i]) walkGlobalTransforms(i, tmpMatC, bufLocal, bufGlobal, bufVisited)
        }

        // jointMatrix = jointGlobal * IBM (scene-root space, matches the parser's bind pose)
        for (si in skinAnimData.indices) {
            if (si !in gpuSkins.indices) continue
            val sad = skinAnimData[si]
            val maxJoints = minOf(sad.jointCount, MAX_JOINTS)
            for (j in 0 until maxJoints) {
                val jointNode = sad.jointNodeIndices[j]
                if (jointNode in 0 until nodeCount) {
                    System.arraycopy(sad.inverseBindMatrices, j * 16, tmpMatA, 0, 16)
                    Matrix.multiplyMM(tmpMatB, 0, bufGlobal[jointNode], 0, tmpMatA, 0)
                    System.arraycopy(tmpMatB, 0, gpuSkins[si].jointMatrices, j * 16, 16)
                }
            }
        }
    }

    private fun quaternionToMatrixInto(x: Float, y: Float, z: Float, w: Float, out: FloatArray) {
        val len = sqrt(x * x + y * y + z * z + w * w).takeIf { it > 0f } ?: 1f
        val qx = x / len; val qy = y / len; val qz = z / len; val qw = w / len
        val xx = qx * qx; val yy = qy * qy; val zz = qz * qz
        val xy = qx * qy; val xz = qx * qz; val yz = qy * qz
        val wx = qw * qx; val wy = qw * qy; val wz = qw * qz
        out[0] = 1f - 2f * (yy + zz); out[1] = 2f * (xy + wz); out[2] = 2f * (xz - wy); out[3] = 0f
        out[4] = 2f * (xy - wz); out[5] = 1f - 2f * (xx + zz); out[6] = 2f * (yz + wx); out[7] = 0f
        out[8] = 2f * (xz + wy); out[9] = 2f * (yz - wx); out[10] = 1f - 2f * (xx + yy); out[11] = 0f
        out[12] = 0f; out[13] = 0f; out[14] = 0f; out[15] = 1f
    }

    private fun walkGlobalTransforms(
        nodeIdx: Int, parentGlobal: FloatArray,
        localMatrices: Array<FloatArray>, globalTransforms: Array<FloatArray>,
        visited: BooleanArray,
    ) {
        if (nodeIdx !in nodeInfos.indices || visited[nodeIdx]) return
        visited[nodeIdx] = true
        Matrix.multiplyMM(globalTransforms[nodeIdx], 0, parentGlobal, 0, localMatrices[nodeIdx], 0)
        for (child in nodeInfos[nodeIdx].children) {
            walkGlobalTransforms(child, globalTransforms[nodeIdx], localMatrices, globalTransforms, visited)
        }
    }

    // ── Mesh upload ──────────────────────────────────────────

    private fun uploadMesh(mesh: GlbMesh): GpuMesh? {
        val vaos = IntArray(1); GLES30.glGenVertexArrays(1, vaos, 0); val vao = vaos[0]
        val vbos = IntArray(1); GLES30.glGenBuffers(1, vbos, 0); val vbo = vbos[0]
        val hasNormals = mesh.normals != null; val hasTexCoords = mesh.texCoords != null
        val hasColors = mesh.colors != null
        val stride = 12
        val interleavedData = FloatArray(mesh.vertexCount * stride)
        for (i in 0 until mesh.vertexCount) {
            interleavedData[i*stride+0] = mesh.positions[i*3+0]; interleavedData[i*stride+1] = mesh.positions[i*3+1]; interleavedData[i*stride+2] = mesh.positions[i*3+2]
            interleavedData[i*stride+3] = if (hasNormals) mesh.normals!![i*3+0] else 0f
            interleavedData[i*stride+4] = if (hasNormals) mesh.normals!![i*3+1] else 0f
            interleavedData[i*stride+5] = if (hasNormals) mesh.normals!![i*3+2] else 1f
            interleavedData[i*stride+6] = if (hasTexCoords) mesh.texCoords!![i*2+0] else 0f
            interleavedData[i*stride+7] = if (hasTexCoords) mesh.texCoords!![i*2+1] else 0f
            for (k in 0..3) interleavedData[i*stride+8+k] = if (hasColors) mesh.colors!![i*4+k] else 1f
        }
        val vertexBuf = ByteBuffer.allocateDirect(interleavedData.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(interleavedData); vertexBuf.position(0)
        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, interleavedData.size * 4, vertexBuf, GLES30.GL_STATIC_DRAW)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride*4, 0); GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride*4, 3*4); GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(2, 2, GLES30.GL_FLOAT, false, stride*4, 6*4); GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(3, 4, GLES30.GL_FLOAT, false, stride*4, 8*4); GLES30.glEnableVertexAttribArray(3)
        var ebo = 0
        if (mesh.indices != null) {
            val ebos = IntArray(1); GLES30.glGenBuffers(1, ebos, 0); ebo = ebos[0]
            val indexBuf = ByteBuffer.allocateDirect(mesh.indices.size * 4).order(ByteOrder.nativeOrder()).asIntBuffer().put(mesh.indices); indexBuf.position(0)
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ebo)
            GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, mesh.indices.size * 4, indexBuf, GLES30.GL_STATIC_DRAW)
        }
        GLES30.glBindVertexArray(0)
        return GpuMesh(vao = vao, vbo = vbo, ebo = ebo, vertexCount = mesh.vertexCount, indexCount = mesh.indexCount,
            hasIndices = mesh.indices != null, hasTexCoords = hasTexCoords, hasNormals = hasNormals,
            materialTextureIndex = mesh.materialTextureIndex, materialBaseColorFactor = mesh.materialBaseColorFactor.copyOf(),
            materialDoubleSided = mesh.materialDoubleSided,
            normalTextureIndex = mesh.materialNormalTextureIndex,
            normalScale = mesh.materialNormalScale,
            emissiveFactor = mesh.materialEmissiveFactor.copyOf(),
            alphaMode = mesh.materialAlphaMode, alphaCutoff = mesh.materialAlphaCutoff)
    }

    private fun uploadSkinnedMesh(mesh: GlbMesh): GpuMesh? {
        if (mesh.joints == null || mesh.weights == null) return null
        val vaos = IntArray(1); GLES30.glGenVertexArrays(1, vaos, 0); val vao = vaos[0]
        val vbos = IntArray(1); GLES30.glGenBuffers(1, vbos, 0); val vbo = vbos[0]
        val hasUV = mesh.texCoords != null; val hasNormals = mesh.normals != null
        val hasColors = mesh.colors != null
        val stride = 20
        val data = FloatArray(mesh.vertexCount * stride)
        for (i in 0 until mesh.vertexCount) {
            var off = i * stride
            data[off++] = mesh.positions[i*3+0]; data[off++] = mesh.positions[i*3+1]; data[off++] = mesh.positions[i*3+2]
            data[off++] = mesh.joints[i*4+0]; data[off++] = mesh.joints[i*4+1]; data[off++] = mesh.joints[i*4+2]; data[off++] = mesh.joints[i*4+3]
            // Renormalize: quantized WEIGHTS_0 (normalized ubyte/ushort) rarely sums
            // to exactly 1, which would scale the skinned vertex.
            val w0 = mesh.weights[i*4+0]; val w1 = mesh.weights[i*4+1]; val w2 = mesh.weights[i*4+2]; val w3 = mesh.weights[i*4+3]
            val wSum = w0 + w1 + w2 + w3
            val wInv = if (wSum > 1e-6f) 1f / wSum else 0f
            data[off++] = w0 * wInv; data[off++] = w1 * wInv; data[off++] = w2 * wInv; data[off++] = w3 * wInv
            data[off++] = if (hasUV) mesh.texCoords!![i*2+0] else 0f; data[off++] = if (hasUV) mesh.texCoords!![i*2+1] else 0f
            data[off++] = if (hasNormals) mesh.normals!![i*3+0] else 0f; data[off++] = if (hasNormals) mesh.normals!![i*3+1] else 0f
            data[off++] = if (hasNormals) mesh.normals!![i*3+2] else 1f
            for (k in 0..3) data[off++] = if (hasColors) mesh.colors!![i*4+k] else 1f
        }
        val buf = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(data); buf.position(0)
        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.size * 4, buf, GLES30.GL_STATIC_DRAW)
        val sb = stride * 4
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, sb, 0); GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(1, 4, GLES30.GL_FLOAT, false, sb, 3*4); GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(2, 4, GLES30.GL_FLOAT, false, sb, 7*4); GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(3, 2, GLES30.GL_FLOAT, false, sb, 11*4); GLES30.glEnableVertexAttribArray(3)
        GLES30.glVertexAttribPointer(4, 3, GLES30.GL_FLOAT, false, sb, 13*4); GLES30.glEnableVertexAttribArray(4)
        GLES30.glVertexAttribPointer(5, 4, GLES30.GL_FLOAT, false, sb, 16*4); GLES30.glEnableVertexAttribArray(5)
        var ebo = 0
        if (mesh.indices != null) {
            val ebos = IntArray(1); GLES30.glGenBuffers(1, ebos, 0); ebo = ebos[0]
            val indexBuf = ByteBuffer.allocateDirect(mesh.indices.size * 4).order(ByteOrder.nativeOrder()).asIntBuffer().put(mesh.indices); indexBuf.position(0)
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ebo)
            GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, mesh.indices.size * 4, indexBuf, GLES30.GL_STATIC_DRAW)
        }
        GLES30.glBindVertexArray(0)
        return GpuMesh(vao = vao, vbo = vbo, ebo = ebo, vertexCount = mesh.vertexCount, indexCount = mesh.indexCount,
            hasIndices = mesh.indices != null, isSkinned = true, skinIndex = mesh.skinIndex,
            hasTexCoords = hasUV, hasNormals = hasNormals, materialTextureIndex = mesh.materialTextureIndex,
            materialBaseColorFactor = mesh.materialBaseColorFactor.copyOf(), materialDoubleSided = mesh.materialDoubleSided,
            normalTextureIndex = mesh.materialNormalTextureIndex,
            normalScale = mesh.materialNormalScale,
            emissiveFactor = mesh.materialEmissiveFactor.copyOf(),
            alphaMode = mesh.materialAlphaMode, alphaCutoff = mesh.materialAlphaCutoff)
    }

    // ── Rendering ────────────────────────────────────────────

    override fun render(parameters: CustomLayerRenderParameters) {
        if (!initialized) return

        // Single frame clock for animation + fade + heading smoothing.
        val now = System.nanoTime()
        val dt = if (lastFrameTimeNs > 0) ((now - lastFrameTimeNs) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.1f) else 0f
        lastFrameTimeNs = now

        // Animation switch (from main thread) → start a cross-fade.
        val pending = pendingAnimationIndex
        if (pending != currentAnimationIndex) {
            if (currentAnimationIndex in animationClips.indices && pending in animationClips.indices) {
                fadeFromIndex = currentAnimationIndex
                fadeFromTime = animationTime
                fadeAlpha = 0f
            } else {
                fadeFromIndex = -1
                fadeAlpha = 1f
            }
            currentAnimationIndex = pending
            animationTime = 0f
            isAnimating = currentAnimationIndex in animationClips.indices
        }

        // Advance playback.
        if (isAnimating) {
            val speed = animSpeed
            animationTime += dt * speed
            val clip = animationClips[currentAnimationIndex]
            if (clip.duration > 0f) animationTime %= clip.duration
            if (fadeFromIndex >= 0) {
                fadeFromTime += dt * speed
                val fadeClip = animationClips.getOrNull(fadeFromIndex)
                if (fadeClip != null && fadeClip.duration > 0f) fadeFromTime %= fadeClip.duration
                fadeAlpha += dt / FADE_DURATION
                if (fadeAlpha >= 1f) { fadeAlpha = 1f; fadeFromIndex = -1 }
            }
            updateJointMatrices()
        }

        // Smooth-turn toward the target heading (frame-rate independent).
        var dh = targetHeadingDeg - curHeadingDeg
        while (dh > 180f) dh -= 360f
        while (dh < -180f) dh += 360f
        curHeadingDeg += dh * (1f - exp(-HEADING_RESPONSE * dt))

        // Anchor: the player is always camera-centered, so use THIS frame's camera
        // center — model and map can never desync (the flicker fix). Geo mode remains
        // for non-player layers.
        val anchorLng = if (followCamera) parameters.longitude else curLng
        val anchorLat = if (followCamera) parameters.latitude else curLat

        val zoomScale = 2.0.pow(parameters.zoom)
        val center = Projection.project(Point.fromLngLat(anchorLng, anchorLat), zoomScale)
        val metersPerWorldPixel = cos(anchorLat * PI / 180.0) * 2.0 * PI * 6378137.0 / (512.0 * zoomScale)
        val pixelsPerMeter = 1.0 / metersPerWorldPixel

        val projMatrixD = DoubleArray(16) { parameters.projectionMatrix[it] }

        val depthWasEnabled = GLES30.glIsEnabled(GLES30.GL_DEPTH_TEST)
        val cullWasEnabled = GLES30.glIsEnabled(GLES30.GL_CULL_FACE)
        val blendWasEnabled = GLES30.glIsEnabled(GLES30.GL_BLEND)

        try {
            GLES30.glDisable(GLES30.GL_CULL_FACE)
            GLES30.glDisable(GLES30.GL_BLEND) // character is opaque; blending only invites artifacts
            if (gpuMeshes.isNotEmpty() && (meshProgram != 0 || skinnedProgram != 0)) {
                // Occlusion: depth-test against the map's 3D buildings so the character
                // is naturally hidden when it walks behind them. The map writes its 3D
                // depth inside parameters.depthRange — draw in the same window or every
                // building wins regardless of distance.
                GLES30.glEnable(GLES30.GL_DEPTH_TEST)
                GLES30.glDepthFunc(GLES30.GL_LEQUAL)
                GLES30.glDepthMask(true)
                val dr = parameters.depthRange
                val prevRange = FloatArray(2)
                GLES30.glGetFloatv(GLES30.GL_DEPTH_RANGE, prevRange, 0)
                if (dr != null) {
                    GLES30.glDepthRangef(dr.min, dr.max)
                }
                renderMeshes(projMatrixD, center.x, center.y, pixelsPerMeter)
                if (dr != null) GLES30.glDepthRangef(prevRange[0], prevRange[1])
            } else if (simpleProgram != 0) {
                GLES30.glDisable(GLES30.GL_DEPTH_TEST)
                val projF = FloatArray(16) { projMatrixD[it].toFloat() }
                renderTriangle(projF, center.x.toFloat(), center.y.toFloat(), (sizeMeters / metersPerWorldPixel).toFloat())
            }
        } finally {
            if (depthWasEnabled) GLES30.glEnable(GLES30.GL_DEPTH_TEST) else GLES30.glDisable(GLES30.GL_DEPTH_TEST)
            if (cullWasEnabled) GLES30.glEnable(GLES30.GL_CULL_FACE) else GLES30.glDisable(GLES30.GL_CULL_FACE)
            if (blendWasEnabled) GLES30.glEnable(GLES30.GL_BLEND) else GLES30.glDisable(GLES30.GL_BLEND)
        }

        // Keep repainting while animating (idle/walk loops run continuously).
        if (isAnimating || fadeFromIndex >= 0) repaintCallback?.run()
    }

    private fun renderMeshes(projD: DoubleArray, cxD: Double, cyD: Double, pixelsPerMeter: Double) {
        val hasRigidMeshes = gpuMeshes.any { !it.isSkinned }
        val hasSkinnedMeshes = gpuMeshes.any { it.isSkinned }

        // Rotation-only matrix: heading about world Z, then stand the Y-up glTF model
        // up into the Z-up Mapbox world (+90° about X also lands glTF forward on north).
        val rotOnly = FloatArray(16)
        Matrix.setIdentityM(rotOnly, 0)
        Matrix.rotateM(rotOnly, 0, curHeadingDeg + headingOffset, 0f, 0f, 1f)
        Matrix.rotateM(rotOnly, 0, 90f, 1f, 0f, 0f)

        // World axes are anisotropic: x/y are mercator world-PIXELS but altitude z is
        // METERS. Scaling z by pixelsPerMeter too stretches the character ~3.5× tall
        // (the "thin" look) — so scale x/y to pixels and z to meters.
        val xyScale = (pixelsPerMeter * sizeMeters).toFloat()
        val zScale = sizeMeters.toFloat()
        val scaleRotate = FloatArray(16)
        Matrix.setIdentityM(scaleRotate, 0)
        Matrix.scaleM(scaleRotate, 0, xyScale, xyScale, zScale)
        Matrix.multiplyMM(scaleRotate, 0, scaleRotate.copyOf(), 0, rotOnly, 0)

        // Fold the world-pixel translation into the projection in DOUBLE precision,
        // then drop to float — avoids precision jitter at high zoom.
        val projTransD = DoubleArray(16) { projD[it] }
        for (row in 0..3) { projTransD[12 + row] += projD[row] * cxD + projD[4 + row] * cyD }
        val projTransF = FloatArray(16) { projTransD[it].toFloat() }
        val mvpMatrix = FloatArray(16)
        Matrix.multiplyMM(mvpMatrix, 0, projTransF, 0, scaleRotate, 0)


        val l = light

        val bias = depthBias

        // Draw the subset matching [filter], grouped per program (uniforms set once each).
        fun drawSet(filter: (GpuMesh) -> Boolean) {
            if (hasRigidMeshes && meshProgram != 0 && gpuMeshes.any { !it.isSkinned && filter(it) }) {
                val locs = meshLocs!!
                GLES30.glUseProgram(meshProgram)
                GLES30.glUniformMatrix4fv(locs.uMatrix, 1, false, mvpMatrix, 0)
                GLES30.glUniformMatrix4fv(locs.uModelMatrix, 1, false, rotOnly, 0)
                GLES30.glUniform1f(locs.uDepthBias, bias)
                GLES30.glUniform1i(locs.uTexture, 0)
                GLES30.glUniform1i(locs.uNormalTexture, 1)
                setLightUniforms(locs, l)
                for (mesh in gpuMeshes) { if (!mesh.isSkinned && filter(mesh)) drawMesh(mesh, locs) }
                GLES30.glBindVertexArray(0)
                // leave both texture units clean — we share this GL context with the map
                GLES30.glActiveTexture(GLES30.GL_TEXTURE1); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
            }

            if (hasSkinnedMeshes && skinnedProgram != 0 && gpuMeshes.any { it.isSkinned && filter(it) }) {
                val locs = skinnedLocs!!
                GLES30.glUseProgram(skinnedProgram)
                GLES30.glUniformMatrix4fv(locs.uMatrix, 1, false, mvpMatrix, 0)
                GLES30.glUniformMatrix4fv(locs.uModelMatrix, 1, false, rotOnly, 0)
                GLES30.glUniform1f(locs.uDepthBias, bias)
                GLES30.glUniform1i(locs.uTexture, 0)
                GLES30.glUniform1i(locs.uNormalTexture, 1)
                setLightUniforms(locs, l)
                for (mesh in gpuMeshes) { if (mesh.isSkinned && filter(mesh)) drawSkinnedMesh(mesh, locs) }
                GLES30.glBindVertexArray(0)
                // leave both texture units clean — we share this GL context with the map
                GLES30.glActiveTexture(GLES30.GL_TEXTURE1); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
            }
        }

        // Opaque + alpha-MASK meshes first (depth-written), then BLEND materials on
        // top without depth writes — standard transparency ordering.
        drawSet { it.alphaMode != 2 }
        if (gpuMeshes.any { it.alphaMode == 2 }) {
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFuncSeparate(
                GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA,
                GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA,
            )
            GLES30.glDepthMask(false)
            drawSet { it.alphaMode == 2 }
            GLES30.glDepthMask(true)
            GLES30.glDisable(GLES30.GL_BLEND)
        }
    }

    private fun setLightUniforms(locs: Locs, l: LightParams) {
        GLES30.glUniform3fv(locs.uSunDir, 1, l.sunDir, 0)
        GLES30.glUniform3fv(locs.uSunColor, 1, l.sunColor, 0)
        GLES30.glUniform3fv(locs.uAmbientSky, 1, l.ambientSky, 0)
        GLES30.glUniform3fv(locs.uAmbientGround, 1, l.ambientGround, 0)
        GLES30.glUniform1f(locs.uEmissive, l.emissive)
    }

    /** Per-mesh material uniforms + texture bindings shared by both pipelines. */
    private fun setMaterialUniforms(mesh: GpuMesh, locs: Locs) {
        val texId = glTextures[mesh.materialTextureIndex]
        val hasTex = mesh.hasTexCoords && texId != null
        GLES30.glUniform4fv(locs.uBaseColor, 1, mesh.materialBaseColorFactor, 0)
        GLES30.glUniform1i(locs.uHasTexture, if (hasTex) 1 else 0)
        GLES30.glUniform3fv(locs.uMatEmissive, 1, mesh.emissiveFactor, 0)
        GLES30.glUniform1f(locs.uAlphaCutoff, if (mesh.alphaMode == 1) mesh.alphaCutoff else -1f)
        val nrmId = glTextures[mesh.normalTextureIndex]
        val hasNrm = mesh.hasTexCoords && nrmId != null
        GLES30.glUniform1i(locs.uHasNormalMap, if (hasNrm) 1 else 0)
        GLES30.glUniform1f(locs.uNormalScale, mesh.normalScale)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, if (hasNrm) nrmId!! else 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, if (hasTex) texId!! else 0)
    }

    private fun drawMesh(mesh: GpuMesh, locs: Locs) {
        setMaterialUniforms(mesh, locs)
        GLES30.glBindVertexArray(mesh.vao)
        if (mesh.hasIndices) GLES30.glDrawElements(GLES30.GL_TRIANGLES, mesh.indexCount, GLES30.GL_UNSIGNED_INT, 0)
        else GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, mesh.vertexCount)
    }

    private fun drawSkinnedMesh(mesh: GpuMesh, locs: Locs) {
        if (mesh.skinIndex !in gpuSkins.indices) return
        val skin = gpuSkins[mesh.skinIndex]
        val uploadCount = minOf(skin.jointCount, MAX_JOINTS)
        GLES30.glUniformMatrix4fv(locs.uJoints, uploadCount, false, skin.jointMatrices, 0)
        setMaterialUniforms(mesh, locs)
        GLES30.glBindVertexArray(mesh.vao)
        if (mesh.hasIndices) GLES30.glDrawElements(GLES30.GL_TRIANGLES, mesh.indexCount, GLES30.GL_UNSIGNED_INT, 0)
        else GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, mesh.vertexCount)
    }

    private fun renderTriangle(projMatrix: FloatArray, cx: Float, cy: Float, off: Float) {
        GLES30.glUseProgram(simpleProgram)
        val vertices = floatArrayOf(cx, cy-off, 1f,0f,0f, cx-off, cy+off, 0f,1f,0f, cx+off, cy+off, 0f,0f,1f)
        val buffer = ByteBuffer.allocateDirect(vertices.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(vertices); buffer.position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, fallbackVbo)
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, vertices.size * 4, buffer)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(simpleProgram, "u_matrix"), 1, false, projMatrix, 0)
        GLES30.glBindVertexArray(fallbackVao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glBindVertexArray(0)
    }

    // ── Cleanup ──────────────────────────────────────────────

    override fun contextLost() {
        // GPU handles are gone; CPU-side scene data is retained so initialize() can re-upload.
        initialized = false; simpleProgram = 0; meshProgram = 0; skinnedProgram = 0
        meshLocs = null; skinnedLocs = null
        fallbackVao = 0; fallbackVbo = 0; gpuMeshes.clear(); gpuSkins.clear(); glTextures.clear()
        skinAnimData.clear()
        isAnimating = false; currentAnimationIndex = -1; fadeFromIndex = -1; lastFrameTimeNs = 0L
    }

    override fun deinitialize() {
        if (meshProgram != 0) GLES30.glDeleteProgram(meshProgram)
        if (skinnedProgram != 0) GLES30.glDeleteProgram(skinnedProgram)
        if (simpleProgram != 0) GLES30.glDeleteProgram(simpleProgram)
        if (fallbackVao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(fallbackVao), 0)
        if (fallbackVbo != 0) GLES30.glDeleteBuffers(1, intArrayOf(fallbackVbo), 0)
        for (mesh in gpuMeshes) {
            GLES30.glDeleteVertexArrays(1, intArrayOf(mesh.vao), 0)
            GLES30.glDeleteBuffers(1, intArrayOf(mesh.vbo), 0)
            if (mesh.ebo != 0) GLES30.glDeleteBuffers(1, intArrayOf(mesh.ebo), 0)
        }
        if (glTextures.isNotEmpty()) { GLES30.glDeleteTextures(glTextures.size, glTextures.values.toIntArray(), 0); glTextures.clear() }
        gpuMeshes.clear(); gpuSkins.clear(); skinAnimData.clear()
        meshLocs = null; skinnedLocs = null
        initialized = false; isAnimating = false
    }

    // ── Shader compilation ──────────────────────────────────

    private fun buildProgram(vsSrc: String, fsSrc: String): Int {
        val vs = compileShader(GLES30.GL_VERTEX_SHADER, vsSrc)
        val fs = compileShader(GLES30.GL_FRAGMENT_SHADER, fsSrc)
        if (vs == 0 || fs == 0) return 0
        val prog = GLES30.glCreateProgram()
        GLES30.glAttachShader(prog, vs); GLES30.glAttachShader(prog, fs)
        GLES30.glLinkProgram(prog)
        val status = IntArray(1); GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) { android.util.Log.e(TAG, "Link: ${GLES30.glGetProgramInfoLog(prog)}"); GLES30.glDeleteProgram(prog); return 0 }
        GLES30.glDeleteShader(vs); GLES30.glDeleteShader(fs)
        return prog
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source); GLES30.glCompileShader(shader)
        val ok = IntArray(1); GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) { android.util.Log.e(TAG, "Shader: ${GLES30.glGetShaderInfoLog(shader)}"); GLES30.glDeleteShader(shader); return 0 }
        return shader
    }

    class LightParams(
        val sunDir: FloatArray,
        val sunColor: FloatArray,
        val ambientSky: FloatArray,
        val ambientGround: FloatArray,
        val emissive: Float,
    )

    companion object {
        private const val TAG = "SkeletalLayerHost"

        // ES 3.0 guarantees only 256 vec4 vertex uniforms (= 64 mat4), so stay at 64.
        // Soldier uses 49+2 joints, fox 24.
        private const val MAX_JOINTS = 64

        // glTF forward (+Z) lands on world -Y (north) after the +90° stand-up rotation,
        // so heading 0 (north) needs no base offset.
        const val BASE_HEADING_OFFSET_DEG = 0f

        private const val FADE_DURATION = 0.28f // idle↔walk cross-fade seconds
        private const val HEADING_RESPONSE = 14f // exp smoothing rate (1/s)

        // Hemisphere + sun rigs tuned to track the map's lightPreset moods while
        // keeping mid-tones close to the texture's true albedo. Day is pushed a bit
        // brighter than "true albedo": the web's three.js rig ran hemisphere 1.15 +
        // key 1.7, so characters read noticeably duller here until ~1.3 sun.
        private val LIGHT_PRESETS = mapOf(
            "day" to LightParams(
                sunDir = floatArrayOf(0.35f, 0.2f, 0.91f),
                sunColor = floatArrayOf(1.30f, 1.25f, 1.17f),
                ambientSky = floatArrayOf(0.70f, 0.72f, 0.76f),
                ambientGround = floatArrayOf(0.46f, 0.45f, 0.43f),
                emissive = 0.02f,
            ),
            "dawn" to LightParams(
                sunDir = floatArrayOf(0.60f, 0.25f, 0.50f),
                sunColor = floatArrayOf(0.92f, 0.68f, 0.50f),
                ambientSky = floatArrayOf(0.52f, 0.50f, 0.60f),
                ambientGround = floatArrayOf(0.36f, 0.34f, 0.36f),
                emissive = 0.05f,
            ),
            "dusk" to LightParams(
                sunDir = floatArrayOf(-0.55f, 0.20f, 0.50f),
                sunColor = floatArrayOf(0.88f, 0.56f, 0.38f),
                ambientSky = floatArrayOf(0.46f, 0.42f, 0.56f),
                ambientGround = floatArrayOf(0.32f, 0.30f, 0.34f),
                emissive = 0.09f,
            ),
            "night" to LightParams(
                sunDir = floatArrayOf(0.30f, 0.30f, 0.85f),
                sunColor = floatArrayOf(0.30f, 0.34f, 0.48f),
                ambientSky = floatArrayOf(0.22f, 0.24f, 0.38f),
                ambientGround = floatArrayOf(0.14f, 0.15f, 0.22f),
                emissive = 0.16f,
            ),
        )
    }
}
