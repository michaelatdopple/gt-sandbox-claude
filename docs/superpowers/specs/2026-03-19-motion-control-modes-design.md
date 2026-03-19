# Loop.Motion Control Modes — Design Spec

**Date:** 2026-03-19
**Branch:** `imu-motion-api-review`
**Status:** Design Review

---

## 1. Problem

The current `Loop.motion` API streams raw IMU sensor data to games: gravity vectors, orientation quaternions, angular velocity. This works for developers who understand sensor physics, but the typical 3P game developer building via dopple-studio wants "tilt the device to move a marble" — not quaternion math.

Every game that uses motion controls today must implement its own:
- **Calibration** — capturing a "neutral" reference position (currently done ad-hoc by taking the first reading, which is noisy and fragile)
- **Drift correction** — the user's natural resting position shifts over a play session, causing "neutral" to creep
- **Coordinate transforms** — converting raw sensor values to usable -1..1 game input
- **Deadzone** — rejecting sensor noise when the device is held still
- **Z-rotation compensation** — preventing axis swap when the user holds the device at a different wrist angle

This is boilerplate that 90% of games need and 90% of developers will get wrong. The SDK should provide it.

## 2. Solution

Add **high-level control modes** to the `Loop.motion` API. Each mode maps to a physical gesture, handles all sensor processing internally, and emits clean, game-ready input. The existing raw API stays unchanged as an escape hatch.

Four modes:
- **`tilt`** — 2D gravity-based tilt (marble maze, cursor, movement)
- **`look`** — 3DOF orientation-based gaze (panorama, FPS aiming)
- **`rotate`** — single-axis rotation with gesture-named axes (steering, dials, body turn)
- **`raw`** — existing behavior, unchanged

All processing happens in **JavaScript** — no native (Kotlin) changes. The mode controllers are JS classes that consume the existing raw `loop:motion` events and process them into clean output. This means:
- No native rebuild required for iteration
- 3P developers can inspect the SDK source when debugging
- A browser-based simulator/playground becomes trivial (no native bridge needed)
- Hot paths can be moved to Kotlin later if profiling shows a need

## 3. Target Audience

3P game developers who build games externally and deploy to Loop via dopple-studio. The SDK (including the `.d.ts` type definitions) is their primary interface. API naming and documentation must be understandable without IMU expertise.

## 4. API Surface

The `Loop.motion` object gains three mode methods alongside the existing raw API:

```typescript
// Mode methods (new)
Loop.motion.tilt(options?: TiltOptions): Promise<TiltController>
Loop.motion.look(options?: LookOptions): Promise<LookController>
Loop.motion.rotate(options?: RotateOptions): Promise<RotateController>

// Raw (existing behavior, new alias)
Loop.motion.raw(options?: MotionOptions): Promise<MotionSubscription>

// Utility (existing, unchanged)
Loop.motion.isSupported(): boolean
Loop.motion.stopAll(): void
Loop.motion.getStatus(): MotionStatus
```

**Constraints:**
- Only one mode controller active at a time. Starting a new mode stops the previous one automatically. The developer does not need to call `stop()` first, but it is not an error to do so.
- `raw()` is an alias for the existing `start()` method, which remains for backwards compatibility. Multiple raw subscriptions are still allowed (existing behavior). Raw subscriptions do NOT participate in the one-mode-at-a-time constraint — starting a mode controller does not stop existing raw subscriptions, and vice versa. `raw()` returns a `MotionSubscription` (existing type), not a `MotionController`.
- `stopAll()` stops both mode controllers and raw subscriptions.

## 5. Mode Definitions

### 5.1 Tilt — "The device IS the control surface"

**Physical gesture:** The user holds the Loop on their wrist at a comfortable angle. Tilting the device away from that neutral position produces input. Tilting right = input right. Returning to neutral = input returns to center.

**Why this gesture works for a wrist device:** The Loop is worn on the wrist, so the natural interaction is tilting the forearm. The range of comfortable tilt is roughly ±30° in any direction, which maps well to a -1..1 normalized range.

