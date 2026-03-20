import type { RotateInput, RotateOptions, MotionData, Vector3, Quaternion } from './types';
import { BaseController } from './controller';
import { Calibrator } from './calibrator';
import { AutoRecalibrator } from './auto-recalibrator';
import { RotateProcessor } from './rotate-processor';

const DEG = 180 / Math.PI;

const DEFAULTS: Required<RotateOptions> = {
  frequency: 60,
  smoothing: 0.1,
  maxAngle: 45,
  deadzone: 2,
  sensitivity: 1,
  axis: 'twist',
  sensorFusion: 'game',
  autoRecalibrate: true,
};

export class RotateController extends BaseController<RotateInput> {
  readonly mode = 'rotate' as const;
  private calibrator: Calibrator;
  private processor: RotateProcessor;
  private gravityRef: Vector3 = { x: 0, y: 0, z: -9.81 };
  private quatRef: Quaternion = { x: 0, y: 0, z: 0, w: 1 };
  private opts: Required<RotateOptions>;

  constructor(options: RotateOptions = {}) {
    const opts = { ...DEFAULTS, ...options };
    super(new AutoRecalibrator(opts.sensorFusion));
    this.opts = opts;
    this.processor = new RotateProcessor(opts.axis, opts.maxAngle, opts.deadzone, opts.sensitivity);
    this.calibrator = new Calibrator(
      this.processor.usesQuaternion ? 'quaternion' : 'vector',
      true
    );
  }

  protected feedCalibrator(data: MotionData): boolean {
    if (this.processor.usesQuaternion) {
      return this.calibrator.feedQuaternion(data.orientation);
    }
    return this.calibrator.feedVector(data.smoothGravity);
  }

  protected onCalibrated(_data: MotionData): void {
    if (this.processor.usesQuaternion) {
      this.quatRef = { ...(this.calibrator.reference as Quaternion) };
      // Extract initial yaw for reference
      const yaw = this.extractYaw(this.quatRef, { x: 0, y: 0, z: 0, w: 1 });
      this.processor.setReferenceQuaternion(yaw);
    } else {
      this.gravityRef = { ...(this.calibrator.reference as Vector3) };
      this.processor.setReferenceGravity(this.gravityRef);
    }
  }

  protected updateAutoRecal(data: MotionData, atRest: boolean, deltaTime: number): void {
    if (!this.opts.autoRecalibrate) return;
    const magnitude = Math.abs(this.lastInput?.value ?? 0);

    if (this.processor.usesQuaternion) {
      this.quatRef = this.autoRecalibrator.updateQuaternion(
        this.quatRef, data.orientation, magnitude, atRest, deltaTime
      );
      const yaw = this.extractYaw(this.quatRef, { x: 0, y: 0, z: 0, w: 1 });
      this.processor.setReferenceQuaternion(yaw);
    } else {
      this.gravityRef = this.autoRecalibrator.updateVector(
        this.gravityRef, data.smoothGravity, magnitude, atRest, deltaTime
      );
      this.processor.setReferenceGravity(this.gravityRef);
    }
  }

  protected computeInput(data: MotionData, atRest: boolean): RotateInput {
    if (this.processor.usesQuaternion) {
      // Extract yaw from current orientation relative to identity
      const yaw = this.extractYaw(data.orientation, { x: 0, y: 0, z: 0, w: 1 });
      return this.processor.processYaw(yaw, atRest, data.timestamp);
    }
    return this.processor.processGravity(data.smoothGravity, atRest, data.timestamp);
  }

  protected resetCalibrator(skipPhase: boolean): void {
    this.calibrator.reset(skipPhase);
  }

  private extractYaw(quat: Quaternion, _ref: Quaternion): number {
    // Yaw from quaternion: atan2(2*(wy - xz), 1 - 2*(y²+z²))
    return Math.atan2(
      2 * (quat.w * quat.y - quat.x * quat.z),
      1 - 2 * (quat.y * quat.y + quat.z * quat.z)
    ) * DEG;
  }
}
