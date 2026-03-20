// sdk/motion/types.ts

// === Sensor fusion ===
export type SensorFusion = 'game' | 'full';

// === Rotate axis ===
export type RotateAxis = 'twist' | 'turn' | 'lean' | 'roll' | 'yaw' | 'pitch';

// === Input types ===
export interface TiltInput {
  x: number;
  y: number;
  magnitude: number;
  atRest: boolean;
  timestamp: number;
}

export interface LookInput {
  yaw: number;
  pitch: number;
  x: number;
  y: number;
  magnitude: number;
  atRest: boolean;
  timestamp: number;
}

export interface RotateInput {
  angle: number;
  value: number;
  atRest: boolean;
  timestamp: number;
}

// === Options ===
interface BaseOptions {
  frequency?: number;
  smoothing?: number;
  maxAngle?: number;
  deadzone?: number;
  autoRecalibrate?: boolean;
}

export interface TiltOptions extends BaseOptions {
  sensitivity?: { x: number; y: number };
}

export interface LookOptions extends BaseOptions {
  sensitivity?: { x: number; y: number };
  sensorFusion?: SensorFusion;
}

export interface RotateOptions extends BaseOptions {
  axis?: RotateAxis;
  sensitivity?: number;
  sensorFusion?: SensorFusion;
}

// === Controller interface ===
export type ModeType = 'tilt' | 'look' | 'rotate';
export type ModeInput = TiltInput | LookInput | RotateInput;

export interface MotionController<T extends ModeInput = ModeInput> {
  readonly mode: ModeType;
  readonly calibrated: boolean;
  readonly active: boolean;
  readonly lastInput: T | null;
  recalibrate(): void;
  pauseRecalibration(): void;
  resumeRecalibration(): void;
  on(event: 'input', handler: (input: T) => void): this;
  on(event: 'calibrated', handler: () => void): this;
  on(event: 'rest', handler: (atRest: boolean) => void): this;
  off(event: string, handler: Function): this;
  stop(): void;
}

// === Internal: raw sensor data shape (from existing MotionData) ===
export interface Vector3 { x: number; y: number; z: number; }
export interface Quaternion { x: number; y: number; z: number; w: number; }

export interface MotionData {
  gravity: Vector3;
  smoothGravity: Vector3;
  orientation: Quaternion;
  delta: Vector3;
  timestamp: number;
  sequenceNumber: number;
}
