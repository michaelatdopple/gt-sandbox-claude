package com.dopple.webview.ui.orchestrator

import android.annotation.SuppressLint
import android.app.Application
import android.graphics.Bitmap
import android.net.http.SslError
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.dopple.webview.server.SslHelper
import java.util.concurrent.ConcurrentHashMap

/**
 * WebView Pool for instant game loading.
 *
 * Maintains a pool of pre-configured WebViews that can be warmed up with URLs
 * before the user commits to launching. This eliminates WebView creation overhead
 * and enables background preloading during hold gesture progress.
 *
 * Usage:
 * 1. Initialize pool early: WebViewPool.init(application)
 * 2. Start prefetch at ~20% hold progress: WebViewPool.prefetch(manifestUrl)
 * 3. Acquire preloaded WebView: WebViewPool.acquire(activity)
 * 4. Release when done: WebViewPool.release(webView)
 *
 * The pool uses MutableContextWrapper to safely swap contexts and avoid Activity leaks.
 */
object WebViewPool {

    private const val TAG = "WebViewPool"
    private const val POOL_SIZE = 2  // Keep 2 WebViews ready

    private var application: Application? = null
    private val pool = mutableListOf<PooledWebView>()
    private val prefetchedViews = ConcurrentHashMap<String, PooledWebView>()

    // Callbacks for prefetch completion
    private val prefetchCallbacks = ConcurrentHashMap<String, MutableList<(Boolean) -> Unit>>()

    /**
     * Wrapper for pooled WebViews with their mutable context.
     */
    data class PooledWebView(
        val webView: WebView,
        val contextWrapper: MutableContextWrapper,
        var loadedUrl: String? = null,
        var isReady: Boolean = false
    )

    /**
     * Initialize the pool. Call this from Application.onCreate().
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun init(app: Application) {
        if (application != null) return
        application = app

        Log.d(TAG, "Initializing WebView pool with size $POOL_SIZE")

        // Pre-create pooled WebViews on background thread
        repeat(POOL_SIZE) {
            createPooledWebView()?.let { pool.add(it) }
        }

        Log.d(TAG, "Pool initialized with ${pool.size} WebViews")
    }

    /**
     * Reserve a WebView for upcoming use.
     * Call this during hold progress (~20%) to have a WebView ready.
     *
     * Unlike actual prefetching (which would load a URL), this just reserves
     * a pre-created WebView so it's immediately available when needed.
     *
     * @param key Unique key (e.g., manifest URL) to associate with the reserved WebView
     * @param onReady Optional callback when WebView is reserved
     */
    fun prefetch(key: String, onReady: ((Boolean) -> Unit)? = null) {
        val app = application
        if (app == null) {
            Log.w(TAG, "Pool not initialized, skipping prefetch")
            onReady?.invoke(false)
            return
        }

        // Check if already reserved
        if (prefetchedViews.containsKey(key)) {
            Log.d(TAG, "WebView already reserved for: $key")
            onReady?.invoke(true)
            return
        }

        Log.d(TAG, "Reserving WebView for: $key")

        // Get a WebView from pool or create new one
        val pooled = synchronized(pool) {
            if (pool.isNotEmpty()) pool.removeAt(0) else null
        } ?: createPooledWebView()

        if (pooled == null) {
            Log.e(TAG, "Failed to get WebView for prefetch")
            onReady?.invoke(false)
            return
        }

        // Mark as reserved
        prefetchedViews[key] = pooled
        pooled.loadedUrl = key
        pooled.isReady = true  // Ready for use (not loading content yet)

        Log.d(TAG, "WebView reserved and ready for: $key")
        onReady?.invoke(true)
    }

    /**
     * Cancel a pending prefetch if the user releases before hold completes.
     */
    fun cancelPrefetch(url: String) {
        val pooled = prefetchedViews.remove(url) ?: return
        prefetchCallbacks.remove(url)

        Log.d(TAG, "Cancelling prefetch for: $url")

        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                pooled.webView.stopLoading()
                pooled.webView.loadUrl("about:blank")
                pooled.loadedUrl = null
                pooled.isReady = false

                // Return to pool
                synchronized(pool) {
                    if (pool.size < POOL_SIZE) {
                        pool.add(pooled)
                    } else {
                        // Pool full, destroy
                        destroyPooledWebView(pooled)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error cancelling prefetch", e)
            }
        }
    }

    /**
     * Check if a URL has been prefetched and is ready.
     */
    fun isPrefetched(url: String): Boolean {
        return prefetchedViews[url]?.isReady == true
    }

    /**
     * Get the prefetch ready state for a URL.
     * Returns null if not prefetching, false if in progress, true if ready.
     */
    fun getPrefetchState(url: String): Boolean? {
        val pooled = prefetchedViews[url] ?: return null
        return pooled.isReady
    }

