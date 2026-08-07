package expo.modules.mapboxanimatedcharacter

import android.opengl.Matrix
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * glTF 2.0 Binary (GLB) parser.
 * Parses meshes, skins, textures, skeleton hierarchy, and animations.
 */

// ── Data classes ──────────────────────────────────────

data class GlbMesh(
    val name: String,
    val positions: FloatArray,
    val normals: FloatArray?,
    val texCoords: FloatArray?,
    val joints: FloatArray?,
    val weights: FloatArray?,
    val indices: IntArray?,
    val vertexCount: Int,
    val indexCount: Int,
    val isSkinned: Boolean = false,
    val skinIndex: Int = -1,
    val meshNodeIndex: Int = -1,
    val colors: FloatArray? = null, // COLOR_0 expanded to vec4, linear
    val materialTextureIndex: Int = -1,
    val materialBaseColorFactor: FloatArray = floatArrayOf(1f, 1f, 1f, 1f),
    val materialDoubleSided: Boolean = false,
    val materialNormalTextureIndex: Int = -1, // image index of the normal map
    val materialNormalScale: Float = 1f,
    val materialEmissiveFactor: FloatArray = floatArrayOf(0f, 0f, 0f),
    val materialAlphaMode: Int = 0, // 0=OPAQUE 1=MASK 2=BLEND
    val materialAlphaCutoff: Float = 0.5f,
)

data class GlbSkin(
    val jointCount: Int,
    val jointNodeIndices: IntArray,
    val inverseBindMatrices: FloatArray,
    val jointMatrices: FloatArray,
)

data class GlbAnimationChannel(
    val samplerIndex: Int,
    val targetNode: Int,
    val targetPath: String,
)

data class GlbAnimationSampler(
    val times: FloatArray,
    val values: FloatArray,
    val interpolation: String,
    val componentCount: Int,
)

data class GlbAnimation(
    val name: String,
    val channels: List<GlbAnimationChannel>,
    val samplers: List<GlbAnimationSampler>,
    val duration: Float,
)

data class GlbNodeInfo(
    val index: Int,
    val name: String,
    val translation: FloatArray,
    val rotation: FloatArray,
    val scale: FloatArray,
    val defaultLocalMatrix: FloatArray,
    val children: IntArray,
    val parentIndex: Int = -1,
)

data class GlbScene(
    val meshes: List<GlbMesh>,
    val skins: List<GlbSkin> = emptyList(),
    val textureImages: List<ByteArray> = emptyList(),
    val textureWraps: List<IntArray> = emptyList(), // per image [wrapS, wrapT], glTF enum values
    val animations: List<GlbAnimation> = emptyList(),
    val nodes: List<GlbNodeInfo> = emptyList(),
    val skippedSkinnedPrimitives: Int = 0,
)

object GlbParser {

    private data class MaterialInfo(
        val textureIndex: Int = -1,
        val baseColorFactor: FloatArray = floatArrayOf(1f, 1f, 1f, 1f),
        val doubleSided: Boolean = false,
        val normalTextureIndex: Int = -1,
        val normalScale: Float = 1f,
        val emissiveFactor: FloatArray = floatArrayOf(0f, 0f, 0f),
        val alphaMode: Int = 0,
        val alphaCutoff: Float = 0.5f,
    )

    private const val TAG = "GlbParser"
    private const val GLB_MAGIC = 0x46546C67
    private const val JSON_CHUNK = 0x4E4F534A
    private const val BIN_CHUNK = 0x004E4942

    private const val BYTE = 5120
    private const val UNSIGNED_BYTE = 5121
    private const val SHORT = 5122
    private const val UNSIGNED_SHORT = 5123
    private const val UNSIGNED_INT = 5125
    private const val FLOAT = 5126
    private const val GL_TRIANGLES = 4

    // Required extensions we can honour (quantization is covered by the generalized
    // accessor reader; the material ones degrade gracefully). Anything else that a
    // file REQUIRES (Draco, meshopt, basisu…) would decode as garbage — fail loudly.
    private val SUPPORTED_REQUIRED_EXTENSIONS = setOf(
        "KHR_mesh_quantization",
        "KHR_materials_unlit",
        "KHR_materials_pbrSpecularGlossiness",
        "KHR_materials_emissive_strength",
    )

