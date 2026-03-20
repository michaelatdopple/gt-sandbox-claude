import { AutoRecalibrator } from '../auto-recalibrator';

const ref = { x: 0, y: 0, z: -9.81 };
const drifted = { x: 0.1, y: 0, z: -9.81 };

// Test 1: at rest → fast rate, reference moves toward current quickly
const ar = new AutoRecalibrator('game');
const result1 = ar.updateVector(ref, drifted, 0, true, 1.0);
// At rest rate=3.0, t=1-exp(-3)≈0.95 → should be very close to drifted
console.assert(Math.abs(result1.x - 0.1) < 0.02, `rest rate: x should be close to 0.1, got ${result1.x}`);

// Test 2: full tilt (magnitude=1) → frozen rate
const ar2 = new AutoRecalibrator('game');
const result2 = ar2.updateVector(ref, drifted, 1.0, false, 1.0);
// Frozen rate=0.02, t=1-exp(-0.02)≈0.0198 → barely moves
console.assert(result2.x < 0.005, `frozen rate: x should barely move, got ${result2.x}`);

// Test 3: 'full' fusion → different frozen rate
const ar3 = new AutoRecalibrator('full');
const result3 = ar3.updateVector(ref, drifted, 1.0, false, 1.0);
// Frozen rate=0.08, t=1-exp(-0.08)≈0.077 → moves slightly more than game
console.assert(result3.x > result2.x, `full fusion frozen rate should be higher than game`);

// Test 4: pause stops all correction
const ar4 = new AutoRecalibrator('game');
ar4.pause();
const result4 = ar4.updateVector(ref, drifted, 0, true, 1.0);
console.assert(result4.x === 0, 'paused: reference should not move');
ar4.resume();
const result5 = ar4.updateVector(ref, drifted, 0, true, 1.0);
console.assert(result5.x > 0, 'resumed: reference should move');

// Test 5: frame-rate independence
const ar5a = new AutoRecalibrator('game');
const ar5b = new AutoRecalibrator('game');
// 30 frames at 30Hz = 1 second
let refA = { ...ref };
for (let i = 0; i < 30; i++) refA = ar5a.updateVector(refA, drifted, 0.5, false, 1/30);
// 120 frames at 120Hz = 1 second
let refB = { ...ref };
for (let i = 0; i < 120; i++) refB = ar5b.updateVector(refB, drifted, 0.5, false, 1/120);
console.assert(Math.abs(refA.x - refB.x) < 0.005, `frame-rate independent: 30Hz=${refA.x} vs 120Hz=${refB.x}`);

console.log('auto-recalibrator tests passed');
