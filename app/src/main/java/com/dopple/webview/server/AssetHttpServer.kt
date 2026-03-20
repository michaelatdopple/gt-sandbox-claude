package com.dopple.webview.server

import android.content.Context
import android.util.Log
import android.webkit.MimeTypeMap
import fi.iki.elonen.NanoHTTPD
import java.io.IOException

/**
 * HTTP server for serving files from Android assets.
 * Used to serve game assets with proper CORS support for ES modules.
 */
class AssetHttpServer(private val context: Context) : NanoHTTPD("127.0.0.1", 8088) {

    init {
        setTempFileManagerFactory(AndroidTempFileManager.Factory(context))
    }

    companion object {
        private const val TAG = "AssetHttpServer"

        @Volatile
        @android.annotation.SuppressLint("StaticFieldLeak") // Uses applicationContext, no leak
        private var instance: AssetHttpServer? = null

        fun ensureRunning(context: Context): AssetHttpServer {
            return instance ?: synchronized(this) {
                instance ?: AssetHttpServer(context.applicationContext).also {
                    it.startServer()
                    instance = it
                }
            }
        }

        fun stopGlobal() {
            synchronized(this) {
                instance?.stop()
                instance = null
            }
        }

        private val MIME_TYPES = mapOf(
            "html" to "text/html",
            "htm" to "text/html",
            "js" to "application/javascript",
            "mjs" to "application/javascript",
            "json" to "application/json",
            "css" to "text/css",
            "png" to "image/png",
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "gif" to "image/gif",
            "svg" to "image/svg+xml",
            "ico" to "image/x-icon",
            "woff" to "font/woff",
            "woff2" to "font/woff2",
            "ttf" to "font/ttf",
            "glb" to "model/gltf-binary",
            "gltf" to "model/gltf+json",
            "love" to "application/zip"
        )
    }

    private var assetBasePath: String = ""

    fun startServer(assetPath: String = "") {
        this.assetBasePath = assetPath
        try {
            start(SOCKET_READ_TIMEOUT, false)
            Log.d(TAG, "Asset server started on http://127.0.0.1:8088")
            Log.d(TAG, "Serving assets from: $assetBasePath")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to start asset server", e)
        }
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        Log.d(TAG, "Request: ${session.method} $uri")

        // Handle OPTIONS for CORS preflight
        if (session.method == Method.OPTIONS) {
            return newFixedLengthResponse(Response.Status.OK, "text/plain", "").apply {
                addHeader("Access-Control-Allow-Origin", "*")
                addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
                addHeader("Access-Control-Allow-Headers", "*")
            }
        }

        return try {
            serveAsset(uri)
        } catch (e: Exception) {
            Log.e(TAG, "Error serving $uri", e)
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "text/plain",
                "Internal Server Error: ${e.message}"
            )
        }
    }

    private fun serveAsset(uri: String): Response {
        var filePath = uri
        if (filePath.isEmpty() || filePath == "/") {
            filePath = "/index.html"
        }

        val relativePath = filePath.removePrefix("/")
        val assetPath = if (assetBasePath.isEmpty()) relativePath else "$assetBasePath/$relativePath"

        return try {
            val inputStream = context.assets.open(assetPath)
            val bytes = inputStream.readBytes()
            inputStream.close()

            val mimeType = getMimeType(relativePath)
            val response = newFixedLengthResponse(
                Response.Status.OK,
                mimeType,
                bytes.inputStream(),
                bytes.size.toLong()
            )

            // Add CORS headers
            response.addHeader("Access-Control-Allow-Origin", "*")
            response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
            response.addHeader("Access-Control-Allow-Headers", "*")

            Log.d(TAG, "Serving: $assetPath ($mimeType, ${bytes.size} bytes)")
            response
        } catch (e: IOException) {
            Log.w(TAG, "Asset not found: $assetPath")
            newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                "text/plain",
                "File not found: $uri"
            )
        }
    }

    private fun getMimeType(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        MIME_TYPES[extension]?.let { return it }
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)?.let { return it }
        return "application/octet-stream"
    }
}
