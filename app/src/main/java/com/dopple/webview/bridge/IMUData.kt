package com.dopple.webview.bridge

/**
 * Data classes for IMU event payloads.
 * Matches the event format specified in design.md.
 */

/**
 * 3D vector for accelerometer, gyroscope, and gravity data.
 */
data class Vector3(
    val x: Float,
    val y: Float,
    val z: Float
) {
    /**
     * Converts to JSON object string.
     */
    fun toJson(): String = """{"x":$x,"y":$y,"z":$z}"""
}

/**
 * Quaternion for orientation data (x, y, z, w).
 */
data class Quaternion(
    val x: Float,
    val y: Float,
    val z: Float,
    val w: Float
) {
    /**
     * Converts to JSON object string.
     */
    fun toJson(): String = """{"x":$x,"y":$y,"z":$z,"w":$w}"""
}

/**
 * Complete IMU data payload matching the Unity reference format.
 *
 * @param gravity Raw gravity vector from accelerometer (m/s^2)
 * @param smoothGravity EMA-smoothed gravity vector (m/s^2)
 * @param delta Angular velocity from gyroscope (rad/s)
 * @param orientation Device orientation as quaternion
 * @param timestamp DOMHighResTimeStamp in milliseconds
 * @param sensorTimestamp Raw sensor timestamp in nanoseconds
 * @param sequenceNumber Monotonically increasing event counter
 */
data class IMUData(
    val gravity: Vector3,
    val smoothGravity: Vector3,
    val delta: Vector3,
    val orientation: Quaternion,
    val timestamp: Double,
    val sensorTimestamp: Long,
    val sequenceNumber: Long
) {
    /**
     * Converts to JSON string for JavaScript dispatch.
     * Uses manual string building for performance (avoids JSON library overhead).
     */
    fun toJson(): String = buildString {
        append("{")
        append("\"gravity\":${gravity.toJson()},")
        append("\"smoothGravity\":${smoothGravity.toJson()},")
        append("\"delta\":${delta.toJson()},")
        append("\"orientation\":${orientation.toJson()},")
        append("\"timestamp\":$timestamp,")
        append("\"sensorTimestamp\":$sensorTimestamp,")
        append("\"sequenceNumber\":$sequenceNumber")
        append("}")
    }
}
