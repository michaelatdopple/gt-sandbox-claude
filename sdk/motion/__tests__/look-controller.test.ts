import type { OrientationData, MotionData } from '../types';
import { LookController } from '../look-controller';

function makeOrientation(alpha = 0, beta = 0, gamma = 0): OrientationData {
  return { alpha, beta, gamma, absolute: false };
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

function feedFrame(ctrl: LookController, alpha = 0, beta = 0, gamma = 0) {
  ctrl.handleMotion(makeMotion());
  ctrl.handleOrientation(makeOrientation(alpha, beta, gamma));
}

// Test 1: calibrates after 45 frames (15 skip + 30 collect)
const ctrl = new LookController({ maxAngle: 45, deadzone: 0 });
let calibrated = false;
ctrl.on('calibrated', () => { calibrated = true; });
let inputCount = 0;
ctrl.on('input', () => inputCount++);

for (let i = 0; i < 45; i++) {
  feedFrame(ctrl);
}
console.assert((calibrated as boolean) === true, 'should calibrate at frame 45');

// Test 2: neutral orientation → near-zero output
feedFrame(ctrl);
console.assert(inputCount === 1, `should have 1 input, got ${inputCount}`);
const last = ctrl.lastInput!;
console.assert('yaw' in last && 'pitch' in last, 'should have LookInput shape');
console.assert(Math.abs(last.yaw) < 1, `neutral yaw should be ~0, got ${last.yaw}`);
console.assert(Math.abs(last.pitch) < 1, `neutral pitch should be ~0, got ${last.pitch}`);

// Test 3: alpha=45 produces yaw output (alpha is heading/yaw in W3C)
feedFrame(ctrl, 45, 0, 0);
const r2 = ctrl.lastInput!;
console.assert(Math.abs(r2.yaw) > 30, `45° alpha should produce yaw>30, got ${r2.yaw}`);

// Test 4: beta=20 produces pitch output
feedFrame(ctrl, 0, 20, 0);
const r3 = ctrl.lastInput!;
console.assert(Math.abs(r3.pitch) > 10, `20° beta should produce pitch>10, got ${r3.pitch}`);

// Test 5: sensorFusion 'game' vs 'full' — constructors work
const ctrlGame = new LookController({ sensorFusion: 'game' });
const ctrlFull = new LookController({ sensorFusion: 'full' });
console.assert(ctrlGame.mode === 'look', 'game mode should work');
console.assert(ctrlFull.mode === 'look', 'full mode should work');

// Test 6: recalibrate works (30 frames, no skip phase)
ctrl.recalibrate();
console.assert(ctrl.calibrated === false, 'should reset');
calibrated = false;
for (let i = 0; i < 30; i++) {
  feedFrame(ctrl);
}
console.assert((calibrated as boolean) === true, 'should recalibrate in 30 frames');

console.log('look-controller tests passed');
