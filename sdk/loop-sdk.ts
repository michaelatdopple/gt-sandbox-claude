// Bridge contract: see bridge-contract.yaml for the API surface map
import type { Loop$motion, Loop$buttons, Loop$haptics, Loop$match, Loop$pack, Loop$ble, Loop$storage, Loop$system } from './generated/bridge-types';
import { TiltController } from './motion/tilt-controller';
import { LookController } from './motion/look-controller';
import { PanController } from './motion/pan-controller';
import { RotateController } from './motion/rotate-controller';
import type { TiltOptions, LookOptions, PanOptions, RotateOptions, MotionController } from './motion/types';
/**
 * Loop SDK - Professional WebView Bridge for Game Developers
 *
 * Provides an intuitive, TypeScript-friendly API for accessing
 * device motion, button input, and haptic feedback.
 *
 * @version 1.4.0
 * @license MIT
 */

// ==================== Native Bridge Accessors ====================
// Typed from bridge-contract.yaml → sdk/generated/bridge-types.ts

const native = {
    get motion(): Loop$motion | undefined { return window.Loop$motion; },
    get buttons(): Loop$buttons | undefined { return window.Loop$buttons; },
    get haptics(): Loop$haptics | undefined { return window.Loop$haptics; },
    get match(): Loop$match | undefined { return window.Loop$match; },
    get pack(): Loop$pack | undefined { return window.Loop$pack; },
    get ble(): Loop$ble | undefined { return window.Loop$ble; },
    get storage(): Loop$storage | undefined { return window.Loop$storage; },
    get system(): Loop$system | undefined { return window['Loop$system']; },
};

// ==================== Internal Types ====================

interface MotionStartOptions {
    frequency?: number;
}

interface MotionStatusResult {
    active: boolean;
    subscriptions: number;
    frequencyHz: number;
    paused: boolean;
}

interface SensorAvailability {
    accelerometer: boolean;
    gyroscope: boolean;
    rotationVector: boolean;
}

interface ButtonEventPayload {
    button: string;
    state: string;
    timestamp: number;
    sequenceNumber: number;
}

interface HapticsStatusResult {
    available: boolean;
    hasAmplitudeSupport: boolean;
}

interface HapticsResult {
    success: boolean;
    error?: string;
    durationMs?: number;
}

interface HapticCurve {
    keys: Array<{ time: number; value: number }>;
    strength?: number;
}

interface MatchStatusResult {
    ready: boolean;
    status: string;
}

interface MatchEventDetail {
    requestId: number;
    success: boolean;
    item?: unknown;
    distance?: number;
    error?: string;
}

interface PackStatusResult {
    ready: boolean;
    state: number;
}

interface PackAssetResponse {
    success: boolean;
    data?: string;
    error?: string;
}

interface BLECreateResponse {
    success: boolean;
    token?: string;
    error?: string;
}

interface BLEJoinResponse {
    success: boolean;
    error?: string;
}

interface BLESendResponse {
    success: boolean;
    error?: string;
}

interface BLEPlayResponse {
    success: boolean;
    token?: string;
    error?: string;
}

// ==================== MotionSubscription Class ====================

/**
 * Represents an active motion data subscription.
 * Extends EventTarget for standard event handling.
 */
class MotionSubscription extends EventTarget {
    private _id: string;
    private _handlers: Map<Function, EventListener>;
    private _active: boolean;

    constructor(subscriptionId: string) {
        super();
        this._id = subscriptionId;
        this._handlers = new Map();
        this._active = true;
    }

    /**
     * Register a handler for motion data events.
     * @param event - Event type ('data')
     * @param handler - Callback function
     * @returns this for chaining
     */
    on(event: string, handler: (data: unknown) => void): this {
        if (!this._active) return this;
        const wrapper: EventListener = (e) => handler((e as CustomEvent).detail);
        this._handlers.set(handler, wrapper);
        this.addEventListener(event, wrapper);
        return this;
    }

    /**
     * Remove a previously registered handler.
     * @param event - Event type ('data')
     * @param handler - The handler to remove
     * @returns this for chaining
     */
    off(event: string, handler: Function): this {
        const wrapper = this._handlers.get(handler);
        if (wrapper) {
            this.removeEventListener(event, wrapper);
            this._handlers.delete(handler);
        }
        return this;
    }

