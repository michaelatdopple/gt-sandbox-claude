import type { Vector3 } from './types';

const BUFFER_SIZE = 30;
const VARIANCE_THRESHOLD = 0.0002;
const SETTLE_TIME = 0.4; // seconds

export class RestDetector {
  private buffer: Vector3[] = [];
  private index = 0;
  private full = false;
  private settleTimer = 0;
  private _atRest = false;

  get atRest(): boolean { return this._atRest; }

  update(gravity: Vector3, deltaTime: number): void {
    // Write to circular buffer
    if (this.buffer.length < BUFFER_SIZE) {
      this.buffer.push({ ...gravity });
    } else {
      this.buffer[this.index] = { ...gravity };
    }
    this.index = (this.index + 1) % BUFFER_SIZE;
    if (this.index === 0) this.full = true;

    const count = this.full ? BUFFER_SIZE : this.buffer.length;
    if (count < 2) return;

    // Compute mean
    let mx = 0, my = 0, mz = 0;
    for (let i = 0; i < count; i++) {
      mx += this.buffer[i].x;
      my += this.buffer[i].y;
      mz += this.buffer[i].z;
    }
    mx /= count; my /= count; mz /= count;

    // Compute variance
    let variance = 0;
    for (let i = 0; i < count; i++) {
      const dx = this.buffer[i].x - mx;
      const dy = this.buffer[i].y - my;
      const dz = this.buffer[i].z - mz;
      variance += dx * dx + dy * dy + dz * dz;
    }
    variance /= count;

    if (variance < VARIANCE_THRESHOLD) {
      this.settleTimer += deltaTime;
      if (this.settleTimer >= SETTLE_TIME) {
        this._atRest = true;
      }
    } else {
      this.settleTimer = 0;
      this._atRest = false;
    }
  }

  reset(): void {
    this.buffer = [];
    this.index = 0;
    this.full = false;
    this.settleTimer = 0;
    this._atRest = false;
  }
}
