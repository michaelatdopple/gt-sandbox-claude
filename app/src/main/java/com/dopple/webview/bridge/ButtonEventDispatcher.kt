package com.dopple.webview.bridge

import android.app.Activity
import android.os.SystemClock
import android.util.Log
import android.webkit.WebView
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicLong

/**
 * Dispatches button events to JavaScript as CustomEvents.
 * Thread-safe and handles UI thread dispatch automatically.
 *
 * Events are dispatched with namespace `com.dopple.loop.bridge.button`
 * and contain button identifier, state, timestamp, and sequence number.
 */
class ButtonEventDispatcher(
    webView: WebView,
    activity: Activity
) {
    companion object {
        private const val TAG = "ButtonEventDispatcher"
        private const val TAG_LATENCY = "KeyLatency"
    }

    // Use weak references to prevent memory leaks
    private val webViewRef = WeakReference(webView)
    private val activityRef = WeakReference(activity)

    // Monotonically increasing sequence number for event ordering
    private val sequenceNumber = AtomicLong(0)

    // Track boot time for DOMHighResTimeStamp conversion
    private val bootTimeMs = System.currentTimeMillis() - SystemClock.elapsedRealtime()

    /**
     * Dispatches a button event to JavaScript.
     *
     * @param buttonId The button identifier (BUTTON_1, BUTTON_2, BUTTON_3)
     * @param state The button state ("down" or "up")
     */
    fun dispatch(buttonId: String, state: String) {
        val startTimeNs = System.nanoTime()
        val activity = activityRef.get() ?: run {
            Log.w(TAG, "Activity reference lost, cannot dispatch event")
            return
        }
        val webView = webViewRef.get() ?: run {
            Log.w(TAG, "WebView reference lost, cannot dispatch event")
            return
        }

        val eventJson = buildEventJson(buttonId, state)
        val script = buildDispatchScript(eventJson)

        activity.runOnUiThread {
            webView.evaluateJavascript(script) { _ ->
                val latencyMs = (System.nanoTime() - startTimeNs) / 1_000_000.0
                Log.d(TAG_LATENCY, "Button $buttonId $state dispatch latency: ${String.format("%.2f", latencyMs)}ms")
            }
        }
    }

    /**
     * Builds the JSON event detail object.
     */
    private fun buildEventJson(buttonId: String, state: String): String {
        val seq = sequenceNumber.incrementAndGet()
        // Use SystemClock.elapsedRealtime() for consistent high-resolution timestamps
        val timestamp = SystemClock.elapsedRealtime().toDouble()

        return buildString {
            append("{")
            append("\"button\":\"$buttonId\",")
            append("\"state\":\"$state\",")
            append("\"timestamp\":$timestamp,")
            append("\"sequenceNumber\":$seq")
            append("}")
        }
    }

    /**
     * Builds the JavaScript dispatch script.
     */
    private fun buildDispatchScript(eventJson: String): String {
        return "window.dispatchEvent(new CustomEvent('${BridgeEventTypes.BUTTON_EVENT}',{detail:$eventJson}));"
    }

    /**
     * Clears references for cleanup.
     */
    fun cleanup() {
        webViewRef.clear()
        activityRef.clear()
    }
}