    /**
     * Stop this subscription and clean up resources.
     */
    stop(): void {
        if (!this._active) return;
        this._active = false;
        if (native.motion) {
            native.motion.unsubscribe(this._id);
        }
        this._handlers.clear();
        Loop.motion._removeSubscription(this._id);
    }

    get id(): string {
        return this._id;
    }

    get active(): boolean {
        return this._active;
    }
}

// ==================== Motion API ====================

const MotionAPI = {
    _subscriptions: new Map<string, MotionSubscription>(),
    _orientationListener: null as ((e: Event) => void) | null,
    _motionListener: null as ((e: Event) => void) | null,
    _activeController: null as MotionController | null,

    /**
     * Check if motion sensors are supported on this device.
     */
    isSupported(): boolean {
        if (!native.motion) return false;
        try {
            const avail = JSON.parse(native.motion.getSensorAvailability()) as SensorAvailability;
            return avail.accelerometer || avail.gyroscope || avail.rotationVector;
        } catch (e) {
            return false;
        }
    },

    /**
     * Start receiving raw motion data (both orientation and motion events).
     */
    async start(options: MotionStartOptions = {}): Promise<MotionSubscription> {
        if (!native.motion) {
            throw new Error('Loop motion API not available');
        }

        const subId = native.motion.subscribe();

        if (options.frequency !== undefined) {
            native.motion.setFrequency(options.frequency);
        }

        const subscription = new MotionSubscription(subId);
        this._subscriptions.set(subId, subscription);
        this._ensureGlobalListeners();

        return subscription;
    },

    /**
     * Set the motion data frequency.
     */
    setFrequency(hz: number): any {
        if (native.motion) {
            native.motion.setFrequency(hz);
        }
        return this;
    },

    /**
     * Switch sensor fusion type.
     */
    setSensorFusion(type: 'game' | 'full'): void {
        if (native.motion) {
            native.motion.setSensorFusion(type);
        }
    },

    /**
     * Get current motion streaming status.
     */
    getStatus(): MotionStatusResult {
        if (!native.motion) {
            return { active: false, subscriptions: 0, frequencyHz: 0, paused: false };
        }
        try {
            return JSON.parse(native.motion.getStatus()) as MotionStatusResult;
        } catch (e) {
            return { active: false, subscriptions: 0, frequencyHz: 0, paused: false };
        }
    },

    /**
     * Get the latest orientation data without subscription.
     */
    getLatest(): unknown | null {
        if (!native.motion) return null;
        try {
            const data = native.motion.getLatest();
            return data ? JSON.parse(data) : null;
        } catch (e) {
            return null;
        }
    },

    /**
     * Start tilt mode — gravity-based 2D joystick.
     * Uses loop:motion events (clean TYPE_GRAVITY from HAL).
     */
    async tilt(options: TiltOptions = {}): Promise<TiltController> {
        if (!this.isSupported()) {
            throw new Error('Motion sensors not available on this device');
        }
        this._activeController?.stop();
        const freq = Math.max(1, Math.min(240, options.frequency ?? 60));
        const ctrl = new TiltController({ ...options, frequency: freq });
        this._activeController = ctrl as unknown as MotionController;
        const sub = await this.start({ frequency: freq });
        this._wireController(ctrl, sub);
        return ctrl;
    },

    /**
     * Start look mode — quaternion-based panoramic view.
     * Uses loop:orientation events (Euler → quaternion in SDK).
     */
    async look(options: LookOptions = {}): Promise<LookController> {
        if (!this.isSupported()) {
            throw new Error('Motion sensors not available on this device');
        }
        this._activeController?.stop();
        const freq = Math.max(1, Math.min(240, options.frequency ?? 60));
        if (options.sensorFusion && options.sensorFusion !== 'game' && native.motion) {
            native.motion.setSensorFusion(options.sensorFusion);
        }
        const ctrl = new LookController({ ...options, frequency: freq });
        this._activeController = ctrl as unknown as MotionController;
        const sub = await this.start({ frequency: freq });
        this._wireController(ctrl, sub, options.sensorFusion);
        return ctrl;
    },

    /**
     * Start pan mode — quaternion-based scrolling with edge absorption.
     * Uses loop:orientation events. No auto-recalibration by default.
     */
    async pan(options: PanOptions = {}): Promise<PanController> {
        if (!this.isSupported()) {
            throw new Error('Motion sensors not available on this device');
        }
        this._activeController?.stop();
        const freq = Math.max(1, Math.min(240, options.frequency ?? 60));
        if (options.sensorFusion && options.sensorFusion !== 'game' && native.motion) {
            native.motion.setSensorFusion(options.sensorFusion);
        }
        const ctrl = new PanController({ ...options, frequency: freq });
        this._activeController = ctrl as unknown as MotionController;
        const sub = await this.start({ frequency: freq });
        this._wireController(ctrl, sub, options.sensorFusion);
        return ctrl;
    },

    /**
     * Start rotate mode — single-axis rotation.
     * Uses loop:motion (gravity axes) or loop:orientation (turn axis).
     */
    async rotate(options: RotateOptions = {}): Promise<RotateController> {
        if (!this.isSupported()) {
            throw new Error('Motion sensors not available on this device');
        }
        this._activeController?.stop();
        const freq = Math.max(1, Math.min(240, options.frequency ?? 60));
        if (options.sensorFusion && options.sensorFusion !== 'game' && native.motion) {
            native.motion.setSensorFusion(options.sensorFusion);
        }
        const ctrl = new RotateController({ ...options, frequency: freq });
        this._activeController = ctrl as unknown as MotionController;
        const sub = await this.start({ frequency: freq });
        this._wireController(ctrl, sub, options.sensorFusion);
        return ctrl;
    },

    /**
     * Start raw motion data subscription (alias for start).
     */
    async raw(options: MotionStartOptions = {}): Promise<MotionSubscription> {
        return this.start(options);
    },

    /**
     * Stop all active subscriptions.
     */
    stopAll(): void {
        this._activeController?.stop();
        this._activeController = null;
        for (const sub of this._subscriptions.values()) {
            sub.stop();
        }
        this._subscriptions.clear();
    },

    /**
     * Wire a controller to receive both orientation and motion events,
     * and set up cleanup on stop.
     */
    _wireController(
        ctrl: { handleOrientation: Function; handleMotion: Function; stop: Function; eventSource: string },
        sub: MotionSubscription,
        sensorFusion?: string
    ): void {
        sub.on('orientation', (data: any) => ctrl.handleOrientation(data));
        sub.on('motion', (data: any) => ctrl.handleMotion(data));
        const origStop = ctrl.stop.bind(ctrl);
        const self = this;
        ctrl.stop = () => {
            origStop();
            sub.stop();
            if (sensorFusion && sensorFusion !== 'game' && native.motion) {
                native.motion.setSensorFusion('game');
            }
            if (self._activeController === (ctrl as unknown as MotionController)) {
                self._activeController = null;
            }
        };
    },

    _removeSubscription(id: string): void {
        this._subscriptions.delete(id);
    },

    _ensureGlobalListeners(): void {
        if (!this._orientationListener) {
            this._orientationListener = (e: Event) => {
                const data = (e as CustomEvent).detail;
                for (const sub of this._subscriptions.values()) {
                    if (sub.active) {
                        sub.dispatchEvent(new CustomEvent('orientation', { detail: data }));
                    }
                }
            };
            window.addEventListener('loop:orientation', this._orientationListener);
        }

        if (!this._motionListener) {
            this._motionListener = (e: Event) => {
                const data = (e as CustomEvent).detail;
                for (const sub of this._subscriptions.values()) {
                    if (sub.active) {
                        sub.dispatchEvent(new CustomEvent('motion', { detail: data }));
                        // Also fire 'data' for backwards compatibility with raw subscriptions
                        sub.dispatchEvent(new CustomEvent('data', { detail: data }));
                    }
                }
            };
            window.addEventListener('loop:motion', this._motionListener);
        }
    }
};

