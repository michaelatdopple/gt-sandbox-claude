import type { LookInput, LookOptions } from './types';
import { BaseController, type EventSource } from './controller';
import { AutoRecalibrator } from './auto-recalibrator';
import { LookProcessor } from './look-processor';

const DEFAULTS: Required<LookOptions> = {
  frequency: 60,
  maxAngle: 45,
  deadzone: 2,
  sensitivity: { x: 1, y: 1 },
  sensorFusion: 'game',
  autoRecalibrate: true,
};

// Number of frames to skip (let sensors settle) then collect for calibration
const SKIP_FRAMES = 15;
const COLLECT_FRAMES = 30;

export class LookController extends BaseController<LookInput> {
  readonly mode = 'look' as const;
  readonly eventSource: EventSource = 'orientation';
  private processor: LookProcessor;
  private opts: Required<LookOptions>;

  // Euler reference for auto-recalibration
  private refAlpha = 0;
  private refBeta = 0;

  // Calibration state
  private calSkip = 0;
  private calCount = 0;
  private calSumAlpha = 0;
  private calSumBeta = 0;
  private calSinAlpha = 0;  // for circular mean
  private calCosAlpha = 0;

  constructor(options: LookOptions = {}) {
    const opts = { ...DEFAULTS, ...options };
    super(new AutoRecalibrator(opts.sensorFusion));
    this.opts = opts;
    this.processor = new LookProcessor(opts.maxAngle, opts.deadzone, opts.sensitivity);
  }

  protected feedCalibrator(): boolean {
    const o = this.latestOrientation;
    if (!o) return false;

    if (this.calSkip < SKIP_FRAMES) {
      this.calSkip++;
      return false;
    }

    // Circular mean for alpha (wraps 0..360)
    const rad = o.alpha * Math.PI / 180;
    this.calSinAlpha += Math.sin(rad);
    this.calCosAlpha += Math.cos(rad);
    this.calSumBeta += o.beta;
    this.calCount++;

    return this.calCount >= COLLECT_FRAMES;
  }

  protected onCalibrated(): void {
    // Circular mean for alpha
    this.refAlpha = Math.atan2(this.calSinAlpha, this.calCosAlpha) * 180 / Math.PI;
    if (this.refAlpha < 0) this.refAlpha += 360;
    this.refBeta = this.calSumBeta / this.calCount;
    this.processor.setReference(this.refAlpha, this.refBeta);
  }

  protected updateAutoRecal(atRest: boolean, deltaTime: number): void {
    if (!this.opts.autoRecalibrate) return;
    const o = this.latestOrientation;
    if (!o) return;

    const magnitude = this.lastInput?.magnitude ?? 0;
    // Lerp reference toward current orientation based on auto-recal rate
    const rate = atRest ? 3.0 : (1.0 - Math.min(1, magnitude)) * 1.0;
    const t = 1 - Math.exp(-rate * deltaTime);

    // Circular lerp for alpha
    let deltaAlpha = o.alpha - this.refAlpha;
    if (deltaAlpha > 180) deltaAlpha -= 360;
    if (deltaAlpha < -180) deltaAlpha += 360;
    this.refAlpha += deltaAlpha * t;
    if (this.refAlpha < 0) this.refAlpha += 360;
    if (this.refAlpha >= 360) this.refAlpha -= 360;

    this.refBeta += (o.beta - this.refBeta) * t;
    this.processor.setReference(this.refAlpha, this.refBeta);
  }

  protected computeInput(atRest: boolean, timestamp: number): LookInput {
    const o = this.latestOrientation;
    if (!o) return { yaw: 0, pitch: 0, x: 0, y: 0, magnitude: 0, atRest, timestamp };
    const gravity = this.latestMotion?.gravity ?? { x: 0, y: 0, z: -9.81 };
    return this.processor.process(o.alpha, o.beta, gravity, atRest, timestamp);
  }

  protected resetCalibrator(skipPhase: boolean): void {
    this.calSkip = skipPhase ? 0 : SKIP_FRAMES; // skip or not
    this.calCount = 0;
    this.calSumBeta = 0;
    this.calSinAlpha = 0;
    this.calCosAlpha = 0;
  }
}
