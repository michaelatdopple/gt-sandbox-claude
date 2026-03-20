import type { ModeType, ModeInput, MotionData, MotionController } from './types';
import { RestDetector } from './rest-detector';
import { AutoRecalibrator } from './auto-recalibrator';

export abstract class BaseController<T extends ModeInput> implements MotionController<T> {
  abstract readonly mode: ModeType;

  private _calibrated = false;
  private _active = true;
  private _lastInput: T | null = null;
  private _lastRest = false;
  private listeners: Map<string, Set<Function>> = new Map();

  protected restDetector = new RestDetector();
  protected autoRecalibrator: AutoRecalibrator;
  protected lastTimestamp = 0;

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

  /** Called by subclass with each raw MotionData frame */
  protected processFrame(data: MotionData): void {
    if (!this._active) return;

    const deltaTime = this.lastTimestamp > 0
      ? (data.timestamp - this.lastTimestamp) / 1000
      : 1 / 60;
    this.lastTimestamp = data.timestamp;

    // Step 1: Rest detection
    this.restDetector.update(data.smoothGravity, deltaTime);
    const atRest = this.restDetector.atRest;
    if (atRest !== this._lastRest) {
      this._lastRest = atRest;
      this.emit('rest', atRest);
    }

    // Step 2: Calibration
    if (!this._calibrated) {
      const done = this.feedCalibrator(data);
      if (done) {
        this._calibrated = true;
        this.onCalibrated(data);
        this.emit('calibrated');
      }
      return; // Don't emit input during calibration
    }

    // Step 3: Auto-recalibration (updates reference in-place)
    this.updateAutoRecal(data, atRest, deltaTime);

    // Step 4: Mode-specific processing
    const input = this.computeInput(data, atRest);
    this._lastInput = input;
    this.emit('input', input);
  }

  /** Subclass feeds the appropriate calibrator (vector or quaternion) */
  protected abstract feedCalibrator(data: MotionData): boolean;

  /** Called once when calibration completes — set reference on processor */
  protected abstract onCalibrated(data: MotionData): void;

  /** Update auto-recalibration reference */
  protected abstract updateAutoRecal(data: MotionData, atRest: boolean, deltaTime: number): void;

  /** Compute mode-specific input from the current frame */
  protected abstract computeInput(data: MotionData, atRest: boolean): T;

  recalibrate(): void {
    this._calibrated = false;
    this.restDetector.reset();
    this.resetCalibrator(false); // no skip phase
  }

  /** Subclass resets its calibrator */
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