// ==================== Buttons API ====================

const ButtonsAPI = {
    _handlers: {
        press: [] as Array<(detail: ButtonEventPayload) => void>,
        release: [] as Array<(detail: ButtonEventPayload) => void>,
        A: [] as Array<(detail: ButtonEventPayload) => void>,
        B: [] as Array<(detail: ButtonEventPayload) => void>,
        C: [] as Array<(detail: ButtonEventPayload) => void>
    } as Record<string, Array<(detail: ButtonEventPayload) => void>>,
    _globalListener: null as ((e: Event) => void) | null,

    /**
     * Register a handler for button events.
     */
    on(event: string, handler: (detail: ButtonEventPayload) => void): void {
        if (this._handlers[event]) {
            this._handlers[event].push(handler);
            this._ensureGlobalListener();
        }
    },

    /**
     * Remove a button event handler.
     */
    off(event: string, handler: (detail: ButtonEventPayload) => void): void {
        if (this._handlers[event]) {
            const idx = this._handlers[event].indexOf(handler);
            if (idx !== -1) {
                this._handlers[event].splice(idx, 1);
            }
        }
    },

    _ensureGlobalListener(): void {
        if (this._globalListener) return;

        this._globalListener = (e: Event) => {
            const detail = (e as CustomEvent).detail as ButtonEventPayload;
            const button = detail.button;
            const state = detail.state;

            // Fire button-specific handlers
            if (this._handlers[button]) {
                for (const h of this._handlers[button]) {
                    h(detail);
                }
            }

            // Fire press/release handlers
            const stateEvent = state === 'down' ? 'press' : 'release';
            if (this._handlers[stateEvent]) {
                for (const h of this._handlers[stateEvent]) {
                    h(detail);
                }
            }
        };

        window.addEventListener('loop:button', this._globalListener);
    }
};

