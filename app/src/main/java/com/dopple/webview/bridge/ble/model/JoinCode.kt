package com.dopple.webview.bridge.ble.model

import com.dopple.webview.bridge.ble.LoopBleSpec
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

/**
 * QR code payload for player invitation.
 *
 * Players generate this when advertising, display it as QR.
 * Host scans the QR to get the JoinCode and connect.
 *
 * @property token Unique 8-character advertising token for BLE discovery
 * @property gameId Game identifier (e.g., "battleship", "othello")
 * @property name Player's display name
 * @property v Protocol version for compatibility checking
 */
data class JoinCode(
    @SerializedName("token")
    val token: String,

    @SerializedName("gameId")
    val gameId: String,

    @SerializedName("name")
    val name: String,

    @SerializedName("v")
    val v: Int = LoopBleSpec.PROTOCOL_VERSION
) {
    /**
     * Serialize to JSON string for QR code generation.
     */
    fun toJson(): String = gson.toJson(this)

    companion object {
        private val gson = Gson()

        /**
         * Parse JoinCode from JSON string (scanned from QR).
         *
         * @throws com.google.gson.JsonSyntaxException if JSON is malformed
         */
        fun fromJson(json: String): JoinCode = gson.fromJson(json, JoinCode::class.java)

        /**
         * Generate a random 8-character token for advertising.
         * Uses only hex characters (0-9, a-f) so they can be entered on the game keypad.
         */
        fun generateToken(): String {
            val chars = "0123456789abcdef"
            return (1..8)
                .map { chars.random() }
                .joinToString("")
        }
    }
}
