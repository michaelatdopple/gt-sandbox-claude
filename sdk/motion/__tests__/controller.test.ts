import type { OrientationData, MotionData } from '../types';
import { BaseController, type EventSource } from '../controller';
import { AutoRecalibrator } from '../auto-recalibrator';

// Minimal concrete subclass for testing
class TestController extends BaseController<{ value: number; atRest: boolean; timestamp: number; angle: number; magnitude: number }> {
  readonly mode = 'tilt' as const;
  readonly eventSource: EventSource = 'motion';
  private calCount = 0;

  constructor() {
    super(new AutoRecalibrator('game'));
  }

  protected feedCalibrator(): boolean {
    this.calCount++;
    return this.calCount >= 45; // simulate 15 skip + 30 collect
  }

  protected onCalibrated(): void {}

  protected updateAutoRecal(_atRest: boolean, _dt: number): void {}

  protected computeInput(atRest: boolean, timestamp: number) {
    return { value: 0.5, atRest, timestamp, angle: 0, magnitude: 0.5 };
  }

  protected resetCalibrator(_skipPhase: boolean): void {
    this.calCount = 0;
  }
}

function makeMotion(gx = 0, gy = 0, gz = -9.81): MotionData {
  return {
    accelerationIncludingGravity: { x: gx, y: gy, z: gz },
    acceleration: { x: 0, y: 0, z: 0 },
    rotationRate: { alpha: 0, beta: 0, gamma: 0 },
    interval: 16.67,
    gravity: { x: gx, y: gy, z: gz },
  };
}

// Test 1: starts uncalibrated and active
const ctrl = new TestController();
console.assert(ctrl.calibrated === false, 'should start uncalibrated');
console.assert(ctrl.active === true, 'should start active');
console.assert(ctrl.lastInput === null, 'should start with null lastInput');

// Test 2: no input events during calibration
let inputCount = 0;
ctrl.on('input', () => inputCount++);
let calibratedFired: boolean = false;
ctrl.on('calibrated', () => { calibratedFired = true; });

for (let i = 0; i < 44; i++) {
  ctrl.handleMotion(makeMotion());
}
console.assert(inputCount === 0, 'no input during calibration');
console.assert(calibratedFired === false, 'not calibrated yet');

// Test 3: calibrated event fires, input starts
ctrl.handleMotion(makeMotion());
console.assert(calibratedFired as boolean === true, 'calibrated should fire at frame 45');
// The frame that completes calibration doesn't emit input (returns after emit calibrated)
ctrl.handleMotion(makeMotion());
console.assert(inputCount === 1, `should have 1 input event, got ${inputCount}`);
console.assert(ctrl.lastInput !== null, 'lastInput should be set');

// Test 4: stop sets active=false
ctrl.stop();
console.assert(ctrl.active === false, 'should be inactive after stop');
ctrl.handleMotion(makeMotion());
console.assert(inputCount === 1, 'no input after stop');

// Test 5: recalibrate resets calibration
const ctrl2 = new TestController();
let cal2: boolean = false;
ctrl2.on('calibrated', () => { cal2 = true; });
for (let i = 0; i < 45; i++) ctrl2.handleMotion(makeMotion());
console.assert(cal2 as boolean === true, 'should calibrate');
ctrl2.recalibrate();
console.assert(ctrl2.calibrated === false, 'should be uncalibrated after recalibrate');
cal2 = false;
for (let i = 0; i < 45; i++) ctrl2.handleMotion(makeMotion());
console.assert(cal2 as boolean === true, 'should re-calibrate');

// Test 6: pause/resume recalibration
const ctrl3 = new TestController();
ctrl3.pauseRecalibration();
ctrl3.resumeRecalibration();

console.log('controller tests passed');
