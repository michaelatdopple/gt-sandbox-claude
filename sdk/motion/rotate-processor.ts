import type { Vector3, RotateAxis, RotateInput } from './types';
import { applyDeadzone } from './deadzone';

// Normalize axis aliases
function resolveAxis(axis: RotateAxis): 'twist' | 'turn' | 'lean' {
  switch (axis) {
    case 'roll': return 'twist';
    case 'yaw': return 'turn';
    case 'pitch': return 'lean';
    default: return axis as 'twist' | 'turn' | 'lean';
  }
}

export class RotateProcessor {
  private resolvedAxis: 'twist' | 'turn' | 'lean';
  private refAngle = 0;
  private maxAngle: number;
  private deadzone: number;
  private sensitivity: number;

  constructor(axis: RotateAxis = 'twist', maxAngle = 45, deadzone = 2, sensitivity = 1) {
    this.resolvedAxis = resolveAxis(axis);
    this.maxAngle = maxAngle;
    this.deadzone = deadzone;
    this.sensitivity = sensitivity;
  }

  get usesQuaternion(): boolean { return this.resolvedAxis === 'turn'; }

  setReferenceGravity(gravity: Vector3): void {
    if (this.resolvedAxis === 'twist') {
      this.refAngle = Math.atan2(gravity.x, -gravity.z) * (180 / Math.PI);
    } else if (this.resolvedAxis === 'lean') {
      this.refAngle = Math.atan2(gravity.y, -gravity.z) * (180 / Math.PI);
    }
  }

  setReferenceQuaternion(yawDegrees: number): void {
    this.refAngle = yawDegrees;
  }

  processGravity(gravity: Vector3, atRest: boolean, timestamp: number): RotateInput {
    let currentAngle: number;
    if (this.resolvedAxis === 'twist') {
      currentAngle = Math.atan2(gravity.x, -gravity.z) * (180 / Math.PI);
    } else {
      currentAngle = Math.atan2(gravity.y, -gravity.z) * (180 / Math.PI);
    }

    const delta = (currentAngle - this.refAngle) * this.sensitivity;
    const value = applyDeadzone(delta, this.deadzone, this.maxAngle);
    return { angle: delta, value, atRest, timestamp };
  }

  processYaw(yawDegrees: number, atRest: boolean, timestamp: number): RotateInput {
    const delta = (yawDegrees - this.refAngle) * this.sensitivity;
    const value = applyDeadzone(delta, this.deadzone, this.maxAngle);
    return { angle: delta, value, atRest, timestamp };
  }
}
