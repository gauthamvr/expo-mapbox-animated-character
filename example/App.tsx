import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { StatusBar } from 'expo-status-bar';
import Mapbox, { Camera, MapView, StyleImport } from '@rnmapbox/maps';
import {
  addSkeletalLayer,
  removeSkeletalLayer,
  setSkeletalAnimation,
  setSkeletalFollowCamera,
  setSkeletalLight,
  updateSkeletalTransform,
  type SkeletalLightPreset,
} from 'expo-mapbox-animated-character';

const MAPBOX_TOKEN = process.env.EXPO_PUBLIC_MAPBOX_TOKEN ?? null;
Mapbox.setAccessToken(MAPBOX_TOKEN);

const LAYER_ID = 'demo-character';

// Midtown Manhattan — dense 3D buildings in the Mapbox Standard style, which is what
// makes the occlusion obvious: walk the character north and it disappears behind them.
const START = { lng: -73.9857, lat: 40.7484 };

// Character world height in metres. These are big on purpose: a real 1.8 m human is a
// speck next to a 100 m tower, so games scale the avatar up. `scale` is metres of world
// height per glTF scene unit, so it depends on how tall the model's bind pose is.
const MODELS = {
  soldier: {
    label: 'Soldier',
    asset: 'models/Soldier.glb',
    metersTall: 22,
    sourceHeightUnits: 1.8322,
    headingOffset: 180, // authored facing -Z, so flip it to face travel
    idle: 'idle',
    walk: 'walk',
  },
  fox: {
    label: 'Fox',
    asset: 'models/fox.glb',
    metersTall: 10.4,
    sourceHeightUnits: 79.03,
    headingOffset: 0,
    idle: 'survey',
    walk: 'run',
  },
} as const;

type ModelKey = keyof typeof MODELS;

const LIGHT_PRESETS: SkeletalLightPreset[] = ['day', 'dusk', 'night', 'dawn'];

// @rnmapbox/maps 10.3.1 declares StyleImport's `show3dObjects` as a boolean, but the
// native side only honours the string form. Passing a real `true` silently drops the
// whole basemap import — you get a flat green screen with no tiles and no buildings,
// and no error anywhere. The cast is deliberate; verified on device.
const SHOW_3D_OBJECTS = 'true' as unknown as boolean;

const SPEED_M_PER_S = 45; // tuned so you cross a city block in a couple of seconds
const PUBLISH_HZ = 60;
const EARTH_R = 6378137;

function offsetLngLat(p: { lng: number; lat: number }, metres: number, headingDeg: number) {
  const h = (headingDeg * Math.PI) / 180;
  const dNorth = metres * Math.cos(h);
  const dEast = metres * Math.sin(h);
  return {
    lng: p.lng + ((dEast / (EARTH_R * Math.cos((p.lat * Math.PI) / 180))) * 180) / Math.PI,
    lat: p.lat + ((dNorth / EARTH_R) * 180) / Math.PI,
  };
}

