package com.dopple.webview.bridge

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Per-game persistent storage using file-per-key strategy.
 *
 * Each key maps to a file: filename = SHA-256 hex hash of key.
 * A separate _index.json maps hashes back to original key names.
 * Atomic writes via write-to-temp-then-rename.
 *
 * @param context Application context for filesDir access
 * @param projectId Game project identifier (validated at construction)
 */
class StorageManager(
    context: Context,
    private val projectId: String
) {
    companion object {
        private const val TAG = "StorageManager"
        private const val STORAGE_DIR = "game-storage"
        private const val INDEX_FILE = "_index.json"
        private const val QUOTA_BYTES = 1_048_576L // 1MB
        private const val MAX_KEY_LENGTH = 256
        private val PROJECT_ID_PATTERN = Regex("[a-zA-Z0-9._-]+")
        private val INVALID_KEY_CHARS = Regex("[/\\\\]|\\.\\.|\\x00")
    }

    private val storageDir: File

    init {
        require(PROJECT_ID_PATTERN.matches(projectId)) {
            "Invalid projectId: must match [a-zA-Z0-9._-]+"
        }
        storageDir = File(context.filesDir, "$STORAGE_DIR/$projectId")
    }

    private fun ensureDir() {
        if (!storageDir.exists()) {
            storageDir.mkdirs()
        }
    }

    private fun hashKey(key: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun validateKey(key: String): String? {
        return when {
            key.isEmpty() -> "Key must not be empty"
            key.length > MAX_KEY_LENGTH -> "Key exceeds $MAX_KEY_LENGTH characters"
            INVALID_KEY_CHARS.containsMatchIn(key) -> "Key contains invalid characters"
            else -> null
        }
    }

    private fun keyFile(hash: String): File {
        val file = File(storageDir, hash)
        val canonical = file.canonicalPath
        val dirCanonical = storageDir.canonicalPath
        require(canonical.startsWith(dirCanonical)) { "Path traversal detected" }
        return file
    }

    private fun readIndex(): MutableMap<String, String> {
        val indexFile = File(storageDir, INDEX_FILE)
        if (!indexFile.exists()) return mutableMapOf()
        return try {
            val json = JSONObject(indexFile.readText(Charsets.UTF_8))
            val map = mutableMapOf<String, String>()
            for (hash in json.keys()) {
                map[hash] = json.getString(hash)
            }
            map
        } catch (e: Exception) {
            Log.w(TAG, "Corrupt index, rebuilding", e)
            mutableMapOf()
        }
    }

    private fun writeIndex(index: Map<String, String>) {
        val indexFile = File(storageDir, INDEX_FILE)
        val json = JSONObject()
        for ((hash, keyName) in index) {
            json.put(hash, keyName)
        }
        atomicWrite(indexFile, json.toString())
    }

    private fun atomicWrite(file: File, content: String) {
        val tmp = File(file.parent, "${file.name}.tmp")
        tmp.writeText(content, Charsets.UTF_8)
        tmp.renameTo(file)
    }

    private fun directorySize(): Long {
        if (!storageDir.exists()) return 0
        return storageDir.listFiles()?.sumOf { it.length() } ?: 0
    }

    private fun successResult(): String =
        """{"success":true}"""

    private fun errorResult(message: String): String =
        JSONObject().put("success", false).put("error", message).toString()

    @Synchronized
    fun setItem(key: String, value: String): String {
        val validationError = validateKey(key)
        if (validationError != null) return errorResult(validationError)

        return try {
            ensureDir()
            val hash = hashKey(key)
            val file = keyFile(hash)

            // Check quota: current usage minus old file size plus new value size
            val currentSize = directorySize()
            val oldSize = if (file.exists()) file.length() else 0
            val newSize = value.toByteArray(Charsets.UTF_8).size.toLong()
            if (currentSize - oldSize + newSize > QUOTA_BYTES) {
                return errorResult("QUOTA_EXCEEDED")
            }

            atomicWrite(file, value)

            // Update index
            val index = readIndex()
            index[hash] = key
            writeIndex(index)

            successResult()
        } catch (e: Exception) {
            Log.e(TAG, "setItem failed", e)
            errorResult(e.message ?: "Unknown error")
        }
    }

    fun getItem(key: String): String? {
        val validationError = validateKey(key)
        if (validationError != null) {
            Log.w(TAG, "getItem: invalid key: $validationError")
            return null
        }

        return try {
            val hash = hashKey(key)
            val file = keyFile(hash)
            if (file.exists()) file.readText(Charsets.UTF_8) else null
        } catch (e: Exception) {
            Log.e(TAG, "getItem failed", e)
            null
        }
    }

    @Synchronized
    fun removeItem(key: String): String {
        val validationError = validateKey(key)
        if (validationError != null) return errorResult(validationError)

        return try {
            val hash = hashKey(key)
            val file = keyFile(hash)
            file.delete()

            val index = readIndex()
            index.remove(hash)
            writeIndex(index)

            successResult()
        } catch (e: Exception) {
            Log.e(TAG, "removeItem failed", e)
            errorResult(e.message ?: "Unknown error")
        }
    }

    @Synchronized
    fun clear(): String {
        return try {
            if (storageDir.exists()) {
                storageDir.listFiles()?.forEach { it.delete() }
            }
            successResult()
        } catch (e: Exception) {
            Log.e(TAG, "clear failed", e)
            errorResult(e.message ?: "Unknown error")
        }
    }

    fun exists(key: String): Boolean {
        val validationError = validateKey(key)
        if (validationError != null) return false

        return try {
            val hash = hashKey(key)
            keyFile(hash).exists()
        } catch (e: Exception) {
            Log.e(TAG, "exists failed", e)
            false
        }
    }

    fun keys(): String {
        return try {
            val index = readIndex()
            val array = JSONArray()
            for (keyName in index.values) {
                array.put(keyName)
            }
            array.toString()
        } catch (e: Exception) {
            Log.e(TAG, "keys failed", e)
            "[]"
        }
    }

    fun getUsage(): String {
        return try {
            val used = directorySize()
            JSONObject()
                .put("used", used)
                .put("quota", QUOTA_BYTES)
                .toString()
        } catch (e: Exception) {
            Log.e(TAG, "getUsage failed", e)
            """{"used":0,"quota":$QUOTA_BYTES}"""
        }
    }
}
