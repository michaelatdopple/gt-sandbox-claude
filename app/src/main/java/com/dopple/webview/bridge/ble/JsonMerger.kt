package com.dopple.webview.bridge.ble

import no.nordicsemi.android.ble.data.DataMerger
import no.nordicsemi.android.ble.data.DataStream

/**
 * DataMerger for reassembling chunked JSON messages.
 *
 * Nordic BLE library splits large messages across multiple packets.
 * This merger reassembles them by detecting JSON completeness.
 *
 * Strategy: accumulate bytes until we have valid JSON (balanced braces/brackets).
 */
class JsonMerger : DataMerger {

    private val buffer = StringBuilder()

    override fun merge(output: DataStream, lastPacket: ByteArray?, index: Int): Boolean {
        if (lastPacket == null || lastPacket.isEmpty()) {
            return false
        }

        // Append to our internal buffer for JSON validation
        val chunk = String(lastPacket, Charsets.UTF_8)
        buffer.append(chunk)

        // Write to output stream
        output.write(lastPacket)

        // Check if we have complete JSON
        val complete = isCompleteJson(buffer.toString())

        if (complete) {
            buffer.clear()
        }

        return complete
    }

    /**
     * Check if the string contains a complete JSON object or array.
     */
    private fun isCompleteJson(json: String): Boolean {
        if (json.isBlank()) return false

        val trimmed = json.trim()
        if (trimmed.isEmpty()) return false

        // Must start with { or [
        val firstChar = trimmed.first()
        if (firstChar != '{' && firstChar != '[') return false

        var braceCount = 0
        var bracketCount = 0
        var inString = false
        var escaped = false

        for (c in trimmed) {
            when {
                escaped -> escaped = false
                c == '\\' -> escaped = true
                c == '"' -> inString = !inString
                !inString && c == '{' -> braceCount++
                !inString && c == '}' -> braceCount--
                !inString && c == '[' -> bracketCount++
                !inString && c == ']' -> bracketCount--
            }
        }

        return braceCount == 0 && bracketCount == 0
    }
}
