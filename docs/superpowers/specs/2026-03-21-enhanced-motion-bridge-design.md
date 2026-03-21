# Enhanced Motion Bridge Design

**Status:** Draft
**Date:** 2026-03-21

## Problem

The native bridge (`IMUSensorManager`) fires a custom `loop:motion` event with a non-standard payload shape. This has several issues:

1. **Mislabeled data**: `TYPE_ACCELEROMETER` is labeled "gravity" — it includes linear acceleration, causing noisy tilt.
2. **Broken sensor routing**: The `onSensorChanged` `when` branch only matches `TYPE_ROTATION_VECTOR`, silently dropping `TYPE_GAME_ROTATION_VECTOR` events (the default). Orientation quaternion stays at identity.
3. **Dead code**: `fusedOrientation`, `updateSensorFusion()`, `applyDriftCorrection()` are wired up but never produce output.
4. **Custom shape**: Developers must learn a proprietary event format instead of using the W3C `DeviceOrientationEvent` / `DeviceMotionEvent` shape they already know.
5. **Missing pan mode**: The launcher prototype proved that panning (edge absorption, no auto-recalibration) is a distinct interaction from looking (auto-recalibration, clamp). The SDK only has `look()`.

A prototype developer abandoned `Loop.motion` entirely and used the browser's `deviceorientation` event because it provided pre-fused Euler angles with zero setup. Our old API gave raw quaternions requiring ~100 lines of math to get usable tilt values.

## Solution

Rewrite the native bridge to fire two events — `loop:orientation` and `loop:motion` — whose payloads mirror the W3C `DeviceOrientationEvent` and `DeviceMotionEvent` field structure. Both browser native events and our bridge events fire simultaneously, allowing third-party drift comparison.

### Enhancements over Chromium's implementation

| Enhancement | Mechanism |
|---|---|
| Free-rotate stability | Skip screen-orientation compensation in Euler conversion |
| Frequency control | Rate limiter 1-240Hz via `Loop.motion.setFrequency()` |
| Dedicated sensor thread | Existing `IMUSensorThread` — sensors never touch UI thread |
| Sensor fusion selection | `setSensorFusion('game'\|'full')` hot-swaps rotation vector sensor |
| Clean gravity | `TYPE_GRAVITY` from HAL instead of EMA-filtered accelerometer |

## Architecture

### Native Bridge (Kotlin)

**Sensors registered (5):**

| Android Sensor | Maps To |
|---|---|
| `TYPE_GAME_ROTATION_VECTOR` | `loop:orientation` → alpha, beta, gamma (quaternion → Euler) |
| `TYPE_ACCELEROMETER` | `loop:motion` → `accelerationIncludingGravity` |
| `TYPE_LINEAR_ACCELERATION` | `loop:motion` → `acceleration` |
| `TYPE_GYROSCOPE` | `loop:motion` → `rotationRate` (converted rad/s → deg/s) |
| `TYPE_GRAVITY` | `loop:motion` → `gravity` (enhancement, not in W3C spec) |

**Euler conversion:** Port of Chromium's `orientation_util.cc` — quaternion → rotation matrix → Euler angles using Z-X'-Y'' intrinsic Tait-Bryan decomposition. Handles four cases: normal, cos(beta) < 0, gimbal lock, cos(gamma) ≈ 0. Screen-orientation compensation is intentionally omitted for free-rotate stability.

**Event payloads:**

```js
// loop:orientation — mirrors DeviceOrientationEvent
{ alpha: 142.3, beta: -12.1, gamma: 5.7, absolute: false }

// loop:motion — mirrors DeviceMotionEvent + gravity enhancement
{
  accelerationIncludingGravity: { x, y, z },  // TYPE_ACCELEROMETER
  acceleration: { x, y, z },                   // TYPE_LINEAR_ACCELERATION
  rotationRate: { alpha, beta, gamma },         // TYPE_GYROSCOPE, deg/s
  interval: 16.67,                              // ms between samples
  gravity: { x, y, z }                         // TYPE_GRAVITY (enhancement)
}
```

**Retained from current bridge:** `IMUSensorThread`, rate limiter, `setSensorFusion()` hot-swap, subscribe/unsubscribe lifecycle, latency logging.

**Removed:** `smoothGravity`, `smoothAlpha`, `updateSmoothedGravity()`, `fusedOrientation`, `updateSensorFusion()`, `applyDriftCorrection()`.

### SDK Integration (TypeScript)

**Event source mapping per mode:**

| Mode | Primary Event | Fields Used |
|---|---|---|
| `look()` | `loop:orientation` | alpha, beta, gamma → internal quaternion |
| `pan()` | `loop:orientation` | same, + edge absorption boundary |
| `tilt()` | `loop:motion` | `gravity` (x, y, z) |
| `rotate()` | `loop:motion` (twist/lean), `loop:orientation` (turn) | gravity or alpha |

**`pan()` — new mode:**

Same core math as `look()` (reference quaternion → delta → yaw/pitch → normalized x,y), with different boundary and calibration behavior:

| Behavior | look() | pan() |
|---|---|---|
| Auto-recalibrate | yes (continuous drift) | no |
| Boundary | clamp at ±1 | edge absorption (slerp ref when magnitude > 1) |
| Recalibration | continuous | explicit only (button press / API call) |
| Use case | aiming, reticle, FPS camera | scrolling, porthole navigation, launcher |

