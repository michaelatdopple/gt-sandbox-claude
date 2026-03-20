import type { TiltInput, TiltOptions, MotionData, Vector3 } from './types';
import { BaseController } from './controller';
import { Calibrator } from './calibrator';
import { AutoRecalibrator } from './auto-recalibrator';
import { TiltProcessor } from './tilt-processor';

const DEFAULTS: Required<TiltOptions> = {
  frequency: 60,
  smoothing: 0.1,
  maxAngle: 25,
  deadzone: 2,
  sensitivity: { x: 1, y: 1 },
  autoRecalibrate: true,
};

export class TiltController extends BaseController<TiltInput> {
  readonly mode = 'tilt' as const;
  private calibrator: Calibrator;
  private processor: TiltProcessor;
  private gravityRef: Vector3 = { x: 0, y: 0, z: -9.81 };
  private opts: Required<TiltOptions>;

  constructor(options: TiltOptions = {}) {
    const opts = { ...DEFAULTS, ...options };
    super(new AutoRecalibrator('game'));
    this.opts = opts;
    this.calibrator = new Calibrator('vector', true);
    this.processor = new TiltProcessor(opts.maxAngle, opts.deadzone, opts.sensitivity);
  }

  protected feedCalibrator(data: MotionData): boolean {
    return this.calibrator.feedVector(data.smoothGravity);
  }

  protected onCalibrated(_data: MotionData): void {
    this.gravityRef = { ...(this.calibrator.reference as Vector3) };
    this.processor.setReference(this.gravityRef);
  }

  protected updateAutoRecal(data: MotionData, atRest: boolean, deltaTime: number): void {
    if (!this.opts.autoRecalibrate) return;
    const magnitude = this.lastInput?.magnitude ?? 0;
    this.gravityRef = this.autoRecalibrator.updateVector(
      this.gravityRef, data.smoothGravity, magnitude, atRest, deltaTime
    );
    this.processor.setReference(this.gravityRef);
  }

  protected computeInput(data: MotionData, atRest: boolean): TiltInput {
    return this.processor.process(data.smoothGravity, atRest, data.timestamp);
  }

  protected resetCalibrator(skipPhase: boolean): void {
    this.calibrator.reset(skipPhase);
  }
}
