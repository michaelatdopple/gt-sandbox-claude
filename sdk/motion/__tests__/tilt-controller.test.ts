import type { MotionData } from '../types';
import { TiltController } from '../tilt-controller';

function makeFrame(ts: number, gx = 0, gy = 0, gz = -9.81): MotionData {
  return {
    gravity: { x: gx, y: gy, z: gz },
    smoothGravity: { x: gx, y: gy, z: gz },
    orientation: { x: 0, y: 0, z: 0, w: 1 },
    delta: { x: 0, y: 0, z: 0 },
    timestamp: ts,
    sequenceNumber: ts,
  };
}

// Test 1: creates active controller
const ctrl = new TiltController({ maxAngle: 25, deadzone: 0 });
console.assert(ctrl.active === true, 'should be active');
console.assert(ctrl.mode === 'tilt', 'mode should be tilt');

// Test 2: calibration takes 45 frames (15 skip + 30 collect)
let calibratedFired = false;
ctrl.on('calibrated', () => { calibratedFired = true; });
let inputCount = 0;
ctrl.on('input', () => inputCount++);

for (let i = 0; i < 44; i++) {
  ctrl['processFrame'](makeFrame(i * 16));
}
console.assert((calibratedFired as boolean) === false, 'not calibrated at frame 44');
ctrl['processFrame'](makeFrame(44 * 16));
console.assert((calibratedFired as boolean) === true, 'calibrated at frame 45');

// Test 3: input after calibration has correct shape
ctrl['processFrame'](makeFrame(45 * 16));
console.assert(inputCount === 1, `should have 1 input, got ${inputCount}`);
const last = ctrl.lastInput!;
console.assert('x' in last && 'y' in last && 'magnitude' in last, 'should have TiltInput shape');
console.assert(Math.abs(last.x) < 0.1, `neutral x should be ~0, got ${last.x}`);
console.assert(Math.abs(last.y) < 0.1, `neutral y should be ~0, got ${last.y}`);

// Test 4: recalibrate works (30 frames, no skip)
ctrl.recalibrate();
console.assert(ctrl.calibrated === false, 'should reset');
calibratedFired = false;
for (let i = 0; i < 30; i++) {
  ctrl['processFrame'](makeFrame((i + 200) * 16));
}
console.assert((calibratedFired as boolean) === true, 'should recalibrate in 30 frames');

// Test 5: options affect output
const ctrl2 = new TiltController({ maxAngle: 10, deadzone: 0, sensitivity: { x: 2, y: 1 }, autoRecalibrate: false });
for (let i = 0; i < 45; i++) ctrl2['processFrame'](makeFrame(i * 16));
// Tilt ~5 degrees right
const g5 = 9.81 * Math.sin(5 * Math.PI / 180);
const gz = -9.81 * Math.cos(5 * Math.PI / 180);
ctrl2['processFrame'](makeFrame(46 * 16, g5, 0, gz));
const r = ctrl2.lastInput!;
// sensitivity x=2 → 5°*2=10° → value=10/10=1
console.assert(r.x > 0.8, `sens x=2, maxAngle=10: should amplify, got ${r.x}`);

console.log('tilt-controller tests passed');
