package com.dopple.webview.ui.scanner

/**
 * State machine for camera lifecycle management.
 * Extracted for testability - CameraManager delegates to this for state transitions.
 *
 * States:
 * - IDLE: Camera not bound
 * - PREPARING: Camera binding in progress
 * - READY: Camera bound but preview not yet streaming
 * - STREAMING: Preview is actively rendering frames (safe to show)
 * - VISIBLE: Camera visible to user
 */
class CameraManagerState {
    enum class State { IDLE, PREPARING, READY, STREAMING, VISIBLE }

    var currentState: State = State.IDLE
        private set

    private var readyCallback: (() -> Unit)? = null

    /**
     * Start camera preparation.
     * @return true if transition occurred, false if already preparing/ready
     */
    fun prepareDuringHold(): Boolean {
        return if (currentState == State.IDLE) {
            currentState = State.PREPARING
            true
        } else {
            false
        }
    }

    /**
     * Camera binding completed successfully.
     */
    fun onCameraReady() {
        if (currentState == State.PREPARING) {
            currentState = State.READY
        }
    }

    /**
     * Preview is actively streaming frames.
     */
    fun onPreviewStreaming() {
        if (currentState == State.READY) {
            currentState = State.STREAMING
            readyCallback?.invoke()
            readyCallback = null
        }
    }

    /**
     * Show camera to user.
     * @return true if transition occurred, false if not ready
     */
    fun showCamera(): Boolean {
        return if (currentState == State.STREAMING) {
            currentState = State.VISIBLE
            true
        } else {
            false
        }
    }

    /**
     * Hide camera and release resources.
     */
    fun hideCamera() {
        currentState = State.IDLE
        readyCallback = null
    }

    /**
     * Check if camera is bound and ready for display (legacy - use isPreviewStreaming).
     */
    fun isCameraReady(): Boolean = currentState == State.READY || currentState == State.STREAMING || currentState == State.VISIBLE

    /**
     * Check if preview is actively streaming frames (safe to show without black screen).
     */
    fun isPreviewStreaming(): Boolean = currentState == State.STREAMING || currentState == State.VISIBLE

    /**
     * Reset to initial state.
     */
    fun reset() {
        currentState = State.IDLE
        readyCallback = null
    }

    /**
     * Register a callback that fires when state reaches STREAMING.
     * If already STREAMING or VISIBLE, fires immediately.
     * Cleared on hideCamera() or reset().
     */
    fun onReady(callback: () -> Unit) {
        if (currentState == State.STREAMING || currentState == State.VISIBLE) {
            callback()
        } else {
            readyCallback = callback
        }
    }
}
