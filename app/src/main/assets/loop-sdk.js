(function () {
    'use strict';

    const BUFFER_SIZE = 30;
    const VARIANCE_THRESHOLD = 0.0002;
    const SETTLE_TIME = 0.4; // seconds
    class RestDetector {
        constructor() {
            this.buffer = [];
            this.index = 0;
            this.full = false;
            this.settleTimer = 0;
            this._atRest = false;
        }
        get atRest() { return this._atRest; }
        update(gravity, deltaTime) {
            // Write to circular buffer
            if (this.buffer.length < BUFFER_SIZE) {
                this.buffer.push({ ...gravity });
            }
            else {
                this.buffer[this.index] = { ...gravity };
            }
            this.index = (this.index + 1) % BUFFER_SIZE;
            if (this.index === 0)
                this.full = true;
            const count = this.full ? BUFFER_SIZE : this.buffer.length;
            if (count < 2)
                return;
            // Compute mean
            let mx = 0, my = 0, mz = 0;
            for (let i = 0; i < count; i++) {
                mx += this.buffer[i].x;
                my += this.buffer[i].y;
                mz += this.buffer[i].z;
            }
            mx /= count;
            my /= count;
            mz /= count;
            // Compute variance
            let variance = 0;
            for (let i = 0; i < count; i++) {
                const dx = this.buffer[i].x - mx;
                const dy = this.buffer[i].y - my;
                const dz = this.buffer[i].z - mz;
                variance += dx * dx + dy * dy + dz * dz;
            }
            variance /= count;
            if (variance < VARIANCE_THRESHOLD) {
                this.settleTimer += deltaTime;
                if (this.settleTimer >= SETTLE_TIME) {
                    this._atRest = true;
                }
            }
            else {
                this.settleTimer = 0;
                this._atRest = false;
            }
        }
        reset() {
            this.buffer = [];
            this.index = 0;
            this.full = false;
            this.settleTimer = 0;
            this._atRest = false;
        }
    }

    class BaseController {
        constructor(autoRecalibrator) {
            this._calibrated = false;
            this._active = true;
            this._lastInput = null;
            this._lastRest = false;
            this.listeners = new Map();
            this.restDetector = new RestDetector();
            this.lastTimestamp = 0;
            this.autoRecalibrator = autoRecalibrator;
        }
        get calibrated() { return this._calibrated; }
        get active() { return this._active; }
        get lastInput() { return this._lastInput; }
        on(event, handler) {
            if (!this.listeners.has(event))
                this.listeners.set(event, new Set());
            this.listeners.get(event).add(handler);
            return this;
        }
        off(event, handler) {
            this.listeners.get(event)?.delete(handler);
            return this;
        }
        emit(event, ...args) {
            this.listeners.get(event)?.forEach(fn => fn(...args));
        }
        /** Called by subclass with each raw MotionData frame */
        processFrame(data) {
            if (!this._active)
                return;
            const deltaTime = this.lastTimestamp > 0
                ? (data.timestamp - this.lastTimestamp) / 1000
                : 1 / 60;
            this.lastTimestamp = data.timestamp;
            // Step 1: Rest detection
            this.restDetector.update(data.smoothGravity, deltaTime);
            const atRest = this.restDetector.atRest;
            if (atRest !== this._lastRest) {
                this._lastRest = atRest;
                this.emit('rest', atRest);
            }
            // Step 2: Calibration
            if (!this._calibrated) {
                const done = this.feedCalibrator(data);
                if (done) {
                    this._calibrated = true;
                    this.onCalibrated(data);
                    this.emit('calibrated');
                }
                return; // Don't emit input during calibration
            }
            // Step 3: Auto-recalibration (updates reference in-place)
            this.updateAutoRecal(data, atRest, deltaTime);
            // Step 4: Mode-specific processing
            const input = this.computeInput(data, atRest);
            this._lastInput = input;
            this.emit('input', input);
        }
        recalibrate() {
            this._calibrated = false;
            this.restDetector.reset();
            this.resetCalibrator(false); // no skip phase
        }
        pauseRecalibration() {
            this.autoRecalibrator.pause();
        }
        resumeRecalibration() {
            this.autoRecalibrator.resume();
        }
        stop() {
            this._active = false;
            this.listeners.clear();
        }
    }

    const SKIP_FRAMES = 15;
    const COLLECT_FRAMES = 30;
    class Calibrator {
        constructor(mode, skipPhase = true) {
            this.skipCount = 0;
            this.collectCount = 0;
            this._hasReference = false;
            // Vector accumulator
            this.sumX = 0;
            this.sumY = 0;
            this.sumZ = 0;
            // Quaternion accumulator
            this.sumQx = 0;
            this.sumQy = 0;
            this.sumQz = 0;
            this.sumQw = 0;
            this.firstQuat = null;
            this._reference = null;
            this.mode = mode;
            this.skipPhase = skipPhase;
        }
        get hasReference() { return this._hasReference; }
        get reference() { return this._reference; }
        feedVector(v) {
            if (this._hasReference)
                return true;
            if (this.skipPhase && this.skipCount < SKIP_FRAMES) {
                this.skipCount++;
                return false;
            }
            this.sumX += v.x;
            this.sumY += v.y;
            this.sumZ += v.z;
            this.collectCount++;
            if (this.collectCount >= COLLECT_FRAMES) {
                this._reference = {
                    x: this.sumX / COLLECT_FRAMES,
                    y: this.sumY / COLLECT_FRAMES,
                    z: this.sumZ / COLLECT_FRAMES,
                };
                this._hasReference = true;
                return true;
            }
            return false;
        }
        feedQuaternion(q) {
            if (this._hasReference)
                return true;
            if (this.skipPhase && this.skipCount < SKIP_FRAMES) {
                this.skipCount++;
                return false;
            }
            // Hemisphere normalization: flip if dot product with first quat < 0
            if (this.firstQuat === null) {
                this.firstQuat = { ...q };
            }
            else {
                const dot = q.x * this.firstQuat.x + q.y * this.firstQuat.y +
                    q.z * this.firstQuat.z + q.w * this.firstQuat.w;
                if (dot < 0) {
                    q = { x: -q.x, y: -q.y, z: -q.z, w: -q.w };
                }
            }
            this.sumQx += q.x;
            this.sumQy += q.y;
            this.sumQz += q.z;
            this.sumQw += q.w;
            this.collectCount++;
            if (this.collectCount >= COLLECT_FRAMES) {
                // Normalize averaged quaternion
                const len = Math.sqrt(this.sumQx * this.sumQx + this.sumQy * this.sumQy +
                    this.sumQz * this.sumQz + this.sumQw * this.sumQw);
                this._reference = {
                    x: this.sumQx / len,
                    y: this.sumQy / len,
                    z: this.sumQz / len,
                    w: this.sumQw / len,
                };
                this._hasReference = true;
                return true;
            }
            return false;
        }
        reset(skipPhase = true) {
            this.skipCount = 0;
            this.collectCount = 0;
            this.skipPhase = skipPhase;
            this._hasReference = false;
            this._reference = null;
            this.sumX = this.sumY = this.sumZ = 0;
            this.sumQx = this.sumQy = this.sumQz = this.sumQw = 0;
            this.firstQuat = null;
        }
    }

    const RATE_REST = 3.0;
    const RATE_MAX = 1.0;
    const RATE_FROZEN_GAME = 0.02;
    const RATE_FROZEN_FULL = 0.08;
    class AutoRecalibrator {
        constructor(fusion = 'game') {
            this.paused = false;
            this.frozenRate = fusion === 'full' ? RATE_FROZEN_FULL : RATE_FROZEN_GAME;
        }
        pause() { this.paused = true; }
        resume() { this.paused = false; }
        updateVector(reference, current, inputMagnitude, atRest, deltaTime) {
            if (this.paused)
                return reference;
            const rate = this.selectRate(inputMagnitude, atRest);
            const t = 1 - Math.exp(-rate * deltaTime);
            return {
                x: reference.x + (current.x - reference.x) * t,
                y: reference.y + (current.y - reference.y) * t,
                z: reference.z + (current.z - reference.z) * t,
            };
        }
        updateQuaternion(reference, current, inputMagnitude, atRest, deltaTime) {
            if (this.paused)
                return reference;
            const rate = this.selectRate(inputMagnitude, atRest);
            const t = 1 - Math.exp(-rate * deltaTime);
            return slerp(reference, current, t);
        }
        selectRate(inputMagnitude, atRest) {
            if (atRest)
                return RATE_REST;
            const weight = 1 - Math.min(1, Math.max(0, inputMagnitude));
            return this.frozenRate + weight * (RATE_MAX - this.frozenRate);
        }
    }
    function slerp(a, b, t) {
        // Ensure shortest path
        let dot = a.x * b.x + a.y * b.y + a.z * b.z + a.w * b.w;
        let bx = b.x, by = b.y, bz = b.z, bw = b.w;
        if (dot < 0) {
            dot = -dot;
            bx = -bx;
            by = -by;
            bz = -bz;
            bw = -bw;
        }
        if (dot > 0.9995) {
            // Linear interpolation for very close quaternions
            return normalizeQuat({
                x: a.x + (bx - a.x) * t,
                y: a.y + (by - a.y) * t,
                z: a.z + (bz - a.z) * t,
                w: a.w + (bw - a.w) * t,
            });
        }
        const theta = Math.acos(dot);
        const sinTheta = Math.sin(theta);
        const wa = Math.sin((1 - t) * theta) / sinTheta;
        const wb = Math.sin(t * theta) / sinTheta;
        return {
            x: wa * a.x + wb * bx,
            y: wa * a.y + wb * by,
            z: wa * a.z + wb * bz,
            w: wa * a.w + wb * bw,
        };
    }
    function normalizeQuat(q) {
        const len = Math.sqrt(q.x * q.x + q.y * q.y + q.z * q.z + q.w * q.w);
        return { x: q.x / len, y: q.y / len, z: q.z / len, w: q.w / len };
    }

    /**
     * Remapped deadzone: values below threshold snap to zero,
     * values above are linearly rescaled so output reaches ±1 smoothly.
     */
    function applyDeadzone(value, deadzone, maxAngle) {
        if (deadzone <= 0)
            return Math.max(-1, Math.min(1, value / maxAngle));
        const absValue = Math.abs(value);
        if (absValue <= deadzone)
            return 0;
        const range = maxAngle - deadzone;
        if (range <= 0)
            return 0;
        const normalized = (absValue - deadzone) / range;
        return Math.sign(value) * Math.min(1, normalized);
    }

    class TiltProcessor {
        constructor(maxAngle = 25, deadzone = 2, sensitivity = { x: 1, y: 1 }) {
            this.refGravity = { x: 0, y: 0, z: -9.81 };
            this.zAngle = 0; // Z-rotation compensation angle (radians)
            this.maxAngle = maxAngle;
            this.deadzone = deadzone;
            this.sensitivity = sensitivity;
        }
        setReference(gravity) {
            this.refGravity = { ...gravity };
            // Compute Z-rotation angle for wrist orientation compensation
            // Use || 0 to normalize -0 to 0 (avoids atan2(0, -0) = π edge case)
            this.zAngle = Math.atan2(gravity.x || 0, (-gravity.y) || 0);
        }
        /** @param gravity Use MotionData.smoothGravity (low-pass filtered) per spec Section 5.1 */
        process(gravity, atRest, timestamp) {
            // Compute tilt angles using atan2
            const currentAngleX = Math.atan2(gravity.x, -gravity.z) * (180 / Math.PI);
            const currentAngleY = Math.atan2(gravity.y, -gravity.z) * (180 / Math.PI);
            const refAngleX = Math.atan2(this.refGravity.x, -this.refGravity.z) * (180 / Math.PI);
            const refAngleY = Math.atan2(this.refGravity.y, -this.refGravity.z) * (180 / Math.PI);
            let deltaX = currentAngleX - refAngleX;
            let deltaY = currentAngleY - refAngleY;
            // Z-rotation compensation: rotate delta back to calibration orientation
            const cos = Math.cos(this.zAngle);
            const sin = Math.sin(this.zAngle);
            const rotX = deltaX * cos - deltaY * sin;
            const rotY = deltaX * sin + deltaY * cos;
            // Apply sensitivity
            const scaledX = rotX * this.sensitivity.x;
            const scaledY = rotY * this.sensitivity.y;
            // Apply deadzone and normalize
            const x = applyDeadzone(scaledX, this.deadzone, this.maxAngle);
            const y = applyDeadzone(scaledY, this.deadzone, this.maxAngle);
            const magnitude = Math.min(1, Math.sqrt(x * x + y * y));
            return { x, y, magnitude, atRest, timestamp };
        }
    }

    const DEFAULTS$2 = {
        frequency: 60,
        smoothing: 0.1,
        maxAngle: 25,
        deadzone: 2,
        sensitivity: { x: 1, y: 1 },
        autoRecalibrate: true,
    };
    class TiltController extends BaseController {
        constructor(options = {}) {
            const opts = { ...DEFAULTS$2, ...options };
            super(new AutoRecalibrator('game'));
            this.mode = 'tilt';
            this.gravityRef = { x: 0, y: 0, z: -9.81 };
            this.opts = opts;
            this.calibrator = new Calibrator('vector', true);
            this.processor = new TiltProcessor(opts.maxAngle, opts.deadzone, opts.sensitivity);
        }
        feedCalibrator(data) {
            return this.calibrator.feedVector(data.smoothGravity);
        }
        onCalibrated(_data) {
            this.gravityRef = { ...this.calibrator.reference };
            this.processor.setReference(this.gravityRef);
        }
        updateAutoRecal(data, atRest, deltaTime) {
            if (!this.opts.autoRecalibrate)
                return;
            const magnitude = this.lastInput?.magnitude ?? 0;
            this.gravityRef = this.autoRecalibrator.updateVector(this.gravityRef, data.smoothGravity, magnitude, atRest, deltaTime);
            this.processor.setReference(this.gravityRef);
        }
        computeInput(data, atRest) {
            return this.processor.process(data.smoothGravity, atRest, data.timestamp);
        }
        resetCalibrator(skipPhase) {
            this.calibrator.reset(skipPhase);
        }
    }

    const DEG$1 = 180 / Math.PI;
    class LookProcessor {
        constructor(maxAngle = 45, deadzone = 2, sensitivity = { x: 1, y: 1 }) {
            this.refInverse = { x: 0, y: 0, z: 0, w: 1 };
            this.maxAngle = maxAngle;
            this.deadzone = deadzone;
            this.sensitivity = sensitivity;
        }
        setReference(quat) {
            // Inverse of unit quaternion = conjugate
            this.refInverse = { x: -quat.x, y: -quat.y, z: -quat.z, w: quat.w };
        }
        process(orientation, gravity, atRest, timestamp) {
            // Delta quaternion: inverse(ref) * current
            const delta = quatMultiply(this.refInverse, orientation);
            // Extract yaw and pitch from delta quaternion
            // Yaw (Y-axis rotation): atan2(2*(wy - xz), 1 - 2*(y²+z²))
            // Pitch (X-axis rotation): asin(2*(wx + yz))
            const yawRaw = Math.atan2(2 * (delta.w * delta.y - delta.x * delta.z), 1 - 2 * (delta.y * delta.y + delta.z * delta.z)) * DEG$1;
            const sinP = 2 * (delta.w * delta.x + delta.y * delta.z);
            const pitchRaw = Math.asin(Math.max(-1, Math.min(1, sinP))) * DEG$1;
            // Apply sensitivity
            const yaw = yawRaw * this.sensitivity.x;
            const pitch = pitchRaw * this.sensitivity.y;
            // Normalize to -1..1
            const x = applyDeadzone(yaw, this.deadzone, this.maxAngle);
            const y = applyDeadzone(pitch, this.deadzone, this.maxAngle);
            const magnitude = Math.min(1, Math.sqrt(x * x + y * y));
            return { yaw, pitch, x, y, magnitude, atRest, timestamp };
        }
    }
    /** Hamilton product of two quaternions */
    function quatMultiply(a, b) {
        return {
            x: a.w * b.x + a.x * b.w + a.y * b.z - a.z * b.y,
            y: a.w * b.y - a.x * b.z + a.y * b.w + a.z * b.x,
            z: a.w * b.z + a.x * b.y - a.y * b.x + a.z * b.w,
            w: a.w * b.w - a.x * b.x - a.y * b.y - a.z * b.z,
        };
    }

    const DEFAULTS$1 = {
        frequency: 60,
        smoothing: 0.1,
        maxAngle: 45,
        deadzone: 2,
        sensitivity: { x: 1, y: 1 },
        sensorFusion: 'game',
        autoRecalibrate: true,
    };
    class LookController extends BaseController {
        constructor(options = {}) {
            const opts = { ...DEFAULTS$1, ...options };
            super(new AutoRecalibrator(opts.sensorFusion));
            this.mode = 'look';
            this.quatRef = { x: 0, y: 0, z: 0, w: 1 };
            this.opts = opts;
            this.calibrator = new Calibrator('quaternion', true);
            this.processor = new LookProcessor(opts.maxAngle, opts.deadzone, opts.sensitivity);
        }
        feedCalibrator(data) {
            return this.calibrator.feedQuaternion(data.orientation);
        }
        onCalibrated(_data) {
            this.quatRef = { ...this.calibrator.reference };
            this.processor.setReference(this.quatRef);
        }
        updateAutoRecal(data, atRest, deltaTime) {
            if (!this.opts.autoRecalibrate)
                return;
            const magnitude = this.lastInput?.magnitude ?? 0;
            this.quatRef = this.autoRecalibrator.updateQuaternion(this.quatRef, data.orientation, magnitude, atRest, deltaTime);
            this.processor.setReference(this.quatRef);
        }
        computeInput(data, atRest) {
            return this.processor.process(data.orientation, data.smoothGravity, atRest, data.timestamp);
        }
        resetCalibrator(skipPhase) {
            this.calibrator.reset(skipPhase);
        }
    }

    // Normalize axis aliases
    function resolveAxis(axis) {
        switch (axis) {
            case 'roll': return 'twist';
            case 'yaw': return 'turn';
            case 'pitch': return 'lean';
            default: return axis;
        }
    }
    class RotateProcessor {
        constructor(axis = 'twist', maxAngle = 45, deadzone = 2, sensitivity = 1) {
            this.refAngle = 0;
            this.resolvedAxis = resolveAxis(axis);
            this.maxAngle = maxAngle;
            this.deadzone = deadzone;
            this.sensitivity = sensitivity;
        }
        get usesQuaternion() { return this.resolvedAxis === 'turn'; }
        setReferenceGravity(gravity) {
            if (this.resolvedAxis === 'twist') {
                this.refAngle = Math.atan2(gravity.x, -gravity.z) * (180 / Math.PI);
            }
            else if (this.resolvedAxis === 'lean') {
                this.refAngle = Math.atan2(gravity.y, -gravity.z) * (180 / Math.PI);
            }
        }
        setReferenceQuaternion(yawDegrees) {
            this.refAngle = yawDegrees;
        }
        processGravity(gravity, atRest, timestamp) {
            let currentAngle;
            if (this.resolvedAxis === 'twist') {
                currentAngle = Math.atan2(gravity.x, -gravity.z) * (180 / Math.PI);
            }
            else {
                currentAngle = Math.atan2(gravity.y, -gravity.z) * (180 / Math.PI);
            }
            const delta = (currentAngle - this.refAngle) * this.sensitivity;
            const value = applyDeadzone(delta, this.deadzone, this.maxAngle);
            return { angle: delta, value, atRest, timestamp };
        }
        processYaw(yawDegrees, atRest, timestamp) {
            const delta = (yawDegrees - this.refAngle) * this.sensitivity;
            const value = applyDeadzone(delta, this.deadzone, this.maxAngle);
            return { angle: delta, value, atRest, timestamp };
        }
    }

    const DEG = 180 / Math.PI;
    const DEFAULTS = {
        frequency: 60,
        smoothing: 0.1,
        maxAngle: 45,
        deadzone: 2,
        sensitivity: 1,
        axis: 'twist',
        sensorFusion: 'game',
        autoRecalibrate: true,
    };
    class RotateController extends BaseController {
        constructor(options = {}) {
            const opts = { ...DEFAULTS, ...options };
            super(new AutoRecalibrator(opts.sensorFusion));
            this.mode = 'rotate';
            this.gravityRef = { x: 0, y: 0, z: -9.81 };
            this.quatRef = { x: 0, y: 0, z: 0, w: 1 };
            this.opts = opts;
            this.processor = new RotateProcessor(opts.axis, opts.maxAngle, opts.deadzone, opts.sensitivity);
            this.calibrator = new Calibrator(this.processor.usesQuaternion ? 'quaternion' : 'vector', true);
        }
        feedCalibrator(data) {
            if (this.processor.usesQuaternion) {
                return this.calibrator.feedQuaternion(data.orientation);
            }
            return this.calibrator.feedVector(data.smoothGravity);
        }
        onCalibrated(_data) {
            if (this.processor.usesQuaternion) {
                this.quatRef = { ...this.calibrator.reference };
                // Extract initial yaw for reference
                const yaw = this.extractYaw(this.quatRef, { x: 0, y: 0, z: 0, w: 1 });
                this.processor.setReferenceQuaternion(yaw);
            }
            else {
                this.gravityRef = { ...this.calibrator.reference };
                this.processor.setReferenceGravity(this.gravityRef);
            }
        }
        updateAutoRecal(data, atRest, deltaTime) {
            if (!this.opts.autoRecalibrate)
                return;
            const magnitude = Math.abs(this.lastInput?.value ?? 0);
            if (this.processor.usesQuaternion) {
                this.quatRef = this.autoRecalibrator.updateQuaternion(this.quatRef, data.orientation, magnitude, atRest, deltaTime);
                const yaw = this.extractYaw(this.quatRef, { x: 0, y: 0, z: 0, w: 1 });
                this.processor.setReferenceQuaternion(yaw);
            }
            else {
                this.gravityRef = this.autoRecalibrator.updateVector(this.gravityRef, data.smoothGravity, magnitude, atRest, deltaTime);
                this.processor.setReferenceGravity(this.gravityRef);
            }
        }
        computeInput(data, atRest) {
            if (this.processor.usesQuaternion) {
                // Extract yaw from current orientation relative to identity
                const yaw = this.extractYaw(data.orientation, { x: 0, y: 0, z: 0, w: 1 });
                return this.processor.processYaw(yaw, atRest, data.timestamp);
            }
            return this.processor.processGravity(data.smoothGravity, atRest, data.timestamp);
        }
        resetCalibrator(skipPhase) {
            this.calibrator.reset(skipPhase);
        }
        extractYaw(quat, _ref) {
            // Yaw from quaternion: atan2(2*(wy - xz), 1 - 2*(y²+z²))
            return Math.atan2(2 * (quat.w * quat.y - quat.x * quat.z), 1 - 2 * (quat.y * quat.y + quat.z * quat.z)) * DEG;
        }
    }

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
        get motion() { return window.Loop$motion; },
        get buttons() { return window.Loop$buttons; },
        get haptics() { return window.Loop$haptics; },
        get match() { return window.Loop$match; },
        get pack() { return window.Loop$pack; },
        get ble() { return window.Loop$ble; },
        get storage() { return window.Loop$storage; },
        get system() { return window['Loop$system']; },
    };
    // ==================== MotionSubscription Class ====================
    /**
     * Represents an active motion data subscription.
     * Extends EventTarget for standard event handling.
     */
    class MotionSubscription extends EventTarget {
        constructor(subscriptionId) {
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
        on(event, handler) {
            if (!this._active)
                return this;
            const wrapper = (e) => handler(e.detail);
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
        off(event, handler) {
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
        stop() {
            if (!this._active)
                return;
            this._active = false;
            if (native.motion) {
                native.motion.unsubscribe(this._id);
            }
            this._handlers.clear();
            Loop.motion._removeSubscription(this._id);
        }
        get id() {
            return this._id;
        }
        get active() {
            return this._active;
        }
    }
    // ==================== Motion API ====================
    const MotionAPI = {
        _subscriptions: new Map(),
        _globalListener: null,
        _activeController: null,
        /**
         * Check if motion sensors are supported on this device.
         */
        isSupported() {
            if (!native.motion)
                return false;
            try {
                const avail = JSON.parse(native.motion.getSensorAvailability());
                return avail.accelerometer || avail.gyroscope || avail.rotationVector;
            }
            catch (e) {
                return false;
            }
        },
        /**
         * Start receiving motion data.
         */
        async start(options = {}) {
            if (!native.motion) {
                throw new Error('Loop motion API not available');
            }
            const subId = native.motion.subscribe();
            if (options.frequency !== undefined) {
                native.motion.setFrequency(options.frequency);
            }
            if (options.smoothing !== undefined) {
                native.motion.setSmoothingAlpha(options.smoothing);
            }
            const subscription = new MotionSubscription(subId);
            this._subscriptions.set(subId, subscription);
            this._ensureGlobalListener();
            return subscription;
        },
        /**
         * Set the motion data frequency.
         */
        setFrequency(hz) {
            if (native.motion) {
                native.motion.setFrequency(hz);
            }
            return this;
        },
        /**
         * Set the smoothing alpha for gravity calculations.
         */
        setSmoothingAlpha(alpha) {
            if (native.motion) {
                native.motion.setSmoothingAlpha(alpha);
            }
            return this;
        },
        /**
         * Get current motion streaming status.
         */
        getStatus() {
            if (!native.motion) {
                return { active: false, subscriptions: 0, frequencyHz: 0, smoothingAlpha: 0.1, paused: false };
            }
            try {
                return JSON.parse(native.motion.getStatus());
            }
            catch (e) {
                return { active: false, subscriptions: 0, frequencyHz: 0, smoothingAlpha: 0.1, paused: false };
            }
        },
        /**
         * Get the latest motion data without subscription.
         */
        getLatest() {
            if (!native.motion)
                return null;
            try {
                const data = native.motion.getLatest();
                return data ? JSON.parse(data) : null;
            }
            catch (e) {
                return null;
            }
        },
        /**
         * Start tilt mode — gravity-based 2D joystick.
         */
        async tilt(options = {}) {
            if (!this.isSupported()) {
                throw new Error('Motion sensors not available on this device');
            }
            this._activeController?.stop();
            const freq = Math.max(1, Math.min(240, options.frequency ?? 60));
            const ctrl = new TiltController({ ...options, frequency: freq });
            this._activeController = ctrl;
            // Start internal raw subscription
            const sub = await this.start({ frequency: freq, smoothing: options.smoothing });
            sub.on('data', (data) => ctrl['processFrame'](data));
            // Wire stop to cleanup
            const origStop = ctrl.stop.bind(ctrl);
            ctrl.stop = () => {
                origStop();
                sub.stop();
                if (this._activeController === ctrl) {
                    this._activeController = null;
                }
            };
            return ctrl;
        },
        /**
         * Start look mode — quaternion-based panoramic view.
         */
        async look(options = {}) {
            if (!this.isSupported()) {
                throw new Error('Motion sensors not available on this device');
            }
            this._activeController?.stop();
            const freq = Math.max(1, Math.min(240, options.frequency ?? 60));
            // Switch sensor fusion if needed
            if (options.sensorFusion && options.sensorFusion !== 'game' && native.motion) {
                native.motion.setSensorFusion(options.sensorFusion);
            }
            const ctrl = new LookController({ ...options, frequency: freq });
            this._activeController = ctrl;
            const sub = await this.start({ frequency: freq, smoothing: options.smoothing });
            sub.on('data', (data) => ctrl['processFrame'](data));
            const origStop = ctrl.stop.bind(ctrl);
            ctrl.stop = () => {
                origStop();
                sub.stop();
                // Restore default sensor fusion
                if (options.sensorFusion && options.sensorFusion !== 'game' && native.motion) {
                    native.motion.setSensorFusion('game');
                }
                if (this._activeController === ctrl) {
                    this._activeController = null;
                }
            };
            return ctrl;
        },
        /**
         * Start rotate mode — single-axis rotation.
         */
        async rotate(options = {}) {
            if (!this.isSupported()) {
                throw new Error('Motion sensors not available on this device');
            }
            this._activeController?.stop();
            const freq = Math.max(1, Math.min(240, options.frequency ?? 60));
            // Switch sensor fusion for turn axis
            if (options.sensorFusion && options.sensorFusion !== 'game' && native.motion) {
                native.motion.setSensorFusion(options.sensorFusion);
            }
            const ctrl = new RotateController({ ...options, frequency: freq });
            this._activeController = ctrl;
            const sub = await this.start({ frequency: freq, smoothing: options.smoothing });
            sub.on('data', (data) => ctrl['processFrame'](data));
            const origStop = ctrl.stop.bind(ctrl);
            ctrl.stop = () => {
                origStop();
                sub.stop();
                if (options.sensorFusion && options.sensorFusion !== 'game' && native.motion) {
                    native.motion.setSensorFusion('game');
                }
                if (this._activeController === ctrl) {
                    this._activeController = null;
                }
            };
            return ctrl;
        },
        /**
         * Start raw motion data subscription (alias for start).
         */
        async raw(options = {}) {
            return this.start(options);
        },
        /**
         * Stop all active subscriptions.
         */
        stopAll() {
            this._activeController?.stop();
            this._activeController = null;
            for (const sub of this._subscriptions.values()) {
                sub.stop();
            }
            this._subscriptions.clear();
        },
        _removeSubscription(id) {
            this._subscriptions.delete(id);
        },
        _ensureGlobalListener() {
            if (this._globalListener)
                return;
            this._globalListener = (e) => {
                const data = e.detail;
                for (const sub of this._subscriptions.values()) {
                    if (sub.active) {
                        sub.dispatchEvent(new CustomEvent('data', { detail: data }));
                    }
                }
            };
            window.addEventListener('loop:motion', this._globalListener);
        }
    };
    // ==================== Buttons API ====================
    const ButtonsAPI = {
        _handlers: {
            press: [],
            release: [],
            A: [],
            B: [],
            C: []
        },
        _globalListener: null,
        /**
         * Register a handler for button events.
         */
        on(event, handler) {
            if (this._handlers[event]) {
                this._handlers[event].push(handler);
                this._ensureGlobalListener();
            }
        },
        /**
         * Remove a button event handler.
         */
        off(event, handler) {
            if (this._handlers[event]) {
                const idx = this._handlers[event].indexOf(handler);
                if (idx !== -1) {
                    this._handlers[event].splice(idx, 1);
                }
            }
        },
        _ensureGlobalListener() {
            if (this._globalListener)
                return;
            this._globalListener = (e) => {
                const detail = e.detail;
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
        _pendingRequests: new Map(),
        _globalListener: null,
        /**
         * Check if match service is ready.
         */
        getStatus() {
            if (!native.match) {
                return { ready: false, status: 'unavailable' };
            }
            try {
                return JSON.parse(native.match.getStatus());
            }
            catch (e) {
                return { ready: false, status: 'error' };
            }
        },
        /**
         * Check if a match operation is currently in progress.
         */
        isBusy() {
            if (!native.match || !native.match.isBusy) {
                return false;
            }
            return native.match.isBusy();
        },
        /**
         * Capture frame from WebView video element and send to native for matching.
         */
        async captureFrame(videoElement = null) {
            if (!native.match) {
                throw new Error('Match API not available');
            }
            // Get video element from cameraManager if not provided
            const video = videoElement || window.lensApp?.cameraManager?.videoElement;
            if (!video || video.readyState < 2) { // HAVE_CURRENT_DATA
                throw new Error('Camera not ready');
            }
            this._ensureGlobalListener();
            // Create off-screen canvas for frame capture
            const canvas = document.createElement('canvas');
            canvas.width = 800;
            canvas.height = 800;
            const ctx = canvas.getContext('2d');
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
                this._pendingRequests.get(requestId).timeout = timeout;
                // Send to native bridge
                try {
                    native.match.captureFrame(requestId, base64);
                }
                catch (e) {
                    clearTimeout(timeout);
                    this._pendingRequests.delete(requestId);
                    reject(new Error('Failed to send frame to native: ' + e.message));
                }
            });
        },
        // Request ID counter for captureFrame
        _nextRequestId: 1,
        /**
         * Set up global listener for match events.
         */
        _ensureGlobalListener() {
            if (this._globalListener)
                return;
            this._globalListener = (e) => {
                const { requestId, success, item, distance, error } = e.detail;
                const pending = this._pendingRequests.get(requestId);
                if (pending) {
                    this._pendingRequests.delete(requestId);
                    if (success) {
                        pending.resolve({ item, distance });
                    }
                    else {
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
        _cache: new Map(),
        /**
         * Check if pack service is ready.
         */
        getStatus() {
            if (!native.pack) {
                return { ready: false, state: 0 };
            }
            try {
                return JSON.parse(native.pack.getStatus());
            }
            catch (e) {
                return { ready: false, state: 0 };
            }
        },
        /**
         * Get an asset from a pack.
         */
        async getAsset(packName, path) {
            const cacheKey = `${packName}:${path}`;
            // Return cached asset if available
            if (this._cache.has(cacheKey)) {
                return this._cache.get(cacheKey);
            }
            if (!native.pack) {
                throw new Error('Pack API not available');
            }
            const response = JSON.parse(native.pack.getAsset(packName, path));
            if (!response.success) {
                throw new Error(response.error || 'Asset not found');
            }
            // Decode base64 to ArrayBuffer
            const binary = atob(response.data);
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
        async getModelUrl(itemId) {
            // Models are stored as {item_id}.small.glb in the items pack
            const buffer = await this.getAsset('items', `models/${itemId}.small.glb`);
            const blob = new Blob([buffer], { type: 'model/gltf-binary' });
            return URL.createObjectURL(blob);
        },
        /**
         * Check if an asset exists in a pack.
         */
        assetExists(packName, path) {
            if (!native.pack)
                return false;
            try {
                return native.pack.assetExists(packName, path);
            }
            catch (e) {
                return false;
            }
        },
        /**
         * Clear the asset cache.
         */
        clearCache() {
            this._cache.clear();
        },
        /**
         * Get the number of cached assets.
         */
        getCacheSize() {
            return this._cache.size;
        }
    };
    // ==================== Haptics API ====================
    const HapticsAPI = {
        /**
         * Check if haptics are supported on this device.
         */
        isSupported() {
            if (!native.haptics)
                return false;
            try {
                const status = JSON.parse(native.haptics.getStatus());
                return status.available;
            }
            catch (e) {
                return false;
            }
        },
        /**
         * Get haptics capability status.
         */
        getStatus() {
            if (!native.haptics) {
                return { available: false, hasAmplitudeSupport: false };
            }
            try {
                return JSON.parse(native.haptics.getStatus());
            }
            catch (e) {
                return { available: false, hasAmplitudeSupport: false };
            }
        },
        /**
         * Trigger a single haptic pulse.
         */
        pulse(intensity = 0.5) {
            if (!native.haptics) {
                return { success: false, error: 'Haptics not available' };
            }
            try {
                return JSON.parse(native.haptics.pulse(intensity));
            }
            catch (e) {
                return { success: false, error: e.message };
            }
        },
        /**
         * Play a curve-based haptic pattern.
         */
        playCurve(curve) {
            if (!native.haptics) {
                return { success: false, error: 'Haptics not available' };
            }
            try {
                return JSON.parse(native.haptics.playCurve(JSON.stringify(curve)));
            }
            catch (e) {
                return { success: false, error: e.message };
            }
        },
        /**
         * Stop any ongoing haptic vibration.
         */
        stop() {
            if (!native.haptics) {
                return { success: false, error: 'Haptics not available' };
            }
            try {
                return JSON.parse(native.haptics.stop());
            }
            catch (e) {
                return { success: false, error: e.message };
            }
        }
    };
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
        },
        _globalListener: null,
        // ==================== Host API ====================
        /**
         * Create a game as host.
         * @param gameId The game identifier
         * @param token Optional deterministic token for BLE advertising.
         *              When provided, both host and client can derive the same token
         *              (e.g. from a shared marker ID) enabling discovery without exchange.
         */
        createGame(gameId, token) {
            if (!native.ble) {
                throw new Error('BLE API not available');
            }
            const response = JSON.parse(token
                ? native.ble.createGame(gameId, token)
                : native.ble.createGame(gameId));
            if (!response.success) {
                throw new Error(response.error || 'Failed to create game');
            }
            this._ensureGlobalListener();
            return response.token;
        },
        // ==================== Client API ====================
        /**
         * Join a game as a player using the host's token.
         */
        async joinGame(hostToken, playerName = 'Player') {
            if (!native.ble) {
                throw new Error('BLE API not available');
            }
            this._ensureGlobalListener();
            const response = JSON.parse(native.ble.joinGame(hostToken, playerName));
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
                const onConnected = (detail) => {
                    cleanup();
                    resolve(detail.host);
                };
                const onDisconnected = (detail) => {
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
        async playGame(seed, playerName = 'Player', options = {}) {
            if (!native.ble) {
                throw new Error('BLE API not available');
            }
            this._ensureGlobalListener();
            const response = JSON.parse(native.ble.playGame(seed, playerName));
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
                const onResolved = (detail) => {
                    cleanup();
                    resolve({ role: detail.role, token: detail.token });
                };
                const onDisconnected = (detail) => {
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
        endGame() {
            if (native.ble) {
                native.ble.endGame();
            }
        },
        /**
         * Leave the game and disconnect from host (client only).
         */
        leaveGame() {
            if (native.ble) {
                native.ble.leaveGame();
            }
        },
        /**
         * Get the current BLE connection state.
         * @returns One of: 'idle', 'negotiating', 'hosting', 'scanning', 'connecting', 'connected', 'reconnecting'
         */
        getState() {
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
        send(data, options = {}) {
            if (!native.ble) {
                throw new Error('BLE API not available');
            }
            const dataJson = typeof data === 'string' ? data : JSON.stringify(data);
            const optionsJson = JSON.stringify({
                to: options.to || null
            });
            const response = JSON.parse(native.ble.send(dataJson, optionsJson));
            if (!response.success) {
                throw new Error(response.error || 'Failed to send message');
            }
        },
        // ==================== Events ====================
        /**
         * Register a handler for BLE events.
         */
        on(event, handler) {
            if (this._handlers[event]) {
                this._handlers[event].push(handler);
                this._ensureGlobalListener();
            }
        },
        /**
         * Remove a BLE event handler.
         */
        off(event, handler) {
            if (this._handlers[event]) {
                const idx = this._handlers[event].indexOf(handler);
                if (idx !== -1) {
                    this._handlers[event].splice(idx, 1);
                }
            }
        },
        // ==================== Internal ====================
        _ensureGlobalListener() {
            if (this._globalListener)
                return;
            // Map event types to handler keys
            const eventMap = {
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
                window.addEventListener(eventType, (e) => {
                    const handlers = this._handlers[handlerKey];
                    if (handlers) {
                        for (const h of handlers) {
                            try {
                                h(e.detail);
                            }
                            catch (err) {
                                console.error(`[Loop.ble] Handler error for ${handlerKey}:`, err);
                            }
                        }
                    }
                });
            });
            this._globalListener = true;
        }
    };
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
        setItem(key, value) {
            if (!native.storage) {
                return { success: false, error: 'Storage not available' };
            }
            try {
                return JSON.parse(native.storage.setItem(key, value));
            }
            catch (e) {
                return { success: false, error: e.message };
            }
        },
        /**
         * Retrieve a value.
         * @param key - Key name
         * @returns The stored value, or null if not found
         */
        getItem(key) {
            if (!native.storage)
                return null;
            try {
                return native.storage.getItem(key);
            }
            catch (e) {
                return null;
            }
        },
        /**
         * Remove a key.
         */
        removeItem(key) {
            if (!native.storage) {
                return { success: false, error: 'Storage not available' };
            }
            try {
                return JSON.parse(native.storage.removeItem(key));
            }
            catch (e) {
                return { success: false, error: e.message };
            }
        },
        /**
         * Remove all keys for this game.
         */
        clear() {
            if (!native.storage) {
                return { success: false, error: 'Storage not available' };
            }
            try {
                return JSON.parse(native.storage.clear());
            }
            catch (e) {
                return { success: false, error: e.message };
            }
        },
        /**
         * Get all key names.
         */
        keys() {
            if (!native.storage)
                return [];
            try {
                return JSON.parse(native.storage.keys());
            }
            catch (e) {
                return [];
            }
        },
        /**
         * Get storage usage in bytes.
         */
        getUsage() {
            if (!native.storage) {
                return { used: 0, quota: 0 };
            }
            try {
                return JSON.parse(native.storage.getUsage());
            }
            catch (e) {
                return { used: 0, quota: 0 };
            }
        },
        /**
         * Check if a key exists without reading its value.
         * More efficient than getItem() !== null for large values.
         */
        exists(key) {
            if (!native.storage)
                return false;
            try {
                return native.storage.exists(key);
            }
            catch (e) {
                return false;
            }
        }
    };
    const SystemAPI = {
        isFreeRotateEnabled() {
            if (!native.system)
                return false;
            try {
                const result = JSON.parse(native.system.isFreeRotateEnabled());
                return result.enabled;
            }
            catch {
                return false;
            }
        },
        setFreeRotate(enabled) {
            if (!native.system)
                return { success: false };
            try {
                return JSON.parse(native.system.setFreeRotate(enabled));
            }
            catch {
                return { success: false };
            }
        },
        on(event, handler) {
            window.addEventListener(`loop:${event}`, ((e) => {
                handler(e.detail);
            }));
        },
        off(event, handler) {
            window.removeEventListener(`loop:${event}`, handler);
        }
    };
    // ==================== Main Loop SDK Object ====================
    const Loop = {
        /**
         * Check if the Loop SDK is available.
         */
        isAvailable() {
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
        get version() {
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
    window.Loop = Loop;
    // Dispatch ready event
    window.dispatchEvent(new CustomEvent('loop:ready', { detail: { version: Loop.version } }));

})();
