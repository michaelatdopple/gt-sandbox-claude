import type { MotionData } from '../types';
import { LookController } from '../look-controller';

function makeFrame(ts: number, qx = 0, qy = 0, qz = 0, qw = 1): MotionData {
  return {
    gravity: { x: 0, y: 0, z: -9.81 },
    smoothGravity: { x: 0, y: 0, z: -9.81 },
    orientation: { x: qx, y: qy, z: qz, w: qw },
    delta: { x: 0, y: 0, z: 0 },
    timestamp: ts,
    sequenceNumber: ts,
  };
}

// Test 1: calibrates with quaternion
const ctrl = new LookController({ maxAngle: 45, deadzone: 0 });
let calibrated = false;
ctrl.on('calibrated', () => { calibrated = true; });
let inputCount = 0;
ctrl.on('input', () => inputCount++);

for (let i = 0; i < 45; i++) {
  ctrl['processFrame'](makeFrame(i * 16));
}
console.assert((calibrated as boolean) === true, 'should calibrate at frame 45');

// Test 2: neutral orientation → near-zero output
ctrl['processFrame'](makeFrame(45 * 16));
console.assert(inputCount === 1, `should have 1 input, got ${inputCount}`);
const last = ctrl.lastInput!;
console.assert('yaw' in last && 'pitch' in last, 'should have LookInput shape');
console.assert(Math.abs(last.yaw) < 1, `neutral yaw should be ~0, got ${last.yaw}`);
console.assert(Math.abs(last.pitch) < 1, `neutral pitch should be ~0, got ${last.pitch}`);

// Test 3: yaw rotation produces output
const yaw45 = {
  x: 0,
  y: Math.sin(22.5 * Math.PI / 180),
  z: 0,
  w: Math.cos(22.5 * Math.PI / 180),
};
ctrl['processFrame'](makeFrame(46 * 16, yaw45.x, yaw45.y, yaw45.z, yaw45.w));
const r2 = ctrl.lastInput!;
console.assert(Math.abs(r2.yaw) > 30, `45° yaw should produce yaw>30, got ${r2.yaw}`);

// Test 4: sensorFusion 'game' vs 'full' affects auto-recal rates
// Just verify constructors work — actual rate differences tested in auto-recalibrator
const ctrlGame = new LookController({ sensorFusion: 'game' });
const ctrlFull = new LookController({ sensorFusion: 'full' });
console.assert(ctrlGame.mode === 'look', 'game mode should work');
console.assert(ctrlFull.mode === 'look', 'full mode should work');

// Test 5: recalibrate works
ctrl.recalibrate();
console.assert(ctrl.calibrated === false, 'should reset');
calibrated = false;
for (let i = 0; i < 30; i++) {
  ctrl['processFrame'](makeFrame((i + 200) * 16));
}
console.assert((calibrated as boolean) === true, 'should recalibrate in 30 frames');

console.log('look-controller tests passed');
