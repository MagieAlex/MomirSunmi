package software.zeasy.momir.sync

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import software.zeasy.momir.print.EscPos
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Turns a downloaded JPEG into the same packed 1-bit raster the desktop builder
 * produces, so artwork pulled in by an on-device resync is indistinguishable
 * from artwork that came off the PC pipeline.
 *
 * Keeping the two implementations in step matters more than it looks: if the
 * phone dithered differently, a deck built before a resync and one built after
 * would print at visibly different densities - and the whole point of storing
 * 1-bit rasters is that nothing downstream ever touches them again.
 *
 * They had drifted. This side used to scale bilinear where Pillow uses Lanczos,
 * skip the autocontrast pass entirely, and pivot the contrast lift around a
 * fixed mid-grey where `ImageEnhance.Contrast` pivots around the image's own
 * mean. The first two are the ones you can see: bilinear undersamples a 616 px
 * crop down to 384 and loses the fine detail the dither would otherwise hold,
 * and without autocontrast a flat original never gets its range back.
 *
 * The order below is momirdeck.py's `dither_art`, step for step. Anything
 * changed here has to be changed there.
 */
object Dither {

    /** Matches momirdeck's --max-art-height. Keeps a slip inside a sleeve. */
    const val MAX_ART_HEIGHT = 300

    /** momirdeck's --gamma and --contrast defaults. */
    private const val GAMMA = 0.85
    private const val CONTRAST = 1.25f

    /** ImageOps.autocontrast(cutoff=1): a percent off each end of the histogram. */
    private const val AUTOCONTRAST_CUTOFF = 1

    class Result(val raster: ByteArray, val height: Int)

    fun fromJpeg(bytes: ByteArray): Result? {
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        try {
            return fromBitmap(decoded)
        } finally {
            decoded.recycle()
        }
    }

    fun fromBitmap(source: Bitmap): Result? {
        val width = EscPos.PRINT_WIDTH_DOTS
        if (source.width <= 0 || source.height <= 0) return null

        val targetHeight = max(1, Math.round(width * source.height.toFloat() / source.width))

        // convert("L") first, as Pillow does. Luminance is a linear combination
        // and the resample is linear, so the order is not what matters - doing
        // the resample on one plane instead of three is.
        val pixels = IntArray(source.width * source.height)
        source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        val luma = FloatArray(pixels.size)
        for (i in pixels.indices) luma[i] = luminance(pixels[i])

        val resized = lanczos(luma, source.width, source.height, width, targetHeight)

        // Centre-crop rather than squash, so the pixel grid stays 1:1.
        var height = targetHeight
        val plane: IntArray
        if (height > MAX_ART_HEIGHT) {
            val top = (height - MAX_ART_HEIGHT) / 2
            height = MAX_ART_HEIGHT
            plane = IntArray(width * height)
            System.arraycopy(resized, top * width, plane, 0, width * height)
        } else {
            plane = resized
        }

        autocontrast(plane)
        applyLut(plane, gammaLut())
        contrast(plane)

        val grey = FloatArray(plane.size) { plane[it].toFloat() }
        floydSteinberg(grey, width, height)

        val stride = EscPos.BYTES_PER_ROW
        val out = ByteArray(stride * height)
        for (y in 0 until height) {
            val rowBase = y * stride
            val greyBase = y * width
            for (x in 0 until width) {
                if (grey[greyBase + x] < 128f) {
                    val index = rowBase + (x shr 3)
                    out[index] = (out[index].toInt() or (0x80 shr (x and 7))).toByte()
                }
            }
        }
        return Result(out, height)
    }

    /**
     * Pillow's `convert("L")`, to the bit: ITU-R 601 weights in 16-bit fixed
     * point. Android's own 77/151/28 are the same weights rounded to eighths of
     * a percent, and on a coloured original that is worth a couple of levels
     * either way - which after a 1.25 contrast lift is a pixel that flips.
     */
    private fun luminance(pixel: Int): Float = (
        ((pixel shr 16 and 0xFF) * 19595 +
            (pixel shr 8 and 0xFF) * 38470 +
            (pixel and 0xFF) * 7471 + 0x8000) shr 16
        ).toFloat()

    // ------------------------------------------------------------------------
    // Resampling
    // ------------------------------------------------------------------------

    /**
     * Separable Lanczos-3, with the support widened by the scale factor when
     * shrinking - which is what makes it a resample rather than a point sample,
     * and what Pillow does inside `Image.resize(..., LANCZOS)`.
     *
     * Bilinear reads two source pixels per output pixel. Going from a 616 px
     * art crop to 384 dots that leaves two thirds of the image unread, and on
     * material that is about to be reduced to one bit, detail that was never
     * sampled is detail the dither cannot recover.
     */
    private fun lanczos(
        source: FloatArray,
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
    ): IntArray {
        val horizontal = resampleAxis(source, sourceWidth, sourceHeight, targetWidth, horizontal = true)
        // Pillow resamples an 8-bit image in 8 bits: the horizontal pass is
        // rounded and clipped before the vertical pass reads it. Carrying full
        // floats through instead is more accurate and therefore wrong - it puts
        // the device off the builder by up to eight levels.
        for (i in horizontal.indices) {
            horizontal[i] = horizontal[i].roundToInt().coerceIn(0, 255).toFloat()
        }
        val vertical = resampleAxis(horizontal, targetWidth, sourceHeight, targetHeight, horizontal = false)
        return IntArray(vertical.size) { vertical[it].roundToInt().coerceIn(0, 255) }
    }