// ==================== Match API ====================

/**
 * Match API - Object identification via ItemMatcher service.
 * Results are delivered via 'loop:match' CustomEvent.
 */
const MatchAPI = {
    _pendingRequests: new Map<number, { resolve: (value: any) => void; reject: (reason: Error) => void; timeout?: ReturnType<typeof setTimeout> }>(),
    _globalListener: null as ((e: Event) => void) | null,

    /**
     * Check if match service is ready.
     */
    getStatus(): MatchStatusResult {
        if (!native.match) {
            return { ready: false, status: 'unavailable' };
        }
        try {
            return JSON.parse(native.match.getStatus()) as MatchStatusResult;
        } catch (e) {
            return { ready: false, status: 'error' };
        }
    },

    /**
     * Check if a match operation is currently in progress.
     */
    isBusy(): boolean {
        if (!native.match || !native.match.isBusy) {
            return false;
        }
        return native.match.isBusy();
    },

    /**
     * Capture frame from WebView video element and send to native for matching.
     */
    async captureFrame(videoElement: HTMLVideoElement | null = null): Promise<{ item: unknown; distance: number }> {
        if (!native.match) {
            throw new Error('Match API not available');
        }

        // Get video element from cameraManager if not provided
        const video = videoElement || (window as any).lensApp?.cameraManager?.videoElement as HTMLVideoElement | undefined;
        if (!video || video.readyState < 2) { // HAVE_CURRENT_DATA
            throw new Error('Camera not ready');
        }

        this._ensureGlobalListener();

        // Create off-screen canvas for frame capture
        const canvas = document.createElement('canvas');
        canvas.width = 800;
        canvas.height = 800;
        const ctx = canvas.getContext('2d')!;

        // Draw center-cropped frame from video
        const size = Math.min(video.videoWidth, video.videoHeight);
        const sx = (video.videoWidth - size) / 2;
        const sy = (video.videoHeight - size) / 2;
        ctx.drawImage(video, sx, sy, size, size, 0, 0, 800, 800);

        // Convert to base64 JPEG (0.85 quality balances size vs quality)
        const dataUrl = canvas.toDataURL('image/jpeg', 0.85);
        const base64 = dataUrl.split(',')[1];

        // Generate request ID client-side for tracking
        const requestId = this._nextRequestId++;

        return new Promise((resolve, reject) => {
            // Store pending request
            this._pendingRequests.set(requestId, { resolve, reject });

            // Timeout after 8 seconds (reduced from 10s based on actual inference times)
            const timeout = setTimeout(() => {
                if (this._pendingRequests.has(requestId)) {
                    this._pendingRequests.delete(requestId);
                    reject(new Error('Match timeout - inference took too long'));
                }
            }, 8000);

            this._pendingRequests.get(requestId)!.timeout = timeout;

            // Send to native bridge
            try {
                native.match!.captureFrame(requestId, base64);
            } catch (e) {
                clearTimeout(timeout);
                this._pendingRequests.delete(requestId);
                reject(new Error('Failed to send frame to native: ' + (e as Error).message));
            }
        });
    },

    // Request ID counter for captureFrame
    _nextRequestId: 1,

    /**
     * Set up global listener for match events.
     */
    _ensureGlobalListener(): void {
        if (this._globalListener) return;

        this._globalListener = (e: Event) => {
            const { requestId, success, item, distance, error } = (e as CustomEvent).detail as MatchEventDetail;
            const pending = this._pendingRequests.get(requestId);

            if (pending) {
                this._pendingRequests.delete(requestId);
                if (success) {
                    pending.resolve({ item, distance });
                } else {
                    pending.reject(new Error(error || 'No match found'));
                }
            }
        };

        window.addEventListener('loop:match', this._globalListener);
    }
};

