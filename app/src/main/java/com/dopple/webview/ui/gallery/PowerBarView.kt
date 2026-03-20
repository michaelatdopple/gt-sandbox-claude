package com.dopple.webview.ui.gallery

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.animation.LinearInterpolator
import androidx.appcompat.widget.AppCompatTextView

/**
 * Segmented power bar for hold progress visualization.
 * Displays: [■ ■ ■ □ □] style progress
 *
 * Also supports pulsing animation for loading state.
 */
@SuppressLint("SetTextI18n")
class PowerBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private var label = "B"
    private var actionLabel = "GO"
    private var segments = 5
    private var fillColor = RetroColors.CYAN
    private var emptyColor = RetroColors.CYAN_DIM

    private var pulseAnimator: ValueAnimator? = null
    private var isPulsing = false

    init {
        typeface = Typeface.MONOSPACE
        textSize = 12f
    }

    fun configure(label: String, actionLabel: String = "", fillColor: Int = RetroColors.CYAN) {
        this.label = label
        this.actionLabel = actionLabel
        this.fillColor = fillColor
        setProgress(0f)
    }

    fun setProgress(progress: Float) {
        if (isPulsing) return  // Don't update during pulse animation

        val filledCount = (progress * segments).toInt().coerceIn(0, segments)
        val filled = FILLED.repeat(filledCount)
        val empty = EMPTY.repeat(segments - filledCount)

        val suffix = if (actionLabel.isNotEmpty()) " $actionLabel" else ""
        text = "$label:[$filled$empty]$suffix"
        setTextColor(if (progress > 0) fillColor else emptyColor)
    }

    /**
     * Start a continuous fill animation for loading state.
     * Bar fills from empty to full, then resets and repeats.
     */
    fun startPulse() {
        if (isPulsing) return

        isPulsing = true
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000  // 1 second per cycle
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()

            addUpdateListener { animator ->
                val progress = animator.animatedValue as Float
                val filledCount = (progress * segments).toInt().coerceIn(0, segments)
                val filled = FILLED.repeat(filledCount)
                val empty = EMPTY.repeat(segments - filledCount)

                text = "$label:[$filled$empty] LOADING"
                setTextColor(fillColor)
            }

            start()
        }
    }

    /**
     * Stop pulse animation and reset to normal state.
     */
    fun stopPulse() {
        isPulsing = false
        pulseAnimator?.cancel()
        pulseAnimator = null
        setProgress(0f)
    }

    companion object {
        const val FILLED = "■"
        const val EMPTY = "□"
    }
}