    fun parse(data: ByteArray): GlbScene? {
        try {
            val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            val magic = buf.getInt()
            if (magic != GLB_MAGIC) { android.util.Log.e(TAG, "Not a GLB"); return null }
            buf.getInt(); buf.getInt() // version, totalLength

            val jsonChunkLen = buf.getInt(); val jsonChunkType = buf.getInt()
            if (jsonChunkType != JSON_CHUNK) { android.util.Log.e(TAG, "No JSON chunk"); return null }
            val jsonBytes = ByteArray(jsonChunkLen); buf.get(jsonBytes)
            val json = JSONObject(String(jsonBytes, Charsets.UTF_8))

            val binChunkLen = buf.getInt(); val binChunkType = buf.getInt()
            if (binChunkType != BIN_CHUNK) { android.util.Log.e(TAG, "No BIN chunk"); return null }
            val binData = ByteArray(binChunkLen); buf.get(binData)
            val binBuf = ByteBuffer.wrap(binData).order(ByteOrder.LITTLE_ENDIAN)

            val required = json.optJSONArray("extensionsRequired")
            if (required != null) {
                for (i in 0 until required.length()) {
                    val ext = required.getString(i)
                    if (ext !in SUPPORTED_REQUIRED_EXTENSIONS) {
                        android.util.Log.e(TAG, "GLB requires unsupported extension '$ext' — " +
                            "decompress/re-export it first (e.g. `gltf-transform copy in.glb out.glb`)")
                        return null
                    }
                }
            }

            val accessors = json.getJSONArray("accessors")
            val bufferViews = json.getJSONArray("bufferViews")
            val meshesJson = json.getJSONArray("meshes")
            val nodesJson = json.optJSONArray("nodes")

            val allMeshes = mutableListOf<GlbMesh>()
            val parsedSkins = mutableListOf<GlbSkin>()
            val textureImages = extractTextureImages(json, bufferViews, binBuf)
            val textureWraps = extractTextureWraps(json, textureImages.size)

            // Parse node infos for skeleton hierarchy
            val nodeInfos = if (nodesJson != null) parseNodeInfos(nodesJson) else emptyList()

            // Parse animations
            val animations = parseAnimations(json, accessors, bufferViews, binBuf)

            if (nodesJson != null) {
                val rootNodes = readSceneRoots(json)
                val worldTransforms = computeWorldTransforms(nodesJson, rootNodes)

                val skinsJson = json.optJSONArray("skins")
                if (skinsJson != null) {
                    for (si in 0 until skinsJson.length()) {
                        parsedSkins.add(parseSkin(si, skinsJson, accessors, bufferViews, binBuf, worldTransforms, nodesJson))
                    }
                }

                val materialsJson = json.optJSONArray("materials")

                for (nodeIndex in 0 until nodesJson.length()) {
                    val node = nodesJson.getJSONObject(nodeIndex)
                    if (!node.has("mesh")) continue
                    val meshIndex = node.getInt("mesh")
                    if (meshIndex !in 0 until meshesJson.length()) continue

                    val meshObj = meshesJson.getJSONObject(meshIndex)
                    val nodeName = node.optString("name", meshObj.optString("name", "node_$nodeIndex"))
                    val primitives = meshObj.optJSONArray("primitives") ?: continue
                    val nodeMatrix = worldTransforms[nodeIndex]
                    val hasSkin = node.has("skin")
                    val skinIdx = if (hasSkin) node.getInt("skin") else -1

                    for (pi in 0 until primitives.length()) {
                        val prim = primitives.getJSONObject(pi)
                        val mode = prim.optInt("mode", GL_TRIANGLES)
                        if (mode != GL_TRIANGLES) {
                            android.util.Log.w(TAG, "Skipping primitive with mode=$mode (only TRIANGLES supported)")
                            continue
                        }

                        val attrs = prim.getJSONObject("attributes")
                        val isSkinned = hasSkin || attrs.has("JOINTS_0")
                        val meshName = if (primitives.length() > 1) "$nodeName#$pi" else nodeName

                        val posIdx = attrs.getInt("POSITION")
                        val positions = readFloatAccessor(posIdx, accessors, bufferViews, binBuf) ?: continue
                        val normals = if (attrs.has("NORMAL")) readFloatAccessor(attrs.getInt("NORMAL"), accessors, bufferViews, binBuf) else null
                        val texCoords = if (attrs.has("TEXCOORD_0")) readFloatAccessor(attrs.getInt("TEXCOORD_0"), accessors, bufferViews, binBuf) else null
                        val colors = if (attrs.has("COLOR_0")) readColorAccessor(attrs.getInt("COLOR_0"), accessors, bufferViews, binBuf) else null
                        val indices = if (prim.has("indices")) readIndexAccessor(prim.getInt("indices"), accessors, bufferViews, binBuf) else null
                        val vertexCount = accessors.getJSONObject(posIdx).getInt("count")
                        val materialInfo = resolveMaterialInfo(prim, materialsJson, json)

                        // Builds the GlbMesh with whichever vertex streams survive the
                        // skinned/rigid + flat-shading decisions; material fields are common.
                        fun addMesh(
                            pos: FloatArray, nrm: FloatArray?, uv: FloatArray?, col: FloatArray?,
                            jnt: FloatArray?, wgt: FloatArray?, idx: IntArray?, vc: Int, skinned: Boolean,
                        ) {
                            allMeshes.add(GlbMesh(
                                name = meshName, positions = pos, normals = nrm,
                                texCoords = uv, joints = jnt, weights = wgt,
                                indices = idx, vertexCount = vc,
                                indexCount = idx?.size ?: 0, isSkinned = skinned,
                                skinIndex = if (skinned) skinIdx else -1, meshNodeIndex = nodeIndex,
                                colors = col,
                                materialTextureIndex = materialInfo.textureIndex,
                                materialBaseColorFactor = materialInfo.baseColorFactor,
                                materialDoubleSided = materialInfo.doubleSided,
                                materialNormalTextureIndex = materialInfo.normalTextureIndex,
                                materialNormalScale = materialInfo.normalScale,
                                materialEmissiveFactor = materialInfo.emissiveFactor,
                                materialAlphaMode = materialInfo.alphaMode,
                                materialAlphaCutoff = materialInfo.alphaCutoff,
                            ))
                        }

                        if (isSkinned && attrs.has("JOINTS_0") && attrs.has("WEIGHTS_0")) {
                            val joints = readJointAccessor(attrs.getInt("JOINTS_0"), accessors, bufferViews, binBuf) ?: continue
                            val weights = readFloatAccessor(attrs.getInt("WEIGHTS_0"), accessors, bufferViews, binBuf) ?: continue
                            if (normals == null) {
                                val flat = flatShade(positions, indices, texCoords, colors, joints, weights)
                                addMesh(flat.positions, flat.normals, flat.texCoords, flat.colors,
                                    flat.joints, flat.weights, null, flat.vertexCount, skinned = true)
                            } else {
                                addMesh(positions, normals, texCoords, colors, joints, weights, indices, vertexCount, skinned = true)
                            }
                        } else {
                            val worldPositions = transformPositions(positions, nodeMatrix)
                            if (normals == null) {
                                // flat-shade from already-transformed positions: the face
                                // normals come out in scene space, no transformNormals needed
                                val flat = flatShade(worldPositions, indices, texCoords, colors, null, null)
                                addMesh(flat.positions, flat.normals, flat.texCoords, flat.colors,
                                    null, null, null, flat.vertexCount, skinned = false)
                            } else {
                                addMesh(worldPositions, transformNormals(normals, nodeMatrix), texCoords, colors,
                                    null, null, indices, vertexCount, skinned = false)
                            }
                        }
                    }
                }
            }

            if (allMeshes.isEmpty()) {
                allMeshes.addAll(parseRawMeshes(meshesJson, accessors, bufferViews, binBuf))
            }

            val rigidCount = allMeshes.count { !it.isSkinned }
            val skinnedCount = allMeshes.count { it.isSkinned }
            android.util.Log.d(TAG, "Parsed $rigidCount rigid + $skinnedCount skinned meshes, ${parsedSkins.size} skins, ${animations.size} animations, ${nodeInfos.size} nodes")
            allMeshes.forEach { m ->
                android.util.Log.d(TAG, "  ${m.name}: ${m.vertexCount}v ${if (m.isSkinned) "SKINNED(skin=${m.skinIndex},node=${m.meshNodeIndex})" else "RIGID"} tex=${m.materialTextureIndex}")
            }

            return GlbScene(
                meshes = allMeshes, skins = parsedSkins,
                textureImages = textureImages, textureWraps = textureWraps,
                animations = animations, nodes = nodeInfos,
            )
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Parse error: ${e.message}", e)
            return null
        }
    }

