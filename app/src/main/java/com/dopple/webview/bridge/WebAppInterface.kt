// Bridge contract: see bridge-contract.yaml for the API surface map
package com.dopple.webview.bridge

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.SharedMemory
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.core.content.ContextCompat
import com.dopple.loop.services.IItemMatchCallback
import com.dopple.loop.services.IPackManager
import com.dopple.loop.services.MatchResult
import com.dopple.webview.bridge.ble.BLEGameManager
import com.dopple.webview.bridge.ble.LoopNegotiator
import com.dopple.webview.bridge.ble.ScanException
import com.dopple.webview.bridge.ble.ScanTimeoutException
import com.dopple.webview.bridge.ble.model.ConnectionState
import com.dopple.webview.bridge.generated.BLENamespaceContract
import com.dopple.webview.bridge.generated.ButtonNamespaceContract
import com.dopple.webview.bridge.generated.HapticsNamespaceContract
import com.dopple.webview.bridge.generated.IMUNamespaceContract
import com.dopple.webview.bridge.generated.ManifestNamespaceContract
import com.dopple.webview.bridge.generated.MatchNamespaceContract
import com.dopple.webview.bridge.generated.PackNamespaceContract
import com.dopple.webview.bridge.generated.SystemNamespaceContract
import com.dopple.webview.services.DoppleServiceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicInteger

/**
 * JavaScript interface exposed to WebView content via the Loop SDK.
 *
 * Provides six namespaces accessible through window.Loop:
 * - `motion`: Motion sensor streaming with subscribe/unsubscribe pattern
 * - `buttons`: Button event management (events dispatched automatically)
 * - `haptics`: Haptic feedback control with intensity and curves
 * - `match`: Object identification via ItemMatcher service
 * - `pack`: Asset retrieval via PackManager service
 * - `ble`: Local multiplayer via Bluetooth Low Energy
 *
 * Usage from JavaScript (via Loop SDK):
 * ```javascript
 * // Listen for button events
 * Loop.buttons.on('press', (e) => {
 *   console.log(e.button, e.state); // 'A', 'B', or 'C'
 * });
 *
 * // Subscribe to motion data
 * const motion = await Loop.motion.start({ frequency: 120 });
 * motion.on('data', (data) => {
 *   console.log(data.gravity, data.orientation);
 * });
 *
 * // Trigger haptic feedback
 * Loop.haptics.pulse(0.5);
 *
 * // Object identification
 * const status = JSON.parse(Loop$match.getStatus());
 * if (status.ready) {
 *   Loop$match.capture(); // Result via 'loop:match' event
 * }
 *
 * // Asset retrieval
 * const assetJson = Loop$pack.getAsset('items', 'models/example.glb');
 * ```
 */
