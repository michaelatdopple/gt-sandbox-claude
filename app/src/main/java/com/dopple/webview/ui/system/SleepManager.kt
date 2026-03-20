package com.dopple.webview.ui.system

import android.app.Activity
import android.util.Log
import android.view.WindowManager
import android.webkit.WebView

/**
 * Manages device sleep/wake: blanks screen and dispatches pause/resume events.
 *
 * Sleep: brightness → 0, dispatch loop:pause {reason:'sleep'}
 * Wake:  brightness → restore, dispatch loop:resume {reason:'sleep', pausedMs}
 */
class SleepManager(private val activity: Activity) {

    companion object {
        private const val TAG = "SleepManager"
    }

    private var sleeping = false
    private var sleepTimestamp = 0L
    private var savedBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE

    val isSleeping: Boolean get() = sleeping

    fun sleep(webView: WebView?) {
        if (sleeping) return
        sleeping = true
        sleepTimestamp = System.currentTimeMillis()

        // Save and zero brightness
        savedBrightness = activity.window.attributes.screenBrightness
        setBrightness(0f)

        // Dispatch JS event
        webView?.evaluateJavascript(
            "window.dispatchEvent(new CustomEvent('loop:pause', {detail:{reason:'sleep'}}))",
            null
        )

        Log.d(TAG, "Sleep activated")
    }

    fun wake(webView: WebView?) {
        if (!sleeping) return
        val pausedMs = System.currentTimeMillis() - sleepTimestamp
        sleeping = false

        // Restore brightness
        setBrightness(savedBrightness)

        // Dispatch JS event with duration
        webView?.evaluateJavascript(
            "window.dispatchEvent(new CustomEvent('loop:resume', {detail:{reason:'sleep',pausedMs:$pausedMs}}))",
            null
        )

        Log.d(TAG, "Wake activated (pausedMs=$pausedMs)")
    }

    private fun setBrightness(value: Float) {
        val params = activity.window.attributes
        params.screenBrightness = value
        activity.window.attributes = params
    }
}