    // ── Node info extraction ──────────────────────────────────

    private fun parseNodeInfos(nodesJson: JSONArray): List<GlbNodeInfo> {
        val count = nodesJson.length()
        val parents = IntArray(count) { -1 }
        // Build parent map
        for (i in 0 until count) {
            val ch = nodesJson.getJSONObject(i).optJSONArray("children") ?: continue
            for (ci in 0 until ch.length()) {
                val c = ch.getInt(ci)
                if (c in 0 until count) parents[c] = i
            }
        }

        val nodes = mutableListOf<GlbNodeInfo>()
        for (i in 0 until count) {
            val node = nodesJson.getJSONObject(i)
            val name = node.optString("name", "node_$i")
            val ch = node.optJSONArray("children")
            val children = if (ch != null) IntArray(ch.length()) { ch.getInt(it) } else IntArray(0)

            val t: FloatArray
            val r: FloatArray
            val s: FloatArray
            val localMatrix: FloatArray

            if (node.has("matrix") && !node.has("translation") && !node.has("rotation") && !node.has("scale")) {
                val m = node.getJSONArray("matrix")
                localMatrix = FloatArray(16) { m.getDouble(it).toFloat() }
                val decomposed = decomposeMatrix(localMatrix)
                t = decomposed.first; r = decomposed.second; s = decomposed.third
            } else {
                t = readFloatArray(node.optJSONArray("translation"), 3, floatArrayOf(0f, 0f, 0f))
                r = readFloatArray(node.optJSONArray("rotation"), 4, floatArrayOf(0f, 0f, 0f, 1f))
                s = readFloatArray(node.optJSONArray("scale"), 3, floatArrayOf(1f, 1f, 1f))
                localMatrix = buildTRSMatrix(t, r, s)
            }

            nodes.add(GlbNodeInfo(
                index = i, name = name, translation = t, rotation = r, scale = s,
                defaultLocalMatrix = localMatrix, children = children, parentIndex = parents[i],
            ))
        }
        return nodes
    }

    // ── Texture extraction ────────────────────────────────────

    private fun extractTextureImages(json: JSONObject, bufferViews: JSONArray, binBuf: ByteBuffer): List<ByteArray> {
        val images = json.optJSONArray("images") ?: return emptyList()
        val result = mutableListOf<ByteArray>()
        for (i in 0 until images.length()) {
            val img = images.getJSONObject(i)
            val bvIdx = img.optInt("bufferView", -1)
            if (bvIdx < 0 || bvIdx >= bufferViews.length()) { result.add(ByteArray(0)); continue }
            val bv = bufferViews.getJSONObject(bvIdx)
            val offset = bv.optInt("byteOffset", 0)
            val length = bv.getInt("byteLength")
            val imageBytes = ByteArray(length)
            binBuf.position(offset); binBuf.get(imageBytes)
            result.add(imageBytes)
            android.util.Log.d(TAG, "Extracted texture image $i: $length bytes (${img.optString("mimeType", "?")})")
        }
        return result
    }

    /** textures[texIdx].source → image index (-1 when unset/out of range). */
    private fun textureImageSource(json: JSONObject, texInfo: JSONObject?): Int {
        val texIdx = texInfo?.optInt("index", -1) ?: -1
        val textures = json.optJSONArray("textures") ?: return -1
        if (texIdx !in 0 until textures.length()) return -1
        if (texInfo != null && texInfo.optInt("texCoord", 0) != 0) {
            android.util.Log.w(TAG, "Texture uses TEXCOORD_${texInfo.optInt("texCoord")} — only set 0 is supported, sampling may be wrong")
        }
        return textures.getJSONObject(texIdx).optInt("source", -1)
    }

