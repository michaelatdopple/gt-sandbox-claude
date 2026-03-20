package com.dopple.webview.data

/**
 * Data model for activity manifest JSON.
 *
 * Required fields: projectId, activityName, url
 * Optional fields: bundleUrl, version, iconPath (ignored), webViewResolution (ignored), runtime
 */
data class Manifest(
    val projectId: String,
    val activityName: String,
    val url: String,
    val bundleUrl: String? = null,
    val version: Int = 0,                 // Bundle version for caching; defaults to 0 for backward compatibility
    val iconPath: String? = null,         // Not used - ignored per spec
    val webViewResolution: String? = null, // Not used - ignored per spec
    // Note: Gson bypasses Kotlin default values during deserialization, setting
    // fields absent from JSON to null. Use nullable type with ?: fallback at call sites.
    val runtime: String? = "webview"      // "webview" (default) or "love2d"
)
