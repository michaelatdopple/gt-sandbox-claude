package com.dopple.webview.history

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Manages activity launch history using SharedPreferences with JSON storage.
 * Follows BundleCacheManager pattern for consistency.
 *
 * Features:
 * - 20-item limit with LRU eviction
 * - Persists across app restarts
 * - Tracks launch count and last launch time
 */
class ActivityHistoryManager(private val context: Context) {

    companion object {
        private const val TAG = "ActivityHistoryManager"
        private const val PREFS_NAME = "activity_history_prefs"
        private const val HISTORY_KEY = "activity_history"
        private const val MAX_HISTORY_SIZE = 20

        /**
         * Default activities that always appear in the gallery.
         * These are built-in demo apps that ship with the device.
         * Served by AssetHttpServer on localhost:8088.
         *
         * Thumbnail URIs:
         * - drawable://resource_name -> bundled drawable resource
         * - file path -> local file
         *
         * Demo cards use demo:// manifestUrl prefix and are not launchable.
         */
        private val DEFAULT_ACTIVITIES = listOf(
            ActivityHistoryItem(
                projectId = "tictactoe-ble-demo",
                activityName = "Tic-Tac-Toe",
                manifestUrl = "http://127.0.0.1:8088/games/tictactoe/manifest.json",
                thumbnailPath = "drawable://ic_tictactoe",
                lastLaunchTime = 0,
                launchCount = 0
            ),
            ActivityHistoryItem(
                projectId = "loop-sdk-explorer",
                activityName = "SDK Explorer",
                manifestUrl = "http://127.0.0.1:8088/games/sdk-explorer/manifest.json",
                thumbnailPath = "drawable://ic_sdk_explorer",
                lastLaunchTime = 0,
                launchCount = 0
            )
        )
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val gson = Gson()

    /**
     * Add or update an activity in history.
     * If activity exists, updates lastLaunchTime and increments launchCount.
     * If new, adds to history (evicting oldest if at capacity).
     */
    fun addActivity(
        projectId: String,
        activityName: String,
        manifestUrl: String,
        thumbnailPath: String? = null
    ) {
        val history = getHistoryOnly().toMutableList()

        // Check if activity already exists (by projectId)
        val existingIndex = history.indexOfFirst { it.projectId == projectId }

        if (existingIndex >= 0) {
            // Update existing entry
            val existing = history[existingIndex]
            val updated = existing.copy(
                activityName = activityName,
                manifestUrl = manifestUrl,
                thumbnailPath = thumbnailPath ?: existing.thumbnailPath,
                lastLaunchTime = System.currentTimeMillis(),
                launchCount = existing.launchCount + 1
            )
            // Move to front (most recent)
            history.removeAt(existingIndex)
            history.add(0, updated)
        } else {
            // Add new entry at front
            val newItem = ActivityHistoryItem(
                projectId = projectId,
                activityName = activityName,
                manifestUrl = manifestUrl,
                thumbnailPath = thumbnailPath,
                lastLaunchTime = System.currentTimeMillis(),
                launchCount = 1
            )
            history.add(0, newItem)

            // Evict oldest if over capacity
            while (history.size > MAX_HISTORY_SIZE) {
                history.removeAt(history.size - 1)
            }
        }

        saveHistory(history)
        Log.d(TAG, "Activity added/updated: $activityName (${history.size} total)")
    }

    /**
     * Get all activities in history, ordered by most recent first.
     * Includes default activities at the end (if not already launched).
     */
    fun getHistory(): List<ActivityHistoryItem> {
        val history = getHistoryOnly()

        // Append default activities that aren't already in history
        val historyIds = history.map { it.projectId }.toSet()
        val defaults = DEFAULT_ACTIVITIES.filter { it.projectId !in historyIds }

        return history + defaults
    }

    /**
     * Get only the user's launch history (no defaults).
     */
    private fun getHistoryOnly(): List<ActivityHistoryItem> {
        val json = prefs.getString(HISTORY_KEY, null) ?: return emptyList()

        return try {
            val type = object : TypeToken<List<ActivityHistoryItem>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse history", e)
            emptyList()
        }
    }

    /**
     * Find an activity by name (case-insensitive partial match).
     */
    fun findByName(name: String): ActivityHistoryItem? {
        return getHistory().find {
            it.activityName.equals(name, ignoreCase = true)
        }
    }

    /**
     * Find an activity by project ID.
     */
    fun findByProjectId(projectId: String): ActivityHistoryItem? {
        return getHistory().find { it.projectId == projectId }
    }

    /**
     * Update thumbnail path for an activity.
     */
    fun updateThumbnail(projectId: String, thumbnailPath: String) {
        val history = getHistoryOnly().toMutableList()
        val index = history.indexOfFirst { it.projectId == projectId }

        if (index >= 0) {
            history[index] = history[index].copy(thumbnailPath = thumbnailPath)
            saveHistory(history)
            Log.d(TAG, "Thumbnail updated for: $projectId")
        }
    }

    /**
     * Remove an activity from history.
     */
    fun removeActivity(projectId: String) {
        val history = getHistoryOnly().toMutableList()
        val removed = history.removeAll { it.projectId == projectId }

        if (removed) {
            saveHistory(history)
            Log.d(TAG, "Activity removed: $projectId")
        }
    }

    /**
     * Clear all history.
     */
    fun clearHistory() {
        prefs.edit { remove(HISTORY_KEY) }
        Log.d(TAG, "History cleared")
    }

    /**
     * Get history count.
     */
    fun getHistoryCount(): Int = getHistory().size

    private fun saveHistory(history: List<ActivityHistoryItem>) {
        val json = gson.toJson(history)
        prefs.edit { putString(HISTORY_KEY, json) }
    }
}
