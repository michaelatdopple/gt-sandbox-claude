package com.dopple.webview.bridge

import android.webkit.JavascriptInterface
import com.dopple.webview.bridge.generated.GalleryNamespaceContract
import org.json.JSONArray
import org.json.JSONObject

/**
 * Bridge namespace exposed as Loop$gallery in JavaScript.
 * Provides gallery-specific native capabilities to the React gallery.
 *
 * Implements GalleryNamespaceContract generated from bridge-contract.yaml.
 */
class GalleryNamespace(
    private val historyProvider: () -> List<HistoryEntry>,
    private val onLaunch: (manifestUrl: String) -> Unit,
    private val onPrepareScanner: () -> Unit = {},
    private val onOpenScanner: () -> Unit = {},
    private val onCloseScanner: () -> Unit = {}
) : GalleryNamespaceContract {

    data class HistoryEntry(
        val projectId: String,
        val activityName: String,
        val manifestUrl: String,
        val thumbnailPath: String?,
        val launchCount: Int
    )

    @JavascriptInterface
    override fun getHistory(): String {
        val entries = historyProvider()
        val array = JSONArray()
        for (entry in entries) {
            array.put(JSONObject().apply {
                put("projectId", entry.projectId)
                put("activityName", entry.activityName)
                put("manifestUrl", entry.manifestUrl)
                put("thumbnailUrl", resolveThumbnailUrl(entry.thumbnailPath))
                put("launchCount", entry.launchCount)
            })
        }
        return array.toString()
    }

    @JavascriptInterface
    override fun launchActivity(manifestUrl: String) {
        onLaunch(manifestUrl)
    }

    @JavascriptInterface
    override fun prepareScanner() {
        onPrepareScanner()
    }

    @JavascriptInterface
    override fun openScanner() {
        onOpenScanner()
    }

    @JavascriptInterface
    override fun closeScanner() {
        onCloseScanner()
    }

    private fun resolveThumbnailUrl(path: String?): Any {
        if (path == null) return JSONObject.NULL
        if (path.startsWith("drawable://")) {
            val name = path.removePrefix("drawable://")
            return "http://127.0.0.1:8088/game-icons/$name.png"
        }
        return path
    }
}
