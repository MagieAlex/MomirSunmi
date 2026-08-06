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
 * the GPU handles natively. The travelling band is a stack of thin rects whose
 * alpha follows a bell curve; the halo is four banks of strips, one per screen
 * edge. Both read as soft light and neither costs anything.
 *
 * ## Why the top edge is the bright one
 *
 * The halo used to be fourteen rounded-rect strokes around the whole perimeter.
 * Stacked strokes are brightest where they meet - the corners - so the effect
 * pointed at the four places nothing happens, on a device whose paper comes out
 * of the top. Now the top bank is the deepest and brightest, the sides are held
 * back and inset so they do not pile up in the corners, and the foot is barely
 * there. It costs about a sixth of the fill the strokes did, because a stroke
 * 40 dp wide is drawn all the way around a 720 x 1440 screen and most of that
 * was over the middle of nothing.
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

    /**
     * Both shaders for a multicolour identity, built once per play rather than
     * per frame. Constructing a `SweepGradient` in [onDraw] also constructs a
     * native `SkShader` behind it, and at 1500 ms that was ninety of each per
     * print - on a device with 340 MB free, and in a class whose whole argument
     * is that it allocates nothing while animating.
     */
    private var sideShader: LinearGradient? = null
    private var bandShader: LinearGradient? = null

    private val bandHalfHeight = dp(90f)
    private val bandStrips = 26

    /** How far each bank of light reaches in from its edge. */
    private val topDepth = dp(76f)
    private val sideDepth = dp(40f)
    private val bottomDepth = dp(26f)

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
        buildShaders()

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
        sideShader = null
        bandShader = null
        invalidate()
    }

    /**
     * A single colour paints flat and needs no shader at all; two or more get a
     * ramp along whichever edge is being drawn, so a Golgari creature is black
     * and green everywhere rather than black on one side.
     */
    private fun buildShaders() {
        if (colors.size < 2 || width == 0 || height == 0) {
            sideShader = null
            bandShader = null
            return
        }
        bandShader = LinearGradient(
            0f, 0f, width.toFloat(), 0f, colors, null, Shader.TileMode.CLAMP,
        )
        sideShader = LinearGradient(
            0f, 0f, 0f, height.toFloat(), colors, null, Shader.TileMode.CLAMP,
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Both shaders are in view coordinates, so a resize invalidates them.
        if (progress > 0f) buildShaders()
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

    /**
     * Four banks of light, weighted towards the slot.
     *
     * The sides are inset past the top and bottom banks rather than running the
     * full height: where two banks overlap their alphas compose, and that is
     * precisely how the old version ended up brightest in the corners.
     */
    private fun drawHalo(canvas: Canvas, intensity: Float) {
        if (intensity <= 0f) return
        paint.style = Paint.Style.FILL

        drawBank(canvas, Edge.TOP, intensity, topDepth)
        drawBank(canvas, Edge.LEFT, intensity * SIDE_WEIGHT, sideDepth)
        drawBank(canvas, Edge.RIGHT, intensity * SIDE_WEIGHT, sideDepth)
        drawBank(canvas, Edge.BOTTOM, intensity * FOOT_WEIGHT, bottomDepth)
    }

    private enum class Edge { TOP, BOTTOM, LEFT, RIGHT }

    private fun drawBank(canvas: Canvas, edge: Edge, intensity: Float, depth: Float) {
        if (intensity <= 0f || width == 0 || height == 0) return

        val along = edge == Edge.TOP || edge == Edge.BOTTOM
        paint.shader = if (along) bandShader else sideShader
        if (paint.shader == null) paint.color = colors[0]

        val step = depth / HALO_STRIPS
        val from = if (along) 0f else topDepth
        val to = if (along) width.toFloat() else height - bottomDepth

        for (strip in 0 until HALO_STRIPS) {
            val t = (strip + 0.5f) / HALO_STRIPS
            // Squared falloff: bright against the edge, gone a thumb's width in.
            val fade = (1f - t) * (1f - t)
            val alpha = intensity * fade * MAX_HALO_ALPHA
            if (alpha <= 0.004f) continue
            paint.alpha = (alpha * 255).toInt().coerceIn(0, 255)

            val near = strip * step
            val far = near + step + 1f
            when (edge) {
                Edge.TOP -> borderRect.set(from, near, to, far)
                Edge.BOTTOM -> borderRect.set(from, height - far, to, height - near)
                Edge.LEFT -> borderRect.set(near, from, far, to)
                Edge.RIGHT -> borderRect.set(width - far, from, width - near, to)
            }
            canvas.drawRect(borderRect, paint)
        }
        paint.shader = null
    }

    private fun drawBand(canvas: Canvas) {
        val intensity = bandIntensity()
        if (intensity <= 0f) return

        val centreY = bandCentreY()
        paint.style = Paint.Style.FILL
        paint.shader = bandShader
        if (bandShader == null) paint.color = colors[0]

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

        private const val HALO_STRIPS = 8

        /** The sides acknowledge the print; the foot barely does. */
        private const val SIDE_WEIGHT = 0.5f
        private const val FOOT_WEIGHT = 0.28f

        /**
         * The band is the part that carries the meaning - it is the card moving
         * towards the slot - so it gets to be more assertive than the halo.
         */
        private const val MAX_BAND_ALPHA = 0.46f
    }
}
