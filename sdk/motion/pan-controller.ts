import type { PanInput, PanOptions } from './types';
import { BaseController, type EventSource } from './controller';
import { AutoRecalibrator } from './auto-recalibrator';
import { PanProcessor } from './pan-processor';

const DEFAULTS: Required<PanOptions> = {
  frequency: 60,
  maxAngle: 30,
  deadzone: 2,
  sensitivity: { x: 1, y: 1 },
  sensorFusion: 'game',
  autoRecalibrate: false,  // pan mode: no auto-recalibration by default
  absorbRate: 0.3,
};

const SKIP_FRAMES = 15;
const COLLECT_FRAMES = 30;

export class PanController extends BaseController<PanInput> {
  readonly mode = 'pan' as const;
  readonly eventSource: EventSource = 'orientation';
  private processor: PanProcessor;
  private opts: Required<PanOptions>;

  // Calibration state
  private calSkip = 0;
  private calCount = 0;
  private calSumBeta = 0;
  private calSinAlpha = 0;
  private calCosAlpha = 0;

  constructor(options: PanOptions = {}) {
    const opts = { ...DEFAULTS, ...options };
    super(new AutoRecalibrator(opts.sensorFusion));
    this.opts = opts;
    this.processor = new PanProcessor(opts.maxAngle, opts.deadzone, opts.sensitivity, opts.absorbRate);
  }

  protected feedCalibrator(): boolean {
    const o = this.latestOrientation;
    if (!o) return false;

    if (this.calSkip < SKIP_FRAMES) {
      this.calSkip++;
      return false;
    }

    const rad = o.alpha * Math.PI / 180;
    this.calSinAlpha += Math.sin(rad);
    this.calCosAlpha += Math.cos(rad);
    this.calSumBeta += o.beta;
    this.calCount++;

    return this.calCount >= COLLECT_FRAMES;
  }

  protected onCalibrated(): void {
    let refAlpha = Math.atan2(this.calSinAlpha, this.calCosAlpha) * 180 / Math.PI;
    if (refAlpha < 0) refAlpha += 360;
    const refBeta = this.calSumBeta / this.calCount;
    this.processor.setReference(refAlpha, refBeta);
  }

  protected updateAutoRecal(_atRest: boolean, _deltaTime: number): void {
    // Pan mode: no auto-recalibration by default (explicit recalibrate() only)
    // Edge absorption in the processor handles boundary behavior
  }

  protected computeInput(atRest: boolean, timestamp: number): PanInput {
    const o = this.latestOrientation;
    if (!o) return { yaw: 0, pitch: 0, x: 0, y: 0, magnitude: 0, atRest, timestamp };
    const gravity = this.latestMotion?.gravity ?? { x: 0, y: 0, z: -9.81 };
    return this.processor.process(o.alpha, o.beta, gravity, atRest, timestamp);
  }

  protected resetCalibrator(skipPhase: boolean): void {
    this.calSkip = skipPhase ? 0 : SKIP_FRAMES;
    this.calCount = 0;
    this.calSumBeta = 0;
    this.calSinAlpha = 0;
    this.calCosAlpha = 0;
  }
}
