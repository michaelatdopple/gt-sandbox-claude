import type { Vector3, Quaternion, LookInput } from './types';
import { applyDeadzone } from './deadzone';

const DEG = 180 / Math.PI;

export class LookProcessor {
  private refQuat: Quaternion = { x: 0, y: 0, z: 0, w: 1 };
  private refInverse: Quaternion = { x: 0, y: 0, z: 0, w: 1 };
  private maxAngle: number;
  private deadzone: number;
  private sensitivity: { x: number; y: number };

  constructor(maxAngle = 45, deadzone = 2, sensitivity = { x: 1, y: 1 }) {
    this.maxAngle = maxAngle;
    this.deadzone = deadzone;
    this.sensitivity = sensitivity;
  }

  setReference(quat: Quaternion): void {
    this.refQuat = { ...quat };
    // Inverse of unit quaternion = conjugate
    this.refInverse = { x: -quat.x, y: -quat.y, z: -quat.z, w: quat.w };
  }

  process(orientation: Quaternion, gravity: Vector3, atRest: boolean, timestamp: number): LookInput {
    // Delta quaternion: inverse(ref) * current
    const delta = quatMultiply(this.refInverse, orientation);

    // Extract yaw and pitch from delta quaternion
    // Yaw (Y-axis rotation): atan2(2*(wy - xz), 1 - 2*(y²+z²))
    // Pitch (X-axis rotation): asin(2*(wx + yz))
    const yawRaw = Math.atan2(
      2 * (delta.w * delta.y - delta.x * delta.z),
      1 - 2 * (delta.y * delta.y + delta.z * delta.z)
    ) * DEG;

    const sinP = 2 * (delta.w * delta.x + delta.y * delta.z);
    const pitchRaw = Math.asin(Math.max(-1, Math.min(1, sinP))) * DEG;

    // Apply sensitivity
    const yaw = yawRaw * this.sensitivity.x;
    const pitch = pitchRaw * this.sensitivity.y;

    // Normalize to -1..1
    const x = applyDeadzone(yaw, this.deadzone, this.maxAngle);
    const y = applyDeadzone(pitch, this.deadzone, this.maxAngle);
    const magnitude = Math.min(1, Math.sqrt(x * x + y * y));

    return { yaw, pitch, x, y, magnitude, atRest, timestamp };
  }
}

/** Hamilton product of two quaternions */
function quatMultiply(a: Quaternion, b: Quaternion): Quaternion {
  return {
    x: a.w * b.x + a.x * b.w + a.y * b.z - a.z * b.y,
    y: a.w * b.y - a.x * b.z + a.y * b.w + a.z * b.x,
    z: a.w * b.z + a.x * b.y - a.y * b.x + a.z * b.w,
    w: a.w * b.w - a.x * b.x - a.y * b.y - a.z * b.z,
  };
}

export { quatMultiply };
