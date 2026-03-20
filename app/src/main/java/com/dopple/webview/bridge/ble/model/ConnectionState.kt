package com.dopple.webview.bridge.ble.model

/**
 * BLE connection state for the game session.
 *
 * Tracks the current role and connection status.
 */
enum class ConnectionState {
    /**
     * No active BLE session. Ready to host or join.
     */
    IDLE,

    /**
     * Negotiating role via seed-play protocol.
     */
    NEGOTIATING,

    /**
     * Acting as host. Created game, waiting for or connected to players.
     */
    HOSTING,

    /**
     * Client scanning for host.
     */
    SCANNING,

    /**
     * Client connecting to host.
     */
    CONNECTING,

    /**
     * Client successfully connected to host.
     */
    CONNECTED,

    /**
     * Connection lost, attempting to reconnect.
     */
    RECONNECTING;

    /**
     * Convert to lowercase string for JavaScript events.
     */
    fun toJsString(): String = name.lowercase()
}
