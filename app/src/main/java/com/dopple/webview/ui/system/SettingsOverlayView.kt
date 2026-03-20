package com.dopple.webview.ui.system

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.provider.Settings
import android.util.AttributeSet
import android.util.Log
import android.view.View

/**
 * Full-screen settings overlay drawn natively.
 * Items: Brightness, Volume, Wi-Fi, and optionally Quit Activity.
 * Navigated with A (next) / B (activate).
 */
@Suppress("TooManyFunctions", "MagicNumber", "TooGenericExceptionCaught", "SwallowedException")
class SettingsOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "SettingsOverlay"
    }

    /** Callback when user selects Quit Activity. */
    var onQuitActivity: (() -> Unit)? = null

    /** Whether to show the Quit Activity item (true when in a game). */
    var showQuitActivity: Boolean = false
        set(value) {
            field = value
            items = buildItems()
            selectedIndex = if (value) items.size - 1 else 0
            invalidate()
        }

    private var selectedIndex = 0

    private var items = buildItems()

    private fun buildItems(): Array<String> {
        return if (showQuitActivity) {
            arrayOf("BRIGHTNESS", "VOLUME", "WI-FI", "QUIT ACTIVITY")
        } else {
            arrayOf("BRIGHTNESS", "VOLUME", "WI-FI")
        }
    }

    private val bgPaint = Paint().apply {
        color = Color.argb(200, 0, 0, 0)
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00FFCC")
        textSize = 32f
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.MONOSPACE
    }
    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00FFCC")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFCC00")
        textSize = 28f
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.MONOSPACE
    }
    private val barBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(80, 0, 255, 204)
        style = Paint.Style.FILL
    }
    private val barFgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00FFCC")
        style = Paint.Style.FILL
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00FFCC")
        textSize = 40f
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.MONOSPACE
        isFakeBoldText = true
    }

    fun navigatePrev() {
        selectedIndex = (selectedIndex - 1 + items.size) % items.size
        invalidate()
    }

    fun navigateNext() {
        selectedIndex = (selectedIndex + 1) % items.size
        invalidate()
    }

    fun activateItem() {
        when (selectedIndex) {
            0 -> adjustBrightness()
            1 -> adjustVolume()
            2 -> toggleWifi()
            3 -> onQuitActivity?.invoke()
        }
        invalidate()
    }

    private fun adjustBrightness() {
        try {
            val current = Settings.System.getInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                128
            )
            val next = (current + 51).coerceAtMost(255)
            val wrapped = if (next > 250) 25 else next
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                wrapped
            )
            Log.d(TAG, "Brightness: $current -> $wrapped")
        } catch (e: Exception) {
            Log.w(TAG, "Cannot adjust brightness: ${e.message}")
        }
    }

    private fun adjustVolume() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            AudioManager.ADJUST_RAISE,
            0
        )
        Log.d(TAG, "Volume raised")
    }

    private fun toggleWifi() {
        try {
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiManager.isWifiEnabled = !wifiManager.isWifiEnabled
            Log.d(TAG, "Wi-Fi toggled to ${wifiManager.isWifiEnabled}")
        } catch (e: Exception) {
            Log.w(TAG, "Cannot toggle Wi-Fi: ${e.message}")
        }
    }

    private fun getBrightnessPercent(): Int {
        return try {
            val v = Settings.System.getInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                128
            )
            (v * 100) / 255
        } catch (e: Exception) { 50 }
    }

    private fun getVolumePercent(): Int {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val current = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return if (max > 0) (current * 100) / max else 50
    }

    private fun isWifiEnabled(): Boolean {
        return try {
            val wm = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            wm.isWifiEnabled
        } catch (e: Exception) { false }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f

        // Background
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // Title
        canvas.drawText("SETTINGS", cx, 120f, titlePaint)

        // Items
        val startY = 220f
        val itemHeight = 120f

        for (i in 0 until items.size) {
            val y = startY + i * itemHeight
            val rect = RectF(60f, y, w - 60f, y + itemHeight - 20f)

            // Selection highlight
            if (i == selectedIndex) {
                canvas.drawRoundRect(rect, 8f, 8f, selectedPaint)
            }

            // Label
            canvas.drawText(items[i], cx, y + 35f, textPaint)

            // Value / bar
            when (i) {
                0 -> {
                    val pct = getBrightnessPercent()
                    drawBar(canvas, rect, pct)
                    canvas.drawText("$pct%", cx, y + 75f, valuePaint)
                }
                1 -> {
                    val pct = getVolumePercent()
                    drawBar(canvas, rect, pct)
                    canvas.drawText("$pct%", cx, y + 75f, valuePaint)
                }
                2 -> {
                    val on = isWifiEnabled()
                    canvas.drawText(if (on) "ON" else "OFF", cx, y + 75f, valuePaint)
                }
                3 -> {
                    canvas.drawText("[B] TO QUIT", cx, y + 75f, valuePaint)
                }
            }
        }

        // Footer
        canvas.drawText("A:NEXT  B:SELECT  C-HOLD:CLOSE", cx, h - 60f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(150, 0, 255, 204)
                textSize = 22f
                textAlign = Paint.Align.CENTER
                typeface = android.graphics.Typeface.MONOSPACE
            }
        )
    }

    private fun drawBar(canvas: Canvas, rect: RectF, percent: Int) {
        val barY = rect.top + 55f
        val barH = 12f
        val barLeft = rect.left + 40f
        val barRight = rect.right - 40f
        val barWidth = barRight - barLeft

        canvas.drawRoundRect(barLeft, barY, barRight, barY + barH, 4f, 4f, barBgPaint)
        val fillRight = barLeft + barWidth * (percent / 100f)
        canvas.drawRoundRect(barLeft, barY, fillRight, barY + barH, 4f, 4f, barFgPaint)
    }
}
