package com.dopple.webview.bridge.ble

import android.app.Activity
import android.util.Log
import android.webkit.WebView
import com.dopple.webview.bridge.ble.model.PlayerInfo
import java.lang.ref.WeakReference

/**
 * Dispatches BLE game events to JavaScript as CustomEvents.
 *
 * Thread-safe and handles UI thread dispatch automatically.
 * Events are dispatched with the `loop:ble:` namespace prefix.
 */
class BLEEventDispatcher(
    webView: WebView,
    activity: Activity
) {
    companion object {
        private const val TAG = "BLEEventDispatcher"

        // Event type constants
        const val EVENT_MESSAGE = "loop:ble:message"
        const val EVENT_PLAYER_JOINED = "loop:ble:playerJoined"
        const val EVENT_PLAYER_LEFT = "loop:ble:playerLeft"
        const val EVENT_CONNECTED = "loop:ble:connected"
        const val EVENT_DISCONNECTED = "loop:ble:disconnected"
        const val EVENT_RECONNECTING = "loop:ble:reconnecting"
        const val EVENT_ROLE_RESOLVED = "loop:ble:roleResolved"
    }

    private val webViewRef = WeakReference(webView)
    private val activityRef = WeakReference(activity)

    /**
     * Dispatch a message received event.
     *
     * @param data JSON string of the message data
     * @param from The player who sent the message
     */
    fun dispatchMessage(data: String, from: PlayerInfo) {
        val detail = """{"data":$data,"from":${from.toJson()}}"""
        dispatch(EVENT_MESSAGE, detail)
    }

    /**
     * Dispatch a player joined event (host receives this).
     *
     * @param player The player who joined
     */
    fun dispatchPlayerJoined(player: PlayerInfo) {
        val detail = """{"player":${player.toJson()}}"""
        dispatch(EVENT_PLAYER_JOINED, detail)
    }

    /**
     * Dispatch a player left event (host receives this).
     *
     * @param player The player who left
     */
    fun dispatchPlayerLeft(player: PlayerInfo) {
        val detail = """{"player":${player.toJson()}}"""
        dispatch(EVENT_PLAYER_LEFT, detail)
    }

    /**
     * Dispatch a connected event (client receives this when connected to host).
     *
     * @param host The host player info
     */
    fun dispatchConnected(host: PlayerInfo) {
        val detail = """{"host":${host.toJson()}}"""
        dispatch(EVENT_CONNECTED, detail)
    }

    /**
     * Dispatch a disconnected event.
     *
     * @param reason The reason for disconnection
     */
    fun dispatchDisconnected(reason: String) {
        val escapedReason = reason.replace("\"", "\\\"")
        val detail = """{"reason":"$escapedReason"}"""
        dispatch(EVENT_DISCONNECTED, detail)
    }

    /**
     * Dispatch a reconnecting event.
     */
    fun dispatchReconnecting() {
        dispatch(EVENT_RECONNECTING, "{}")
    }

    /**
     * Dispatch a role resolved event (seed-play negotiation complete).
     *
     * @param role "host" or "client"
     * @param token The derived BLE token
     */
    fun dispatchRoleResolved(role: String, token: String) {
        val detail = """{"role":"$role","token":"$token"}"""
        dispatch(EVENT_ROLE_RESOLVED, detail)
    }

    /**
     * Internal dispatch method. Marshals to UI thread and evaluates JavaScript.
     */
    private fun dispatch(eventType: String, detail: String) {
        val activity = activityRef.get() ?: run {
            Log.w(TAG, "Activity reference lost, cannot dispatch $eventType")
            return
        }
        val webView = webViewRef.get() ?: run {
            Log.w(TAG, "WebView reference lost, cannot dispatch $eventType")
            return
        }

        val script = buildDispatchScript(eventType, detail)

        activity.runOnUiThread {
            webView.evaluateJavascript(script) { _ ->
                Log.d(TAG, "Dispatched $eventType")
            }
        }
    }

    /**
     * Builds the JavaScript dispatch script.
     */
    private fun buildDispatchScript(eventType: String, detail: String): String {
        return "window.dispatchEvent(new CustomEvent('$eventType',{detail:$detail}));"
    }

    /**
     * Clears references for cleanup.
     */
    fun cleanup() {
        webViewRef.clear()
        activityRef.clear()
    }
}
