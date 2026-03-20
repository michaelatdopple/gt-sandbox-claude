package com.dopple.webview.bridge.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattCharacteristic.PERMISSION_WRITE
import android.bluetooth.BluetoothGattCharacteristic.PROPERTY_NOTIFY
import android.bluetooth.BluetoothGattCharacteristic.PROPERTY_WRITE
import android.bluetooth.BluetoothGattService
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import no.nordicsemi.android.ble.BleServerManager
import no.nordicsemi.android.ble.observer.ServerObserver

/**
 * Host's GATT server definition for the Loop multiplayer game service.
 *
 * In the Host-as-Server architecture:
 * - Host creates this GATT server and advertises
 * - Players scan, find the host, and connect to this server
 * - Host sends messages to players via notifications
 * - Players send messages to host via writes
 *
 * Follows the Nordic BLE Library "trivia" example pattern.
 */
class LoopServerManager(
    context: Context
) : BleServerManager(context), ServerObserver {

    companion object {
        private const val TAG = "LoopServerManager"
    }

    // Callback for when players connect/disconnect
    private var connectionCallback: ConnectionCallback? = null

    // Message characteristic for bidirectional communication
    private var messageChar: BluetoothGattCharacteristic? = null

    // Incoming write requests from players
    private val _incomingWrites = MutableSharedFlow<WriteEvent>(extraBufferCapacity = 64)
    val incomingWrites: SharedFlow<WriteEvent> = _incomingWrites.asSharedFlow()

    // Track server ready state
    @Volatile
    private var serverReady = false

    init {
        setServerObserver(this)
    }

    /**
     * Initialize the GATT server with the Loop game service.
     *
     * Service contains a single characteristic for message exchange:
     * - WRITE: Players write messages to host
     * - NOTIFY: Host sends messages to players
     */
    override fun initializeServer(): List<BluetoothGattService> {
        Log.d(TAG, "Initializing Loop GATT server")

        messageChar = sharedCharacteristic(
            LoopBleSpec.MESSAGE_CHAR_UUID,
            PROPERTY_WRITE or PROPERTY_NOTIFY,
            PERMISSION_WRITE,
            cccd(),
            description("Loop game message exchange", false)
        )

        Log.d(TAG, "Server initialized with service: ${LoopBleSpec.SERVICE_UUID}")

        return listOf(
            service(LoopBleSpec.SERVICE_UUID, messageChar!!)
        )
    }

    override fun onServerReady() {
        Log.i(TAG, "GATT server ready and accepting connections")
        serverReady = true
    }

    override fun onDeviceConnectedToServer(device: BluetoothDevice) {
        Log.i(TAG, "Player connected: ${device.address}")
        connectionCallback?.onPlayerConnected(device)
    }

    override fun onDeviceDisconnectedFromServer(device: BluetoothDevice) {
        Log.d(TAG, "Player disconnected: ${device.address}")
        connectionCallback?.onPlayerDisconnected(device)
    }

    /**
     * Get the message characteristic for LoopServerConnection to use.
     */
    fun getMessageCharacteristic(): BluetoothGattCharacteristic? = messageChar

    /**
     * Check if the server is ready.
     */
    fun isReady(): Boolean = serverReady

    /**
     * Set callback for connection events.
     */
    fun setConnectionCallback(callback: ConnectionCallback) {
        connectionCallback = callback
    }

    /**
     * Emit a write event when a player writes to the message characteristic.
     */
    fun emitWriteEvent(device: BluetoothDevice, data: ByteArray) {
        Log.d(TAG, "Write received from ${device.address}: ${data.size} bytes")
        _incomingWrites.tryEmit(WriteEvent(device.address, data))
    }

    /**
     * Stop the server and clean up.
     */
    fun stop() {
        Log.d(TAG, "Stopping GATT server")
        serverReady = false
        messageChar = null
        connectionCallback = null
        close()
    }

    /**
     * Event representing a write from a player.
     */
    data class WriteEvent(
        val deviceAddress: String,
        val data: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is WriteEvent) return false
            return deviceAddress == other.deviceAddress && data.contentEquals(other.data)
        }

        override fun hashCode(): Int {
            return 31 * deviceAddress.hashCode() + data.contentHashCode()
        }
    }

    /**
     * Callback interface for connection events.
     */
    interface ConnectionCallback {
        fun onPlayerConnected(device: BluetoothDevice)
        fun onPlayerDisconnected(device: BluetoothDevice)
    }
}
