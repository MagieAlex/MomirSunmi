package software.zeasy.momir.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.text.style.ImageSpan

/**
 * An [ImageSpan] that sits on the middle of the line rather than on its baseline.
 *
 * Mana symbols are discs. A disc aligned to the baseline hangs below the text it
 * belongs to by its whole bottom half, which on a line like "{2}{U}, {T}: Draw a
 * card" reads as three things that fell off the sentence. Centring on the font's
 * own middle puts them where a printed card puts them.
 *
 * The line is also grown to fit the symbol when the symbol is taller than the
 * type, so a row of costs never clips against the line above.
 */
class CenteredImageSpan(private val icon: Drawable) : ImageSpan(icon) {

    /**
     * A hair of white after each symbol.
     *
     * Discs set flush against each other read as one blob at 15sp - the eye has
     * no straight edges to separate them on. A printed card leaves about a tenth
     * of a symbol between them and so does this.
     */
    private val gap = (icon.bounds.width() * GAP_FRACTION).toInt()

    override fun getSize(
        paint: Paint,
        text: CharSequence?,
        start: Int,
        end: Int,
        fontMetrics: Paint.FontMetricsInt?,
    ): Int {
        val bounds = icon.bounds
        if (fontMetrics != null) {
            val metrics = paint.fontMetricsInt
            val middle = metrics.ascent + (metrics.descent - metrics.ascent) / 2
            val half = bounds.height() / 2
            fontMetrics.ascent = minOf(metrics.ascent, middle - half)
            fontMetrics.top = fontMetrics.ascent
            fontMetrics.descent = maxOf(metrics.descent, middle + half)
            fontMetrics.bottom = fontMetrics.descent
        }
        return bounds.width() + gap
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence?,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint,
    ) {
        val metrics = paint.fontMetricsInt
        val middle = y + (metrics.ascent + metrics.descent) / 2f
        canvas.save()
        canvas.translate(x, middle - icon.bounds.height() / 2f)
        icon.draw(canvas)
        canvas.restore()
    }

    private companion object {
        const val GAP_FRACTION = 0.11f
    }
}