    private fun resampleAxis(
        source: FloatArray,
        sourceWidth: Int,
        sourceHeight: Int,
        target: Int,
        horizontal: Boolean,
    ): FloatArray {
        val inSize = if (horizontal) sourceWidth else sourceHeight
        val outWidth = if (horizontal) target else sourceWidth
        val outHeight = if (horizontal) sourceHeight else target
        val out = FloatArray(outWidth * outHeight)

        val scale = inSize.toDouble() / target
        val filterScale = max(1.0, scale)
        val support = SUPPORT * filterScale

        for (index in 0 until target) {
            val center = (index + 0.5) * scale
            val first = max(0, floor(center - support).toInt())
            val last = minOf(inSize - 1, ceil(center + support).toInt())

            var total = 0.0
            val weights = DoubleArray(last - first + 1)
            for (tap in weights.indices) {
                val w = kernel((first + tap + 0.5 - center) / filterScale)
                weights[tap] = w
                total += w
            }
            if (total == 0.0) continue
            for (tap in weights.indices) weights[tap] /= total

            if (horizontal) {
                for (y in 0 until outHeight) {
                    var sum = 0.0
                    val row = y * sourceWidth
                    for (tap in weights.indices) sum += weights[tap] * source[row + first + tap]
                    out[y * outWidth + index] = sum.toFloat()
                }
            } else {
                val row = index * outWidth
                for (x in 0 until outWidth) {
                    var sum = 0.0
                    for (tap in weights.indices) sum += weights[tap] * source[(first + tap) * outWidth + x]
                    out[row + x] = sum.toFloat()
                }
            }
        }
        return out
    }

    private fun kernel(x: Double): Double {
        if (x == 0.0) return 1.0
        if (x <= -SUPPORT || x >= SUPPORT) return 0.0
        val px = PI * x
        return SUPPORT * sin(px) * sin(px / SUPPORT) / (px * px)
    }

    private const val SUPPORT = 3.0

    // ------------------------------------------------------------------------
    // Tone
    // ------------------------------------------------------------------------

    /**
     * ImageOps.autocontrast(cutoff=1): throw away the darkest and lightest one
     * percent, then stretch what is left across the full range.
     *
     * Scryfall art crops are photographs of paintings and most of them use half
     * the range. Without this the gamma and contrast steps are working on a
     * histogram that was never opened up, and the dither turns a misty
     * background into flat white.
     */
    private fun autocontrast(plane: IntArray) {
        val histogram = IntArray(256)
        plane.forEach { histogram[it]++ }

        val cut = plane.size * AUTOCONTRAST_CUTOFF / 100
        trim(histogram, cut, fromDark = true)
        trim(histogram, cut, fromDark = false)

        val lo = histogram.indexOfFirst { it > 0 }
        val hi = histogram.indexOfLast { it > 0 }
        if (lo < 0 || hi <= lo) return

        val scale = 255.0 / (hi - lo)
        val lut = IntArray(256) { ((it * scale) - lo * scale).toInt().coerceIn(0, 255) }
        applyLut(plane, lut)
    }

    private fun trim(histogram: IntArray, cutoff: Int, fromDark: Boolean) {
        var remaining = cutoff
        val range = if (fromDark) 0..255 else 255 downTo 0
        for (bin in range) {
            if (remaining <= 0) break
            if (remaining > histogram[bin]) {
                remaining -= histogram[bin]
                histogram[bin] = 0
            } else {
                histogram[bin] -= remaining
                remaining = 0
            }
        }
    }

    /** The builder's `[min(255, int((i / 255) ** gamma * 255 + 0.5)) ...]`, exactly. */
    private fun gammaLut() = IntArray(256) {
        minOf(255, (Math.pow(it / 255.0, GAMMA) * 255 + 0.5).toInt())
    }

    /**
     * ImageEnhance.Contrast: blend away from a flat image of the *mean*, not
     * from a fixed mid-grey.
     *
     * On a dark painting the mean sits well below 128, and pivoting at 128
     * pushes the whole picture darker while claiming to add contrast - which is
     * how the same artwork came out heavier on the device than off the PC.
     */
    private fun contrast(plane: IntArray) {
        if (CONTRAST == 1f) return
        var sum = 0L
        plane.forEach { sum += it }
        val mean = (sum.toDouble() / plane.size + 0.5).toInt()
        for (i in plane.indices) {
            plane[i] = (mean + CONTRAST * (plane[i] - mean)).toInt().coerceIn(0, 255)
        }
    }

    private fun applyLut(plane: IntArray, lut: IntArray) {
        for (i in plane.indices) plane[i] = lut[plane[i]]
    }

    // ------------------------------------------------------------------------
    // Dither
    // ------------------------------------------------------------------------

    private fun floydSteinberg(grey: FloatArray, width: Int, height: Int) {
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val index = row + x
                val old = grey[index]
                val new = if (old < 128f) 0f else 255f
                grey[index] = new
                val error = old - new

                if (x + 1 < width) grey[index + 1] += error * 7f / 16f
                if (y + 1 < height) {
                    val below = index + width
                    if (x > 0) grey[below - 1] += error * 3f / 16f
                    grey[below] += error * 5f / 16f
                    if (x + 1 < width) grey[below + 1] += error * 1f / 16f
                }
            }
        }
    }
}