// ==================== Pack API ====================

/**
 * Pack API - Asset retrieval via PackManager service.
 * Provides access to GLB models and other pack assets.
 */
const PackAPI = {
    _cache: new Map<string, ArrayBuffer>(),

    /**
     * Check if pack service is ready.
     */
    getStatus(): PackStatusResult {
        if (!native.pack) {
            return { ready: false, state: 0 };
        }
        try {
            return JSON.parse(native.pack.getStatus()) as PackStatusResult;
        } catch (e) {
            return { ready: false, state: 0 };
        }
    },

    /**
     * Get an asset from a pack.
     */
    async getAsset(packName: string, path: string): Promise<ArrayBuffer> {
        const cacheKey = `${packName}:${path}`;

        // Return cached asset if available
        if (this._cache.has(cacheKey)) {
            return this._cache.get(cacheKey)!;
        }

        if (!native.pack) {
            throw new Error('Pack API not available');
        }

        const response = JSON.parse(native.pack.getAsset(packName, path)) as PackAssetResponse;
        if (!response.success) {
            throw new Error(response.error || 'Asset not found');
        }

        // Decode base64 to ArrayBuffer
        const binary = atob(response.data!);
        const bytes = new Uint8Array(binary.length);
        for (let i = 0; i < binary.length; i++) {
            bytes[i] = binary.charCodeAt(i);
        }

        const buffer = bytes.buffer;

        // Cache the asset
        this._cache.set(cacheKey, buffer);

        return buffer;
    },

    /**
     * Get a GLB model as a Blob URL.
     */
    async getModelUrl(itemId: string): Promise<string> {
        // Models are stored as {item_id}.small.glb in the items pack
        const buffer = await this.getAsset('items', `models/${itemId}.small.glb`);
        const blob = new Blob([buffer], { type: 'model/gltf-binary' });
        return URL.createObjectURL(blob);
    },

    /**
     * Check if an asset exists in a pack.
     */
    assetExists(packName: string, path: string): boolean {
        if (!native.pack) return false;
        try {
            return native.pack.assetExists(packName, path);
        } catch (e) {
            return false;
        }
    },

    /**
     * Clear the asset cache.
     */
    clearCache(): void {
        this._cache.clear();
    },

    /**
     * Get the number of cached assets.
     */
    getCacheSize(): number {
        return this._cache.size;
    }
};

// ==================== Haptics API ====================