    private fun resolveMaterialInfo(prim: JSONObject, materialsJson: JSONArray?, json: JSONObject): MaterialInfo {
        val matIdx = prim.optInt("material", -1)
        if (matIdx < 0 || materialsJson == null || matIdx >= materialsJson.length()) return MaterialInfo()
        val mat = materialsJson.getJSONObject(matIdx)
        val ext = mat.optJSONObject("extensions")
        val pbr = mat.optJSONObject("pbrMetallicRoughness")
        // Legacy KHR_materials_pbrSpecularGlossiness assets keep their color in
        // diffuseFactor/diffuseTexture — fall back to those when core pbr is absent.
        val specGloss = ext?.optJSONObject("KHR_materials_pbrSpecularGlossiness")
        val bcfJson = pbr?.optJSONArray("baseColorFactor") ?: specGloss?.optJSONArray("diffuseFactor")
        val bcf = FloatArray(4) { idx ->
            if (bcfJson != null && idx < bcfJson.length()) bcfJson.getDouble(idx).toFloat() else 1f
        }
        var textureIndex = textureImageSource(json, pbr?.optJSONObject("baseColorTexture"))
        if (textureIndex < 0) textureIndex = textureImageSource(json, specGloss?.optJSONObject("diffuseTexture"))
        val normalTextureInfo = mat.optJSONObject("normalTexture")
        val normalTextureIndex = textureImageSource(json, normalTextureInfo)
        val normalScale = normalTextureInfo?.optDouble("scale", 1.0)?.toFloat() ?: 1f
        val efJson = mat.optJSONArray("emissiveFactor")
        val emissiveStrength = ext?.optJSONObject("KHR_materials_emissive_strength")
            ?.optDouble("emissiveStrength", 1.0)?.toFloat() ?: 1f
        val emissive = FloatArray(3) { idx ->
            (if (efJson != null && idx < efJson.length()) efJson.getDouble(idx).toFloat() else 0f) * emissiveStrength
        }
        val alphaMode = when (mat.optString("alphaMode", "OPAQUE")) {
            "MASK" -> 1; "BLEND" -> 2; else -> 0
        }
        return MaterialInfo(
            textureIndex = textureIndex, baseColorFactor = bcf,
            doubleSided = mat.optBoolean("doubleSided", false),
            normalTextureIndex = normalTextureIndex,
            normalScale = normalScale,
            emissiveFactor = emissive,
            alphaMode = alphaMode,
            alphaCutoff = mat.optDouble("alphaCutoff", 0.5).toFloat(),
        )
    }

    /** Effective sampler wrap modes per image index ([wrapS, wrapT], glTF enums; 10497=REPEAT). */
    private fun extractTextureWraps(json: JSONObject, imageCount: Int): List<IntArray> {
        val wraps = MutableList(imageCount) { intArrayOf(10497, 10497) }
        val textures = json.optJSONArray("textures") ?: return wraps
        val samplers = json.optJSONArray("samplers")
        for (i in 0 until textures.length()) {
            val tex = textures.getJSONObject(i)
            val src = tex.optInt("source", -1)
            val smpIdx = tex.optInt("sampler", -1)
            if (src !in 0 until imageCount || smpIdx < 0 || samplers == null || smpIdx >= samplers.length()) continue
            val smp = samplers.getJSONObject(smpIdx)
            wraps[src] = intArrayOf(smp.optInt("wrapS", 10497), smp.optInt("wrapT", 10497))
        }
        return wraps
    }

    // ── Skin parsing ────────────────────────────────────────

    private fun parseSkin(
        skinIndex: Int, skinsJson: JSONArray, accessors: JSONArray,
        bufferViews: JSONArray, binBuf: ByteBuffer,
        worldTransforms: Array<FloatArray>, nodesJson: JSONArray,
    ): GlbSkin {
        val skinObj = skinsJson.getJSONObject(skinIndex)
        val jointsArr = skinObj.getJSONArray("joints")
        val jointCount = jointsArr.length()
        val jointNodeIndices = IntArray(jointCount) { jointsArr.getInt(it) }

        val ibmAccessorIdx = skinObj.getInt("inverseBindMatrices")
        val ibmFlat = readFloatAccessor(ibmAccessorIdx, accessors, bufferViews, binBuf)
            ?: FloatArray(jointCount * 16)

        // Compute bind-pose joint matrices
        val jointMatrices = FloatArray(jointCount * 16)
        for (j in 0 until jointCount) {
            val jointNodeIdx = jointNodeIndices[j]
            val jointGlobal = if (jointNodeIdx in worldTransforms.indices) worldTransforms[jointNodeIdx] else identityMatrix()
            val ibm = FloatArray(16)
            System.arraycopy(ibmFlat, j * 16, ibm, 0, 16)
            val result = FloatArray(16)
            Matrix.multiplyMM(result, 0, jointGlobal, 0, ibm, 0)
            System.arraycopy(result, 0, jointMatrices, j * 16, 16)
        }

        if (skinIndex == 0 && jointCount > 0) {
            val jn = jointNodeIndices[0]
            val wt = if (jn in worldTransforms.indices) worldTransforms[jn] else identityMatrix()
            android.util.Log.d(TAG, "DBG j0 node=$jn world=[${(0 until 16).joinToString(","){ "%.2f".format(wt[it]) }}]")
            android.util.Log.d(TAG, "DBG j0 ibm=[${(0 until 16).joinToString(","){ "%.2f".format(ibmFlat[it]) }}]")
            android.util.Log.d(TAG, "DBG j0 bind=[${(0 until 16).joinToString(","){ "%.2f".format(jointMatrices[it]) }}]")
        }
        android.util.Log.d(TAG, "Parsed skin $skinIndex: $jointCount joints")
        return GlbSkin(
            jointCount = jointCount, jointNodeIndices = jointNodeIndices,
            inverseBindMatrices = ibmFlat, jointMatrices = jointMatrices,
        )
    }

    // ── Animation parsing ────────────────────────────────────

