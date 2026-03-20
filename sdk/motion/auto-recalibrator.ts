import type { Vector3, Quaternion, SensorFusion } from './types';

const RATE_REST = 3.0;
const RATE_MAX = 1.0;
const RATE_FROZEN_GAME = 0.02;
const RATE_FROZEN_FULL = 0.08;

export class AutoRecalibrator {
  private paused = false;
  private frozenRate: number;

  constructor(fusion: SensorFusion = 'game') {
    this.frozenRate = fusion === 'full' ? RATE_FROZEN_FULL : RATE_FROZEN_GAME;
  }

  pause(): void { this.paused = true; }
  resume(): void { this.paused = false; }

  updateVector(
    reference: Vector3,
    current: Vector3,
    inputMagnitude: number,
    atRest: boolean,
    deltaTime: number
  ): Vector3 {
    if (this.paused) return reference;
    const rate = this.selectRate(inputMagnitude, atRest);
    const t = 1 - Math.exp(-rate * deltaTime);
    return {
      x: reference.x + (current.x - reference.x) * t,
      y: reference.y + (current.y - reference.y) * t,
      z: reference.z + (current.z - reference.z) * t,
    };
  }

  updateQuaternion(
    reference: Quaternion,
    current: Quaternion,
    inputMagnitude: number,
    atRest: boolean,
    deltaTime: number
  ): Quaternion {
    if (this.paused) return reference;
    const rate = this.selectRate(inputMagnitude, atRest);
    const t = 1 - Math.exp(-rate * deltaTime);
    return slerp(reference, current, t);
  }

  private selectRate(inputMagnitude: number, atRest: boolean): number {
    if (atRest) return RATE_REST;
    const weight = 1 - Math.min(1, Math.max(0, inputMagnitude));
    return this.frozenRate + weight * (RATE_MAX - this.frozenRate);
  }
}

function slerp(a: Quaternion, b: Quaternion, t: number): Quaternion {
  // Ensure shortest path
  let dot = a.x * b.x + a.y * b.y + a.z * b.z + a.w * b.w;
  let bx = b.x, by = b.y, bz = b.z, bw = b.w;
  if (dot < 0) { dot = -dot; bx = -bx; by = -by; bz = -bz; bw = -bw; }

  if (dot > 0.9995) {
    // Linear interpolation for very close quaternions
    return normalizeQuat({
      x: a.x + (bx - a.x) * t,
      y: a.y + (by - a.y) * t,
      z: a.z + (bz - a.z) * t,
      w: a.w + (bw - a.w) * t,
    });
  }

  const theta = Math.acos(dot);
  const sinTheta = Math.sin(theta);
  const wa = Math.sin((1 - t) * theta) / sinTheta;
  const wb = Math.sin(t * theta) / sinTheta;

  return {
    x: wa * a.x + wb * bx,
    y: wa * a.y + wb * by,
    z: wa * a.z + wb * bz,
    w: wa * a.w + wb * bw,
  };
}

function normalizeQuat(q: Quaternion): Quaternion {
  const len = Math.sqrt(q.x * q.x + q.y * q.y + q.z * q.z + q.w * q.w);
  return { x: q.x / len, y: q.y / len, z: q.z / len, w: q.w / len };
}

export { slerp, normalizeQuat };
