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
    /** Smoothing alpha for gravity (0.0 = max smooth, 1.0 = no smoothing, default 0.1) */
    smoothing?: number;
}

/** Motion sensor data payload */
interface MotionData {
    gravity: Vector3;
    smoothGravity: Vector3;
    orientation: Quaternion;
    delta: Vector3;
    timestamp: number;
    sequenceNumber: number;
}

/** Motion streaming status */
interface MotionStatus {
    active: boolean;
    subscriptions: number;
    frequencyHz: number;
    smoothingAlpha: number;
    paused: boolean;
}

/** Active motion data subscription */
interface MotionSubscription extends EventTarget {
    readonly id: string;
    readonly active: boolean;
    on(event: 'data', handler: (data: MotionData) => void): this;
    off(event: 'data', handler: (data: MotionData) => void): this;
    stop(): void;
}

/** Motion API interface */
interface MotionAPI {
    isSupported(): boolean;
    start(options?: MotionOptions): Promise<MotionSubscription>;
    setFrequency(hz: number): MotionAPI;
    setSmoothingAlpha(alpha: number): MotionAPI;
    getStatus(): MotionStatus;
    getLatest(): MotionData | null;
    stopAll(): void;
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
