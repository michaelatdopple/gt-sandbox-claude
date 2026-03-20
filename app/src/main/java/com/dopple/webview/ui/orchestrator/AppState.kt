package com.dopple.webview.ui.orchestrator

/**
 * Tracks where a game was launched from, for return navigation.
 */
sealed class LaunchSource {
    data class Gallery(val cardIndex: Int) : LaunchSource()
    object Scanner : LaunchSource()
}

/**
 * Shared app state exposed by AppOrchestrator.
 */
data class AppState(
    val isWebViewPoolReady: Boolean = false,
    val currentGameUrl: String? = null
)
