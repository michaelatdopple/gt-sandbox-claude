package com.dopple.webview

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import com.dopple.webview.bridge.BridgeEventTypes
import com.dopple.webview.history.ActivityHistoryManager
import com.dopple.webview.server.AssetHttpServer
import com.dopple.webview.services.DoppleServiceManager
import com.dopple.webview.ui.gallery.GalleryWebViewFragment
import com.dopple.webview.ui.game.GameFragment
import com.dopple.webview.ui.love.Love2dGameFragment
import com.dopple.webview.ui.input.ButtonRouter
import com.dopple.webview.ui.orchestrator.AppOrchestrator
import com.dopple.webview.ui.orchestrator.WebViewPool
import com.dopple.webview.ui.scanner.CameraManager
import org.json.JSONObject

/**
 * Single-activity host for Scanner and WebView fragments.
 * Handles hardware key events and navigation.
 *
 * Also manages:
 * - DoppleServiceManager for ItemMatcher and PackManager connections
 */
class MainActivity : AppCompatActivity() {

    private lateinit var navController: NavController
    private lateinit var navHostFragment: NavHostFragment

    // Centralized button routing
    private lateinit var buttonRouter: ButtonRouter

    // Service manager for ItemMatcher and PackManager
    private lateinit var serviceManager: DoppleServiceManager

    // Activity history manager for ADB deep links
    private lateinit var historyManager: ActivityHistoryManager

    // Central navigation orchestrator
    private lateinit var orchestrator: AppOrchestrator

    // Sleep/wake manager
    private lateinit var sleepManager: com.dopple.webview.ui.system.SleepManager

    // Settings overlay state
    private var settingsOverlayVisible = false
    private var settingsOpenTimestamp = 0L

    // Camera manager for scanner mode
    lateinit var cameraManager: CameraManager
        private set

