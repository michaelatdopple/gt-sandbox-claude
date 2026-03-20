import { RotateProcessor } from '../rotate-processor';

const flat = { x: 0, y: 0, z: -9.81 };

// Test 1: twist axis — wrist rotation via gravity x
const rp = new RotateProcessor('twist', 45, 0);
rp.setReferenceGravity(flat);
// Twist 45° right: gravity.x = 9.81*sin(45°), gravity.z = -9.81*cos(45°)
const twisted = { x: 9.81 * Math.sin(45 * Math.PI / 180), y: 0, z: -9.81 * Math.cos(45 * Math.PI / 180) };
const r1 = rp.processGravity(twisted, false, 0);
console.assert(Math.abs(r1.angle - 45) < 1, `twist 45°: angle should be ~45, got ${r1.angle}`);
console.assert(Math.abs(r1.value - 1) < 0.05, `twist 45°: value should be ~1, got ${r1.value}`);

// Test 2: lean axis — forward tilt via gravity y
const rp2 = new RotateProcessor('lean', 45, 0);
rp2.setReferenceGravity(flat);
const leaned = { x: 0, y: 9.81 * Math.sin(30 * Math.PI / 180), z: -9.81 * Math.cos(30 * Math.PI / 180) };
const r2 = rp2.processGravity(leaned, false, 1);
console.assert(Math.abs(r2.angle - 30) < 1, `lean 30°: angle should be ~30, got ${r2.angle}`);

// Test 3: turn axis — yaw from quaternion
const rp3 = new RotateProcessor('turn', 45, 0);
rp3.setReferenceQuaternion(0);
const r3 = rp3.processYaw(30, false, 2);
console.assert(Math.abs(r3.angle - 30) < 0.1, `turn 30°: angle should be 30, got ${r3.angle}`);
console.assert(rp3.usesQuaternion === true, 'turn axis should use quaternion');

// Test 4: aliases
const rpRoll = new RotateProcessor('roll', 45, 0);
console.assert(rpRoll.usesQuaternion === false, 'roll (→twist) should not use quaternion');
const rpYaw = new RotateProcessor('yaw', 45, 0);
console.assert(rpYaw.usesQuaternion === true, 'yaw (→turn) should use quaternion');
const rpPitch = new RotateProcessor('pitch', 45, 0);
console.assert(rpPitch.usesQuaternion === false, 'pitch (→lean) should not use quaternion');

// Test 5: output value clamped ±1
const rp5 = new RotateProcessor('twist', 45, 0);
rp5.setReferenceGravity(flat);
const extreme = { x: 9.81, y: 0, z: 0 }; // 90° twist
const r5 = rp5.processGravity(extreme, false, 3);
console.assert(Math.abs(r5.value) <= 1.001, `value should be clamped to ±1, got ${r5.value}`);

console.log('rotate-processor tests passed');
