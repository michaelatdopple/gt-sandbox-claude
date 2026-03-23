import type { Vector3, PanInput } from './types';
import { applyDeadzone } from './deadzone';

/**
 * Pan processor — Euler-based panning with edge absorption.
 *
 * Same core math as LookProcessor (reference → delta → yaw/pitch → normalized x,y),
 * with edge absorption instead of clamping at boundary.
 *
 * Edge absorption: when normalized magnitude exceeds 1, shift the reference
 * toward the current orientation. This allows infinite scrolling past the
 * boundary without hard stops, while maintaining firm response within bounds.
 */
export class PanProcessor {
  private refAlpha = 0;
  private refBeta = 0;
  private maxAngle: number;
  private deadzone: number;
  private sensitivity: { x: number; y: number };
  private absorbRate: number;

  constructor(
    maxAngle = 30,
    deadzone = 2,
    sensitivity = { x: 1, y: 1 },
    absorbRate = 0.3
  ) {
    this.maxAngle = maxAngle;
    this.deadzone = deadzone;
    this.sensitivity = sensitivity;
    this.absorbRate = absorbRate;
  }

  get referenceAlpha(): number { return this.refAlpha; }
  get referenceBeta(): number { return this.refBeta; }

  setReference(alpha: number, beta: number): void {
    this.refAlpha = alpha;
    this.refBeta = beta;
  }

  process(alpha: number, beta: number, gravity: Vector3, atRest: boolean, timestamp: number): PanInput {
    // Compute yaw delta (alpha wraps 0..360)
    let yawRaw = alpha - this.refAlpha;
    if (yawRaw > 180) yawRaw -= 360;
    if (yawRaw < -180) yawRaw += 360;

    const pitchRaw = beta - this.refBeta;

    // Apply sensitivity
    const yaw = yawRaw * this.sensitivity.x;
    const pitch = pitchRaw * this.sensitivity.y;

    // Normalize to -1..1
    const x = applyDeadzone(yaw, this.deadzone, this.maxAngle);
    const y = applyDeadzone(pitch, this.deadzone, this.maxAngle);
    const magnitude = Math.sqrt(x * x + y * y);

    // Edge absorption: when magnitude exceeds 1, shift reference toward current
    if (magnitude > 1) {
      const excess = magnitude - 1;
      const t = Math.min(0.5, excess * this.absorbRate);
      // Lerp reference toward current
      this.refAlpha = lerpAngle(this.refAlpha, alpha, t);
      this.refBeta = this.refBeta + (beta - this.refBeta) * t;
    }

    return {
      yaw,
      pitch,
      x: Math.max(-1, Math.min(1, x)),
      y: Math.max(-1, Math.min(1, y)),
      magnitude: Math.min(1, magnitude),
      atRest,
      timestamp,
    };
  }
}

/** Lerp between two angles handling 0..360 wrapping */
function lerpAngle(from: number, to: number, t: number): number {
  let delta = to - from;
  if (delta > 180) delta -= 360;
  if (delta < -180) delta += 360;
  let result = from + delta * t;
  if (result < 0) result += 360;
  if (result >= 360) result -= 360;
  return result;
}
