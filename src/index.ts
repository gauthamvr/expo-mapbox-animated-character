import { requireNativeModule } from 'expo-modules-core';

/** One animation clip found in the GLB, in the order glTF declares them. */
export type SkeletalAnimationInfo = {
  /** Clip name from the glTF (e.g. `Idle`, `Walk`, `Survey`, `Run`). */
  name: string;
  /** Clip length in seconds. */
  duration: number;
};

/** Resolved by {@link addSkeletalLayer} once the model is parsed and on the map. */
export type SkeletalLayerInfo = {
  animations: SkeletalAnimationInfo[];
};

/**
 * Character lighting mood. Keep this in sync with the Mapbox Standard style's
 * `lightPreset` so the character doesn't read as lit differently from the city.
 */
export type SkeletalLightPreset = 'dawn' | 'day' | 'dusk' | 'night';

interface NativeMapboxAnimatedCharacter {
  addSkeletalLayer(
    layerId: string,
    longitude: number,
    latitude: number,
    scale: number,
    modelAsset: string,
    headingOffsetDeg: number,
  ): Promise<SkeletalLayerInfo>;
  removeSkeletalLayer(layerId: string): Promise<void>;
  setSkeletalAnimation(layerId: string, animationIndex: number): Promise<void>;
  updateSkeletalTransform(
    layerId: string,
    longitude: number,
    latitude: number,
    heading: number,
    animSpeed: number,
  ): Promise<void>;
  setSkeletalLight(layerId: string, preset: string): Promise<void>;
  setSkeletalFollowCamera(layerId: string, enabled: boolean): Promise<void>;
  setSkeletalDepthBias(layerId: string, bias: number): Promise<void>;
}

const NativeModule = requireNativeModule<NativeMapboxAnimatedCharacter>('MapboxAnimatedCharacter');

/**
 * Add an animated character as a Mapbox custom layer.
 *
 * The model renders inside the map's own GL frame and shares its depth buffer, so
 * 3D buildings occlude it for free. Call this after the style has loaded — custom
 * layers live in the style, so a style reload drops the layer and you must add it
 * again (`onDidFinishLoadingStyle` is the right hook).
 *
 * @param layerId       Unique layer id. Reused as the handle for every other call here.
 * @param longitude     Initial longitude.
 * @param latitude      Initial latitude.
 * @param scale         Metres of world height per glTF scene unit. For a model whose
 *                      bind pose is 1.83 units tall that you want ~38 m tall on the
 *                      map, pass `38 / 1.83`.
 * @param modelAsset    Path inside the Android assets folder, e.g. `models/fox.glb`.
 *                      Use the config plugin to get files there — see the README.
 * @param headingOffsetDeg Per-model facing correction in degrees. 0 means the glTF's
 *                      +Z front faces north at heading 0; pass 180 for models authored
 *                      facing the other way.
 * @returns The model's animation clips, so you can resolve indices by name.
 */
export function addSkeletalLayer(
  layerId: string,
  longitude: number,
  latitude: number,
  scale: number,
  modelAsset: string,
  headingOffsetDeg: number = 0,
): Promise<SkeletalLayerInfo> {
  return NativeModule.addSkeletalLayer(layerId, longitude, latitude, scale, modelAsset, headingOffsetDeg);
}

/** Remove the layer and release its GPU resources. Safe to call if it isn't installed. */
export function removeSkeletalLayer(layerId: string): Promise<void> {
  return NativeModule.removeSkeletalLayer(layerId);
}

/**
 * Switch the playing clip by index (indices match {@link SkeletalLayerInfo.animations}).
 * Transitions cross-fade over ~0.28 s, so calling this on every idle↔walk change is fine.
 */
export function setSkeletalAnimation(layerId: string, animationIndex: number): Promise<void> {
  return NativeModule.setSkeletalAnimation(layerId, animationIndex);
}

/**
 * Push the live transform. Call this from your movement loop — 20–30 Hz is plenty,
 * since the native side smooths heading and advances the clip on the render thread.
 *
 * Fire-and-forget: rejections are swallowed because the layer may not be installed yet.
 *
 * @param heading   Compass bearing the character faces, in degrees (0 = north, clockwise).
 * @param animSpeed Clip playback rate multiplier. Scale it with walking speed so the
 *                  feet don't skate.
 */
export function updateSkeletalTransform(
  layerId: string,
  longitude: number,
  latitude: number,
  heading: number,
  animSpeed: number = 1,
): void {
  NativeModule.updateSkeletalTransform(layerId, longitude, latitude, heading, animSpeed).catch(() => {});
}

/** Set the character's lighting mood. Mirror the map's `lightPreset`. */
export function setSkeletalLight(layerId: string, preset: SkeletalLightPreset): Promise<void> {
  return NativeModule.setSkeletalLight(layerId, preset);
}

/**
 * Anchor mode.
 *
 * `true` (the default) draws the model at each frame's camera centre rather than at
 * the last coordinate pushed from JS. For a camera-locked player that is strictly
 * better: the model and the map can never disagree by a frame, which is what causes
 * visible jitter while moving.
 *
 * Set `false` for any character that is not the one the camera follows.
 */
export function setSkeletalFollowCamera(layerId: string, enabled: boolean): Promise<void> {
  return NativeModule.setSkeletalFollowCamera(layerId, enabled);
}

/**
 * Clip-space depth nudge toward the camera; 0 (the default) disables it.
 *
 * Only reach for this if a device draws buildings over the character when it should
 * be in front. Values around 0.0001–0.001 are the useful range; too much and the
 * character punches through buildings it is genuinely behind.
 */
export function setSkeletalDepthBias(layerId: string, bias: number): Promise<void> {
  return NativeModule.setSkeletalDepthBias(layerId, bias);
}
