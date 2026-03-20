import type { LookInput, LookOptions, MotionData, Quaternion } from './types';
import { BaseController } from './controller';
import { Calibrator } from './calibrator';
import { AutoRecalibrator } from './auto-recalibrator';
import { LookProcessor } from './look-processor';

const DEFAULTS: Required<LookOptions> = {
  frequency: 60,
  smoothing: 0.1,
  maxAngle: 45,
  deadzone: 2,
  sensitivity: { x: 1, y: 1 },
  sensorFusion: 'game',
  autoRecalibrate: true,
};

export class LookController extends BaseController<LookInput> {
  readonly mode = 'look' as const;
  private calibrator: Calibrator;
  private processor: LookProcessor;
  private quatRef: Quaternion = { x: 0, y: 0, z: 0, w: 1 };
  private opts: Required<LookOptions>;

  constructor(options: LookOptions = {}) {
    const opts = { ...DEFAULTS, ...options };
    super(new AutoRecalibrator(opts.sensorFusion));
    this.opts = opts;
    this.calibrator = new Calibrator('quaternion', true);
    this.processor = new LookProcessor(opts.maxAngle, opts.deadzone, opts.sensitivity);
  }

  protected feedCalibrator(data: MotionData): boolean {
    return this.calibrator.feedQuaternion(data.orientation);
  }

  protected onCalibrated(_data: MotionData): void {
    this.quatRef = { ...(this.calibrator.reference as Quaternion) };
    this.processor.setReference(this.quatRef);
  }

  protected updateAutoRecal(data: MotionData, atRest: boolean, deltaTime: number): void {
    if (!this.opts.autoRecalibrate) return;
    const magnitude = this.lastInput?.magnitude ?? 0;
    this.quatRef = this.autoRecalibrator.updateQuaternion(
      this.quatRef, data.orientation, magnitude, atRest, deltaTime
    );
    this.processor.setReference(this.quatRef);
  }

  protected computeInput(data: MotionData, atRest: boolean): LookInput {
    return this.processor.process(data.orientation, data.smoothGravity, atRest, data.timestamp);
  }

  protected resetCalibrator(skipPhase: boolean): void {
    this.calibrator.reset(skipPhase);
  }
}
