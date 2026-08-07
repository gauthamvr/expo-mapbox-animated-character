# Credits

The MIT licence in [`LICENSE`](./LICENSE) covers the source code of this library. It
does **not** cover the 3D models bundled in `example/assets/models/`, which carry their
own terms and are included only so the demo runs without extra downloads.

## `example/assets/models/fox.glb`

From the [Khronos glTF Sample Models](https://github.com/KhronosGroup/glTF-Sample-Models/tree/main/2.0/Fox)
collection.

| Part | Author | Licence |
| --- | --- | --- |
| Low-poly model | PixelMannen | [CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/) |
| Rigging and animation | [@tomkranis](https://sketchfab.com/tomkranis) | [CC-BY 4.0](https://creativecommons.org/licenses/by/4.0/) |
| glTF conversion | @AsoboStudio and @scurest | — |

The CC-BY 4.0 rig requires attribution if you ship this model. Keep this section, or
swap in your own model.

## `example/assets/models/Soldier.glb`

Taken from the [three.js examples](https://github.com/mrdoob/three.js/tree/dev/examples/models/gltf),
where it ships as `examples/models/gltf/Soldier.glb`. The character and its
Idle/Walk/Run clips are Mixamo-derived.

Adobe's [Mixamo FAQ](https://helpx.adobe.com/creative-cloud/faq/mixamo-faq.html) states
that Mixamo content may be used in personal, commercial, and non-profit projects without
crediting Mixamo. Adobe does not publish a redistribution licence for the raw asset
files, so treat this copy as a convenience for trying the demo rather than as an
asset you are cleared to re-ship in your own product. If you need certainty, download
the character from Mixamo under your own Adobe account, or replace it with a model
whose terms you control.

If you are the rights holder and want this file removed, open an issue and it will be
taken out.

## Prior art and references

- Mapbox's [glTF model limitations](https://docs.mapbox.com/style-spec/guides/using-3d-models/#limitations),
  which is the gap this library exists to fill.
- Mapbox's [native custom layer example](https://docs.mapbox.com/android/maps/examples/android-view/native-custom-layer/)
  for Android, the starting point for the `CustomLayerHost` plumbing.
- Mapbox GL JS's [three.js custom layer example](https://docs.mapbox.com/mapbox-gl-js/example/add-3d-model/) —
  the web equivalent of what this does natively.
- Christian Schüler's derivative-based tangent-frame technique, used by the fragment
  shader to apply normal maps to models that ship without `TANGENT` attributes.
