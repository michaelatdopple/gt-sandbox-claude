package com.dopple.webview.server

import android.content.Context
import android.util.Log
import android.webkit.MimeTypeMap
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * Local HTTPS server for hosting downloaded Unity WebGL bundles.
 * Uses NanoHTTPD with TLS for secure JS API support.
 *
 * Configuration:
 * - Port: 8443
 * - Host: 127.0.0.1
 * - Protocol: HTTPS with self-signed certificate
 */
class LocalHttpsServer(context: Context) : NanoHTTPD("127.0.0.1", 8443) {

    init {
        setTempFileManagerFactory(AndroidTempFileManager.Factory(context))
    }

    companion object {
        private const val TAG = "LocalHttpsServer"

        // MIME type mappings for Unity WebGL content
        private val MIME_TYPES = mapOf(
            "html" to "text/html",
            "htm" to "text/html",
            "js" to "application/javascript",
            "mjs" to "application/javascript",
            "wasm" to "application/wasm",
            "data" to "application/octet-stream",
            "json" to "application/json",
            "png" to "image/png",
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "gif" to "image/gif",
            "svg" to "image/svg+xml",
            "css" to "text/css",
            "ico" to "image/x-icon",
            "mp3" to "audio/mpeg",
            "ogg" to "audio/ogg",
            "wav" to "audio/wav",
            "mp4" to "video/mp4",
            "webm" to "video/webm",
            "webp" to "image/webp",
            "woff" to "font/woff",
            "woff2" to "font/woff2",
            "ttf" to "font/ttf",
            "otf" to "font/otf",
            "unityweb" to "application/octet-stream",
            "br" to "application/octet-stream",
            "gz" to "application/gzip"
        )
    }

    private var bundlePath: String? = null
    private var isRunning = false

    /**
     * Starts the server with the specified bundle directory as the root.
     *
     * @param bundlePath The path to the extracted bundle directory
     */
    fun start(bundlePath: String) {
        this.bundlePath = bundlePath

        try {
            // Configure SSL
            configureSSL()

            // Start server
            start(SOCKET_READ_TIMEOUT, false)
            isRunning = true
            Log.d(TAG, "Server started on https://127.0.0.1:8443")
            Log.d(TAG, "Serving files from: $bundlePath")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to start server", e)
        }
    }

    override fun stop() {
        super.stop()
        isRunning = false
        bundlePath = null
        Log.d(TAG, "Server stopped")
    }

    private fun configureSSL() {
        try {
            // Generate or load SSL context
            val sslContext = CertificateGenerator.createSSLContext()

            // Make server secure
            makeSecure(sslContext.serverSocketFactory, null)
            Log.d(TAG, "SSL configured successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to configure SSL, falling back to HTTP", e)
            // Server will still work but without HTTPS
        }
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        Log.d(TAG, "Request: ${session.method} $uri")

        // Handle the request
        return try {
            serveFile(uri)
        } catch (e: Exception) {
            Log.e(TAG, "Error serving $uri", e)
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "text/plain",
                "Internal Server Error: ${e.message}"
            )
        }
    }

    private fun serveFile(uri: String): Response {
        val rootPath = bundlePath
        if (rootPath == null) {
            return newFixedLengthResponse(
                Response.Status.SERVICE_UNAVAILABLE,
                "text/plain",
                "Server not initialized"
            )
        }

        // Clean up the URI
        var filePath = uri
        if (filePath.isEmpty() || filePath == "/") {
            filePath = "/index.html"
        }

        // Remove leading slash and resolve to file
        val relativePath = filePath.removePrefix("/")
        val file = File(rootPath, relativePath)

        // Security check: prevent path traversal
        val canonicalRoot = File(rootPath).canonicalPath
        val canonicalFile = file.canonicalPath
        if (!canonicalFile.startsWith(canonicalRoot)) {
            Log.w(TAG, "Path traversal attempt blocked: $uri")
            return newFixedLengthResponse(
                Response.Status.FORBIDDEN,
                "text/plain",
                "Access denied"
            )
        }

        // Check if file exists
        if (!file.exists()) {
            Log.w(TAG, "File not found: ${file.absolutePath}")
            return newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                "text/plain",
                "File not found: $uri"
            )
        }

        // If it's a directory, look for index.html
        val actualFile = if (file.isDirectory) {
            File(file, "index.html")
        } else {
            file
        }

        if (!actualFile.exists()) {
            return newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                "text/plain",
                "File not found: $uri"
            )
        }

        // Determine MIME type
        val mimeType = getMimeType(actualFile.name)

        // Create response with file stream
        val inputStream = FileInputStream(actualFile)
        val response = newFixedLengthResponse(
            Response.Status.OK,
            mimeType,
            inputStream,
            actualFile.length()
        )

        // Add CORS headers for local development
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "*")

        // Add caching headers for static content
        response.addHeader("Cache-Control", "max-age=3600")

        Log.d(TAG, "Serving: ${actualFile.name} ($mimeType, ${actualFile.length()} bytes)")
        return response
    }

    private fun getMimeType(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()

        // Check our custom mappings first
        MIME_TYPES[extension]?.let { return it }

        // Fall back to system MIME type map
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)?.let { return it }

        // Default to binary
        return "application/octet-stream"
    }
}
