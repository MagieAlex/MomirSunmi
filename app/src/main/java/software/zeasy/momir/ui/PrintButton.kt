package software.zeasy.momir.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator

/**
 * The big fat print button.
 *
 * A round target roughly 200 dp across, because this gets pressed with a thumb,
 * repeatedly, while holding cards in the other hand. It owns three states:
 * idle, pressed (dips and darkens) and busy (a sweeping arc while the printer
 * chews). Busy also blocks input, which is the cheapest way to stop an
 * impatient double-tap turning into two tokens.
 */
class PrintButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    var label: String = "PRINT"
        set(value) {
            field = value
            invalidate()
        }

    var accentColor: Int = Color.parseColor("#3D7BFF")
        set(value) {
            field = value
            invalidate()
        }

    var onPress: (() -> Unit)? = null

    /** Long press renders a slip without printing it, for checking a layout. */
    var onLongPress: (() -> Unit)? = null

    private var longPressFired = false
    private val longPressRunnable = Runnable {
        longPressFired = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        onLongPress?.invoke()
    }

    var isBusy: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (value) startSweep() else stopSweep()
            invalidate()
        }

    private var pressScale = 1f
    private var sweepStart = 0f
    private var sweepAnimator: ValueAnimator? = null
    private var pressAnimator: ValueAnimator? = null

    private var flashProgress = 0f
    private var flashColor = Color.WHITE
    private var flashAnimator: ValueAnimator? = null

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        color = Color.WHITE
    }
    private val arcRect = RectF()
    private val bounds = Rect()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desired = dp(200f).toInt()
        val size = minOf(resolveSize(desired, widthMeasureSpec), resolveSize(desired, heightMeasureSpec))
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        val centreX = width / 2f
        val centreY = height / 2f
        val outer = minOf(width, height) / 2f - dp(4f)
        val radius = outer * pressScale

        fillPaint.color = if (isEnabled) {
            if (isPressed) darken(accentColor, 0.82f) else accentColor
        } else {
            Color.parseColor("#2A2E36")
        }
        canvas.drawCircle(centreX, centreY, radius * 0.88f, fillPaint)

        ringPaint.strokeWidth = dp(3f)
        ringPaint.color = if (isEnabled) withAlpha(accentColor, 90) else Color.parseColor("#33383F")
        canvas.drawCircle(centreX, centreY, radius, ringPaint)

        if (flashProgress > 0f && flashProgress < 1f) {
            val fade = 1f - flashProgress

            // Wash of colour over the face, dying away.
            fillPaint.color = flashColor
            fillPaint.alpha = (fade * 0.75f * 255).toInt().coerceIn(0, 255)
            canvas.drawCircle(centreX, centreY, radius * 0.88f, fillPaint)
            fillPaint.alpha = 255

            // Ring pushing outward past the button's own edge.
            ringPaint.color = flashColor
            ringPaint.alpha = (fade * fade * 255).toInt().coerceIn(0, 255)
            ringPaint.strokeWidth = dp(3f) + dp(5f) * fade
            canvas.drawCircle(centreX, centreY, radius * (0.88f + 0.42f * flashProgress), ringPaint)
            ringPaint.alpha = 255
        }

        if (isBusy) {
            arcRect.set(centreX - radius, centreY - radius, centreX + radius, centreY + radius)
            ringPaint.strokeWidth = dp(4f)
            ringPaint.color = Color.WHITE
            canvas.drawArc(arcRect, sweepStart, 70f, false, ringPaint)
        } else {
            textPaint.textSize = radius * 0.26f
            textPaint.color = if (isEnabled) Color.WHITE else Color.parseColor("#6B7280")
            textPaint.getTextBounds(label, 0, label.length, bounds)
            canvas.drawText(label, centreX, centreY + bounds.height() / 2f, textPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || isBusy) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isPressed = true
                longPressFired = false
                animatePress(0.94f)
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                if (onLongPress != null) {
                    postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                val inside = event.x in 0f..width.toFloat() && event.y in 0f..height.toFloat()
                isPressed = false
                animatePress(1f)
                if (inside && !longPressFired) {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onPress?.invoke()
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                isPressed = false
                animatePress(1f)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /**
     * Lights the button up in [color] and throws off an expanding ring.
     *
     * Fired twice per print: once neutrally the instant it is pressed, so the
     * press feels answered before anything has been rolled, and again in the
     * card's own colours once there is a card to have colours.
     */
    fun flash(color: Int) {
        flashColor = color
        flashAnimator?.cancel()
        flashAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 620
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                flashProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun animatePress(target: Float) {
        pressAnimator?.cancel()
        pressAnimator = ValueAnimator.ofFloat(pressScale, target).apply {
            duration = 110
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                pressScale = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun startSweep() {
        sweepAnimator?.cancel()
        sweepAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                sweepStart = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopSweep() {
        sweepAnimator?.cancel()
        sweepAnimator = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopSweep()
        pressAnimator?.cancel()
        flashAnimator?.cancel()
    }

    private fun darken(color: Int, factor: Float) = Color.rgb(
        (Color.red(color) * factor).toInt().coerceIn(0, 255),
        (Color.green(color) * factor).toInt().coerceIn(0, 255),
        (Color.blue(color) * factor).toInt().coerceIn(0, 255),
    )

    private fun withAlpha(color: Int, alpha: Int) =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    private fun dp(value: Float) = value * resources.displayMetrics.density
}