const HapticsAPI = {
    /**
     * Check if haptics are supported on this device.
     */
    isSupported(): boolean {
        if (!native.haptics) return false;
        try {
            const status = JSON.parse(native.haptics.getStatus()) as HapticsStatusResult;
            return status.available;
        } catch (e) {
            return false;
        }
    },

    /**
     * Get haptics capability status.
     */
    getStatus(): HapticsStatusResult {
        if (!native.haptics) {
            return { available: false, hasAmplitudeSupport: false };
        }
        try {
            return JSON.parse(native.haptics.getStatus()) as HapticsStatusResult;
        } catch (e) {
            return { available: false, hasAmplitudeSupport: false };
        }
    },

    /**
     * Trigger a single haptic pulse.
     */
    pulse(intensity = 0.5): HapticsResult {
        if (!native.haptics) {
            return { success: false, error: 'Haptics not available' };
        }
        try {
            return JSON.parse(native.haptics.pulse(intensity)) as HapticsResult;
        } catch (e) {
            return { success: false, error: (e as Error).message };
        }
    },

    /**
     * Play a curve-based haptic pattern.
     */
    playCurve(curve: HapticCurve): HapticsResult {
        if (!native.haptics) {
            return { success: false, error: 'Haptics not available' };
        }
        try {
            return JSON.parse(native.haptics.playCurve(JSON.stringify(curve))) as HapticsResult;
        } catch (e) {
            return { success: false, error: (e as Error).message };
        }
    },

    /**
     * Stop any ongoing haptic vibration.
     */
    stop(): HapticsResult {
        if (!native.haptics) {
            return { success: false, error: 'Haptics not available' };
        }
        try {
            return JSON.parse(native.haptics.stop()) as HapticsResult;
        } catch (e) {
            return { success: false, error: (e as Error).message };
        }
    }
};

// ==================== BLE API ====================

type BLEHandlerKey = 'message' | 'playerJoined' | 'playerLeft' | 'connected' | 'disconnected' | 'reconnecting' | 'roleResolved';

/**
 * BLE API - Local multiplayer via Bluetooth Low Energy.
 * Enables peer-to-peer communication between Dopple devices.
 */
