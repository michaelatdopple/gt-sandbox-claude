package com.dopple.webview.ui.scanner

import android.content.Context
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

/**
 * Manages camera lifecycle for zero-latency scanner mode.
 *
 * Camera binds during hold gesture (at 20% progress) to hidden PreviewView.
 * When hold completes, visibility swaps instantly - camera already rendering.
 */
class CameraManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val scannerOverlay: View,
    private val statusText: TextView,
    private val loadingIndicator: ProgressBar
) {
    companion object {
        private const val TAG = "CameraManager"
    }

    private val state = CameraManagerState()
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var barcodeScanner: BarcodeScanner? = null
    private var onQrDetected: ((String) -> Unit)? = null
    private var isDetectionActive = false
    private var streamStateObserver: Observer<PreviewView.StreamState>? = null

    /**
     * Pre-warm camera provider on Gallery load.
     * This caches the provider so subsequent calls are instant.
     */
    fun prewarm() {
        Log.d(TAG, "Pre-warming camera provider")
        val startTime = System.currentTimeMillis()
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                cameraProvider = future.get()
                val elapsed = System.currentTimeMillis() - startTime
                Log.d(TAG, "Camera provider pre-warmed in ${elapsed}ms")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to pre-warm camera provider", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Called at 20% hold progress. Binds camera but keeps views hidden.
     * Camera provider should already be pre-warmed, so this should be fast.
     */
    fun prepareDuringHold() {
        if (!state.prepareDuringHold()) return

        Log.d(TAG, "Preparing camera during hold")
        val startTime = System.currentTimeMillis()

        // If already pre-warmed, this is instant
        if (cameraProvider != null) {
            bindCameraPreview()
            state.onCameraReady()
            val elapsed = System.currentTimeMillis() - startTime
            Log.d(TAG, "Camera bound in ${elapsed}ms (pre-warmed)")
            return
        }

        // Fallback: get provider now (slow on cold start)
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                cameraProvider = future.get()
                bindCameraPreview()
                state.onCameraReady()
                val elapsed = System.currentTimeMillis() - startTime
                Log.d(TAG, "Camera bound in ${elapsed}ms (cold start)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bind camera", e)
                state.reset()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindCameraPreview() {
        val provider = cameraProvider ?: return

        provider.unbindAll()

        // Make PreviewView visible so it can actually render frames
        // It's behind the opaque Gallery fragment, so user won't see it yet
        previewView.visibility = View.VISIBLE
        Log.d(TAG, "PreviewView set to VISIBLE for streaming")

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        provider.bindToLifecycle(lifecycleOwner, cameraSelector, preview)

        // Observe stream state to know when preview is actually rendering
        observeStreamState()
    }

    /**
     * Observe PreviewView stream state to detect when frames are being rendered.
     * This is more reliable than just checking if camera is bound.
     */
    private fun observeStreamState() {
        // Remove any existing observer
        streamStateObserver?.let {
            previewView.previewStreamState.removeObserver(it)
        }

        streamStateObserver = Observer { streamState ->
            Log.d(TAG, "Preview stream state: $streamState")
            if (streamState == PreviewView.StreamState.STREAMING) {
                state.onPreviewStreaming()
            }
        }

        previewView.previewStreamState.observe(lifecycleOwner, streamStateObserver!!)
    }

    /**
     * Called at 100% hold. Shows camera and scanner UI.
     * PreviewView is already visible from prepareDuringHold() - we just show the overlay.
     */
    fun showCameraLayer() {
        if (!state.showCamera()) {
            Log.w(TAG, "Cannot show camera - not ready (state: ${state.currentState})")
            return
        }

        Log.d(TAG, "Showing camera layer - preview already streaming")
        // PreviewView is already VISIBLE from prepareDuringHold()
        scannerOverlay.visibility = View.VISIBLE
        statusText.text = context.getString(com.dopple.webview.R.string.scanner_status_hint)
        loadingIndicator.visibility = View.GONE
    }

    /**
     * Start QR detection after camera is visible.
     */
    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    fun startQrDetection(callback: (String) -> Unit) {
        if (isDetectionActive) return
        onQrDetected = callback
        isDetectionActive = true

        Log.d(TAG, "Starting QR detection")

        // Initialize barcode scanner
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        barcodeScanner = BarcodeScanning.getClient(options)

        // Create and bind ImageAnalysis
        imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(
                    ContextCompat.getMainExecutor(context)
                ) { imageProxy ->
                    processImage(imageProxy)
                }
            }

        // Re-bind with analysis
        val provider = cameraProvider ?: return
        provider.unbindAll()

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        provider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            imageAnalysis
        )
    }

    @androidx.camera.core.ExperimentalGetImage
    private fun processImage(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null || !isDetectionActive) {
            imageProxy.close()
            return
        }

        val inputImage = InputImage.fromMediaImage(
            mediaImage,
            imageProxy.imageInfo.rotationDegrees
        )

        barcodeScanner?.process(inputImage)
            ?.addOnSuccessListener { barcodes ->
                for (barcode in barcodes) {
                    barcode.rawValue?.let { url ->
                        if (isDetectionActive) {
                            isDetectionActive = false  // Prevent duplicate callbacks
                            Log.d(TAG, "QR detected: $url")
                            onQrDetected?.invoke(url)
                        }
                    }
                }
            }
            ?.addOnCompleteListener {
                imageProxy.close()
            }
    }

    /**
     * Stop QR detection.
     */
    fun stopQrDetection() {
        Log.d(TAG, "Stopping QR detection")
        isDetectionActive = false
        imageAnalysis?.clearAnalyzer()
        onQrDetected = null
    }

    /**
     * Hide camera and release resources.
     */
    fun hideCameraLayer() {
        Log.d(TAG, "Hiding camera layer and releasing resources")
        previewView.visibility = View.GONE
        scannerOverlay.visibility = View.GONE

        stopQrDetection()
        cameraProvider?.unbindAll()
        barcodeScanner?.close()
        barcodeScanner = null
        state.hideCamera()
    }

    /**
     * Show loading state while processing QR code.
     */
    fun showLoading(message: String) {
        statusText.text = message
        loadingIndicator.visibility = View.VISIBLE
    }

    /**
     * Check if camera preview is actually streaming (rendering frames).
     */
    fun isCameraReady(): Boolean = state.isPreviewStreaming()

    /**
     * Register a callback that fires when camera reaches STREAMING state.
     * If already streaming, fires immediately.
     */
    fun onReady(callback: () -> Unit) {
        state.onReady(callback)
    }
}
