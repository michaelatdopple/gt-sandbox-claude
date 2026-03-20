import { Calibrator } from '../calibrator';

const gravity = { x: 0.1, y: -0.2, z: -9.8 };
const quat = { x: 0.1, y: 0.2, z: 0.3, w: 0.927 }; // roughly unit

// Test 1: skip phase - first 15 frames don't produce reference
const cal = new Calibrator('vector');
for (let i = 0; i < 15; i++) {
  console.assert(cal.feedVector(gravity) === false, `frame ${i}: should still be skipping`);
}
console.assert(cal.hasReference === false, 'should not have reference during skip');

// Test 2: collection phase - 30 more frames produces reference
for (let i = 0; i < 29; i++) {
  console.assert(cal.feedVector(gravity) === false, `collect ${i}: not done yet`);
}
console.assert(cal.feedVector(gravity) === true, 'frame 45: should have reference');
console.assert(cal.hasReference === true, 'hasReference should be true');

// Test 3: reference is the averaged value
const ref = cal.reference as { x: number; y: number; z: number };
console.assert(Math.abs(ref.x - 0.1) < 0.001, `ref.x should be ~0.1, got ${ref.x}`);
console.assert(Math.abs(ref.y - (-0.2)) < 0.001, `ref.y should be ~-0.2, got ${ref.y}`);

// Test 4: quaternion with hemisphere normalization
const qcal = new Calibrator('quaternion');
for (let i = 0; i < 15; i++) qcal.feedQuaternion(quat); // skip
for (let i = 0; i < 15; i++) qcal.feedQuaternion(quat); // collect first 15
// Feed flipped quaternion (opposite hemisphere)
const flipped = { x: -0.1, y: -0.2, z: -0.3, w: -0.927 };
for (let i = 0; i < 15; i++) qcal.feedQuaternion(flipped);
console.assert(qcal.hasReference === true, 'quat calibrator should complete');
const qref = qcal.reference as { x: number; y: number; z: number; w: number };
// After hemisphere normalization, flipped should have been un-flipped
// so average should be close to original quat direction
console.assert(qref.w > 0, `qref.w should be positive (normalized), got ${qref.w}`);

// Test 5: reset and recalibrate without skip
cal.reset(false); // skipPhase = false
console.assert(cal.hasReference === false, 'should not have reference after reset');
for (let i = 0; i < 30; i++) cal.feedVector(gravity);
console.assert(cal.hasReference === true, 'should calibrate in 30 frames without skip');

console.log('calibrator tests passed');