**Sensor:** Gravity vector from the accelerometer (`MotionData.smoothGravity`). At rest, gravity points straight down through the device. When tilted, gravity's projection onto the device's X/Y plane shifts. The controller decomposes gravity into tilt angles using `atan2` (not `asin` — `atan2` gives symmetric response at any resting angle, as validated in the Vivarium camera system). The delta from the calibrated reference = tilt input.

**Why gravity, not gyroscope or quaternion:** Gravity is an *absolute* reference. A device held still at 15° tilt reads 15° forever. There is no integration, no accumulated error, no drift. This makes tilt the most reliable mode for sustained positional control. Gyroscope-based tracking drifts within seconds without correction. Quaternion orientation (from `TYPE_ROTATION_VECTOR`) is better but still has yaw drift — and tilt mode doesn't need yaw.

**Auto-recalibration:** Over a long play session, the user's resting wrist position shifts. Without recalibration, "neutral" drifts and the user must hold an uncomfortable compensating angle. The controller uses a three-tier adaptive recalibration rate (ported from the Vivarium `GyroInputProvider`):
- **Device at rest** (detected via gravity variance < 0.0002 for 400ms): Fast correction (3.0/sec) — snaps reference to current position quickly during natural pauses
- **Near center** (small tilt magnitude): Moderate correction — rate proportional to how centered the input is
- **Full tilt** (user actively tilting): Frozen (0.02/sec) — never fights the user's intentional input

**Z-rotation compensation:** The Loop is wrist-worn, meaning the screen's orientation relative to gravity varies constantly (palm down, palm sideways, etc.). If the user recalibrates while holding their wrist at a rotated angle, the tilt axes would swap or invert without compensation. The controller captures the gravity Z-angle at calibration time and applies a 2D rotation matrix to map tilt delta back to the original axis orientation. This technique is ported from Vivarium's `GyroInputProvider.ComputeScreenZAngle()`.

**Output:**
```typescript
interface TiltInput {
  x: number;          // -1..1, left/right tilt relative to neutral
  y: number;          // -1..1, forward/backward tilt relative to neutral
  magnitude: number;  // 0..1, distance from center (for "how far tilted")
  atRest: boolean;    // true when device gravity variance is below threshold
  timestamp: number;  // DOMHighResTimeStamp in milliseconds
}
```

**Use cases:** Marble/ball maze, top-down character movement, menu cursor navigation, parallax depth effects, balance games, fishing rod angle.

