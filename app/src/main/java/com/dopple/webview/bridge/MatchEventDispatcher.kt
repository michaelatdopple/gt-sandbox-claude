package com.dopple.webview.bridge

import android.app.Activity
import android.util.Log
import android.webkit.WebView
import com.dopple.loop.services.MatchResult
import java.lang.ref.WeakReference

/**
 * Dispatches match results from ItemMatcher to JavaScript as CustomEvents.
 * Thread-safe and handles UI thread dispatch automatically.
 *
 * Events are dispatched as 'loop:match' CustomEvents with the following detail:
 * - requestId: The request ID from the capture call
 * - success: Boolean indicating if a valid match was found
 * - item: The matched item data (if success)
 * - distance: The match distance (if success)
 * - error: Error message (if failure)
 */
class MatchEventDispatcher(
    webView: WebView,
    activity: Activity
) {
    companion object {
        private const val TAG = "MatchEventDispatcher"
        private const val EVENT_NAME = "loop:match"

        // Match threshold - items with distance below this are considered successful matches
        private const val MATCH_THRESHOLD = 0.775f
    }

    private val webViewRef = WeakReference(webView)
    private val activityRef = WeakReference(activity)

    /**
     * Dispatches a match result to JavaScript.
     *
     * @param requestId The request ID from the capture call
     * @param result The MatchResult from ItemMatcher
     */
    fun dispatchResult(requestId: Int, result: MatchResult) {
        val activity = activityRef.get() ?: run {
            Log.w(TAG, "Activity reference lost, cannot dispatch result")
            return
        }
        val webView = webViewRef.get() ?: run {
            Log.w(TAG, "WebView reference lost, cannot dispatch result")
            return
        }

        // Find the best match (lowest distance)
        val topMatch = result.matchedItems?.minByOrNull { it.distance }
        val success = topMatch != null && topMatch.distance < MATCH_THRESHOLD

        val eventJson = buildResultJson(requestId, success, topMatch)
        val script = "window.dispatchEvent(new CustomEvent('$EVENT_NAME',{detail:$eventJson}));"

        activity.runOnUiThread {
            webView.evaluateJavascript(script) { _ ->
                Log.d(TAG, "Match result dispatched: requestId=$requestId, success=$success")
            }
        }
    }

    /**
     * Dispatches a match error to JavaScript.
     *
     * @param requestId The request ID from the capture call
     * @param errorMessage Description of the error
     */
    fun dispatchError(requestId: Int, errorMessage: String) {
        val activity = activityRef.get() ?: run {
            Log.w(TAG, "Activity reference lost, cannot dispatch error")
            return
        }
        val webView = webViewRef.get() ?: run {
            Log.w(TAG, "WebView reference lost, cannot dispatch error")
            return
        }

        val eventJson = buildErrorJson(requestId, errorMessage)
        val script = "window.dispatchEvent(new CustomEvent('$EVENT_NAME',{detail:$eventJson}));"

        activity.runOnUiThread {
            webView.evaluateJavascript(script) { _ ->
                Log.d(TAG, "Match error dispatched: requestId=$requestId, error=$errorMessage")
            }
        }
    }

    /**
     * Builds the JSON for a successful or failed match result.
     */
    private fun buildResultJson(
        requestId: Int,
        success: Boolean,
        topMatch: com.dopple.loop.services.MatchedItem?
    ): String {
        return buildString {
            append("{")
            append("\"requestId\":$requestId,")
            append("\"success\":$success")

            if (success && topMatch != null) {
                append(",\"item\":${topMatch.itemJson},")
                append("\"distance\":${topMatch.distance}")
            }

            append("}")
        }
    }

    /**
     * Builds the JSON for an error result.
     */
    private fun buildErrorJson(requestId: Int, errorMessage: String): String {
        // Escape quotes in error message for JSON safety
        val escapedMessage = errorMessage.replace("\"", "\\\"")

        return buildString {
            append("{")
            append("\"requestId\":$requestId,")
            append("\"success\":false,")
            append("\"error\":\"$escapedMessage\"")
            append("}")
        }
    }

    /**
     * Clears references for cleanup.
     */
    fun cleanup() {
        webViewRef.clear()
        activityRef.clear()
    }
}
