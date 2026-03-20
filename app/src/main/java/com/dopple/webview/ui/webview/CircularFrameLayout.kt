package com.dopple.webview.ui.webview

import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.core.graphics.withSave

/**
 * FrameLayout that clips its content to a circular shape.
 * Used to wrap pooled WebViews for the circular display.
 */
class CircularFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val clipPath = Path()
    private var circleCenterX = 0f
    private var circleCenterY = 0f
    private var circleRadius = 0f

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        circleCenterX = w / 2f
        circleCenterY = h / 2f
        circleRadius = minOf(w, h) / 2f

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
}
