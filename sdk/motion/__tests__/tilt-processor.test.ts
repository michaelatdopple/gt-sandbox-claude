import { TiltProcessor } from '../tilt-processor';

// Device "flat" — gravity straight down (z = -9.81)
const flat = { x: 0, y: 0, z: -9.81 };

// Test 1: neutral reference → zero tilt
const tp = new TiltProcessor(25, 2);
tp.setReference(flat);
const r1 = tp.process(flat, false, 0);
console.assert(Math.abs(r1.x) < 0.001, `neutral x should be ~0, got ${r1.x}`);
console.assert(Math.abs(r1.y) < 0.001, `neutral y should be ~0, got ${r1.y}`);

// Test 2: tilted ~25° right → x≈1.0
// At 25° tilt right, gravity.x = 9.81*sin(25°) ≈ 4.14, gravity.z = -9.81*cos(25°) ≈ -8.89
const tiltRight = { x: 9.81 * Math.sin(25 * Math.PI / 180), y: 0, z: -9.81 * Math.cos(25 * Math.PI / 180) };
const r2 = tp.process(tiltRight, false, 1);
console.assert(r2.x > 0.9, `25° right: x should be ~1, got ${r2.x}`);
console.assert(Math.abs(r2.y) < 0.1, `25° right: y should be ~0, got ${r2.y}`);

// Test 3: tilted left → negative x
const tiltLeft = { x: -tiltRight.x, y: 0, z: tiltRight.z };
const r3 = tp.process(tiltLeft, false, 2);
console.assert(r3.x < -0.9, `25° left: x should be ~-1, got ${r3.x}`);

// Test 4: tilted forward → positive y
const tiltFwd = { x: 0, y: 9.81 * Math.sin(25 * Math.PI / 180), z: -9.81 * Math.cos(25 * Math.PI / 180) };
const r4 = tp.process(tiltFwd, false, 3);
console.assert(r4.y > 0.9, `25° fwd: y should be ~1, got ${r4.y}`);

// Test 5: sensitivity scaling
const tp2 = new TiltProcessor(25, 0, { x: 2, y: 0.5 });
tp2.setReference(flat);
const halfTilt = { x: 9.81 * Math.sin(12.5 * Math.PI / 180), y: 9.81 * Math.sin(12.5 * Math.PI / 180), z: -9.81 * Math.cos(12.5 * Math.PI / 180) };
const r5 = tp2.process(halfTilt, false, 4);
// x sensitivity=2 → 12.5°*2=25° → value≈1
// y sensitivity=0.5 → 12.5°*0.5=6.25° → value≈6.25/25=0.25
console.assert(r5.x > 0.9, `sens x=2: should amplify, got ${r5.x}`);
console.assert(r5.y < 0.4, `sens y=0.5: should reduce, got ${r5.y}`);

console.log('tilt-processor tests passed');
