package com.dopple.webview.ui.orchestrator

import android.content.Context
import android.content.ContextWrapper

/**
 * A ContextWrapper that allows swapping the base context.
 * Used by WebViewPool to avoid Activity leaks when pooling WebViews.
 *
 * WebViews hold a reference to their creation context. By using this wrapper,
 * we can detach from Activity contexts when returning WebViews to the pool
 * and attach to new Activity contexts when acquiring them.
 */
class MutableContextWrapper(base: Context) : ContextWrapper(base) {

    /**
     * Set a new base context.
     * Call this when acquiring a WebView from the pool to bind to current Activity,
     * or when releasing to bind to Application context (avoiding Activity leak).
     */
    fun setBaseContext(context: Context) {
        attachBaseContext(context)
    }

    @android.annotation.SuppressLint("DiscouragedPrivateApi")
    override fun attachBaseContext(base: Context?) {
        // Use reflection to clear existing base context first
        try {
            val field = ContextWrapper::class.java.getDeclaredField("mBase")
            field.isAccessible = true
            field.set(this, base)
        } catch (e: Exception) {
            // Fall back to super if reflection fails (shouldn't happen)
            super.attachBaseContext(base)
        }
    }
}
