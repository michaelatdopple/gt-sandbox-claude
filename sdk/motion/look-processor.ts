import type { Vector3, LookInput } from './types';
import { applyDeadzone } from './deadzone';

/**
 * Look processor — works directly with W3C Euler angles (alpha, beta, gamma).
 *
 * Since the native bridge already converts quaternion→Euler, there's no need
 * to convert back to quaternion. Direct Euler delta computation is simpler
 * and avoids frame convention issues.
 *
 * Alpha delta = yaw (left/right heading change)
 * Beta delta = pitch (tilt up/down)
 */
export class LookProcessor {
  private refAlpha = 0;
  private refBeta = 0;
  private maxAngle: number;
  private deadzone: number;
  private sensitivity: { x: number; y: number };

  constructor(maxAngle = 45, deadzone = 2, sensitivity = { x: 1, y: 1 }) {
    this.maxAngle = maxAngle;
    this.deadzone = deadzone;
    this.sensitivity = sensitivity;
  }

  setReference(alpha: number, beta: number): void {
    this.refAlpha = alpha;
    this.refBeta = beta;
  }

  process(alpha: number, beta: number, gravity: Vector3, atRest: boolean, timestamp: number): LookInput {
    // Compute yaw delta (alpha wraps 0..360)
    let yawRaw = alpha - this.refAlpha;
    // Normalize to -180..180
    if (yawRaw > 180) yawRaw -= 360;
    if (yawRaw < -180) yawRaw += 360;

    // Compute pitch delta (beta is -180..180, no wrapping needed for small angles)
    const pitchRaw = beta - this.refBeta;

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

/** Hamilton product of two quaternions (retained for other consumers) */
export function quatMultiply(a: { x: number; y: number; z: number; w: number }, b: { x: number; y: number; z: number; w: number }) {
  return {
    x: a.w * b.x + a.x * b.w + a.y * b.z - a.z * b.y,
    y: a.w * b.y - a.x * b.z + a.y * b.w + a.z * b.x,
    z: a.w * b.z + a.x * b.y - a.y * b.x + a.z * b.w,
    w: a.w * b.w - a.x * b.x - a.y * b.y - a.z * b.z,
  };
}
