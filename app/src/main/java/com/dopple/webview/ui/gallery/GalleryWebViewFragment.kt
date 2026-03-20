package com.dopple.webview.ui.gallery

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.dopple.webview.R
import com.dopple.webview.bridge.GalleryNamespace
import com.dopple.webview.bridge.LoopSDKInjector
import com.dopple.webview.bridge.WebAppInterface
import com.dopple.webview.history.ActivityHistoryManager
import com.dopple.webview.services.DoppleServiceManager
import com.dopple.webview.MainActivity
import com.dopple.webview.ui.orchestrator.AppOrchestrator
import com.dopple.webview.ui.scanner.ScannerModeController

/**
 * Gallery home screen running as a React app inside a WebView.
 *
 * Uses WebViewPool for fast acquisition. Registers full Loop SDK bridge
 * (buttons, motion, haptics) plus gallery-specific namespace.
 *
 * Button events are forwarded by ButtonRouter via getBridge().dispatchButtonEvent().
 */
@Suppress("TooManyFunctions")
class GalleryWebViewFragment : Fragment() {

    companion object {
        private const val TAG = "GalleryWebViewFragment"
        private const val GALLERY_URL = "http://127.0.0.1:8088/gallery/index.html"
    }

    private var webViewContainer: FrameLayout? = null
    private var webView: WebView? = null
    private var bridge: WebAppInterface? = null
    private var galleryNamespace: GalleryNamespace? = null
    private var historyManager: ActivityHistoryManager? = null
    private var scannerModeController: ScannerModeController? = null

    var orchestrator: AppOrchestrator? = null
        set(value) {
            field = value
            if (value != null && webViewSetupPending) {
                webViewSetupPending = false
                setupWebView()
                loadGallery()
            }
        }
    var serviceManager: DoppleServiceManager? = null
    private var webViewSetupPending = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_gallery_webview, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.setOnApplyWindowInsetsListener { _, _ ->
            android.view.WindowInsets.CONSUMED
        }
        view.requestApplyInsets()

        webViewContainer = view.findViewById(R.id.webview_container)
        historyManager = ActivityHistoryManager(requireContext())

        scannerModeController = ScannerModeController(
            onPrepareCamera = {
                (activity as? MainActivity)?.cameraManager?.prepareDuringHold()
            },
            onShowCamera = {
                val cam = (activity as? MainActivity)?.cameraManager ?: return@ScannerModeController
                cam.showCameraLayer()
                cam.startQrDetection { url ->
                    scannerModeController?.deactivateScannerMode()
                    orchestrator?.onGalleryLaunchGame(0, url)
                }
            },
            onHideCamera = {
                (activity as? MainActivity)?.cameraManager?.hideCameraLayer()
            },
            onShowGallery = {
                view.visibility = View.VISIBLE
            },
            onHideGallery = {
                view.visibility = View.INVISIBLE
            }
        )

        view.isFocusableInTouchMode = true
        view.requestFocus()

        if (orchestrator != null) {
            setupWebView()
            loadGallery()
        } else {
            webViewSetupPending = true
            Log.d(TAG, "WebView setup pending — waiting for orchestrator injection")
        }
    }

    @SuppressLint("JavascriptInterface")
    private fun setupWebView() {
        val pool = orchestrator?.webViewPool ?: return

        webView = pool.acquire(requireContext())

        webView?.let { wv ->
            wv.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            webViewContainer?.addView(wv)
            wv.visibility = View.VISIBLE

            wv.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    view?.let {
                        LoopSDKInjector.inject(it, requireContext())
                        LoopSDKInjector.injectInternal(it, requireContext())
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    Log.d(TAG, "Gallery loaded: $url")
                    restoreScrollPosition()
                }
            }

            wv.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                    consoleMessage?.let {
                        Log.d(TAG, "JS: ${it.message()} (${it.sourceId()}:${it.lineNumber()})")
                    }
                    return true
                }
            }

            registerBridges(wv)
        }
    }

    @SuppressLint("JavascriptInterface")
    private fun registerBridges(wv: WebView) {
        bridge = WebAppInterface(
            wv,
            requireActivity(),
            { serviceManager },
            lifecycleScope
        ).also { b ->
            wv.addJavascriptInterface(b.button, "Loop\$buttons")
            wv.addJavascriptInterface(b.imu, "Loop\$motion")
            wv.addJavascriptInterface(b.haptics, "Loop\$haptics")
            wv.addJavascriptInterface(b.match, "Loop\$match")
            wv.addJavascriptInterface(b.pack, "Loop\$pack")
            wv.addJavascriptInterface(b.manifest, "Loop\$manifest")
            wv.addJavascriptInterface(b.ble, "Loop\$ble")
            wv.addJavascriptInterface(b.system, "Loop\$system")
        }

        galleryNamespace = GalleryNamespace(
            historyProvider = {
                historyManager?.getHistory()?.map { item ->
                    GalleryNamespace.HistoryEntry(
                        projectId = item.projectId,
                        activityName = item.activityName,
                        manifestUrl = item.manifestUrl,
                        thumbnailPath = item.thumbnailPath,
                        launchCount = item.launchCount
                    )
                } ?: emptyList()
            },
            onLaunch = { manifestUrl ->
                activity?.runOnUiThread {
                    orchestrator?.onGalleryLaunchGame(0, manifestUrl)
                }
            },
            onPrepareScanner = {
                activity?.runOnUiThread {
                    (activity as? MainActivity)?.cameraManager?.prepareDuringHold()
                }
            },
            onOpenScanner = {
                activity?.runOnUiThread {
                    val cam = (activity as? MainActivity)?.cameraManager ?: return@runOnUiThread
                    cam.onReady {
                        scannerModeController?.activateScannerMode()
                    }
                }
            },
            onCloseScanner = {
                activity?.runOnUiThread {
                    scannerModeController?.deactivateScannerMode()
                }
            }
        )
        wv.addJavascriptInterface(galleryNamespace!!, "Loop\$gallery")
    }

    private fun loadGallery() {
        webView?.loadUrl(GALLERY_URL)
    }

    /**
     * After page load, send scrollToIndex to React if returning from a game.
     */
    private fun restoreScrollPosition() {
        val index = arguments?.getInt("scrollToIndex", -1) ?: -1
        if (index >= 0) {
            webView?.evaluateJavascript(
                "window.__setScrollIndex && window.__setScrollIndex($index)",
                null
            )
        }
    }

    override fun onPause() {
        super.onPause()
        bridge?.pause()
    }

    override fun onResume() {
        super.onResume()
        bridge?.resume()
    }

    override fun onDestroyView() {
        super.onDestroyView()

        webView?.let { wv ->
            webViewContainer?.removeView(wv)
            orchestrator?.webViewPool?.release(wv)
        }

        bridge?.cleanup()
        bridge = null
        galleryNamespace = null
        scannerModeController = null
        webView = null
        webViewContainer = null
        historyManager = null
    }

    fun getBridge(): WebAppInterface? = bridge
    fun getWebView(): android.webkit.WebView? = webView
    fun isScannerActive(): Boolean = scannerModeController?.currentMode == ScannerModeController.Mode.SCANNER

    fun exitScanner() {
        scannerModeController?.deactivateScannerMode()
    }
}
