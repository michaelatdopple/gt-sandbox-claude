package com.dopple.webview.history

/**
 * Data class representing an activity in the launch history.
 */
data class ActivityHistoryItem(
    val projectId: String,
    val activityName: String,
    val manifestUrl: String,
    val thumbnailPath: String? = null,
    val lastLaunchTime: Long = System.currentTimeMillis(),
    val launchCount: Int = 1
)
