package com.dopple.webview.bridge

import android.view.KeyEvent

/**
 * Constants for the Loop SDK event system.
 * Defines event namespaces and button keycode mappings.
 *
 * Event names use the `loop:` prefix for a clean, professional API.
 * Button names use gaming-familiar A/B/C convention.
 */
object BridgeEventTypes {
    // Event namespaces (Loop SDK format)
    const val BUTTON_EVENT = "loop:button"
    const val MOTION_EVENT = "loop:motion"
    const val HAPTICS_EVENT = "loop:haptics"
    const val MATCH_EVENT = "loop:match"

    // Button keycode mappings
    // Button A: THUMBR - short press = bridge event, long press (>=1s) = navigation exit
    const val BUTTON_A_KEYCODE = KeyEvent.KEYCODE_BUTTON_THUMBR // 107

    // Button B: R1 - direct bridge event
    const val BUTTON_B_KEYCODE = KeyEvent.KEYCODE_BUTTON_R1 // 103

    // Button C: Top button - THUMBL / scancode 116 (confirmed via device logging)
    const val BUTTON_C_KEYCODE = KeyEvent.KEYCODE_BUTTON_THUMBL // 106

    // Long-press threshold in milliseconds for Button A (THUMBR)
    const val LONG_PRESS_THRESHOLD_MS = 1000L

    /**
     * Maps Android keycode to Loop SDK button identifier (A, B, C).
     * Returns null for unrecognized keycodes.
     */
    fun mapKeycodeToButtonId(keyCode: Int): String? = when (keyCode) {
        BUTTON_A_KEYCODE -> "A"
        BUTTON_B_KEYCODE -> "B"
        BUTTON_C_KEYCODE -> "C"
        else -> null
    }

    /**
     * Checks if a keycode is a known Loop button.
     */
    fun isBridgeButton(keyCode: Int): Boolean =
        keyCode == BUTTON_A_KEYCODE ||
        keyCode == BUTTON_B_KEYCODE ||
        keyCode == BUTTON_C_KEYCODE
}