    /**
     * Acquire a WebView for use.
     * If a prefetched WebView exists for the URL, returns it.
     * Otherwise returns a fresh pooled WebView.
     *
     * @param context Activity context to bind to
     * @param url Optional URL to get prefetched WebView for
     * @return WebView ready for use, or null if pool exhausted
     */
    fun acquire(context: android.content.Context, url: String? = null): WebView? {
        // Try to get prefetched WebView
        if (url != null) {
            val prefetched = prefetchedViews.remove(url)
            if (prefetched != null) {
                Log.d(TAG, "Returning prefetched WebView for: $url")
                prefetched.contextWrapper.setBaseContext(context)
                return prefetched.webView
            }
        }

        // Get from pool
        val pooled = synchronized(pool) {
            if (pool.isNotEmpty()) pool.removeAt(0) else null
        }

        if (pooled != null) {
            Log.d(TAG, "Returning pooled WebView")
            pooled.contextWrapper.setBaseContext(context)
            return pooled.webView
        }

        // Create new if pool empty
        Log.d(TAG, "Pool empty, creating new WebView")
        val newPooled = createPooledWebView()
        newPooled?.contextWrapper?.setBaseContext(context)
        return newPooled?.webView
    }

    /**
     * Release a WebView back to the pool.
     * Call this when done with the WebView (e.g., in Fragment.onDestroyView).
     */
    fun release(webView: WebView) {
        val app = application ?: return

        Log.d(TAG, "Releasing WebView to pool")

        // Find the pooled wrapper
        val existing = prefetchedViews.values.find { it.webView == webView }
            ?: pool.find { it.webView == webView }

        if (existing != null) {
            // Reset and return to pool
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                resetWebView(existing)
                existing.contextWrapper.setBaseContext(app.applicationContext)

                synchronized(pool) {
                    if (pool.size < POOL_SIZE && !pool.contains(existing)) {
                        pool.add(existing)
                    }
                }
            }
        } else {
            // Unknown WebView, just destroy it
            Log.w(TAG, "Unknown WebView, destroying instead of pooling")
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                webView.destroy()
            }
        }
    }

    /**
     * Clean up all pooled WebViews.
     * Call this when the app is being destroyed.
     */
    fun cleanup() {
        Log.d(TAG, "Cleaning up WebView pool")

        synchronized(pool) {
            pool.forEach { destroyPooledWebView(it) }
            pool.clear()
        }

        prefetchedViews.values.forEach { destroyPooledWebView(it) }
        prefetchedViews.clear()
        prefetchCallbacks.clear()

        application = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createPooledWebView(): PooledWebView? {
        val app = application ?: return null

        return try {
            val contextWrapper = MutableContextWrapper(app.applicationContext)
            val webView = WebView(contextWrapper)

            // Configure WebView
            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                allowFileAccess = true
                allowContentAccess = true
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs = true
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs = true
                useWideViewPort = true
                loadWithOverviewMode = false
                cacheMode = WebSettings.LOAD_DEFAULT
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
                textZoom = 100
            }

            webView.setInitialScale(100)
            webView.isVerticalScrollBarEnabled = false
            webView.isHorizontalScrollBarEnabled = false
            webView.overScrollMode = View.OVER_SCROLL_NEVER
            webView.scrollTo(0, 0)
            webView.setPadding(0, 0, 0, 0)

            // Black background for seamless loading transition
            webView.setBackgroundColor(android.graphics.Color.BLACK)

            // Start invisible
            webView.visibility = View.GONE

            PooledWebView(webView, contextWrapper)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create pooled WebView", e)
            null
        }
    }

    private fun resetWebView(pooled: PooledWebView) {
        try {
            pooled.webView.apply {
                stopLoading()
                loadUrl("about:blank")
                clearHistory()
                // Don't clear cache to preserve shared resources
                webViewClient = WebViewClient()
                visibility = View.GONE

                // Remove from any parent
                (parent as? ViewGroup)?.removeView(this)
            }
            pooled.loadedUrl = null
            pooled.isReady = false
        } catch (e: Exception) {
            Log.e(TAG, "Error resetting WebView", e)
        }
    }

    private fun destroyPooledWebView(pooled: PooledWebView) {
        try {
            pooled.webView.apply {
                stopLoading()
                loadUrl("about:blank")
                clearHistory()
                clearCache(true)
                removeAllViews()
                destroy()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error destroying WebView", e)
        }
    }

    private fun notifyPrefetchComplete(url: String, success: Boolean) {
        prefetchCallbacks.remove(url)?.forEach { callback ->
            try {
                callback(success)
            } catch (e: Exception) {
                Log.e(TAG, "Error in prefetch callback", e)
            }
        }
    }
}
