import { RestDetector } from '../rest-detector';

// Test 1: not at rest initially
const rd = new RestDetector();
console.assert(rd.atRest === false, 'should start not at rest');

// Test 2: becomes at rest after stable gravity for 400ms
const rd2 = new RestDetector();
const stableGravity = { x: 0, y: 0, z: 9.81 };
// Feed 30 samples (fills buffer) + enough to reach 400ms settle at 60Hz
for (let i = 0; i < 55; i++) {
  rd2.update(stableGravity, 1/60);
}
console.assert(rd2.atRest === true, 'should be at rest after 400ms of stable gravity');

// Test 3: exits rest immediately on motion
const moving = { x: 1, y: 0, z: 9.81 };
rd2.update(moving, 1/60);
console.assert(rd2.atRest === false, 'should exit rest immediately on motion');

// Test 4: reset clears state
const rd3 = new RestDetector();
for (let i = 0; i < 55; i++) {
  rd3.update(stableGravity, 1/60);
}
console.assert(rd3.atRest === true, 'should be at rest');
rd3.reset();
console.assert(rd3.atRest === false, 'should not be at rest after reset');

console.log('rest-detector tests passed');
