// Hand-written DX types for game developers — appended to tsc output
// Bridge contract: see bridge-contract.yaml for the API surface map
/**
 * Loop SDK TypeScript Definitions
 *
 * Provides type definitions for the Loop SDK - a professional WebView bridge
 * for accessing device motion, button input, haptic feedback, object matching,
 * asset packs, and BLE multiplayer.
 *
 * @version 1.4.0
 * @license MIT
 */

// ==================== Core Types ====================

/** 3D Vector with x, y, z components */
interface Vector3 {
    x: number;
    y: number;
    z: number;
}

/** Quaternion for 3D rotation */
interface Quaternion {
    x: number;
    y: number;
    z: number;
    w: number;
}

// ==================== Motion Types ====================

/** Configuration options for motion streaming */
interface MotionOptions {
    /** Update frequency in Hz (1-240, default 60) */
    frequency?: number;
}

/** W3C DeviceOrientationEvent-aligned data from loop:orientation */
interface OrientationData {
    /** Rotation around Z axis (0..360) */
    alpha: number;
    /** Rotation around X axis (-180..180) */
    beta: number;
    /** Rotation around Y axis (-90..90) */
    gamma: number;
    /** true if orientation is relative to Earth's coordinate frame */
    absolute: boolean;
}

/** W3C DeviceMotionEvent-aligned data from loop:motion + gravity enhancement */
interface MotionData {
    /** Raw accelerometer including gravity (TYPE_ACCELEROMETER) */
    accelerationIncludingGravity: Vector3;
    /** Linear acceleration without gravity (TYPE_LINEAR_ACCELERATION) */
    acceleration: Vector3;
    /** Angular velocity in deg/s (TYPE_GYROSCOPE) */
    rotationRate: { alpha: number; beta: number; gamma: number };
    /** Milliseconds between samples */
    interval: number;
    /** Clean gravity vector from HAL (TYPE_GRAVITY) — enhancement over W3C */
    gravity: Vector3;
}

/** Motion streaming status */
interface MotionStatus {
    active: boolean;
    subscriptions: number;
    frequencyHz: number;
    paused: boolean;
}

/** Active motion data subscription */
interface MotionSubscription extends EventTarget {
    readonly id: string;
    readonly active: boolean;
    /** Orientation event data (loop:orientation) */
    on(event: 'orientation', handler: (data: OrientationData) => void): this;
    /** Motion event data (loop:motion) */
    on(event: 'motion', handler: (data: MotionData) => void): this;
    /** Raw data (backwards compat alias for 'motion') */
    on(event: 'data', handler: (data: MotionData) => void): this;
    off(event: string, handler: Function): this;
    stop(): void;
}

// === Motion Control Modes ===

type SensorFusion = 'game' | 'full';
type RotateAxis = 'twist' | 'turn' | 'lean' | 'roll' | 'yaw' | 'pitch';

interface TiltInput {
  x: number;
  y: number;
  magnitude: number;
  atRest: boolean;
  timestamp: number;
}

interface LookInput {
  yaw: number;
  pitch: number;
  x: number;
  y: number;
  magnitude: number;
  atRest: boolean;
  timestamp: number;
}

interface RotateInput {
  angle: number;
  value: number;
  atRest: boolean;
  timestamp: number;
}

interface TiltOptions {
  frequency?: number;
  maxAngle?: number;
  deadzone?: number;
  sensitivity?: { x: number; y: number };
  autoRecalibrate?: boolean;
}

interface LookOptions {
  frequency?: number;
  maxAngle?: number;
  deadzone?: number;
  sensitivity?: { x: number; y: number };
  /** @default 'game' — TYPE_GAME_ROTATION_VECTOR (6-axis, no magnetometer) */
  sensorFusion?: SensorFusion;
  autoRecalibrate?: boolean;
}

interface PanOptions {
  frequency?: number;
  maxAngle?: number;
  deadzone?: number;
  sensitivity?: { x: number; y: number };
  /** @default 'game' */
  sensorFusion?: SensorFusion;
  /** Edge absorption strength (default 0.3) */
  absorbRate?: number;
  autoRecalibrate?: boolean;
}

interface RotateOptions {
  frequency?: number;
  maxAngle?: number;
  deadzone?: number;
  sensitivity?: number;
  axis?: RotateAxis;
  /** Only relevant for 'turn'/'yaw' axis. @default 'game' */
  sensorFusion?: SensorFusion;
  autoRecalibrate?: boolean;
}

interface PanInput {
  yaw: number;
  pitch: number;
  x: number;       // -1..1 normalized
  y: number;       // -1..1 normalized
  magnitude: number;
  atRest: boolean;
  timestamp: number;
}

