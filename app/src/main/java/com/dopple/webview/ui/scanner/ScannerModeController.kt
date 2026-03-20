package com.dopple.webview.ui.scanner

/**
 * State machine controlling Gallery ↔ Scanner mode transitions.
 *
 * Encapsulates the mode switching logic to keep GalleryFragment focused
 * on UI concerns while making the state machine testable.
 */
class ScannerModeController(
    private val onPrepareCamera: () -> Unit,
    private val onShowCamera: () -> Unit,
    private val onHideCamera: () -> Unit,
    private val onShowGallery: () -> Unit,
    private val onHideGallery: () -> Unit
) {
    enum class Mode { GALLERY, SCANNER }

    var currentMode: Mode = Mode.GALLERY
        private set

    private var cameraPreparationTriggered = false

    /**
     * Called during hold gesture with progress 0.0 to 1.0.
     * At 20% progress, triggers camera preparation.
     */
    fun onHoldProgress(progress: Float) {
        if (currentMode != Mode.GALLERY) return

        if (progress >= 0.2f && !cameraPreparationTriggered) {
            cameraPreparationTriggered = true
            onPrepareCamera()
        }
    }

    /**
     * Called when hold gesture is cancelled.
     * Resets preparation state so next hold will prepare again.
     */
    fun onHoldCancelled() {
        cameraPreparationTriggered = false
    }

    /**
     * Activate scanner mode - hides gallery, shows camera.
     */
    fun activateScannerMode() {
        if (currentMode == Mode.SCANNER) return

        currentMode = Mode.SCANNER
        onHideGallery()
        onShowCamera()
    }

    /**
     * Deactivate scanner mode - hides camera, shows gallery.
     */
    fun deactivateScannerMode() {
        if (currentMode == Mode.GALLERY) return

        currentMode = Mode.GALLERY
        cameraPreparationTriggered = false
        onHideCamera()
        onShowGallery()
    }

    /**
     * Handle tap event.
     * @return true if handled (scanner mode exits), false if not handled
     */
    fun onTap(): Boolean {
        return if (currentMode == Mode.SCANNER) {
            deactivateScannerMode()
            true
        } else {
            false
        }
    }
}
