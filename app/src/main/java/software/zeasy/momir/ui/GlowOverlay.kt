package software.zeasy.momir.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * The print animation: the screen edge takes on the card's colour identity, and
 * a band of that colour sweeps up the screen and out of the top — which on a
 * Sunmi V2 is exactly where the paper comes out.
 *
 * ## Why there is no blur anywhere in here
 *
 * The obvious way to draw a glow is `BlurMaskFilter`. It is also unsupported by
 * the hardware-accelerated canvas, so using it silently forces the view into a
 * software layer: a full-screen 720x1440 bitmap re-rasterised on the CPU every
 * frame. On a 2014-era armeabi-v7a chip that is a slideshow.
 *
 * Everything here is built from gradients and stacked translucent fills, which
 * the GPU handles natively. The edge halo is fourteen rounded-rect strokes of
 * growing width and falling alpha; the travelling band is a stack of thin rects
 * whose alpha follows a bell curve. Both read as soft light and neither costs
 * anything.
 *
 * The view sits on top of the whole layout but is not clickable, so touches fall
 * through to the dial and the button underneath.
 */
class GlowOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var colors: IntArray = intArrayOf(Color.WHITE)
    private var animator: ValueAnimator? = null

    /** 0 while idle; the whole effect is driven off this one value. */
    private var progress = 0f

    /** Where the band starts, in view coordinates. Set to the button's centre. */
    private var originY = 0f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderRect = RectF()

    private val cornerRadius = dp(2f)
    private val haloLayers = 14
    private val bandHalfHeight = dp(90f)
    private val bandStrips = 26

    val isPlaying: Boolean get() = animator?.isRunning == true

    /**
     * Starts the effect.
     *
     * @param identityColors one or more colours; multiples become a gradient
     *   that runs around the border and across the band.
     * @param fromY where the band begins, normally the print button's centre.
     */
    fun play(identityColors: List<Int>, fromY: Float) {
        if (identityColors.isEmpty()) return
        colors = identityColors.toIntArray()
        originY = fromY

        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DURATION_MS
            interpolator = LinearInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun stop() {
        animator?.cancel()
        animator = null
        progress = 0f
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
    }

    override fun onDraw(canvas: Canvas) {
        if (progress <= 0f) return
        drawHalo(canvas, borderIntensity())
        drawBand(canvas)
    }

    // ------------------------------------------------------------------------
    // Timeline
    // ------------------------------------------------------------------------

    /** Border ramps up fast, holds while the band travels, then fades. */
    private fun borderIntensity(): Float = when {
        progress < BORDER_IN -> progress / BORDER_IN
        progress < BORDER_OUT -> 1f
        else -> 1f - (progress - BORDER_OUT) / (1f - BORDER_OUT)
    }.coerceIn(0f, 1f)

    /**
     * The band's centre, travelling from [originY] to above the top edge.
     *
     * Eased out rather than linear: the paper does not accelerate, but a band
     * that decelerates as it reaches the slot reads as *arriving* somewhere,
     * which is the whole point of pointing it at the printer.
     */
    private fun bandCentreY(): Float {
        val t = ((progress - BAND_START) / (BAND_END - BAND_START)).coerceIn(0f, 1f)
        val eased = 1f - (1f - t) * (1f - t)
        return originY + (-bandHalfHeight - originY) * eased
    }

    private fun bandIntensity(): Float {
        if (progress < BAND_START || progress > BAND_END) return 0f
        val t = (progress - BAND_START) / (BAND_END - BAND_START)
        // Fade in over the first fifth, hold, fade out over the last fifth.
        return when {
            t < 0.2f -> t / 0.2f
            t > 0.8f -> (1f - t) / 0.2f
            else -> 1f
        }.coerceIn(0f, 1f)
    }

    // ------------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------------

    private fun drawHalo(canvas: Canvas, intensity: Float) {
        if (intensity <= 0f) return

        paint.style = Paint.Style.STROKE
        paint.shader = if (colors.size == 1) {
            null
        } else {
            // Sweep, so a two- or five-colour identity runs its colours around
            // the perimeter instead of picking one.
            SweepGradient(width / 2f, height / 2f, closedRing(colors), null)
        }
        if (colors.size == 1) paint.color = colors[0]

        for (layer in 0 until haloLayers) {
            val t = layer / haloLayers.toFloat()
            val strokeWidth = dp(2f) + t * dp(38f)
            // Cubed falloff. Squared still stacked into something that read as a
            // solid frame and covered the header text at the edges; cubing keeps
            // the bright part hugging the very edge and lets it die away fast.
            val fade = 1f - t
            val alpha = intensity * fade * fade * fade * MAX_HALO_ALPHA
            paint.strokeWidth = strokeWidth
            paint.alpha = (alpha * 255).toInt().coerceIn(0, 255)

            val inset = strokeWidth / 2f
            borderRect.set(inset, inset, width - inset, height - inset)
            canvas.drawRoundRect(borderRect, cornerRadius, cornerRadius, paint)
        }
        paint.shader = null
    }

    private fun drawBand(canvas: Canvas) {
        val intensity = bandIntensity()
        if (intensity <= 0f) return

        val centreY = bandCentreY()
        paint.style = Paint.Style.FILL
        paint.shader = if (colors.size == 1) {
            null
        } else {
            LinearGradient(0f, 0f, width.toFloat(), 0f, colors, null, Shader.TileMode.CLAMP)
        }
        if (colors.size == 1) paint.color = colors[0]

        // Thin horizontal strips with a bell-shaped alpha profile. Setting alpha
        // on the paint scales the gradient too, so one shader covers all of them.
        val stripHeight = bandHalfHeight * 2f / bandStrips
        for (strip in 0 until bandStrips) {
            val offset = (strip + 0.5f) / bandStrips * 2f - 1f      // -1 .. 1
            val falloff = 1f - offset * offset                       // bell
            val alpha = intensity * falloff * falloff * MAX_BAND_ALPHA
            if (alpha <= 0.004f) continue

            paint.alpha = (alpha * 255).toInt().coerceIn(0, 255)
            val top = centreY - bandHalfHeight + strip * stripHeight
            canvas.drawRect(0f, top, width.toFloat(), top + stripHeight + 1f, paint)
        }
        paint.shader = null
    }

    /** SweepGradient needs the first colour repeated at the end, or the ring has a seam. */
    private fun closedRing(source: IntArray): IntArray {
        val ring = IntArray(source.size + 1)
        System.arraycopy(source, 0, ring, 0, source.size)
        ring[source.size] = source[0]
        return ring
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density

    companion object {
        private const val DURATION_MS = 1500L

        // Fractions of the timeline.
        private const val BORDER_IN = 0.10f
        private const val BORDER_OUT = 0.65f
        private const val BAND_START = 0.08f
        private const val BAND_END = 0.72f

        /** Kept well below 1: this sits over the UI and has to stay readable. */
        private const val MAX_HALO_ALPHA = 0.62f

        /**
         * The band is the part that carries the meaning - it is the card moving
         * towards the slot - so it gets to be more assertive than the halo.
         */
        private const val MAX_BAND_ALPHA = 0.46f
    }
}