interface MotionController<T = TiltInput | LookInput | PanInput | RotateInput> {
  readonly mode: 'tilt' | 'look' | 'pan' | 'rotate';
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

interface TiltController extends MotionController<TiltInput> {
  readonly mode: 'tilt';
}

interface LookController extends MotionController<LookInput> {
  readonly mode: 'look';
}

interface PanController extends MotionController<PanInput> {
  readonly mode: 'pan';
}

interface RotateController extends MotionController<RotateInput> {
  readonly mode: 'rotate';
}

/** Motion API interface */
interface MotionAPI {
    isSupported(): boolean;
    start(options?: MotionOptions): Promise<MotionSubscription>;
    setFrequency(hz: number): MotionAPI;
    /** Switch sensor fusion: 'game' (6-axis) or 'full' (9-axis with magnetometer) */
    setSensorFusion(type: 'game' | 'full'): void;
    getStatus(): MotionStatus;
    getLatest(): OrientationData | null;
    stopAll(): void;
    /** Start tilt mode — gravity-based 2D joystick using loop:motion events. */
    tilt(options?: TiltOptions): Promise<TiltController>;
    /** Start look mode — quaternion-based panoramic view using loop:orientation events. */
    look(options?: LookOptions): Promise<LookController>;
    /** Start pan mode — scrolling with edge absorption using loop:orientation events. */
    pan(options?: PanOptions): Promise<PanController>;
    /** Start rotate mode — single-axis rotation. */
    rotate(options?: RotateOptions): Promise<RotateController>;
    /** Start raw motion data subscription (alias for start). */
    raw(options?: MotionOptions): Promise<MotionSubscription>;
}

// ==================== Button Types ====================

type ButtonId = 'A' | 'B' | 'C';
type ButtonState = 'down' | 'up';
type ButtonEventType = 'press' | 'release' | 'A' | 'B' | 'C';

interface ButtonEvent {
    button: ButtonId;
    state: ButtonState;
    timestamp: number;
    sequenceNumber: number;
}

type ButtonHandler = (event: ButtonEvent) => void;

interface ButtonsAPI {
    on(event: ButtonEventType, handler: ButtonHandler): void;
    off(event: ButtonEventType, handler: ButtonHandler): void;
}

// ==================== Haptics Types ====================

interface HapticsStatus {
    available: boolean;
    hasAmplitudeSupport: boolean;
}

interface HapticKeyframe {
    time: number;
    value: number;
}

interface HapticCurve {
    keys: HapticKeyframe[];
    strength?: number;
}

interface HapticsResult {
    success: boolean;
    error?: string;
    durationMs?: number;
}

interface HapticsAPI {
    isSupported(): boolean;
    getStatus(): HapticsStatus;
    pulse(intensity?: number): HapticsResult;
    playCurve(curve: HapticCurve): HapticsResult;
    stop(): HapticsResult;
}

// ==================== Match Types ====================

interface MatchStatus {
    ready: boolean;
    status: string;
}

interface MatchResult {
    item: unknown;
    distance: number;
}

interface MatchAPI {
    getStatus(): MatchStatus;
    isBusy(): boolean;
    captureFrame(videoElement?: HTMLVideoElement): Promise<MatchResult>;
}

// ==================== Pack Types ====================

interface PackStatus {
    ready: boolean;
    state: number;
}

interface PackAPI {
    getStatus(): PackStatus;
    getAsset(packName: string, path: string): Promise<ArrayBuffer>;
    getModelUrl(itemId: string): Promise<string>;
    assetExists(packName: string, path: string): boolean;
    clearCache(): void;
    getCacheSize(): number;
}

// ==================== BLE Types ====================

interface BLEPlayerInfo {
    id: string;
    name: string;
}

interface BLEMessageEvent {
    data: unknown;
    from: BLEPlayerInfo;
}

interface BLEPlayerEvent {
    player: BLEPlayerInfo;
}

interface BLEConnectedEvent {
    host: BLEPlayerInfo;
}

interface BLEDisconnectedEvent {
    reason: string;
}

interface BLERoleResolvedEvent {
    role: string;
    token: string;
}

type BLEConnectionState = 'idle' | 'negotiating' | 'hosting' | 'scanning' | 'connecting' | 'connected' | 'reconnecting';

type BLEEventType = 'message' | 'playerJoined' | 'playerLeft' | 'connected' | 'disconnected' | 'reconnecting' | 'roleResolved';

interface BLEPlayGameOptions {
    /** Timeout in milliseconds for role resolution (default: 15000) */
    timeoutMs?: number;
}

interface BLESendOptions {
    to?: string;
}

interface BLEAPI {
    createGame(gameId: string, token?: string): string;
    joinGame(hostToken: string, playerName?: string): Promise<BLEPlayerInfo>;
    playGame(seed: string, playerName?: string, options?: BLEPlayGameOptions): Promise<{ role: string; token: string }>;
    getState(): BLEConnectionState;
    endGame(): void;
    leaveGame(): void;
    send(data: unknown, options?: BLESendOptions): void;
    on(event: 'message', handler: (detail: BLEMessageEvent) => void): void;
    on(event: 'playerJoined' | 'playerLeft', handler: (detail: BLEPlayerEvent) => void): void;
    on(event: 'connected', handler: (detail: BLEConnectedEvent) => void): void;
    on(event: 'disconnected', handler: (detail: BLEDisconnectedEvent) => void): void;
    on(event: 'roleResolved', handler: (detail: BLERoleResolvedEvent) => void): void;
    on(event: 'reconnecting', handler: () => void): void;
    off(event: BLEEventType, handler: Function): void;
}

// ==================== Storage Types ====================

interface StorageResult {
    success: boolean;
    error?: string;
}

interface StorageUsage {
    /** Bytes currently used */
    used: number;
    /** Maximum bytes allowed (1048576 = 1MB) */
    quota: number;
}

interface StorageAPI {
    setItem(key: string, value: string): StorageResult;
    getItem(key: string): string | null;
    removeItem(key: string): StorageResult;
    clear(): StorageResult;
    keys(): string[];
    getUsage(): StorageUsage;
    exists(key: string): boolean;
}

// ==================== System Types ====================

interface SystemPauseEvent {
    reason: 'sleep' | 'settings';
}

interface SystemResumeEvent {
    reason: 'sleep' | 'settings';
    pausedMs: number;
}

type SystemEventType = 'pause' | 'resume';

interface SystemAPI {
    isFreeRotateEnabled(): boolean;
    setFreeRotate(enabled: boolean): { success: boolean };
    on(event: 'pause', handler: (detail: SystemPauseEvent) => void): void;
    on(event: 'resume', handler: (detail: SystemResumeEvent) => void): void;
    off(event: SystemEventType, handler: Function): void;
}

// ==================== Main SDK Interface ====================

interface LoopSDK {
    isAvailable(): boolean;
    readonly version: string;
    readonly motion: MotionAPI;
    readonly buttons: ButtonsAPI;
    readonly haptics: HapticsAPI;
    readonly match: MatchAPI;
    readonly pack: PackAPI;
    readonly ble: BLEAPI;
    readonly storage: StorageAPI;
    readonly system: SystemAPI;
}

// ==================== Global Declarations ====================

declare global {
    interface Window {
        Loop: LoopSDK;
    }
    const Loop: LoopSDK;
}

// ==================== Custom Events ====================

interface LoopOrientationEvent extends CustomEvent<OrientationData> {
    type: 'loop:orientation';
}

interface LoopMotionEvent extends CustomEvent<MotionData> {
    type: 'loop:motion';
}

interface LoopButtonEvent extends CustomEvent<ButtonEvent> {
    type: 'loop:button';
}

interface LoopReadyEvent extends CustomEvent<{ version: string }> {
    type: 'loop:ready';
}

interface LoopMatchEvent extends CustomEvent<{ requestId: number; success: boolean; item?: unknown; distance?: number; error?: string }> {
    type: 'loop:match';
}

interface LoopBLEMessageEvent extends CustomEvent<BLEMessageEvent> {
    type: 'loop:ble:message';
}

interface LoopBLEPlayerJoinedEvent extends CustomEvent<BLEPlayerEvent> {
    type: 'loop:ble:playerJoined';
}

interface LoopBLEPlayerLeftEvent extends CustomEvent<BLEPlayerEvent> {
    type: 'loop:ble:playerLeft';
}

interface LoopBLEConnectedEvent extends CustomEvent<BLEConnectedEvent> {
    type: 'loop:ble:connected';
}

interface LoopBLEDisconnectedEvent extends CustomEvent<BLEDisconnectedEvent> {
    type: 'loop:ble:disconnected';
}

interface LoopBLEReconnectingEvent extends CustomEvent<{}> {
    type: 'loop:ble:reconnecting';
}

interface LoopBLERoleResolvedEvent extends CustomEvent<BLERoleResolvedEvent> {
    type: 'loop:ble:roleResolved';
}

interface LoopPauseEvent extends CustomEvent<SystemPauseEvent> {
    type: 'loop:pause';
}

interface LoopResumeEvent extends CustomEvent<SystemResumeEvent> {
    type: 'loop:resume';
}

declare global {
    interface WindowEventMap {
        'loop:orientation': LoopOrientationEvent;
        'loop:motion': LoopMotionEvent;
        'loop:button': LoopButtonEvent;
        'loop:ready': LoopReadyEvent;
        'loop:match': LoopMatchEvent;
        'loop:ble:message': LoopBLEMessageEvent;
        'loop:ble:playerJoined': LoopBLEPlayerJoinedEvent;
        'loop:ble:playerLeft': LoopBLEPlayerLeftEvent;
        'loop:ble:connected': LoopBLEConnectedEvent;
        'loop:ble:disconnected': LoopBLEDisconnectedEvent;
        'loop:ble:reconnecting': LoopBLEReconnectingEvent;
        'loop:ble:roleResolved': LoopBLERoleResolvedEvent;
        'loop:pause': LoopPauseEvent;
        'loop:resume': LoopResumeEvent;
    }
}

export {};
