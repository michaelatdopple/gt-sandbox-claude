package com.dopple.webview.ui.game

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.content.pm.PackageManager
import android.net.http.SslError
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.dopple.webview.R
import com.dopple.webview.bridge.LoopSDKInjector
import com.dopple.webview.bridge.StorageNamespace
import com.dopple.webview.bridge.WebAppInterface
import com.dopple.webview.cache.BundleCacheManager
import com.dopple.webview.data.ManifestRepository
import com.dopple.webview.history.ActivityHistoryManager
import com.dopple.webview.download.BundleDownloader
import com.dopple.webview.download.ZipExtractor
import com.dopple.webview.server.AssetHttpServer
import com.dopple.webview.server.LocalHttpsServer
import com.dopple.webview.server.SslHelper
import com.dopple.webview.ui.gallery.PowerBarView
import com.dopple.webview.ui.gallery.RetroColors
import com.dopple.webview.ui.orchestrator.AppOrchestrator
import com.dopple.webview.services.DoppleServiceManager
import com.dopple.webview.data.Manifest as ActivityManifest
import kotlinx.coroutines.launch
import java.io.File

/**
 * Fragment for playing games via WebView.
 *
 * Navigation:
 * - Press A → Return to referrer (Gallery or Scanner)
 */
class GameFragment : Fragment() {

    companion object {
        private const val TAG = "GameFragment"
        const val ARG_MANIFEST_URL = "manifestUrl"
        private const val LOCAL_SERVER_PORT = 8443
    }

    private val viewModel: GameViewModel by viewModels()

    // Views
    private var webViewContainer: FrameLayout? = null
    private var loadingOverlay: LinearLayout? = null
    private var loadingText: TextView? = null
    private var loadingProgress: PowerBarView? = null
    private var loadingGameName: TextView? = null
    private var errorView: LinearLayout? = null
    private var errorText: TextView? = null

