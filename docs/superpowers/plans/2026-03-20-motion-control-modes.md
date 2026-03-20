# Loop.Motion Control Modes — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add high-level motion control modes (tilt, look, rotate) to the Loop SDK that give 3P game developers clean, processed input without IMU expertise.

**Architecture:** Pure JavaScript mode controllers that consume existing raw `loop:motion` events and process them through a pipeline of calibration → auto-recalibration → mode-specific math → deadzone filtering. One native bridge addition (`setSensorFusion`) to switch quaternion sensor type. SDK explorer page for evaluation and parameter tuning.

**Tech Stack:** TypeScript (bundled via Rollup to IIFE), Kotlin (one bridge method), HTML5 Canvas (explorer visualizations)

**Spec:** `docs/superpowers/specs/2026-03-19-motion-control-modes-design.md`

---

## File Structure

### New files (SDK motion processors)

| File | Responsibility |
|---|---|
| `sdk/motion/types.ts` | All type definitions: TiltOptions, TiltInput, LookOptions, LookInput, RotateOptions, RotateInput, RotateAxis, SensorFusion, MotionController interface |
| `sdk/motion/rest-detector.ts` | Circular buffer gravity variance → atRest flag. 30 samples, threshold 0.0002, 400ms settle. |
| `sdk/motion/calibrator.ts` | Three-phase reference capture: skip 15 frames, collect 30, average. Parameterized for Vector3 (gravity) and Quaternion. |
| `sdk/motion/auto-recalibrator.ts` | Three-tier adaptive drift correction. Parameterized by interpolation method. Pause/resume support. Fusion-aware frozen rates. |
| `sdk/motion/tilt-processor.ts` | Gravity → atan2 → normalized tilt with Z-rotation compensation. |
| `sdk/motion/look-processor.ts` | Quaternion delta → yaw/pitch with Player Space gyro, canonical frame preservation. |
| `sdk/motion/rotate-processor.ts` | Single-axis extraction — delegates to tilt math (twist/lean) or look math (turn). |
| `sdk/motion/deadzone.ts` | Remapped deadzone filter: below threshold → zero, above → linearly rescaled to ±1. |
| `sdk/motion/controller.ts` | Base MotionController class: lifecycle, event dispatch, calibration orchestration, lastInput. |
| `sdk/motion/tilt-controller.ts` | TiltController: wires RestDetector + Calibrator + AutoRecalibrator + TiltProcessor + Deadzone. |
| `sdk/motion/look-controller.ts` | LookController: same pipeline with LookProcessor + sensorFusion option. |
| `sdk/motion/rotate-controller.ts` | RotateController: dispatches to tilt or look math based on axis. |

### Modified files

| File | Changes |
|---|---|
| `sdk/loop-sdk.ts` | Add `.tilt()`, `.look()`, `.rotate()`, `.raw()` methods to MotionAPI. Import controllers. |
| `sdk/loop-sdk-dx.d.ts` | Add all new type declarations for 3P developer autocompletion. |
| `bridge-contract.yaml` | Add `setSensorFusion` native method to motion namespace. |
| `sdk/generated/bridge-types.ts` | Regenerated — gains `setSensorFusion` on `Loop$motion` interface. |

### New files (native bridge)

| File | Changes |
|---|---|
| Kotlin `IMUSensorManager.kt` | Add `setSensorFusion()` method. Default orientation sensor to `TYPE_GAME_ROTATION_VECTOR`. |
| Kotlin `IMUNamespace` in `WebAppInterface.kt` | Add `@JavascriptInterface fun setSensorFusion(type: String): Boolean` |

### New files (SDK Explorer)

| File | Responsibility |
|---|---|
| `app/src/main/assets/games/sdk-explorer/index.html` | Single-page explorer app |
| `app/src/main/assets/games/sdk-explorer/manifest.json` | Game manifest for gallery |

### Test approach

Tests run as an HTML page on the device (or in a browser with mocked MotionData). Each processor is a pure function/class with no DOM dependencies — testable by feeding synthetic MotionData frames and asserting output.

| File | What it tests |
|---|---|
| `sdk/motion/__tests__/rest-detector.test.ts` | Variance calculation, settle time, immediate exit |
| `sdk/motion/__tests__/calibrator.test.ts` | Skip phase, collection averaging, quaternion hemisphere normalization |
| `sdk/motion/__tests__/auto-recalibrator.test.ts` | Rate tier selection, exponential decay, pause/resume, fusion-aware rates |
| `sdk/motion/__tests__/tilt-processor.test.ts` | atan2 decomposition, Z-rotation compensation, sensitivity scaling |
| `sdk/motion/__tests__/look-processor.test.ts` | Quaternion delta, Player Space gyro, canonical frame preservation |
| `sdk/motion/__tests__/rotate-processor.test.ts` | Axis dispatch, twist/turn/lean extraction |
| `sdk/motion/__tests__/deadzone.test.ts` | Remapped deadzone, per-axis application, edge cases |
| `sdk/motion/__tests__/controller.test.ts` | Lifecycle, one-mode-at-a-time, calibration events, lastInput |

---

## Phase 1: Shared Infrastructure

### Task 1: Type Definitions

**Files:**
- Create: `sdk/motion/types.ts`

- [ ] **Step 1: Create types file with all interfaces and type aliases**

