package com.dopple.webview.server

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * Custom TempFileManager for Android that uses the app's cache directory.
 *
 * Fixes NanoHTTPD's default behavior which uses System.getProperty("java.io.tmpdir"),
 * which on some Android devices returns an invalid path causing:
 * "No data directory found for package android"
 */
class AndroidTempFileManager(private val cacheDir: File) : NanoHTTPD.TempFileManager {

    private val tempFiles = mutableListOf<NanoHTTPD.TempFile>()

    override fun createTempFile(filename_hint: String?): NanoHTTPD.TempFile {
        val tempFile = AndroidTempFile(cacheDir)
        tempFiles.add(tempFile)
        return tempFile
    }

    override fun clear() {
        tempFiles.forEach { it.delete() }
        tempFiles.clear()
    }

    /**
     * Factory that creates AndroidTempFileManager instances.
     */
    class Factory(context: Context) : NanoHTTPD.TempFileManagerFactory {
        private val cacheDir = context.cacheDir

        override fun create(): NanoHTTPD.TempFileManager {
            return AndroidTempFileManager(cacheDir)
        }
    }
}

/**
 * TempFile implementation that stores data in the app's cache directory.
 */
private class AndroidTempFile(cacheDir: File) : NanoHTTPD.TempFile {

    private val file: File = File.createTempFile("NanoHTTPD-", "", cacheDir)
    private val outputStream: OutputStream = FileOutputStream(file)

    override fun open(): OutputStream = outputStream

    override fun delete() {
        try {
            outputStream.close()
        } catch (ignored: Exception) {}
        file.delete()
    }

    override fun getName(): String = file.absolutePath
}