    // WebView
    private var webView: WebView? = null
    private var bridge: WebAppInterface? = null
    private var storageNamespace: StorageNamespace? = null
    private var pendingPermissionRequest: PermissionRequest? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            pendingPermissionRequest?.grant(pendingPermissionRequest?.resources)
        } else {
            pendingPermissionRequest?.deny()
        }
        pendingPermissionRequest = null
    }

    // Dependencies
    private var historyManager: ActivityHistoryManager? = null
    private val manifestRepository = ManifestRepository()
    private val bundleDownloader = BundleDownloader()
    private var assetServer: AssetHttpServer? = null
    private var localServer: LocalHttpsServer? = null
    private var cacheManager: BundleCacheManager? = null
    private var webViewSetupPending = false
    var orchestrator: AppOrchestrator? = null
        set(value) {
            field = value
            // If webview setup was pending, do it now
            if (value != null && webViewSetupPending) {
                webViewSetupPending = false
                setupWebView()
                loadGame()
            }
        }
    var serviceManager: DoppleServiceManager? = null

    // Get manifest URL from arguments
    private val manifestUrl: String?
        get() = arguments?.getString(ARG_MANIFEST_URL)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_game, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Consume all window insets for fullscreen
        view.setOnApplyWindowInsetsListener { _, _ ->
            android.view.WindowInsets.CONSUMED
        }
        view.requestApplyInsets()

        // Bind views
        webViewContainer = view.findViewById(R.id.webview_container)
        loadingOverlay = view.findViewById(R.id.loading_overlay)
        loadingText = view.findViewById(R.id.loading_text)
        loadingProgress = view.findViewById(R.id.loading_progress)
        loadingGameName = view.findViewById(R.id.loading_game_name)
        errorView = view.findViewById(R.id.error_view)
        errorText = view.findViewById(R.id.error_text)

        // Initialize managers
        historyManager = ActivityHistoryManager(requireContext())
        cacheManager = BundleCacheManager(requireContext())

        // Configure loading progress
        loadingProgress?.configure("", "LOADING", RetroColors.CYAN)
        loadingProgress?.startPulse()

        // Observe state
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    updateUI(state)
                }
            }
        }

        // WebView needs focus to function
        view.isFocusableInTouchMode = true
        view.requestFocus()

        // Acquire WebView and load game
        // If orchestrator isn't set yet (injection delayed), mark as pending
        if (orchestrator != null) {
            setupWebView()
            loadGame()
        } else {
            webViewSetupPending = true
            Log.d(TAG, "WebView setup pending - waiting for orchestrator injection")
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
        bridge?.freeRotate?.restoreStateForGame()
        super.onDestroyView()

        // Stop servers
        localServer?.stop()
        localServer = null
        // AssetHttpServer is a singleton — don't stop it here
        assetServer = null

        // Release WebView back to pool
        webView?.let { wv ->
            webViewContainer?.removeView(wv)
            orchestrator?.webViewPool?.release(wv)
        }

        bridge?.cleanup()
        bridge = null
        storageNamespace = null
        webView = null
        webViewContainer = null
        loadingOverlay = null
        loadingText = null
        loadingProgress = null
        loadingGameName = null
        errorView = null
        errorText = null
        historyManager = null
        cacheManager = null
    }

    @android.annotation.SuppressLint("JavascriptInterface")
    private fun setupWebView() {
        val pool = orchestrator?.webViewPool ?: return
        val url = manifestUrl ?: return

        // Acquire from pool (may be prefetched)
        webView = pool.acquire(requireContext(), url)

        webView?.let { wv ->
            // Add to container
            wv.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            webViewContainer?.addView(wv)
            wv.visibility = View.VISIBLE

            // Set up client
            wv.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    view?.let { LoopSDKInjector.inject(it, requireContext()) }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    viewModel.setLoading(false)
                    Log.d(TAG, "Page loaded: $url")
                }

                @SuppressLint("WebViewClientOnReceivedSslError")
                override fun onReceivedSslError(
                    view: WebView?,
                    handler: SslErrorHandler?,
                    error: SslError?
                ) {
                    val url = error?.url ?: ""
                    // Safe: SslHelper.isLocalNetworkUrl only matches localhost and RFC 1918 private IPs
                    if (SslHelper.isLocalNetworkUrl(url)) {
                        Log.d(TAG, "Accepting self-signed cert for local: $url")
                        handler?.proceed()
                    } else {
                        Log.w(TAG, "Rejecting SSL error for: $url")
                        handler?.cancel()
                    }
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    if (request?.isForMainFrame == true) {
                        Log.e(TAG, "WebView error: ${error?.description}")
                        viewModel.setError("Failed to load: ${error?.description}")
                    }
                }
            }

            // Set up chrome client for getUserMedia permissions
            wv.webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest?) {
                    request?.let { handlePermissionRequest(it) }
                }

                override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                    consoleMessage?.let {
                        Log.d(TAG, "JS: ${it.message()} (${it.sourceId()}:${it.lineNumber()})")
                    }
                    return true
                }
            }

            // Set up bridge
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
            bridge?.freeRotate?.saveStateForGame()
        }
    }

    @SuppressLint("JavascriptInterface")
    private fun registerStorageNamespace(projectId: String) {
        val wv = webView ?: return
        storageNamespace = StorageNamespace(requireContext().applicationContext, projectId)
        wv.addJavascriptInterface(storageNamespace!!, "Loop\$storage")
    }

    private fun loadGame() {
        val url = manifestUrl ?: return

        lifecycleScope.launch {
            if (!isAdded) return@launch

            val result = manifestRepository.fetchManifest(url, context)

            if (!isAdded) return@launch

            result.onSuccess { manifest ->
                viewModel.setGameName(manifest.activityName)
                historyManager?.addActivity(
                    projectId = manifest.projectId,
                    activityName = manifest.activityName,
                    manifestUrl = url
                )
                registerStorageNamespace(manifest.projectId)
                processManifest(manifest)
            }.onFailure { error ->
                viewModel.setError(error.message ?: "Failed to load game")
            }
        }
    }

    /**
     * Dispatch manifest to the appropriate loading strategy:
     * - bundleUrl present → download, extract, cache, serve via LocalHttpsServer
     * - localhost:8088 URL → start AssetHttpServer, then load
     * - otherwise → load URL directly
     */
    private fun processManifest(manifest: ActivityManifest) {
        if (manifest.bundleUrl != null) {
            downloadAndHostBundle(manifest)
        } else if (manifest.url.startsWith("http://127.0.0.1:8088/")) {
            startAssetServerIfNeeded()
            webView?.loadUrl(manifest.url)
        } else {
            webView?.loadUrl(manifest.url)
        }
    }

    private fun downloadAndHostBundle(manifest: ActivityManifest) {
        lifecycleScope.launch {
            try {
                if (!isAdded || context == null) return@launch

                val ctx = requireContext()
                val cache = cacheManager ?: BundleCacheManager(ctx)
                val projectDir = cache.getBundleDirectory(manifest.projectId)

                // Check cache
                val shouldUseCache = cache.getCachedVersion(manifest.projectId) == manifest.version
                    && cache.isBundleCached(manifest.projectId)
                    && cache.validateCachedBundle(manifest.projectId)

                if (shouldUseCache) {
                    Log.d(TAG, "Using cached bundle")
                    startServerAndLoad(projectDir, manifest)
                    return@launch
                }

                // Clean and prepare directory
                try {
                    if (projectDir.exists()) projectDir.deleteRecursively()
                } catch (e: Exception) {
                    Log.w(TAG, "Could not clean previous bundle", e)
                }

                if (!projectDir.mkdirs() && !projectDir.exists()) {
                    viewModel.setError("Could not create storage")
                    return@launch
                }

                val bundleUrl = manifest.bundleUrl
                if (bundleUrl.isNullOrBlank()) {
                    viewModel.setError("Invalid bundle URL")
                    return@launch
                }

                // Download
                val tempFile = File(ctx.cacheDir, "bundle_app.zip")

                val downloadResult = bundleDownloader.download(
                    url = bundleUrl,
                    destination = tempFile,
                    onProgress = { _, _ -> }
                )

                if (!isAdded) {
                    tempFile.delete()
                    return@launch
                }

                downloadResult.onFailure { error ->
                    viewModel.setError(error.message ?: "Download failed")
                    return@launch
                }

                // Extract
                val extractResult = ZipExtractor.extract(tempFile, projectDir)

                if (!isAdded) {
                    tempFile.delete()
                    projectDir.deleteRecursively()
                    return@launch
                }

                extractResult.onFailure { error ->
                    viewModel.setError(error.message ?: "Extraction failed")
                    tempFile.delete()
                    return@launch
                }

                tempFile.delete()
                cache.setCachedVersion(manifest.projectId, manifest.version)

                startServerAndLoad(projectDir, manifest)

            } catch (e: Exception) {
                Log.e(TAG, "Bundle processing failed", e)
                viewModel.setError("Failed to load: ${e.message}")
            }
        }
    }

    private fun startServerAndLoad(projectDir: File, manifest: ActivityManifest) {
        try {
            if (localServer == null) {
                localServer = LocalHttpsServer(requireContext())
            }
            localServer?.start(projectDir.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "Server start failed", e)
            viewModel.setError("Could not start server")
            return
        }

        val entryPoint = if (manifest.url.startsWith("file://")) {
            manifest.url.removePrefix("file://")
        } else {
            "index.html"
        }

        val localUrl = "https://127.0.0.1:$LOCAL_SERVER_PORT/$entryPoint"
        Log.d(TAG, "Loading bundle: $localUrl")

        activity?.runOnUiThread {
            if (isAdded && webView != null) {
                viewModel.setLoading(false)
                webView?.loadUrl(localUrl)
            }
        }
    }

    private fun handlePermissionRequest(request: PermissionRequest) {
        val resources = request.resources
        val androidPermissions = mutableListOf<String>()

        resources.forEach { resource ->
            when (resource) {
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> {
                    androidPermissions.add(android.Manifest.permission.CAMERA)
                }
                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> {
                    androidPermissions.add(android.Manifest.permission.RECORD_AUDIO)
                }
            }
        }

        if (androidPermissions.isEmpty()) {
            request.grant(resources)
            return
        }

        val allGranted = androidPermissions.all { permission ->
            ContextCompat.checkSelfPermission(requireContext(), permission) ==
                    PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) {
            request.grant(resources)
        } else {
            pendingPermissionRequest = request
            requestPermissionLauncher.launch(androidPermissions.toTypedArray())
        }
    }

    private fun startAssetServerIfNeeded() {
        assetServer = AssetHttpServer.ensureRunning(requireContext())
        Log.d(TAG, "Using shared asset server for game")
    }

    private fun updateUI(state: GameUiState) {
        if (state.error != null) {
            loadingOverlay?.visibility = View.GONE
            errorView?.visibility = View.VISIBLE
            errorText?.text = state.error
        } else if (state.isLoading) {
            loadingOverlay?.visibility = View.VISIBLE
            errorView?.visibility = View.GONE
            loadingGameName?.text = state.gameName
        } else {
            // Fade out loading overlay
            loadingOverlay?.animate()
                ?.alpha(0f)
                ?.setDuration(300)
                ?.withEndAction {
                    loadingOverlay?.visibility = View.GONE
                }
                ?.start()
            errorView?.visibility = View.GONE
        }
    }

    fun getBridge(): WebAppInterface? = bridge
    fun getWebView(): WebView? = webView
}