    companion object {
        private const val TAG = "MainActivity"
        private const val TAG_KEY_DISCOVERY = "KeyDiscovery"
        private val REQUIRED_PERMISSIONS = arrayOf(
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.BLUETOOTH_ADVERTISE,
        )
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val denied = results.filterValues { !it }.keys
        if (denied.isNotEmpty()) Log.w(TAG, "Permissions denied: $denied")
        cameraManager.prewarm()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize camera manager for scanner mode
        val cameraLayer = findViewById<PreviewView>(R.id.camera_layer)
        val scannerOverlay = findViewById<View>(R.id.scanner_overlay)
        val scannerStatus = scannerOverlay.findViewById<TextView>(R.id.scanner_status)
        val scannerLoading = scannerOverlay.findViewById<ProgressBar>(R.id.scanner_loading)

        cameraManager = CameraManager(
            context = this,
            lifecycleOwner = this,
            previewView = cameraLayer,
            scannerOverlay = scannerOverlay,
            statusText = scannerStatus,
            loadingIndicator = scannerLoading
        )

        ensurePermissions()

        // Start asset server (singleton — serves gallery and games on port 8088)
        AssetHttpServer.ensureRunning(this)

        // Enable edge-to-edge fullscreen for 800x800 circular display
        // Must be called after setContentView for insetsController to be available
        enableImmersiveMode()

        // Initialize service manager and bind to HostService
        serviceManager = DoppleServiceManager(this, lifecycleScope)
        serviceManager.bind()
        Log.d(TAG, "DoppleServiceManager created and binding initiated")

        // Initialize history manager for ADB deep links
        historyManager = ActivityHistoryManager(this)

        // Set up Navigation
        navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController

        // Initialize orchestrator
        orchestrator = AppOrchestrator { destination, args ->
            when (destination) {
                AppOrchestrator.DEST_GALLERY -> {
                    navController.navigate(R.id.galleryFragment, args)
                }
                AppOrchestrator.DEST_GAME -> {
                    navController.navigate(R.id.gameFragment, args)
                }
                AppOrchestrator.DEST_LOVE2D -> {
                    navController.navigate(R.id.love2dGameFragment, args)
                }
            }
        }
        orchestrator.webViewPool = WebViewPool

        // Initialize sleep manager
        sleepManager = com.dopple.webview.ui.system.SleepManager(this)

        // Initialize button router
        buttonRouter = ButtonRouter(
            onSleep = {
                Log.d(TAG, "ButtonRouter: onSleep")
                sleepManager.sleep(getActiveWebView())
            },
            onWake = { previousContext ->
                Log.d(TAG, "ButtonRouter: onWake (previousContext=$previousContext)")
                sleepManager.wake(getActiveWebView())
            },
            onOpenSettings = {
                Log.d(TAG, "ButtonRouter: onOpenSettings")
                showSettingsOverlay()
            },
            onDismissSettings = {
                Log.d(TAG, "ButtonRouter: onDismissSettings")
                hideSettingsOverlay()
            },
            onForwardToBridge = { buttonId, state ->
                getCurrentGameFragment()?.getBridge()
                    ?.dispatchButtonEvent(buttonId, state)
            },
            onForwardToGalleryBridge = { buttonId, state ->
                getCurrentGalleryWebViewFragment()?.getBridge()
                    ?.dispatchButtonEvent(buttonId, state)
            },
            onForwardToSettings = { buttonId, state ->
                Log.d(TAG, "ButtonRouter: settings $buttonId=$state")
                handleSettingsButton(buttonId, state)
            },
            onExitScanner = {
                Log.d(TAG, "ButtonRouter: onExitScanner")
                getCurrentGalleryWebViewFragment()?.exitScanner()
            }
        )

        // Inject orchestrator and service manager into fragments when navigated
        navController.addOnDestinationChangedListener { _, destination, _ ->
            // Post to allow fragment to be created
            Handler(Looper.getMainLooper()).postDelayed({
                when (destination.id) {
                    R.id.galleryFragment -> {
                        val gallery = navHostFragment.childFragmentManager
                            .fragments.firstOrNull() as? GalleryWebViewFragment
                        gallery?.let { fragment ->
                            fragment.orchestrator = orchestrator
                            fragment.serviceManager = serviceManager
                            Log.d(TAG, "Injected orchestrator into GalleryWebViewFragment")
                        }
                    }
                    R.id.gameFragment -> {
                        (navHostFragment.childFragmentManager.fragments.firstOrNull() as? GameFragment)?.let { fragment ->
                            fragment.orchestrator = orchestrator
                            fragment.serviceManager = serviceManager
                            Log.d(TAG, "Injected orchestrator and serviceManager into GameFragment")
                        }
                    }
                    R.id.love2dGameFragment -> {
                        val love2d = navHostFragment.childFragmentManager
                            .fragments.firstOrNull() as? Love2dGameFragment
                        love2d?.let { fragment ->
                            fragment.orchestrator = orchestrator
                            fragment.serviceManager = serviceManager
                            Log.d(TAG, "Injected orchestrator into Love2dGameFragment")
                        }
                    }
                }
            }, 50)
        }

        // Handle deeplink for debug/testing: dopple://test/bridge
        handleDeeplink()
    }

    private fun ensurePermissions() {
        val needed = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) {
            cameraManager.prewarm()
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        AssetHttpServer.stopGlobal()

        // Unbind from HostService
        serviceManager.unbind()
        Log.d(TAG, "DoppleServiceManager unbound")
    }

    private fun handleDeeplink() {
        handleDeeplinkIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeeplinkIntent(intent)
    }

