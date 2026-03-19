# Loop.Motion API: Foundation Analysis & Control Mode Design

**Date:** 2026-03-19
**Branch:** `imu-motion-api-review`
**Status:** Analysis & Design Proposal

---

## Table of Contents

1. [Current API Foundation](#1-current-api-foundation)
2. [Architecture Deep Dive](#2-architecture-deep-dive)
3. [Data Flow & Threading](#3-data-flow--threading)
4. [What We Have vs What We Need](#4-what-we-have-vs-what-we-need)
5. [Vivarium Reference Patterns](#5-vivarium-reference-patterns)
6. [3DOF & Drift Research](#6-3dof--drift-research)
7. [Proposed Control Modes](#7-proposed-control-modes)
8. [Calibration Strategy](#8-calibration-strategy)
9. [Proposed API Shape](#9-proposed-api-shape)
10. [Implementation Considerations](#10-implementation-considerations)

---

## 1. Current API Foundation

### What Exists Today

The Loop.Motion API is a **raw sensor streaming pipeline** — it faithfully delivers hardware IMU data to JavaScript but provides no higher-level interpretation. Games receive physics-level sensor readings and must implement their own control logic.

#### Native Bridge Methods (`bridge-contract.yaml` → `IMUNamespace`)

| Method | Returns | Purpose |
|--------|---------|---------|
| `subscribe()` | `String` (UUID) | Start receiving motion events |
| `unsubscribe(id)` | `Boolean` | Stop a subscription |
| `setFrequency(hz)` | `Int` (clamped 1-240) | Set publish rate |
| `setSmoothingAlpha(alpha)` | `Float` (clamped 0-1) | Set EMA gravity filter |
| `getStatus()` | JSON string | Active state, sub count, config |
| `getLatest()` | JSON string or null | Poll latest data without subscription |
| `getSensorAvailability()` | JSON string | Which hardware sensors exist |

#### SDK Layer (`Loop.motion`)

```typescript
const sub = await Loop.motion.start({ frequency: 60, smoothing: 0.1 });
sub.on('data', (data: MotionData) => { ... });
sub.stop();
```

Higher-level conveniences: `isSupported()`, `setFrequency()` (chainable), `setSmoothingAlpha()` (chainable), `getStatus()`, `getLatest()`, `stopAll()`.

#### Data Payload (`MotionData`)

```typescript
{
  gravity: Vector3,        // Raw accelerometer (m/s^2)
  smoothGravity: Vector3,  // EMA-filtered gravity
  orientation: Quaternion,  // Device orientation (from TYPE_ROTATION_VECTOR)
  delta: Vector3,          // Gyroscope angular velocity (rad/s)
  timestamp: number,       // DOMHighResTimeStamp (ms)
  sequenceNumber: number   // Monotonic counter
}
```

### Current Game Usage

**Quarto** uses motion for two purposes:
1. **Parallax background** — subtle tilt-reactive visual depth
2. **Tilt-to-select** — map `smoothGravity` offset from neutral to grid position

```javascript
// Quarto's ad-hoc calibration
function calibrateNeutral(gravity) {
  neutralGravity = { x: gravity.x, y: gravity.y };
}
function onMotionData(data) {
  const sg = data.smoothGravity;
  if (!neutralGravity) calibrateNeutral(sg);
  tiltX = clamp((sg.x - neutralGravity.x) * 2, -1, 1);
  tiltY = clamp((sg.y - neutralGravity.y) * 2, -1, 1);
}
```

This pattern — "capture first reading as neutral, compute delta, scale to -1..1" — is what every game will need to do. It belongs in the SDK.

---

## 2. Architecture Deep Dive

### Kotlin: `IMUSensorManager` (405 lines)

**Sensors registered:**
- `TYPE_ACCELEROMETER` — raw gravity vector (m/s^2, 3-axis)
- `TYPE_GYROSCOPE` — angular velocity (rad/s, 3-axis)
- `TYPE_ROTATION_VECTOR` — hardware-fused orientation quaternion

All registered at `SENSOR_DELAY_FASTEST` on a dedicated `HandlerThread("IMUSensorThread")`.

**Sensor fusion is minimal.** The class has a complementary filter factor (0.98) and stillness detection infrastructure, but the actual `applyDriftCorrection()` method is a no-op — it relies on `TYPE_ROTATION_VECTOR` which already implements Android's native sensor fusion (hardware Kalman filter). The stillness detection (`STILLNESS_THRESHOLD = 0.05 rad/s`, `STILLNESS_DURATION = 0.5s`) detects device rest but doesn't act on it.

**EMA smoothing** is applied only to gravity:
```kotlin
smoothGravity[i] = smoothAlpha * latestGravity[i] + (1 - smoothAlpha) * smoothGravity[i]
```

**Rate limiting** decouples sensor sampling (~200+ Hz) from JavaScript dispatch (configurable, default 60 Hz). Events are dispatched via `evaluateJavascript()` on the UI thread.

### Kotlin: `IMUData` (73 lines)

Clean data classes with hand-built JSON serialization for performance (<1ms target). Contains `Vector3` (gravity, smoothGravity, delta) and `Quaternion` (orientation).

### SDK: `loop-sdk.ts` — MotionSubscription & MotionAPI

- `MotionSubscription` extends `EventTarget`, supports `.on('data', handler)` / `.off()` / `.stop()`
- `MotionAPI` manages a global listener on `window` for `loop:motion` events, dispatches to all active subscriptions
- Multiple simultaneous subscriptions supported
- Global listener lifecycle tied to first subscribe / last unsubscribe

### Performance Characteristics

| Metric | Value |
|--------|-------|
| IMU latency P50 @ 120Hz | 8.67ms |
| IMU latency P95 @ 120Hz | 29.13ms |
| Dropped frames @ 120Hz | 0 |
| Serialization | <1ms (hand-built JSON) |

The P95 bottleneck is `evaluateJavascript()` scheduling on the Android UI thread — a platform limitation.

---

## 3. Data Flow & Threading

```
Hardware IMU Sensors
    │
    ▼
SensorManager (SENSOR_DELAY_FASTEST, ~200+ Hz)
    │
    ▼
IMUSensorThread (dedicated HandlerThread)
    ├── TYPE_ACCELEROMETER → latestGravity[] + smoothGravity[] (EMA)
    ├── TYPE_GYROSCOPE → latestGyro[] + stillness detection
    └── TYPE_ROTATION_VECTOR → latestOrientation[] (quaternion)
    │
    ▼
Rate limiter (publishIntervalMs, checked on every sensor event)
    │
    ▼
buildIMUData() → IMUData.toJson() → JavaScript string
    │
    ▼
activity.runOnUiThread { webView.evaluateJavascript(script) }
    │
    ▼
window.dispatchEvent(new CustomEvent('loop:motion', {detail: data}))
    │
    ▼
Global SDK listener → dispatches to all MotionSubscription instances
    │
    ▼
Game handler: subscription.on('data', (data) => { ... })
```

**Thread safety model:**
- `@Volatile` fields for all latest sensor data
- `AtomicLong` for sequence numbers
- `ConcurrentHashMap` for subscriptions
- `WeakReference` for activity/webview to prevent leaks

---

## 4. What We Have vs What We Need

### What the current API provides well:
- Reliable, low-latency raw sensor data streaming
- Clean subscription lifecycle management
- Configurable frequency and smoothing
- Proper threading and lifecycle management (pause/resume/cleanup)
- Good test coverage

### What's missing for real game controls:

| Gap | Impact |
|-----|--------|
| **No calibration API** | Every game does ad-hoc "first reading = neutral" — fragile, no multi-frame averaging, no recalibration |
| **No reference frame management** | Games get raw device coordinates; no concept of "neutral" or "relative to how I'm holding it" |
| **No processed control output** | Games must manually convert quaternions/gravity to usable -1..1 input — math that 95% will get wrong |
| **No deadzone** | Sensor noise at rest causes jitter; every game must implement its own noise rejection |
| **No drift handling** | Long sessions accumulate drift; no auto-recalibration or reset mechanism exposed |
| **No control mode abstraction** | "Tilt to steer" vs "point to look" vs "hold flat and move" all require different math that the SDK could provide |
| **No Z-rotation compensation** | If user recalibrates at a rotated orientation, axes swap — Vivarium solved this |

---

## 5. Vivarium Reference Patterns

Vivarium's camera system (Unity/C#) implements a production-quality 3DOF motion control system. Key patterns worth porting:

### 5.1 Multi-Frame Calibration

```
Phase 1: SKIP first 15 frames (~200-500ms)
  - IMU needs stabilization time after enable
  - Early readings are noisy garbage

Phase 2: COLLECT next 30 frames
  - Gravity sensor: average Vector3 samples
  - Attitude sensor: average quaternions (hemisphere-normalized sum)

Phase 3: REFERENCE established
  - Stable baseline immune to startup noise
  - Z-rotation reference captured for axis compensation
```

**Why this matters:** Quarto's "first reading = neutral" captures a potentially noisy reading. The multi-frame approach gives a reference that's 10-50x more stable.

### 5.2 Three-Tier Auto-Recalibration

Vivarium's adaptive recalibration prevents drift without fighting the user:

| State | Rate | Behavior |
|-------|------|----------|
| **Rest** (device still) | Fast (3.0/sec) | Snap reference to current — eliminates drift quickly |
| **Near center** (small tilt) | Moderate (0.02-1.0/sec) | Gentle correction proportional to how centered the input is |
| **Full tilt** (user actively tilting) | Frozen (0.02/sec) | Don't fight the user — hold reference steady |

The rate selection is continuous, not discrete:
```
inputMag = magnitude of normalized input (0..1)
recalWeight = 1 - clamp01(inputMag)  // 1 at center, 0 at full tilt
rate = MIN_RATE + recalWeight * (MAX_RATE - MIN_RATE)
reference = lerp(reference, current, deltaTime * rate)
```

Additionally:
- **`SlowRecalOnly` mode**: Caps rate at MIN when `returnToCenter` is disabled (e.g., during selection menus)
- **`PauseAutoRecalibration`**: Completely freezes reference (for cutscenes, interactions)

### 5.3 Rest Detection

Circular buffer of 30 gravity readings, variance computed each frame:
- Variance < 0.0002 for 400ms → device at rest → fast recal rate
- Any motion → immediate exit (no exit hysteresis)

This solves a subtle problem: when the device is still, accelerometer noise can't be distinguished from tiny intentional tilts. Rest detection lets us aggressively snap the reference during stillness, then immediately stop when the user moves.

### 5.4 Z-Rotation Compensation (Gravity Sensor)

When a user recalibrates while holding the device rotated (e.g., lying on a couch):
- Tilt axes would swap or invert without compensation
- Vivarium captures the Z angle from gravity's XZ projection at calibration
- Applies a 2D rotation matrix to tilt delta to map back to the original axis orientation

```
zAngle = atan2(gravityProjection.x, -gravityProjection.y)
deltaZ = (currentZAngle - calibrationZAngle)
rotatedTilt = [cos(-δz), -sin(-δz)] * [tiltX]
              [sin(-δz),  cos(-δz)]   [tiltY]
```

### 5.5 Canonical Frame Preservation (Attitude Sensor)

For quaternion-based input, Vivarium captures a "canonical reference" on first calibration, then on subsequent recalibrations:
- New reference is set, but canonical frame is preserved
- Look input is remapped from current reference frame → canonical frame using signed angle between projected right vectors
- Prevents axis swap/flip when recalibrating at a rotated orientation

### 5.6 Two Sensor Strategies

| | Gravity Sensor | Attitude Sensor |
|---|---|---|
| **Data** | 3-axis gravity vector | Full quaternion orientation |
| **Decomposition** | `atan2` → tilt angles | Inverse multiply → Euler extract |
| **Best for** | 2D tilt control (pan/cursor) | 3D look/rotation |
| **Drift** | Minimal (accelerometer is absolute) | Moderate (gyro integration drift) |
| **Axes at any angle** | Z-rotation compensation needed | Canonical frame remapping needed |
| **Range** | ~±45° practical | Full 360° possible |

### 5.7 Movement Strategies (Bonus Patterns)

Vivarium implements pluggable movement strategies on top of the look input:

- **EdgeShift**: When look input reaches the edge of a radius, drift the camera in that direction
- **MomentumDrift**: Same but with inertia (acceleration/deceleration)
- **FlickPan**: Detect fast input velocity → launch camera to a target position
- **Hybrid**: Compose EdgeShift + FlickPan

These are game-specific, but the *look radius + edge detection* pattern could inform our SDK's mode design.

---

## 6. 3DOF & Drift Research

### Vuforia's Approach

Vuforia Fusion detects and utilizes the platform's native device tracker (ARCore on Android, ARKit on iOS) or falls back to its own sensor-fusion technology. For 3DOF:
- Uses visual details from camera + IMU for 6DOF pose
- Falls back to IMU-only 3DOF when visual tracking unavailable
- Drift is bounded by periodic visual corrections (not applicable to us — no camera)

**Key insight for Loop:** Without visual anchoring, pure IMU 3DOF will always drift. Our strategy must accept this and manage it — Vivarium's adaptive recalibration is the right pattern for our use case.

### Sensor Fusion Algorithms

| Algorithm | Compute Cost | Accuracy | Best For |
|-----------|-------------|----------|----------|
| **Complementary Filter** | Lowest | Good for slow motion | Simple tilt applications |
| **Mahony (AHRS)** | Low | Good for dynamic | Real-time on constrained devices |
| **Madgwick (AHRS)** | Low-Medium | Best for noisy data | Mobile/game applications |
| **Kalman Filter** | Highest | Best overall | When compute budget allows |

**Our situation:** Android's `TYPE_ROTATION_VECTOR` already implements hardware-accelerated sensor fusion (typically Kalman-based). We don't need to run our own fusion algorithm — we just need to handle drift on top of what the OS provides.

### Drift Compensation Techniques

1. **Gyroscope bias calibration at startup**: Average readings during a known-static period, subtract bias from subsequent measurements. *Vivarium does this with the 15-skip + 30-sample calibration.*

2. **Zero Velocity Update (ZUPT)**: When device is stationary, force velocity to zero and correct accumulated drift. *Vivarium's rest detection + fast recal rate is a form of this.*

3. **Adaptive recalibration rate**: Correct more aggressively when user is near-neutral, freeze when they're actively using the input. *Vivarium's three-tier system.*

4. **Periodic explicit recalibration**: Let the user trigger a "re-center" action. *Vivarium supports this via `Recalibrate()` — we should expose it.*

5. **Temperature compensation**: IMU bias varies with temperature. Modern Android devices handle this in the HAL, but it's worth noting for edge cases on budget hardware.

### Stanford EE267 Key Insights

From Stanford's VR orientation tracking course:
- Gyroscope bias is the primary source of drift in 3DOF tracking
- Static calibration (averaging readings at startup) removes ~90% of bias error
- Complementary filtering with accelerometer bounds pitch/roll drift but NOT yaw drift
- Magnetometer is needed for absolute yaw reference (Loop doesn't use magnetometer currently)
- Predictive tracking (extrapolating orientation ahead by a few ms) can reduce perceived latency

---

## 7. Proposed Control Modes

Based on analysis of Vivarium patterns, game use cases, and the Loop hardware form factor (wrist-worn, 800x800 circular display):

### Mode 1: `tilt` — 2D Tilt Control

**Use case:** Marble maze, cursor control, menu navigation, parallax effects
**Sensor:** Gravity (accelerometer) — drift-free, most reliable
**Output:** `{ x: -1..1, y: -1..1 }` — normalized 2D tilt from neutral

**How it works:**
1. Calibrate neutral holding position (multi-frame)
2. Decompose gravity vector to tilt angles via `atan2`
3. Compute delta from reference
4. Apply sensitivity, deadzone, Z-rotation compensation
5. Normalize to -1..1

**Configuration:**
```typescript
{
  mode: 'tilt',
  maxTiltDeg?: number,      // Default 25° — maps to full -1..1
  deadzone?: number,         // Default 2° — reject noise
  sensitivity?: { x: number, y: number },  // Per-axis scaling
  autoRecalibrate?: boolean, // Default true — adaptive drift correction
}
```

### Mode 2: `look` — 3DOF Gaze/Look Direction

**Use case:** FPS-style look, panorama viewer, "point the device to look around"
**Sensor:** Attitude (rotation vector) — full quaternion orientation
**Output:** `{ yaw: number, pitch: number }` in degrees from neutral, OR `{ x: -1..1, y: -1..1 }` normalized

**How it works:**
1. Calibrate reference orientation (multi-frame quaternion averaging)
2. Compute delta quaternion: `inverse(reference) * current`
3. Extract yaw/pitch Euler angles from delta
4. Apply sensitivity, deadzone, canonical frame remapping
5. Normalize by maxLookDeg OR provide raw degrees

**Configuration:**
```typescript
{
  mode: 'look',
  maxLookDeg?: number,       // Default 45° — maps to full -1..1
  deadzone?: number,          // Default 1°
  sensitivity?: { x: number, y: number },
  autoRecalibrate?: boolean,  // Default true
  outputMode?: 'normalized' | 'degrees',  // Default 'normalized'
}
```

### Mode 3: `steer` — Single-Axis Rotation (Steering Wheel)

**Use case:** Racing games, single-axis rotation control, dial/knob interactions
**Sensor:** Gyroscope (angular velocity) integrated, or gravity Z-angle
**Output:** `{ angle: number }` in degrees from neutral, or `{ value: -1..1 }` normalized

**How it works:**
1. Calibrate neutral rotation angle
2. Track rotation around the selected axis (typically Z for wrist rotation)
3. Apply deadzone and sensitivity
4. Normalize by maxAngle

**Configuration:**
```typescript
{
  mode: 'steer',
  axis?: 'roll' | 'pitch' | 'yaw',  // Default 'roll' (wrist rotation)
  maxAngleDeg?: number,              // Default 45°
  deadzone?: number,                  // Default 2°
  sensitivity?: number,               // Default 1.0
}
```

### Mode 4: `flat` — Flat Surface Movement

**Use case:** Air hockey, flat-table games, "device as a cursor on a surface"
**Sensor:** Accelerometer (linear acceleration, gravity subtracted)
**Output:** `{ x: number, y: number }` — velocity or position delta

**How it works:**
1. Detect device is held approximately flat (gravity Z > threshold)
2. Use linear acceleration (accel - gravity) to detect movement gestures
3. Double-integrate for position (with aggressive drift zeroing) OR use velocity directly
4. Apply deadzone to reject vibration/table noise

**Note:** This is the hardest mode to implement well. Double integration of accelerometer data drifts rapidly. Practical implementations typically use:
- Velocity-only output (single integration, less drift)
- Gesture detection (flick direction) rather than continuous tracking
- Short integration windows with periodic zeroing

**Configuration:**
```typescript
{
  mode: 'flat',
  outputMode?: 'velocity' | 'gesture',  // Default 'velocity'
  deadzone?: number,                      // Default 0.3 m/s^2
  flatThreshold?: number,                 // Default 8.0 (m/s^2 gravity Z required)
}
```

### Mode 5: `raw` — Current Behavior (Raw Sensor Data)

**Use case:** Custom game-specific processing, SDK explorer, debugging
**Sensor:** All sensors
**Output:** Full `MotionData` (unchanged from current API)

This is what we have today. Keep it as an explicit mode for games that need custom processing.

---

## 8. Calibration Strategy

### The Problem Today

No calibration API exists. Games must:
1. Capture a "neutral" reading themselves
2. Handle drift manually (they don't)
3. Provide their own recalibration trigger (they don't)
4. Deal with startup noise (they don't — first reading is used)

### Proposed Solution: Layered Calibration

#### Layer 1: Automatic (SDK-managed, invisible to game)

When a control mode starts:
1. **Skip phase**: Discard first 15 frames (~250ms at 60Hz) — IMU stabilization
2. **Collection phase**: Average next 30 frames — stable reference
3. **Mode-ready event**: Fire only after reference is established
4. **Auto-recalibration**: Adaptive three-tier rate (from Vivarium):
   - Rest: fast correction (3.0/sec)
   - Near-neutral: moderate correction (proportional to input magnitude)
   - Active tilt: frozen (~0.02/sec)

Game code sees none of this. They get clean, calibrated, drift-corrected output from frame 1.

#### Layer 2: Explicit Recalibration (game-triggered)

```typescript
// Option A: Method on the mode controller
const controller = await Loop.motion.startMode('tilt', options);
controller.recalibrate();  // Re-runs the calibration sequence

// Option B: Button-triggered (common UX pattern)
Loop.buttons.on('B', () => controller.recalibrate());
```

Use cases:
- User changed how they're holding the device
- Game instructions say "hold device like this, then press B"
- Between game rounds/levels

#### Layer 3: Configuration (tuning knobs)

```typescript
{
  calibration: {
    skipFrames?: number,        // Default 15
    sampleFrames?: number,      // Default 30
    autoRecalibrate?: boolean,  // Default true
    recalRateMin?: number,      // Default 0.02/sec
    recalRateMax?: number,      // Default 1.0/sec
    recalRateRest?: number,     // Default 3.0/sec
    restVarianceThreshold?: number,  // Default 0.0002
    restSettleTime?: number,    // Default 0.4s
  }
}
```

Most games never touch this. Power users can tune for specific use cases.

### Why NOT Expose `calibrate()` as a Standalone Native Method

We *could* add `calibrate()` to the bridge contract. But the calibration is mode-specific:
- Tilt mode calibrates a gravity reference vector
- Look mode calibrates a quaternion reference frame (with canonical frame tracking)
- Steer mode calibrates a rotation angle reference

Making calibration a property of the mode controller keeps the semantics clean and prevents misuse (e.g., calling calibrate() before choosing a mode).

**However**, we *should* add a native method for the case where a game using raw mode wants calibration. Recommendation: add `resetReference()` to the native bridge that captures a new reference orientation. The SDK wraps this with mode-appropriate semantics.

---

## 9. Proposed API Shape

### High-Level SDK API

```typescript
interface MotionAPI {
  // === Existing (keep) ===
  isSupported(): boolean;
  start(options?: MotionOptions): Promise<MotionSubscription>;  // Raw mode
  setFrequency(hz: number): MotionAPI;
  setSmoothingAlpha(alpha: number): MotionAPI;
  getStatus(): MotionStatus;
  getLatest(): MotionData | null;
  stopAll(): void;

  // === New: Control Modes ===
  startMode(mode: MotionMode, options?: MotionModeOptions): Promise<MotionController>;
}

type MotionMode = 'tilt' | 'look' | 'steer' | 'flat' | 'raw';

interface MotionModeOptions {
  frequency?: number;
  smoothing?: number;
  // Mode-specific options (discriminated by mode)
  maxAngleDeg?: number;
  deadzone?: number;
  sensitivity?: number | { x: number, y: number };
  autoRecalibrate?: boolean;
  axis?: 'roll' | 'pitch' | 'yaw';
  outputMode?: 'normalized' | 'degrees' | 'velocity' | 'gesture';
}
```

### MotionController (returned from `startMode`)

```typescript
interface MotionController extends EventTarget {
  readonly mode: MotionMode;
  readonly calibrated: boolean;
  readonly active: boolean;

  /** Re-run calibration sequence (new reference from current orientation) */
  recalibrate(): void;

  /** Pause auto-recalibration (e.g., during cutscenes) */
  pauseRecalibration(): void;
  resumeRecalibration(): void;

  /** Subscribe to processed control output */
  on(event: 'input', handler: (input: MotionInput) => void): this;
  on(event: 'calibrated', handler: () => void): this;
  on(event: 'rest', handler: (atRest: boolean) => void): this;

  off(event: string, handler: Function): this;

  /** Stop this mode */
  stop(): void;
}
```

### MotionInput (processed output)

```typescript
/** Discriminated union based on mode */
type MotionInput = TiltInput | LookInput | SteerInput | FlatInput | RawInput;

interface TiltInput {
  mode: 'tilt';
  x: number;        // -1..1 left/right
  y: number;        // -1..1 forward/backward
  magnitude: number; // 0..1 distance from center
  atRest: boolean;   // Device is stationary
  timestamp: number;
}

interface LookInput {
  mode: 'look';
  yaw: number;       // Degrees or -1..1 based on outputMode
  pitch: number;     // Degrees or -1..1 based on outputMode
  x: number;         // Alias: normalized yaw
  y: number;         // Alias: normalized pitch
  magnitude: number;
  atRest: boolean;
  timestamp: number;
}

interface SteerInput {
  mode: 'steer';
  angle: number;     // Degrees or -1..1 based on outputMode
  value: number;     // Alias: normalized angle
  atRest: boolean;
  timestamp: number;
}

interface FlatInput {
  mode: 'flat';
  x: number;         // Velocity or gesture magnitude
  y: number;
  isFlat: boolean;   // Device is approximately horizontal
  timestamp: number;
}

interface RawInput {
  mode: 'raw';
  data: MotionData;  // Full sensor payload (unchanged)
}
```

### Example Game Code

```typescript
// Simple tilt game (marble maze)
const ctrl = await Loop.motion.startMode('tilt', {
  maxAngleDeg: 20,
  deadzone: 2,
});
ctrl.on('input', (input) => {
  marble.vx += input.x * speed;
  marble.vy += input.y * speed;
});

// Look-around panorama
const ctrl = await Loop.motion.startMode('look', {
  maxLookDeg: 60,
  outputMode: 'degrees',
});
ctrl.on('input', (input) => {
  camera.yaw = input.yaw;
  camera.pitch = input.pitch;
});

// Racing game steering
const ctrl = await Loop.motion.startMode('steer', {
  maxAngleDeg: 35,
  axis: 'roll',
});
ctrl.on('input', (input) => {
  car.steerAngle = input.value * maxSteering;
});

// Recalibrate on button press
Loop.buttons.on('B', () => ctrl.recalibrate());
```

---

## 10. Implementation Considerations

### Where Does Processing Live?

**Option A: All in JavaScript (SDK layer)**
- Pros: Easy to iterate, no native rebuild needed, games can override
- Cons: Quaternion math in JS at 60Hz, GC pressure from object creation, latency

**Option B: All in Kotlin (native layer)**
- Pros: Best performance, no GC pressure, direct sensor access
- Cons: Requires native rebuild for changes, harder to debug, bridge contract changes

**Option C: Hybrid (recommended)**
- **Kotlin**: Calibration state, reference tracking, auto-recalibration, rest detection, deadzone, normalization → dispatch processed `MotionInput` as JSON
- **JavaScript**: Mode selection, event routing, API ergonomics
- **Rationale**: The heavy math (quaternion inverse multiply, atan2 decomposition, circular buffer variance) is better in Kotlin. The API shape and event model stay in JS for developer experience.

### Native Bridge Additions

New methods needed on the native side:

```yaml
# bridge-contract.yaml additions
motion:
  nativeMethods:
    # ... existing methods ...
    startMode:
      params:
        - name: mode
          type: String
        - name: optionsJson
          type: String
      returns: String  # subscription ID
    recalibrate:
      params:
        - name: subscriptionId
          type: String
      returns: Boolean
    pauseRecalibration:
      params:
        - name: subscriptionId
          type: String
      returns: Boolean
    resumeRecalibration:
      params:
        - name: subscriptionId
          type: String
      returns: Boolean
```

### New Kotlin Classes

| Class | Responsibility |
|-------|---------------|
| `MotionMode` | Enum: TILT, LOOK, STEER, FLAT, RAW |
| `MotionCalibrator` | Multi-frame calibration, reference management |
| `TiltProcessor` | Gravity → normalized tilt (atan2, Z-compensation) |
| `LookProcessor` | Quaternion → yaw/pitch (inverse multiply, canonical frame) |
| `SteerProcessor` | Single-axis rotation extraction |
| `FlatProcessor` | Linear acceleration detection |
| `RestDetector` | Circular buffer variance, settle timer |
| `AutoRecalibrator` | Three-tier adaptive rate |
| `MotionModeManager` | Orchestrates processor + calibrator + recalibrator per subscription |

### Events

New event type alongside `loop:motion`:

```
loop:motion:input  — processed control mode output
loop:motion:calibrated  — calibration complete
loop:motion:rest  — rest state changed
```

Or: use the existing `loop:motion` event but change the payload shape when a mode is active. **Recommendation: new event name** to avoid breaking existing games using raw data.

### Migration Path

1. **Phase 1**: Add `startMode()` alongside existing `start()`. Both work. Raw mode = current behavior.
2. **Phase 2**: Port Quarto and SDK Explorer to use modes.
3. **Phase 3**: Make `startMode('tilt')` the recommended API in documentation.

Existing `start()` and `MotionData` payload remain unchanged — no breaking changes.

---

## Appendix A: Open Source Reference Projects

### Tier 1: Directly Applicable

#### x-io Fusion (Madgwick's Latest AHRS) — **PRIMARY REFERENCE**
- **URL:** https://github.com/xioTechnologies/Fusion
- **Language:** C (MIT license), ~1.5k stars
- **What:** The definitive AHRS library by Sebastian Madgwick. Combines gyro + accel + optional magnetometer into quaternion orientation via complementary filter with gradient-descent correction.
- **Key patterns to port:**
  - **Automatic gyro bias estimation** — detects stillness (all axes < 3.0 dps for 5 seconds), runs a 0.02 Hz high-pass filter to extract DC drift, subtracts from readings. No explicit calibration step needed.
  - **Acceleration rejection** — compares predicted gravity (from current quaternion) vs measured gravity. If angular error exceeds threshold, ignores accelerometer that frame. Hysteretic counter (+1 per rejection, -9 per acceptance) triggers recovery if rejection exceeds 90% over configurable period.
  - **Magnetic rejection** — identical mechanism to acceleration rejection for magnetometer heading.
  - **Recovery mechanisms** — if sensor is rejected too long, forces re-acceptance to prevent permanent lockout. Angular rate recovery resets algorithm (preserving quaternion) if gyro nears sensor range limit.
  - **Gain ramping** — initializes with gain=10.0, ramps down to target (e.g., 0.5) over 3 seconds for fast initial convergence.
  - **Linear acceleration output** — gravity-subtracted accelerometer, both in sensor frame and Earth frame.
- **Porting effort:** ~600 lines of C → Kotlin. Self-contained, no dependencies. Data classes + inline math functions translate cleanly.

#### GamepadMotionHelpers — **GAMING-SPECIFIC REFERENCE**
- **URL:** https://github.com/JibbSmart/GamepadMotionHelpers
- **Language:** C++ header-only (MIT license), ~55 stars
- **What:** Purpose-built for game controller gyro. Created by the developer behind gyro aiming standards (used in JoyShockMapper, popular with Steam Deck community).
- **Key patterns to port:**
  - **Player Space gyro** — hybrid approach that projects yaw onto gravity axis with a "relaxation factor" (1.41x) and clamps to raw gyro magnitude. Pitch stays in local space. Minimizes drift while feeling responsive. This is the key innovation for gaming IMU:
    ```
    worldYaw = -(gravY * gyroY + gravZ * gyroZ)  // gravity-projected yaw
    y = sign(worldYaw) * min(|worldYaw| * 1.41, sqrt(gyroY² + gyroZ²))  // relaxed + clamped
    x = gyroX  // local pitch, unmodified
    ```
  - **Stillness calibration** — adaptive threshold system: tracks min observed motion (MinDeltaGyro), requires all 6 axes (3 gyro + 3 accel) below threshold for 2+ seconds. Bias estimated via window midpoint with exponential half-life smoothing. Confidence grows during stillness, making subsequent changes more gradual.
  - **Sensor fusion calibration** — computes angular velocity from accelerometer direction changes (cross product of consecutive normalized gravity vectors), compares with gyro. Difference = bias error. Only applies to axes where accelerometer has authority (gravity component > 0.7). Smoothing prevents false corrections.
  - **Combined calibration** — stillness for high-confidence axes, sensor fusion fills gaps on weak axes. Best of both worlds.
  - **Three gyro spaces** — Local (raw), World (full gravity-adjusted yaw + perpendicular pitch), Player (loose gravity-adjusted yaw + local pitch). Player space is the recommended default for gaming.
  - **Shakiness tracking** — exponential decay of acceleration deviation magnitude, used to modulate gravity correction speed. High shakiness → slow correction. Still → fast correction.
- **Porting effort:** ~1200 lines of C++ → Kotlin. Single header, no dependencies. Complex but well-structured.

#### FSensor — **ANDROID-NATIVE REFERENCE**
- **URL:** https://github.com/KalebKE/FSensor
- **Language:** Java/Kotlin (Apache 2.0 license), ~211 stars
- **What:** Android sensor fusion library with Complementary, Kalman, and Low-Pass filters. Already wraps Android SensorManager.
- **Key patterns to reference:**
  - **Complementary filter** — frequency-domain fusion: `alpha = timeConstant / (timeConstant + dt)`, gyro weighted by alpha, accel/mag by (1-alpha). Default time constant 0.18s.
  - **Kalman filter** — Apache Commons Math KalmanFilter with 4D quaternion state. Predict step uses gyro, correct step uses accel/mag-derived orientation. Process/measurement noise both 0.01.
  - **Adaptive frequency** — filters calculate actual sensor delivery rate dynamically, making all time constants device-agnostic.
  - **Magnetic calibration** — ellipsoid-to-sphere fitting for hard/soft iron compensation.
  - **Quaternion integration** — `deltaQ = AngleAxis(magnitude * dt, normalized_gyro)`, `Q *= deltaQ`.
- **Porting effort:** Already Java/Kotlin. Could reference directly or adapt patterns. Uses Apache Commons Math (heavy dependency).

### Tier 2: Architecture & Approach References

#### Monado (OpenXR Runtime)
- **URL:** https://gitlab.freedesktop.org/monado/monado
- **Language:** C/C++ (Boost license)
- **What:** Production OpenXR runtime backed by Collabora. Has dedicated `m_imu_3dof` module for IMU-only 3DOF devices (phone VR). Uses flexkalman EKF.
- **Relevant:** Architecture for how a production VR runtime structures its 3DOF path. Separates gyro integration from accel-based gravity correction.

#### cardboard-vr-display (WebVR Polyfill)
- **URL:** https://github.com/immersive-web/cardboard-vr-display
- **Language:** JavaScript (Apache 2.0), ~96 stars
- **What:** JavaScript 3DOF tracking for phone VR. Complementary filter (K=0.98) with 40ms motion prediction. Falls back between sensor APIs gracefully.
- **Relevant:** If we ever want sensor fusion running in the WebView itself (JavaScript-side). Shows prediction technique for latency reduction.

#### JoyShockMapper
- **URL:** https://github.com/JibbSmart/JoyShockMapper
- **Language:** C++ (MIT license), ~845 stars
- **What:** Full gyro-aiming application. Flick stick algorithm, real-world calibration (mapping gyro DPS to actual physical rotation), multiple aim mode configurations.
- **Relevant:** Real-world calibration approach and flick stick pattern for potential steer mode enhancement.

#### AHRS Python Library
- **URL:** https://github.com/Mayitzin/ahrs
- **Language:** Python (MIT license), ~701 stars
- **What:** 18 different attitude estimation algorithms implemented to match original paper equations. Madgwick, Mahony, EKF, UKF, QUEST, TRIAD, Davenport, AQUA, FAMC, Complementary, Fourati, etc.
- **Relevant:** Algorithm comparison and prototyping. If we want to evaluate different fusion approaches before committing to a Kotlin implementation, prototype in Python first using this library.

### Tier 3: Supplementary References

| Project | URL | Why |
|---------|-----|-----|
| OpenHMD | github.com/OpenHMD/OpenHMD | HMD rotation tracking drivers (unmaintained, redirects to Monado) |
| libsurvive | github.com/collabora/libsurvive | IMU-space tracking with external correction signals |
| sensor-fusion-demo | github.com/apacha/sensor-fusion-demo | Android demo comparing Kalman vs complementary vs rotation vector |
| madgwick.js | github.com/ZiCog/madgwick.js | JavaScript port of Madgwick/Mahony with THREE.js visualization |
| sensor-polyfills | github.com/kenchris/sensor-polyfills | W3C Generic Sensor API polyfill for WebView compatibility |
| magnetometer_calibration | github.com/nliaudat/magnetometer_calibration | Python ellipsoid fitting for mag calibration |
| android-iio-sensors-hal | github.com/intel/android-iio-sensors-hal | Intel's Android sensor HAL with gyro bias calibration |

### Recommended Porting Strategy

Based on deep analysis of all three Tier 1 projects:

1. **Gyro bias estimation**: Port from **x-io Fusion** — simplest, most elegant (76 lines of C). Detects stillness, runs low-pass filter on gyro to extract DC offset. Drop-in addition to our `IMUSensorManager`.

2. **Player Space gyro mode**: Port from **GamepadMotionHelpers** — the `GetPlayerSpaceGyro()` function (~15 lines) is the single most impactful addition for gaming feel. Loose gravity-adjusted yaw + local pitch.

3. **Acceleration rejection**: Port from **x-io Fusion** — prevents accelerometer from corrupting orientation during sharp movements. Hysteretic counter with recovery is robust and well-tested.

4. **Adaptive calibration**: Port from **GamepadMotionHelpers** — the combined stillness + sensor fusion approach with confidence tracking and per-axis authority. More sophisticated than Fusion's bias module.

5. **Filter architecture**: Reference **FSensor** — Android-native patterns for sensor registration, frequency-agnostic time constants, quaternion integration. Don't use the library directly (Apache Commons Math is heavy), but follow the patterns.

6. **Don't port**: Kalman filter (Android's TYPE_ROTATION_VECTOR already does this in hardware), magnetometer calibration (not using mag currently), full AHRS with magnetometer (unnecessary complexity for our use case).

## Appendix B: Research Sources

- [Stanford EE267: 3-DOF Orientation Tracking with IMUs](https://stanford.edu/class/ee267/notes/ee267_notes_imu.pdf) — Comprehensive course notes on IMU orientation estimation
- [Vuforia Device Tracking](https://developer.vuforia.com/library/vuforia-engine/environments/device-tracking/device-tracking/) — Vuforia's approach to 6DOF/3DOF tracking
- [Vuforia Fusion](https://developer.vuforia.com/library/vuforia-engine/environments/device-tracking/vuforia-fusion/vuforia-fusion/) — Platform-adaptive sensor fusion
- [Madgwick Orientation Filter (AHRS docs)](https://ahrs.readthedocs.io/en/latest/filters/madgwick.html) — Gradient-descent based orientation estimation
- [Mahony Orientation Filter (AHRS docs)](https://ahrs.readthedocs.io/en/latest/filters/mahony.html) — SO(3) observer-based estimation
- [Comparison of Madgwick, Mahony, and Basic AHRS](https://web.cs.ndsu.nodak.edu/~siludwig/Publish/papers/SPIE20181.pdf) — Performance comparison study
- [IMU Data Fusing: Complementary, Kalman, Mahony](https://www.olliw.eu/2013/imu-data-fusing/) — Practical comparison of fusion approaches
- [Magnetometer-Based Drift Correction During Rest](https://www.ncbi.nlm.nih.gov/pmc/articles/PMC6471153/) — Rest-state drift compensation techniques
- [Evaluation of Drift Correction Strategies](https://www.diva-portal.org/smash/get/diva2:1366127/FULLTEXT01.pdf) — ZUPT and absolute measurement strategies
- [How IMU Sensor Fusion Works (SageMotion)](https://www.sagemotion.com/blog/how-does-imu-sensor-fusion-work) — Practical sensor fusion overview
- [Using Tilt as a Game Interface](https://dl.acm.org/doi/10.1145/1394021.1394031) — UX research on tilt controls for mobile games

## Appendix C: Vivarium Source Files Referenced

- `GyroInputProvider.cs` — Gravity-based 2D tilt with atan2 decomposition, Z-compensation, rest detection, auto-recalibration
- `AttitudeInputProvider.cs` — Quaternion-based 3DOF look with canonical frame preservation
- `VivariumCameraConfig.cs` — Comprehensive tuning parameters (ScriptableObject)
- `LookRadiusProcessor.cs` — Soft/hard clamping of look offset within radius
- `EdgeShiftStrategy.cs`, `FlickPanStrategy.cs`, `MomentumDriftStrategy.cs`, `HybridStrategy.cs` — Movement strategies
- `CameraDebugOverlay.cs`, `CameraDebugSliders.cs` — Debug tooling
