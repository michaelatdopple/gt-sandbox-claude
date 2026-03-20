package com.dopple.webview.download

import android.content.Context
import android.os.StatFs
import android.util.Log
import com.dopple.webview.util.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Downloads ZIP bundles from URLs with progress callbacks.
 * Uses OkHttp for streaming downloads.
 */
class BundleDownloader {

    companion object {
        private const val TAG = "BundleDownloader"
        private const val MIN_FREE_SPACE_MB = 100L // Minimum 100MB free space required
        private const val SAFETY_MARGIN_MULTIPLIER = 2.5 // Extra space for ZIP extraction
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private var currentCall: Call? = null
    private val isCancelled = AtomicBoolean(false)

    /**
     * Downloads a file from the given URL with progress updates.
     *
     * @param url The URL to download from
     * @param destination The file to save to
     * @param context Optional Android context for network connectivity check
     * @param onProgress Callback for progress updates (bytesDownloaded, totalBytes)
     * @return Result containing the downloaded file or an error
     */
    suspend fun download(
        url: String,
        destination: File,
        context: Context? = null,
        onProgress: (bytesDownloaded: Long, totalBytes: Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        isCancelled.set(false)

        try {
            // Check network connectivity if context is provided
            if (context != null && !NetworkUtils.isNetworkAvailable(context)) {
                Log.w(TAG, "No network connectivity available")
                return@withContext Result.failure(
                    DownloadException(NetworkUtils.OFFLINE_ERROR_MESSAGE)
                )
            }

            // Validate URL before attempting download
            if (url.isBlank()) {
                return@withContext Result.failure(
                    DownloadException("Invalid download URL")
                )
            }

            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            val response = executeRequest(request)

            if (!response.isSuccessful) {
                val errorMessage = mapHttpError(response.code)
                Log.e(TAG, "Download failed: HTTP ${response.code} - $errorMessage")
                return@withContext Result.failure(DownloadException(errorMessage))
            }

            val body = response.body
                ?: return@withContext Result.failure(DownloadException("Empty response from server"))

            val contentLength = body.contentLength()

            // Check storage space before downloading
            val storageCheck = checkStorageSpace(destination.parentFile, contentLength)
            if (storageCheck != null) {
                Log.e(TAG, "Storage check failed: $storageCheck")
                body.close()
                return@withContext Result.failure(DownloadException(storageCheck))
            }

            var bytesDownloaded = 0L
            var lastProgressUpdate = 0

            // Ensure parent directory exists
            destination.parentFile?.mkdirs()

            // Stream the response to file
            body.byteStream().use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(8192)
                    var bytes: Int

                    while (input.read(buffer).also { bytes = it } != -1) {
                        if (isCancelled.get()) {
                            destination.delete()
                            return@withContext Result.failure(
                                DownloadException("Download cancelled")
                            )
                        }

                        output.write(buffer, 0, bytes)
                        bytesDownloaded += bytes

                        // Update progress at most every 1%
                        if (contentLength > 0) {
                            val progress = (bytesDownloaded * 100 / contentLength).toInt()
                            if (progress > lastProgressUpdate) {
                                lastProgressUpdate = progress
                                onProgress(bytesDownloaded, contentLength)
                            }
                        } else {
                            // Unknown total size, update every 100KB
                            if (bytesDownloaded % (100 * 1024) < 8192) {
                                onProgress(bytesDownloaded, -1)
                            }
                        }
                    }
                }
            }

            // Final progress update
            onProgress(bytesDownloaded, bytesDownloaded)
            Log.d(TAG, "Download complete: $bytesDownloaded bytes")

            Result.success(destination)

        } catch (e: SocketTimeoutException) {
            Log.e(TAG, "Download timeout", e)
            destination.delete()
            Result.failure(DownloadException("Connection timeout - please check your network"))
        } catch (e: UnknownHostException) {
            Log.e(TAG, "Unknown host", e)
            destination.delete()
            Result.failure(DownloadException("No internet connection"))
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception", e)
            destination.delete()
            Result.failure(DownloadException("Storage permission denied"))
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "Out of memory during download", e)
            destination.delete()
            System.gc() // Request garbage collection
            Result.failure(DownloadException("Not enough memory - please close other apps"))
        } catch (e: IOException) {
            Log.e(TAG, "IO exception during download", e)
            destination.delete()
            val message = when {
                e.message?.contains("No space left") == true -> "Storage full - please free up space"
                e.message?.contains("ENOSPC") == true -> "Storage full - please free up space"
                else -> "Download failed: ${e.message}"
            }
            Result.failure(DownloadException(message))
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected download error", e)
            destination.delete()
            Result.failure(DownloadException("Download error: ${e.message}"))
        }
    }

    private fun checkStorageSpace(directory: File?, requiredBytes: Long): String? {
        if (directory == null) return "Invalid storage directory"

        return try {
            val stat = StatFs(directory.absolutePath)
            val availableBytes = stat.availableBlocksLong * stat.blockSizeLong
            val availableMB = availableBytes / (1024 * 1024)
            val requiredMB = if (requiredBytes > 0) {
                (requiredBytes * SAFETY_MARGIN_MULTIPLIER / (1024 * 1024)).toLong()
            } else {
                MIN_FREE_SPACE_MB
            }

            Log.d(TAG, "Storage check: ${availableMB}MB available, ${requiredMB}MB required")

            when {
                availableMB < MIN_FREE_SPACE_MB -> {
                    "Storage full - only ${availableMB}MB available, need at least ${MIN_FREE_SPACE_MB}MB"
                }
                requiredBytes > 0 && availableBytes < (requiredBytes * SAFETY_MARGIN_MULTIPLIER).toLong() -> {
                    "Not enough storage - need ${requiredMB}MB but only ${availableMB}MB available"
                }
                else -> null // Storage OK
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not check storage space", e)
            null // Allow download to proceed if we can't check
        }
    }

    private fun mapHttpError(code: Int): String {
        return when (code) {
            400 -> "Bad request"
            401 -> "Unauthorized"
            403 -> "Access forbidden"
            404 -> "Bundle not found"
            408 -> "Request timeout"
            429 -> "Too many requests - please try again later"
            500 -> "Server error"
            502 -> "Bad gateway"
            503 -> "Service unavailable"
            504 -> "Gateway timeout"
            else -> "Download failed (HTTP $code)"
        }
    }

    private suspend fun executeRequest(request: Request): Response =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            currentCall = call

            continuation.invokeOnCancellation {
                call.cancel()
                isCancelled.set(true)
            }

            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    currentCall = null
                    if (continuation.isActive) {
                        continuation.resumeWithException(e)
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    currentCall = null
                    if (continuation.isActive) {
                        continuation.resume(response)
                    }
                }
            })
        }

    /**
     * Cancels the current download if one is in progress.
     */
    fun cancel() {
        isCancelled.set(true)
        currentCall?.cancel()
        currentCall = null
    }
}

/**
 * Exception for download-related errors with user-friendly messages.
 */
class DownloadException(message: String) : Exception(message)