    private fun handleDeeplinkIntent(intent: Intent?) {
        val uri = intent?.data ?: return

        Log.d(TAG, "Deeplink detected: $uri")

        // Debug-only deeplinks (gated by BuildConfig.DEBUG)
        if (BuildConfig.DEBUG) {
            when (uri.host) {
                "test" -> {
                    handleTestDeeplink(uri)
                    return
                }
            }
        }

        when (uri.host) {
            "nav" -> {
                // dopple://nav/{gallery|scanner} - Direct navigation for testing
                val destination = uri.pathSegments.firstOrNull()
                Log.d(TAG, "Nav deeplink: $destination")
                when (destination) {
                    "gallery" -> {
                        navController.navigate(R.id.galleryFragment)
                    }
                    "scanner" -> {
                        // Scanner is now a mode in Gallery, just navigate to Gallery
                        navController.navigate(R.id.galleryFragment)
                    }
                }
                return
            }

            "launch" -> {
                // dopple://launch?manifest=<url> - Launch by manifest URL (for CDP debugging)
                // dopple://launch?activity=<name> - Launch from history by name
                val manifestUrl = uri.getQueryParameter("manifest")
                val activityName = uri.getQueryParameter("activity")

                when {
                    manifestUrl != null -> {
                        Log.d(TAG, "Launch deeplink with manifest: $manifestUrl")
                        Handler(Looper.getMainLooper()).postDelayed({
                            launchByManifestUrl(manifestUrl)
                        }, 500)
                    }
                    activityName != null -> {
                        Log.d(TAG, "Launch deeplink with activity: $activityName")
                        Handler(Looper.getMainLooper()).postDelayed({
                            launchFromHistory(activityName)
                        }, 500)
                    }
                }
            }

            "lens" -> {
                // dopple://lens/{lens-name} - Switch to specific lens
                val lensName = uri.pathSegments.firstOrNull()
                if (lensName != null) {
                    Log.d(TAG, "Lens switch deeplink: $lensName")
                    Handler(Looper.getMainLooper()).postDelayed({
                        switchToLensViaJS(lensName)
                    }, 500)
                }
            }

            "qr-test" -> handleQrTestDeeplink(uri)

            "love" -> handleLoveDeeplink(uri)
        }
    }

