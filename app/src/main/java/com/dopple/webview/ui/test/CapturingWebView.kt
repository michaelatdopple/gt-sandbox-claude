package com.dopple.webview.ui.test

import android.content.Context
import android.util.Log
import android.webkit.ValueCallback
import android.webkit.WebView
import kotlinx.coroutines.delay

/**
 * WebView subclass that captures evaluateJavascript calls for test assertions.
 * Used by BleTestFragment to verify BLE events are dispatched correctly.
 */
class CapturingWebView(context: Context) : WebView(context) {

    companion object {
        private const val TAG = "CapturingWebView"
    }

    data class CapturedEvent(val script: String, val timestampMs: Long)

    private val captured = mutableListOf<CapturedEvent>()
    private val lock = Any()

    override fun evaluateJavascript(script: String, resultCallback: ValueCallback<String>?) {
        synchronized(lock) {
            captured.add(CapturedEvent(script, System.currentTimeMillis()))
        }
        Log.d(TAG, "Captured: $script")
        resultCallback?.onReceiveValue("null")
    }

    /** Get all captured scripts. */
    fun getCaptured(): List<CapturedEvent> {
        synchronized(lock) { return captured.toList() }
    }

    /** Clear captured events. */
    fun clearCaptured() {
        synchronized(lock) { captured.clear() }
    }

    /**
     * Wait for a captured event matching the given type string.
     * Searches for the event type in the captured script text.
     * Returns the matching event or null on timeout.
     */
    suspend fun waitForEvent(
        eventType: String,
        timeoutMs: Long = 10_000L,
        sinceMs: Long = 0L
    ): CapturedEvent? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            synchronized(lock) {
                val match = captured.firstOrNull {
                    it.timestampMs >= sinceMs && it.script.contains(eventType)
                }
                if (match != null) return match
            }
            delay(50)
        }
        return null
    }
}
