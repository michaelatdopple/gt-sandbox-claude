package com.dopple.webview.cache

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.io.File

/**
 * Manages bundle caching operations including version tracking and integrity validation.
 */
class BundleCacheManager(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "bundle_cache_prefs"
        private const val VERSION_KEY_PREFIX = "bundle_version_"
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Gets the cached version for a project, or null if not cached.
     */
    fun getCachedVersion(projectId: String): Int? {
        val key = "$VERSION_KEY_PREFIX$projectId"
        return if (prefs.contains(key)) prefs.getInt(key, 0) else null
    }

    /**
     * Stores the version for a project in SharedPreferences.
     */
    fun setCachedVersion(projectId: String, version: Int) {
        prefs.edit { putInt("$VERSION_KEY_PREFIX$projectId", version) }
    }

    /**
     * Returns the bundle directory path for a project.
     */
    fun getBundleDirectory(projectId: String): File {
        return File(File(context.filesDir, "bundles"), projectId)
    }

    /**
     * Checks if bundle directory exists for a project.
     */
    fun isBundleCached(projectId: String): Boolean {
        return getBundleDirectory(projectId).exists()
    }

    /**
     * Validates bundle integrity by checking if index.html exists.
     */
    fun validateCachedBundle(projectId: String): Boolean {
        val indexFile = File(getBundleDirectory(projectId), "index.html")
        return indexFile.exists()
    }
}