    private fun parseAnimations(json: JSONObject, accessors: JSONArray, bufferViews: JSONArray, binBuf: ByteBuffer): List<GlbAnimation> {
        val animsJson = json.optJSONArray("animations") ?: return emptyList()
        val result = mutableListOf<GlbAnimation>()

        for (ai in 0 until animsJson.length()) {
            val animObj = animsJson.getJSONObject(ai)
            val name = animObj.optString("name", "animation_$ai")

            val samplersJson = animObj.getJSONArray("samplers")
            val samplers = mutableListOf<GlbAnimationSampler>()
            var maxTime = 0f

            for (si in 0 until samplersJson.length()) {
                val samplerObj = samplersJson.getJSONObject(si)
                val inputIdx = samplerObj.getInt("input")
                val outputIdx = samplerObj.getInt("output")
                val interpolation = samplerObj.optString("interpolation", "LINEAR")

                val times = readFloatAccessor(inputIdx, accessors, bufferViews, binBuf) ?: floatArrayOf()
                val rawValues = readFloatAccessor(outputIdx, accessors, bufferViews, binBuf) ?: floatArrayOf()

                val outputAcc = accessors.getJSONObject(outputIdx)
                val componentCount = when (outputAcc.getString("type")) {
                    "VEC4" -> 4; "VEC3" -> 3; "SCALAR" -> 1; else -> 3
                }

                // For CUBICSPLINE, extract just the values (skip in/out tangents)
                val values = if (interpolation == "CUBICSPLINE" && times.isNotEmpty() &&
                    rawValues.size == times.size * 3 * componentCount) {
                    val trimmed = FloatArray(times.size * componentCount)
                    for (k in times.indices) {
                        System.arraycopy(rawValues, (3 * k + 1) * componentCount, trimmed, k * componentCount, componentCount)
                    }
                    trimmed
                } else rawValues

                if (times.isNotEmpty()) maxTime = maxOf(maxTime, times.last())
                samplers.add(GlbAnimationSampler(times = times, values = values, interpolation = interpolation, componentCount = componentCount))
            }

            val channelsJson = animObj.getJSONArray("channels")
            val channels = mutableListOf<GlbAnimationChannel>()
            for (ci in 0 until channelsJson.length()) {
                val chObj = channelsJson.getJSONObject(ci)
                val target = chObj.getJSONObject("target")
                val targetNode = target.optInt("node", -1)
                val targetPath = target.getString("path")
                if (targetNode >= 0 && targetPath in listOf("translation", "rotation", "scale")) {
                    channels.add(GlbAnimationChannel(samplerIndex = chObj.getInt("sampler"), targetNode = targetNode, targetPath = targetPath))
                }
            }

            result.add(GlbAnimation(name = name, channels = channels, samplers = samplers, duration = maxTime))
            android.util.Log.d(TAG, "Parsed animation [$ai] '$name': ${channels.size} channels, duration=${maxTime}s")
        }
        return result
    }

    // ── Joint index accessor ──────────────────────────────────

    private fun readJointAccessor(accessorIdx: Int, accessors: JSONArray, bufferViews: JSONArray, binBuf: ByteBuffer): FloatArray? {
        val accessor = accessors.getJSONObject(accessorIdx)
        val bvIdx = accessor.getInt("bufferView")
        val bv = bufferViews.getJSONObject(bvIdx)
        val byteOffset = bv.optInt("byteOffset", 0) + accessor.optInt("byteOffset", 0)
        val count = accessor.getInt("count")
        val ct = accessor.getInt("componentType")
        val components = 4
        val compSize = when (ct) { UNSIGNED_BYTE -> 1; UNSIGNED_SHORT -> 2; UNSIGNED_INT, FLOAT -> 4; else -> return null }
        val byteStride = bv.optInt("byteStride", components * compSize)
        val floats = FloatArray(count * components)
        for (i in 0 until count) {
            binBuf.position(byteOffset + i * byteStride)
            for (c in 0 until components) {
                floats[i * components + c] = when (ct) {
                    UNSIGNED_BYTE -> (binBuf.get().toInt() and 0xFF).toFloat()
                    UNSIGNED_SHORT -> (binBuf.getShort().toInt() and 0xFFFF).toFloat()
                    UNSIGNED_INT -> binBuf.getInt().toFloat()
                    FLOAT -> binBuf.getFloat() // non-spec but seen in the wild
                    else -> 0f
                }
            }
        }
        return floats
    }

    // ── Fallback mesh parsing ─────────────────────────────────

    private fun parseRawMeshes(meshesJson: JSONArray, accessors: JSONArray, bufferViews: JSONArray, binBuf: ByteBuffer): List<GlbMesh> {
        val meshes = mutableListOf<GlbMesh>()
        for (mi in 0 until meshesJson.length()) {
            val meshObj = meshesJson.getJSONObject(mi)
            val meshName = meshObj.optString("name", "mesh_$mi")
            val primitives = meshObj.optJSONArray("primitives") ?: continue
            for (pi in 0 until primitives.length()) {
                val prim = primitives.getJSONObject(pi)
                val mode = prim.optInt("mode", GL_TRIANGLES)
                if (mode != GL_TRIANGLES) {
                    android.util.Log.w(TAG, "Skipping primitive with mode=$mode (only TRIANGLES supported)")
                    continue
                }
                val attrs = prim.getJSONObject("attributes")
                val posIdx = attrs.getInt("POSITION")
                val positions = readFloatAccessor(posIdx, accessors, bufferViews, binBuf) ?: continue
                val normals = if (attrs.has("NORMAL")) readFloatAccessor(attrs.getInt("NORMAL"), accessors, bufferViews, binBuf) else null
                val indices = if (prim.has("indices")) readIndexAccessor(prim.getInt("indices"), accessors, bufferViews, binBuf) else null
                val name = if (primitives.length() > 1) "$meshName#$pi" else meshName
                if (normals == null) {
                    val flat = flatShade(positions, indices, null, null, null, null)
                    meshes.add(GlbMesh(name = name, positions = flat.positions, normals = flat.normals,
                        texCoords = null, joints = null, weights = null, indices = null,
                        vertexCount = flat.vertexCount, indexCount = 0))
                } else meshes.add(GlbMesh(name = name, positions = positions, normals = normals,
                    texCoords = null, joints = null, weights = null, indices = indices,
                    vertexCount = accessors.getJSONObject(posIdx).getInt("count"), indexCount = indices?.size ?: 0))
            }
        }
        return meshes
    }

    // ── World transform computation ──────────────────────────

