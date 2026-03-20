package com.dopple.webview.bridge.ble

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import android.webkit.WebView
import com.dopple.webview.bridge.ble.model.ConnectionState
import com.dopple.webview.bridge.ble.model.JoinCode
import com.dopple.webview.bridge.ble.model.PlayerInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Result of a seed-play negotiation triggered by [BLEGameManager.playGame].
 */
data class PlayGameResult(
    val success: Boolean,
    val role: String,
    val token: String,
    val error: String? = null
)

/**
 * High-level orchestrator for BLE multiplayer game sessions.
 *
 * Host-as-Server Architecture:
 * - HOST: Creates GATT server, advertises, players connect to host
 * - CLIENT: Scans for host, connects to host's GATT server
 *
 * Message flow:
 * - Host → Player: sendNotification() from local GATT server
 * - Player → Host: writeCharacteristic() to host's server
 *
 * Implements [LoopServerConnection.MessageCallback] and
 * [LoopClientConnection.MessageCallback] / [LoopClientConnection.ConnectionStateCallback]
 * for callback-based message routing (no per-connection collector coroutines).
 */
class BLEGameManager(
    private val context: Context,
    private val activity: Activity,
    private val webView: WebView,
    private val clientConnectionFactory: () -> LoopClientConnection =
        { LoopClientConnection(context) }
) : LoopServerConnection.MessageCallback,
    LoopClientConnection.MessageCallback,
    LoopClientConnection.ConnectionStateCallback {

    // Coroutine scope for BLE operations
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Bluetooth adapter
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }

    // Event dispatcher for WebView
    private val eventDispatcher = BLEEventDispatcher(webView, activity)

    // Current state
    private val _state = MutableStateFlow(ConnectionState.IDLE)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    // Current game ID
    private var currentGameId: String? = null

    // ---- HOST Components (Server mode) ----
    private var serverManager: LoopServerManager? = null
    private var advertiser: LoopAdvertiser? = null
    private val connectedPlayers = mutableMapOf<String, LoopServerConnection>()
    private var hostToken: String? = null

    // ---- CLIENT Components (Player mode) ----
    private var clientScanner: LoopClientScanner? = null
    private var clientConnection: LoopClientConnection? = null

    // ---- NEGOTIATION Components (Seed-play mode) ----
    private var negotiator: LoopNegotiator? = null

    // ---- Player identity ----
    private var localPlayerName: String = "Player"

    /** Pending name-timeout jobs keyed by device address — cancelled when __sys:name arrives. */
    private val nameTimeoutJobs = mutableMapOf<String, Job>()

    // ============================================================
    // Callback implementations
    // ============================================================

    /** LoopServerConnection.MessageCallback — host receives player message */
    override fun onMessageReceived(data: ByteArray, player: PlayerInfo) {
        handleIncomingMessage(data, player)
    }

    /** LoopClientConnection.MessageCallback — client receives host message */
    override fun onMessageReceived(data: ByteArray) {
        handleIncomingMessage(data, null) // null = from host
    }

    /** LoopClientConnection.ConnectionStateCallback — connected to host */
    override fun onConnected(hostDevice: BluetoothDevice) {
        if (_state.value == ConnectionState.CONNECTING) {
            _state.value = ConnectionState.CONNECTED
            val hostInfo = PlayerInfo(hostDevice.address, "Host")
            eventDispatcher.dispatchConnected(hostInfo)
        }
    }

    /** LoopClientConnection.ConnectionStateCallback — disconnected from host */
    override fun onDisconnected(reason: String) {
        if (_state.value == ConnectionState.CONNECTED) {
            _state.value = ConnectionState.IDLE
            eventDispatcher.dispatchDisconnected(reason)
        }
    }

    // ============================================================
    // HOST API (Server mode - creates game, players connect to us)
    // ============================================================

    /**
     * Create a game as host.
     *
     * The host:
     * 1. Creates a GATT server with the Loop service
     * 2. Advertises with a token for players to find
     * 3. Players scan for this token and connect
     *
     * @param gameId The game identifier (e.g., "battleship")
     * @param preGeneratedToken Optional pre-generated token; skips internal
     *   generation. Used by the bridge to return the token synchronously.
     * @param existingAdvertiser Optional already-running advertiser from negotiation.
     *   If provided, skip creating a new advertiser (zero transition gap).
     * @return JoinCode containing the token for players to scan
     */
    suspend fun createGame(
        gameId: String,
        preGeneratedToken: String? = null,
        existingAdvertiser: LoopAdvertiser? = null
    ): JoinCode {
        require(_state.value == ConnectionState.IDLE) {
            "Cannot create game: current state is ${_state.value}"
        }

        val adapter = bluetoothAdapter
            ?: throw IllegalStateException("Bluetooth not available")

        val token = preGeneratedToken ?: JoinCode.generateToken()
        hostToken = token

        serverManager = LoopServerManager(context).apply {
            setConnectionCallback(object : LoopServerManager.ConnectionCallback {
                override fun onPlayerConnected(device: BluetoothDevice) {
                    handlePlayerConnected(device)
                }

                override fun onPlayerDisconnected(device: BluetoothDevice) {
                    handlePlayerDisconnected(device)
                }
            })

            // Collect incoming writes from all players (one collector per game, on manager scope)
            scope.launch {
                incomingWrites.collect { writeEvent ->
                    handleIncomingMessage(
                        writeEvent.data,
                        PlayerInfo(writeEvent.deviceAddress, getPlayerName(writeEvent.deviceAddress))
                    )
                }
            }

            open()
        }

        // Reuse negotiation advertiser if provided, otherwise create new
        advertiser = existingAdvertiser ?: LoopAdvertiser(adapter).apply {
            startAdvertising(token)
        }

        currentGameId = gameId
        _state.value = ConnectionState.HOSTING

        Log.d(TAG, "Game created, advertising with token: $token")
        return JoinCode(token, gameId, "Host")
    }

    /**
     * Handle a player connecting to our GATT server.
     * Uses [MessageCallback] — no per-player collector coroutine launched.
     *
     * playerJoined dispatch is deferred until the client's __sys:name message
     * arrives so the event contains the real player name. A 2s timeout fires
     * the event with a fallback name if no __sys:name is received.
     */
    private fun handlePlayerConnected(device: BluetoothDevice) {
        Log.i(TAG, "Player connected: ${device.address}")

        val playerInfo = PlayerInfo(device.address, "Player-${device.address.takeLast(5)}")

        // Create a LoopServerConnection for this player — pass callback, no collector
        val connection = LoopServerConnection(context, playerInfo).apply {
            setMessageCallback(this@BLEGameManager)
        }

        // Connect to the player using our server
        scope.launch {
            try {
                val server = serverManager
                    ?: throw IllegalStateException("Server manager not available")
                connection.connectToPlayer(device, server)
                connectedPlayers[device.address] = connection

                // Defer playerJoined — wait for __sys:name (timeout dispatches fallback)
                nameTimeoutJobs[device.address] = scope.launch {
                    delay(NAME_TIMEOUT_MS)
                    Log.d(TAG, "Name timeout for ${device.address}, dispatching fallback")
                    nameTimeoutJobs.remove(device.address)
                    eventDispatcher.dispatchPlayerJoined(connection.getPlayerInfo())
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set up connection to ${device.address}", e)
                connection.release()
            }
        }
    }

    /**
     * Handle a player disconnecting from our GATT server.
     */
    private fun handlePlayerDisconnected(device: BluetoothDevice) {
        Log.d(TAG, "Player disconnected: ${device.address}")

        connectedPlayers.remove(device.address)?.let { connection ->
            val playerInfo = connection.getPlayerInfo()
            connection.release()
            eventDispatcher.dispatchPlayerLeft(playerInfo)
        }
    }

    /**
     * Get player name by address (or generate one).
     */
    private fun getPlayerName(address: String): String {
        return connectedPlayers[address]?.getPlayerInfo()?.name
            ?: "Player-${address.takeLast(5)}"
    }

    /**
     * Send a message to a specific player (host only).
     *
     * @param playerId The player's ID (device address)
     * @param data The message data as JSON string
     */
    suspend fun sendToPlayer(playerId: String, data: String) {
        require(_state.value == ConnectionState.HOSTING) {
            "Cannot send: not hosting"
        }

        val connection = connectedPlayers[playerId]
            ?: throw IllegalArgumentException("Player not found: $playerId")

        connection.send(data.toByteArray(Charsets.UTF_8))
    }

    /**
     * Send a message to all connected players (host only).
     *
     * @param data The message data as JSON string
     */
    suspend fun sendToAllPlayers(data: String) {
        require(_state.value == ConnectionState.HOSTING) {
            "Cannot send: not hosting"
        }

        val bytes = data.toByteArray(Charsets.UTF_8)
        connectedPlayers.values.forEach { connection ->
            try {
                connection.send(bytes)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send to ${connection.getPlayerInfo().name}", e)
            }
        }
    }

    /**
     * End the game and disconnect all players (host only).
     */
    fun endGame() {
        Log.d(TAG, "Ending game")

        // Stop advertising
        advertiser?.stopAdvertising()
        advertiser = null

        // Disconnect all players
        connectedPlayers.values.forEach { connection ->
            val playerInfo = connection.getPlayerInfo()
            connection.release()
            eventDispatcher.dispatchPlayerLeft(playerInfo)
        }
        connectedPlayers.clear()

        // Stop server
        serverManager?.stop()
        serverManager = null

        hostToken = null
        currentGameId = null
        _state.value = ConnectionState.IDLE
    }

    // ============================================================
    // SEED-PLAY API (Auto-role negotiation)
    // ============================================================

    /**
     * Play a game using seed-based auto-role negotiation.
     *
     * Derives a deterministic BLE token from [seed], then runs the
     * scan-first / elect-if-needed protocol to decide host vs. client.
     *
     * @param seed Shared seed string (e.g. NFC tag ID, QR payload)
     * @param playerName The local player's display name
     * @return [PlayGameResult] with the resolved role and token
     */
    suspend fun playGame(seed: String, playerName: String): PlayGameResult {
        if (_state.value != ConnectionState.IDLE) {
            Log.w(TAG, "playGame: auto-resetting from ${_state.value}")
            cleanup()
        }

        localPlayerName = playerName

        val adapter = bluetoothAdapter
            ?: throw IllegalStateException("Bluetooth not available")

        _state.value = ConnectionState.NEGOTIATING

        val negScanner = LoopClientScanner(adapter)
        val negAdvertiser = LoopAdvertiser(adapter)
        val neg = LoopNegotiator(adapter, negScanner, negAdvertiser)
        negotiator = neg

        try {
            val t0 = System.currentTimeMillis()
            val negotiationResult = neg.negotiate(seed)
            val tNeg = System.currentTimeMillis()

            // Reset to IDLE before delegating to createGame/connect
            _state.value = ConnectionState.IDLE
            negotiator = null

            return when (negotiationResult) {
                is NegotiationResult.BecomeHost -> {
                    val token = negotiationResult.token
                    // Pass the still-running advertiser — zero transition gap
                    createGame("seed-play",
                        preGeneratedToken = token,
                        existingAdvertiser = negotiationResult.advertiser)
                    val tConn = System.currentTimeMillis()
                    Log.i(TAG, "TIMING: playGame negotiate=${tNeg - t0}ms " +
                        "connect=${tConn - tNeg}ms total=${tConn - t0}ms role=host")
                    eventDispatcher.dispatchRoleResolved("host", token)
                    PlayGameResult(success = true, role = "host", token = token)
                }
                is NegotiationResult.JoinHost -> {
                    val token = negotiationResult.token
                    connectToDiscoveredHost(negotiationResult.hostDevice)
                    // Send our name to the host over BLE
                    sendSystemMessage(buildNameMessage(playerName))
                    val tConn = System.currentTimeMillis()
                    Log.i(TAG, "TIMING: playGame negotiate=${tNeg - t0}ms " +
                        "connect=${tConn - tNeg}ms total=${tConn - t0}ms role=client")
                    eventDispatcher.dispatchRoleResolved("client", token)
                    PlayGameResult(success = true, role = "client", token = token)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "playGame negotiation failed", e)
            negScanner.stopScan()
            negAdvertiser.stopAdvertising()
            negotiator = null
            _state.value = ConnectionState.IDLE
            throw e
        }
    }

    /**
     * Connect to a discovered host device, bypassing the scan phase.
     * Used by seed-play negotiation when the host has already been found.
     */
    private suspend fun connectToDiscoveredHost(hostDevice: BluetoothDevice) {
        _state.value = ConnectionState.CONNECTING
        clientConnection = clientConnectionFactory().apply {
            setMessageCallback(this@BLEGameManager)
            setConnectionStateCallback(this@BLEGameManager)
        }

        try {
            clientConnection!!.connectToHost(hostDevice)
            Log.d(TAG, "Connected to discovered host: ${hostDevice.address}")
        } catch (e: Exception) {
            Log.w(TAG, "GATT connect failed, resetting to IDLE: ${e.message}")
            clientConnection?.release()
            clientConnection = null
            _state.value = ConnectionState.IDLE
            throw e
        }
    }

    // ============================================================
    // CLIENT API (Player mode - joins game, connects to host)
    // ============================================================

    /**
     * Join a game as a player.
     *
     * The player:
     * 1. Scans for the host's advertising token
     * 2. Connects to the host's GATT server
     * 3. Sends messages via writeCharacteristic()
     * 4. Receives messages via notifications
     *
     * Wraps connectToHost() in try/catch for CONNECTING → IDLE recovery.
     *
     * @param hostToken The token from the host's QR code
     * @param playerName The player's display name
     */
    suspend fun joinGame(hostToken: String, playerName: String) {
        require(_state.value == ConnectionState.IDLE) {
            "Cannot join: current state is ${_state.value}"
        }

        val adapter = bluetoothAdapter
            ?: throw IllegalStateException("Bluetooth not available")

        Log.d(TAG, "Joining game with token: $hostToken, player: $playerName")

        _state.value = ConnectionState.SCANNING

        // Scan for the host — reset state on failure so client can retry
        clientScanner = LoopClientScanner(adapter)
        val hostDevice: BluetoothDevice
        try {
            hostDevice = clientScanner!!.scanForHost(hostToken)
        } catch (e: Exception) {
            Log.w(TAG, "Scan failed, resetting to IDLE: ${e.message}")
            clientScanner?.stopScan()
            clientScanner = null
            _state.value = ConnectionState.IDLE
            throw e
        }
        Log.d(TAG, "Found host: ${hostDevice.address}")

        // Connect to the host's GATT server — callbacks replace collector coroutines
        _state.value = ConnectionState.CONNECTING
        clientConnection = clientConnectionFactory().apply {
            setMessageCallback(this@BLEGameManager)
            setConnectionStateCallback(this@BLEGameManager)
        }

        try {
            clientConnection!!.connectToHost(hostDevice)
        } catch (e: Exception) {
            Log.w(TAG, "GATT connect failed, resetting to IDLE: ${e.message}")
            clientConnection?.release()
            clientConnection = null
            _state.value = ConnectionState.IDLE
            throw e
        }

        Log.d(TAG, "Connected to host")
    }

    /**
     * Send a message to the host (client only).
     *
     * @param data The message data as JSON string
     */
    suspend fun sendToHost(data: String) {
        require(_state.value == ConnectionState.CONNECTED) {
            "Cannot send: not connected to host"
        }

        val connection = clientConnection
            ?: throw IllegalStateException("No connection to host")

        connection.send(data.toByteArray(Charsets.UTF_8))
    }

    /**
     * Leave the game and disconnect from host (client only).
     */
    fun leaveGame() {
        Log.d(TAG, "Leaving game")

        clientScanner?.stopScan()
        clientScanner = null

        clientConnection?.release()
        clientConnection = null

        _state.value = ConnectionState.IDLE
    }

    // ============================================================
    // Common API
    // ============================================================

    /**
     * Get the current connection state.
     */
    fun getState(): ConnectionState = _state.value

    /**
     * Get list of connected players (host only).
     */
    fun getConnectedPlayers(): List<PlayerInfo> =
        connectedPlayers.values.map { it.getPlayerInfo() }

    /**
     * Get the host's token (host only, while hosting).
     */
    fun getHostToken(): String? = hostToken

    /**
     * Clean up all resources.
     * Uses explicit state check instead of calling both endGame() and leaveGame().
     */
    fun cleanup() {
        Log.d(TAG, "Cleaning up BLEGameManager")

        when (_state.value) {
            ConnectionState.HOSTING -> endGame()
            ConnectionState.NEGOTIATING -> {
                negotiator?.cancel()
                negotiator = null
                _state.value = ConnectionState.IDLE
            }
            ConnectionState.IDLE -> {}
            else -> leaveGame()
        }

        eventDispatcher.cleanup()
    }

    // ============================================================
    // Private helpers
    // ============================================================

    companion object {
        private const val TAG = "BLEGameManager"

        /** How long to wait for a __sys:name before dispatching fallback playerJoined. */
        private const val NAME_TIMEOUT_MS = 2000L

        // System message types
        private const val SYS_KEY = "__sys"
        private const val SYS_NAME = "name"
        private const val SYS_PEER_JOINED = "peerJoined"
    }

    /**
     * Handle incoming message from any source.
     * Intercepts system messages (__sys) before they reach JS.
     *
     * @param bytes The raw message bytes
     * @param from The sender (null if from host, as client)
     */
    private fun handleIncomingMessage(bytes: ByteArray, from: PlayerInfo?) {
        val json = String(bytes, Charsets.UTF_8)
        Log.d(TAG, "Message received: $json from ${from?.name ?: "host"}")

        // Intercept system messages
        try {
            val obj = JSONObject(json)
            if (obj.has(SYS_KEY)) {
                handleSystemMessage(obj, from)
                return
            }
        } catch (_: Exception) {
            // Not JSON or no __sys key — fall through to regular dispatch
        }

        val sender = from ?: PlayerInfo("host", "Host")
        eventDispatcher.dispatchMessage(json, sender)
    }

    /**
     * Process a system message.
     *
     * Host receives __sys:name from players → updates PlayerInfo, dispatches playerJoined,
     * sends host name back, broadcasts peer info to other players.
     *
     * Client receives __sys:name from host → re-dispatches connected with real host name.
     * Client receives __sys:peerJoined → dispatches playerJoined for peer awareness.
     */
    private fun handleSystemMessage(obj: JSONObject, from: PlayerInfo?) {
        when (obj.getString(SYS_KEY)) {
            SYS_NAME -> {
                val name = obj.getString("name")

                if (from != null && _state.value == ConnectionState.HOSTING) {
                    // HOST: received player's name
                    val address = from.id
                    Log.d(TAG, "Received player name '$name' from $address")

                    // Update the connection's PlayerInfo
                    connectedPlayers[address]?.let { connection ->
                        connection.updatePlayerName(name)

                        // Cancel fallback timeout and dispatch with real name
                        nameTimeoutJobs.remove(address)?.cancel()
                        eventDispatcher.dispatchPlayerJoined(connection.getPlayerInfo())
                    }

                    // Send host name back to this player
                    scope.launch {
                        try {
                            sendToPlayer(address, buildNameMessage(localPlayerName))
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to send host name to $address", e)
                        }
                    }

                    // Broadcast peer info to all other connected players
                    scope.launch {
                        val peerMsg = buildPeerJoinedMessage(address, name)
                        connectedPlayers.forEach { (addr, conn) ->
                            if (addr != address) {
                                try {
                                    conn.send(peerMsg.toByteArray(Charsets.UTF_8))
                                } catch (e: Exception) {
                                    Log.w(TAG, "Failed to broadcast peer info to $addr", e)
                                }
                            }
                        }
                    }
                } else if (_state.value == ConnectionState.CONNECTED) {
                    // CLIENT: received host's name
                    Log.d(TAG, "Received host name '$name'")
                    val hostInfo = PlayerInfo("host", name)
                    eventDispatcher.dispatchConnected(hostInfo)
                }
            }

            SYS_PEER_JOINED -> {
                // CLIENT: a new peer joined the game
                val peerId = obj.getString("id")
                val peerName = obj.getString("name")
                Log.d(TAG, "Peer joined: $peerName ($peerId)")
                eventDispatcher.dispatchPlayerJoined(PlayerInfo(peerId, peerName))
            }

            else -> Log.w(TAG, "Unknown system message type: ${obj.getString(SYS_KEY)}")
        }
    }

    /**
     * Send a system message over the BLE channel.
     * Routes to sendToHost (client) or sendToPlayer (host).
     */
    private suspend fun sendSystemMessage(message: String) {
        when (_state.value) {
            ConnectionState.CONNECTED -> sendToHost(message)
            ConnectionState.HOSTING -> {
                // Host doesn't send system messages to all — only targeted via handleSystemMessage
                Log.w(TAG, "sendSystemMessage called while HOSTING — use sendToPlayer instead")
            }
            else -> Log.w(TAG, "Cannot send system message in state ${_state.value}")
        }
    }

    private fun buildNameMessage(name: String): String =
        """{"$SYS_KEY":"$SYS_NAME","name":${JSONObject.quote(name)}}"""

    private fun buildPeerJoinedMessage(id: String, name: String): String =
        """{"$SYS_KEY":"$SYS_PEER_JOINED","id":${JSONObject.quote(id)},"name":${JSONObject.quote(name)}}"""
}
