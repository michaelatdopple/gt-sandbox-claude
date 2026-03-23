import type { ModeType, ModeInput, OrientationData, MotionData, MotionController } from './types';
import { RestDetector } from './rest-detector';
import { AutoRecalibrator } from './auto-recalibrator';

/**
 * Event source types for different controller modes.
 * - 'orientation': listens to loop:orientation (look, pan)
 * - 'motion': listens to loop:motion (tilt, rotate gravity axes)
 * - 'both': listens to both (rotate turn axis needs orientation + gravity for rest)
 */
export type EventSource = 'orientation' | 'motion' | 'both';

export abstract class BaseController<T extends ModeInput> implements MotionController<T> {
  abstract readonly mode: ModeType;
  abstract readonly eventSource: EventSource;

  private _calibrated = false;
  private _active = true;
  private _lastInput: T | null = null;
  private _lastRest = false;
  private listeners: Map<string, Set<Function>> = new Map();

  protected restDetector = new RestDetector();
  protected autoRecalibrator: AutoRecalibrator;
  protected lastTimestamp = 0;

  // Latest data from each event source
  protected latestOrientation: OrientationData | null = null;
  protected latestMotion: MotionData | null = null;

  constructor(autoRecalibrator: AutoRecalibrator) {
    this.autoRecalibrator = autoRecalibrator;
  }

  get calibrated(): boolean { return this._calibrated; }
  get active(): boolean { return this._active; }
  get lastInput(): T | null { return this._lastInput; }

  on(event: 'input', handler: (input: T) => void): this;
  on(event: 'calibrated', handler: () => void): this;
  on(event: 'rest', handler: (atRest: boolean) => void): this;
  on(event: string, handler: Function): this {
    if (!this.listeners.has(event)) this.listeners.set(event, new Set());
    this.listeners.get(event)!.add(handler);
    return this;
  }

  off(event: string, handler: Function): this {
    this.listeners.get(event)?.delete(handler);
    return this;
  }

  protected emit(event: string, ...args: unknown[]): void {
    this.listeners.get(event)?.forEach(fn => fn(...args));
  }

  /** Called when a loop:orientation event arrives */
  handleOrientation(data: OrientationData): void {
    this.latestOrientation = data;
    if (this.eventSource === 'orientation' || this.eventSource === 'both') {
      this.tick(data.alpha); // use alpha as timestamp proxy — real timestamp set in tick
    }
  }

  /** Called when a loop:motion event arrives */
  handleMotion(data: MotionData): void {
    this.latestMotion = data;
    if (this.eventSource === 'motion') {
      this.tick(Date.now());
    }
    // For 'both' mode, orientation is the primary driver — motion just updates latestMotion
  }

  private tick(now: number): void {
    if (!this._active) return;

    const timestamp = Date.now();
    const deltaTime = this.lastTimestamp > 0
      ? (timestamp - this.lastTimestamp) / 1000
      : 1 / 60;
    this.lastTimestamp = timestamp;

    // Step 1: Rest detection (uses gravity from motion data)
    const gravity = this.latestMotion?.gravity;
    if (gravity) {
      this.restDetector.update(gravity, deltaTime);
    }
    const atRest = this.restDetector.atRest;
    if (atRest !== this._lastRest) {
      this._lastRest = atRest;
      this.emit('rest', atRest);
    }

    // Step 2: Calibration
    if (!this._calibrated) {
      const done = this.feedCalibrator();
      if (done) {
        this._calibrated = true;
        this.onCalibrated();
        this.emit('calibrated');
      }
      return;
    }

    // Step 3: Auto-recalibration
    this.updateAutoRecal(atRest, deltaTime);

    // Step 4: Mode-specific processing
    const input = this.computeInput(atRest, timestamp);
    this._lastInput = input;
    this.emit('input', input);
  }

  /** Subclass feeds the appropriate calibrator */
  protected abstract feedCalibrator(): boolean;

  /** Called once when calibration completes */
  protected abstract onCalibrated(): void;

  /** Update auto-recalibration reference */
  protected abstract updateAutoRecal(atRest: boolean, deltaTime: number): void;

  /** Compute mode-specific input from current data */
  protected abstract computeInput(atRest: boolean, timestamp: number): T;

  recalibrate(): void {
    this._calibrated = false;
    this.restDetector.reset();
    this.resetCalibrator(false);
  }

  protected abstract resetCalibrator(skipPhase: boolean): void;

  pauseRecalibration(): void {
    this.autoRecalibrator.pause();
  }

  resumeRecalibration(): void {
    this.autoRecalibrator.resume();
  }

  stop(): void {
    this._active = false;
    this.listeners.clear();
  }
}