const BLEAPI = {
    _handlers: {
        message: [],
        playerJoined: [],
        playerLeft: [],
        connected: [],
        disconnected: [],
        reconnecting: [],
        roleResolved: []
    } as Record<BLEHandlerKey, Array<(detail: unknown) => void>>,
    _globalListener: null as true | null,

    // ==================== Host API ====================

    /**
     * Create a game as host.
     * @param gameId The game identifier
     * @param token Optional deterministic token for BLE advertising.
     *              When provided, both host and client can derive the same token
     *              (e.g. from a shared marker ID) enabling discovery without exchange.
     */
    createGame(gameId: string, token?: string): string {
        if (!native.ble) {
            throw new Error('BLE API not available');
        }

        const response = JSON.parse(
            token
                ? native.ble.createGame(gameId, token)
                : native.ble.createGame(gameId)
        ) as BLECreateResponse;
        if (!response.success) {
            throw new Error(response.error || 'Failed to create game');
        }

        this._ensureGlobalListener();
        return response.token!;
    },

    // ==================== Client API ====================

    /**
     * Join a game as a player using the host's token.
     */
    async joinGame(hostToken: string, playerName = 'Player'): Promise<unknown> {
        if (!native.ble) {
            throw new Error('BLE API not available');
        }

        this._ensureGlobalListener();

        const response = JSON.parse(native.ble.joinGame(hostToken, playerName)) as BLEJoinResponse;
        if (!response.success) {
            throw new Error(response.error || 'Failed to join game');
        }

        // Wait for connected or disconnected event
        return new Promise((resolve, reject) => {
            const timeout = setTimeout(() => {
                cleanup();
                reject(new Error('Timeout waiting for connection'));
            }, 15000);

            const cleanup = () => {
                clearTimeout(timeout);
                this.off('connected', onConnected);
                this.off('disconnected', onDisconnected);
            };

            const onConnected = (detail: any) => {
                cleanup();
                resolve(detail.host);
            };

            const onDisconnected = (detail: any) => {
                cleanup();
                reject(new Error(detail.reason || 'Connection failed'));
            };

            this.on('connected', onConnected);
            this.on('disconnected', onDisconnected);
        });
    },

    /**
     * Play a game using seed-based auto-role negotiation.
     * Derives a BLE token from the seed; negotiates host/client role automatically.
     * Resolves with { role, token } when the role is determined.
     * @param seed Shared seed string (e.g. NFC tag ID, QR payload)
     * @param playerName The local player's display name
     * @param options Optional settings: { timeoutMs } (default 15000)
     */
    async playGame(seed: string, playerName = 'Player', options: { timeoutMs?: number } = {}): Promise<{ role: string; token: string }> {
        if (!native.ble) {
            throw new Error('BLE API not available');
        }

        this._ensureGlobalListener();

        const response = JSON.parse(native.ble.playGame(seed, playerName)) as BLEPlayResponse;
        if (!response.success) {
            throw new Error(response.error || 'Failed to start playGame');
        }

        const timeoutMs = options.timeoutMs || 15000;
        return new Promise((resolve, reject) => {
            const timeout = setTimeout(() => {
                cleanup();
                reject(new Error('Timeout waiting for role resolution'));
            }, timeoutMs);

            const cleanup = () => {
                clearTimeout(timeout);
                this.off('roleResolved', onResolved);
                this.off('disconnected', onDisconnected);
            };

            const onResolved = (detail: any) => {
                cleanup();
                resolve({ role: detail.role, token: detail.token });
            };

            const onDisconnected = (detail: any) => {
                cleanup();
                reject(new Error(detail.reason || 'Negotiation failed'));
            };

            this.on('roleResolved', onResolved);
            this.on('disconnected', onDisconnected);
        });
    },

    /**
     * End the game and disconnect all players (host only).
     */
    endGame(): void {
        if (native.ble) {
            native.ble.endGame();
        }
    },

    /**
     * Leave the game and disconnect from host (client only).
     */
    leaveGame(): void {
        if (native.ble) {
            native.ble.leaveGame();
        }
    },

    /**
     * Get the current BLE connection state.
     * @returns One of: 'idle', 'negotiating', 'hosting', 'scanning', 'connecting', 'connected', 'reconnecting'
     */
    getState(): string {
        if (!native.ble || !native.ble.getState) {
            return 'idle';
        }
        const response = JSON.parse(native.ble.getState());
        return response.state || 'idle';
    },

    // ==================== Messaging ====================

    /**
     * Send a message to player(s) or host.
     */
    send(data: unknown, options: { to?: string } = {}): void {
        if (!native.ble) {
            throw new Error('BLE API not available');
        }

        const dataJson = typeof data === 'string' ? data : JSON.stringify(data);
        const optionsJson = JSON.stringify({
            to: options.to || null
        });

        const response = JSON.parse(native.ble.send(dataJson, optionsJson)) as BLESendResponse;
        if (!response.success) {
            throw new Error(response.error || 'Failed to send message');
        }
    },

    // ==================== Events ====================

    /**
     * Register a handler for BLE events.
     */
    on(event: string, handler: (detail: unknown) => void): void {
        if (this._handlers[event as BLEHandlerKey]) {
            this._handlers[event as BLEHandlerKey].push(handler);
            this._ensureGlobalListener();
        }
    },

    /**
     * Remove a BLE event handler.
     */
    off(event: string, handler: (detail: unknown) => void): void {
        if (this._handlers[event as BLEHandlerKey]) {
            const idx = this._handlers[event as BLEHandlerKey].indexOf(handler);
            if (idx !== -1) {
                this._handlers[event as BLEHandlerKey].splice(idx, 1);
            }
        }
    },

    // ==================== Internal ====================

    _ensureGlobalListener(): void {
        if (this._globalListener) return;

        // Map event types to handler keys
        const eventMap: Record<string, BLEHandlerKey> = {
            'loop:ble:message': 'message',
            'loop:ble:playerJoined': 'playerJoined',
            'loop:ble:playerLeft': 'playerLeft',
            'loop:ble:connected': 'connected',
            'loop:ble:disconnected': 'disconnected',
            'loop:ble:reconnecting': 'reconnecting',
            'loop:ble:roleResolved': 'roleResolved'
        };

        // Create a single listener for all BLE events
        Object.entries(eventMap).forEach(([eventType, handlerKey]) => {
            window.addEventListener(eventType, (e: Event) => {
                const handlers = this._handlers[handlerKey];
                if (handlers) {
                    for (const h of handlers) {
                        try {
                            h((e as CustomEvent).detail);
                        } catch (err) {
                            console.error(`[Loop.ble] Handler error for ${handlerKey}:`, err);
                        }
                    }
                }
            });
        });

        this._globalListener = true;
    }
};

// ==================== Storage Types ====================

interface StorageResult {
    success: boolean;
    error?: string;
}

interface StorageUsage {
    used: number;
    quota: number;
}

// ==================== Storage API ====================