```typescript
// sdk/motion/types.ts

// === Sensor fusion ===
export type SensorFusion = 'game' | 'full';

// === Rotate axis ===
export type RotateAxis = 'twist' | 'turn' | 'lean' | 'roll' | 'yaw' | 'pitch';

// === Input types ===
export interface TiltInput {
  x: number;
  y: number;
  magnitude: number;
  atRest: boolean;
  timestamp: number;
}

export interface LookInput {
  yaw: number;
  pitch: number;
  x: number;
  y: number;
  magnitude: number;
  atRest: boolean;
  timestamp: number;
}

export interface RotateInput {
  angle: number;
  value: number;
  atRest: boolean;
  timestamp: number;
}

// === Options ===
interface BaseOptions {
  frequency?: number;
  smoothing?: number;
  maxAngle?: number;
  deadzone?: number;
  autoRecalibrate?: boolean;
}

export interface TiltOptions extends BaseOptions {
  sensitivity?: { x: number; y: number };
}

export interface LookOptions extends BaseOptions {
  sensitivity?: { x: number; y: number };
  sensorFusion?: SensorFusion;
}

export interface RotateOptions extends BaseOptions {
  axis?: RotateAxis;
  sensitivity?: number;
  sensorFusion?: SensorFusion;
}

// === Controller interface ===
export type ModeType = 'tilt' | 'look' | 'rotate';
export type ModeInput = TiltInput | LookInput | RotateInput;

export interface MotionController<T extends ModeInput = ModeInput> {
  readonly mode: ModeType;
  readonly calibrated: boolean;
  readonly active: boolean;
  readonly lastInput: T | null;
  recalibrate(): void;
  pauseRecalibration(): void;
  resumeRecalibration(): void;
  on(event: 'input', handler: (input: T) => void): this;
  on(event: 'calibrated', handler: () => void): this;
  on(event: 'rest', handler: (atRest: boolean) => void): this;
  off(event: string, handler: Function): this;
  stop(): void;
}

// === Internal: raw sensor data shape (from existing MotionData) ===
export interface Vector3 { x: number; y: number; z: number; }
export interface Quaternion { x: number; y: number; z: number; w: number; }

export interface MotionData {
  gravity: Vector3;
  smoothGravity: Vector3;
  orientation: Quaternion;
  delta: Vector3;
  timestamp: number;
  sequenceNumber: number;
}
```

- [ ] **Step 2: Verify TypeScript compiles**

Run: `npx tsc --noEmit sdk/motion/types.ts`
Expected: No errors

- [ ] **Step 3: Commit**

```bash
git add sdk/motion/types.ts
git commit -m "feat(motion): add type definitions for control modes"
```

---

### Task 2: Rest Detector

**Files:**
- Create: `sdk/motion/rest-detector.ts`
- Create: `sdk/motion/__tests__/rest-detector.test.ts`

- [ ] **Step 1: Write failing tests**

```typescript
// sdk/motion/__tests__/rest-detector.test.ts
import { RestDetector } from '../rest-detector';

// Test 1: not at rest initially
const rd = new RestDetector();
console.assert(rd.atRest === false, 'should start not at rest');

// Test 2: becomes at rest after stable gravity for 400ms
const rd2 = new RestDetector();
const stableGravity = { x: 0, y: 0, z: 9.81 };
// Feed 30 samples (fills buffer) + enough to reach 400ms settle at 60Hz
for (let i = 0; i < 55; i++) {
  rd2.update(stableGravity, 1/60);
}
console.assert(rd2.atRest === true, 'should be at rest after 400ms of stable gravity');

// Test 3: exits rest immediately on motion
const moving = { x: 1, y: 0, z: 9.81 };
rd2.update(moving, 1/60);
console.assert(rd2.atRest === false, 'should exit rest immediately on motion');

console.log('rest-detector tests passed');
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `npx tsx sdk/motion/__tests__/rest-detector.test.ts`
Expected: FAIL — module not found

- [ ] **Step 3: Implement RestDetector**

```typescript
// sdk/motion/rest-detector.ts
import type { Vector3 } from './types';

const BUFFER_SIZE = 30;
const VARIANCE_THRESHOLD = 0.0002;
const SETTLE_TIME = 0.4; // seconds

export class RestDetector {
  private buffer: Vector3[] = [];
  private index = 0;
  private full = false;
  private settleTimer = 0;
  private _atRest = false;

  get atRest(): boolean { return this._atRest; }

  update(gravity: Vector3, deltaTime: number): void {
    // Write to circular buffer
    if (this.buffer.length < BUFFER_SIZE) {
      this.buffer.push({ ...gravity });
    } else {
      this.buffer[this.index] = { ...gravity };
    }
    this.index = (this.index + 1) % BUFFER_SIZE;
    if (this.index === 0) this.full = true;

    const count = this.full ? BUFFER_SIZE : this.buffer.length;
    if (count < 2) return;

    // Compute mean
    let mx = 0, my = 0, mz = 0;
    for (let i = 0; i < count; i++) {
      mx += this.buffer[i].x;
      my += this.buffer[i].y;
      mz += this.buffer[i].z;
    }
    mx /= count; my /= count; mz /= count;

    // Compute variance
    let variance = 0;
    for (let i = 0; i < count; i++) {
      const dx = this.buffer[i].x - mx;
      const dy = this.buffer[i].y - my;
      const dz = this.buffer[i].z - mz;
      variance += dx * dx + dy * dy + dz * dz;
    }
    variance /= count;

    if (variance < VARIANCE_THRESHOLD) {
      this.settleTimer += deltaTime;
      if (this.settleTimer >= SETTLE_TIME) {
        this._atRest = true;
      }
    } else {
      this.settleTimer = 0;
      this._atRest = false;
    }
  }

