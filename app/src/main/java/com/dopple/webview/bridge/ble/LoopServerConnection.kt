package com.dopple.webview.bridge.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.content.Context
import android.util.Log
import com.dopple.webview.bridge.ble.model.PlayerInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.ktx.suspend

/**
 * Per-player connection on the host side.
 *
 * In the Host-as-Server architecture:
 * - Each player that connects to the host gets a LoopServerConnection
 * - The host sends messages to players via sendNotification()
 * - The host receives messages from players via write callbacks
 *
 * Uses the Nordic BLE Library's server support via useServer().
 * Messages are routed via [MessageCallback] instead of SharedFlow.
 */
class LoopServerConnection(
    context: Context,
    private var playerInfo: PlayerInfo
) : BleManager(context) {

    companion object {
        private const val TAG = "LoopServerConnection"
    }

    /**
     * Callback for routing incoming messages to the orchestrator.
     */
    interface MessageCallback {
        fun onMessageReceived(data: ByteArray, player: PlayerInfo)
    }

    // Per-connection coroutine scope — cancelled in release()
    private val connectionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Reference to the server manager
    private var serverManager: LoopServerManager? = null

    // Message characteristic from the server
    private var messageChar: BluetoothGattCharacteristic? = null

    // Callback for message routing
    private var messageCallback: MessageCallback? = null

    /**
     * Set the callback for incoming messages from this player.
     */
    fun setMessageCallback(callback: MessageCallback) {
        messageCallback = callback
    }

    /**
     * Not used for server connections - we don't discover services on the remote device.
     */
    override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
        // Server connections don't discover remote services
        return true
    }

    /**
     * Called when the server is ready for this connection.
     * Gets the characteristic from our local GATT server.
     */
    override fun onServerReady(server: BluetoothGattServer) {
        Log.d(TAG, "Server ready for ${playerInfo.name}")
        messageChar = server.getService(LoopBleSpec.SERVICE_UUID)
            ?.getCharacteristic(LoopBleSpec.MESSAGE_CHAR_UUID)

        if (messageChar == null) {
            Log.e(TAG, "Message characteristic not found in server!")
        }
    }

    /**
     * Initialize the connection.
     * Sets up write callback to receive messages from the player.
     * Routes messages via [MessageCallback] instead of SharedFlow.
     */
    override fun initialize() {
        Log.d(TAG, "Initializing connection for ${playerInfo.name}")

        val char = messageChar
        if (char == null) {
            Log.e(TAG, "Cannot initialize - messageChar is null")
            return
        }

        // Set up callback to receive writes from the player
        setWriteCallback(char)
            .with { device, data ->
                data.value?.let { bytes ->
                    Log.d(TAG, "Received ${bytes.size} bytes from ${playerInfo.name}")
                    messageCallback?.onMessageReceived(bytes, playerInfo)
                    // Also emit to server manager for centralized handling
                    serverManager?.emitWriteEvent(device, bytes)
                }
            }

        // Wait for player to enable notifications
        waitUntilNotificationsEnabled(char)
            .done {
                Log.d(TAG, "Notifications enabled for ${playerInfo.name}")
            }
            .fail { _, status ->
                Log.w(TAG, "Notifications not enabled for ${playerInfo.name}, status: $status")
            }
            .enqueue()

        Log.d(TAG, "${playerInfo.name} initialized")
    }

    /**
     * Clean up when services are invalidated (shouldn't happen for server-side).
     */
    override fun onServicesInvalidated() {
        Log.d(TAG, "Services invalidated for ${playerInfo.name}")
        messageChar = null
    }

    /**
     * Connect to a player device that has connected to our server.
     *
     * @param device The BluetoothDevice of the connected player
     * @param server The LoopServerManager hosting our GATT server
     */
    suspend fun connectToPlayer(device: BluetoothDevice, server: LoopServerManager) {
        Log.d(TAG, "Setting up connection for ${playerInfo.name} at ${device.address}")
        serverManager = server

        // Use the server for this connection
        useServer(server)

        // Connect to the device that has already connected to our server
        connect(device)
            .useAutoConnect(false)
            .suspend()

        Log.d(TAG, "Connection established for ${playerInfo.name}")
    }

    /**
     * Send a message to this player via notification.
     *
     * @param data The message bytes to send
     */
    suspend fun send(data: ByteArray) {
        val char = messageChar
        if (char == null) {
            throw IllegalStateException("Not connected to ${playerInfo.name} - characteristic not available")
        }

        Log.d(TAG, "Sending ${data.size} bytes to ${playerInfo.name}")

        sendNotification(char, data)
            .split()
            .suspend()

        Log.d(TAG, "Notification sent to ${playerInfo.name}")
    }

    /**
     * Get the player info for this connection.
     */
    fun getPlayerInfo(): PlayerInfo = playerInfo

    /**
     * Update the player's display name (e.g. after receiving a __sys:name message).
     */
    fun updatePlayerName(name: String) {
        playerInfo = playerInfo.copy(name = name)
    }

    /**
     * Disconnect from the player.
     */
    fun disconnectPlayer() {
        Log.d(TAG, "Disconnecting ${playerInfo.name}")
        disconnect().enqueue()
    }

    /**
     * Close the connection, cancel the per-connection scope, and release resources.
     */
    fun release() {
        Log.d(TAG, "Releasing connection to ${playerInfo.name}")
        connectionScope.cancel()
        messageCallback = null
        serverManager = null
        close()
    }
}
