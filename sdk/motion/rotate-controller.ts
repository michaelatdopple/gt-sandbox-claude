import type { RotateInput, RotateOptions, Vector3 } from './types';
import { BaseController, type EventSource } from './controller';
import { Calibrator } from './calibrator';
import { AutoRecalibrator } from './auto-recalibrator';
import { RotateProcessor } from './rotate-processor';

const DEFAULTS: Required<RotateOptions> = {
  frequency: 60,
  maxAngle: 45,
  deadzone: 2,
  sensitivity: 1,
  axis: 'twist',
  sensorFusion: 'game',
  autoRecalibrate: true,
};

// For turn axis: calibration via circular mean of alpha
const SKIP_FRAMES = 15;
const COLLECT_FRAMES = 30;

export class RotateController extends BaseController<RotateInput> {
  readonly mode = 'rotate' as const;
  readonly eventSource: EventSource;
  private calibrator: Calibrator | null;  // used for gravity-based axes
  private processor: RotateProcessor;
  private gravityRef: Vector3 = { x: 0, y: 0, z: -9.81 };
  private opts: Required<RotateOptions>;

  // Turn axis calibration state (Euler-based)
  private calSkip = 0;
  private calCount = 0;
  private calSinAlpha = 0;
  private calCosAlpha = 0;
  private refAlpha = 0;

  constructor(options: RotateOptions = {}) {
    const opts = { ...DEFAULTS, ...options };
    super(new AutoRecalibrator(opts.sensorFusion));
    this.opts = opts;
    this.processor = new RotateProcessor(opts.axis, opts.maxAngle, opts.deadzone, opts.sensitivity);

    if (this.processor.usesQuaternion) {
      // Turn axis: uses orientation Euler alpha directly
      this.calibrator = null;
      this.eventSource = 'orientation';
    } else {
      // Gravity-based axes (twist, lean)
      this.calibrator = new Calibrator('vector', true);
      this.eventSource = 'motion';
    }
  }

  protected feedCalibrator(): boolean {
    if (this.processor.usesQuaternion) {
      // Turn axis: calibrate from alpha
      const o = this.latestOrientation;
      if (!o) return false;

      if (this.calSkip < SKIP_FRAMES) {
        this.calSkip++;
        return false;
      }

      const rad = o.alpha * Math.PI / 180;
      this.calSinAlpha += Math.sin(rad);
      this.calCosAlpha += Math.cos(rad);
      this.calCount++;
      return this.calCount >= COLLECT_FRAMES;
    }

    // Gravity-based axes
    const gravity = this.currentGravity;
    return this.calibrator!.feedVector(gravity);
  }

  protected onCalibrated(): void {
    if (this.processor.usesQuaternion) {
      this.refAlpha = Math.atan2(this.calSinAlpha, this.calCosAlpha) * 180 / Math.PI;
      if (this.refAlpha < 0) this.refAlpha += 360;
      this.processor.setReferenceQuaternion(this.refAlpha);
    } else {
      this.gravityRef = { ...(this.calibrator!.reference as Vector3) };
      this.processor.setReferenceGravity(this.gravityRef);
    }
  }

  protected updateAutoRecal(atRest: boolean, deltaTime: number): void {
    if (!this.opts.autoRecalibrate) return;
    const magnitude = Math.abs(this.lastInput?.value ?? 0);

    if (this.processor.usesQuaternion) {
      // Lerp refAlpha toward current alpha
      const o = this.latestOrientation;
      if (!o) return;
      const rate = atRest ? 3.0 : (1.0 - Math.min(1, magnitude)) * 1.0;
      const t = 1 - Math.exp(-rate * deltaTime);
      let delta = o.alpha - this.refAlpha;
      if (delta > 180) delta -= 360;
      if (delta < -180) delta += 360;
      this.refAlpha += delta * t;
      if (this.refAlpha < 0) this.refAlpha += 360;
      if (this.refAlpha >= 360) this.refAlpha -= 360;
      this.processor.setReferenceQuaternion(this.refAlpha);
    } else {
      this.gravityRef = this.autoRecalibrator.updateVector(
        this.gravityRef, this.currentGravity, magnitude, atRest, deltaTime
      );
      this.processor.setReferenceGravity(this.gravityRef);
    }
  }

  protected computeInput(atRest: boolean, timestamp: number): RotateInput {
    if (this.processor.usesQuaternion) {
      const o = this.latestOrientation;
      const alpha = o?.alpha ?? 0;
      return this.processor.processYaw(alpha, atRest, timestamp);
    }
    return this.processor.processGravity(this.currentGravity, atRest, timestamp);
  }

  protected resetCalibrator(skipPhase: boolean): void {
    if (this.processor.usesQuaternion) {
      this.calSkip = skipPhase ? 0 : SKIP_FRAMES;
      this.calCount = 0;
      this.calSinAlpha = 0;
      this.calCosAlpha = 0;
    } else {
      this.calibrator!.reset(skipPhase);
    }
  }

  private get currentGravity(): Vector3 {
    return this.latestMotion?.gravity ?? { x: 0, y: 0, z: -9.81 };
  }
}