/**
 * Storage API - Per-game persistent key-value storage.
 * Scoped per game by projectId (set from manifest, not accessible from JS).
 * 1MB quota per game. Data persists across sessions.
 */
const StorageAPI = {
    /**
     * Store a value.
     * @param key - Key name (max 256 chars, no slashes or null bytes)
     * @param value - String value to store
     */
    setItem(key: string, value: string): StorageResult {
        if (!native.storage) {
            return { success: false, error: 'Storage not available' };
        }
        try {
            return JSON.parse(native.storage.setItem(key, value)) as StorageResult;
        } catch (e) {
            return { success: false, error: (e as Error).message };
        }
    },

    /**
     * Retrieve a value.
     * @param key - Key name
     * @returns The stored value, or null if not found
     */
    getItem(key: string): string | null {
        if (!native.storage) return null;
        try {
            return native.storage.getItem(key);
        } catch (e) {
            return null;
        }
    },

    /**
     * Remove a key.
     */
    removeItem(key: string): StorageResult {
        if (!native.storage) {
            return { success: false, error: 'Storage not available' };
        }
        try {
            return JSON.parse(native.storage.removeItem(key)) as StorageResult;
        } catch (e) {
            return { success: false, error: (e as Error).message };
        }
    },

    /**
     * Remove all keys for this game.
     */
    clear(): StorageResult {
        if (!native.storage) {
            return { success: false, error: 'Storage not available' };
        }
        try {
            return JSON.parse(native.storage.clear()) as StorageResult;
        } catch (e) {
            return { success: false, error: (e as Error).message };
        }
    },

    /**
     * Get all key names.
     */
    keys(): string[] {
        if (!native.storage) return [];
        try {
            return JSON.parse(native.storage.keys()) as string[];
        } catch (e) {
            return [];
        }
    },

    /**
     * Get storage usage in bytes.
     */
    getUsage(): StorageUsage {
        if (!native.storage) {
            return { used: 0, quota: 0 };
        }
        try {
            return JSON.parse(native.storage.getUsage()) as StorageUsage;
        } catch (e) {
            return { used: 0, quota: 0 };
        }
    },

    /**
     * Check if a key exists without reading its value.
     * More efficient than getItem() !== null for large values.
     */
    exists(key: string): boolean {
        if (!native.storage) return false;
        try {
            return native.storage.exists(key);
        } catch (e) {
            return false;
        }
    }
};

// ==================== System API (pause/resume) ====================

type SystemEventType = 'pause' | 'resume';

const SystemAPI = {
    isFreeRotateEnabled(): boolean {
        if (!native.system) return false;
        try {
            const result = JSON.parse(native.system.isFreeRotateEnabled());
            return result.enabled;
        } catch { return false; }
    },

    setFreeRotate(enabled: boolean): { success: boolean } {
        if (!native.system) return { success: false };
        try {
            return JSON.parse(native.system.setFreeRotate(enabled));
        } catch { return { success: false }; }
    },

    on(event: SystemEventType, handler: (detail: any) => void): void {
        window.addEventListener(`loop:${event}`, ((e: CustomEvent) => {
            handler(e.detail);
        }) as EventListener);
    },

    off(event: SystemEventType, handler: (detail: any) => void): void {
        window.removeEventListener(`loop:${event}`, handler as EventListener);
    }
};

// ==================== Main Loop SDK Object ====================

const Loop = {
    /**
     * Check if the Loop SDK is available.
     */
    isAvailable(): boolean {
        return native.motion !== undefined ||
               native.haptics !== undefined ||
               native.buttons !== undefined ||
               native.match !== undefined ||
               native.pack !== undefined ||
               native.ble !== undefined ||
               native.storage !== undefined;
    },

    /**
     * Get SDK version.
     */
    get version(): string {
        return '1.4.0';
    },

    motion: MotionAPI,
    buttons: ButtonsAPI,
    haptics: HapticsAPI,
    match: MatchAPI,
    pack: PackAPI,
    ble: BLEAPI,
    storage: StorageAPI,
    system: SystemAPI
};

// Expose globally
(window as any).Loop = Loop;

// Dispatch ready event
window.dispatchEvent(new CustomEvent('loop:ready', { detail: { version: Loop.version } }));
