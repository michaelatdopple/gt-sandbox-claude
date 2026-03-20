package com.dopple.webview.download

import android.os.StatFs
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/**
 * Extracts ZIP files to the /data storage directory.
 * Uses java.util.zip for memory-efficient extraction.
 */
object ZipExtractor {

    private const val TAG = "ZipExtractor"
    private const val BUFFER_SIZE = 8192
    private const val MAX_ENTRY_SIZE = 500L * 1024 * 1024 // 500MB max per entry (safety limit)
    private const val MAX_TOTAL_SIZE = 2L * 1024 * 1024 * 1024 // 2GB max total extraction
    private const val MIN_FREE_SPACE_MB = 50L // Minimum 50MB free space required during extraction

    /**
     * Extracts a ZIP file to the specified destination directory.
     *
     * @param zipFile The ZIP file to extract
     * @param destinationDir The directory to extract to
     * @return Result containing the destination directory or an error
     */
    fun extract(zipFile: File, destinationDir: File): Result<File> {
        return try {
            if (!zipFile.exists()) {
                return Result.failure(ExtractionException("ZIP file does not exist"))
            }

            if (zipFile.length() == 0L) {
                return Result.failure(ExtractionException("ZIP file is empty"))
            }

            // Create destination directory
            if (!destinationDir.exists()) {
                if (!destinationDir.mkdirs()) {
                    return Result.failure(ExtractionException("Could not create extraction directory"))
                }
            }

            // Check initial storage space
            val storageCheck = checkStorageSpace(destinationDir)
            if (storageCheck != null) {
                return Result.failure(ExtractionException(storageCheck))
            }

            val canonicalDestination = destinationDir.canonicalPath
            var totalExtractedSize = 0L
            var entryCount = 0

            ZipInputStream(BufferedInputStream(FileInputStream(zipFile), BUFFER_SIZE)).use { zipIn ->
                var entry: ZipEntry? = zipIn.nextEntry

                while (entry != null) {
                    entryCount++

                    // Safety limit on number of entries (prevent zip bombs)
                    if (entryCount > 10000) {
                        Log.w(TAG, "Too many entries in ZIP file, aborting")
                        cleanupOnFailure(destinationDir)
                        return Result.failure(ExtractionException("ZIP file contains too many entries"))
                    }

                    val entryFile = File(destinationDir, entry.name)

                    // Security check: prevent zip slip vulnerability
                    if (!entryFile.canonicalPath.startsWith(canonicalDestination)) {
                        Log.w(TAG, "Skipping potentially malicious entry: ${entry.name}")
                        zipIn.closeEntry()
                        entry = zipIn.nextEntry
                        continue
                    }

                    if (entry.isDirectory) {
                        // Create directory
                        if (!entryFile.mkdirs() && !entryFile.exists()) {
                            Log.w(TAG, "Could not create directory: ${entry.name}")
                        }
                    } else {
                        // Check entry size limit
                        val entrySize = entry.size
                        if (entrySize > MAX_ENTRY_SIZE) {
                            Log.w(TAG, "Entry too large: ${entry.name} (${entrySize} bytes)")
                            cleanupOnFailure(destinationDir)
                            return Result.failure(ExtractionException("File too large in ZIP archive"))
                        }

                        // Check total extraction size
                        if (entrySize > 0) {
                            totalExtractedSize += entrySize
                            if (totalExtractedSize > MAX_TOTAL_SIZE) {
                                Log.w(TAG, "Total extraction size exceeded limit")
                                cleanupOnFailure(destinationDir)
                                return Result.failure(ExtractionException("ZIP archive too large"))
                            }
                        }

                        // Ensure parent directories exist
                        val currentEntryName = entry.name
                        entryFile.parentFile?.let { parent ->
                            if (!parent.exists() && !parent.mkdirs()) {
                                Log.w(TAG, "Could not create parent directory for: $currentEntryName")
                            }
                        }

                        // Extract file with buffered output
                        val extractedSize = extractEntry(zipIn, entryFile)

                        // Update total size if we didn't know entry size beforehand
                        if (entrySize <= 0) {
                            totalExtractedSize += extractedSize
                            if (totalExtractedSize > MAX_TOTAL_SIZE) {
                                Log.w(TAG, "Total extraction size exceeded limit")
                                cleanupOnFailure(destinationDir)
                                return Result.failure(ExtractionException("ZIP archive too large"))
                            }
                        }

                        // Periodically check storage space during extraction
                        if (entryCount % 50 == 0) {
                            val spaceCheck = checkStorageSpace(destinationDir)
                            if (spaceCheck != null) {
                                cleanupOnFailure(destinationDir)
                                return Result.failure(ExtractionException(spaceCheck))
                            }
                        }
                    }

                    Log.d(TAG, "Extracted: ${entry.name}")
                    zipIn.closeEntry()
                    entry = zipIn.nextEntry
                }
            }

            Log.d(TAG, "Extraction complete to: ${destinationDir.absolutePath} ($entryCount entries, ${totalExtractedSize / 1024}KB)")
            Result.success(destinationDir)

        } catch (e: ZipException) {
            Log.e(TAG, "Invalid ZIP file", e)
            cleanupOnFailure(destinationDir)
            Result.failure(ExtractionException("Invalid or corrupted ZIP file"))
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception during extraction", e)
            cleanupOnFailure(destinationDir)
            Result.failure(ExtractionException("Storage permission denied"))
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "Out of memory during extraction", e)
            cleanupOnFailure(destinationDir)
            System.gc() // Request garbage collection
            Result.failure(ExtractionException("Not enough memory - please close other apps"))
        } catch (e: IOException) {
            Log.e(TAG, "Extraction failed", e)
            cleanupOnFailure(destinationDir)
            val message = when {
                e.message?.contains("No space left") == true -> "Storage full - please free up space"
                e.message?.contains("ENOSPC") == true -> "Storage full - please free up space"
                else -> "Extraction failed: ${e.message}"
            }
            Result.failure(ExtractionException(message))
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected extraction error", e)
            cleanupOnFailure(destinationDir)
            Result.failure(ExtractionException("Extraction error: ${e.message}"))
        }
    }

    private fun extractEntry(zipIn: ZipInputStream, entryFile: File): Long {
        var extractedBytes = 0L
        BufferedOutputStream(FileOutputStream(entryFile), BUFFER_SIZE).use { output ->
            val buffer = ByteArray(BUFFER_SIZE)
            var length: Int

            while (zipIn.read(buffer).also { length = it } > 0) {
                output.write(buffer, 0, length)
                extractedBytes += length

                // Safety check for zip bomb (entry claiming small size but huge actual size)
                if (extractedBytes > MAX_ENTRY_SIZE) {
                    throw IOException("Entry size exceeds safe limit during extraction")
                }
            }
        }
        return extractedBytes
    }

    private fun checkStorageSpace(directory: File): String? {
        return try {
            val stat = StatFs(directory.absolutePath)
            val availableBytes = stat.availableBlocksLong * stat.blockSizeLong
            val availableMB = availableBytes / (1024 * 1024)

            if (availableMB < MIN_FREE_SPACE_MB) {
                "Storage full - only ${availableMB}MB available"
            } else {
                null // Storage OK
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not check storage space", e)
            null // Allow extraction to proceed if we can't check
        }
    }

    private fun cleanupOnFailure(destinationDir: File) {
        try {
            if (destinationDir.exists()) {
                destinationDir.deleteRecursively()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not cleanup after extraction failure", e)
        }
    }
}

/**
 * Exception for extraction-related errors with user-friendly messages.
 */
class ExtractionException(message: String) : Exception(message)
