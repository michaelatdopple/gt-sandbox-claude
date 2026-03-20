package com.dopple.webview.bridge.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Handles BLE advertising for player discovery.
 *
 * Players advertise their presence with a unique token that the host scans for.
 * The token is included in manufacturer-specific data for filtering.
 */
class LoopAdvertiser(
    private val bluetoothAdapter: BluetoothAdapter
) {
    companion object {
        private const val TAG = "LoopAdvertiser"
    }

    private var advertiser: BluetoothLeAdvertiser? = null
    private var currentCallback: AdvertiseCallback? = null
    private var isAdvertising = false
    private var currentToken: String? = null

    /**
     * Start BLE advertising with the given token and optional nonce.
     *
     * @param token Unique 8-character token for discovery
     * @param nonce Optional 4-byte nonce for seed-play negotiation.
     *              Defaults to CONFIRMED_HOST_NONCE (all zeros) for backward compat.
     * @param connectable Whether the advertisement should be connectable.
     *              Use false during election phase, true when hosting.
     * @throws AdvertisingException if advertising fails to start
     * @throws IllegalStateException if BLE advertising is not supported
     */
    @android.annotation.SuppressLint("MissingPermission")
    suspend fun startAdvertising(token: String, nonce: ByteArray? = null, connectable: Boolean = true) {
        if (isAdvertising) {
            Log.w(TAG, "Already advertising, stopping first")
            stopAdvertising()
        }

        val bleAdvertiser = bluetoothAdapter.bluetoothLeAdvertiser
            ?: throw IllegalStateException("BLE advertising not supported on this device")

        advertiser = bleAdvertiser
        currentToken = token
        val tokenBytes = token.toByteArray(Charsets.UTF_8)
        val mfgData = if (nonce != null) tokenBytes + nonce else tokenBytes

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(connectable)
            .setTimeout(0) // No timeout
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        val advertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(LoopBleSpec.SERVICE_UUID))
            .build()

        val scanResponseData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addManufacturerData(LoopBleSpec.MANUFACTURER_ID, mfgData)
            .build()

        suspendCancellableCoroutine { continuation ->
            val callback = createCallback(continuation)
            currentCallback = callback

            continuation.invokeOnCancellation {
                stopAdvertising()
            }

            Log.d(TAG, "Starting advertising with token: $token, connectable: $connectable")
            bleAdvertiser.startAdvertising(settings, advertiseData, scanResponseData, callback)
        }
    }

    /**
     * Atomic lock transition: stop election ad, restart as confirmed host.
     * Minimizes the gap between stopping and restarting (~10-50ms).
     */
    suspend fun transitionToConfirmedHost(token: String) {
        Log.d(TAG, "Transitioning to confirmed host")
        stopAdvertising()
        startAdvertising(token, nonce = LoopBleSpec.CONFIRMED_HOST_NONCE, connectable = true)
    }

    /**
     * Stop BLE advertising.
     */
    @android.annotation.SuppressLint("MissingPermission")
    fun stopAdvertising() {
        currentCallback?.let { callback ->
            try {
                advertiser?.stopAdvertising(callback)
                Log.d(TAG, "Advertising stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping advertising", e)
            }
        }
        currentCallback = null
        isAdvertising = false
    }

    /**
     * Check if currently advertising.
     */
    fun isAdvertising(): Boolean = isAdvertising

    private fun createCallback(continuation: CancellableContinuation<Unit>): AdvertiseCallback {
        return object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                Log.d(TAG, "Advertising started successfully")
                isAdvertising = true
                if (continuation.isActive) {
                    continuation.resume(Unit)
                }
            }

            override fun onStartFailure(errorCode: Int) {
                Log.e(TAG, "Advertising failed with error code: $errorCode")
                isAdvertising = false
                if (continuation.isActive) {
                    continuation.resumeWithException(
                        AdvertisingException(errorCode, getErrorMessage(errorCode))
                    )
                }
            }
        }
    }

    private fun getErrorMessage(errorCode: Int): String = when (errorCode) {
        AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE ->
            "Advertise data too large"
        AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS ->
            "Too many advertisers"
        AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED ->
            "Advertising already started"
        AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR ->
            "Internal error"
        AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED ->
            "Feature unsupported"
        else ->
            "Unknown error: $errorCode"
    }
}

/**
 * Exception thrown when BLE advertising fails.
 */
class AdvertisingException(
    val errorCode: Int,
    message: String
) : Exception("Advertising failed: $message (code: $errorCode)")
