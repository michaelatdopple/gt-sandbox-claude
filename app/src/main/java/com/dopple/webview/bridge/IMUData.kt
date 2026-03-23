package com.dopple.webview.bridge

/**
 * Data classes for IMU event payloads.
 * W3C DeviceOrientationEvent / DeviceMotionEvent aligned shapes.
 */

/**
 * 3D vector for accelerometer, gyroscope, and gravity data.
 */
data class Vector3(
    val x: Float,
    val y: Float,
    val z: Float
) {
    fun toJson(): String = """{"x":$x,"y":$y,"z":$z}"""
}

/**
 * Rotation rate with alpha/beta/gamma matching W3C DeviceMotionEvent.rotationRate.
 * Values are in degrees per second.
 */
data class RotationRate(
    val alpha: Double,
    val beta: Double,
    val gamma: Double
) {
    fun toJson(): String = """{"alpha":$alpha,"beta":$beta,"gamma":$gamma}"""
}

/**
 * Orientation data matching W3C DeviceOrientationEvent.
 * Dispatched as 'loop:orientation' event.
 *
 * @param alpha Rotation around Z axis (0..360)
 * @param beta Rotation around X axis (-180..180)
 * @param gamma Rotation around Y axis (-90..90)
 * @param absolute true if orientation is relative to Earth's coordinate frame
 */
data class OrientationData(
    val alpha: Double,
    val beta: Double,
    val gamma: Double,
    val absolute: Boolean
) {
    fun toJson(): String = buildString {
        append("{")
        append("\"alpha\":$alpha,")
        append("\"beta\":$beta,")
        append("\"gamma\":$gamma,")
        append("\"absolute\":$absolute")
        append("}")
    }
}

/**
 * Motion event data matching W3C DeviceMotionEvent + gravity enhancement.
 * Dispatched as 'loop:motion' event.
 *
 * @param accelerationIncludingGravity Raw accelerometer (TYPE_ACCELEROMETER)
 * @param acceleration Linear acceleration without gravity (TYPE_LINEAR_ACCELERATION)
 * @param rotationRate Gyroscope angular velocity in deg/s (TYPE_GYROSCOPE)
 * @param interval Milliseconds between samples
 * @param gravity Clean gravity vector from HAL (TYPE_GRAVITY) — enhancement over W3C
 */
data class MotionEventData(
    val accelerationIncludingGravity: Vector3,
    val acceleration: Vector3,
    val rotationRate: RotationRate,
    val interval: Double,
    val gravity: Vector3
) {
    fun toJson(): String = buildString {
        append("{")
        append("\"accelerationIncludingGravity\":${accelerationIncludingGravity.toJson()},")
        append("\"acceleration\":${acceleration.toJson()},")
        append("\"rotationRate\":${rotationRate.toJson()},")
        append("\"interval\":$interval,")
        append("\"gravity\":${gravity.toJson()}")
        append("}")
    }
}