class WebAppInterface(
    private val webView: WebView,
    private val activity: Activity,
    private val serviceManagerProvider: () -> DoppleServiceManager? = { null },
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    // Dynamic accessor for late-bound dependency
    private val serviceManager: DoppleServiceManager?
        get() = serviceManagerProvider()

    companion object {
        private const val TAG = "WebAppInterface"
        const val SDK_VERSION = "1.4.0"
    }

    // Component managers
    val buttonDispatcher = ButtonEventDispatcher(webView, activity)
    val imuSensorManager = IMUSensorManager(activity.applicationContext, webView, activity)
    val hapticsManager = HapticsManager(activity.applicationContext)
    val matchEventDispatcher = MatchEventDispatcher(webView, activity)
    private val freeRotateManager = FreeRotateManager { serviceManager?.systemSettings }

    // Weak reference to activity for lifecycle checks
    private val activityRef = WeakReference(activity)

    // Request ID counter for match operations
    private val nextRequestId = AtomicInteger(0)

    // Track if a match request is currently in progress to prevent spam
    @Volatile private var matchInProgress = false

    /**
     * Buttons namespace - exposed as Loop$buttons in JavaScript.
     * Button events are dispatched automatically as 'loop:button' CustomEvents.
     * Button identifiers use gaming convention: A, B, C.
     */
    inner class ButtonNamespace : ButtonNamespaceContract {
        // Button events are dispatched via ButtonEventDispatcher
        // No additional methods needed - all button handling is event-based
    }

    /**
     * Motion namespace - exposed as Loop$motion in JavaScript.
     * Provides sensor streaming with subscribe/unsubscribe pattern.
     * Events are dispatched as 'loop:motion' CustomEvents.
     */
    inner class IMUNamespace : IMUNamespaceContract {
        /**
         * Subscribes to motion sensor events.
         * Events will be dispatched as 'loop:motion' CustomEvent.
         * @return Unique subscription ID (UUID string)
         */
        @JavascriptInterface
        override fun subscribe(): String = imuSensorManager.subscribe()

        /**
         * Unsubscribes from IMU sensor events.
         * @param id Subscription ID returned from subscribe()
         * @return true if subscription was removed, false if ID not found
         */
        @JavascriptInterface
        override fun unsubscribe(id: String): Boolean = imuSensorManager.unsubscribe(id)

        /**
         * Sets the IMU publish frequency.
         * @param hz Frequency in Hz (1-240, default 60)
         * @return Actual frequency after clamping to valid range
         */
        @JavascriptInterface
        override fun setFrequency(hz: Int): Int = imuSensorManager.setFrequency(hz)

        /**
         * Sets the EMA smoothing alpha for gravity smoothing.
         * @param alpha Smoothing factor (0.0 = max smooth, 1.0 = no smoothing)
         * @return Actual alpha after clamping
         */
        @JavascriptInterface
        override fun setSmoothingAlpha(alpha: Float): Float = imuSensorManager.setSmoothingAlpha(alpha)

        /**
         * Switches the orientation sensor type.
         * @param type "game" (TYPE_GAME_ROTATION_VECTOR, 6-axis) or "full" (TYPE_ROTATION_VECTOR, 9-axis)
         * @return true if switch succeeded
         */
        @JavascriptInterface
        override fun setSensorFusion(type: String): Boolean = imuSensorManager.setSensorFusion(type)

        /**
         * Returns current IMU streaming status.
         * @return JSON: {active, subscriptions, frequencyHz, smoothingAlpha, paused}
         */
        @JavascriptInterface
        override fun getStatus(): String = imuSensorManager.getStatus()

        /**
         * Returns the latest IMU data without requiring subscription.
         * Useful for polling scenarios.
         * @return JSON IMU data or null if no data available
         */
        @JavascriptInterface
        override fun getLatest(): String? = imuSensorManager.getLatest()

        /**
         * Returns sensor availability status.
         * @return JSON: {accelerometer, gyroscope, rotationVector}
         */
        @JavascriptInterface
        override fun getSensorAvailability(): String = imuSensorManager.getSensorAvailability()
    }

    /**
     * Haptics namespace - exposed as Loop$haptics in JavaScript.
     * Provides haptic feedback control with intensity and curves.
     */
    inner class HapticsNamespace : HapticsNamespaceContract {
        /**
         * Returns haptics capability status.
         * @return JSON: {available, hasAmplitudeSupport}
         */
        @JavascriptInterface
        override fun getStatus(): String = hapticsManager.getStatus()

        /**
         * Triggers a single haptic pulse.
         * Clean API: accepts primitive intensity directly.
         * @param intensity Vibration intensity (0.0-1.0)
         * @return JSON: {success: boolean, error?: string}
         */
        @JavascriptInterface
        override fun pulse(intensity: Float): String {
            return hapticsManager.triggerOne(intensity)
        }

        /**
         * Plays a curve-based haptic pattern.
         * @param curveJson JSON string: {"keys": [{"time": 0, "value": 0.5}, ...], "strength": 1.0}
         * @return JSON: {success: boolean, durationMs?: number, error?: string}
         */
        @JavascriptInterface
        override fun playCurve(curveJson: String): String = hapticsManager.playCurve(curveJson)

        /**
         * Stops any ongoing haptic vibration.
         * @return JSON: {success: boolean, error?: string}
         */
        @JavascriptInterface
        override fun stop(): String = hapticsManager.stop()
    }

    /**
     * Match namespace - exposed as Loop$match in JavaScript.
     * Provides object identification via ItemMatcher service.
     * Events are dispatched as 'loop:match' CustomEvents.
     */
    inner class MatchNamespace : MatchNamespaceContract {
        /**
         * Returns whether ItemMatcher service is ready.
         * @return JSON: {"ready": boolean, "status": string}
         */
        @JavascriptInterface
        override fun getStatus(): String {
            val matcher = serviceManager?.itemMatcher
            val state = serviceManager?.state?.value

            return when {
                matcher == null -> """{"ready":false,"status":"disconnected"}"""
                state == DoppleServiceManager.State.INITIALIZING -> """{"ready":false,"status":"initializing"}"""
                state == DoppleServiceManager.State.READY -> {
                    try {
                        if (matcher.isInitialized) {
                            """{"ready":true,"status":"initialized"}"""
                        } else {
                            """{"ready":false,"status":"initializing"}"""
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error checking ItemMatcher status", e)
                        """{"ready":false,"status":"error"}"""
                    }
                }
                else -> """{"ready":false,"status":"${state?.name?.lowercase() ?: "unknown"}"}"""
            }
        }

        /**
         * Returns whether a match is currently in progress.
         * @return boolean - true if busy
         */
        @JavascriptInterface
        override fun isBusy(): Boolean = matchInProgress

        /**
         * Captures a frame from WebView canvas and initiates object matching.
         * This method receives a base64-encoded JPEG from JavaScript canvas capture,
         * decodes it to RGB24, and sends it to ItemMatcher for inference.
         * @param requestId Request ID from JavaScript for callback tracking
         * @param base64Image Base64-encoded JPEG image data
         */
        @JavascriptInterface
        override fun captureFrame(requestId: Int, base64Image: String) {
            val error = validateMatcherReady()
            if (error != null) {
                matchEventDispatcher.dispatchError(requestId, error)
                return
            }

            matchInProgress = true
            Log.d(TAG, "Starting captureFrame request $requestId")
            scope.launch { executeMatch(requestId, base64Image) }
        }

        private fun validateMatcherReady(): String? = when {
            matchInProgress -> "Scan in progress"
            else -> checkMatcherInitialized()
        }

        private fun checkMatcherInitialized(): String? {
            val matcher = serviceManager?.itemMatcher ?: return "ItemMatcher not available"
            return try {
                if (!matcher.isInitialized) "ItemMatcher not ready" else null
            } catch (e: Exception) {
                "Error checking ItemMatcher: ${e.message}"
            }
        }

        private suspend fun executeMatch(requestId: Int, base64Image: String) {
            var sharedMemory: SharedMemory? = null
            try {
                val imageBytes = Base64.decode(base64Image, Base64.DEFAULT)
                val bitmap = android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                    ?: error("Failed to decode image")
                val rgb24 = bitmapToRgb24(bitmap)
                val width = bitmap.width
                val height = bitmap.height
                bitmap.recycle()

                val currentMatcher = serviceManager?.itemMatcher
                    ?: error("ItemMatcher disconnected")
                val roll = getCurrentRoll()

                sharedMemory = SharedMemory.create("frame_$requestId", rgb24.size)
                val buffer = sharedMemory.mapReadWrite()
                buffer.put(rgb24)
                SharedMemory.unmap(buffer)

                val callback = matchCallback(requestId, sharedMemory)
                currentMatcher.matchRawCameraImageSharedMemoryWithVariantAndMaxN(
                    sharedMemory, width, height, roll, "mobileclip2", 1, callback
                )
            } catch (e: Exception) {
                matchInProgress = false
                Log.e(TAG, "Error during captureFrame", e)
                matchEventDispatcher.dispatchError(requestId, e.message ?: "Unknown error")
                sharedMemory?.runCatching { close() }
            }
        }

        private fun matchCallback(requestId: Int, sharedMemory: SharedMemory?) =
            object : IItemMatchCallback.Stub() {
                override fun onMatchResult(result: MatchResult) {
                    matchInProgress = false
                    Log.d(TAG, "captureFrame match result received for request $requestId")
                    matchEventDispatcher.dispatchResult(requestId, result)
                    sharedMemory?.runCatching { close() }
                }

                override fun onMatchError(matchNumber: Int, matchType: String, errorMessage: String) {
                    matchInProgress = false
                    Log.e(TAG, "captureFrame match error for request $requestId: $errorMessage")
                    matchEventDispatcher.dispatchError(requestId, errorMessage)
                    sharedMemory?.runCatching { close() }
                }
            }

        /**
         * Converts a Bitmap to RGB24 byte array.
         * @param bitmap Source bitmap
         * @return ByteArray in RGB24 format (R,G,B triplets)
         */
        private fun bitmapToRgb24(bitmap: android.graphics.Bitmap): ByteArray {
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

            val rgb24 = ByteArray(width * height * 3)
            for (i in pixels.indices) {
                val pixel = pixels[i]
                rgb24[i * 3] = ((pixel shr 16) and 0xFF).toByte()     // R
                rgb24[i * 3 + 1] = ((pixel shr 8) and 0xFF).toByte()  // G
                rgb24[i * 3 + 2] = (pixel and 0xFF).toByte()          // B
            }
            return rgb24
        }

        /**
         * Gets the current device roll angle from IMU.
         */
        private fun getCurrentRoll(): Float {
            // Get orientation from IMU sensor manager
            val latestJson = imuSensorManager.getLatest() ?: return 0f

            return try {
                val json = org.json.JSONObject(latestJson)
                val orientation = json.optJSONObject("orientation")
                if (orientation != null) {
                    // Calculate roll from quaternion
                    val x = orientation.optDouble("x", 0.0).toFloat()
                    val y = orientation.optDouble("y", 0.0).toFloat()
                    val z = orientation.optDouble("z", 0.0).toFloat()
                    val w = orientation.optDouble("w", 1.0).toFloat()

                    // Roll = atan2(2*(w*x + y*z), 1 - 2*(x*x + y*y))
                    kotlin.math.atan2(
                        2f * (w * x + y * z),
                        1f - 2f * (x * x + y * y)
                    )
                } else {
                    0f
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error parsing IMU data for roll", e)
                0f
            }
        }
    }

    /**
     * Pack namespace - exposed as Loop$pack in JavaScript.
     * Provides asset retrieval via PackManager service.
     */
    inner class PackNamespace : PackNamespaceContract {
        /**
         * Returns whether PackManager service is ready.
         * @return JSON: {"ready": boolean, "state": number}
         */
        @JavascriptInterface
        override fun getStatus(): String {
            val packManager = serviceManager?.packManager

            return if (packManager != null) {
                try {
                    val state = packManager.packState
                    val ready = state == IPackManager.PACK_STATE_READY
                    """{"ready":$ready,"state":$state}"""
                } catch (e: Exception) {
                    Log.e(TAG, "Error checking PackManager status", e)
                    """{"ready":false,"state":${IPackManager.PACK_STATE_ERROR}}"""
                }
            } else {
                """{"ready":false,"state":${IPackManager.PACK_STATE_UNINITIALIZED}}"""
            }
        }

        /**
         * Retrieves an asset from a pack as base64-encoded string.
         * @param packName Name of the pack (e.g., "items")
         * @param path Path within the pack (e.g., "models/marionette.glb")
         * @return JSON: {"success": boolean, "data"?: string, "error"?: string}
         */
        @JavascriptInterface
        override fun getAsset(packName: String, path: String): String {
            val packManager = serviceManager?.packManager
            if (packManager == null) {
                return """{"success":false,"error":"PackManager not available"}"""
            }

            return try {
                val bytes = packManager.getPackFileBytes(packName, path)
                if (bytes == null || bytes.isEmpty()) {
                    """{"success":false,"error":"Asset not found: $path"}"""
                } else {
                    val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    """{"success":true,"data":"$base64"}"""
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting asset: $packName/$path", e)
                """{"success":false,"error":"${e.message?.replace("\"", "\\\"")}"}"""
            }
        }

        /**
         * Checks if an asset exists in a pack.
         * @param packName Name of the pack
         * @param path Path within the pack
         * @return boolean - true if exists
         */
        @JavascriptInterface
        override fun assetExists(packName: String, path: String): Boolean {
            return try {
                serviceManager?.packManager?.packFileExists(packName, path) ?: false
            } catch (e: Exception) {
                Log.e(TAG, "Error checking asset existence", e)
                false
            }
        }
    }

    /**
     * Manifest namespace - exposed as Loop$manifest in JavaScript.
     * Provides manifest loading triggered by QR scans.
     */
    inner class ManifestNamespace : ManifestNamespaceContract {
        // Listener for manifest load requests
        private var manifestLoadListener: ((String) -> Unit)? = null

        /**
         * Set listener for manifest load requests from JavaScript.
         * Called by WebViewFragment to receive manifest URLs to load.
         */
        fun setManifestLoadListener(listener: (String) -> Unit) {
            manifestLoadListener = listener
        }

        /**
         * Load a manifest from URL (triggered by QR scan).
         * This will fetch the manifest, download bundle if needed, and load the app.
         * Results are dispatched via 'loop:manifest' CustomEvent.
         * @param url The manifest URL to load
         */
        @JavascriptInterface
        override fun load(url: String) {
            Log.d(TAG, "Loading manifest from QR: $url")

            // Dispatch to listener (WebViewFragment)
            manifestLoadListener?.invoke(url) ?: run {
                Log.e(TAG, "No manifest load listener registered")
                dispatchManifestError("Manifest loader not available")
            }
        }

        /**
         * Dispatch manifest loading success event to JavaScript.
         */
        fun dispatchSuccess(projectId: String, activityName: String) {
            val js = """
                window.dispatchEvent(new CustomEvent('loop:manifest', {
                    detail: {
                        success: true,
                        projectId: '$projectId',
                        activityName: '${activityName.replace("'", "\\'")}'
                    }
                }));
            """.trimIndent()

            activity.runOnUiThread {
                webView.evaluateJavascript(js, null)
            }
        }

        /**
         * Dispatch manifest loading error event to JavaScript.
         */
        fun dispatchManifestError(error: String) {
            val escapedError = error.replace("'", "\\'").replace("\n", "\\n")
            val js = """
                window.dispatchEvent(new CustomEvent('loop:manifest', {
                    detail: {
                        success: false,
                        error: '$escapedError'
                    }
                }));
            """.trimIndent()

            activity.runOnUiThread {
                webView.evaluateJavascript(js, null)
            }
        }

        /**
         * Dispatch manifest loading progress event to JavaScript.
         */
        fun dispatchProgress(percent: Int, message: String) {
            val escapedMessage = message.replace("'", "\\'")
            val js = """
                window.dispatchEvent(new CustomEvent('loop:manifest:progress', {
                    detail: {
                        percent: $percent,
                        message: '$escapedMessage'
                    }
                }));
            """.trimIndent()

            activity.runOnUiThread {
                webView.evaluateJavascript(js, null)
            }
        }
    }

    /**
     * BLE namespace - exposed as Loop$ble in JavaScript.
     * Provides local multiplayer via Bluetooth Low Energy.
     * Events are dispatched as 'loop:ble:*' CustomEvents.
     */
    @Suppress("TooManyFunctions") // contract interface + BLE lifecycle helpers
    inner class BLENamespace : BLENamespaceContract {
        private var gameManager: BLEGameManager? = null

        // Lazy initialization of game manager
        private fun getOrCreateManager(): BLEGameManager {
            return gameManager ?: BLEGameManager(
                activity.applicationContext,
                activity,
                webView
            ).also { gameManager = it }
        }

        // Check if BLE permissions are granted
        private fun hasBlePermissions(): Boolean {
            val scan = ContextCompat.checkSelfPermission(
                activity, Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED

            val connect = ContextCompat.checkSelfPermission(
                activity, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED

            val advertise = ContextCompat.checkSelfPermission(
                activity, Manifest.permission.BLUETOOTH_ADVERTISE
            ) == PackageManager.PERMISSION_GRANTED

            return scan && connect && advertise
        }

        // Request BLE permissions if not granted
        private fun ensureBlePermissions(): Boolean {
            if (hasBlePermissions()) return true

            // Request permissions
            activity.runOnUiThread {
                if (activity is androidx.activity.ComponentActivity) {
                    activity.requestPermissions(
                        arrayOf(
                            Manifest.permission.BLUETOOTH_SCAN,
                            Manifest.permission.BLUETOOTH_CONNECT,
                            Manifest.permission.BLUETOOTH_ADVERTISE
                        ),
                        1001
                    )
                }
            }
            return false
        }

        /**
         * Create a game as host (random token).
         */
        @JavascriptInterface
        override fun createGame(gameId: String): String =
            createGameImpl(gameId, null)

        /**
         * Create a game as host with a deterministic token.
         */
        @JavascriptInterface
        override fun createGame(gameId: String, token: String): String =
            createGameImpl(gameId, token.ifEmpty { null })

        private fun createGameImpl(gameId: String, deterministicToken: String?): String {
            if (!hasBlePermissions()) {
                ensureBlePermissions()
                return """{"success":false,"error":"BLE permissions required. Please grant permissions and try again."}"""
            }

            return try {
                val token = deterministicToken
                    ?: com.dopple.webview.bridge.ble.model.JoinCode.generateToken()
                scope.launch {
                    try {
                        getOrCreateManager().createGame(gameId, preGeneratedToken = token)
                        Log.d(TAG, "Game created with token: $token")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error creating game", e)
                    }
                }
                """{"success":true,"token":"$token"}"""
            } catch (e: Exception) {
                Log.e(TAG, "Error creating game", e)
                """{"success":false,"error":"${e.message?.replace("\"", "\\\"")}"}"""
            }
        }

        /**
         * Get the current BLE connection state.
         */
        @JavascriptInterface
        override fun getState(): String {
            val state = gameManager?.getState()?.toJsString() ?: "idle"
            return """{"success":true,"state":"$state"}"""
        }

        /**
         * End the game and disconnect all players (host only).
         */
        @JavascriptInterface
        override fun endGame() {
            try {
                gameManager?.endGame()
            } catch (e: Exception) {
                Log.e(TAG, "Error ending game", e)
            }
        }

        /**
         * Leave the game and disconnect from host (client only).
         * Stops any active scan and resets state to IDLE.
         */
        @JavascriptInterface
        override fun leaveGame() {
            try {
                gameManager?.leaveGame()
            } catch (e: Exception) {
                Log.e(TAG, "Error leaving game", e)
            }
        }

        /**
         * Send a message to player(s) or host.
         * @param dataJson The message data as JSON string
         * @param optionsJson Options: {to?: string}
         * @return JSON: success status
         */
        @JavascriptInterface
        override fun send(dataJson: String, optionsJson: String): String {
            return try {
                val options = org.json.JSONObject(optionsJson)
                val to = if (options.has("to") && !options.isNull("to")) options.getString("to") else null
                val manager = gameManager
                    ?: return """{"success":false,"error":"BLE not initialized"}"""
                dispatchSend(manager, dataJson, to)
            } catch (e: Exception) {
                Log.e(TAG, "Error sending message", e)
                """{"success":false,"error":"${e.message?.replace("\"", "\\\"")}"}"""
            }
        }

        private fun dispatchSend(manager: BLEGameManager, dataJson: String, to: String?): String =
            when (manager.getState()) {
                ConnectionState.CONNECTED -> {
                    scope.launch {
                        runCatching { manager.sendToHost(dataJson) }
                            .onFailure { Log.e(TAG, "Error sending to host", it) }
                    }
                    """{"success":true}"""
                }
                ConnectionState.HOSTING -> {
                    scope.launch {
                        runCatching {
                            if (!to.isNullOrEmpty()) manager.sendToPlayer(to, dataJson)
                            else manager.sendToAllPlayers(dataJson)
                        }.onFailure { Log.e(TAG, "Error sending to players", it) }
                    }
                    """{"success":true}"""
                }
                else -> """{"success":false,"error":"Not connected"}"""
            }

        /**
         * Join a game as a player using the host's token.
         * @param hostToken The token from the host's QR code or display
         * @param playerName The player's display name
         * @return JSON: success status
         */
        @JavascriptInterface
        override fun joinGame(hostToken: String, playerName: String): String {
            // Check BLE permissions first
            if (!hasBlePermissions()) {
                ensureBlePermissions()
                return """{"success":false,"error":"BLE permissions required. Please grant permissions and try again."}"""
            }

            return try {
                val manager = getOrCreateManager()
                scope.launch {
                    try {
                        manager.joinGame(hostToken, playerName)
                        // Connected event dispatched via 'connected' event
                    } catch (e: ScanTimeoutException) {
                        Log.e(TAG, "Scan timed out", e)
                        dispatchBleDisconnected(e.message)
                    } catch (e: ScanException) {
                        Log.e(TAG, "Scan failed", e)
                        dispatchBleDisconnected(e.message)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error joining game", e)
                        dispatchBleDisconnected(e.message)
                    }
                }
                """{"success":true}"""
            } catch (e: Exception) {
                Log.e(TAG, "Error in joinGame", e)
                """{"success":false,"error":"${e.message?.replace("\"", "\\\"")}"}"""
            }
        }

        /**
         * Play a game using seed-based auto-role negotiation.
         * Derives a BLE token from the seed; negotiates host/client role automatically.
         * Role result is communicated via `loop:ble:roleResolved` event.
         * @param seed Shared seed string (e.g. NFC tag ID, QR payload)
         * @param playerName The local player's display name
         * @return JSON: {success, token}
         */
        @JavascriptInterface
        override fun playGame(seed: String, playerName: String): String {
            if (!hasBlePermissions()) {
                ensureBlePermissions()
                return """{"success":false,"error":"BLE permissions required. Please grant permissions and try again."}"""
            }

            return try {
                val token = LoopNegotiator.deriveToken(seed)
                scope.launch {
                    try {
                        getOrCreateManager().playGame(seed, playerName)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in playGame", e)
                        dispatchBleDisconnected(e.message)
                    }
                }
                """{"success":true,"token":"$token"}"""
            } catch (e: Exception) {
                Log.e(TAG, "Error in playGame", e)
                """{"success":false,"error":"${e.message?.replace("\"", "\\\"")}"}"""
            }
        }

        /**
         * Dispatch a BLE disconnected event to JavaScript.
         * Used when scan/connection fails so the SDK Promise rejects immediately.
         */
        private fun dispatchBleDisconnected(message: String?) {
            val reason = message?.replace("\"", "\\\"") ?: "Connection failed"
            val js = "window.dispatchEvent(new CustomEvent(" +
                "'loop:ble:disconnected',{detail:{reason:\"$reason\"}}));"
            activity.runOnUiThread { webView.evaluateJavascript(js, null) }
        }

        /**
         * Clean up BLE resources.
         */
        fun cleanup() {
            gameManager?.cleanup()
            gameManager = null
        }
    }

    /**
     * System namespace - exposed as Loop$system in JavaScript.
     * Provides device system settings such as free rotate control.
     */
    inner class SystemNamespace : SystemNamespaceContract {
        /**
         * Returns whether free rotate (auto-rotation) is currently enabled.
         * @return JSON: {"enabled": boolean}
         */
        @JavascriptInterface
        override fun isFreeRotateEnabled(): String = freeRotateManager.isFreeRotateEnabled()

        /**
         * Enables or disables free rotate (auto-rotation).
         * @param enabled true to enable, false to disable
         * @return JSON: {"success": boolean}
         */
        @JavascriptInterface
        override fun setFreeRotate(enabled: Boolean): String = freeRotateManager.setFreeRotate(enabled)
    }

    // Namespace instances for JavaScript access
    val button = ButtonNamespace()
    val imu = IMUNamespace()
    val haptics = HapticsNamespace()
    val match = MatchNamespace()
    val pack = PackNamespace()
    val manifest = ManifestNamespace()
    val ble = BLENamespace()
    val system = SystemNamespace()

    /** Public accessor for FreeRotateManager lifecycle hooks (save/restore on game and settings transitions). */
    val freeRotate: FreeRotateManager get() = freeRotateManager

    /**
     * Dispatches a button event to JavaScript.
     * Called from MainActivity when hardware buttons are pressed.
     */
    fun dispatchButtonEvent(buttonId: String, state: String) {
        buttonDispatcher.dispatch(buttonId, state)
    }

    /**
     * Pauses sensor streaming (call from fragment onPause).
     */
    fun pause() {
        imuSensorManager.pause()
    }

    /**
     * Resumes sensor streaming (call from fragment onResume).
     */
    fun resume() {
        imuSensorManager.resume()
    }

    /**
     * Cleans up all resources (call from fragment onDestroyView).
     */
    fun cleanup() {
        buttonDispatcher.cleanup()
        imuSensorManager.cleanup()
        matchEventDispatcher.cleanup()
        ble.cleanup()
        activityRef.clear()
        Log.d(TAG, "WebAppInterface cleanup complete")
    }
}
