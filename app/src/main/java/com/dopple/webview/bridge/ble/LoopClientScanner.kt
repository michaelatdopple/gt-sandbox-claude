package com.dopple.webview.bridge.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Discovery result from continuous scanning during seed-play negotiation.
 */
data class ScanDiscovery(
    val device: BluetoothDevice,
    val nonce: ByteArray,
    val rssi: Int,
    val isConfirmedHost: Boolean
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ScanDiscovery) return false
        return device == other.device && nonce.contentEquals(other.nonce)
    }

    override fun hashCode(): Int = device.hashCode() * 31 + nonce.contentHashCode()
}

/**
 * Scanner for players to find the host device.
 *
 * In the Host-as-Server architecture:
 * - Host advertises with a token
 * - Players scan for the host's token
 * - Once found, player connects to host's GATT server
 *
 * This is the inverse of the original LoopScanner which was used by hosts.
 */
class LoopClientScanner(
    private val bluetoothAdapter: BluetoothAdapter
) {
    companion object {
        private const val TAG = "LoopClientScanner"
        private const val DEFAULT_TIMEOUT_MS = 10_000L
        private const val CALLBACK_CLEANUP_DELAY_MS = 500L
    }

    private var scanner: BluetoothLeScanner? = null
    private var currentCallback: ScanCallback? = null
    @Volatile private var isScanning = false
    @Volatile private var scanStopped = false
    private val handler = Handler(Looper.getMainLooper())
    private val lock = Any()

    /**
     * Scan for a host advertising the specified token.
     *
     * @param token The token from the host's QR code or known test token
     * @param timeoutMs Maximum time to scan before giving up
     * @return The BluetoothDevice of the host advertising the token
     * @throws ScanTimeoutException if no host found within timeout
     * @throws ScanException if scanning fails
     */
    @android.annotation.SuppressLint("MissingPermission")
    suspend fun scanForHost(
        token: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): BluetoothDevice {
        if (isScanning) {
            Log.w(TAG, "Already scanning, stopping first")
            stopScan()
        }

        val bleScanner = bluetoothAdapter.bluetoothLeScanner
            ?: throw IllegalStateException("BLE scanner not available")

        scanner = bleScanner
        val tokenBytes = token.toByteArray(Charsets.UTF_8)

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .build()

        // Hardware-level filter: match service UUID + token bytes in mfg data.
        // Mask is built from actual token length — no nonce padding for host/join flow.
        val mask = ByteArray(tokenBytes.size) { 0xFF.toByte() }
        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(LoopBleSpec.SERVICE_UUID))
                .setManufacturerData(
                    LoopBleSpec.MANUFACTURER_ID,
                    tokenBytes,
                    mask
                )
                .build()
        )

        scanStopped = false

        return suspendCancellableCoroutine { continuation ->
            var timeoutRunnable: Runnable? = null

            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    if (scanStopped) {
                        Log.v(TAG, "Ignoring scan result after stop")
                        return
                    }

                    // Token already matched by ScanFilter — just verify mfg data present
                    Log.d(TAG, "Found host with matching token: ${result.device.address}, rssi=${result.rssi}")
                    timeoutRunnable?.let { handler.removeCallbacks(it) }
                    stopScan()
                    if (continuation.isActive) {
                        continuation.resume(result.device)
                    }
                }

                override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                    Log.d(TAG, "Batch scan results: ${results?.size ?: 0} devices")
                    results?.forEach { result ->
                        onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, result)
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    if (scanStopped) return

                    Log.e(TAG, "Scan failed with error code: $errorCode")
                    timeoutRunnable?.let { handler.removeCallbacks(it) }
                    isScanning = false
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            ScanException(errorCode, getErrorMessage(errorCode))
                        )
                    }
                }
            }

            synchronized(lock) {
                currentCallback = callback
                isScanning = true
            }

            // Set up timeout
            timeoutRunnable = Runnable {
                Log.w(TAG, "Scan timed out after ${timeoutMs}ms")
                stopScan()
                if (continuation.isActive) {
                    continuation.resumeWithException(
                        ScanTimeoutException("No host found with token: $token")
                    )
                }
            }
            handler.postDelayed(timeoutRunnable, timeoutMs)

            continuation.invokeOnCancellation {
                timeoutRunnable.let { handler.removeCallbacks(it) }
                stopScan()
            }

            Log.d(TAG, "Starting scan for host token: $token")
            try {
                bleScanner.startScan(filters, settings, callback)
                Log.d(TAG, "startScan() returned successfully")
            } catch (e: Exception) {
                Log.e(TAG, "startScan() threw exception", e)
                throw e
            }
        }
    }

    /**
     * Start a continuous scan for devices advertising the given token.
     * Unlike [scanForHost], this does not auto-stop on first match and has no
     * internal timeout — the caller controls lifetime via [stopScan].
     *
     * Each matching advertisement is delivered via [onDiscovery] with nonce
     * extracted from manufacturer data bytes 8-11.
     *
     * @param token The 8-char hex token to match
     * @param onDiscovery Callback for each matching discovery
     * @param onError Callback for scan failure
     */
    @android.annotation.SuppressLint("MissingPermission")
    fun startContinuousScan(
        token: String,
        onDiscovery: (ScanDiscovery) -> Unit,
        onError: (Exception) -> Unit
    ) {
        if (isScanning) {
            Log.w(TAG, "Already scanning, stopping first")
            stopScan()
        }

        val bleScanner = bluetoothAdapter.bluetoothLeScanner
            ?: run {
                onError(IllegalStateException("BLE scanner not available"))
                return
            }

        scanner = bleScanner
        val tokenBytes = token.toByteArray(Charsets.UTF_8)

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .build()

        // Hardware-level filter: match service UUID + token prefix in mfg data
        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(LoopBleSpec.SERVICE_UUID))
                .setManufacturerData(
                    LoopBleSpec.MANUFACTURER_ID,
                    tokenBytes + ByteArray(LoopBleSpec.NONCE_BYTE_LENGTH),
                    LoopBleSpec.buildTokenMatchMask()
                )
                .build()
        )

        scanStopped = false

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (scanStopped) return

                val mfgData = result.scanRecord
                    ?.getManufacturerSpecificData(LoopBleSpec.MANUFACTURER_ID)
                    ?: return

                if (mfgData.size < LoopBleSpec.MFG_DATA_LENGTH) return

                // Token already matched by ScanFilter — just extract nonce
                val nonce = mfgData.copyOfRange(
                    LoopBleSpec.TOKEN_BYTE_LENGTH,
                    LoopBleSpec.MFG_DATA_LENGTH
                )
                val isConfirmed = nonce.contentEquals(LoopBleSpec.CONFIRMED_HOST_NONCE)

                onDiscovery(
                    ScanDiscovery(result.device, nonce, result.rssi, isConfirmed)
                )
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                results?.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
            }

            override fun onScanFailed(errorCode: Int) {
                if (scanStopped) return
                isScanning = false
                onError(ScanException(errorCode, getErrorMessage(errorCode)))
            }
        }

        synchronized(lock) {
            currentCallback = callback
            isScanning = true
        }

        Log.d(TAG, "Starting continuous scan for token: $token")
        try {
            bleScanner.startScan(filters, settings, callback)
        } catch (e: Exception) {
            Log.e(TAG, "startScan() threw exception", e)
            isScanning = false
            onError(e)
        }
    }

    /**
     * Stop any active scan.
     */
    @android.annotation.SuppressLint("MissingPermission")
    fun stopScan() {
        scanStopped = true

        synchronized(lock) {
            if (!isScanning) return

            currentCallback?.let { callback ->
                try {
                    scanner?.stopScan(callback)
                    Log.d(TAG, "Scan stopped")
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping scan", e)
                }

                handler.postDelayed({
                    synchronized(lock) {
                        if (currentCallback === callback) {
                            currentCallback = null
                        }
                    }
                }, CALLBACK_CLEANUP_DELAY_MS)
            }
            isScanning = false
        }
    }

    /**
     * Check if currently scanning.
     */
    fun isScanning(): Boolean = isScanning

    private fun getErrorMessage(errorCode: Int): String = when (errorCode) {
        ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "Scan already started"
        ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "Application registration failed"
        ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "Feature unsupported"
        ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "Internal error"
        else -> "Unknown error: $errorCode"
    }
}

/**
 * Exception thrown when BLE scanning fails.
 */
class ScanException(
    val errorCode: Int,
    message: String
) : Exception("Scan failed: $message (code: $errorCode)")

/**
 * Exception thrown when scanning times out without finding the target device.
 */
class ScanTimeoutException(message: String) : Exception(message)
