package software.zeasy.momir.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
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
 * Each value is drawn as the generic mana symbol it is: a pale stone disc with
 * the number struck into it, lit from the upper left. A player reading `{5}` on
 * this dial and `{5}` on a card in their hand is reading the same object, and
 * that is worth more than any amount of styling on a bare numeral.
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
            // The numerals, once. onDraw runs continuously through every drag
            // and fling, and formatting three of them per frame is three objects
            // a second thousand times over for text that never changes.
            labels = Array(value.size) { value[it].toString() }
            textHeights = IntArray(value.size)
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

    private var labels: Array<String> = emptyArray()

    /**
     * Cap height per label at full size, measured on first use. `getTextBounds`
     * walks the glyphs, and it was being asked the same question about the same
     * three numerals every frame.
     */
    private var textHeights = IntArray(0)

    private val scroller = OverScroller(context)
    private var velocityTracker: VelocityTracker? = null
    private var lastTouchY = 0f
    private var isDragging = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFlingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private val maxFlingVelocity = ViewConfiguration.get(context).scaledMaximumFlingVelocity

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val bounds = Rect()
    private val ornament = Path()

    /** The number struck into the disc, not the disc itself. */
    var textColor: Int = Color.parseColor("#1A1712")
    var indicatorColor: Int = Color.parseColor("#C9A85C")

    /** Disc radius at full size. Leaves a gap between neighbours on the drum. */
    private val discRadius = itemHeight * 0.40f

    /**
     * Built once and reused for every disc: the canvas is translated and scaled
     * around each item, so one shader in the item's own coordinate space serves
     * all five visible discs at all five sizes.
     */
    private val discShader: RadialGradient = RadialGradient(
        -discRadius * 0.35f, -discRadius * 0.4f, discRadius * 1.9f,
        intArrayOf(STONE_LIT, STONE_MID, STONE_DEEP),
        floatArrayOf(0f, 0.5f, 1f),
        Shader.TileMode.CLAMP,
    )

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

        drawSlot(canvas, centreY)

        for (index in values.indices) {
            val flatY = index * itemHeight - scrollOffset
            val normalised = flatY / radius
            if (abs(normalised) > 1.05f) continue

            // One angle drives position, size and opacity. That is the trick.
            val angle = normalised * (Math.PI / 2) * DRUM_ARC
            val drawY = centreY + (sin(angle) * radius * 0.94f).toFloat()
            val scale = cos(angle).toFloat().coerceAtLeast(0f)
            if (scale <= 0.02f) continue

            val alpha = (255 * Math.pow(scale.toDouble(), 1.4).toFloat()).toInt().coerceIn(0, 255)
            drawSymbol(canvas, index, width / 2f, drawY, scale, alpha)
        }
    }

    /**
     * The selection frame: a gold hairline running in from each edge, stopped by
     * a small diamond just short of the disc. Card frames break their rules this
     * way rather than running a line straight through, and it leaves the centre
     * of the dial to the symbol.
     */
    private fun drawSlot(canvas: Canvas, centreY: Float) {
        indicatorPaint.style = Paint.Style.STROKE
        indicatorPaint.color = indicatorColor
        indicatorPaint.alpha = 130
        indicatorPaint.strokeWidth = dp(1f)

        val gap = discRadius + dp(18f)
        val edge = dp(22f)
        canvas.drawLine(edge, centreY, width / 2f - gap, centreY, indicatorPaint)
        canvas.drawLine(width / 2f + gap, centreY, width - edge, centreY, indicatorPaint)

        indicatorPaint.style = Paint.Style.FILL
        indicatorPaint.alpha = 190
        drawDiamond(canvas, width / 2f - gap + dp(5f), centreY, dp(3.5f))
        drawDiamond(canvas, width / 2f + gap - dp(5f), centreY, dp(3.5f))
        indicatorPaint.alpha = 255
    }

    private fun drawDiamond(canvas: Canvas, x: Float, y: Float, size: Float) {
        ornament.rewind()
        ornament.moveTo(x, y - size)
        ornament.lineTo(x + size, y)
        ornament.lineTo(x, y + size)
        ornament.lineTo(x - size, y)
        ornament.close()
        canvas.drawPath(ornament, indicatorPaint)
    }

    /**
     * One generic mana symbol, drawn at the origin and placed by the canvas.
     *
     * Scaling through the canvas rather than through the geometry is what lets
     * the disc gradient be built once: the shader lives in the item's own
     * coordinate space and comes along for the ride.
     */
    private fun drawSymbol(canvas: Canvas, index: Int, x: Float, y: Float, scale: Float, alpha: Int) {
        val save = canvas.save()
        canvas.translate(x, y)
        canvas.scale(scale, scale)

        // The centred symbol carries a gold setting; the rest are plain stone, so
        // the eye lands on the one that is actually selected.
        val centred = scale > 0.97f
        if (centred) {
            indicatorPaint.style = Paint.Style.STROKE
            indicatorPaint.color = indicatorColor
            indicatorPaint.strokeWidth = dp(1.5f)
            indicatorPaint.alpha = 220
            canvas.drawCircle(0f, 0f, discRadius + dp(6f), indicatorPaint)
            indicatorPaint.alpha = 60
            indicatorPaint.strokeWidth = dp(1f)
            canvas.drawCircle(0f, 0f, discRadius + dp(11f), indicatorPaint)
            indicatorPaint.alpha = 255
        }

        discPaint.shader = discShader
        discPaint.alpha = alpha
        canvas.drawCircle(0f, 0f, discRadius, discPaint)
        discPaint.shader = null

        // Struck edge: dark on the outside of the disc, the way the printed
        // symbol carries a shadow around its rim.
        discPaint.style = Paint.Style.STROKE
        discPaint.strokeWidth = dp(1.5f)
        discPaint.color = STONE_EDGE
        discPaint.alpha = alpha
        canvas.drawCircle(0f, 0f, discRadius - dp(0.75f), discPaint)
        discPaint.style = Paint.Style.FILL
        discPaint.alpha = 255

        val label = labels[index]
        textPaint.textSize = discRadius * if (label.length > 1) 0.95f else 1.15f
        var capHeight = textHeights[index]
        if (capHeight == 0) {
            textPaint.getTextBounds(label, 0, label.length, bounds)
            capHeight = bounds.height()
            textHeights[index] = capHeight
        }
        textPaint.color = textColor
        textPaint.alpha = alpha
        canvas.drawText(label, 0f, capHeight / 2f, textPaint)
        textPaint.alpha = 255

        canvas.restoreToCount(save)
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
        /**
         * How many discs the dial asks for, and gets.
         *
         * This is only the desired height - the layout constrains the wheel
         * between the chip row and the count line and hands back whatever is
         * left, and everything the drum does is computed from the measured
         * height. It said five, which on a 720 x 1440 V2 was never once true:
         * the space is enough for three, which is what the arc has always been
         * drawn for. A constant that describes a layout that does not exist is
         * worse than no constant.
         */
        private const val VISIBLE_ITEMS = 3

        /**
         * The generic mana disc. Magic prints it as a warm off-white stone with a
         * shadow along the bottom edge; these three stops are that, tuned a shade
         * darker so it does not glare on a near-black screen.
         */
        private val STONE_LIT = Color.parseColor("#E8E1D2")
        private val STONE_MID = Color.parseColor("#C6BDA9")
        private val STONE_DEEP = Color.parseColor("#8E8375")
        private val STONE_EDGE = Color.parseColor("#4A4238")

        /** How far round the cylinder the visible items wrap. 1.0 would flatten the rim items to nothing. */
        private const val DRUM_ARC = 0.76

        private const val SNAP_DURATION_MS = 250
    }
}
