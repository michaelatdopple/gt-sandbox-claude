package com.dopple.webview.bridge

import android.content.Context
import android.util.Log
import android.webkit.WebView

/**
 * Utility for injecting the Loop SDK into WebView pages.
 *
 * The SDK is injected via evaluateJavascript() in onPageStarted
 * to ensure it's available before any page JavaScript runs.
 */
object LoopSDKInjector {
    private const val TAG = "LoopSDKInjector"
    private var cachedScript: String? = null
    private var cachedInternalScript: String? = null

    /**
     * Loads the Loop SDK script from assets.
     * Caches the script for subsequent injections.
     *
     * @param context Android context for asset access
     * @return The SDK script content, or null if loading failed
     */
    fun loadScript(context: Context): String? {
        cachedScript?.let { return it }

        return try {
            context.assets.open("loop-sdk.js").bufferedReader().use { reader ->
                reader.readText().also {
                    cachedScript = it
                    Log.d(TAG, "Loop SDK loaded (${it.length} bytes)")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load loop-sdk.js", e)
            null
        }
    }

    /**
     * Loads the internal SDK script from assets.
     * Caches the script for subsequent injections.
     *
     * @param context Android context for asset access
     * @return The internal SDK script content, or null if loading failed
     */
    private fun loadInternalScript(context: Context): String? {
        cachedInternalScript?.let { return it }

        return try {
            context.assets.open("internal-sdk.js").bufferedReader().use { reader ->
                reader.readText().also {
                    cachedInternalScript = it
                    Log.d(TAG, "Internal SDK loaded (${it.length} bytes)")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load internal-sdk.js", e)
            null
        }
    }

    /**
     * Injects the Loop SDK into a WebView.
     * Should be called from WebViewClient.onPageStarted().
     *
     * @param webView The WebView to inject into
     * @param context Android context for asset access
     */
    fun inject(webView: WebView, context: Context) {
        val script = loadScript(context)
        if (script == null) {
            Log.e(TAG, "Cannot inject - SDK script not available")
            return
        }

        webView.evaluateJavascript(script) { result ->
            Log.d(TAG, "Loop SDK injected successfully")
        }
    }

    /**
     * Injects the internal SDK into a WebView (gallery only).
     * Should be called after inject() in WebViewClient.onPageStarted().
     *
     * @param webView The WebView to inject into
     * @param context Android context for asset access
     */
    fun injectInternal(webView: WebView, context: Context) {
        val script = loadInternalScript(context)
        if (script == null) {
            Log.e(TAG, "Cannot inject - internal SDK script not available")
            return
        }

        webView.evaluateJavascript(script) { result ->
            Log.d(TAG, "Internal SDK injected successfully")
        }
    }

    /**
     * Clears the cached script (useful for testing or reload scenarios).
     */
    fun clearCache() {
        cachedScript = null
        cachedInternalScript = null
    }
}
