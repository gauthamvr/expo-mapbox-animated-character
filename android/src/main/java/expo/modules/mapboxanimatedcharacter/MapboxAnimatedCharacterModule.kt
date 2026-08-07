package expo.modules.mapboxanimatedcharacter

import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.kotlin.Promise
import com.mapbox.maps.MapView
import com.mapbox.bindgen.Value

class MapboxAnimatedCharacterModule : Module() {

  private val mainHandler = Handler(Looper.getMainLooper())
  private val TAG = "MapboxAnimatedChar"

  // Live custom layers, keyed by the layer id the caller chose.
  private val skeletalHosts = mutableMapOf<String, SkeletalLayerHost>()

  // Parsed GLB scenes are immutable — cache per asset path so style reloads and
  // avatar switches don't re-parse the binary.
  private val sceneCache = mutableMapOf<String, GlbScene>()

  override fun definition() = ModuleDefinition {

    Name("MapboxAnimatedCharacter")

    /**
     * addSkeletalLayer — adds the custom GL layer that renders an animated skinned GLB.
     * Parses the GLB on a background thread (cached per asset), then installs the layer
     * on the main thread. Resolves with the model's animation list so JS can pick
     * clips by name.
     */
    AsyncFunction("addSkeletalLayer") { layerId: String, longitude: Double, latitude: Double, scale: Double, modelAsset: String, headingOffsetDeg: Double, promise: Promise ->
      Thread {
        try {
          val scene: GlbScene? = if (modelAsset.isNotEmpty()) {
            synchronized(sceneCache) { sceneCache[modelAsset] } ?: run {
              val ctx = appContext.reactContext ?: throw Exception("No React context")
              val bytes = ctx.assets.open(modelAsset).use { it.readBytes() }
              android.util.Log.d(TAG, "Read $modelAsset: ${bytes.size} bytes")
              val parsed = GlbParser.parse(bytes) ?: throw Exception("GLB parse failed for $modelAsset")
              synchronized(sceneCache) { sceneCache[modelAsset] = parsed }
              parsed
            }
          } else null

          mainHandler.post {
            try {
              val mapView = findMapView() ?: throw Exception("MapView not found")
              val mapboxMap = mapView.mapboxMap
              val style = mapboxMap.style
              if (style == null) {
                promise.reject("ERR_NO_STYLE", "Map style not loaded yet", null)
                return@post
              }

              try { style.removeStyleLayer(layerId) } catch (_: Exception) {}

              val host = SkeletalLayerHost(scene, longitude, latitude, scale, headingOffsetDeg.toFloat())
              host.repaintCallback = Runnable {
                mainHandler.post { mapboxMap.triggerRepaint() }
              }

              style.addStyleCustomLayer(layerId, host, null).also { result ->
                if (result.isError) {
                  android.util.Log.e(TAG, "addSkeletalLayer FAILED: ${result.error}")
                  promise.reject("ERR_CUSTOM_LAYER", "addStyleCustomLayer failed: ${result.error}", null)
                } else {
                  style.setStyleLayerProperty(layerId, "slot", Value.valueOf("top"))
                  skeletalHosts[layerId] = host
                  android.util.Log.d(TAG, "addSkeletalLayer SUCCESS — slot=top at ($longitude, $latitude)")
                  mapboxMap.triggerRepaint()
                  promise.resolve(mapOf(
                    "animations" to (scene?.animations?.map {
                      mapOf("name" to it.name, "duration" to it.duration.toDouble())
                    } ?: emptyList<Map<String, Any>>()),
                  ))
                }
              }
            } catch (e: Exception) {
              android.util.Log.e(TAG, "addSkeletalLayer exception: ${e.message}", e)
              promise.reject("ERR_CUSTOM_LAYER", e.message ?: "Unknown error", e)
            }
          }
        } catch (e: Exception) {
          android.util.Log.e(TAG, "addSkeletalLayer load exception: ${e.message}", e)
          promise.reject("ERR_CUSTOM_LAYER", e.message ?: "Unknown error", e)
        }
      }.start()
    }

    AsyncFunction("removeSkeletalLayer") { layerId: String, promise: Promise ->
      mainHandler.post {
        try {
          val mapView = findMapView() ?: throw Exception("MapView not found")
          val style = mapView.mapboxMap.style
          if (style == null) {
            promise.reject("ERR_NO_STYLE", "Map style not loaded yet", null)
            return@post
          }

          try {
            style.removeStyleLayer(layerId)
          } catch (_: Exception) {}

          skeletalHosts.remove(layerId)
          mapView.mapboxMap.triggerRepaint()
          android.util.Log.d(TAG, "removeSkeletalLayer SUCCESS for '$layerId'")
          promise.resolve(null)
        } catch (e: Exception) {
          android.util.Log.e(TAG, "removeSkeletalLayer exception: ${e.message}", e)
          promise.reject("ERR_REMOVE_CUSTOM_LAYER", e.message ?: "Unknown error", e)
        }
      }
    }

    AsyncFunction("setSkeletalAnimation") { layerId: String, animationIndex: Int, promise: Promise ->
      mainHandler.post {
        try {
          val host = skeletalHosts[layerId] ?: throw Exception("No skeletal layer '$layerId'")
          host.setAnimation(animationIndex)
          val mapView = findMapView()
          mapView?.mapboxMap?.triggerRepaint()
          android.util.Log.d(TAG, "setSkeletalAnimation: layer=$layerId anim=$animationIndex")
          promise.resolve(null)
        } catch (e: Exception) {
          android.util.Log.e(TAG, "setSkeletalAnimation error: ${e.message}", e)
          promise.reject("ERR_ANIM", e.message ?: "Unknown error", e)
        }
      }
    }

    // Per-tick player transform. Lock-free: writes @Volatile fields the GL thread
    // reads next frame (no main-thread hop). AsyncFunction (the proven Expo pattern).
    AsyncFunction("updateSkeletalTransform") { layerId: String, longitude: Double, latitude: Double, heading: Double, animSpeed: Double, promise: Promise ->
      skeletalHosts[layerId]?.updateTransform(longitude, latitude, heading.toFloat(), animSpeed.toFloat())
      promise.resolve(null)
    }

    // Day/night character lighting — tracks the map style's lightPreset.
    AsyncFunction("setSkeletalLight") { layerId: String, preset: String, promise: Promise ->
      skeletalHosts[layerId]?.setLightPreset(preset)
      mainHandler.post { findMapView()?.mapboxMap?.triggerRepaint() }
      promise.resolve(null)
    }

    // When true (default) the model is glued to each frame's camera center — the
    // de-flicker mode for a camera-locked player character.
    AsyncFunction("setSkeletalFollowCamera") { layerId: String, enabled: Boolean, promise: Promise ->
      skeletalHosts[layerId]?.setFollowCamera(enabled)
      promise.resolve(null)
    }

    // Depth tuning knob: clip-space nudge toward the camera in case a device's
    // custom-layer matrix doesn't depth-match the map's 3D content (0 = off).
    AsyncFunction("setSkeletalDepthBias") { layerId: String, bias: Double, promise: Promise ->
      skeletalHosts[layerId]?.setDepthBias(bias.toFloat())
      mainHandler.post { findMapView()?.mapboxMap?.triggerRepaint() }
      promise.resolve(null)
    }
  }

  private fun findMapView(): MapView? {
    val activity = appContext.currentActivity ?: return null
    return findMapViewInViewGroup(activity.window.decorView as? ViewGroup)
  }

  private fun findMapViewInViewGroup(viewGroup: ViewGroup?): MapView? {
    if (viewGroup == null) return null
    for (i in 0 until viewGroup.childCount) {
      val child = viewGroup.getChildAt(i)
      if (child is MapView) return child
      if (child is ViewGroup) {
        val found = findMapViewInViewGroup(child)
        if (found != null) return found
      }
    }
    return null
  }
}
