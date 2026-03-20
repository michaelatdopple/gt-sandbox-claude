package com.dopple.webview

import android.app.Application
import android.util.Log
import com.dopple.webview.ui.orchestrator.WebViewPool

/**
 * Application class for Dopple WebView.
 * Initializes global components like WebViewPool for instant game loading.
 */
class DoppleApplication : Application() {

    companion object {
        private const val TAG = "DoppleApplication"
    }

    override fun onCreate() {
        super.onCreate()

        Log.d(TAG, "Initializing DoppleApplication")

        // Initialize WebView pool for instant game loading
        WebViewPool.init(this)
    }

    override fun onTerminate() {
        super.onTerminate()

        // Clean up WebView pool
        WebViewPool.cleanup()
    }
}