    /**
     * Handle test harness deeplinks (DEBUG builds only).
     * Format: dopple://test/{command}?{params}
     */
    private fun handleTestDeeplink(uri: android.net.Uri) {
        val path = uri.pathSegments.firstOrNull() ?: return
        val secondPath = uri.pathSegments.getOrNull(1)

        Log.d(TAG, "Test deeplink: path=$path, secondPath=$secondPath")

        when (path) {
            "bridge" -> {
                // Navigate to GameFragment with debug URL
                navController.navigate(
                    R.id.gameFragment,
                    android.os.Bundle().apply {
                        putString("manifestUrl", "debug://test-bridge")
                    }
                )
            }

            "lens" -> {
                // dopple://test/lens?name={qr|object|vivarium}
                val lensName = uri.getQueryParameter("name")
                if (lensName != null) {
                    postToWebView { switchToLensViaJS(lensName) }
                }
            }

            "button" -> {
                // dopple://test/button?id=A&action=press&duration=1500
                val buttonId = uri.getQueryParameter("id") ?: "A"
                val action = uri.getQueryParameter("action") ?: "press"
                val duration = uri.getQueryParameter("duration")
                val sequence = uri.getQueryParameter("sequence")
                val interval = uri.getQueryParameter("interval")

                // Scanner mode not available in WebView gallery
                if (isOnGalleryFragment()) {
                    if (buttonId == "A" && action == "long_press") {
                        Log.d(TAG, "Test button: Scanner mode not available in WebView gallery")
                        return
                    }
                }

                val options = JSONObject().apply {
                    duration?.let { put("duration", it.toLongOrNull() ?: 1000) }
                    sequence?.let { put("sequence", it) }
                    interval?.let { put("interval", it.toLongOrNull() ?: 200) }
                }

                postToWebView {
                    val js = "window.__handleButtonDeeplink && window.__handleButtonDeeplink('$buttonId', '$action', ${options})"
                    evaluateJavascript(js)
                }
            }

            "rim" -> {
                // dopple://test/rim?type=swipe&direction=cw&velocity=120&arc=45
                val type = uri.getQueryParameter("type") ?: "swipe"
                val direction = uri.getQueryParameter("direction") ?: "cw"
                val velocity = uri.getQueryParameter("velocity")
                val arc = uri.getQueryParameter("arc")
                val duration = uri.getQueryParameter("duration")
                val startAngle = uri.getQueryParameter("start_angle")
                val accumulatedAngle = uri.getQueryParameter("accumulated_angle")

                val params = JSONObject().apply {
                    put("type", type)
                    put("direction", direction)
                    velocity?.let { put("velocity", it.toDoubleOrNull() ?: 120.0) }
                    arc?.let { put("arc", it.toDoubleOrNull() ?: 45.0) }
                    duration?.let { put("duration", it.toLongOrNull() ?: 500) }
                    startAngle?.let { put("start_angle", it.toDoubleOrNull() ?: 0.0) }
                    accumulatedAngle?.let { put("accumulated_angle", it.toDoubleOrNull() ?: 0.0) }
                }

                postToWebView {
                    val js = "window.__handleRimDeeplink && window.__handleRimDeeplink(${params})"
                    evaluateJavascript(js)
                }
            }

            "imu" -> {
                // dopple://test/imu?pattern=shake&intensity=high&duration=1000
                // dopple://test/imu?accel_x=0&accel_y=9.8&accel_z=0
                val pattern = uri.getQueryParameter("pattern")
                val intensity = uri.getQueryParameter("intensity")
                val duration = uri.getQueryParameter("duration")
                val accelX = uri.getQueryParameter("accel_x")
                val accelY = uri.getQueryParameter("accel_y")
                val accelZ = uri.getQueryParameter("accel_z")
                val gyroX = uri.getQueryParameter("gyro_x")
                val gyroY = uri.getQueryParameter("gyro_y")
                val gyroZ = uri.getQueryParameter("gyro_z")

                val params = JSONObject().apply {
                    pattern?.let { put("pattern", it) }
                    intensity?.let { put("intensity", it) }
                    duration?.let { put("duration", it.toLongOrNull() ?: 1000) }
                    accelX?.let { put("accel_x", it.toDoubleOrNull() ?: 0.0) }
                    accelY?.let { put("accel_y", it.toDoubleOrNull() ?: 0.0) }
                    accelZ?.let { put("accel_z", it.toDoubleOrNull() ?: 0.0) }
                    gyroX?.let { put("gyro_x", it.toDoubleOrNull() ?: 0.0) }
                    gyroY?.let { put("gyro_y", it.toDoubleOrNull() ?: 0.0) }
                    gyroZ?.let { put("gyro_z", it.toDoubleOrNull() ?: 0.0) }
                }

                postToWebView {
                    val js = "window.__handleIMUDeeplink && window.__handleIMUDeeplink(${params})"
                    evaluateJavascript(js)
                }
            }

            "orientation" -> {
                // dopple://test/orientation?to=landscape
                val to = uri.getQueryParameter("to") ?: "portrait"

                postToWebView {
                    val js = "window.__handleIMUDeeplink && window.__handleIMUDeeplink({to: '$to'})"
                    evaluateJavascript(js)
                }
            }

            "camera" -> {
                // dopple://test/camera/start, /stop, /status
                val action = secondPath ?: "status"

                postToWebView {
                    val js = "window.__handleCameraDeeplink && window.__handleCameraDeeplink('$action')"
                    evaluateJavascript(js)
                }
            }

            "metrics" -> {
                // dopple://test/metrics/camera, /all
                val metricsType = secondPath ?: "all"

                postToWebView {
                    val js = "window.__handleMetricsDeeplink && window.__handleMetricsDeeplink('$metricsType')"
                    evaluateJavascript(js)
                }
            }

            "overlay" -> {
                // dopple://test/overlay?enabled=true
                val enabled = uri.getQueryParameter("enabled")?.toBoolean() ?: true

                postToWebView {
                    val js = "window.__handleOverlayDeeplink && window.__handleOverlayDeeplink($enabled)"
                    evaluateJavascript(js)
                }
            }

            "spell" -> {
                // dopple://test/spell/mock?result=success|failure&delay=3000&name=MockDragon
                // dopple://test/spell/settings?manaRingEffect=true&celebrationConfetti=false
                when (secondPath) {
                    "mock" -> {
                        val result = uri.getQueryParameter("result") ?: "success"
                        val delay = uri.getQueryParameter("delay")
                        val name = uri.getQueryParameter("name")
                        val error = uri.getQueryParameter("error")

                        val params = JSONObject().apply {
                            put("result", result)
                            delay?.let { put("delay", it.toLongOrNull() ?: 2000) }
                            name?.let { put("name", it) }
                            error?.let { put("error", it) }
                        }

                        postToWebView {
                            val js = "window.__handleSpellMockDeeplink && window.__handleSpellMockDeeplink($params)"
                            evaluateJavascript(js)
                        }
                    }

                    "settings" -> {
                        val params = JSONObject().apply {
                            uri.getQueryParameter("manaRingEffect")?.let { put("manaRingEffect", it) }
                            uri.getQueryParameter("celebrationConfetti")?.let { put("celebrationConfetti", it) }
                            uri.getQueryParameter("celebrationTextStyle")?.let { put("celebrationTextStyle", it) }
                            uri.getQueryParameter("celebrationDuration")?.let { put("celebrationDuration", it) }
                            uri.getQueryParameter("vortexParticleIntensity")?.let { put("vortexParticleIntensity", it) }
                        }

                        postToWebView {
                            val js = "window.__handleSpellSettingsDeeplink && window.__handleSpellSettingsDeeplink($params)"
                            evaluateJavascript(js)
                        }
                    }
                }
            }
        }
    }

