import type { Vector3, TiltInput } from './types';
import { applyDeadzone } from './deadzone';

export class TiltProcessor {
  private refGravity: Vector3 = { x: 0, y: 0, z: -9.81 };
  private zAngle = 0; // Z-rotation compensation angle (radians)
  private maxAngle: number;
  private deadzone: number;
  private sensitivity: { x: number; y: number };

  constructor(maxAngle = 25, deadzone = 2, sensitivity = { x: 1, y: 1 }) {
    this.maxAngle = maxAngle;
    this.deadzone = deadzone;
    this.sensitivity = sensitivity;
  }

  setReference(gravity: Vector3): void {
    this.refGravity = { ...gravity };
    // Compute Z-rotation angle for wrist orientation compensation
    // Use || 0 to normalize -0 to 0 (avoids atan2(0, -0) = π edge case)
    this.zAngle = Math.atan2(gravity.x || 0, (-gravity.y) || 0);
  }

  /** @param gravity Use MotionData.smoothGravity (low-pass filtered) per spec Section 5.1 */
  process(gravity: Vector3, atRest: boolean, timestamp: number): TiltInput {
    // Compute tilt angles using atan2
    const currentAngleX = Math.atan2(gravity.x, -gravity.z) * (180 / Math.PI);
    const currentAngleY = Math.atan2(gravity.y, -gravity.z) * (180 / Math.PI);
    const refAngleX = Math.atan2(this.refGravity.x, -this.refGravity.z) * (180 / Math.PI);
    const refAngleY = Math.atan2(this.refGravity.y, -this.refGravity.z) * (180 / Math.PI);

    let deltaX = currentAngleX - refAngleX;
    let deltaY = currentAngleY - refAngleY;

    // Z-rotation compensation: rotate delta back to calibration orientation
    const cos = Math.cos(this.zAngle);
    const sin = Math.sin(this.zAngle);
    const rotX = deltaX * cos - deltaY * sin;
    const rotY = deltaX * sin + deltaY * cos;

    // Apply sensitivity
    const scaledX = rotX * this.sensitivity.x;
    const scaledY = rotY * this.sensitivity.y;

    // Apply deadzone and normalize
    const x = applyDeadzone(scaledX, this.deadzone, this.maxAngle);
    const y = applyDeadzone(scaledY, this.deadzone, this.maxAngle);
    const magnitude = Math.min(1, Math.sqrt(x * x + y * y));

    return { x, y, magnitude, atRest, timestamp };
  }
}