  reset(): void {
    this.buffer = [];
    this.index = 0;
    this.full = false;
    this.settleTimer = 0;
    this._atRest = false;
  }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `npx tsx sdk/motion/__tests__/rest-detector.test.ts`
Expected: PASS — "rest-detector tests passed"

- [ ] **Step 5: Commit**

```bash
git add sdk/motion/rest-detector.ts sdk/motion/__tests__/rest-detector.test.ts
git commit -m "feat(motion): add RestDetector with circular buffer variance"
```

---

### Task 3: Calibrator

**Files:**
- Create: `sdk/motion/calibrator.ts`
- Create: `sdk/motion/__tests__/calibrator.test.ts`

- [ ] **Step 1: Write failing tests**

Test cases:
1. Skips first 15 frames (no reference established)
2. After 15+30=45 frames, `hasReference` is true
3. Gravity reference is averaged correctly
4. Quaternion averaging handles hemisphere normalization (flip sign if dot < 0)
5. `reset()` clears state for recalibration
6. Recalibration mode (skipPhase=false) skips the skip phase

- [ ] **Step 2: Run tests to verify they fail**

- [ ] **Step 3: Implement Calibrator**

```typescript
// sdk/motion/calibrator.ts
import type { Vector3, Quaternion } from './types';

const SKIP_FRAMES = 15;
const COLLECT_FRAMES = 30;

export type CalibratorMode = 'vector' | 'quaternion';

export class Calibrator {
  private mode: CalibratorMode;
  private skipCount = 0;
  private collectCount = 0;
  private skipPhase: boolean;
  private _hasReference = false;

  // Vector accumulator
  private sumX = 0;
  private sumY = 0;
  private sumZ = 0;

  // Quaternion accumulator
  private sumQx = 0;
  private sumQy = 0;
  private sumQz = 0;
  private sumQw = 0;
  private firstQuat: Quaternion | null = null;

  private _reference: Vector3 | Quaternion | null = null;

  constructor(mode: CalibratorMode, skipPhase = true) {
    this.mode = mode;
    this.skipPhase = skipPhase;
  }

  get hasReference(): boolean { return this._hasReference; }
  get reference(): Vector3 | Quaternion | null { return this._reference; }

  feedVector(v: Vector3): boolean {
    if (this._hasReference) return true;

    if (this.skipPhase && this.skipCount < SKIP_FRAMES) {
      this.skipCount++;
      return false;
    }

    this.sumX += v.x;
    this.sumY += v.y;
    this.sumZ += v.z;
    this.collectCount++;

    if (this.collectCount >= COLLECT_FRAMES) {
      this._reference = {
        x: this.sumX / COLLECT_FRAMES,
        y: this.sumY / COLLECT_FRAMES,
        z: this.sumZ / COLLECT_FRAMES,
      };
      this._hasReference = true;
      return true;
    }
    return false;
  }

  feedQuaternion(q: Quaternion): boolean {
    if (this._hasReference) return true;

    if (this.skipPhase && this.skipCount < SKIP_FRAMES) {
      this.skipCount++;
      return false;
    }

    // Hemisphere normalization: flip if dot product with first quat < 0
    if (this.firstQuat === null) {
      this.firstQuat = { ...q };
    } else {
      const dot = q.x * this.firstQuat.x + q.y * this.firstQuat.y +
                  q.z * this.firstQuat.z + q.w * this.firstQuat.w;
      if (dot < 0) {
        q = { x: -q.x, y: -q.y, z: -q.z, w: -q.w };
      }
    }

    this.sumQx += q.x;
    this.sumQy += q.y;
    this.sumQz += q.z;
    this.sumQw += q.w;
    this.collectCount++;

    if (this.collectCount >= COLLECT_FRAMES) {
      // Normalize averaged quaternion
      const len = Math.sqrt(
        this.sumQx * this.sumQx + this.sumQy * this.sumQy +
        this.sumQz * this.sumQz + this.sumQw * this.sumQw
      );
      this._reference = {
        x: this.sumQx / len,
        y: this.sumQy / len,
        z: this.sumQz / len,
        w: this.sumQw / len,
      };
      this._hasReference = true;
      return true;
    }
    return false;
  }

  reset(skipPhase = true): void {
    this.skipCount = 0;
    this.collectCount = 0;
    this.skipPhase = skipPhase;
    this._hasReference = false;
    this._reference = null;
    this.sumX = this.sumY = this.sumZ = 0;
    this.sumQx = this.sumQy = this.sumQz = this.sumQw = 0;
    this.firstQuat = null;
  }
}
```

- [ ] **Step 4: Run tests to verify they pass**

- [ ] **Step 5: Commit**

```bash
git add sdk/motion/calibrator.ts sdk/motion/__tests__/calibrator.test.ts
git commit -m "feat(motion): add Calibrator with skip/collect/average phases"
```

---

### Task 4: Auto-Recalibrator

**Files:**
- Create: `sdk/motion/auto-recalibrator.ts`
- Create: `sdk/motion/__tests__/auto-recalibrator.test.ts`

- [ ] **Step 1: Write failing tests**

Test cases:
1. At rest → fast rate (3.0/sec) — reference moves toward current quickly
2. Near center → moderate rate proportional to centrality
3. Full tilt → frozen rate (0.02/sec for `game`, 0.08/sec for `full`)
4. `pause()` → reference stops moving
5. `resume()` → correction resumes
6. Frame-rate independence: same reference drift at 30Hz and 120Hz over same wall time

- [ ] **Step 2: Run tests to verify they fail**

- [ ] **Step 3: Implement AutoRecalibrator**

```typescript
// sdk/motion/auto-recalibrator.ts
import type { Vector3, Quaternion, SensorFusion } from './types';

const RATE_REST = 3.0;
const RATE_MAX = 1.0;
const RATE_FROZEN_GAME = 0.02;
const RATE_FROZEN_FULL = 0.08;

export class AutoRecalibrator {
  private paused = false;
  private frozenRate: number;

  constructor(fusion: SensorFusion = 'game') {
    this.frozenRate = fusion === 'full' ? RATE_FROZEN_FULL : RATE_FROZEN_GAME;
  }

  pause(): void { this.paused = true; }
  resume(): void { this.paused = false; }

  updateVector(
    reference: Vector3,
    current: Vector3,
    inputMagnitude: number,
    atRest: boolean,
    deltaTime: number
  ): Vector3 {
    if (this.paused) return reference;
    const rate = this.selectRate(inputMagnitude, atRest);
    const t = 1 - Math.exp(-rate * deltaTime);
    return {
      x: reference.x + (current.x - reference.x) * t,
      y: reference.y + (current.y - reference.y) * t,
      z: reference.z + (current.z - reference.z) * t,
    };
  }

  updateQuaternion(
    reference: Quaternion,
    current: Quaternion,
    inputMagnitude: number,
    atRest: boolean,
    deltaTime: number
  ): Quaternion {
    if (this.paused) return reference;
    const rate = this.selectRate(inputMagnitude, atRest);
    const t = 1 - Math.exp(-rate * deltaTime);
    return slerp(reference, current, t);
  }

  private selectRate(inputMagnitude: number, atRest: boolean): number {
    if (atRest) return RATE_REST;
    const weight = 1 - Math.min(1, Math.max(0, inputMagnitude));
    return this.frozenRate + weight * (RATE_MAX - this.frozenRate);
  }
}

function slerp(a: Quaternion, b: Quaternion, t: number): Quaternion {
  // Ensure shortest path
  let dot = a.x * b.x + a.y * b.y + a.z * b.z + a.w * b.w;
  let bx = b.x, by = b.y, bz = b.z, bw = b.w;
  if (dot < 0) { dot = -dot; bx = -bx; by = -by; bz = -bz; bw = -bw; }

  if (dot > 0.9995) {
    // Linear interpolation for very close quaternions
    return normalizeQuat({
      x: a.x + (bx - a.x) * t,
      y: a.y + (by - a.y) * t,
      z: a.z + (bz - a.z) * t,
      w: a.w + (bw - a.w) * t,
    });
  }

  const theta = Math.acos(dot);
  const sinTheta = Math.sin(theta);
  const wa = Math.sin((1 - t) * theta) / sinTheta;
  const wb = Math.sin(t * theta) / sinTheta;

  return {
    x: wa * a.x + wb * bx,
    y: wa * a.y + wb * by,
    z: wa * a.z + wb * bz,
    w: wa * a.w + wb * bw,
  };
}

function normalizeQuat(q: Quaternion): Quaternion {
  const len = Math.sqrt(q.x * q.x + q.y * q.y + q.z * q.z + q.w * q.w);
  return { x: q.x / len, y: q.y / len, z: q.z / len, w: q.w / len };
}

export { slerp, normalizeQuat };
```

- [ ] **Step 4: Run tests to verify they pass**

- [ ] **Step 5: Commit**

```bash
git add sdk/motion/auto-recalibrator.ts sdk/motion/__tests__/auto-recalibrator.test.ts
git commit -m "feat(motion): add AutoRecalibrator with three-tier rates"
```

---

### Task 5: Deadzone Filter

**Files:**
- Create: `sdk/motion/deadzone.ts`
- Create: `sdk/motion/__tests__/deadzone.test.ts`

- [ ] **Step 1: Write failing tests**

Test cases:
1. Value below deadzone → returns 0
2. Value at deadzone boundary → returns 0
3. Value at maxAngle → returns ±1
4. Value between deadzone and maxAngle → linearly rescaled (no jump)
5. Negative values work symmetrically
6. Zero deadzone → passthrough (no rescaling)

- [ ] **Step 2: Run tests to verify they fail**

- [ ] **Step 3: Implement deadzone**

```typescript
// sdk/motion/deadzone.ts

/**
 * Remapped deadzone: values below threshold snap to zero,
 * values above are linearly rescaled so output reaches ±1 smoothly.
 */
export function applyDeadzone(value: number, deadzone: number, maxAngle: number): number {
  if (deadzone <= 0) return Math.max(-1, Math.min(1, value / maxAngle));

  const absValue = Math.abs(value);
  if (absValue <= deadzone) return 0;

  const range = maxAngle - deadzone;
  if (range <= 0) return 0;

  const normalized = (absValue - deadzone) / range;
  return Math.sign(value) * Math.min(1, normalized);
}
```

- [ ] **Step 4: Run tests to verify they pass**

- [ ] **Step 5: Commit**

```bash
git add sdk/motion/deadzone.ts sdk/motion/__tests__/deadzone.test.ts
git commit -m "feat(motion): add remapped deadzone filter"
```

---

## Phase 2: Mode Processors

### Task 6: Tilt Processor

**Files:**
- Create: `sdk/motion/tilt-processor.ts`
- Create: `sdk/motion/__tests__/tilt-processor.test.ts`

- [ ] **Step 1: Write failing tests**

Test cases:
1. Device flat (gravity straight down) with neutral reference → x=0, y=0
2. Tilted 25° right → x≈1.0 (at default maxAngle)
3. Tilted 25° left → x≈-1.0
4. Tilted 25° forward → y≈1.0
5. Z-rotation compensation: tilt right produces same output regardless of wrist rotation angle
6. Sensitivity scaling: { x: 2, y: 0.5 } doubles X response, halves Y

- [ ] **Step 2: Run tests to verify they fail**

- [ ] **Step 3: Implement TiltProcessor**

```typescript
// sdk/motion/tilt-processor.ts
import type { Vector3, TiltInput } from './types';
import { applyDeadzone } from './deadzone';

export class TiltProcessor {
  private refGravity: Vector3 = { x: 0, y: 0, z: -9.81 };
  private zAngle = 0; // Z-rotation compensation angle (radians)
  private maxAngle: number;
  private deadzone: number;
  private sensitivity: { x: number; y: number };

  constructor(maxAngle = 25, deadzone = 2, sensitivity = { x: 1, y: 1 }) {
    this.maxAngle = maxAngle;
    this.deadzone = deadzone;
    this.sensitivity = sensitivity;
  }

  setReference(gravity: Vector3): void {
    this.refGravity = { ...gravity };
    // Compute Z-rotation angle for wrist orientation compensation
    this.zAngle = Math.atan2(gravity.x, -gravity.y);
  }

  process(gravity: Vector3, atRest: boolean, timestamp: number): TiltInput {
    // Compute tilt angles using atan2
    const currentAngleX = Math.atan2(gravity.x, -gravity.z) * (180 / Math.PI);
    const currentAngleY = Math.atan2(gravity.y, -gravity.z) * (180 / Math.PI);
    const refAngleX = Math.atan2(this.refGravity.x, -this.refGravity.z) * (180 / Math.PI);
    const refAngleY = Math.atan2(this.refGravity.y, -this.refGravity.z) * (180 / Math.PI);

    let deltaX = currentAngleX - refAngleX;
    let deltaY = currentAngleY - refAngleY;

    // Z-rotation compensation: rotate delta back to calibration orientation
    const cos = Math.cos(this.zAngle);
    const sin = Math.sin(this.zAngle);
    const rotX = deltaX * cos - deltaY * sin;
    const rotY = deltaX * sin + deltaY * cos;

    // Apply sensitivity
    const scaledX = rotX * this.sensitivity.x;
    const scaledY = rotY * this.sensitivity.y;

    // Apply deadzone and normalize
    const x = applyDeadzone(scaledX, this.deadzone, this.maxAngle);
    const y = applyDeadzone(scaledY, this.deadzone, this.maxAngle);
    const magnitude = Math.min(1, Math.sqrt(x * x + y * y));

    return { x, y, magnitude, atRest, timestamp };
  }
}
```

- [ ] **Step 4: Run tests to verify they pass**

- [ ] **Step 5: Commit**

```bash
git add sdk/motion/tilt-processor.ts sdk/motion/__tests__/tilt-processor.test.ts
git commit -m "feat(motion): add TiltProcessor with atan2 and Z-compensation"
```

---

### Task 7: Look Processor

**Files:**
- Create: `sdk/motion/look-processor.ts`
- Create: `sdk/motion/__tests__/look-processor.test.ts`

- [ ] **Step 1: Write failing tests**

Test cases:
1. No rotation from reference → yaw=0, pitch=0
2. 45° yaw rotation → yaw≈45
3. 30° pitch rotation → pitch≈30
4. Player Space gyro: yaw is gravity-projected with 1.41× relaxation
5. Canonical frame preservation: recalibration at rotated orientation doesn't swap axes
6. Normalized output: yaw/maxAngle maps to x, pitch/maxAngle maps to y

- [ ] **Step 2: Run tests to verify they fail**

- [ ] **Step 3: Implement LookProcessor**

The processor computes `inverse(reference) * current` to get the delta quaternion, then extracts yaw and pitch as Euler angles. Player Space gyro adjusts yaw using the gravity projection algorithm from GamepadMotionHelpers. Canonical frame preservation remaps angles through the initial calibration frame on recalibration.

Key math:
- Quaternion inverse: `{ -x, -y, -z, w }` (conjugate, since unit quaternions)
- Quaternion multiply: standard Hamilton product
- Euler extraction: `yaw = atan2(2*(w*y - x*z), 1 - 2*(y² + z²))`, `pitch = asin(2*(w*x + y*z))`
- Player Space gyro: `worldYaw = -(gravY * gyroY + gravZ * gyroZ)`, clamped

- [ ] **Step 4: Run tests to verify they pass**

- [ ] **Step 5: Commit**

```bash
git add sdk/motion/look-processor.ts sdk/motion/__tests__/look-processor.test.ts
git commit -m "feat(motion): add LookProcessor with Player Space gyro"
```

---

### Task 8: Rotate Processor

**Files:**
- Create: `sdk/motion/rotate-processor.ts`
- Create: `sdk/motion/__tests__/rotate-processor.test.ts`

- [ ] **Step 1: Write failing tests**

Test cases:
1. Twist axis: wrist rotation → single angle via `atan2(gravity.x, -gravity.z)`
2. Lean axis: forward tilt → single angle via `atan2(gravity.y, -gravity.z)`
3. Turn axis: body rotation → yaw from quaternion delta
4. Gesture aliases: 'roll' → 'twist', 'yaw' → 'turn', 'pitch' → 'lean'
5. Output is `{ angle, value }` where value = angle/maxAngle clamped to ±1

- [ ] **Step 2: Run tests to verify they fail**

- [ ] **Step 3: Implement RotateProcessor**

```typescript
// sdk/motion/rotate-processor.ts
import type { Vector3, Quaternion, RotateAxis, RotateInput } from './types';
import { applyDeadzone } from './deadzone';

// Normalize axis aliases
function resolveAxis(axis: RotateAxis): 'twist' | 'turn' | 'lean' {
  switch (axis) {
    case 'roll': return 'twist';
    case 'yaw': return 'turn';
    case 'pitch': return 'lean';
    default: return axis as 'twist' | 'turn' | 'lean';
  }
}

export class RotateProcessor {
  private resolvedAxis: 'twist' | 'turn' | 'lean';
  private refAngle = 0;
  private maxAngle: number;
  private deadzone: number;
  private sensitivity: number;

  constructor(axis: RotateAxis = 'twist', maxAngle = 45, deadzone = 2, sensitivity = 1) {
    this.resolvedAxis = resolveAxis(axis);
    this.maxAngle = maxAngle;
    this.deadzone = deadzone;
    this.sensitivity = sensitivity;
  }

  get usesQuaternion(): boolean { return this.resolvedAxis === 'turn'; }

  setReferenceGravity(gravity: Vector3): void {
    if (this.resolvedAxis === 'twist') {
      this.refAngle = Math.atan2(gravity.x, -gravity.z) * (180 / Math.PI);
    } else if (this.resolvedAxis === 'lean') {
      this.refAngle = Math.atan2(gravity.y, -gravity.z) * (180 / Math.PI);
    }
  }

  setReferenceQuaternion(yawDegrees: number): void {
    this.refAngle = yawDegrees;
  }

  processGravity(gravity: Vector3, atRest: boolean, timestamp: number): RotateInput {
    let currentAngle: number;
    if (this.resolvedAxis === 'twist') {
      currentAngle = Math.atan2(gravity.x, -gravity.z) * (180 / Math.PI);
    } else {
      currentAngle = Math.atan2(gravity.y, -gravity.z) * (180 / Math.PI);
    }

    const delta = (currentAngle - this.refAngle) * this.sensitivity;
    const value = applyDeadzone(delta, this.deadzone, this.maxAngle);
    return { angle: delta, value, atRest, timestamp };
  }

  processYaw(yawDegrees: number, atRest: boolean, timestamp: number): RotateInput {
    const delta = (yawDegrees - this.refAngle) * this.sensitivity;
    const value = applyDeadzone(delta, this.deadzone, this.maxAngle);
    return { angle: delta, value, atRest, timestamp };
  }
}
```

- [ ] **Step 4: Run tests to verify they pass**

- [ ] **Step 5: Commit**

```bash
git add sdk/motion/rotate-processor.ts sdk/motion/__tests__/rotate-processor.test.ts
git commit -m "feat(motion): add RotateProcessor with gesture-named axes"
```

---

## Phase 3: Controllers

### Task 9: Base Controller

**Files:**
- Create: `sdk/motion/controller.ts`
- Create: `sdk/motion/__tests__/controller.test.ts`

- [ ] **Step 1: Write failing tests**

Test cases:
1. Controller starts with `calibrated=false`, `active=true`, `lastInput=null`
2. Handlers registered via `.on('input')` receive events after calibration
3. No events fire during calibration phase
4. `stop()` sets `active=false`, cleans up subscription
5. `recalibrate()` resets calibrator (no skip phase), fires 'calibrated' again
6. `pauseRecalibration()` / `resumeRecalibration()` toggle auto-recal
7. `lastInput` holds the most recent input value

- [ ] **Step 2: Run tests to verify they fail**

- [ ] **Step 3: Implement BaseController**

The base controller class handles:
- Creating an internal raw `MotionSubscription` via `Loop.motion.start()`
- Running each frame through the processing pipeline: RestDetector → Calibrator → AutoRecalibrator → ModeProcessor → Deadzone → emit
- Event dispatch for 'input', 'calibrated', 'rest'
- `lastInput` storage
- `stop()` cleanup

Subclasses (TiltController, LookController, RotateController) provide the mode-specific processor and calibration type.

- [ ] **Step 4: Run tests to verify they pass**

- [ ] **Step 5: Commit**

```bash
git add sdk/motion/controller.ts sdk/motion/__tests__/controller.test.ts
git commit -m "feat(motion): add BaseController with processing pipeline"
```

---

### Task 10: TiltController

**Files:**
- Create: `sdk/motion/tilt-controller.ts`
- Create: `sdk/motion/__tests__/tilt-controller.test.ts`

- [ ] **Step 1: Write failing tests**

Test cases:
1. `new TiltController(options)` creates an active controller
2. After feeding 45 frames of stable data, `calibrated` is true and 'input' events start
3. Output is a `TiltInput` with x/y in -1..1 range
4. Recalibrate triggers new 30-frame collection (no skip phase)
5. Options: maxAngle, deadzone, smoothing, sensitivity all affect output

- [ ] **Step 2: Run tests to verify they fail**

- [ ] **Step 3: Implement TiltController**

Wires together: RestDetector + Calibrator('vector') + AutoRecalibrator + TiltProcessor + Deadzone. Extends BaseController.

- [ ] **Step 4: Run tests to verify they pass**

- [ ] **Step 5: Commit**

```bash
git add sdk/motion/tilt-controller.ts sdk/motion/__tests__/tilt-controller.test.ts
git commit -m "feat(motion): add TiltController"
```

---

### Task 11: LookController

**Files:**
- Create: `sdk/motion/look-controller.ts`
- Create: `sdk/motion/__tests__/look-controller.test.ts`

- [ ] **Step 1: Write failing tests**

Test cases:
1. Quaternion calibration: 15 skip + 30 collect
2. Output is a `LookInput` with yaw/pitch in degrees and x/y in -1..1
3. `sensorFusion: 'game'` → frozen rate 0.02/sec
4. `sensorFusion: 'full'` → frozen rate 0.08/sec AND calls `setSensorFusion('full')` on native bridge
5. Canonical frame preservation survives recalibration

- [ ] **Step 2: Run tests to verify they fail**

- [ ] **Step 3: Implement LookController**

Wires together: RestDetector + Calibrator('quaternion') + AutoRecalibrator(fusion) + LookProcessor + Deadzone. Calls native `setSensorFusion` on start if not 'game'.

- [ ] **Step 4: Run tests to verify they pass**

- [ ] **Step 5: Commit**

```bash
git add sdk/motion/look-controller.ts sdk/motion/__tests__/look-controller.test.ts
git commit -m "feat(motion): add LookController with sensorFusion option"
```

---

### Task 12: RotateController

**Files:**
- Create: `sdk/motion/rotate-controller.ts`
- Create: `sdk/motion/__tests__/rotate-controller.test.ts`

- [ ] **Step 1: Write failing tests**

Test cases:
1. Default axis 'twist' → gravity-based, drift-free
2. Axis 'turn' → quaternion-based, calls setSensorFusion
3. Axis 'lean' → gravity-based, drift-free
4. Alias 'roll' → resolves to 'twist'
5. Output is `RotateInput` with angle in degrees and value in -1..1

- [ ] **Step 2: Run tests to verify they fail**

- [ ] **Step 3: Implement RotateController**

Dispatches to gravity-based calibration+processing for twist/lean, quaternion-based for turn. Uses RotateProcessor internally.

- [ ] **Step 4: Run tests to verify they pass**

- [ ] **Step 5: Commit**

```bash
git add sdk/motion/rotate-controller.ts sdk/motion/__tests__/rotate-controller.test.ts
git commit -m "feat(motion): add RotateController with axis dispatch"
```

---

## Phase 4: SDK Integration

### Task 13: Bridge Contract Update

**Files:**
- Modify: `bridge-contract.yaml`

- [ ] **Step 1: Add setSensorFusion to bridge contract**

Add to the `motion` namespace `nativeMethods`:
```yaml
setSensorFusion: { params: [type: String], returns: Boolean }
```

Add to `sdkMethods`:
```yaml
setSensorFusion:
  maps: [setSensorFusion]
  note: "Switch orientation sensor: 'game' (TYPE_GAME_ROTATION_VECTOR) or 'full' (TYPE_ROTATION_VECTOR)"
```

- [ ] **Step 2: Regenerate bridge types**

Run: `npm run generate`
Expected: `sdk/generated/bridge-types.ts` updated with `setSensorFusion(type: string): boolean` on `Loop$motion`

- [ ] **Step 3: Verify generated types**

Run: `grep setSensorFusion sdk/generated/bridge-types.ts`
Expected: Method present in `Loop$motion` interface

- [ ] **Step 4: Commit**

```bash
git add bridge-contract.yaml sdk/generated/bridge-types.ts
git commit -m "feat(bridge): add setSensorFusion to motion namespace"
```

---

### Task 14: SDK MotionAPI Integration

**Files:**
- Modify: `sdk/loop-sdk.ts`

- [ ] **Step 1: Import controller types and add mode methods to MotionAPI**

Add to `MotionAPI` object:
- `tilt(options?)` — creates TiltController, stops any existing mode controller, returns Promise
- `look(options?)` — creates LookController, stops any existing mode controller, returns Promise
- `rotate(options?)` — creates RotateController, stops any existing mode controller, returns Promise
- `raw(options?)` — alias for existing `start()`, returns Promise<MotionSubscription>
- `_activeController` — tracks the current mode controller for one-mode-at-a-time enforcement

The mode methods enforce one-mode-at-a-time by calling `_activeController?.stop()` before creating a new controller. Raw subscriptions are NOT affected (they don't participate in the constraint).

- [ ] **Step 2: Build SDK**

Run: `npm run build:sdk`
Expected: `app/src/main/assets/loop-sdk.js` updated with mode methods

- [ ] **Step 3: Verify bundle includes motion modules**

Run: `grep "TiltController\|LookController\|RotateController" app/src/main/assets/loop-sdk.js`
Expected: All three controller names present in the bundle

- [ ] **Step 4: Commit**

```bash
git add sdk/loop-sdk.ts app/src/main/assets/loop-sdk.js
git commit -m "feat(sdk): integrate motion control modes into MotionAPI"
```

---

### Task 15: DX Type Declarations

**Files:**
- Modify: `sdk/loop-sdk-dx.d.ts`

- [ ] **Step 1: Add all new type declarations**

Add to the DX types file:
- `TiltOptions`, `LookOptions`, `RotateOptions` interfaces
- `TiltInput`, `LookInput`, `RotateInput` interfaces
- `TiltController`, `LookController`, `RotateController` interfaces
- `RotateAxis`, `SensorFusion` type aliases
- Updated `MotionAPI` with `tilt()`, `look()`, `rotate()`, `raw()` methods

- [ ] **Step 2: Verify types compile**

Run: `npx tsc --noEmit sdk/loop-sdk-dx.d.ts`
Expected: No errors

- [ ] **Step 3: Commit**

```bash
git add sdk/loop-sdk-dx.d.ts
git commit -m "feat(sdk): add DX type declarations for motion control modes"
```

---

## Phase 5: Native Bridge

### Task 16: Kotlin setSensorFusion

**Files:**
- Modify: `IMUSensorManager.kt`
- Modify: `WebAppInterface.kt` (IMUNamespace)

- [ ] **Step 1: Add setSensorFusion to IMUSensorManager**

```kotlin
// In IMUSensorManager.kt

private var orientationSensorType = Sensor.TYPE_GAME_ROTATION_VECTOR  // Changed default

fun setSensorFusion(type: String): Boolean {
    val sensorType = when (type) {
        "game" -> Sensor.TYPE_GAME_ROTATION_VECTOR
        "full" -> Sensor.TYPE_ROTATION_VECTOR
        else -> return false
    }
    if (sensorType == orientationSensorType) return true

    // Hot-swap: unregister current orientation sensor, register new one
    val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    sensorManager.getDefaultSensor(orientationSensorType)?.let {
        sensorManager.unregisterListener(this, it)
    }
    orientationSensorType = sensorType
    sensorManager.getDefaultSensor(orientationSensorType)?.let {
        sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST, sensorHandler)
        return true
    }
    return false
}
```

- [ ] **Step 2: Add @JavascriptInterface to IMUNamespace**

```kotlin
@JavascriptInterface
fun setSensorFusion(type: String): Boolean {
    return imuManager.setSensorFusion(type)
}
```

- [ ] **Step 3: Build and verify**

Run: `./gradlew assembleDebug`
Expected: Build succeeds

- [ ] **Step 4: Deploy and test on device**

Run:
```bash
SERIAL=$(adb devices | awk 'NR==2{print $1}')
adb -s $SERIAL install -r app/build/outputs/apk/debug/*.apk
```

Verify via CDP:
```javascript
Loop.motion.setSensorFusion && Loop.motion.setSensorFusion('game')  // should return true
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/dopple/webview/bridge/IMUSensorManager.kt
git add app/src/main/java/com/dopple/webview/bridge/WebAppInterface.kt
git commit -m "feat(native): add setSensorFusion to switch orientation sensor type"
```

---

## Phase 6: SDK Explorer

### Task 17: Explorer — Sensor Dashboard

**Files:**
- Create: `app/src/main/assets/games/sdk-explorer/index.html`
- Create: `app/src/main/assets/games/sdk-explorer/manifest.json`

- [ ] **Step 1: Create manifest**

```json
{
  "projectId": "sdk-explorer",
  "activityName": "SDK Explorer",
  "url": "http://127.0.0.1:8088/games/sdk-explorer/index.html"
}
```

- [ ] **Step 2: Create base HTML with sensor dashboard**

Self-contained HTML page with:
- `loop:ready` gate
- Live gravity vector display (canvas, 3D arrow on sphere)
- Live quaternion display (wireframe cube mirroring device rotation)
- Gyro angular velocity strip chart
- Rest state indicator
- Calibration state indicator
- Tab navigation between: Dashboard | Tilt | Look | Rotate | Tune

- [ ] **Step 3: Deploy and verify on device**

Run: deploy via adb, launch via `dopple://launch?manifest=...`
Expected: Sensor data streams and updates visualizations in real-time

- [ ] **Step 4: Commit**

```bash
git add app/src/main/assets/games/sdk-explorer/
git commit -m "feat(explorer): add SDK Explorer with live sensor dashboard"
```

---

### Task 18: Explorer — Mode Testers

**Files:**
- Modify: `app/src/main/assets/games/sdk-explorer/index.html`

- [ ] **Step 1: Add tilt tester panel**

- Crosshair on 2D plane controlled by `Loop.motion.tilt()`
- Sliders for maxAngle (10-60), deadzone (0-10), smoothing (0.01-0.5)
- Live display of x, y, magnitude, atRest values
- Recalibrate button (maps to B button)

- [ ] **Step 2: Add look tester panel**

- Virtual grid sphere responding to `Loop.motion.look()`
- Yaw/pitch degree readout
- Drift accumulation indicator (degrees since calibration)
- Toggle between `sensorFusion: 'game'` and `'full'`

- [ ] **Step 3: Add rotate tester panel**

- Arc gauge for single-axis rotation
- Axis selector: twist / turn / lean
- Steering wheel visualization for twist axis

- [ ] **Step 4: Deploy and verify each mode**

- [ ] **Step 5: Commit**

```bash
git add app/src/main/assets/games/sdk-explorer/index.html
git commit -m "feat(explorer): add tilt/look/rotate mode tester panels"
```

---

### Task 19: Explorer — Tuning Engine

**Files:**
- Modify: `app/src/main/assets/games/sdk-explorer/index.html`

- [ ] **Step 1: Add recording capability**

- Record button captures raw MotionData frames with timestamps
- Stop button ends recording
- Display frame count and duration
- Export recorded data as JSON to `Loop.storage`

- [ ] **Step 2: Add replay-based parameter search**

- Load recorded session
- Grid search over parameter space (smoothing, maxAngle, deadzone)
- Score each combination against quality metrics
- Display top 3 results with before/after comparison

- [ ] **Step 3: Add auto-derive helpers**

- "Find my max angle" — prompt user to tilt to comfortable limit
- "Calibrate deadzone" — measure noise floor at rest
- "Compare fusion types" — replay through both, show drift scores

- [ ] **Step 4: Deploy and verify tuning workflow**

- [ ] **Step 5: Commit**

```bash
git add app/src/main/assets/games/sdk-explorer/index.html
git commit -m "feat(explorer): add recording and replay-based autotuning"
```

---

## Phase 7: Integration Testing

### Task 20: End-to-End Device Testing

- [ ] **Step 1: Deploy full SDK to device**

Run: `npm run build && ./gradlew assembleDebug && adb install -r ...`

- [ ] **Step 2: Test tilt mode with Quarto game**

Modify Quarto's motion code to use `Loop.motion.tilt()` instead of raw motion. Verify:
- Calibration happens automatically (no first-reading hack)
- Tilt response feels correct
- Auto-recalibration handles wrist drift

- [ ] **Step 3: Test look mode with SDK Explorer**

Use the look tester panel. Verify:
- Panorama look-around works smoothly
- Drift is manageable over 2+ minutes
- `sensorFusion: 'game'` vs `'full'` shows measurable difference

- [ ] **Step 4: Test rotate mode with SDK Explorer**

Use the rotate tester panel. Verify:
- Twist/lean are drift-free
- Turn drifts slowly, auto-recal corrects during pauses

- [ ] **Step 5: Test one-mode-at-a-time constraint**

Via CDP: start tilt, then start look. Verify tilt controller is stopped.

- [ ] **Step 6: Commit any fixes**

```bash
git commit -m "fix: address integration testing findings"
```

---

## Task Dependency Graph

```
Phase 1: Types(1) → RestDetector(2) → Calibrator(3) → AutoRecal(4) → Deadzone(5)
Phase 2: TiltProc(6) → LookProc(7) → RotateProc(8)
Phase 3: BaseCtrl(9) → TiltCtrl(10) → LookCtrl(11) → RotateCtrl(12)
Phase 4: Bridge(13) → SDK Integration(14) → DX Types(15)
Phase 5: Kotlin(16)                     [independent, can parallel with Phase 2-3]
Phase 6: Explorer Dashboard(17) → Mode Testers(18) → Tuning(19)   [after Phase 4]
Phase 7: E2E Testing(20)                                           [after all]
```

Tasks 1-5 are sequential (each builds on prior). Tasks 6-8 depend on 1-5 but are independent of each other. Tasks 9-12 depend on 6-8. Task 13 is independent of all JS work. Task 16 is independent of all JS work (can run in parallel). Tasks 17-19 depend on 14.