    /**
     * Post a WebView action after a delay to ensure WebView is initialized.
     */
    private fun postToWebView(action: () -> Unit) {
        Handler(Looper.getMainLooper()).postDelayed({
            action()
        }, 100)
    }

    /**
     * Execute JavaScript in the current GameFragment's WebView.
     */
    private fun evaluateJavascript(js: String) {
        getCurrentGameFragment()?.let { fragment ->
            val webView = fragment.getWebView()
            if (webView != null) {
                webView.evaluateJavascript(js) { result ->
                    Log.d(TAG, "JS result: $result")
                }
            } else {
                Log.w(TAG, "WebView not available for JS execution")
            }
        }
    }

    /**
     * Switch lens via JavaScript bridge call.
     * Used by deeplinks: dopple://lens/{lens-name}
     */
    fun switchToLensViaJS(lensName: String) {
        getCurrentGameFragment()?.let { fragment ->
            val webView = fragment.getWebView()
            if (webView != null) {
                val js = "window.__handleLensDeeplink && window.__handleLensDeeplink('$lensName')"
                webView.evaluateJavascript(js) { result ->
                    Log.d(TAG, "Lens switch result: $result")
                }
            } else {
                Log.w(TAG, "WebView not available for lens switch")
            }
        }
    }

    /**
     * Handle QR test deeplinks.
     * Format: dopple://qr-test?url={manifest-url}
     */
    private fun handleQrTestDeeplink(uri: android.net.Uri) {
        val testUrl = uri.getQueryParameter("url") ?: return
        Log.d(TAG, "QR test deeplink with URL: $testUrl")
        Handler(Looper.getMainLooper()).postDelayed({
            simulateQRScanViaJS(testUrl)
        }, 500)
    }

    /**
     * Handle Love2D bundled game deeplinks.
     * Format: dopple://love/{game-name}
     */
    private fun handleLoveDeeplink(uri: android.net.Uri) {
        val gameName = uri.pathSegments.firstOrNull() ?: return
        Log.d(TAG, "Love2D deeplink: launching bundled game '$gameName'")
        val manifestUrl = "http://127.0.0.1:8088/games/$gameName/manifest.json"
        Handler(Looper.getMainLooper()).postDelayed({
            navController.navigate(
                R.id.love2dGameFragment,
                Bundle().apply {
                    putString("manifestUrl", manifestUrl)
                }
            )
        }, 500)
    }

