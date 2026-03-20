import type { MotionData } from '../types';
import { RotateController } from '../rotate-controller';

function makeFrame(ts: number, gx = 0, gy = 0, gz = -9.81, qx = 0, qy = 0, qz = 0, qw = 1): MotionData {
  return {
    gravity: { x: gx, y: gy, z: gz },
    smoothGravity: { x: gx, y: gy, z: gz },
    orientation: { x: qx, y: qy, z: qz, w: qw },
    delta: { x: 0, y: 0, z: 0 },
    timestamp: ts,
    sequenceNumber: ts,
  };
}

// Test 1: twist axis — gravity-based
const ctrl = new RotateController({ axis: 'twist', maxAngle: 45, deadzone: 0 });
let calibrated = false;
ctrl.on('calibrated', () => { calibrated = true; });

for (let i = 0; i < 45; i++) ctrl['processFrame'](makeFrame(i * 16));
console.assert((calibrated as boolean) === true, 'should calibrate');

// Twist 30°
const gx30 = 9.81 * Math.sin(30 * Math.PI / 180);
const gz30 = -9.81 * Math.cos(30 * Math.PI / 180);
ctrl['processFrame'](makeFrame(45 * 16, gx30, 0, gz30));
const r1 = ctrl.lastInput!;
console.assert(Math.abs(r1.angle - 30) < 2, `twist 30°: angle should be ~30, got ${r1.angle}`);
console.assert('value' in r1, 'should have RotateInput shape');

// Test 2: turn axis — quaternion-based
const ctrl2 = new RotateController({ axis: 'turn', maxAngle: 45, deadzone: 0 });
let cal2 = false;
ctrl2.on('calibrated', () => { cal2 = true; });

for (let i = 0; i < 45; i++) ctrl2['processFrame'](makeFrame(i * 16));
console.assert((cal2 as boolean) === true, 'turn should calibrate');

// Yaw 30°
const qy30 = Math.sin(15 * Math.PI / 180);
const qw30 = Math.cos(15 * Math.PI / 180);
ctrl2['processFrame'](makeFrame(45 * 16, 0, 0, -9.81, 0, qy30, 0, qw30));
const r2 = ctrl2.lastInput!;
console.assert(Math.abs(r2.angle) > 15, `turn: should detect rotation, got angle=${r2.angle}`);

// Test 3: lean axis — gravity-based
const ctrl3 = new RotateController({ axis: 'lean', maxAngle: 45, deadzone: 0 });
for (let i = 0; i < 45; i++) ctrl3['processFrame'](makeFrame(i * 16));
const gy20 = 9.81 * Math.sin(20 * Math.PI / 180);
const gz20 = -9.81 * Math.cos(20 * Math.PI / 180);
ctrl3['processFrame'](makeFrame(45 * 16, 0, gy20, gz20));
const r3 = ctrl3.lastInput!;
console.assert(Math.abs(r3.angle - 20) < 2, `lean 20°: angle should be ~20, got ${r3.angle}`);

// Test 4: aliases
const ctrlRoll = new RotateController({ axis: 'roll' });
console.assert(ctrlRoll.mode === 'rotate', 'roll alias should work');
const ctrlYaw = new RotateController({ axis: 'yaw' });
console.assert(ctrlYaw.mode === 'rotate', 'yaw alias should work');
const ctrlPitch = new RotateController({ axis: 'pitch' });
console.assert(ctrlPitch.mode === 'rotate', 'pitch alias should work');

console.log('rotate-controller tests passed');
