package com.dopple.webview.bridge.ble.model

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

/**
 * Information about a connected player.
 *
 * Used to identify message senders/recipients in game events.
 *
 * @property id Unique player identifier (typically the BLE device address or generated UUID)
 * @property name Player's display name
 */
data class PlayerInfo(
    @SerializedName("id")
    val id: String,

    @SerializedName("name")
    val name: String
) {
    /**
     * Serialize to JSON for JavaScript callbacks.
     */
    fun toJson(): String = gson.toJson(this)

    companion object {
        private val gson = Gson()

        /**
         * Parse PlayerInfo from JSON string.
         */
        fun fromJson(json: String): PlayerInfo = gson.fromJson(json, PlayerInfo::class.java)
    }
}
