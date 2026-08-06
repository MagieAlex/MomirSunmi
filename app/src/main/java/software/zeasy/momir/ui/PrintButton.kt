package software.zeasy.momir.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The big fat print button, drawn as a set seal: a struck brass rim with the
 * five colours of Magic set into it as gems, over a stone face lit from above.
 *
 * The pentagon is not decoration. WUBRG runs clockwise from the top - white,
 * blue, black, red, green - which is the colour wheel every Magic player has in
 * their head, and putting the roll under it says what this button rolls without
 * a word of explanation. When a card comes back, its own colours are the ones
 * that light.
 *
 * ## Cost
 *
 * Everything is circles, arcs and two cached gradients. No blur filter (it would
 * force a software layer), no bitmap, nothing allocated in [onDraw]. The press
 * scale is applied as a canvas transform rather than by recomputing geometry, so
 * the cached shaders stay valid at every scale.
 *
 * A round target roughly 200 dp across, because this gets pressed with a thumb,
 * repeatedly, while holding cards in the other hand. Busy blocks input, which is
 * the cheapest way to stop an impatient double-tap turning into two slips.
 */
class PrintButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {


    /** The rim's resting colour. Card colours override it for the length of a flash. */
    var accentColor: Int = GOLD
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

    /** 0 while idle, 1 at the end of a flash. Drives the whole colour-identity state. */
    private var flashProgress = 0f
    private var flashColors: IntArray = intArrayOf(Color.WHITE)
    private var flashAnimator: ValueAnimator? = null

    /**
     * How strongly the seal is still wearing the last creature's colours: 1 from
     * the moment it is rolled, 0 once the result is retired. The rim and the gems
     * keep the identity long after the flash has died, so a glance at the button
     * says what just came out of the printer.
     */
    private var chargeAmount = 0f
    private var chargeAnimator: ValueAnimator? = null

    /**
     * A slow breath on the rim while nothing is happening, so the button looks
     * live rather than switched off. Quantised to roughly 25 redraws a second of
     * one 200 dp view - far below anything the dial or the glow costs.
     */
    private var idlePulse = 0f
    private var idleAnimator: ValueAnimator? = null

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pipPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sigilPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arcRect = RectF()

    /** The planeswalker symbol and the fissures it lights, both in view coordinates. */
    private val sigilPath = Path()
    private val crackPath = Path()
    private val sigilBounds = RectF()

    /** Cached in [onSizeChanged]; all are in view coordinates at press scale 1. */
    private var faceShader: RadialGradient? = null
    private var rimShader: LinearGradient? = null
    private var identityShader: SweepGradient? = null

    /** Gold light behind the symbol at rest, and the card's colours over it. */
    private var sigilRestShader: RadialGradient? = null
    private var sigilChargeShader: LinearGradient? = null
    private var bloomShader: RadialGradient? = null

    private var radius = 0f
    private var centreX = 0f
    private var centreY = 0f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desired = dp(200f).toInt()
        val size = minOf(resolveSize(desired, widthMeasureSpec), resolveSize(desired, heightMeasureSpec))
        setMeasuredDimension(size, size)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centreX = w / 2f
        centreY = h / 2f
        radius = minOf(w, h) / 2f - dp(3f)

        // Light from above: the highlight sits a third of a radius up, which is
        // what turns a flat disc into something struck out of metal.
        faceShader = RadialGradient(
            centreX, centreY - radius * 0.34f, radius * 1.1f,
            intArrayOf(FACE_LIT, FACE_MID, FACE_DEEP),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )

