# How it works

A walk through the parts of `SkeletalLayerHost.kt` that were not obvious, in the order
you hit them if you build this yourself. Everything here is Android / OpenGL ES 3.0.

## Where the code runs

Mapbox's `CustomLayerHost` gives you four callbacks on the map's own GL thread:

| Callback | When | What we do |
| --- | --- | --- |
| `initialize()` | Layer added, and again after a context loss | Compile shaders, upload meshes and textures |
| `render(parameters)` | Every frame the layer is visible | Evaluate animation, build the MVP, draw |
| `contextLost()` | GL context destroyed | Drop GPU handles, keep the parsed scene |
| `deinitialize()` | Layer removed | Delete programs, buffers, textures |

The important consequence: **you are a guest in the map's GL context**, drawing into the
map's framebuffer with the map's depth buffer already populated. That is the whole
reason occlusion works, and it is also why every piece of GL state you touch has to be
restored.

The parsed scene is kept on the CPU across `contextLost()`, so re-initialising is a
re-upload rather than a re-parse. Parsed scenes are also cached per asset path in the
module, so switching a character back and forth costs nothing after the first load.

## Coordinate systems, and the mistake that makes your character thin

`render()` hands you `parameters.projectionMatrix`, which expects positions in Mapbox's
world space. Getting into it:

```kotlin
val zoomScale = 2.0.pow(parameters.zoom)
val center = Projection.project(Point.fromLngLat(anchorLng, anchorLat), zoomScale)
val metersPerWorldPixel = cos(anchorLat * PI / 180.0) * 2.0 * PI * 6378137.0 / (512.0 * zoomScale)
val pixelsPerMeter = 1.0 / metersPerWorldPixel
```

Here is the trap. That world space is **anisotropic**: `x` and `y` are Mercator world
pixels, but `z` — altitude — is metres. Scale all three axes by `pixelsPerMeter` and your
character comes out roughly 3.5× too tall at typical zoom, which reads as a stretched,
skinny figure rather than an obviously wrong one. So you scale the horizontal axes into
pixels and the vertical axis into metres:

```kotlin
val xyScale = (pixelsPerMeter * sizeMeters).toFloat()
val zScale = sizeMeters.toFloat()
Matrix.scaleM(scaleRotate, 0, xyScale, xyScale, zScale)
```

### Keep the translation in double precision

At zoom 20 the Mercator world is about 500 million pixels across, and the character's
position within it does not survive a float. Fold the translation into the projection
matrix in `double`, then downcast the finished matrix:

```kotlin
val projTransD = DoubleArray(16) { projD[it] }
for (row in 0..3) {
  projTransD[12 + row] += projD[row] * cxD + projD[4 + row] * cyD
}
val projTransF = FloatArray(16) { projTransD[it].toFloat() }
```

Building a float translation matrix and multiplying gives visible positional jitter at
high zoom. Folding first, in double, does not.

### Orientation

glTF is Y-up with `+Z` forward. Mapbox's world is Z-up. One rotation reconciles them:

```kotlin
Matrix.rotateM(rotOnly, 0, curHeadingDeg + headingOffset, 0f, 0f, 1f) // heading about world Z
Matrix.rotateM(rotOnly, 0, 90f, 1f, 0f, 0f)                           // stand the model up
```

After the +90° about X, glTF's `+Z` forward lands on world `−Y`, which is north. So a
model authored to the glTF convention needs **no** base heading offset — heading 0 faces
north. Models authored facing the other way need `headingOffset = 180`, which is the
case for the soldier in the example.

## Depth: the part that actually makes this worth doing

Enabling `GL_DEPTH_TEST` is not enough. The map renders its 3D content into a specific
sub-range of the depth buffer, handed to you as `parameters.depthRange`. Draw outside
that window and every building wins the depth test regardless of where it actually is,
so your character is permanently hidden.

```kotlin
GLES30.glEnable(GLES30.GL_DEPTH_TEST)
GLES30.glDepthFunc(GLES30.GL_LEQUAL)
GLES30.glDepthMask(true)
val dr = parameters.depthRange
val prevRange = FloatArray(2)
GLES30.glGetFloatv(GLES30.GL_DEPTH_RANGE, prevRange, 0)
if (dr != null) GLES30.glDepthRangef(dr.min, dr.max)
renderMeshes(...)
if (dr != null) GLES30.glDepthRangef(prevRange[0], prevRange[1])
```

The custom-layer projection does not necessarily use the same near/far planes as the
map's tile matrices, so on some devices the result can still sit slightly behind where
it should. `setSkeletalDepthBias()` exposes a clip-space nudge toward the camera for
that case:

```glsl
gl_Position.z -= u_depthBias * gl_Position.w;
```

It defaults to `0`. Reach for it only if a specific device misbehaves; too much bias
punches the character through buildings it is genuinely behind.

Transparency follows the usual ordering: `OPAQUE` and alpha-`MASK` meshes are drawn
first with depth writes on, then `BLEND` materials with `glDepthMask(false)`.

## The camera-anchor flicker

If the camera follows the character, the obvious implementation — draw at the last
coordinate JS pushed — has a subtle failure. The camera position for frame *N* and the
model position for frame *N* come from different places and can differ by a frame's
worth of movement. The character appears to vibrate against the map while walking.

