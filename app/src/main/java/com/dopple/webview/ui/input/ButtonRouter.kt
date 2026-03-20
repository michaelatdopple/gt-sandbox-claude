package com.dopple.webview.ui.input

/**
 * Centralizes hardware button routing decisions.
 * Pure Kotlin — no Android imports. Testable with lambda callbacks.
 *
 * C is global — routed before context dispatch:
 *   tap  → sleep
 *   hold → open/dismiss settings
 *
 * A/B routing is context-specific:
 *   GALLERY            → gallery bridge
 *   SCANNER            → A-down exits scanner; B forwards to gallery bridge
 *   GAME               → game bridge
 *   SETTINGS_OVERLAY    → settings handler
 *   SLEEP              → wake (any button)
 */
class ButtonRouter(
    private val onSleep: () -> Unit,
    private val onWake: (previousContext: Context) -> Unit,
    private val onOpenSettings: () -> Unit,
    private val onDismissSettings: () -> Unit,
    private val onForwardToBridge: (buttonId: String, state: String) -> Unit,
    private val onForwardToGalleryBridge: (buttonId: String, state: String) -> Unit,
    private val onForwardToSettings: (buttonId: String, state: String) -> Unit,
    private val onExitScanner: () -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis
) {
    enum class Context {
        GALLERY, SCANNER, GAME, SETTINGS_OVERLAY, SLEEP
    }

    companion object {
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val HOLD_THRESHOLD_MS = 600L
    }

    /** Context before entering sleep — passed to onWake so caller can restore. */
    private var previousContext: Context = Context.GALLERY

    /** Timestamp of last C-down for hold detection. */
    private var cDownTimestamp = 0L

    /**
     * Route a button event. Returns true if consumed.
     */
    fun route(context: Context, buttonId: String, action: Int): Boolean {
        // SLEEP: any button-down wakes, everything consumed
        if (context == Context.SLEEP) {
            if (action == ACTION_DOWN) {
                onWake(previousContext)
            }
            return true
        }

        // C is global — handle before context dispatch
        if (buttonId == "C") {
            return routeC(context, action)
        }

        // A/B: context-specific routing
        return when (context) {
            Context.GALLERY -> routeGallery(buttonId, action)
            Context.SCANNER -> routeScanner(buttonId, action)
            Context.GAME -> routeGame(buttonId, action)
            Context.SETTINGS_OVERLAY -> routeSettings(buttonId, action)
            Context.SLEEP -> true // unreachable — handled above
        }
    }

    private fun routeC(context: Context, action: Int): Boolean {
        if (action == ACTION_DOWN) {
            cDownTimestamp = clock()
            return true
        }

        // ACTION_UP — decide tap vs hold
        val elapsed = clock() - cDownTimestamp
        if (elapsed >= HOLD_THRESHOLD_MS) {
            // HOLD
            when (context) {
                Context.SETTINGS_OVERLAY -> onDismissSettings()
                else -> onOpenSettings()
            }
        } else {
            // TAP → sleep
            previousContext = context
            onSleep()
        }
        return true
    }

    private fun routeGallery(buttonId: String, action: Int): Boolean {
        val state = if (action == ACTION_DOWN) "down" else "up"
        onForwardToGalleryBridge(buttonId, state)
        return true
    }

    private fun routeScanner(buttonId: String, action: Int): Boolean {
        if (buttonId == "A" && action == ACTION_DOWN) {
            onExitScanner()
            return true
        }
        // B and A-up: forward to gallery bridge (hold-to-open-scanner logic lives in JS)
        val state = if (action == ACTION_DOWN) "down" else "up"
        onForwardToGalleryBridge(buttonId, state)
        return true
    }

    private fun routeGame(buttonId: String, action: Int): Boolean {
        val state = if (action == ACTION_DOWN) "down" else "up"
        onForwardToBridge(buttonId, state)
        return true
    }

    private fun routeSettings(buttonId: String, action: Int): Boolean {
        val state = if (action == ACTION_DOWN) "down" else "up"
        onForwardToSettings(buttonId, state)
        return true
    }
}
