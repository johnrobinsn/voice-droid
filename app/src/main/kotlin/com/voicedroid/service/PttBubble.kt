package com.voicedroid.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.min

/**
 * A draggable floating PTT button. Lives across all apps when the service is up.
 * Tap to toggle Talk; hold and drag to reposition. Position persists across runs.
 *
 * Requires SYSTEM_ALERT_WINDOW. Caller must check [Settings.canDrawOverlays] before
 * calling [show] — we silently no-op if the permission is missing.
 */
class PttBubble(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var view: BubbleView? = null
    private var params: WindowManager.LayoutParams? = null

    fun show(onTap: () -> Unit) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { show(onTap) }
            return
        }
        if (view != null) return
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "overlay permission missing — not showing bubble")
            return
        }
        val size = (BUBBLE_SIZE_DP * context.resources.displayMetrics.density).toInt()
        val v = BubbleView(context)
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(KEY_X, 80)
            y = prefs.getInt(KEY_Y, 600)
        }
        v.setOnTouchListener(DragTapListener(lp, onTap))
        try {
            wm.addView(v, lp)
            view = v
            params = lp
            Log.i(TAG, "bubble shown at (${lp.x}, ${lp.y})")
        } catch (e: Throwable) {
            Log.w(TAG, "failed to add bubble: ${e.message}")
        }
    }

    fun setState(listening: Boolean, speaking: Boolean) {
        mainHandler.post {
            view?.apply {
                this.listening = listening
                this.speaking = speaking
                invalidate()
            }
        }
    }

    fun hide() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { hide() }
            return
        }
        view?.let {
            try { wm.removeView(it) } catch (_: Throwable) {}
        }
        view = null
        params = null
    }

    @SuppressLint("ClickableViewAccessibility")
    private inner class DragTapListener(
        private val lp: WindowManager.LayoutParams,
        private val onTap: () -> Unit,
    ) : View.OnTouchListener {
        private var startX = 0
        private var startY = 0
        private var pX = 0f
        private var pY = 0f
        private var moved = false
        private val touchSlopPx = (8 * context.resources.displayMetrics.density)

        override fun onTouch(v: View, ev: MotionEvent): Boolean {
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x; startY = lp.y
                    pX = ev.rawX; pY = ev.rawY
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - pX
                    val dy = ev.rawY - pY
                    if (abs(dx) > touchSlopPx || abs(dy) > touchSlopPx) {
                        moved = true
                        lp.x = (startX + dx).toInt()
                        lp.y = (startY + dy).toInt()
                        try { wm.updateViewLayout(v, lp) } catch (_: Throwable) {}
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) onTap()
                    else prefs.edit().putInt(KEY_X, lp.x).putInt(KEY_Y, lp.y).apply()
                }
            }
            return true
        }
    }

    /**
     * Circular button with two visual layers:
     *  - Core:       gray (idle) or green (listening — mic open). Mic stays "green"
     *                from the moment the user taps through the model's response, so
     *                they can barge in by just talking.
     *  - Inner ring: white, shown whenever listening.
     *  - Outer ring: blue, **blinks** while the model is producing audio (speaking).
     *                Stops once response.done fires.
     */
    private class BubbleView(context: Context) : View(context) {
        var listening: Boolean = false
        var speaking: Boolean = false
            set(value) {
                if (field == value) return
                field = value
                if (value) startPulse() else stopPulse()
            }

        private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val outerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val micPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }

        private var pulseStartMs: Long = 0L
        private val pulseRunnable = object : Runnable {
            override fun run() {
                invalidate()
                if (speaking) postOnAnimation(this)
            }
        }

        private fun startPulse() {
            pulseStartMs = System.currentTimeMillis()
            removeCallbacks(pulseRunnable)
            postOnAnimation(pulseRunnable)
        }

        private fun stopPulse() {
            removeCallbacks(pulseRunnable)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val r = min(w, h) / 2f
            val cx = w / 2f
            val cy = h / 2f

            // Outer blue ring — blinks while the model is speaking. Drawn first so it
            // appears behind/around the main button.
            if (speaking) {
                val elapsed = (System.currentTimeMillis() - pulseStartMs).coerceAtLeast(0L)
                val phase = (elapsed % 900L) / 900f  // 0..1 every 900 ms
                val pulse = (kotlin.math.sin(phase * 2 * Math.PI).toFloat() + 1f) / 2f
                outerRingPaint.color = 0xFF1E88E5.toInt() // blue
                outerRingPaint.alpha = (255 * (0.35f + 0.5f * pulse)).toInt()
                outerRingPaint.strokeWidth = 4f + 4f * pulse
                canvas.drawCircle(cx, cy, r - 2f, outerRingPaint)
            }

            corePaint.color = if (listening) 0xFF388E3C.toInt() // green
                              else 0xCC212121.toInt()           // translucent dark grey
            // shadow
            canvas.drawCircle(cx + 2f, cy + 3f, r - 4f, Paint().apply { color = 0x33000000 })
            canvas.drawCircle(cx, cy, r - 4f, corePaint)
            if (listening) {
                ringPaint.color = Color.WHITE
                ringPaint.strokeWidth = 4f
                canvas.drawCircle(cx, cy, r - 6f, ringPaint)
            }
            // simple mic glyph: capsule + stand
            val mw = r * 0.30f
            val mh = r * 0.55f
            canvas.drawRoundRect(
                cx - mw / 2f, cy - mh / 2f - r * 0.05f,
                cx + mw / 2f, cy + mh / 2f - r * 0.05f,
                mw / 2f, mw / 2f, micPaint,
            )
            val standPaint = Paint(micPaint).apply { strokeWidth = 4f; style = Paint.Style.STROKE }
            canvas.drawLine(cx, cy + mh / 2f - r * 0.05f, cx, cy + r * 0.42f, standPaint)
            canvas.drawLine(cx - r * 0.16f, cy + r * 0.42f, cx + r * 0.16f, cy + r * 0.42f, standPaint)
        }
    }

    companion object {
        private const val TAG = "PttBubble"
        private const val FILE_NAME = "voice_droid_settings"
        private const val KEY_X = "bubble_x"
        private const val KEY_Y = "bubble_y"
        private const val BUBBLE_SIZE_DP = 64f
    }
}