```ts
interface PanOptions {
  maxAngle?: number;                        // default 30
  deadzone?: number;                        // default 2
  sensitivity?: { x: number; y: number };   // default { x: 1, y: 1 }
  absorbRate?: number;                      // edge absorption strength, default 0.3
}

interface PanInput {
  yaw: number;
  pitch: number;
  x: number;       // -1..1 normalized
  y: number;       // -1..1 normalized
  magnitude: number;
  atRest: boolean;
  timestamp: number;
}
```

Edge absorption math: when normalized magnitude exceeds 1, slerp the reference quaternion toward the current orientation by `min(0.5, excess * absorbRate)`. This allows infinite scrolling past the boundary without hard stops, while maintaining firm response within bounds.

**One-mode-at-a-time enforcement:** Calling `pan()` stops any active `look()`/`tilt()`/`rotate()` subscription and vice versa. Same as current behavior.

### Drift Comparison

Browser native `deviceorientation`/`devicemotion` events continue firing alongside our `loop:orientation`/`loop:motion` events. Third-party developers can listen to both and compare:

- **Free-rotate off:** alpha, beta, gamma should match exactly (same sensor, same math).
- **Free-rotate on:** alpha diverges by the display rotation angle (intentional). Beta and gamma should still match.

Any other difference indicates a bug in our Euler conversion. No comparison utility is shipped in the SDK — this is a dev-time diagnostic.

## API Surface

```ts
// Mode methods
Loop.motion.look(options?: LookOptions): Promise<MotionSubscription>
Loop.motion.pan(options?: PanOptions): Promise<MotionSubscription>
Loop.motion.tilt(options?: TiltOptions): Promise<MotionSubscription>
Loop.motion.rotate(options?: RotateOptions): Promise<MotionSubscription>

// Enhancement controls
Loop.motion.setFrequency(hz: number): MotionAPI
Loop.motion.setSensorFusion(type: 'game' | 'full'): void

// Existing (unchanged)
Loop.motion.start(options?: MotionOptions): Promise<MotionSubscription>
Loop.motion.stopAll(): void
Loop.motion.getStatus(): MotionStatus
Loop.motion.getLatest(): MotionData | null
```

## File Changes

### Kotlin (native bridge)

| File | Change |
|---|---|
| `IMUSensorManager.kt` | Add `TYPE_GRAVITY`, `TYPE_LINEAR_ACCELERATION` sensors. Fix `when` branch to match `TYPE_GAME_ROTATION_VECTOR`. Add quaternion → Euler conversion. Fire `loop:orientation` + `loop:motion` with W3C-shaped payloads. Remove EMA filter, `smoothGravity`, dead fusion code. |
| `IMUData.kt` | Replace with two data classes: `OrientationData` (alpha, beta, gamma, absolute) and `MotionEventData` (accelerationIncludingGravity, acceleration, rotationRate, interval, gravity). |

### SDK (TypeScript)

| File | Change |
|---|---|
| `sdk/motion/types.ts` | Add `PanOptions`, `PanInput`. Add `'pan'` to `ModeType` union, `PanInput` to `ModeInput`. Update `MotionData` interface to W3C-shaped fields (accelerationIncludingGravity, acceleration, rotationRate, interval, gravity). |
| `sdk/motion/pan-processor.ts` | New — edge absorption math ported from launcher prototype. |
| `sdk/motion/pan-controller.ts` | New — extends `BaseController`, quaternion pipeline with edge absorption. |
| `sdk/motion/controller.ts` | Update `BaseController` event subscription to support both `loop:orientation` and `loop:motion` event sources depending on mode. |
| `sdk/motion/look-processor.ts` | No interface change — controller converts Euler → quaternion before calling processor. |
| `sdk/motion/look-controller.ts` | Switch event source from `loop:motion` orientation to `loop:orientation`. Convert Euler angles to quaternion before passing to `LookProcessor`. |
| `sdk/motion/tilt-controller.ts` | Switch to `loop:motion` gravity field (now clean HAL data from `TYPE_GRAVITY`). |
| `sdk/motion/rotate-controller.ts` | Same event source switch as tilt. |
| `sdk/loop-sdk.ts` | Add `pan()` method. Wire all modes to new event names. |
| `sdk/loop-sdk-dx.d.ts` | Add `PanOptions`, `PanInput`, `pan()` type declarations. Update `MotionData` type. |

## Breaking Changes

The `loop:motion` event payload shape changes:

| Old field | New field |
|---|---|
| `gravity` (was TYPE_ACCELEROMETER) | `accelerationIncludingGravity` |
| `smoothGravity` | removed |
| `delta` (was rad/s) | `rotationRate` (deg/s) |
| `orientation` (quaternion) | removed from event (use `loop:orientation` Euler instead) |
| — | `acceleration` (new, TYPE_LINEAR_ACCELERATION) |
| — | `gravity` (new, clean TYPE_GRAVITY) |
| — | `interval` (new, ms between samples) |

This is pre-release. Only the SDK explorer and internal games consume `loop:motion` events. Migration path: update field names in consuming code.
