package com.dopple.webview.bridge

import android.app.Activity
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.webkit.WebView
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Manages IMU sensor streaming with W3C-aligned event payloads.
 *
 * Fires two events:
 * - `loop:orientation` — mirrors DeviceOrientationEvent (alpha, beta, gamma, absolute)
 * - `loop:motion` — mirrors DeviceMotionEvent + gravity enhancement
 *
 * Features:
 * - 5 sensors: GAME_ROTATION_VECTOR, ACCELEROMETER, LINEAR_ACCELERATION, GYROSCOPE, GRAVITY
 * - Quaternion → Euler conversion ported from Chromium's orientation_util.cc
 * - Configurable publish frequency (1-240 Hz)
 * - setSensorFusion() hot-swap between game/full rotation vector
 * - Dedicated sensor thread (IMUSensorThread)
 * - Screen-orientation compensation intentionally omitted for free-rotate stability
 */
class IMUSensorManager(
    private val context: Context,
    webView: WebView,
    activity: Activity
) : SensorEventListener {

    companion object {
        private const val TAG = "IMUSensorManager"
        private const val TAG_LATENCY = "IMULatency"

        // Frequency bounds
        private const val MIN_FREQUENCY_HZ = 1
        private const val MAX_FREQUENCY_HZ = 240
        private const val DEFAULT_FREQUENCY_HZ = 60

        // Euler conversion constants
        private const val EPSILON = 1e-6
        private const val RAD_TO_DEG = 180.0 / Math.PI
    }

    // Weak references to prevent memory leaks
    private val webViewRef = WeakReference(webView)
    private val activityRef = WeakReference(activity)

    // Android sensor system
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    // 5 sensors
    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val linearAcceleration: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val gravitySensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private var orientationSensorType = Sensor.TYPE_GAME_ROTATION_VECTOR
    private var rotationVector: Sensor? = sensorManager.getDefaultSensor(orientationSensorType)
        ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) // fallback

    // Dedicated handler thread for sensor callbacks
    private var sensorThread: HandlerThread? = null
    private var sensorHandler: Handler? = null

    // Subscription management
    private val subscriptions = ConcurrentHashMap<String, Boolean>()

    // Sequence number for event ordering
    private val sequenceNumber = AtomicLong(0)

    // Frequency control
    @Volatile private var publishIntervalMs = 1000L / DEFAULT_FREQUENCY_HZ
    @Volatile private var lastPublishTime = 0L
    @Volatile private var lastIntervalMs = 1000.0 / DEFAULT_FREQUENCY_HZ

    // Latest sensor data (thread-safe via volatile)
    @Volatile private var latestAccel = floatArrayOf(0f, 0f, 0f)
    @Volatile private var latestLinearAccel = floatArrayOf(0f, 0f, 0f)
    @Volatile private var latestGyro = floatArrayOf(0f, 0f, 0f)
    @Volatile private var latestGravity = floatArrayOf(0f, 0f, -9.81f)
    @Volatile private var latestQuaternion = floatArrayOf(0f, 0f, 0f, 1f) // x, y, z, w
    @Volatile private var latestSensorTimestamp = 0L

    // Pause state
    @Volatile private var isPaused = false

    /**
     * Subscribes to IMU events.
     * @return Unique subscription ID
     */
    fun subscribe(): String {
        val id = UUID.randomUUID().toString()
        subscriptions[id] = true

        if (subscriptions.size == 1) {
            startSensors()
        }

        Log.d(TAG, "Subscribed: $id (total: ${subscriptions.size})")
        return id
    }

    /**
     * Unsubscribes from IMU events.
     * @param id Subscription ID returned from subscribe()
     * @return true if subscription was removed
     */
    fun unsubscribe(id: String): Boolean {
        val removed = subscriptions.remove(id) != null

        if (subscriptions.isEmpty()) {
            stopSensors()
        }

        Log.d(TAG, "Unsubscribed: $id (removed: $removed, remaining: ${subscriptions.size})")
        return removed
    }

    /**
     * Sets the IMU publish frequency.
     * @param hz Frequency in Hz (clamped to 1-240)
     * @return Actual frequency after clamping
     */
    fun setFrequency(hz: Int): Int {
        val clampedHz = hz.coerceIn(MIN_FREQUENCY_HZ, MAX_FREQUENCY_HZ)
        publishIntervalMs = 1000L / clampedHz
        Log.d(TAG, "Frequency set to $clampedHz Hz (interval: ${publishIntervalMs}ms)")
        return clampedHz
    }

    /**
     * Switches the orientation sensor type.
     * @param type "game" for TYPE_GAME_ROTATION_VECTOR (6-axis) or "full" for TYPE_ROTATION_VECTOR (9-axis)
     * @return true if switch succeeded
     */
    fun setSensorFusion(type: String): Boolean {
        val sensorType = when (type) {
            "game" -> Sensor.TYPE_GAME_ROTATION_VECTOR
            "full" -> Sensor.TYPE_ROTATION_VECTOR
            else -> return false
        }
        if (sensorType == orientationSensorType) return true

        // Hot-swap: unregister current orientation sensor, register new one
        rotationVector?.let { sensorManager.unregisterListener(this, it) }

        orientationSensorType = sensorType
        rotationVector = sensorManager.getDefaultSensor(sensorType)

        val handler = sensorHandler
        if (handler != null && subscriptions.isNotEmpty()) {
            rotationVector?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST, handler)
            } ?: return false
        }

        Log.d(TAG, "Sensor fusion switched to $type (type=$sensorType)")
        return rotationVector != null
    }

    /**
     * Returns current IMU status.
     */
    fun getStatus(): String {
        val frequencyHz = 1000 / publishIntervalMs
        return buildString {
            append("{")
            append("\"active\":${subscriptions.isNotEmpty()},")
            append("\"subscriptions\":${subscriptions.size},")
            append("\"frequencyHz\":$frequencyHz,")
            append("\"paused\":$isPaused")
            append("}")
        }
    }

    /**
     * Returns the latest orientation data without requiring subscription.
     */
    fun getLatest(): String? {
        if (latestSensorTimestamp == 0L) return null
        return buildOrientationData().toJson()
    }

    /**
     * Returns sensor availability status.
     */
    fun getSensorAvailability(): String {
        return buildString {
            append("{")
            append("\"accelerometer\":${accelerometer != null},")
            append("\"gyroscope\":${gyroscope != null},")
            append("\"rotationVector\":${rotationVector != null},")
            append("\"linearAcceleration\":${linearAcceleration != null},")
            append("\"gravity\":${gravitySensor != null}")
            append("}")
        }
    }

    /**
     * Pauses sensor streaming (for lifecycle management).
     */
    fun pause() {
        if (isPaused) return
        isPaused = true

        if (subscriptions.isNotEmpty()) {
            unregisterSensors()
            Log.d(TAG, "Paused sensor streaming")
        }
    }

    /**
     * Resumes sensor streaming after pause.
     */
    fun resume() {
        if (!isPaused) return
        isPaused = false

        if (subscriptions.isNotEmpty()) {
            registerSensors()
            Log.d(TAG, "Resumed sensor streaming")
        }
    }

    /**
     * Cleans up all resources.
     */
    fun cleanup() {
        subscriptions.clear()
        stopSensors()
        webViewRef.clear()
        activityRef.clear()
        Log.d(TAG, "Cleanup complete")
    }

    // --- SensorEventListener implementation ---

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(event.values, 0, latestAccel, 0, 3)
                latestSensorTimestamp = event.timestamp
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                System.arraycopy(event.values, 0, latestLinearAccel, 0, 3)
            }
            Sensor.TYPE_GYROSCOPE -> {
                System.arraycopy(event.values, 0, latestGyro, 0, 3)
            }
            Sensor.TYPE_GRAVITY -> {
                System.arraycopy(event.values, 0, latestGravity, 0, 3)
            }
            Sensor.TYPE_GAME_ROTATION_VECTOR,
            Sensor.TYPE_ROTATION_VECTOR -> {
                updateQuaternionFromRotationVector(event.values)
            }
        }

        // Rate-limited dispatch
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - lastPublishTime
        if (elapsed >= publishIntervalMs && subscriptions.isNotEmpty() && !isPaused) {
            lastIntervalMs = elapsed.toDouble()
            lastPublishTime = now
            dispatchEvents()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        Log.d(TAG, "Sensor accuracy changed: ${sensor?.name} -> $accuracy")
    }

    // --- Private methods ---

    private fun startSensors() {
        if (sensorThread == null) {
            sensorThread = HandlerThread("IMUSensorThread").apply { start() }
            sensorHandler = Handler(sensorThread!!.looper)
        }
        registerSensors()
        Log.d(TAG, "Sensors started")
    }

    private fun stopSensors() {
        unregisterSensors()
        sensorThread?.quitSafely()
        sensorThread = null
        sensorHandler = null
        Log.d(TAG, "Sensors stopped")
    }

    private fun registerSensors() {
        val handler = sensorHandler ?: return

        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST, handler)
        }
        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST, handler)
        }
        linearAcceleration?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST, handler)
        }
        gravitySensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST, handler)
        }
        rotationVector?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST, handler)
        }

        Log.d(TAG, "Sensors registered (5 types)")
    }

    private fun unregisterSensors() {
        sensorManager.unregisterListener(this)
        Log.d(TAG, "Sensors unregistered")
    }

    private fun updateQuaternionFromRotationVector(rotationVector: FloatArray) {
        // Rotation vector format: x, y, z, [w], [heading accuracy]
        if (rotationVector.size >= 4) {
            latestQuaternion[0] = rotationVector[0] // x
            latestQuaternion[1] = rotationVector[1] // y
            latestQuaternion[2] = rotationVector[2] // z
            latestQuaternion[3] = rotationVector[3] // w
        } else if (rotationVector.size >= 3) {
            val x = rotationVector[0]
            val y = rotationVector[1]
            val z = rotationVector[2]
            latestQuaternion[0] = x
            latestQuaternion[1] = y
            latestQuaternion[2] = z
            val wSquared = 1f - x * x - y * y - z * z
            latestQuaternion[3] = if (wSquared > 0) sqrt(wSquared) else 0f
        }
    }

    /**
     * Converts quaternion to Euler angles (alpha, beta, gamma) matching W3C DeviceOrientationEvent.
     *
     * Port of Chromium's orientation_util.cc quaternion → rotation matrix → Euler angles
     * using Z-X'-Y'' intrinsic Tait-Bryan decomposition.
     *
     * Screen-orientation compensation is intentionally omitted for free-rotate stability.
     */
    private fun quaternionToEuler(qx: Float, qy: Float, qz: Float, qw: Float): DoubleArray {
        // Quaternion to rotation matrix
        val sqx = qx.toDouble() * qx
        val sqy = qy.toDouble() * qy
        val sqz = qz.toDouble() * qz
        val sqw = qw.toDouble() * qw

        val m11 = sqw + sqx - sqy - sqz
        val m12 = 2.0 * (qx * qy - qw * qz)
        val m13 = 2.0 * (qx * qz + qw * qy)
        val m21 = 2.0 * (qx * qy + qw * qz)
        val m22 = sqw - sqx + sqy - sqz
        val m23 = 2.0 * (qy * qz - qw * qx)
        val m31 = 2.0 * (qx * qz - qw * qy)
        val m32 = 2.0 * (qy * qz + qw * qx)
        val m33 = sqw - sqx - sqy + sqz

        // Z-X'-Y'' Tait-Bryan angles (matching W3C spec)
        // beta = asin(-m32)
        // alpha = atan2(m31, m33)  — when cos(beta) != 0
        // gamma = atan2(m12, m22)  — when cos(beta) != 0

        val sinBeta = -m32
        val beta: Double
        val alpha: Double
        val gamma: Double

        if (abs(sinBeta) < 1.0 - EPSILON) {
            // Normal case
            beta = asin(sinBeta.coerceIn(-1.0, 1.0)) * RAD_TO_DEG
            alpha = atan2(m31, m33) * RAD_TO_DEG
            gamma = atan2(m12, m22) * RAD_TO_DEG
        } else {
            // Gimbal lock: cos(beta) ≈ 0
            beta = if (sinBeta > 0) 90.0 else -90.0
            alpha = atan2(-m13, m11) * RAD_TO_DEG
            gamma = 0.0
        }

        // Normalize alpha to [0, 360)
        val normalizedAlpha = ((alpha % 360.0) + 360.0) % 360.0

        return doubleArrayOf(normalizedAlpha, beta, gamma)
    }

    private fun buildOrientationData(): OrientationData {
        val euler = quaternionToEuler(
            latestQuaternion[0], latestQuaternion[1],
            latestQuaternion[2], latestQuaternion[3]
        )
        val isAbsolute = orientationSensorType == Sensor.TYPE_ROTATION_VECTOR
        return OrientationData(
            alpha = euler[0],
            beta = euler[1],
            gamma = euler[2],
            absolute = isAbsolute
        )
    }

    private fun buildMotionEventData(): MotionEventData {
        return MotionEventData(
            accelerationIncludingGravity = Vector3(latestAccel[0], latestAccel[1], latestAccel[2]),
            acceleration = Vector3(latestLinearAccel[0], latestLinearAccel[1], latestLinearAccel[2]),
            rotationRate = RotationRate(
                // Gyroscope gives rad/s, W3C spec uses deg/s
                alpha = (latestGyro[2] * RAD_TO_DEG),  // Z-axis → alpha
                beta = (latestGyro[0] * RAD_TO_DEG),   // X-axis → beta
                gamma = (latestGyro[1] * RAD_TO_DEG)   // Y-axis → gamma
            ),
            interval = lastIntervalMs,
            gravity = Vector3(latestGravity[0], latestGravity[1], latestGravity[2])
        )
    }

    private fun dispatchEvents() {
        val startTimeNs = System.nanoTime()
        val activity = activityRef.get() ?: return
        val webView = webViewRef.get() ?: return

        val seq = sequenceNumber.incrementAndGet()
        val orientationData = buildOrientationData()
        val motionData = buildMotionEventData()

        val script = buildString {
            // Dispatch loop:orientation (mirrors DeviceOrientationEvent)
            append("window.dispatchEvent(new CustomEvent('${BridgeEventTypes.ORIENTATION_EVENT}',")
            append("{detail:${orientationData.toJson()}}));")
            // Dispatch loop:motion (mirrors DeviceMotionEvent + gravity)
            append("window.dispatchEvent(new CustomEvent('${BridgeEventTypes.MOTION_EVENT}',")
            append("{detail:${motionData.toJson()}}));")
        }

        activity.runOnUiThread {
            webView.evaluateJavascript(script) { _ ->
                val latencyMs = (System.nanoTime() - startTimeNs) / 1_000_000.0
                if (seq % 100 == 0L) {
                    Log.d(TAG_LATENCY, "IMU dispatch latency: ${String.format("%.2f", latencyMs)}ms (seq: $seq)")
                }
            }
        }
    }
}
