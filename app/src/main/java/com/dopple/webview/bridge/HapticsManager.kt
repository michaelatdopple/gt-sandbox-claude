package com.dopple.webview.bridge

import android.content.Context
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import org.json.JSONObject

/**
 * Manages haptic feedback for the Android-WebView bridge.
 * Supports single-pulse and curve-based vibration patterns.
 *
 * Uses VibrationEffect API (Android 8.0+) with amplitude control
 * when available, with fallback to duration-based simulation.
 */
class HapticsManager(private val context: Context) {

    companion object {
        private const val TAG = "HapticsManager"
        private const val TAG_LATENCY = "HapticsLatency"

        // Default duration for single pulse in ms
        private const val DEFAULT_PULSE_DURATION_MS = 100L

        // Sample interval for curve playback in ms
        private const val CURVE_SAMPLE_INTERVAL_MS = 50L
    }

    private val vibrator = (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
        ?.defaultVibrator

    /**
     * Checks if haptic feedback is available on this device.
     */
    fun isAvailable(): Boolean = vibrator?.hasVibrator() == true

    /**
     * Checks if the device supports amplitude control for variable intensity.
     */
    fun hasAmplitudeControl(): Boolean {
        return vibrator?.hasAmplitudeControl() == true
    }

    /**
     * Returns haptics capability status as JSON.
     */
    fun getStatus(): String {
        return """{"available":${isAvailable()},"hasAmplitudeSupport":${hasAmplitudeControl()}}"""
    }

    /**
     * Triggers a single haptic pulse with configurable intensity.
     *
     * @param intensity Vibration intensity from 0.0 to 1.0
     * @return JSON result with success status
     */
    fun triggerOne(intensity: Float): String {
        val startTimeNs = System.nanoTime()
        val clampedIntensity = intensity.coerceIn(0f, 1f)

        if (vibrator == null || !isAvailable()) {
            return """{"success":false,"error":"Haptics not available"}"""
        }

        try {
            if (hasAmplitudeControl()) {
                // Use amplitude-controlled vibration
                val amplitude = (clampedIntensity * 255).toInt().coerceIn(1, 255)
                val effect = VibrationEffect.createOneShot(DEFAULT_PULSE_DURATION_MS, amplitude)
                vibrator.vibrate(effect)
            } else {
                // Use default amplitude
                val effect = VibrationEffect.createOneShot(DEFAULT_PULSE_DURATION_MS, VibrationEffect.DEFAULT_AMPLITUDE)
                vibrator.vibrate(effect)
            }

            val latencyMs = (System.nanoTime() - startTimeNs) / 1_000_000.0
            Log.d(TAG_LATENCY, "triggerOne latency: ${String.format("%.2f", latencyMs)}ms")

            return """{"success":true}"""
        } catch (e: Exception) {
            Log.e(TAG, "Error triggering haptic", e)
            return """{"success":false,"error":"${e.message}"}"""
        }
    }

    /**
     * Plays a curve-based haptic pattern using keyframes.
     *
     * @param curveJson JSON string with format: {"keys":[{"time":0,"value":0.5},...],"strength":1.0}
     * @return JSON result with success status
     */
    fun playCurve(curveJson: String): String {
        val startTimeNs = System.nanoTime()

        if (vibrator == null || !isAvailable()) {
            return """{"success":false,"error":"Haptics not available"}"""
        }

        try {
            val json = JSONObject(curveJson)
            val keysArray = json.getJSONArray("keys")
            val strength = json.optDouble("strength", 1.0).toFloat().coerceIn(0f, 1f)

            if (keysArray.length() < 2) {
                return """{"success":false,"error":"At least 2 keyframes required"}"""
            }

            // Parse keyframes
            val keyframes = mutableListOf<Pair<Float, Float>>()
            for (i in 0 until keysArray.length()) {
                val keyframe = keysArray.getJSONObject(i)
                val time = keyframe.getDouble("time").toFloat()
                val value = keyframe.getDouble("value").toFloat().coerceIn(0f, 1f)
                keyframes.add(time to value)
            }

            // Sort by time
            keyframes.sortBy { it.first }

            // Calculate duration and sample count
            val durationMs = (keyframes.last().first * 1000).toLong()
            if (durationMs <= 0) {
                return """{"success":false,"error":"Invalid curve duration"}"""
            }

            val sampleCount = (durationMs / CURVE_SAMPLE_INTERVAL_MS).toInt().coerceAtLeast(1)

            // Build waveform
            val timings = LongArray(sampleCount) { CURVE_SAMPLE_INTERVAL_MS }
            val amplitudes = IntArray(sampleCount) { i ->
                val t = i * CURVE_SAMPLE_INTERVAL_MS / 1000f
                val value = interpolateCurve(keyframes, t) * strength
                if (hasAmplitudeControl()) {
                    (value * 255).toInt().coerceIn(0, 255)
                } else {
                    if (value > 0.1f) VibrationEffect.DEFAULT_AMPLITUDE else 0
                }
            }

            val effect = VibrationEffect.createWaveform(timings, amplitudes, -1)
            vibrator.vibrate(effect)

            val latencyMs = (System.nanoTime() - startTimeNs) / 1_000_000.0
            Log.d(TAG_LATENCY, "playCurve latency: ${String.format("%.2f", latencyMs)}ms")

            return """{"success":true,"durationMs":$durationMs}"""
        } catch (e: Exception) {
            Log.e(TAG, "Error playing haptic curve", e)
            return """{"success":false,"error":"${e.message}"}"""
        }
    }

    /**
     * Interpolates value at time t from keyframes using linear interpolation.
     */
    private fun interpolateCurve(keyframes: List<Pair<Float, Float>>, t: Float): Float {
        if (keyframes.isEmpty()) return 0f
        if (t <= keyframes.first().first) return keyframes.first().second
        if (t >= keyframes.last().first) return keyframes.last().second

        // Find surrounding keyframes
        var prevIndex = 0
        for (i in keyframes.indices) {
            if (keyframes[i].first <= t) {
                prevIndex = i
            } else {
                break
            }
        }

        val prev = keyframes[prevIndex]
        val next = keyframes.getOrElse(prevIndex + 1) { prev }

        if (prev.first == next.first) return prev.second

        // Linear interpolation
        val ratio = (t - prev.first) / (next.first - prev.first)
        return prev.second + (next.second - prev.second) * ratio
    }

    /**
     * Stops any ongoing vibration.
     */
    fun stop(): String {
        return try {
            vibrator?.cancel()
            """{"success":true}"""
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping haptic", e)
            """{"success":false,"error":"${e.message}"}"""
        }
    }
}
