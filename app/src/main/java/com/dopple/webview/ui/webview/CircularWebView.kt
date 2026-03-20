package com.dopple.webview.ui.webview

import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.util.AttributeSet
import android.webkit.WebView
import androidx.core.graphics.withClip
import androidx.core.graphics.withSave

/**
 * Custom WebView that clips content to a circular shape.
 * Matches the 800x800px circular display form factor.
 */
class CircularWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : WebView(context, attrs, defStyleAttr) {

    private val clipPath = Path()
    private var circleCenterX = 0f
    private var circleCenterY = 0f
    private var circleRadius = 0f

    init {
        // Software layer required for clip path support in some cases
        // However, this can impact WebView performance, so we'll try hardware first
        // and only fall back to software if needed
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        // Center the circle in the view
        circleCenterX = w / 2f
        circleCenterY = h / 2f

        // Use the smaller dimension to fit the circle
        // Fill the entire 800x800 circular display
        val maxRadius = minOf(w, h) / 2f
        circleRadius = maxRadius // Full screen circular mask for 800x800 display

        // Build the circular clip path
        clipPath.reset()
        clipPath.addCircle(circleCenterX, circleCenterY, circleRadius, Path.Direction.CW)
    }

    override fun dispatchDraw(canvas: Canvas) {
        canvas.withSave {
            if (!clipPath.isEmpty) {
                clipPath(clipPath)
            }
            super.dispatchDraw(this)
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.withClip(clipPath) {
            super.onDraw(this)
        }
    }
}
