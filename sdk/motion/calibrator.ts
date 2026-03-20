import type { Vector3, Quaternion } from './types';

const SKIP_FRAMES = 15;
const COLLECT_FRAMES = 30;

export type CalibratorMode = 'vector' | 'quaternion';

export class Calibrator {
  readonly mode: CalibratorMode;
  private skipCount = 0;
  private collectCount = 0;
  private skipPhase: boolean;
  private _hasReference = false;

  // Vector accumulator
  private sumX = 0;
  private sumY = 0;
  private sumZ = 0;

  // Quaternion accumulator
  private sumQx = 0;
  private sumQy = 0;
  private sumQz = 0;
  private sumQw = 0;
  private firstQuat: Quaternion | null = null;

  private _reference: Vector3 | Quaternion | null = null;

  constructor(mode: CalibratorMode, skipPhase = true) {
    this.mode = mode;
    this.skipPhase = skipPhase;
  }

  get hasReference(): boolean { return this._hasReference; }
  get reference(): Vector3 | Quaternion | null { return this._reference; }

  feedVector(v: Vector3): boolean {
    if (this._hasReference) return true;

    if (this.skipPhase && this.skipCount < SKIP_FRAMES) {
      this.skipCount++;
      return false;
    }

    this.sumX += v.x;
    this.sumY += v.y;
    this.sumZ += v.z;
    this.collectCount++;

    if (this.collectCount >= COLLECT_FRAMES) {
      this._reference = {
        x: this.sumX / COLLECT_FRAMES,
        y: this.sumY / COLLECT_FRAMES,
        z: this.sumZ / COLLECT_FRAMES,
      };
      this._hasReference = true;
      return true;
    }
    return false;
  }

  feedQuaternion(q: Quaternion): boolean {
    if (this._hasReference) return true;

    if (this.skipPhase && this.skipCount < SKIP_FRAMES) {
      this.skipCount++;
      return false;
    }

    // Hemisphere normalization: flip if dot product with first quat < 0
    if (this.firstQuat === null) {
      this.firstQuat = { ...q };
    } else {
      const dot = q.x * this.firstQuat.x + q.y * this.firstQuat.y +
                  q.z * this.firstQuat.z + q.w * this.firstQuat.w;
      if (dot < 0) {
        q = { x: -q.x, y: -q.y, z: -q.z, w: -q.w };
      }
    }

    this.sumQx += q.x;
    this.sumQy += q.y;
    this.sumQz += q.z;
    this.sumQw += q.w;
    this.collectCount++;

    if (this.collectCount >= COLLECT_FRAMES) {
      // Normalize averaged quaternion
      const len = Math.sqrt(
        this.sumQx * this.sumQx + this.sumQy * this.sumQy +
        this.sumQz * this.sumQz + this.sumQw * this.sumQw
      );
      this._reference = {
        x: this.sumQx / len,
        y: this.sumQy / len,
        z: this.sumQz / len,
        w: this.sumQw / len,
      };
      this._hasReference = true;
      return true;
    }
    return false;
  }

  reset(skipPhase = true): void {
    this.skipCount = 0;
    this.collectCount = 0;
    this.skipPhase = skipPhase;
    this._hasReference = false;
    this._reference = null;
    this.sumX = this.sumY = this.sumZ = 0;
    this.sumQx = this.sumQy = this.sumQz = this.sumQw = 0;
    this.firstQuat = null;
  }
}