    /**
     * Simulate QR scan via JavaScript bridge call.
     * Used by deeplinks: dopple://qr-test?url={url}
     */
    fun simulateQRScanViaJS(url: String) {
        getCurrentGameFragment()?.let { fragment ->
            val webView = fragment.getWebView()
            if (webView != null) {
                val escapedUrl = url.replace("'", "\\'")
                val js = "window.__handleQRTestDeeplink && window.__handleQRTestDeeplink('$escapedUrl')"
                webView.evaluateJavascript(js) { result ->
                    Log.d(TAG, "QR test simulation result: $result")
                }
            } else {
                Log.w(TAG, "WebView not available for QR test")
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            enableImmersiveMode()
        }
    }

    private fun enableImmersiveMode() {
        // Make content extend behind system bars
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Ensure window extends into display cutout area (use ALWAYS for maximum coverage)
        window.attributes.layoutInDisplayCutoutMode =
            android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS

        // Hide system bars
        window.insetsController?.let { controller ->
            controller.hide(
                WindowInsets.Type.statusBars() or
                WindowInsets.Type.navigationBars() or
                WindowInsets.Type.systemBars() or
                WindowInsets.Type.displayCutout()
            )
            controller.systemBarsBehavior =
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        // Consume system insets so content fills entire screen
        val contentView = findViewById<View>(android.R.id.content)
        contentView?.setOnApplyWindowInsetsListener { view, insets ->
            // Don't apply any padding - content should fill the entire 800x800
            view.setPadding(0, 0, 0, 0)
            WindowInsets.CONSUMED
        }

        // Request layout immediately
        contentView?.requestApplyInsets()
    }

    /**
     * Intercept key events before they reach the WebView.
     * WebView steals focus and consumes hardware key events (gamepad buttons, power key),
     * preventing them from reaching onKeyDown(). All button routing goes through here.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Log for hardware discovery
        if (event.action == KeyEvent.ACTION_DOWN) {
            Log.d(TAG_KEY_DISCOVERY, "KeyEvent: code=${event.keyCode}, scan=${event.scanCode}, device=${event.deviceId}")
        }

        // Map keycode to button ID
        val buttonId = BridgeEventTypes.mapKeycodeToButtonId(event.keyCode)
            ?: return super.dispatchKeyEvent(event)

        // Consume repeat key events without routing — prevents multiple
        // "down" dispatches that break hold detection in the JS layer.
        if (event.repeatCount > 0) return true

        val context = currentButtonContext()
        val consumed = buttonRouter.route(context, buttonId, event.action)

        return if (consumed) true else super.dispatchKeyEvent(event)
    }

    private fun currentButtonContext(): ButtonRouter.Context {
        if (sleepManager.isSleeping) return ButtonRouter.Context.SLEEP
        if (settingsOverlayVisible) return ButtonRouter.Context.SETTINGS_OVERLAY
        return when (navController.currentDestination?.id) {
            R.id.galleryFragment -> {
                val gallery = getCurrentGalleryWebViewFragment()
                if (gallery?.isScannerActive() == true) ButtonRouter.Context.SCANNER
                else ButtonRouter.Context.GALLERY
            }
            R.id.gameFragment -> ButtonRouter.Context.GAME
            R.id.love2dGameFragment -> ButtonRouter.Context.GAME
            else -> ButtonRouter.Context.GALLERY
        }
    }

    // ==================== Settings Overlay ====================

    private fun showSettingsOverlay() {
        if (settingsOverlayVisible) return
        settingsOverlayVisible = true
        settingsOpenTimestamp = System.currentTimeMillis()

        val overlay = findViewById<com.dopple.webview.ui.system.SettingsOverlayView>(R.id.settings_overlay)
            ?: return
        val isInGame = navController.currentDestination?.id == R.id.gameFragment ||
            navController.currentDestination?.id == R.id.love2dGameFragment
        overlay.showQuitActivity = isInGame
        overlay.onQuitActivity = if (isInGame) {{
            orchestrator.onGameExitToGallery()
            hideSettingsOverlay()
        }} else null
        overlay.visibility = View.VISIBLE

        // Save and disable free rotate for settings
        getActiveBridge()?.freeRotate?.saveStateForSettings()

        // Dispatch pause to active WebView
        getActiveWebView()?.evaluateJavascript(
            "window.dispatchEvent(new CustomEvent('loop:pause', {detail:{reason:'settings'}}))",
            null
        )

        Log.d(TAG, "Settings overlay shown")
    }

    private fun hideSettingsOverlay() {
        if (!settingsOverlayVisible) return
        val pausedMs = System.currentTimeMillis() - settingsOpenTimestamp
        settingsOverlayVisible = false

        val overlay = findViewById<com.dopple.webview.ui.system.SettingsOverlayView>(R.id.settings_overlay)
        overlay?.onQuitActivity = null
        overlay?.visibility = View.GONE

        // Restore free rotate after settings
        getActiveBridge()?.freeRotate?.restoreStateForSettings()

        // Dispatch resume to active WebView
        getActiveWebView()?.evaluateJavascript(
            "window.dispatchEvent(new CustomEvent('loop:resume', {detail:{reason:'settings',pausedMs:$pausedMs}}))",
            null
        )

        Log.d(TAG, "Settings overlay hidden (pausedMs=$pausedMs)")
    }

    private fun handleSettingsButton(buttonId: String, state: String) {
        if (state != "down") return
        val overlay = findViewById<com.dopple.webview.ui.system.SettingsOverlayView>(R.id.settings_overlay)
            ?: return
        when (buttonId) {
            "A" -> overlay.navigateNext()
            "B" -> overlay.activateItem()
        }
    }

    // ==================== Helper Methods ====================

    private fun getActiveWebView(): android.webkit.WebView? {
        return getCurrentGameFragment()?.getWebView()
            ?: getCurrentGalleryWebViewFragment()?.getWebView()
    }

    private fun getActiveBridge(): com.dopple.webview.bridge.WebAppInterface? {
        return getCurrentGameFragment()?.getBridge()
            ?: getCurrentGalleryWebViewFragment()?.getBridge()
    }

    private fun isOnGalleryFragment(): Boolean {
        return navController.currentDestination?.id == R.id.galleryFragment
    }

    private fun isOnGameFragment(): Boolean {
        return navController.currentDestination?.id == R.id.gameFragment
    }

    private fun getCurrentGameFragment(): GameFragment? {
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
        return navHostFragment?.childFragmentManager?.fragments?.firstOrNull {
            it is GameFragment
        } as? GameFragment
    }

    private fun getCurrentGalleryWebViewFragment(): GalleryWebViewFragment? {
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
        return navHostFragment?.childFragmentManager?.fragments?.firstOrNull {
            it is GalleryWebViewFragment
        } as? GalleryWebViewFragment
    }

    /**
     * Launch an activity by manifest URL (used by ADB deep links).
     */
    private fun launchByManifestUrl(manifestUrl: String) {
        Log.d(TAG, "Launching by manifest URL: $manifestUrl")

        // Navigate to GameFragment with the manifest URL
        val bundle = android.os.Bundle().apply {
            putString("manifestUrl", manifestUrl)
        }

        navController.navigate(R.id.gameFragment, bundle)
    }

    /**
     * Launch an activity from history by name (used by ADB deep links).
     */
    private fun launchFromHistory(activityName: String) {
        Log.d(TAG, "Launching from history: $activityName")

        val historyItem = historyManager.findByName(activityName)
        if (historyItem != null) {
            launchByManifestUrl(historyItem.manifestUrl)
        } else {
            Log.w(TAG, "Activity not found in history: $activityName")
        }
    }
}
