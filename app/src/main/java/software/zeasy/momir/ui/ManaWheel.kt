package software.zeasy.momir.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The mana value dial: a vertical drum, the way an iOS time picker works.
 *
 * Items are laid out on a virtual cylinder. Their distance from the centre maps
 * to an angle, and from that angle come three things at once - vertical
 * position through sin (so items bunch up towards the rim), size through cos,
 * and opacity. That single mapping is what makes it read as a rotating wheel
 * rather than a list that happens to fade.
 *
 * Only mana values that actually exist are offered. Magic has never printed a
 * creature at mana value 14, or anything above 16, and a dial that lets you land
 * on a number that can never produce a token is just a bug you can spin to.
 */
class ManaWheel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    var values: List<Int> = emptyList()
        set(value) {
            field = value
            scrollOffset = scrollOffset.coerceIn(0f, maxOffset)
            // Deliberately silent. Populating the wheel is not the user choosing
            // something, and firing the callback here would report index 0 and
            // clobber the caller's saved selection before it can restore it.
            lastReportedIndex = -1
            invalidate()
        }

    var onSelectionChanged: ((Int) -> Unit)? = null

    private var itemHeight = dp(62f)
    private var scrollOffset = 0f
    private var lastReportedIndex = -1

    private val scroller = OverScroller(context)
    private var velocityTracker: VelocityTracker? = null
    private var lastTouchY = 0f
    private var isDragging = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFlingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private val maxFlingVelocity = ViewConfiguration.get(context).scaledMaximumFlingVelocity

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    private val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val bounds = Rect()

    var textColor: Int = Color.WHITE
    var indicatorColor: Int = Color.parseColor("#7FB2FF")

    private val maxOffset: Float
        get() = ((values.size - 1).coerceAtLeast(0) * itemHeight)

    val selectedIndex: Int
        get() = if (values.isEmpty()) -1
        else (scrollOffset / itemHeight).roundToInt().coerceIn(0, values.size - 1)

    val selectedValue: Int?
        get() = values.getOrNull(selectedIndex)

    fun setSelectedValue(value: Int, animate: Boolean = false) {
        val index = values.indexOf(value)
        if (index < 0) return
        val target = index * itemHeight
        if (animate) {
            scroller.startScroll(0, scrollOffset.toInt(), 0, (target - scrollOffset).toInt(), 300)
            postInvalidateOnAnimation()
        } else {
            scrollOffset = target
            notifySelection(haptic = false)
            invalidate()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = resolveSize(dp(220f).toInt(), widthMeasureSpec)
        val height = resolveSize((itemHeight * VISIBLE_ITEMS).toInt(), heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        if (values.isEmpty()) return

        val centreY = height / 2f
        val radius = height / 2f

        // Selection frame first, so items ride over it.
        indicatorPaint.color = indicatorColor
        val half = itemHeight / 2f
        canvas.drawLine(dp(24f), centreY - half, width - dp(24f), centreY - half, indicatorPaint)
        canvas.drawLine(dp(24f), centreY + half, width - dp(24f), centreY + half, indicatorPaint)

        for (index in values.indices) {
            val flatY = index * itemHeight - scrollOffset
            val normalised = flatY / radius
            if (abs(normalised) > 1.05f) continue

            // One angle drives position, size and opacity. That is the trick.
            val angle = normalised * (Math.PI / 2) * DRUM_ARC
            val drawY = centreY + (sin(angle) * radius * 0.94f).toFloat()
            val scale = cos(angle).toFloat().coerceAtLeast(0f)

            textPaint.textSize = BASE_TEXT_SP * dp(1f) * scale
            textPaint.color = textColor
            textPaint.alpha = (255 * Math.pow(scale.toDouble(), 1.15).toFloat()).toInt().coerceIn(0, 255)

            val label = values[index].toString()
            textPaint.getTextBounds(label, 0, label.length, bounds)
            canvas.drawText(label, width / 2f, drawY + bounds.height() / 2f, textPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (values.isEmpty()) return false

        val tracker = velocityTracker ?: VelocityTracker.obtain().also { velocityTracker = it }
        tracker.addMovement(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                scroller.forceFinished(true)
                lastTouchY = event.y
                isDragging = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val delta = lastTouchY - event.y
                if (!isDragging && abs(delta) > touchSlop) isDragging = true
                if (isDragging) {
                    // Rubber-band past the ends instead of stopping dead.
                    scrollOffset = applyOverscroll(scrollOffset + delta)
                    lastTouchY = event.y
                    notifySelection(haptic = true)
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                tracker.computeCurrentVelocity(1000, maxFlingVelocity.toFloat())
                val velocityY = tracker.yVelocity
                if (isDragging && abs(velocityY) > minFlingVelocity) {
                    scroller.fling(
                        0, scrollOffset.toInt(),
                        0, (-velocityY).toInt(),
                        0, 0,
                        0, maxOffset.toInt(),
                        0, (itemHeight / 2).toInt(),
                    )
                    postInvalidateOnAnimation()
                } else {
                    snapToNearest()
                }
                releaseTracker()
                isDragging = false
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                snapToNearest()
                releaseTracker()
                isDragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollOffset = scroller.currY.toFloat()
            notifySelection(haptic = true)
            invalidate()
            postInvalidateOnAnimation()
        } else if (!isDragging && scrollOffset % itemHeight != 0f) {
            snapToNearest()
        }
    }

    private fun snapToNearest() {
        if (values.isEmpty()) return
        val target = selectedIndex * itemHeight
        val distance = target - scrollOffset
        if (abs(distance) < 0.5f) {
            scrollOffset = target
            invalidate()
            return
        }
        scroller.startScroll(0, scrollOffset.toInt(), 0, distance.toInt(), SNAP_DURATION_MS)
        postInvalidateOnAnimation()
    }

    private fun applyOverscroll(raw: Float): Float {
        val limit = itemHeight * 0.6f
        return when {
            raw < 0f -> (raw / 2.5f).coerceAtLeast(-limit)
            raw > maxOffset -> maxOffset + ((raw - maxOffset) / 2.5f).coerceAtMost(limit)
            else -> raw
        }
    }

    private fun notifySelection(haptic: Boolean) {
        val index = selectedIndex
        if (index < 0 || index == lastReportedIndex) return
        lastReportedIndex = index
        if (haptic) {
            performHapticFeedback(
                HapticFeedbackConstants.VIRTUAL_KEY,
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING,
            )
        }
        values.getOrNull(index)?.let { onSelectionChanged?.invoke(it) }
    }

    private fun releaseTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density

    companion object {
        private const val VISIBLE_ITEMS = 5
        private const val BASE_TEXT_SP = 38f

        /** How far round the cylinder the visible items wrap. 1.0 would flatten the rim items to nothing. */
        private const val DRUM_ARC = 0.76

        private const val SNAP_DURATION_MS = 250
    }
}
