package com.dopple.webview.ui.gallery

import android.graphics.Color
import androidx.core.graphics.toColorInt

/**
 * CRT/Terminal color palette for retro gallery styling.
 */
object RetroColors {
    // Background
    val BACKGROUND = "#0A0A0A".toColorInt()

    // Green phosphor (primary text/borders)
    val GREEN_PHOSPHOR = "#33FF33".toColorInt()
    val GREEN_DIM = "#1A6B1A".toColorInt()

    // Cyan (selection/highlight)
    val CYAN = "#00FFFF".toColorInt()
    val CYAN_DIM = "#006666".toColorInt()

    // Amber (accents)
    val AMBER = "#FFB000".toColorInt()
    val AMBER_DIM = "#664600".toColorInt()

    // Scanline overlay
    val SCANLINE = Color.argb(13, 0, 0, 0)  // 5% opacity black
}