The fix is to stop treating the followed character's position as an input at all. The
camera is centred on it by definition, so use *this frame's* camera centre:

```kotlin
val anchorLng = if (followCamera) parameters.longitude else curLng
val anchorLat = if (followCamera) parameters.latitude else curLat
```

Now model and map are derived from the same number and cannot disagree. This is only
valid for the character the camera is actually centred on — hence
`setSkeletalFollowCamera(layerId, false)` for everyone else.

## Animation on the render thread

Clip evaluation happens in `render()`, driven by a `System.nanoTime()` delta clamped to
100 ms so a stall does not teleport the animation. Per frame:

1. Evaluate the active clip's TRS channels into preallocated buffers (binary search over
   keyframe times, `slerp` for rotations, `STEP` honoured).
2. If cross-fading, evaluate the outgoing clip too and blend with a smoothstep weight.
3. Compose local matrices, walk the node hierarchy to globals.
4. `jointMatrix = jointGlobal × inverseBindMatrix`, written into the skin's array.
5. Upload as a `mat4[64]` uniform; skin in the vertex shader.

All the per-frame buffers are allocated once at load, since this runs 60 times a second
and the GL thread is not a place to make garbage.

Heading is smoothed on the native side with a frame-rate-independent exponential:

```kotlin
curHeadingDeg += dh * (1f - exp(-HEADING_RESPONSE * dt))
```

That way JS can push a raw target heading at whatever rate it likes and the turn still
looks smooth.

Joint indices are clamped in the shader, because indexing past a uniform array is
undefined behaviour on some GPUs:

```glsl
const int MAXJ = 64 - 1;
int j0 = clamp(int(a_joints.x), 0, MAXJ);
```

A rig with more than 64 joints will deform wrong, but it will not crash.

## Shading

The character has to sit in the same light as the basemap or it reads as a sticker.
Four presets — `day`, `dawn`, `dusk`, `night` — mirror the Standard style's
`lightPreset`, each a hemisphere ambient plus a directional sun in world Z-up space.

Colour management matters more than the rig does. Base colour textures are sRGB, while
`baseColorFactor` and vertex colours are linear, so:

```glsl
vec3 albedo = srgbToLinear(base.rgb) * u_baseColorFactor.rgb * v_color.rgb;
// … light in linear …
c = c / (1.0 + 0.08 * c);          // soft highlight rolloff
fragColor = vec4(linearToSrgb(c), alpha);
```

Skipping the sRGB round trip gives you a character that is too dark in shadow and too
saturated in light, next to a basemap that got it right.

### Normal maps without tangents

Most game GLBs ship a normal map but no `TANGENT` attribute, so the tangent frame is
reconstructed per-pixel from screen-space derivatives of position and UV
(Christian Schüler's method):

```glsl
vec3 dp1 = dFdx(P); vec3 dp2 = dFdy(P);
vec2 duv1 = dFdx(uv); vec2 duv2 = dFdy(uv);
vec3 dp2perp = cross(dp2, N); vec3 dp1perp = cross(N, dp1);
vec3 T = dp2perp * duv1.x + dp1perp * duv2.x;
vec3 B = dp2perp * duv1.y + dp1perp * duv2.y;
```

Two device-only bugs came out of this, both worth knowing:

**`highp` is mandatory, not a preference.** Those UV deltas are around `1e-5` when the
camera is close, and their squares underflow fp16. Adreno GPUs honour `mediump` as fp16
where the Android emulator does not, so at `mediump` this looks perfect on the emulator
and blows the normals out into white patches on a real phone. The fragment shader
declares `precision highp float;` for exactly this reason.

**Ambient uses the geometric normal, not the perturbed one.** Feeding the perturbed
normal into the hemisphere term lowers the average `n.z` across the model, which
visibly dulls it under ambient-heavy daylight. A normal map should add detail to the
sun term, not darken the whole character:

```glsl
vec3 ambient = mix(u_ambientGround, u_ambientSky, clamp(gn.z * 0.5 + 0.5, 0.0, 1.0));
```

## Sharing GL state with the map

Every state change is saved and restored around the draw — depth test, cull, blend, and
the depth range — and both texture units are unbound afterwards. Leaving a texture bound
or blending enabled corrupts the map's own rendering in ways that are miserable to
trace back to their cause, because the symptom appears in someone else's layer.

## Threading

- **GLB parsing** happens on a background thread, so a 2 MB model does not stall the UI.
- **Layer install/remove** happens on the main thread, as the Mapbox API requires.
- **Transform updates** are lock-free: JS writes `@Volatile` fields that the GL thread
  reads on its next frame. No main-thread hop, no allocation, no lock, at 60 Hz.
- **Animation switching** sets a `pendingAnimationIndex` that the render thread picks up
  and turns into a cross-fade, so the main thread never touches playback state.

While a clip is playing, `render()` calls back into the module to
`triggerRepaint()`, because Mapbox only redraws when something asks it to.

## Things that would be worth doing

- Instancing, so a crowd of characters costs one draw call per material rather than one
  per character.
- A shared parsed scene across layers — the module caches parses already, but each layer
  still uploads its own GPU copy.
- Skinning via transform feedback or compute, to get the joint work off the CPU.
