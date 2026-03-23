import { LookProcessor, quatMultiply } from '../look-processor';

// Test 1: no rotation → yaw=0, pitch=0
const lp = new LookProcessor(45, 0);
lp.setReference(0, 0);
const r1 = lp.process(0, 0, { x: 0, y: 0, z: -9.81 }, false, 0);
console.assert(Math.abs(r1.yaw) < 0.1, `no rotation: yaw should be ~0, got ${r1.yaw}`);
console.assert(Math.abs(r1.pitch) < 0.1, `no rotation: pitch should be ~0, got ${r1.pitch}`);

// Test 2: alpha=45 → yaw=45 (alpha is heading/yaw in W3C)
const r2 = lp.process(45, 0, { x: 0, y: 0, z: -9.81 }, false, 1);
console.assert(Math.abs(r2.yaw - 45) < 1, `45° yaw: should be ~45, got ${r2.yaw}`);
console.assert(Math.abs(r2.pitch) < 1, `45° yaw: pitch should be ~0, got ${r2.pitch}`);

// Test 3: beta=30 → pitch=30
const r3 = lp.process(0, 30, { x: 0, y: 0, z: -9.81 }, false, 2);
console.assert(Math.abs(r3.pitch - 30) < 1, `30° pitch: should be ~30, got ${r3.pitch}`);
console.assert(Math.abs(r3.yaw) < 1, `30° pitch: yaw should be ~0, got ${r3.yaw}`);

// Test 4: normalized output at maxAngle
const lp2 = new LookProcessor(45, 0);
lp2.setReference(0, 0);
const r4 = lp2.process(45, 0, { x: 0, y: 0, z: -9.81 }, false, 3);
console.assert(Math.abs(r4.x - 1) < 0.01, `at maxAngle: x should be ~1, got ${r4.x}`);

// Test 5: alpha wrapping (350 → 10 = +20° delta, not -340°)
const lp3 = new LookProcessor(45, 0);
lp3.setReference(350, 0);
const r5 = lp3.process(10, 0, { x: 0, y: 0, z: -9.81 }, false, 4);
console.assert(Math.abs(r5.yaw - 20) < 1, `wrapping: 350→10 should be +20°, got ${r5.yaw}`);

// Test 6: quaternion multiply correctness (retained export)
const a = { x: 1, y: 0, z: 0, w: 0 };
const b = { x: 0, y: 1, z: 0, w: 0 };
const ab = quatMultiply(a, b);
console.assert(Math.abs(ab.z - 1) < 0.001, `i*j should be k, got z=${ab.z}`);
console.assert(Math.abs(ab.w) < 0.001, `i*j: w should be 0, got ${ab.w}`);

console.log('look-processor tests passed');