export default function App() {
  const camera = useRef<Camera>(null);

  const [modelKey, setModelKey] = useState<ModelKey>('soldier');
  const [light, setLight] = useState<SkeletalLightPreset>('day');
  const [follow, setFollow] = useState(true);
  const [status, setStatus] = useState('Loading style…');

  const posRef = useRef({ ...START });
  const headingRef = useRef(0);
  const dirRef = useRef<number | null>(null); // held D-pad direction, degrees from north
  const viewRef = useRef({ bearing: 0, pitch: 60, zoom: 17.4 });
  const clipsRef = useRef({ idle: -1, walk: -1, current: -1, ready: false });
  const followRef = useRef(true);
  const lightRef = useRef<SkeletalLightPreset>('day');

  followRef.current = follow;
  lightRef.current = light;

  const applyCamera = useCallback((animationDuration = 0) => {
    const v = viewRef.current;
    // In free-camera mode leave the centre alone so the user's own panning survives.
    const centre = followRef.current
      ? { centerCoordinate: [posRef.current.lng, posRef.current.lat] as [number, number] }
      : {};
    camera.current?.setCamera({
      ...centre,
      zoomLevel: v.zoom,
      pitch: v.pitch,
      heading: v.bearing,
      animationDuration,
    });
  }, []);

  // Custom layers live inside the style, so a style reload drops them. Re-add on every
  // onDidFinishLoadingStyle, and whenever the chosen model changes.
  const installLayer = useCallback(async () => {
    const cfg = MODELS[modelKey];
    clipsRef.current.ready = false;
    try {
      const info = await addSkeletalLayer(
        LAYER_ID,
        posRef.current.lng,
        posRef.current.lat,
        cfg.metersTall / cfg.sourceHeightUnits,
        cfg.asset,
        cfg.headingOffset,
      );

      // Resolve clip indices by name — glTF clip order is not guaranteed.
      const names = info.animations.map((a) => a.name.toLowerCase());
      const pick = (want: string) => names.findIndex((n) => n.includes(want));
      clipsRef.current.idle = pick(cfg.idle);
      clipsRef.current.walk = pick(cfg.walk);
      clipsRef.current.current = -1;
      clipsRef.current.ready = true;

      await setSkeletalLight(LAYER_ID, lightRef.current);
      await setSkeletalFollowCamera(LAYER_ID, followRef.current);
      updateSkeletalTransform(LAYER_ID, posRef.current.lng, posRef.current.lat, headingRef.current, 1);

      setStatus(`${cfg.label} · clips: ${info.animations.map((a) => a.name).join(', ') || 'none'}`);
    } catch (e) {
      // Style not ready yet — onDidFinishLoadingStyle will call us again.
      setStatus(`Waiting for style… (${e instanceof Error ? e.message : String(e)})`);
    }
  }, [modelKey]);

  useEffect(() => {
    installLayer();
  }, [installLayer]);

  useEffect(() => () => void removeSkeletalLayer(LAYER_ID).catch(() => {}), []);

  useEffect(() => {
    if (clipsRef.current.ready) setSkeletalLight(LAYER_ID, light).catch(() => {});
  }, [light]);

  useEffect(() => {
    if (clipsRef.current.ready) setSkeletalFollowCamera(LAYER_ID, follow).catch(() => {});
    applyCamera(300);
  }, [follow, applyCamera]);

  // Movement loop.
  useEffect(() => {
    let raf = 0;
    let last = 0;
    let lastPublish = 0;

    const tick = (now: number) => {
      if (last === 0) last = now;
      const dt = Math.min(0.05, (now - last) / 1000);
      last = now;

      const held = dirRef.current;
      const moving = held !== null;

      if (moving) {
        // Input is screen-relative: "up" is whatever direction the camera faces.
        const heading = held + viewRef.current.bearing;
        headingRef.current = heading;
        posRef.current = offsetLngLat(posRef.current, SPEED_M_PER_S * dt, heading);
        if (followRef.current) applyCamera(0);
      }

      if (now - lastPublish >= 1000 / PUBLISH_HZ) {
        lastPublish = now;
        updateSkeletalTransform(
          LAYER_ID,
          posRef.current.lng,
          posRef.current.lat,
          headingRef.current,
          moving ? 1.3 : 1,
        );
        const clips = clipsRef.current;
        if (clips.ready) {
          const want = moving ? clips.walk : clips.idle;
          if (want >= 0 && want !== clips.current) {
            clips.current = want;
            setSkeletalAnimation(LAYER_ID, want).catch(() => {});
          }
        }
      }

      raf = requestAnimationFrame(tick);
    };

    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, [applyCamera]);

  const nudge = (key: 'bearing' | 'pitch' | 'zoom', delta: number) => {
    const v = viewRef.current;
    if (key === 'bearing') v.bearing = (v.bearing + delta + 360) % 360;
    if (key === 'pitch') v.pitch = Math.max(0, Math.min(80, v.pitch + delta));
    if (key === 'zoom') v.zoom = Math.max(14, Math.min(20, v.zoom + delta));
    applyCamera(200);
  };

  if (!MAPBOX_TOKEN) {
    return (
      <View style={styles.gate}>
        <StatusBar style="light" />
        <Text style={styles.gateTitle}>Mapbox access token missing</Text>
        <Text style={styles.gateBody}>
          Create <Text style={styles.mono}>example/.env</Text> containing:
        </Text>
        <Text style={styles.gateCode}>EXPO_PUBLIC_MAPBOX_TOKEN=pk.your_public_token</Text>
        <Text style={styles.gateBody}>
          Get a free public token at account.mapbox.com, then restart the bundler.
        </Text>
      </View>
    );
  }

  return (
    <View style={styles.root}>
      <StatusBar style="light" />
      <MapView
        style={styles.map}
        styleURL="mapbox://styles/mapbox/standard"
        scaleBarEnabled={false}
        logoEnabled={false}
        attributionEnabled={false}
        compassEnabled={false}
        // The camera is driven entirely from code so screen-relative input stays exact.
        // Panning is enabled only in free-camera mode, where the character is anchored
        // to its own coordinate instead of the camera centre.
        scrollEnabled={!follow}
        rotateEnabled={false}
        pitchEnabled={false}
        zoomEnabled={false}
        onDidFinishLoadingStyle={installLayer}
      >
        <StyleImport id="basemap" existing config={{ lightPreset: light, show3dObjects: SHOW_3D_OBJECTS }} />
        <Camera
          ref={camera}
          defaultSettings={{
            centerCoordinate: [START.lng, START.lat],
            zoomLevel: viewRef.current.zoom,
            pitch: viewRef.current.pitch,
            heading: 0,
          }}
        />
      </MapView>

      <View style={styles.statusBar} pointerEvents="none">
        <Text style={styles.statusText} numberOfLines={2}>
          {status}
        </Text>
      </View>

      <View style={styles.optionColumn}>
        <Chip
          label={`Model: ${MODELS[modelKey].label}`}
          onPress={() => setModelKey(modelKey === 'soldier' ? 'fox' : 'soldier')}
        />
        <Chip
          label={`Light: ${light}`}
          onPress={() => setLight(LIGHT_PRESETS[(LIGHT_PRESETS.indexOf(light) + 1) % LIGHT_PRESETS.length])}
        />
        <Chip label={`Camera: ${follow ? 'follow' : 'free'}`} onPress={() => setFollow(!follow)} />
      </View>

      <View style={styles.dpad}>
        <Hold style={styles.dpadUp} label="▲" dir={0} dirRef={dirRef} />
        <Hold style={styles.dpadLeft} label="◀" dir={270} dirRef={dirRef} />
        <Hold style={styles.dpadRight} label="▶" dir={90} dirRef={dirRef} />
        <Hold style={styles.dpadDown} label="▼" dir={180} dirRef={dirRef} />
      </View>

      <View style={styles.viewPad}>
        <View style={styles.viewRow}>
          <Chip small label="⟲" onPress={() => nudge('bearing', -30)} />
          <Chip small label="⟳" onPress={() => nudge('bearing', 30)} />
        </View>
        <View style={styles.viewRow}>
          <Chip small label="pitch −" onPress={() => nudge('pitch', -10)} />
          <Chip small label="pitch +" onPress={() => nudge('pitch', 10)} />
        </View>
        <View style={styles.viewRow}>
          <Chip small label="zoom −" onPress={() => nudge('zoom', -0.5)} />
          <Chip small label="zoom +" onPress={() => nudge('zoom', 0.5)} />
        </View>
      </View>
    </View>
  );
}

function Chip({ label, onPress, small }: { label: string; onPress: () => void; small?: boolean }) {
  return (
    <Pressable style={[styles.chip, small && styles.chipSmall]} onPress={onPress}>
      <Text style={styles.chipText}>{label}</Text>
    </Pressable>
  );
}

function Hold({
  label,
  dir,
  dirRef,
  style,
}: {
  label: string;
  dir: number;
  dirRef: React.MutableRefObject<number | null>;
  style: object;
}) {
  return (
    <Pressable
      style={[styles.dpadBtn, style]}
      onPressIn={() => {
        dirRef.current = dir;
      }}
      onPressOut={() => {
        if (dirRef.current === dir) dirRef.current = null;
      }}
    >
      <Text style={styles.dpadText}>{label}</Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: '#0b0e13' },
  map: { flex: 1 },

  statusBar: {
    position: 'absolute',
    top: 48,
    left: 12,
    right: 12,
    backgroundColor: 'rgba(11,14,19,0.78)',
    borderRadius: 10,
    paddingHorizontal: 12,
    paddingVertical: 8,
  },
  statusText: { color: '#dbe4f0', fontSize: 12 },

  optionColumn: { position: 'absolute', top: 100, right: 12, gap: 8, alignItems: 'flex-end' },
  viewPad: { position: 'absolute', right: 12, bottom: 28, gap: 8, alignItems: 'flex-end' },
  viewRow: { flexDirection: 'row', gap: 8 },

  chip: {
    backgroundColor: 'rgba(20,26,36,0.9)',
    borderColor: 'rgba(120,150,190,0.35)',
    borderWidth: 1,
    borderRadius: 999,
    paddingHorizontal: 14,
    paddingVertical: 9,
  },
  chipSmall: { paddingHorizontal: 11, paddingVertical: 7 },
  chipText: { color: '#e8eef7', fontSize: 13, fontWeight: '600' },

  dpad: { position: 'absolute', left: 20, bottom: 28, width: 168, height: 168 },
  dpadBtn: {
    position: 'absolute',
    width: 56,
    height: 56,
    borderRadius: 14,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: 'rgba(20,26,36,0.9)',
    borderColor: 'rgba(120,150,190,0.35)',
    borderWidth: 1,
  },
  dpadUp: { left: 56, top: 0 },
  dpadLeft: { left: 0, top: 56 },
  dpadRight: { left: 112, top: 56 },
  dpadDown: { left: 56, top: 112 },
  dpadText: { color: '#e8eef7', fontSize: 18 },

  gate: { flex: 1, backgroundColor: '#0b0e13', alignItems: 'center', justifyContent: 'center', padding: 28, gap: 12 },
  gateTitle: { color: '#f2f6fb', fontSize: 19, fontWeight: '700' },
  gateBody: { color: '#a8b6c8', fontSize: 14, textAlign: 'center', lineHeight: 20 },
  gateCode: {
    color: '#9ad0ff',
    fontFamily: 'monospace',
    fontSize: 13,
    backgroundColor: 'rgba(20,26,36,0.9)',
    paddingHorizontal: 12,
    paddingVertical: 8,
    borderRadius: 8,
  },
  mono: { fontFamily: 'monospace', color: '#9ad0ff' },
});
