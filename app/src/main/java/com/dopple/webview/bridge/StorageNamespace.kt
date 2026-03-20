package com.dopple.webview.bridge

import android.content.Context
import android.webkit.JavascriptInterface
import com.dopple.webview.bridge.generated.StorageNamespaceContract

/**
 * Bridge namespace exposed as Loop$storage in JavaScript.
 * Provides per-game persistent key-value storage.
 *
 * Standalone class (like GalleryNamespace) — registered separately in fragments
 * after the manifest is resolved and projectId is available.
 *
 * Implements StorageNamespaceContract generated from bridge-contract.yaml.
 */
class StorageNamespace(
    context: Context,
    projectId: String
) : StorageNamespaceContract {

    private val manager = StorageManager(context, projectId)

    @JavascriptInterface
    override fun setItem(key: String, value: String): String = manager.setItem(key, value)

    @JavascriptInterface
    override fun getItem(key: String): String? = manager.getItem(key)

    @JavascriptInterface
    override fun removeItem(key: String): String = manager.removeItem(key)

    @JavascriptInterface
    override fun clear(): String = manager.clear()

    @JavascriptInterface
    override fun keys(): String = manager.keys()

    @JavascriptInterface
    override fun getUsage(): String = manager.getUsage()

    @JavascriptInterface
    override fun exists(key: String): Boolean = manager.exists(key)
}
