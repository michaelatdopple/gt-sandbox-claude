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

export interface PanInput {
  yaw: number;
  pitch: number;
  x: number;       // -1..1 normalized
  y: number;       // -1..1 normalized
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

export interface PanOptions extends BaseOptions {
  sensitivity?: { x: number; y: number };
  sensorFusion?: SensorFusion;
  absorbRate?: number;  // edge absorption strength, default 0.3
}

export interface RotateOptions extends BaseOptions {
  axis?: RotateAxis;
  sensitivity?: number;
  sensorFusion?: SensorFusion;
}

// === Controller interface ===
export type ModeType = 'tilt' | 'look' | 'pan' | 'rotate';
export type ModeInput = TiltInput | LookInput | PanInput | RotateInput;

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

// === W3C-aligned sensor data shapes ===
export interface Vector3 { x: number; y: number; z: number; }
export interface Quaternion { x: number; y: number; z: number; w: number; }

/**
 * W3C DeviceOrientationEvent-shaped data from loop:orientation.
 */
export interface OrientationData {
  alpha: number;   // Z-axis rotation (0..360)
  beta: number;    // X-axis rotation (-180..180)
  gamma: number;   // Y-axis rotation (-90..90)
  absolute: boolean;
}

/**
 * W3C DeviceMotionEvent-shaped data from loop:motion + gravity enhancement.
 */
export interface MotionData {
  accelerationIncludingGravity: Vector3;  // TYPE_ACCELEROMETER
  acceleration: Vector3;                   // TYPE_LINEAR_ACCELERATION
  rotationRate: { alpha: number; beta: number; gamma: number };  // TYPE_GYROSCOPE, deg/s
  interval: number;                        // ms between samples
  gravity: Vector3;                        // TYPE_GRAVITY (enhancement)
}
