package com.dopple.webview.bridge

import android.os.RemoteException
import android.util.Log
import com.dopple.settings.ISystemSettings

class FreeRotateManager(
    private val getSystemSettings: () -> ISystemSettings?
) {
    companion object {
        private const val TAG = "FreeRotateManager"
    }

    private var gameOpenState: Boolean? = null
    private var settingsOpenState: Boolean? = null

    fun isFreeRotateEnabled(): String {
        val settings = getSystemSettings()
        if (settings == null) {
            Log.e(TAG, "ISystemSettings not bound — cannot query free rotate")
            return """{"enabled":false}"""
        }
        return try {
            val enabled = settings.isScreenFreeRotateEnabled
            """{"enabled":$enabled}"""
        } catch (e: RemoteException) {
            Log.e(TAG, "ISystemSettings call failed: isScreenFreeRotateEnabled", e)
            """{"enabled":false}"""
        }
    }

    fun setFreeRotate(enabled: Boolean): String {
        val settings = getSystemSettings()
        if (settings == null) {
            Log.e(TAG, "ISystemSettings not bound — cannot set free rotate")
            return """{"success":false}"""
        }
        return try {
            val result = settings.enableScreenFreeRotate(enabled)
            """{"success":$result}"""
        } catch (e: RemoteException) {
            Log.e(TAG, "ISystemSettings call failed: enableScreenFreeRotate", e)
            """{"success":false}"""
        }
    }

    fun saveStateForGame() {
        val settings = getSystemSettings()
        if (settings == null) {
            Log.w(TAG, "ISystemSettings not bound — skipping game state save")
            gameOpenState = null
            return
        }
        try {
            gameOpenState = settings.isScreenFreeRotateEnabled
            Log.d(TAG, "Saved rotation state for game: $gameOpenState")
        } catch (e: RemoteException) {
            Log.e(TAG, "Failed to save rotation state for game", e)
            gameOpenState = null
        }
    }

    fun restoreStateForGame() {
        val saved = gameOpenState ?: return
        gameOpenState = null
        val settings = getSystemSettings()
        if (settings == null) {
            Log.w(TAG, "ISystemSettings not bound — skipping game state restore")
            return
        }
        try {
            settings.enableScreenFreeRotate(saved)
            Log.d(TAG, "Restored rotation state after game: $saved")
        } catch (e: RemoteException) {
            Log.e(TAG, "Failed to restore rotation state after game", e)
        }
    }

    fun saveStateForSettings() {
        val settings = getSystemSettings()
        if (settings == null) {
            Log.w(TAG, "ISystemSettings not bound — skipping settings state save")
            settingsOpenState = null
            return
        }
        try {
            settingsOpenState = settings.isScreenFreeRotateEnabled
            settings.enableScreenFreeRotate(false)
            Log.d(TAG, "Saved rotation state for settings: $settingsOpenState, disabled free rotate")
        } catch (e: RemoteException) {
            Log.e(TAG, "Failed to save rotation state for settings", e)
            settingsOpenState = null
        }
    }

    fun restoreStateForSettings() {
        val saved = settingsOpenState ?: return
        settingsOpenState = null
        val settings = getSystemSettings()
        if (settings == null) {
            Log.w(TAG, "ISystemSettings not bound — skipping settings state restore")
            return
        }
        try {
            settings.enableScreenFreeRotate(saved)
            Log.d(TAG, "Restored rotation state after settings: $saved")
        } catch (e: RemoteException) {
            Log.e(TAG, "Failed to restore rotation state after settings", e)
        }
    }
}
