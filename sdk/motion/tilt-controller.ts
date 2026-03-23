import type { TiltInput, TiltOptions, Vector3 } from './types';
import { BaseController, type EventSource } from './controller';
import { Calibrator } from './calibrator';
import { AutoRecalibrator } from './auto-recalibrator';
import { TiltProcessor } from './tilt-processor';

const DEFAULTS: Required<TiltOptions> = {
  frequency: 60,
  maxAngle: 25,
  deadzone: 2,
  sensitivity: { x: 1, y: 1 },
  autoRecalibrate: true,
};

export class TiltController extends BaseController<TiltInput> {
  readonly mode = 'tilt' as const;
  readonly eventSource: EventSource = 'motion';
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

  /** Clean gravity from TYPE_GRAVITY HAL sensor */
  private get currentGravity(): Vector3 {
    return this.latestMotion?.gravity ?? { x: 0, y: 0, z: -9.81 };
  }

  protected feedCalibrator(): boolean {
    return this.calibrator.feedVector(this.currentGravity);
  }

  protected onCalibrated(): void {
    this.gravityRef = { ...(this.calibrator.reference as Vector3) };
    this.processor.setReference(this.gravityRef);
  }

  protected updateAutoRecal(atRest: boolean, deltaTime: number): void {
    if (!this.opts.autoRecalibrate) return;
    const magnitude = this.lastInput?.magnitude ?? 0;
    this.gravityRef = this.autoRecalibrator.updateVector(
      this.gravityRef, this.currentGravity, magnitude, atRest, deltaTime
    );
    this.processor.setReference(this.gravityRef);
  }

  protected computeInput(atRest: boolean, timestamp: number): TiltInput {
    return this.processor.process(this.currentGravity, atRest, timestamp);
  }

  protected resetCalibrator(skipPhase: boolean): void {
    this.calibrator.reset(skipPhase);
  }
}
