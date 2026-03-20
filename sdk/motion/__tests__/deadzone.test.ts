import { applyDeadzone } from '../deadzone';

// Test 1: value below deadzone → 0
console.assert(applyDeadzone(1, 2, 25) === 0, 'below deadzone should be 0');

// Test 2: value at deadzone boundary → 0
console.assert(applyDeadzone(2, 2, 25) === 0, 'at deadzone should be 0');

// Test 3: value at maxAngle → 1
const atMax = applyDeadzone(25, 2, 25);
console.assert(Math.abs(atMax - 1) < 0.001, `at maxAngle should be 1, got ${atMax}`);

// Test 4: value between deadzone and maxAngle → linearly rescaled
const mid = applyDeadzone(13.5, 2, 25); // halfway between 2 and 25
console.assert(Math.abs(mid - 0.5) < 0.001, `mid should be ~0.5, got ${mid}`);

// Test 5: negative values work symmetrically
const neg = applyDeadzone(-25, 2, 25);
console.assert(Math.abs(neg - (-1)) < 0.001, `negative max should be -1, got ${neg}`);

// Test 6: zero deadzone → passthrough normalized
const noDeadzone = applyDeadzone(12.5, 0, 25);
console.assert(Math.abs(noDeadzone - 0.5) < 0.001, `no deadzone: 12.5/25 = 0.5, got ${noDeadzone}`);

// Test 7: value beyond maxAngle → clamped to 1
const beyond = applyDeadzone(30, 2, 25);
console.assert(Math.abs(beyond - 1) < 0.001, `beyond max should clamp to 1, got ${beyond}`);

console.log('deadzone tests passed');
