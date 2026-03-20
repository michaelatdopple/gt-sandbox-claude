package com.dopple.webview.bridge.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.ktx.suspend

/**
 * Player's connection to the host's GATT server.
 *
 * In the Host-as-Server architecture:
 * - Player scans for and finds the host
 * - Player connects to the host's GATT server
 * - Player sends messages to host via writeCharacteristic()
 * - Player receives messages from host via notifications
 *
 * Follows the Nordic BLE Library pattern for client connections.
 * Messages and state changes are routed via callbacks instead of SharedFlows.
 */
class LoopClientConnection(
    context: Context
) : BleManager(context) {

    companion object {
        private const val TAG = "LoopClientConnection"
        private const val TARGET_MTU = 517
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val RETRY_COUNT = 3
        private const val RETRY_DELAY_MS = 300
    }

    /**
     * Callback for routing incoming messages from the host.
     */
    interface MessageCallback {
        fun onMessageReceived(data: ByteArray)
    }

    /**
     * Callback for connection state changes.
     */
    interface ConnectionStateCallback {
        fun onConnected(hostDevice: BluetoothDevice)
        fun onDisconnected(reason: String)
    }

    // Per-connection coroutine scope — cancelled in release()
    private val connectionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Message characteristic from host's GATT server
    private var messageChar: BluetoothGattCharacteristic? = null

    // Host device reference
    private var hostDevice: BluetoothDevice? = null

    // Callbacks
    private var messageCallback: MessageCallback? = null
    private var connectionStateCallback: ConnectionStateCallback? = null

    /**
     * Set the callback for incoming messages from the host.
     */
    fun setMessageCallback(callback: MessageCallback) {
        messageCallback = callback
    }

    /**
     * Set the callback for connection state changes.
     */
    fun setConnectionStateCallback(callback: ConnectionStateCallback) {
        connectionStateCallback = callback
    }

    /**
     * Validate that the host's GATT server has the required Loop service.
     */
    override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
        val service = gatt.getService(LoopBleSpec.SERVICE_UUID)
        if (service == null) {
            Log.w(TAG, "Loop service not found on host")
            return false
        }

        messageChar = service.getCharacteristic(LoopBleSpec.MESSAGE_CHAR_UUID)
        if (messageChar == null) {
            Log.w(TAG, "Message characteristic not found on host")
            return false
        }

        Log.d(TAG, "Host service validated")
        return true
    }

    /**
     * Initialize the connection after service discovery.
     * Sets up MTU and notification callbacks.
     * Routes messages via [MessageCallback] and state via [ConnectionStateCallback].
     */
    override fun initialize() {
        Log.d(TAG, "Initializing connection to host")

        val char = messageChar
        if (char == null) {
            Log.e(TAG, "Cannot initialize - messageChar is null")
            return
        }

        // Request larger MTU for better throughput
        requestMtu(TARGET_MTU)
            .with { _, mtu ->
                Log.d(TAG, "MTU negotiated: $mtu")
            }
            .enqueue()

        // Set up notification callback to receive messages from host
        setNotificationCallback(char)
            .with { _, data ->
                data.value?.let { bytes ->
                    Log.d(TAG, "Received ${bytes.size} bytes from host")
                    messageCallback?.onMessageReceived(bytes)
                }
            }

        // Enable notifications to receive messages from host
        enableNotifications(char)
            .done {
                Log.d(TAG, "Notifications enabled on host")
            }
            .fail { _, status ->
                Log.w(TAG, "Failed to enable notifications, status: $status")
            }
            .enqueue()

        // Notify via callback
        hostDevice?.let { connectionStateCallback?.onConnected(it) }
        Log.d(TAG, "Connection to host initialized")
    }

    /**
     * Clean up when services are invalidated (disconnect or service changed).
     */
    override fun onServicesInvalidated() {
        Log.d(TAG, "Services invalidated")
        messageChar = null
        connectionStateCallback?.onDisconnected("Services invalidated")
    }

    /**
     * Connect to the host device.
     *
     * @param device The BluetoothDevice of the host found via scanning
     */
    suspend fun connectToHost(device: BluetoothDevice) {
        Log.d(TAG, "Connecting to host at ${device.address}")
        hostDevice = device

        connect(device)
            .retry(RETRY_COUNT, RETRY_DELAY_MS)
            .timeout(CONNECT_TIMEOUT_MS)
            .useAutoConnect(false)
            .suspend()

        Log.d(TAG, "Connected to host")
    }

    /**
     * Send a message to the host via write characteristic.
     *
     * @param data The message bytes to send
     */
    suspend fun send(data: ByteArray) {
        val char = messageChar
        if (char == null) {
            throw IllegalStateException("Not connected to host - characteristic not available")
        }

        Log.d(TAG, "Sending ${data.size} bytes to host")

        writeCharacteristic(char, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            .split()
            .suspend()

        Log.d(TAG, "Message sent to host")
    }

    /**
     * Get the host device if connected.
     */
    fun getHostDevice(): BluetoothDevice? = hostDevice

    /**
     * Disconnect from the host.
     */
    fun disconnectFromHost() {
        Log.d(TAG, "Disconnecting from host")
        disconnect().enqueue()
    }

    /**
     * Close the connection, cancel the per-connection scope, and release resources.
     */
    fun release() {
        Log.d(TAG, "Releasing connection to host")
        connectionScope.cancel()
        messageCallback = null
        connectionStateCallback = null
        hostDevice = null
        close()
    }
}
