package com.dopple.webview.ui.orchestrator

import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Central coordinator for app navigation and state.
 *
 * All navigation logic lives here. Fragments are dumb views that:
 * - Render UI based on state
 * - Send events to orchestrator
 * - Never talk to each other directly
 *
 * Note: Scanner is now a mode within GalleryFragment, not a separate destination.
 */
class AppOrchestrator(
    private val navigate: (destination: String, args: Bundle?) -> Unit
) {
    companion object {
        const val DEST_GALLERY = "gallery"
        const val DEST_GAME = "game"
        const val DEST_LOVE2D = "love2d"

        const val ARG_MANIFEST_URL = "manifestUrl"
        const val ARG_SCROLL_TO_INDEX = "scrollToIndex"
    }

    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    private var launchSource: LaunchSource = LaunchSource.Gallery(0)

    // Reference to WebViewPool (set by MainActivity)
    var webViewPool: WebViewPool? = null

    // --- Gallery Events ---

    fun onGalleryLaunchGame(cardIndex: Int, manifestUrl: String, runtime: String = "webview") {
        launchSource = LaunchSource.Gallery(cardIndex)

        when (runtime) {
            "love2d" -> {
                navigate(DEST_LOVE2D, Bundle().apply {
                    putString(ARG_MANIFEST_URL, manifestUrl)
                })
            }
            else -> {
                webViewPool?.prefetch(manifestUrl)
                navigate(DEST_GAME, Bundle().apply {
                    putString(ARG_MANIFEST_URL, manifestUrl)
                })
            }
        }
    }

    // --- Game Events ---

    fun onGameExitToGallery() {
        when (val source = launchSource) {
            is LaunchSource.Gallery -> {
                navigate(DEST_GALLERY, Bundle().apply {
                    putInt(ARG_SCROLL_TO_INDEX, source.cardIndex)
                })
            }
            is LaunchSource.Scanner -> {
                // Scanner is now a mode in Gallery, return to Gallery
                navigate(DEST_GALLERY, null)
            }
        }
    }

    // --- State Updates ---

    fun setWebViewPoolReady(ready: Boolean) {
        _state.value = _state.value.copy(isWebViewPoolReady = ready)
    }

    fun setCurrentGameUrl(url: String?) {
        _state.value = _state.value.copy(currentGameUrl = url)
    }
}
