package com.dopple.webview.ui.webview

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

/**
 * Overlay view that displays current FPS reading.
 * Used for performance monitoring during WebView display.
 */
class FpsOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {

    init {
        // Don't intercept touch events
        isClickable = false
        isFocusable = false
        // Center the text
        textAlignment = TEXT_ALIGNMENT_CENTER
    }

    fun updateFps(fps: Int) {
        text = "$fps"
    }
}
