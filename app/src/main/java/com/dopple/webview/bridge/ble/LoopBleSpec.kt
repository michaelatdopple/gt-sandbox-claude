package com.dopple.webview.bridge.ble

import java.util.UUID

/**
 * BLE specification constants for Loop multiplayer games.
 *
 * UUIDs are custom 128-bit UUIDs following the standard format.
 * Service and characteristic UUIDs should be unique to Loop.
 */
object LoopBleSpec {
    /**
     * Loop Game Service UUID.
     *
     * Custom UUID for the Loop multiplayer game service.
     * Host-as-Server architecture: Host runs GATT server with this service,
     * players scan for the host and connect to this service.
     */
    val SERVICE_UUID: UUID = UUID.fromString("4c4f4f50-0001-1000-8000-00805f9b34fb")

    /**
     * Message Characteristic UUID.
     *
     * Bidirectional message exchange characteristic.
     * Properties: WRITE | WRITE_NO_RESPONSE | NOTIFY
     */
    val MESSAGE_CHAR_UUID: UUID = UUID.fromString("4c4f4f50-0002-1000-8000-00805f9b34fb")

    /**
     * Manufacturer ID for advertising data.
     *
     * The token bytes are included in manufacturer-specific data.
     * Using 0xFFFF (reserved for testing) during development.
     * Replace with Dopple's registered Bluetooth SIG ID for production.
     */
    const val MANUFACTURER_ID: Int = 0xFFFF

    /**
     * Maximum supported players in a game session.
     * Android BLE typically supports 5-7 concurrent connections.
     */
    const val MAX_PLAYERS: Int = 6

    /**
     * Protocol version for join code compatibility.
     */
    const val PROTOCOL_VERSION: Int = 1

    // ── Seed-play negotiation constants ──

    /** Length of token portion in manufacturer data (bytes). */
    const val TOKEN_BYTE_LENGTH: Int = 8

    /** Length of nonce portion in manufacturer data (bytes). */
    const val NONCE_BYTE_LENGTH: Int = 4

    /** Total manufacturer data length: token + nonce. */
    const val MFG_DATA_LENGTH: Int = TOKEN_BYTE_LENGTH + NONCE_BYTE_LENGTH

    /** Nonce value indicating a confirmed host (all zeros). */
    val CONFIRMED_HOST_NONCE: ByteArray = ByteArray(NONCE_BYTE_LENGTH)

    // ── Single-phase staggered election constants ──

    /** Minimum scan duration — used by highest-ranked device (ms). */
    const val SCAN_DURATION_MIN_MS: Long = 500L

    /** Maximum scan duration — used by lowest-ranked device (ms). */
    const val SCAN_DURATION_MAX_MS: Long = 3000L

    /** Grace period after self-promoting to check for competing confirmed hosts (ms). */
    const val CLAIM_GRACE_MS: Long = 200L

    /**
     * Build a manufacturer data mask: match token bytes (0xFF),
     * ignore nonce bytes (0x00). For hardware-level scan filtering.
     */
    fun buildTokenMatchMask(): ByteArray {
        val mask = ByteArray(MFG_DATA_LENGTH)
        for (i in 0 until TOKEN_BYTE_LENGTH) mask[i] = 0xFF.toByte()
        return mask
    }
}