**What tilt is NOT good for:** Looking around freely (limited to ~±30° practical range), continuous 360° rotation, fast flick-based input, detecting horizontal body rotation (gravity doesn't change when you spin).

### 5.2 Look — "The device IS a window into a world"

**Physical gesture:** The user holds the device and rotates it in space to look around — like holding a phone up and turning to see a panorama. The screen shows what's "behind" it in the virtual world. Point left, see what's to the left.

**Sensor:** Full quaternion orientation from `MotionData.orientation` (sourced from Android's `TYPE_ROTATION_VECTOR`, which implements hardware-accelerated sensor fusion). The controller captures a reference quaternion at calibration, then computes the delta: `inverse(reference) * current`. From this delta quaternion it extracts yaw (horizontal look) and pitch (vertical look).

**Why quaternion, not gravity:** Gravity cannot detect yaw — horizontal rotation. If the user spins in their chair while holding the device level, gravity doesn't change at all. Quaternion orientation from the rotation vector sensor captures all three rotation axes. This is the same sensor that AR frameworks like ARCore and Vuforia use for 3DOF phone tracking.

**The drift problem and how we handle it:** Unlike gravity (an absolute reference), the quaternion's yaw component relies on gyroscope integration, which drifts. Over minutes, "forward" creeps. We handle this three ways:

1. **Auto-recalibration** — same adaptive three-tier approach as tilt, but using `Quaternion.slerp()` instead of `Vector3.lerp()`. Near-center = moderate slerp toward current orientation. At rest = fast slerp. Active looking = frozen.

2. **Canonical frame preservation** — ported from Vivarium's `AttitudeInputProvider`. On first calibration, the controller captures a "canonical" reference orientation. On subsequent recalibrations, the canonical frame is preserved and look input is remapped through it using signed angle projection. This prevents axis swap/flip when the user recalibrates at a rotated orientation.

3. **Player Space gyro** — ported from GamepadMotionHelpers. For the yaw component, projects gyro onto the gravity axis with a relaxation factor (1.41×), then clamps to actual gyro magnitude. This loosely gravity-corrects yaw without over-relying on a potentially noisy gravity estimate. Pitch stays in local space (drift-free since gravity bounds it). The algorithm:
   ```
   worldYaw = -(gravY * gyroY + gravZ * gyroZ)
   y = sign(worldYaw) * min(|worldYaw| * 1.41, sqrt(gyroY² + gyroZ²))
   x = gyroX  // local pitch, unmodified
   ```

**Output:**
```typescript
interface LookInput {
  yaw: number;        // degrees from neutral (horizontal rotation)
  pitch: number;      // degrees from neutral (vertical rotation)
  x: number;          // -1..1, normalized yaw (for games that want tilt-style output)
  y: number;          // -1..1, normalized pitch
  magnitude: number;  // 0..1
  atRest: boolean;
  timestamp: number;
}
```

Both degrees and normalized values are provided because different games need different things: a panorama viewer wants degrees for camera angle mapping, while a UI cursor wants -1..1 normalized input. Providing both avoids a configuration option.

**Use cases:** Panorama viewer, FPS-style gyro aiming (proven on Steam Deck / Switch), stargazing apps, 3D object inspection, tower defense battlefield scanning, VR-lite experiences.

**What look is NOT good for:** Sustained positional control (yaw drift makes it unreliable for "hold at exactly 15° to mean 15°" — use tilt for that), single-axis rotation (use rotate), anything needing absolute orientation without periodic recalibration.

### 5.3 Rotate — "Single-axis rotation"

**Physical gesture:** The user rotates the device around one axis. The default is wrist twist (rolling the forearm). Also supports body turn (yaw — spinning or swiveling) and lean (pitch — nodding forward/back).

**Why a separate mode from tilt:** Tilt gives 2D output (X and Y simultaneously). Rotate gives 1D output (single angle). The math is simpler, the API is cleaner, and auto-recalibration can be more aggressive since there's only one axis to track. A racing game doesn't want to deal with a 2D vector when it only needs steering angle.

**Axis options:** Named after the physical gesture (not the physics term) for 3P developer clarity:

| Gesture name | Technical alias | Physical motion | Sensor source |
|---|---|---|---|
| `twist` (default) | `roll` | Wrist rotation, like turning a doorknob | Gravity — `atan2(gravity.x, -gravity.z)`. Drift-free. |
| `turn` | `yaw` | Body/vertical rotation, like spinning in a chair | Quaternion yaw extraction. Subject to drift, auto-recal compensates. |
| `lean` | `pitch` | Forward/backward tilt, like nodding | Gravity — `atan2(gravity.y, -gravity.z)`. Drift-free. |

The type accepts both gesture and technical names:
```typescript
type RotateAxis = 'twist' | 'turn' | 'lean'     // primary, documented
               | 'roll'  | 'yaw'  | 'pitch';    // aliases, accepted but not promoted
```

Gesture names are used in all documentation, examples, and JSDoc. Technical aliases exist for developers who come from IMU/robotics backgrounds and think in those terms.

**Output:**
```typescript
interface RotateInput {
  angle: number;      // degrees from neutral
  value: number;      // -1..1, normalized by maxAngle
  atRest: boolean;
  timestamp: number;
}
```

**Use cases:** Racing/steering, dial/knob interactions, turret control, compass scanning (turn), rotation puzzles, fishing reel.

### 5.4 Raw — "Give me everything"

**Physical gesture:** Any — the game decides what the sensor data means.

**Output:** The existing `MotionData` payload, unchanged:
```typescript
interface MotionData {
  gravity: Vector3;        // raw accelerometer (m/s²)
  smoothGravity: Vector3;  // EMA-filtered gravity
  orientation: Quaternion;  // device orientation quaternion
  delta: Vector3;          // angular velocity (rad/s)
  timestamp: number;
  sequenceNumber: number;
}
```

`Loop.motion.raw()` is an alias for the existing `Loop.motion.start()`. Both continue to work. This mode exists as an escape hatch for the 10% of developers who need custom sensor processing — gesture recognition, rhythm game beat detection, porting existing IMU code from another platform.

## 6. Options

### 6.1 TiltOptions

```typescript
interface TiltOptions {
  /** Update frequency in Hz. Higher = smoother but more CPU.
   *  30 for casual games, 60 for action, 120 for competitive.
   *  @default 60 */
  frequency?: number;

  /** Gravity smoothing factor (EMA alpha). 0.0 = maximum smoothing (laggy but stable),
   *  1.0 = no smoothing (responsive but jittery). Lower if input feels noisy.
   *  NOTE: This is an EMA alpha value — lower = more smoothing, higher = less.
   *  This matches the existing Loop.motion.start() smoothing parameter convention.
   *  @default 0.1 */
  smoothing?: number;

  /** Maximum tilt angle (degrees) that maps to full input (±1).
   *  Smaller = more sensitive. Larger = more range but bigger tilts needed.
   *  @default 25 */
  maxAngle?: number;

  /** Ignore tilt below this many degrees. Prevents jitter at rest.
   *  Uses remapped deadzone: values below the threshold are zero, values above
   *  are linearly rescaled so the output still reaches ±1 smoothly (no jump
   *  at the deadzone boundary). Applied to X and Y independently.
   *  Set to 0 to disable.
   *  @default 2 */
  deadzone?: number;

  /** Per-axis sensitivity scaling. Compensates if one direction feels
   *  too sensitive or sluggish relative to the other.
   *  @default { x: 1, y: 1 } */
  sensitivity?: { x: number; y: number };

  /** Continuously adjust the neutral reference to compensate for drift.
   *  Disable for games that need a fixed reference angle.
   *  @default true */
  autoRecalibrate?: boolean;
}
```

### 6.2 LookOptions

```typescript
interface LookOptions {
  /** @default 60 */
  frequency?: number;

  /** @default 0.1 */
  smoothing?: number;

  /** Maximum look angle (degrees) that maps to full input (±1).
   *  @default 45 */
  maxAngle?: number;

  /** @default 1 */
  deadzone?: number;

  /** @default { x: 1, y: 1 } */
  sensitivity?: { x: number; y: number };

  /** @default true */
  autoRecalibrate?: boolean;
}
```

### 6.3 RotateOptions

```typescript
interface RotateOptions {
  /** Which rotation axis to track. Gesture-named for clarity.
   *  'twist' = wrist rotation (drift-free).
   *  'turn' = body/vertical rotation (drifts, auto-recal compensates).
   *  'lean' = forward/backward tilt (drift-free).
   *  Technical aliases ('roll', 'yaw', 'pitch') also accepted.
   *  @default 'twist' */
  axis?: RotateAxis;

  /** @default 60 */
  frequency?: number;

  /** @default 0.1 */
  smoothing?: number;

  /** Maximum rotation angle (degrees) that maps to full value (±1).
   *  For 'twist' on a wrist, practical comfort range is about ±60°.
   *  @default 45 */
  maxAngle?: number;

  /** @default 2 */
  deadzone?: number;

  /** Single-axis sensitivity. Unlike tilt/look, rotate only has one axis.
   *  @default 1.0 */
  sensitivity?: number;

  /** @default true */
  autoRecalibrate?: boolean;
}
```

Note: `sensitivity` is `{ x, y }` for 2D modes (tilt, look) but a single `number` for 1D mode (rotate). This keeps each mode's options minimal — a rotate user never has to think about which axis is X vs Y.

## 7. Controller Interface

All mode controllers (tilt, look, rotate) implement a shared base interface:

```typescript
interface MotionController {
  /** Which mode this controller is running */
  readonly mode: 'tilt' | 'look' | 'rotate';

  /** True after calibration completes (~750ms after creation) */
  readonly calibrated: boolean;

  /** True while the controller is active (false after stop()) */
  readonly active: boolean;

  /** The most recent input value, or null before calibration completes.
   *  Useful for polling in render loops instead of caching event values. */
  readonly lastInput: TiltInput | LookInput | RotateInput | null;

  /** Re-run calibration from the current device position.
   *  Input continues flowing using the old reference during recalibration.
   *  When the new reference is ready, input transitions smoothly — no gap.
   *  The 'calibrated' event fires again when complete. */
  recalibrate(): void;

  /** Freeze auto-recalibration. The neutral reference stays fixed.
   *  Use during cutscenes, menus, or "hold this angle to confirm" interactions. */
  pauseRecalibration(): void;

  /** Resume auto-recalibration after pausing. */
  resumeRecalibration(): void;

  /** Subscribe to processed input. Events begin after calibration completes. */
  on(event: 'input', handler: (input: TiltInput | LookInput | RotateInput) => void): this;

  /** Fires when calibration completes (initial or after recalibrate()). */
  on(event: 'calibrated', handler: () => void): this;

  /** Fires when the device rest state changes. */
  on(event: 'rest', handler: (atRest: boolean) => void): this;

  off(event: string, handler: Function): this;

  /** Stop this controller and release resources. */
  stop(): void;
}
```

Each concrete controller type (`TiltController`, `LookController`, `RotateController`) narrows the `on('input')` handler type to its specific input type for TypeScript safety.

## 8. Controller Lifecycle

### Start → Calibrate → Stream → Stop

```
Loop.motion.tilt(opts)
  → Promise resolves immediately with TiltController
    (controller is in calibrating state: calibrated=false, active=true)
  → Internally: raw subscription created, calibration begins
  → ~750ms: calibration completes (15 frames skipped + 30 frames averaged at 60Hz)
  → 'calibrated' event fires (optional — most games don't listen for this)
  → 'input' events begin flowing at configured frequency
  → ...game runs...
  → controller.stop()
  → Internal raw subscription cleaned up, events stop
```

The promise resolves as soon as the controller is constructed and the internal raw subscription is established. Calibration proceeds asynchronously. Input events do not fire until calibration completes. This means `await Loop.motion.tilt()` returns near-instantly — the ~750ms calibration happens in the background while the game finishes setup.

### Behavioral details

**Handler registration during calibration:** The handler is stored but no events fire. The first `input` event arrives after calibration completes. No errors, no special handling needed by the developer.

**Starting a new mode while one is active:** The previous controller is stopped automatically. Only one mode at a time. Example: calling `Loop.motion.look()` while a `TiltController` is active stops the tilt controller, then starts look.

**Recalibration during active input:** Input continues flowing with the *old* reference during the recalibration window. `recalibrate()` skips the skip phase (sensor is already warmed up) and runs only the 30-frame collection phase (~500ms at 60Hz). When the new reference is established, input transitions to the new reference. There is no gap or discontinuity. The `calibrated` event fires again.

**Auto-recalibration pause/resume:** `pauseRecalibration()` freezes the neutral reference in place. Useful for "hold the device at this angle to select" interactions where drift correction would fight the user. `resumeRecalibration()` re-enables adaptive correction.

## 8.5 Error States

**`isSupported()` returns false but mode method called:** The promise rejects with an error: `"Motion sensors not available on this device"`. Games should check `isSupported()` first, but the rejection provides a clear diagnostic.

**Frequency out of range (1-240 Hz):** Clamped silently to the valid range. Matches existing `start()` behavior.

**Activity pauses (Android lifecycle):** The native `IMUSensorManager` unregisters sensors on pause and re-registers on resume. The JS controller stays alive but receives no events during pause. On resume, sensor data resumes flowing. If `autoRecalibrate` is enabled, the rest-detection fast rate (3.0/sec) will quickly re-center the reference since the user's wrist position likely shifted. If `autoRecalibrate` is disabled, the game should call `recalibrate()` on resume if position accuracy matters.

**Sensor becomes unavailable mid-session:** This is extremely rare on Loop hardware (sensors are always present). If it happens, the controller stops receiving events. No error event is emitted — the game simply stops getting `input` callbacks. `getStatus()` can be polled to detect this.

## 9. Calibration Strategy

### Why calibration matters

Raw IMU sensors produce noisy startup readings. The first ~200-500ms of data after sensor enable contains transient noise that doesn't represent the device's actual orientation. Games that use "first reading = neutral" (as Quarto does today) capture a potentially inaccurate reference.

### The three-phase approach (ported from Vivarium)

1. **Skip phase** — discard the first 15 frames (~250ms at 60Hz). The IMU hardware needs stabilization time after being enabled. These readings are garbage.

2. **Collection phase** — average the next 30 frames (~500ms at 60Hz). For gravity-based modes (tilt, rotate/twist, rotate/lean), this is a simple vector average. For quaternion-based modes (look, rotate/turn), quaternions are averaged using hemisphere-normalized sum (flip sign if dot product < 0 to prevent averaging across the quaternion double-cover).

3. **Reference established** — the averaged value becomes the neutral reference. Input processing begins. The `calibrated` event fires.

### Auto-recalibration (continuous, invisible)

After calibration, the reference is continuously adjusted to track the user's natural drift:

- **Rate selection** is based on input magnitude and rest state:
  - `atRest` → fast rate (3.0 corrections/sec) — during natural pauses, snap quickly
  - Near center (low magnitude) → moderate rate (proportional to centrality)
  - Full tilt (high magnitude) → frozen rate (0.02/sec) — never fight active input
- **Interpolation method** depends on mode (both use frame-rate-independent exponential decay):
  - Gravity reference: `reference = lerp(reference, current, 1 - exp(-rate * deltaTime))`
  - Quaternion reference: `reference = slerp(reference, current, 1 - exp(-rate * deltaTime))`

### Explicit recalibration

Games can trigger recalibration via `controller.recalibrate()`. This re-runs the full three-phase calibration from scratch. A common pattern:

```typescript
Loop.buttons.on('B', () => controller.recalibrate());
```

### Why calibration is mode-scoped

Calibration is a method on the controller, not a standalone API, because the calibration data is mode-specific:
- Tilt calibrates a gravity reference vector
- Look calibrates a quaternion reference with canonical frame tracking
- Rotate calibrates a single-axis angle reference

A standalone `Loop.motion.calibrate()` would be ambiguous — calibrate what, for which mode?

## 10. Architecture

### Data flow

```
Android IMU Sensors (hardware)
    │
    ▼
IMUSensorManager.kt (Kotlin, unchanged)
  - Registers TYPE_ACCELEROMETER, TYPE_GYROSCOPE, TYPE_ROTATION_VECTOR
  - Dedicated HandlerThread for sensor callbacks
  - Rate-limited dispatch at configured frequency
  - EMA smoothing on gravity
  - Builds IMUData, serializes to JSON (<1ms)
    │
    ▼
WebView.evaluateJavascript() (UI thread)
  - Dispatches: window.dispatchEvent(new CustomEvent('loop:motion', {detail: data}))
    │
    ▼
Loop SDK global listener (JavaScript, existing)
  - Routes to all active MotionSubscription instances
    │
    ▼
Mode Controller (JavaScript, NEW)
  - TiltController / LookController / RotateController
  - Creates internal raw subscription
  - Processes each MotionData frame through:
    1. RestDetector.update() → atRest flag
    2. Calibrator (if still calibrating) → skip/collect/average
    3. AutoRecalibrator.update() → adjust reference
    4. ModeProcessor.process() → {x, y} or {angle, value} or {yaw, pitch}
    5. Deadzone filter
  - Emits 'input' event with processed output
    │
    ▼
Game handler: controller.on('input', (input) => { ... })
```

### File structure

```
sdk/
  loop-sdk.ts              — existing file, gains .tilt()/.look()/.rotate()/.raw()
  motion/
    types.ts               — TiltOptions, TiltInput, LookOptions, etc.
    calibrator.ts           — multi-frame reference capture (shared)
    auto-recalibrator.ts    — three-tier adaptive drift correction (shared)
    rest-detector.ts        — circular buffer gravity variance (shared)
    tilt-processor.ts       — gravity → atan2 → normalized tilt, Z-compensation
    look-processor.ts       — quaternion inverse multiply → yaw/pitch, canonical frame, Player Space gyro
    rotate-processor.ts     — single-axis extraction (delegates to tilt or look math by axis)
    tilt-controller.ts      — TiltController class
    look-controller.ts      — LookController class
    rotate-controller.ts    — RotateController class
```

Rollup bundles everything into the existing `loop-sdk.js` asset. No additional script injection needed.

### Shared internals

Three classes are shared across all mode controllers:

**Calibrator** — encapsulates the skip-collect-average sequence. Parameterized by data type (Vector3 for gravity, Quaternion for orientation). Fires a callback when reference is established.

**AutoRecalibrator** — implements the three-tier rate selection. Parameterized by interpolation method (lerp for vectors, slerp for quaternions). Accepts `pause()` / `resume()`.

**RestDetector** — maintains a circular buffer of gravity readings (30 samples), computes variance each frame. Below threshold (0.0002) for settle time (400ms) = at rest. Any motion = immediate exit (no exit hysteresis). Ported from Vivarium's `GyroInputProvider.UpdateRestDetection()`.

## 11. Native Foundation (unchanged — for reviewer context)

The mode controllers are built on top of an existing, tested native IMU pipeline. No native changes are required for this feature.

### Bridge contract (`bridge-contract.yaml`)

The `motion` namespace is scope `public` with 7 native methods: `subscribe`, `unsubscribe`, `setFrequency`, `setSmoothingAlpha`, `getStatus`, `getLatest`, `getSensorAvailability`. The code generator (`generate-bridge-contracts.mjs`) produces the `IMUNamespaceContract` Kotlin interface and TypeScript bridge types.

### IMUSensorManager.kt (~405 lines)

Owns the Android sensor lifecycle. Registers `TYPE_ACCELEROMETER`, `TYPE_GYROSCOPE`, and `TYPE_ROTATION_VECTOR` at `SENSOR_DELAY_FASTEST` on a dedicated `HandlerThread("IMUSensorThread")`. Rate-limits event dispatch to the configured frequency (default 60Hz). Applies EMA smoothing to gravity. Manages subscription reference counting (first subscribe starts sensors, last unsubscribe stops them). Handles pause/resume for Android activity lifecycle via weak references.

Has stillness detection infrastructure (threshold 0.05 rad/s, duration 0.5s) and a complementary filter factor (0.98), but `applyDriftCorrection()` is currently a no-op — it relies on `TYPE_ROTATION_VECTOR`'s built-in hardware Kalman filter for orientation fusion.

### IMUData.kt (~73 lines)

Data classes: `Vector3(x, y, z)`, `Quaternion(x, y, z, w)`, `IMUData`. Hand-built JSON serialization via `buildString { append(...) }` for performance (<1ms target). Carries both `timestamp` (ms, for JS) and `sensorTimestamp` (ns, raw Android).

### WebAppInterface.kt

The `IMUNamespace` inner class implements `IMUNamespaceContract` with `@JavascriptInterface` annotations. All 7 methods are thin pass-throughs to `IMUSensorManager`. Events are dispatched as `window.dispatchEvent(new CustomEvent('loop:motion', {detail: <json>}))`.

### Why no native changes

The native layer already provides everything the mode controllers need:
- `gravity` / `smoothGravity` → used by tilt and rotate (twist/lean) modes
- `orientation` quaternion → used by look and rotate (turn) modes
- `delta` (angular velocity) → used by Player Space gyro calculation
- Configurable frequency and smoothing → passed through from mode options

Performance is validated: IMU dispatch latency is 8.67ms P50 / 29.13ms P95 at 120Hz with zero dropped frames. The JS processing per frame is a handful of multiplies and one `atan2` — sub-microsecond overhead.

### Source files for reviewer reference

- `bridge-contract.yaml` — API surface definition
- `app/src/main/java/com/dopple/webview/bridge/IMUSensorManager.kt`
- `app/src/main/java/com/dopple/webview/bridge/IMUData.kt`
- `app/src/main/java/com/dopple/webview/bridge/WebAppInterface.kt`
- `sdk/loop-sdk.ts` — existing SDK (where new mode methods are added)
- `sdk/loop-sdk-dx.d.ts` — existing DX types (where new types are added)

## 12. Open Source References

The processing algorithms in the mode controllers are ported from three sources:

### x-io Fusion (Madgwick AHRS) — MIT license
- **Gyro bias estimation:** Detects stillness (all axes < 3.0 dps for 5 seconds), runs 0.02 Hz high-pass filter to extract DC drift, subtracts from readings. Automatic, no explicit calibration step needed. ~76 lines of C to port.
- **Acceleration rejection:** Compares predicted gravity (from current quaternion) vs measured gravity. If error exceeds threshold, ignores accelerometer for that frame. Hysteretic counter (+1 per rejection, -9 per acceptance) prevents oscillation. Recovery mechanism forces re-acceptance if rejection exceeds 90% over configurable period.
- Source: https://github.com/xioTechnologies/Fusion

### GamepadMotionHelpers (JibbSmart) — MIT license
- **Player Space gyro:** Projects yaw onto gravity axis with 1.41× relaxation factor, clamps to raw gyro magnitude. Pitch stays local. Minimizes yaw drift while feeling responsive.
- **Combined calibration:** Stillness detection + sensor fusion (cross-checking gyro against accelerometer-derived angular velocity) with per-axis authority detection and confidence tracking.
- Source: https://github.com/JibbSmart/GamepadMotionHelpers

### Vivarium camera system (internal)
- **Multi-frame calibration:** Skip 15 frames + average 30 frames for stable reference.
- **Three-tier auto-recalibration:** Rest/near-center/full-tilt adaptive rate.
- **Z-rotation compensation:** 2D rotation matrix from gravity Z-angle projection.
- **Canonical frame preservation:** Signed angle remapping for quaternion-based input.
- Source: `/home/claude/gt/vivarium/refinery/rig/Unity/Vivarium/Assets/Scripts/Camera/`

## 13. What's NOT in Scope

- **`flat` mode** (accelerometer position tracking) — double integration of accelerometer data drifts too fast without external references (camera, beacons). Not viable for reliable game input.
- **Native-side processing** — all mode logic in JS for iteration speed and 3P debuggability. Can be moved to Kotlin later if profiling justifies it.
- **Magnetometer** — Loop hardware doesn't reliably expose it; not needed for current use cases.
- **Custom Kalman filter** — Android's `TYPE_ROTATION_VECTOR` already provides hardware-fused orientation via an on-chip Kalman filter. Running another one on top would add latency with marginal accuracy benefit.
- **x-io Fusion gyro bias estimation and acceleration rejection** — referenced in Section 12 as prior art. These algorithms are designed for raw IMU fusion (building orientation from scratch). Since we use Android's `TYPE_ROTATION_VECTOR` (hardware-fused), gyro bias is already handled. These remain reference material for a potential future native-side processing path, not initial implementation scope.
- **Motion prediction** — extrapolating orientation ahead by a few ms to reduce perceived latency. Valuable for VR headsets (20ms+ display latency) but unnecessary for Loop's direct-to-WebView pipeline (8.67ms P50).

## 14. Example Usage

### Marble maze (tilt)
```typescript
const tilt = await Loop.motion.tilt({ maxAngle: 20, deadzone: 2 });
tilt.on('input', ({ x, y }) => {
  marble.vx += x * gravity;
  marble.vy += y * gravity;
});

// Recalibrate between levels
Loop.buttons.on('B', () => tilt.recalibrate());
```

### Panorama viewer (look)
```typescript
const look = await Loop.motion.look({ maxAngle: 60 });
look.on('input', ({ yaw, pitch }) => {
  camera.rotation.y = yaw;
  camera.rotation.x = pitch;
});
```

### Racing game (rotate)
```typescript
const steer = await Loop.motion.rotate({ axis: 'twist', maxAngle: 35 });
steer.on('input', ({ value }) => {
  car.steerAngle = value * maxSteering;
});
```

### Turret scanner (rotate with turn)
```typescript
const scan = await Loop.motion.rotate({ axis: 'turn', maxAngle: 90 });
scan.on('input', ({ angle }) => {
  turret.rotation = angle;
  radar.sweep(angle);
});
```

### Parallax effect (tilt, low frequency)
```typescript
const tilt = await Loop.motion.tilt({ frequency: 30, maxAngle: 15 });
tilt.on('input', ({ x, y }) => {
  backgroundLayer.x = x * parallaxStrength;
  foregroundLayer.x = -x * parallaxStrength * 0.5;
});
```

### Custom gesture detection (raw)
```typescript
const raw = await Loop.motion.raw({ frequency: 120 });
raw.on('data', (data) => {
  shakeDetector.feed(data.delta);
  if (shakeDetector.detected()) onShake();
});
```
