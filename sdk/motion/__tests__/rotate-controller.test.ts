import type { OrientationData, MotionData } from '../types';
import { RotateController } from '../rotate-controller';

function makeMotion(gx = 0, gy = 0, gz = -9.81): MotionData {
  return {
    accelerationIncludingGravity: { x: gx, y: gy, z: gz },
    acceleration: { x: 0, y: 0, z: 0 },
    rotationRate: { alpha: 0, beta: 0, gamma: 0 },
    interval: 16.67,
    gravity: { x: gx, y: gy, z: gz },
  };
}

function makeOrientation(alpha = 0, beta = 0, gamma = 0): OrientationData {
  return { alpha, beta, gamma, absolute: false };
}

function feedMotionFrame(ctrl: RotateController, gx = 0, gy = 0, gz = -9.81) {
  ctrl.handleMotion(makeMotion(gx, gy, gz));
}

function feedOrientationFrame(ctrl: RotateController, alpha = 0, beta = 0, gamma = 0) {
  ctrl.handleMotion(makeMotion());  // gravity for rest detection
  ctrl.handleOrientation(makeOrientation(alpha, beta, gamma));
}

// Test 1: twist axis — gravity-based
const ctrl = new RotateController({ axis: 'twist', maxAngle: 45, deadzone: 0 });
let calibrated = false;
ctrl.on('calibrated', () => { calibrated = true; });

for (let i = 0; i < 45; i++) feedMotionFrame(ctrl);
console.assert((calibrated as boolean) === true, 'should calibrate');

// Twist 30°
const gx30 = 9.81 * Math.sin(30 * Math.PI / 180);
const gz30 = -9.81 * Math.cos(30 * Math.PI / 180);
feedMotionFrame(ctrl, gx30, 0, gz30);
const r1 = ctrl.lastInput!;
console.assert(Math.abs(r1.angle - 30) < 2, `twist 30°: angle should be ~30, got ${r1.angle}`);
console.assert('value' in r1, 'should have RotateInput shape');

// Test 2: turn axis — uses alpha directly from loop:orientation
const ctrl2 = new RotateController({ axis: 'turn', maxAngle: 45, deadzone: 0 });
let cal2 = false;
ctrl2.on('calibrated', () => { cal2 = true; });

for (let i = 0; i < 45; i++) feedOrientationFrame(ctrl2);
console.assert((cal2 as boolean) === true, 'turn should calibrate');

// Turn 30° via alpha
feedOrientationFrame(ctrl2, 30, 0, 0);
const r2 = ctrl2.lastInput!;
console.assert(Math.abs(r2.angle) > 15, `turn: should detect rotation, got angle=${r2.angle}`);

// Test 3: lean axis — gravity-based
const ctrl3 = new RotateController({ axis: 'lean', maxAngle: 45, deadzone: 0 });
for (let i = 0; i < 45; i++) feedMotionFrame(ctrl3);
const gy20 = 9.81 * Math.sin(20 * Math.PI / 180);
const gz20 = -9.81 * Math.cos(20 * Math.PI / 180);
feedMotionFrame(ctrl3, 0, gy20, gz20);
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
