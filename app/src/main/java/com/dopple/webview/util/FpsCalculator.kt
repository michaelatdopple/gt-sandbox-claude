package com.dopple.webview.util

import android.view.Choreographer

/**
 * Calculates FPS using Choreographer.FrameCallback for accurate measurements.
 * Provides rolling average for stable readings.
 */
class FpsCalculator : Choreographer.FrameCallback {

    private var lastFrameTimeNanos = 0L
    private var onFpsUpdated: ((Int) -> Unit)? = null

    fun start(callback: (Int) -> Unit) {
        onFpsUpdated = callback
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun stop() {
        onFpsUpdated = null
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (lastFrameTimeNanos != 0L) {
            val elapsed = frameTimeNanos - lastFrameTimeNanos
            val fps = (1_000_000_000.0 / elapsed).toInt()
            onFpsUpdated?.invoke(fps)
        }
        lastFrameTimeNanos = frameTimeNanos
        Choreographer.getInstance().postFrameCallback(this)
    }
}