    private fun readSceneRoots(json: JSONObject): IntArray? {
        val scenes = json.optJSONArray("scenes") ?: return null
        val si = json.optInt("scene", 0)
        if (si !in 0 until scenes.length()) return null
        val rootNodes = scenes.getJSONObject(si).optJSONArray("nodes") ?: return null
        return IntArray(rootNodes.length()) { rootNodes.getInt(it) }
    }

    private fun computeWorldTransforms(nodesJson: JSONArray, rootNodes: IntArray?): Array<FloatArray> {
        val n = nodesJson.length()
        val wt = Array(n) { identityMatrix() }
        val visited = BooleanArray(n)
        val parents = IntArray(n) { -1 }
        for (i in 0 until n) {
            val ch = nodesJson.getJSONObject(i).optJSONArray("children") ?: continue
            for (ci in 0 until ch.length()) { val c = ch.getInt(ci); if (c in 0 until n) parents[c] = i }
        }
        val roots = rootNodes?.takeIf { it.isNotEmpty() } ?: parents.withIndex().filter { it.value == -1 }.map { it.index }.toIntArray()
        val id = identityMatrix()
        for (r in roots) { if (r in 0 until n) walkNodeTransforms(r, id, nodesJson, wt, visited) }
        for (i in 0 until n) { if (!visited[i]) walkNodeTransforms(i, id, nodesJson, wt, visited) }
        return wt
    }

    private fun walkNodeTransforms(ni: Int, pw: FloatArray, nodes: JSONArray, wt: Array<FloatArray>, vis: BooleanArray) {
        if (vis[ni]) return
        val node = nodes.getJSONObject(ni)
        val local = buildLocalMatrix(node)
        val world = FloatArray(16); Matrix.multiplyMM(world, 0, pw, 0, local, 0)
        wt[ni] = world; vis[ni] = true
        val ch = node.optJSONArray("children") ?: return
        for (ci in 0 until ch.length()) { val c = ch.getInt(ci); if (c in 0 until nodes.length()) walkNodeTransforms(c, world, nodes, wt, vis) }
    }

    // ── Matrix utilities ──────────────────────────────────────

    private fun buildLocalMatrix(node: JSONObject): FloatArray {
        if (node.has("matrix")) {
            val m = node.getJSONArray("matrix"); return FloatArray(16) { m.getDouble(it).toFloat() }
        }
        val t = readFloatArray(node.optJSONArray("translation"), 3, floatArrayOf(0f, 0f, 0f))
        val r = readFloatArray(node.optJSONArray("rotation"), 4, floatArrayOf(0f, 0f, 0f, 1f))
        val s = readFloatArray(node.optJSONArray("scale"), 3, floatArrayOf(1f, 1f, 1f))
        return buildTRSMatrix(t, r, s)
    }

    private fun buildTRSMatrix(t: FloatArray, r: FloatArray, s: FloatArray): FloatArray {
        val tm = identityMatrix().also { it[12] = t[0]; it[13] = t[1]; it[14] = t[2] }
        val rm = quaternionToMatrix(r[0], r[1], r[2], r[3])
        val sm = identityMatrix().also { it[0] = s[0]; it[5] = s[1]; it[10] = s[2] }
        val rs = FloatArray(16); Matrix.multiplyMM(rs, 0, rm, 0, sm, 0)
        val result = FloatArray(16); Matrix.multiplyMM(result, 0, tm, 0, rs, 0)
        return result
    }

    /** Decompose a 4x4 matrix into translation, rotation (quaternion), scale. */
    private fun decomposeMatrix(m: FloatArray): Triple<FloatArray, FloatArray, FloatArray> {
        val t = floatArrayOf(m[12], m[13], m[14])
        // Column lengths = scale
        val sx = sqrt(m[0]*m[0] + m[1]*m[1] + m[2]*m[2])
        val sy = sqrt(m[4]*m[4] + m[5]*m[5] + m[6]*m[6])
        val sz = sqrt(m[8]*m[8] + m[9]*m[9] + m[10]*m[10])
        if (sx < 1e-6f || sy < 1e-6f || sz < 1e-6f) {
            return Triple(t, floatArrayOf(0f, 0f, 0f, 1f), floatArrayOf(1f, 1f, 1f))
        }
        val s = floatArrayOf(sx, sy, sz)
        // Normalized rotation matrix columns
        val r00 = m[0]/sx; val r10 = m[1]/sx; val r20 = m[2]/sx
        val r01 = m[4]/sy; val r11 = m[5]/sy; val r21 = m[6]/sy
        val r02 = m[8]/sz; val r12 = m[9]/sz; val r22 = m[10]/sz
        // Check determinant for negative scale
        val det = r00*(r11*r22-r12*r21) - r01*(r10*r22-r12*r20) + r02*(r10*r21-r11*r20)
        if (det < 0) { s[0] = -s[0] }
        // Rotation matrix to quaternion
        val trace = r00 + r11 + r22
        val q = if (trace > 0) {
            val w4 = sqrt(trace + 1f) * 2f
            floatArrayOf((r21-r12)/w4, (r02-r20)/w4, (r10-r01)/w4, w4/4f)
        } else if (r00 > r11 && r00 > r22) {
            val x4 = sqrt(1f+r00-r11-r22) * 2f
            floatArrayOf(x4/4f, (r01+r10)/x4, (r02+r20)/x4, (r21-r12)/x4)
        } else if (r11 > r22) {
            val y4 = sqrt(1f+r11-r00-r22) * 2f
            floatArrayOf((r01+r10)/y4, y4/4f, (r12+r21)/y4, (r02-r20)/y4)
        } else {
            val z4 = sqrt(1f+r22-r00-r11) * 2f
            floatArrayOf((r02+r20)/z4, (r12+r21)/z4, z4/4f, (r10-r01)/z4)
        }
        val ql = sqrt(q[0]*q[0]+q[1]*q[1]+q[2]*q[2]+q[3]*q[3])
        if (ql > 0f) { q[0]/=ql; q[1]/=ql; q[2]/=ql; q[3]/=ql }
        return Triple(t, q, s)
    }