        // The rim is the only genuinely metallic thing on the screen, and metal is
        // not a colour - it is a colour that changes across the surface. One
        // diagonal ramp, bright at the top left where the light is and dark at the
        // bottom right, does the whole job.
        rimShader = LinearGradient(
            centreX - radius, centreY - radius, centreX + radius, centreY + radius,
            intArrayOf(GOLD_BRIGHT, GOLD, GOLD_DEEP, GOLD),
            floatArrayOf(0f, 0.3f, 0.68f, 1f),
            Shader.TileMode.CLAMP,
        )
        identityShader = null
        sigilChargeShader = null
        buildSigil()
    }

    /**
     * The planeswalker symbol, and the fissures running out of it.
     *
     * Everything is built once here in view coordinates: the press dip is a
     * canvas transform, so the paths and their shaders stay valid at every
     * scale, and [onDraw] never touches a `Path` builder.
     */
    private fun buildSigil() {
        val height = radius * SIGIL_HEIGHT
        val scale = height / 100f
        val left = centreX - 50f * scale
        // Nudged up a hair: the symbol's visual mass sits low, and centring its
        // bounding box makes it look like it is sliding off the bottom.
        val top = centreY - height / 2f - radius * 0.02f

        sigilPath.rewind()
        var first = true
        for (segment in SIGIL) {
            val x0 = left + segment[0] * scale
            val y0 = top + segment[1] * scale
            if (first) {
                sigilPath.moveTo(x0, y0)
                first = false
            }
            sigilPath.cubicTo(
                left + segment[2] * scale, top + segment[3] * scale,
                left + segment[4] * scale, top + segment[5] * scale,
                left + segment[6] * scale, top + segment[7] * scale,
            )
        }
        sigilPath.close()
        sigilPath.computeBounds(sigilBounds, true)

        // Fissures: tapered slivers running from under the symbol out towards the
        // groove, as though the stone had cracked around whatever is lit inside.
        crackPath.rewind()
        for (crack in CRACKS) {
            val angle = crack[0] * DEG_TO_RAD
            val nx = -sin(angle)
            val ny = cos(angle)
            val innerR = radius * FACE * crack[1]
            val outerR = radius * FACE * crack[2]
            val half = radius * crack[3]
            val ix = centreX + cos(angle) * innerR
            val iy = centreY + sin(angle) * innerR
            crackPath.moveTo(ix + nx * half, iy + ny * half)
            crackPath.lineTo(centreX + cos(angle) * outerR, centreY + sin(angle) * outerR)
            crackPath.lineTo(ix - nx * half, iy - ny * half)
            crackPath.close()
        }

        sigilRestShader = RadialGradient(
            centreX, sigilBounds.centerY(), height * 0.62f,
            intArrayOf(LIGHT_CORE, LIGHT_MID, LIGHT_EDGE),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP,
        )
        bloomShader = RadialGradient(
            centreX, sigilBounds.centerY(), height * 0.95f,
            intArrayOf(withAlpha(LIGHT_MID, 90), withAlpha(LIGHT_MID, 0)),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (radius <= 0f) return

        val save = canvas.save()
        // Press dip is a transform, not new geometry, so the cached shaders hold.
        canvas.scale(pressScale, pressScale, centreX, centreY)

        drawFace(canvas)
        drawSigil(canvas)
        drawRim(canvas)
        drawPentagon(canvas)
        drawFlash(canvas)

        canvas.restoreToCount(save)
    }

    // ------------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------------

    private fun drawFace(canvas: Canvas) {
        // Shadow ring at the very edge, so the whole seal sits *in* the screen
        // rather than on top of it.
        ringPaint.shader = null
        ringPaint.color = GROOVE
        ringPaint.strokeWidth = dp(6f)
        canvas.drawCircle(centreX, centreY, radius * 0.99f, ringPaint)

        fillPaint.shader = if (isEnabled) faceShader else null
        fillPaint.color = if (isEnabled) Color.WHITE else DISABLED_FACE
        canvas.drawCircle(centreX, centreY, radius * FACE, fillPaint)
        fillPaint.shader = null

        // Engraved groove between face and rim, with a lit hairline on its upper
        // edge - a bevel is two lines, one dark and one bright, and without the
        // bright one the face reads as a hole.
        ringPaint.color = GROOVE
        ringPaint.strokeWidth = dp(4f)
        canvas.drawCircle(centreX, centreY, radius * FACE, ringPaint)

        ringPaint.color = withAlpha(BEVEL, if (isEnabled) 150 else 40)
        ringPaint.strokeWidth = dp(1f)
        arcRect.set(
            centreX - radius * FACE * 0.985f, centreY - radius * FACE * 0.985f,
            centreX + radius * FACE * 0.985f, centreY + radius * FACE * 0.985f,
        )
        canvas.drawArc(arcRect, 190f, 160f, false, ringPaint)

        // Inner keyline, the way a card frame repeats its border a few millimetres in.
        ringPaint.color = withAlpha(accentColor, if (isEnabled) 54 else 18)
        ringPaint.strokeWidth = dp(1f)
        canvas.drawCircle(centreX, centreY, radius * FACE * 0.8f, ringPaint)
    }

    private fun drawRim(canvas: Canvas) {
        val breathing = 0.78f + 0.22f * idlePulse
        val fade = 1f - flashProgress

        // Brass underneath, always.
        ringPaint.strokeWidth = dp(5f)
        ringPaint.shader = if (isEnabled) rimShader else null
        ringPaint.color = if (isEnabled) accentColor else DISABLED_RIM
        ringPaint.alpha = ((if (isEnabled) breathing else 0.4f) * 255).toInt().coerceIn(0, 255)
        canvas.drawCircle(centreX, centreY, radius * RIM, ringPaint)
        ringPaint.shader = null
        ringPaint.alpha = 255

        // The card's colours over it, for as long as the seal stays charged. One
        // colour paints flat, several run round the ring as a sweep, so a Golgari
        // creature comes back black *and* green rather than the app picking a
        // favourite.
        if (chargeAmount > 0f && isEnabled) {
            ringPaint.shader = identityShader
            if (identityShader == null) ringPaint.color = flashColors[0]
            ringPaint.alpha = (chargeAmount * breathing * 255).toInt().coerceIn(0, 255)
            canvas.drawCircle(centreX, centreY, radius * RIM, ringPaint)
            ringPaint.shader = null
            ringPaint.alpha = 255
        }

        // The flash itself is that same ring again, brighter and fatter, dying
        // back into the charge underneath.
        if (flashProgress > 0f && flashProgress < 1f && isEnabled) {
            ringPaint.shader = identityShader
            if (identityShader == null) ringPaint.color = flashColors[0]
            ringPaint.alpha = (fade * 255).toInt().coerceIn(0, 255)
            ringPaint.strokeWidth = dp(5f) + dp(4f) * fade
            canvas.drawCircle(centreX, centreY, radius * RIM, ringPaint)
            ringPaint.shader = null
            ringPaint.alpha = 255
        }
    }

    /**
     * The five gems, WUBRG clockwise from the top. Off state is a dark stone with
     * a hint of its hue; a card lights the ones it actually contains.
     */
    private fun drawPentagon(canvas: Canvas) {
        val pipRadius = radius * 0.058f
        val fade = 1f - flashProgress
        val flashing = flashProgress > 0f && flashProgress < 1f && isEnabled
        val lit = isEnabled && (flashing || chargeAmount > 0f)

        for (index in PENTAGON.indices) {
            val angle = (-90f + index * 72f) * DEG_TO_RAD
            val x = centreX + cos(angle) * radius * RIM
            val y = centreY + sin(angle) * radius * RIM
            val colour = PENTAGON[index]
            val isMine = lit && flashColors.any { it == colour }

            // Socket, so a gem reads as set into the rim rather than dropped on it.
            pipPaint.color = SOCKET
            canvas.drawCircle(x, y, pipRadius * 1.7f, pipPaint)

            pipPaint.color = colour
            pipPaint.alpha = when {
                !isEnabled -> 40
                isMine && flashing -> 255
                isMine -> (115 + 95 * chargeAmount).toInt()
                // Dark enough to stay quiet, bright enough that the five gems are
                // still legibly white, blue, black, red and green at rest.
                else -> 115
            }
            canvas.drawCircle(x, y, pipRadius, pipPaint)

            if (isMine && flashing) {
                // A halo on the card's own colours, dying with the flash.
                pipPaint.alpha = (fade * 90).toInt().coerceIn(0, 255)
                canvas.drawCircle(x, y, pipRadius * (1.4f + 1.5f * flashProgress), pipPaint)
            }
            pipPaint.alpha = 255
        }
    }

    private fun drawFlash(canvas: Canvas) {
        if (flashProgress <= 0f || flashProgress >= 1f) return
        val fade = 1f - flashProgress

        // Wash over the face, dying away.
        fillPaint.shader = null
        fillPaint.color = flashColors[0]
        fillPaint.alpha = (fade * 0.42f * 255).toInt().coerceIn(0, 255)
        canvas.drawCircle(centreX, centreY, radius * FACE, fillPaint)
        fillPaint.alpha = 255

        // A ring travelling out from the middle to the groove - the roll leaving
        // the button. Kept inside the face so it never clips against the bounds.
        ringPaint.shader = identityShader
        if (identityShader == null) ringPaint.color = flashColors[0]
        ringPaint.alpha = (fade * fade * 220).toInt().coerceIn(0, 255)
        ringPaint.strokeWidth = dp(2f) + dp(4f) * fade
        canvas.drawCircle(centreX, centreY, radius * FACE * (0.15f + 0.85f * flashProgress), ringPaint)
        ringPaint.shader = null
        ringPaint.alpha = 255
    }

    /**
     * The symbol, set into the stone with light coming up through it.
     *
     * The word PRINT used to be here. The planeswalker symbol says the same
     * thing to anyone who has ever held a Magic card, and says it in a language
     * the rest of the screen is already speaking.
     *
     * The glow is built the way the screen overlay builds its halo: the same
     * path stroked several times over, widening and fading. `BlurMaskFilter`
     * would be the obvious tool and would silently drop this view into a
     * software layer - a full-screen CPU re-raster every frame on a chip from
     * 2014. Stacked translucent strokes read as light and cost the GPU nothing.
     */
    private fun drawSigil(canvas: Canvas) {
        // How hard the stone is lit: a slow breath at rest, brighter and in the
        // card's own colours while the seal is charged.
        val breath = 0.62f + 0.38f * idlePulse
        val lit = if (isEnabled) breath else 0.22f

        sigilPaint.style = Paint.Style.FILL
        sigilPaint.shader = null

        // Fissures first, under everything, so the symbol sits on top of its own light.
        sigilPaint.color = if (chargeAmount > 0f) flashColors[0] else LIGHT_MID
        sigilPaint.alpha = (lit * (0.20f + 0.28f * chargeAmount) * 255).toInt().coerceIn(0, 255)
        canvas.drawPath(crackPath, sigilPaint)

        if (isEnabled) {
            sigilPaint.shader = bloomShader
            sigilPaint.alpha = (lit * 255).toInt().coerceIn(0, 255)
            canvas.drawCircle(centreX, sigilBounds.centerY(), radius * FACE, sigilPaint)
            sigilPaint.shader = null
            sigilPaint.alpha = 255
        }

        // The recess. A dark line all the way round is what makes the symbol read
        // as cut into the stone rather than laid on top of it.
        sigilPaint.style = Paint.Style.STROKE
        sigilPaint.color = GROOVE
        sigilPaint.strokeWidth = dp(3.5f)
        canvas.drawPath(sigilPath, sigilPaint)

        // Light escaping the recess.
        if (isEnabled) {
            sigilPaint.color = if (chargeAmount > 0f) flashColors[0] else LIGHT_MID
            for (layer in 0 until GLOW_LAYERS) {
                val t = layer / GLOW_LAYERS.toFloat()
                sigilPaint.strokeWidth = dp(2f) + dp(11f) * t
                val fade = (1f - t).let { it * it }
                sigilPaint.alpha = (lit * fade * 0.55f * 255).toInt().coerceIn(0, 255)
                canvas.drawPath(sigilPath, sigilPaint)
            }
            sigilPaint.alpha = 255
        }

        // The symbol itself.
        sigilPaint.style = Paint.Style.FILL
        if (isEnabled) {
            sigilPaint.shader = sigilRestShader
            sigilPaint.alpha = (0.55f + 0.45f * idlePulse).let { (it * 255).toInt() }.coerceIn(0, 255)
            canvas.drawPath(sigilPath, sigilPaint)
            sigilPaint.shader = null

            // A card's colours wash over the gold for as long as the charge lasts.
            if (chargeAmount > 0f) {
                sigilPaint.shader = sigilChargeShader
                if (sigilChargeShader == null) sigilPaint.color = flashColors[0]
                sigilPaint.alpha = (chargeAmount * 235).toInt().coerceIn(0, 255)
                canvas.drawPath(sigilPath, sigilPaint)
                sigilPaint.shader = null
            }
            sigilPaint.alpha = 255
        } else {
            sigilPaint.color = DISABLED_SIGIL
            canvas.drawPath(sigilPath, sigilPaint)
        }

        if (isBusy) {
            // The seal charging: an arc running round the groove rather than a
            // spinner in the middle, so the symbol keeps the centre.
            val r = radius * FACE * 0.94f
            arcRect.set(centreX - r, centreY - r, centreX + r, centreY + r)
            ringPaint.shader = null
            ringPaint.strokeWidth = dp(3f)
            ringPaint.color = GOLD_BRIGHT
            canvas.drawArc(arcRect, sweepStart, 66f, false, ringPaint)
        }
    }

    // ------------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------------

    private fun isOnSeal(x: Float, y: Float): Boolean {
        val dx = x - centreX
        val dy = y - centreY
        return dx * dx + dy * dy <= radius * radius
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false

        // Busy still eats the touch, and dips a little without flashing. Silence
        // is how a broken button behaves; this has to read as "not yet".
        if (isBusy) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN && isOnSeal(event.x, event.y)) {
                animatePress(0.985f)
                postDelayed({ animatePress(1f) }, 90)
                return true
            }
            return false
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // The seal is round and the view is square: the four corners are
                // a quarter of the rectangle and lie entirely outside the thing
                // you can see. A grip adjustment must not print a card.
                if (!isOnSeal(event.x, event.y)) return false
                isPressed = true
                longPressFired = false
                animatePress(0.945f)
                if (onLongPress != null) {
                    postDelayed(longPressRunnable, LONG_PRESS_MS)
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                val inside = isOnSeal(event.x, event.y)
                isPressed = false
                animatePress(1f)
                if (inside && !longPressFired) {
                    // One tap, one haptic. A struck seal makes a single sound;
                    // the press dip has already answered the finger going down.
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

    // ------------------------------------------------------------------------
    // Animation
    // ------------------------------------------------------------------------

    /**
     * Lights the button in one colour. Kept for the neutral acknowledgement fired
     * the instant the button is pressed, before there is a card to have colours.
     */
    fun flash(color: Int) = flash(listOf(color))

    /**
     * Lights the button in a card's colour identity: the rim takes the colours,
     * the matching gems come up, and a ring runs out across the face.
     */
    fun flash(colors: List<Int>) {
        if (colors.isEmpty()) return
        flashColors = colors.toIntArray()
        identityShader = if (flashColors.size < 2) null else {
            SweepGradient(centreX, centreY, closedRing(flashColors), null)
        }
        // Down the symbol rather than around it: a ring of colours makes sense on
        // a rim, but on a shape this size it would just muddy. Each colour is
        // given twice so it gets a flat band instead of being all transition.
        sigilChargeShader = if (flashColors.size < 2) null else {
            val ramp = IntArray(flashColors.size * 2)
            flashColors.forEachIndexed { index, colour ->
                ramp[index * 2] = colour
                ramp[index * 2 + 1] = colour
            }
            LinearGradient(
                0f, sigilBounds.top, 0f, sigilBounds.bottom, ramp, null, Shader.TileMode.CLAMP,
            )
        }

        chargeAnimator?.cancel()
        chargeAmount = 1f

        flashAnimator?.cancel()
        flashAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 760
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                flashProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /**
     * Lets the rim fall back to brass, over the same beat the result panel takes
     * to fade, so the last roll leaves the screen in one piece rather than two.
     */
    fun discharge() {
        if (chargeAmount <= 0f) return
        chargeAnimator?.cancel()
        chargeAnimator = ValueAnimator.ofFloat(chargeAmount, 0f).apply {
            duration = DISCHARGE_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                chargeAmount = it.animatedValue as Float
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

    /**
     * The idle breath. Only runs while the window is actually in front of the
     * user - a dialog, the scanner or a locked screen stops it - and only
     * redraws when the value has moved enough to be visible.
     */
    private fun startIdle() {
        if (idleAnimator != null) return
        idleAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2600
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val next = it.animatedValue as Float
                if (abs(next - idlePulse) < 0.02f) return@addUpdateListener
                idlePulse = next
                invalidate()
            }
            start()
        }
    }

    private fun stopIdle() {
        idleAnimator?.cancel()
        idleAnimator = null
        // Cancelling mid-breath and restarting from 0 snapped the rim between
        // two visibly different alphas every time a dialog opened or closed.
        // Settle on the bright end instead, which is where a paused breath reads
        // as "waiting" rather than as a glitch.
        if (idlePulse != 1f) {
            idlePulse = 1f
            invalidate()
        }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus && isShown) startIdle() else stopIdle()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE && hasWindowFocus()) startIdle() else stopIdle()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopSweep()
        stopIdle()
        pressAnimator?.cancel()
        flashAnimator?.cancel()
        chargeAnimator?.cancel()
        removeCallbacks(longPressRunnable)
    }

    // ------------------------------------------------------------------------

    /** SweepGradient needs the first colour repeated at the end, or the ring has a seam. */
    private fun closedRing(source: IntArray): IntArray {
        val ring = IntArray(source.size + 1)
        System.arraycopy(source, 0, ring, 0, source.size)
        ring[source.size] = source[0]
        return ring
    }

    private fun withAlpha(color: Int, alpha: Int) =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    private fun dp(value: Float) = value * resources.displayMetrics.density

    companion object {
        /** Face and rim as fractions of the outer radius. */
        private const val FACE = 0.81f
        private const val RIM = 0.94f

        private const val DEG_TO_RAD = (Math.PI / 180).toFloat()

        /**
         * Well past the platform's 500 ms. This gesture writes a preview instead
         * of printing, and somebody pressing a 200 dp seal deliberately, with
         * cards in the other hand, crosses half a second often enough that the
         * default turns "print" into "nothing happened" by accident.
         */
        private const val LONG_PRESS_MS = 1200L

        /**
         * The rim gives up the card's colours over exactly the beat the result
         * panel takes to fade, on the same curve. MainActivity reads this rather
         * than carrying its own copy of the number, which is how the two drifted
         * apart the first time.
         */
        const val DISCHARGE_MS = 460L

        private val GOLD = Color.parseColor("#C9A85C")
        private val GOLD_BRIGHT = Color.parseColor("#F3E4B4")
        private val GOLD_DEEP = Color.parseColor("#6E5C31")

        /**
         * The symbol's height as a fraction of the seal's outer radius. The
         * outline is only half as wide as it is tall, so it can afford to run
         * most of the face's height without crowding the groove.
         */
        private const val SIGIL_HEIGHT = 1.16f

        private const val GLOW_LAYERS = 5

        /** Light in the recess: white-hot at the core, amber where it meets stone. */
        private val LIGHT_CORE = Color.parseColor("#FFF6DC")
        private val LIGHT_MID = Color.parseColor("#F0C766")
        private val LIGHT_EDGE = Color.parseColor("#C08A2A")
        private val DISABLED_SIGIL = Color.parseColor("#22262E")

        /**
         * The planeswalker symbol: five prongs falling away from the centre, over
         * a body that tapers to a point.
         *
         * Traced from the vector original rather than drawn by hand - an earlier
         * attempt to reconstruct it from memory produced a three-pronged shape
         * that was recognisably not the symbol. Each row is one cubic, as
         * `startX, startY, c1x, c1y, c2x, c2y, endX, endY`, normalised into a box
         * 100 tall; the outline is 49.8 wide, so the aspect comes out of the data
         * rather than being imposed here. Straight runs in the original are
         * carried as cubics with their handles on the endpoints, which keeps one
         * segment type through the whole path.
         */
        private val SIGIL: Array<FloatArray> = arrayOf(
            floatArrayOf(74.39f, 38.39f, 73.97f, 27.29f, 73.01f, 23.08f, 72.38f, 23.08f),
            floatArrayOf(72.38f, 23.08f, 71.74f, 23.08f, 71.54f, 29.22f, 70.69f, 34.65f),
            floatArrayOf(70.69f, 34.65f, 69.85f, 40.07f, 68.36f, 46.22f, 68.36f, 46.22f),
            floatArrayOf(68.36f, 46.22f, 68.36f, 46.22f, 64.57f, 44.77f, 64.57f, 44.77f),
            floatArrayOf(64.57f, 44.77f, 64.57f, 44.77f, 63.5f, 36.93f, 63.08f, 27.53f),
            floatArrayOf(63.08f, 27.53f, 62.67f, 18.14f, 62.35f, 9.93f, 61.17f, 9.93f),
            floatArrayOf(61.17f, 9.93f, 60.03f, 9.92f, 59.81f, 17.65f, 59.38f, 27.66f),
            floatArrayOf(59.38f, 27.66f, 58.96f, 37.66f, 57.48f, 43.2f, 57.48f, 43.2f),
            floatArrayOf(57.48f, 43.2f, 57.48f, 43.2f, 54.0f, 42.72f, 54.0f, 42.72f),
            floatArrayOf(54.0f, 42.72f, 54.0f, 42.72f, 52.31f, 34.04f, 51.68f, 7.53f),
            floatArrayOf(51.68f, 7.53f, 51.53f, 1.26f, 50.0f, 0.0f, 50.0f, 0.0f),
            floatArrayOf(50.0f, 0.0f, 50.0f, 0.0f, 48.47f, 1.26f, 48.32f, 7.53f),
            floatArrayOf(48.32f, 7.53f, 47.69f, 34.04f, 45.99f, 42.72f, 45.99f, 42.72f),
            floatArrayOf(45.99f, 42.72f, 45.99f, 42.72f, 42.51f, 43.2f, 42.51f, 43.2f),
            floatArrayOf(42.51f, 43.2f, 42.51f, 43.2f, 41.03f, 37.66f, 40.61f, 27.66f),
            floatArrayOf(40.61f, 27.66f, 40.19f, 17.65f, 39.97f, 9.92f, 38.82f, 9.93f),
            floatArrayOf(38.82f, 9.93f, 37.65f, 9.93f, 37.33f, 18.14f, 36.9f, 27.53f),
            floatArrayOf(36.9f, 27.53f, 36.49f, 36.93f, 35.43f, 44.77f, 35.43f, 44.77f),
            floatArrayOf(35.43f, 44.77f, 35.43f, 44.77f, 31.64f, 46.22f, 31.64f, 46.22f),
            floatArrayOf(31.64f, 46.22f, 31.64f, 46.22f, 30.15f, 40.07f, 29.31f, 34.65f),
            floatArrayOf(29.31f, 34.65f, 28.46f, 29.22f, 28.25f, 23.08f, 27.62f, 23.08f),
            floatArrayOf(27.62f, 23.08f, 26.98f, 23.08f, 26.03f, 27.29f, 25.61f, 38.39f),
            floatArrayOf(25.61f, 38.39f, 25.19f, 49.47f, 25.09f, 51.64f, 25.09f, 51.64f),
            floatArrayOf(25.09f, 51.64f, 25.09f, 51.64f, 37.55f, 56.95f, 42.4f, 70.69f),
            floatArrayOf(42.4f, 70.69f, 47.27f, 84.43f, 48.5f, 96.36f, 48.53f, 97.68f),
            floatArrayOf(48.53f, 97.68f, 48.6f, 99.74f, 50.0f, 100.0f, 50.0f, 100.0f),
            floatArrayOf(50.0f, 100.0f, 50.0f, 100.0f, 51.22f, 99.74f, 51.47f, 97.68f),
            floatArrayOf(51.47f, 97.68f, 51.62f, 96.37f, 52.73f, 84.43f, 57.59f, 70.69f),
            floatArrayOf(57.59f, 70.69f, 62.45f, 56.95f, 74.91f, 51.64f, 74.91f, 51.64f),
            floatArrayOf(74.91f, 51.64f, 74.91f, 51.64f, 74.81f, 49.47f, 74.39f, 38.39f),
        )

        /**
         * Fissures, as `angleDegrees, innerRadius, outerRadius, halfWidth`, the
         * radii as fractions of the face and the width of the outer radius.
         * Deliberately irregular: four cracks at tidy intervals would read as a
         * badge, and stone does not crack to a schedule.
         */
        private val CRACKS = arrayOf(
            floatArrayOf(-24f, 0.34f, 0.93f, 0.020f),
            floatArrayOf(38f, 0.40f, 0.88f, 0.016f),
            floatArrayOf(150f, 0.36f, 0.95f, 0.022f),
            floatArrayOf(206f, 0.44f, 0.82f, 0.014f),
            floatArrayOf(272f, 0.30f, 0.72f, 0.012f),
        )

        private val FACE_LIT = Color.parseColor("#2E333C")
        private val FACE_MID = Color.parseColor("#191D24")
        private val FACE_DEEP = Color.parseColor("#0A0C10")
        private val GROOVE = Color.parseColor("#05060A")
        private val SOCKET = Color.parseColor("#0A0B0E")
        private val BEVEL = Color.parseColor("#8C93A3")

        private val DISABLED_FACE = Color.parseColor("#171A1F")
        private val DISABLED_RIM = Color.parseColor("#2E323A")
        private val DISABLED_TEXT = Color.parseColor("#5E5A52")

        /** WUBRG clockwise from the top - the colour wheel, in its canonical order. */
        private val PENTAGON = ManaColors.PENTAGON
    }
}
