import { LookProcessor, quatMultiply } from '../look-processor';

// Identity quaternion
const identity = { x: 0, y: 0, z: 0, w: 1 };

// Test 1: no rotation → yaw=0, pitch=0
const lp = new LookProcessor(45, 0);
lp.setReference(identity);
const r1 = lp.process(identity, { x: 0, y: 0, z: -9.81 }, false, 0);
console.assert(Math.abs(r1.yaw) < 0.1, `no rotation: yaw should be ~0, got ${r1.yaw}`);
console.assert(Math.abs(r1.pitch) < 0.1, `no rotation: pitch should be ~0, got ${r1.pitch}`);

// Test 2: 45° yaw rotation (around Y axis)
// Quaternion for 45° around Y: { x:0, y:sin(22.5°), z:0, w:cos(22.5°) }
const yaw45 = {
  x: 0,
  y: Math.sin(22.5 * Math.PI / 180),
  z: 0,
  w: Math.cos(22.5 * Math.PI / 180),
};
const r2 = lp.process(yaw45, { x: 0, y: 0, z: -9.81 }, false, 1);
console.assert(Math.abs(r2.yaw - 45) < 1, `45° yaw: should be ~45, got ${r2.yaw}`);
console.assert(Math.abs(r2.pitch) < 1, `45° yaw: pitch should be ~0, got ${r2.pitch}`);

// Test 3: 30° pitch rotation (around X axis)
const pitch30 = {
  x: Math.sin(15 * Math.PI / 180),
  y: 0,
  z: 0,
  w: Math.cos(15 * Math.PI / 180),
};
const r3 = lp.process(pitch30, { x: 0, y: 0, z: -9.81 }, false, 2);
console.assert(Math.abs(r3.pitch - 30) < 1, `30° pitch: should be ~30, got ${r3.pitch}`);
console.assert(Math.abs(r3.yaw) < 1, `30° pitch: yaw should be ~0, got ${r3.yaw}`);

// Test 4: normalized output clamps
const lp2 = new LookProcessor(45, 0);
lp2.setReference(identity);
const r4 = lp2.process(yaw45, { x: 0, y: 0, z: -9.81 }, false, 3);
console.assert(Math.abs(r4.x - 1) < 0.01, `at maxAngle: x should be ~1, got ${r4.x}`);

// Test 5: quaternion multiply correctness
const a = { x: 1, y: 0, z: 0, w: 0 };
const b = { x: 0, y: 1, z: 0, w: 0 };
const ab = quatMultiply(a, b);
// i*j = k → { x:0, y:0, z:1, w:0 }
console.assert(Math.abs(ab.z - 1) < 0.001, `i*j should be k, got z=${ab.z}`);
console.assert(Math.abs(ab.w) < 0.001, `i*j: w should be 0, got ${ab.w}`);

console.log('look-processor tests passed');