    internal fun quaternionToMatrix(x: Float, y: Float, z: Float, w: Float): FloatArray {
        val len = sqrt(x*x + y*y + z*z + w*w).takeIf { it > 0f } ?: 1f
        val qx = x/len; val qy = y/len; val qz = z/len; val qw = w/len
        val xx = qx*qx; val yy = qy*qy; val zz = qz*qz
        val xy = qx*qy; val xz = qx*qz; val yz = qy*qz
        val wx = qw*qx; val wy = qw*qy; val wz = qw*qz
        return floatArrayOf(
            1f-2f*(yy+zz), 2f*(xy+wz), 2f*(xz-wy), 0f,
            2f*(xy-wz), 1f-2f*(xx+zz), 2f*(yz+wx), 0f,
            2f*(xz+wy), 2f*(yz-wx), 1f-2f*(xx+yy), 0f,
            0f, 0f, 0f, 1f)
    }

    internal fun identityMatrix() = floatArrayOf(1f,0f,0f,0f, 0f,1f,0f,0f, 0f,0f,1f,0f, 0f,0f,0f,1f)

    private fun readFloatArray(src: JSONArray?, size: Int, def: FloatArray): FloatArray {
        if (src == null) return def.copyOf()
        return FloatArray(size) { if (it < src.length()) src.getDouble(it).toFloat() else def[it] }
    }

    private fun transformPositions(pos: FloatArray, m: FloatArray): FloatArray {
        val r = FloatArray(pos.size); var i = 0
        while (i < pos.size) {
            val x = pos[i]; val y = pos[i+1]; val z = pos[i+2]
            r[i] = m[0]*x+m[4]*y+m[8]*z+m[12]; r[i+1] = m[1]*x+m[5]*y+m[9]*z+m[13]; r[i+2] = m[2]*x+m[6]*y+m[10]*z+m[14]
            i += 3
        }; return r
    }

    private class FlatShaded(
        val positions: FloatArray, val normals: FloatArray, val texCoords: FloatArray?,
        val colors: FloatArray?, val joints: FloatArray?, val weights: FloatArray?, val vertexCount: Int,
    )

    // glTF spec: a primitive without NORMAL must be rendered flat-shaded (the sample
    // Fox ships none — a constant fallback normal lights it in blotchy per-joint
    // patches). Flat needs un-shared corners, so de-index every vertex stream and
    // emit the face normal at each of the face's three corners.
    private fun flatShade(
        positions: FloatArray, indices: IntArray?, texCoords: FloatArray?,
        colors: FloatArray?, joints: FloatArray?, weights: FloatArray?,
    ): FlatShaded {
        val idx = indices ?: IntArray(positions.size / 3) { it }
        val triCount = idx.size / 3
        val vc = triCount * 3
        val pos = FloatArray(vc * 3)
        val nrm = FloatArray(vc * 3)
        val uv = if (texCoords != null) FloatArray(vc * 2) else null
        val col = if (colors != null) FloatArray(vc * 4) else null
        val jnt = if (joints != null) FloatArray(vc * 4) else null
        val wgt = if (weights != null) FloatArray(vc * 4) else null
        var o = 0
        for (t in 0 until triCount) {
            val i0 = idx[t * 3]; val i1 = idx[t * 3 + 1]; val i2 = idx[t * 3 + 2]
            val ax = positions[i1*3] - positions[i0*3]; val ay = positions[i1*3+1] - positions[i0*3+1]; val az = positions[i1*3+2] - positions[i0*3+2]
            val bx = positions[i2*3] - positions[i0*3]; val by = positions[i2*3+1] - positions[i0*3+1]; val bz = positions[i2*3+2] - positions[i0*3+2]
            var nx = ay*bz - az*by; var ny = az*bx - ax*bz; var nz = ax*by - ay*bx
            val l = sqrt(nx*nx + ny*ny + nz*nz)
            if (l > 0f) { nx /= l; ny /= l; nz /= l }
            for (src in intArrayOf(i0, i1, i2)) {
                pos[o*3] = positions[src*3]; pos[o*3+1] = positions[src*3+1]; pos[o*3+2] = positions[src*3+2]
                nrm[o*3] = nx; nrm[o*3+1] = ny; nrm[o*3+2] = nz
                if (uv != null) { uv[o*2] = texCoords!![src*2]; uv[o*2+1] = texCoords[src*2+1] }
                if (col != null) for (k in 0..3) col[o*4+k] = colors!![src*4+k]
                if (jnt != null) for (k in 0..3) jnt[o*4+k] = joints!![src*4+k]
                if (wgt != null) for (k in 0..3) wgt[o*4+k] = weights!![src*4+k]
                o++
            }
        }
        return FlatShaded(pos, nrm, uv, col, jnt, wgt, vc)
    }

    private fun transformNormals(normals: FloatArray, matrix: FloatArray): FloatArray {
        val inv = FloatArray(16); val nm = FloatArray(16)
        if (Matrix.invertM(inv, 0, matrix, 0)) Matrix.transposeM(nm, 0, inv, 0)
        else System.arraycopy(matrix, 0, nm, 0, 16)
        val r = FloatArray(normals.size); var i = 0
        while (i < normals.size) {
            val x = normals[i]; val y = normals[i+1]; val z = normals[i+2]
            var nx = nm[0]*x+nm[4]*y+nm[8]*z; var ny = nm[1]*x+nm[5]*y+nm[9]*z; var nz = nm[2]*x+nm[6]*y+nm[10]*z
            val l = sqrt(nx*nx+ny*ny+nz*nz); if (l > 0f) { nx/=l; ny/=l; nz/=l }
            r[i] = nx; r[i+1] = ny; r[i+2] = nz; i += 3
        }; return r
    }

