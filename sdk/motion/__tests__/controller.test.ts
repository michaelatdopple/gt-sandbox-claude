import type { MotionData } from '../types';
import { BaseController } from '../controller';
import { AutoRecalibrator } from '../auto-recalibrator';

// Minimal concrete subclass for testing
class TestController extends BaseController<{ value: number; atRest: boolean; timestamp: number; angle: number; magnitude: number }> {
  readonly mode = 'tilt' as const;
  private calCount = 0;

  constructor() {
    super(new AutoRecalibrator('game'));
  }

  protected feedCalibrator(_data: MotionData): boolean {
    this.calCount++;
    return this.calCount >= 45; // simulate 15 skip + 30 collect
  }

  protected onCalibrated(_data: MotionData): void {}

  protected updateAutoRecal(_data: MotionData, _atRest: boolean, _dt: number): void {}

  protected computeInput(_data: MotionData, atRest: boolean) {
    return { value: 0.5, atRest, timestamp: _data.timestamp, angle: 0, magnitude: 0.5 };
  }

  protected resetCalibrator(_skipPhase: boolean): void {
    this.calCount = 0;
  }
}

function makeFrame(ts: number): MotionData {
  return {
    gravity: { x: 0, y: 0, z: -9.81 },
    smoothGravity: { x: 0, y: 0, z: -9.81 },
    orientation: { x: 0, y: 0, z: 0, w: 1 },
    delta: { x: 0, y: 0, z: 0 },
    timestamp: ts,
    sequenceNumber: ts,
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
  ctrl['processFrame'](makeFrame(i * 16));
}
console.assert(inputCount === 0, 'no input during calibration');
console.assert(calibratedFired === false, 'not calibrated yet');

// Test 3: calibrated event fires, input starts
ctrl['processFrame'](makeFrame(44 * 16));
console.assert(calibratedFired as boolean === true, 'calibrated should fire at frame 45');
// The frame that completes calibration doesn't emit input (returns after emit calibrated)
ctrl['processFrame'](makeFrame(45 * 16));
console.assert(inputCount === 1, `should have 1 input event, got ${inputCount}`);
console.assert(ctrl.lastInput !== null, 'lastInput should be set');

// Test 4: stop sets active=false
ctrl.stop();
console.assert(ctrl.active === false, 'should be inactive after stop');
ctrl['processFrame'](makeFrame(46 * 16));
console.assert(inputCount === 1, 'no input after stop');

// Test 5: recalibrate resets calibration
const ctrl2 = new TestController();
let cal2: boolean = false;
ctrl2.on('calibrated', () => { cal2 = true; });
for (let i = 0; i < 45; i++) ctrl2['processFrame'](makeFrame(i * 16));
console.assert(cal2 as boolean === true, 'should calibrate');
ctrl2.recalibrate();
console.assert(ctrl2.calibrated === false, 'should be uncalibrated after recalibrate');
cal2 = false;
for (let i = 0; i < 45; i++) ctrl2['processFrame'](makeFrame((i + 100) * 16));
console.assert(cal2 as boolean === true, 'should re-calibrate');

// Test 6: pause/resume recalibration
const ctrl3 = new TestController();
ctrl3.pauseRecalibration();
ctrl3.resumeRecalibration();
// Just verify no errors — actual effect tested via AutoRecalibrator

console.log('controller tests passed');
