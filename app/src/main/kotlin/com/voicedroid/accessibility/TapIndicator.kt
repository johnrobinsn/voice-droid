package com.voicedroid.accessibility

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator

/**
 * Shows a brief circular ripple at the given screen coordinates via an accessibility overlay.
 * The overlay requires no extra permissions — TYPE_ACCESSIBILITY_OVERLAY is granted
 * implicitly to active AccessibilityServices.
 */
object TapIndicator {

    private const val RADIUS_DP = 24f
    private const val DURATION_MS = 400L
    private val handler = Handler(Looper.getMainLooper())

    fun show(context: Context, x: Float, y: Float) {
        handler.post { showOnMain(context, x, y) }
    }

    private fun showOnMain(context: Context, x: Float, y: Float) {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val density = context.resources.displayMetrics.density
        val sizePx = (RADIUS_DP * 2 * density).toInt()

        val dot = DotView(context, sizePx / 2f)

        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            this.x = (x - sizePx / 2f).toInt()
            this.y = (y - sizePx / 2f).toInt()
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
        }

        try {
            wm.addView(dot, params)
        } catch (_: Throwable) {
            return
        }

        val scaleX = ObjectAnimator.ofFloat(dot, View.SCALE_X, 0.4f, 1.2f)
        val scaleY = ObjectAnimator.ofFloat(dot, View.SCALE_Y, 0.4f, 1.2f)
        val alpha = ObjectAnimator.ofFloat(dot, View.ALPHA, 1f, 0f)

        AnimatorSet().apply {
            playTogether(scaleX, scaleY, alpha)
            duration = DURATION_MS
            interpolator = AccelerateInterpolator()
            start()
        }

        handler.postDelayed({
            try { wm.removeView(dot) } catch (_: Throwable) {}
        }, DURATION_MS + 50)
    }

    private class DotView(context: Context, private val radius: Float) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xCC2196F3.toInt() // Material Blue with slight transparency
            style = Paint.Style.FILL
        }
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 3f * context.resources.displayMetrics.density
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            canvas.drawCircle(cx, cy, radius * 0.6f, paint)
            canvas.drawCircle(cx, cy, radius * 0.8f, ringPaint)
        }
    }
}