    private fun componentByteSize(ct: Int) = when (ct) {
        BYTE, UNSIGNED_BYTE -> 1; SHORT, UNSIGNED_SHORT -> 2; UNSIGNED_INT, FLOAT -> 4; else -> 0
    }

    /** Read one component at the buffer's current position, dequantizing per the glTF spec. */
    private fun readComponentAsFloat(bin: ByteBuffer, ct: Int, normalized: Boolean): Float = when (ct) {
        FLOAT -> bin.getFloat()
        UNSIGNED_BYTE -> { val v = (bin.get().toInt() and 0xFF).toFloat(); if (normalized) v / 255f else v }
        BYTE -> { val v = bin.get().toFloat(); if (normalized) maxOf(v / 127f, -1f) else v }
        UNSIGNED_SHORT -> { val v = (bin.getShort().toInt() and 0xFFFF).toFloat(); if (normalized) v / 65535f else v }
        SHORT -> { val v = bin.getShort().toFloat(); if (normalized) maxOf(v / 32767f, -1f) else v }
        UNSIGNED_INT -> bin.getInt().toFloat()
        else -> 0f
    }

    /**
     * Read any accessor into floats: every glTF component type (with `normalized`
     * dequantization — covers quantized UVs/weights/colors and KHR_mesh_quantization),
     * byteStride-aware, accessors without a bufferView (spec: zeros), and sparse overlays.
     */
    private fun readFloatAccessor(idx: Int, accessors: JSONArray, bvs: JSONArray, bin: ByteBuffer): FloatArray? {
        val acc = accessors.getJSONObject(idx)
        val count = acc.getInt("count")
        val ct = acc.getInt("componentType")
        val normalized = acc.optBoolean("normalized", false)
        val comps = when (acc.getString("type")) { "SCALAR"->1; "VEC2"->2; "VEC3"->3; "VEC4"->4; "MAT4"->16; else -> return null }
        val cs = componentByteSize(ct)
        if (cs == 0) { android.util.Log.w(TAG, "Unsupported accessor componentType $ct"); return null }
        val floats = FloatArray(count * comps)
        val bvIdx = acc.optInt("bufferView", -1)
        if (bvIdx >= 0) {
            val bv = bvs.getJSONObject(bvIdx)
            val off = bv.optInt("byteOffset", 0) + acc.optInt("byteOffset", 0)
            val stride = bv.optInt("byteStride", comps * cs)
            for (i in 0 until count) {
                bin.position(off + i * stride)
                for (c in 0 until comps) floats[i*comps+c] = readComponentAsFloat(bin, ct, normalized)
            }
        } // no bufferView → all zeros until the sparse overlay below
        val sparse = acc.optJSONObject("sparse")
        if (sparse != null) {
            try {
                val sCount = sparse.getInt("count")
                val sIdx = sparse.getJSONObject("indices")
                val sVal = sparse.getJSONObject("values")
                val iBv = bvs.getJSONObject(sIdx.getInt("bufferView"))
                val iCt = sIdx.getInt("componentType")
                val iOff = iBv.optInt("byteOffset", 0) + sIdx.optInt("byteOffset", 0)
                val vBv = bvs.getJSONObject(sVal.getInt("bufferView"))
                val vOff = vBv.optInt("byteOffset", 0) + sVal.optInt("byteOffset", 0)
                for (k in 0 until sCount) {
                    bin.position(iOff + k * componentByteSize(iCt))
                    val target = when (iCt) {
                        UNSIGNED_BYTE -> bin.get().toInt() and 0xFF
                        UNSIGNED_SHORT -> bin.getShort().toInt() and 0xFFFF
                        else -> bin.getInt()
                    }
                    bin.position(vOff + k * comps * cs)
                    for (c in 0 until comps) {
                        val dst = target * comps + c
                        if (dst < floats.size) floats[dst] = readComponentAsFloat(bin, ct, normalized)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Sparse accessor overlay failed: ${e.message}")
            }
        }
        return floats
    }

    /** COLOR_0 → vec4 floats (VEC3 colors get alpha=1). Any component type/normalization. */
    private fun readColorAccessor(idx: Int, accessors: JSONArray, bvs: JSONArray, bin: ByteBuffer): FloatArray? {
        val acc = accessors.getJSONObject(idx)
        val raw = readFloatAccessor(idx, accessors, bvs, bin) ?: return null
        if (acc.getString("type") != "VEC3") return raw
        val count = raw.size / 3
        val out = FloatArray(count * 4)
        for (i in 0 until count) {
            out[i*4] = raw[i*3]; out[i*4+1] = raw[i*3+1]; out[i*4+2] = raw[i*3+2]; out[i*4+3] = 1f
        }
        return out
    }

    private fun readIndexAccessor(idx: Int, accessors: JSONArray, bvs: JSONArray, bin: ByteBuffer): IntArray? {
        val acc = accessors.getJSONObject(idx)
        val bv = bvs.getJSONObject(acc.getInt("bufferView"))
        val ct = acc.getInt("componentType")
        val cs = when (ct) { UNSIGNED_BYTE->1; UNSIGNED_SHORT->2; UNSIGNED_INT->4; else -> return null }
        val off = bv.optInt("byteOffset", 0) + acc.optInt("byteOffset", 0)
        val count = acc.getInt("count")
        val stride = bv.optInt("byteStride", cs)
        val indices = IntArray(count)
        for (i in 0 until count) {
            bin.position(off + i * stride)
            indices[i] = when (ct) { UNSIGNED_BYTE -> bin.get().toInt() and 0xFF; UNSIGNED_SHORT -> bin.getShort().toInt() and 0xFFFF; UNSIGNED_INT -> bin.getInt(); else -> 0 }
        }
        return indices
    }
}
