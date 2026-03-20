package com.dopple.webview.data

import android.content.Context
import android.util.Log
import com.dopple.webview.util.NetworkUtils
import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Repository for fetching and parsing manifest JSON from URLs.
 * Uses OkHttp for network requests and Gson for JSON parsing.
 */
class ManifestRepository {

    companion object {
        private const val TAG = "ManifestRepository"
        private const val MAX_RESPONSE_SIZE = 1024 * 1024 // 1MB max response size
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    /**
     * Fetches and parses a manifest from the given URL.
     *
     * @param url The URL to fetch the manifest from
     * @param context Optional Android context for network connectivity check and asset access
     * @return Result containing the parsed Manifest or an error
     */
    suspend fun fetchManifest(url: String, context: Context? = null): Result<Manifest> = withContext(Dispatchers.IO) {
        try {
            // Validate URL before making request
            if (url.isBlank()) {
                return@withContext Result.failure(
                    ManifestException("Invalid manifest URL")
                )
            }

            // Handle demo:// URLs (non-launchable preview cards)
            if (url.startsWith("demo://")) {
                Log.d(TAG, "Demo URL not launchable: $url")
                return@withContext Result.failure(
                    ManifestException("This is a demo preview - not launchable")
                )
            }

            // Handle local asset URLs (built-in activities served by AssetHttpServer)
            // Pattern: http://127.0.0.1:8088/path/to/manifest.json -> assets/games/path/to/manifest.json
            if (url.startsWith("http://127.0.0.1:8088/") && context != null) {
                Log.d(TAG, "Loading manifest from assets: $url")
                return@withContext loadManifestFromAssets(url, context)
            }

            // Check network connectivity if context is provided
            if (context != null && !NetworkUtils.isNetworkAvailable(context)) {
                Log.w(TAG, "No network connectivity available")
                return@withContext Result.failure(
                    ManifestException(NetworkUtils.OFFLINE_ERROR_MESSAGE)
                )
            }

            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                return@withContext Result.failure(
                    ManifestException("Invalid URL format - must be http or https")
                )
            }

            Log.d(TAG, "Fetching manifest from: $url")
            val response = fetchUrl(url)

            if (!response.isSuccessful) {
                Log.e(TAG, "Manifest fetch failed: HTTP ${response.code}")
                return@withContext Result.failure(
                    ManifestException(mapHttpError(response.code))
                )
            }

            val responseBody = response.body
            if (responseBody == null) {
                return@withContext Result.failure(
                    ManifestException("Empty response from server")
                )
            }

            // Check response size before reading
            val contentLength = responseBody.contentLength()
            if (contentLength > MAX_RESPONSE_SIZE) {
                responseBody.close()
                return@withContext Result.failure(
                    ManifestException("Manifest response too large")
                )
            }

            val body = responseBody.string()

            if (body.isBlank()) {
                return@withContext Result.failure(
                    ManifestException("Empty manifest response")
                )
            }

            // Check actual size if content-length was not provided
            if (body.length > MAX_RESPONSE_SIZE) {
                return@withContext Result.failure(
                    ManifestException("Manifest response too large")
                )
            }

            val manifest = parseManifest(body)
            val validationResult = validateManifest(manifest)

            if (validationResult != null) {
                Log.e(TAG, "Manifest validation failed: $validationResult")
                return@withContext Result.failure(ManifestException(validationResult))
            }

            Log.d(TAG, "Manifest parsed successfully: ${manifest.activityName}")
            Result.success(manifest)

        } catch (e: MalformedURLException) {
            Log.e(TAG, "Malformed URL", e)
            Result.failure(ManifestException("Invalid URL format"))
        } catch (e: SocketTimeoutException) {
            Log.e(TAG, "Connection timeout", e)
            Result.failure(ManifestException("Connection timeout - please try again"))
        } catch (e: UnknownHostException) {
            Log.e(TAG, "Unknown host", e)
            Result.failure(ManifestException("No internet connection"))
        } catch (e: SSLException) {
            Log.e(TAG, "SSL error", e)
            Result.failure(ManifestException("Secure connection failed"))
        } catch (e: IOException) {
            Log.e(TAG, "IO exception", e)
            Result.failure(ManifestException("Network error - please check your connection"))
        } catch (e: JsonSyntaxException) {
            Log.e(TAG, "JSON syntax error", e)
            Result.failure(ManifestException("Invalid manifest format"))
        } catch (e: JsonParseException) {
            Log.e(TAG, "JSON parse error", e)
            Result.failure(ManifestException("Could not parse manifest"))
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "Out of memory", e)
            System.gc()
            Result.failure(ManifestException("Not enough memory"))
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error", e)
            Result.failure(ManifestException("Failed to load activity"))
        }
    }

    private suspend fun fetchUrl(url: String): Response = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        val call = client.newCall(request)

        continuation.invokeOnCancellation {
            call.cancel()
        }

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) {
                    continuation.resumeWithException(e)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) {
                    continuation.resume(response)
                }
            }
        })
    }

    private fun parseManifest(json: String): Manifest {
        return gson.fromJson(json, Manifest::class.java)
    }

    private fun validateManifest(manifest: Manifest?): String? {
        if (manifest == null) {
            return "Invalid manifest: null"
        }
        if (manifest.projectId.isBlank()) {
            return "Missing projectId"
        }
        if (manifest.activityName.isBlank()) {
            return "Missing activityName"
        }
        if (manifest.url.isBlank()) {
            return "Missing url"
        }
        // Basic URL validation
        if (!manifest.url.startsWith("http://") &&
            !manifest.url.startsWith("https://") &&
            !manifest.url.startsWith("file://")) {
            return "Invalid url format"
        }
        return null
    }

    private fun mapHttpError(code: Int): String {
        return when (code) {
            400 -> "Bad request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Manifest not found"
            500 -> "Server error"
            502 -> "Bad gateway"
            503 -> "Service unavailable"
            else -> "HTTP error: $code"
        }
    }

    /**
     * Load a manifest directly from Android assets.
     * Used for built-in activities that would normally be served by AssetHttpServer.
     *
     * @param url The localhost URL (e.g., http://127.0.0.1:8088/games/tictactoe/manifest.json)
     * @param context Android context for asset access
     * @return Result containing the parsed Manifest or an error
     */
    private fun loadManifestFromAssets(url: String, context: Context): Result<Manifest> {
        return try {
            // Extract path from URL, e.g. /games/tictactoe/manifest.json
            val assetPath = url.removePrefix("http://127.0.0.1:8088/")

            Log.d(TAG, "Reading manifest from asset: $assetPath")

            val inputStream = context.assets.open(assetPath)
            val body = inputStream.bufferedReader().use { it.readText() }
            inputStream.close()

            if (body.isBlank()) {
                return Result.failure(ManifestException("Empty manifest file"))
            }

            val manifest = parseManifest(body)
            val validationResult = validateManifest(manifest)

            if (validationResult != null) {
                Log.e(TAG, "Manifest validation failed: $validationResult")
                return Result.failure(ManifestException(validationResult))
            }

            Log.d(TAG, "Manifest loaded from assets: ${manifest.activityName}")
            Result.success(manifest)
        } catch (e: java.io.FileNotFoundException) {
            Log.e(TAG, "Manifest not found in assets", e)
            Result.failure(ManifestException("Built-in activity not found"))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load manifest from assets", e)
            Result.failure(ManifestException("Failed to load built-in activity"))
        }
    }
}

/**
 * Exception for manifest-related errors with user-friendly messages.
 */
class ManifestException(message: String) : Exception(message)
