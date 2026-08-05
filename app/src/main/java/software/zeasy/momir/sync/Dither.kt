package software.zeasy.momir.sync

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import software.zeasy.momir.print.EscPos

/**
 * Turns a downloaded JPEG into the same packed 1-bit raster the desktop builder
 * produces, so artwork pulled in by an on-device resync is indistinguishable
 * from artwork that came off the PC pipeline.
 *
 * Keeping the two implementations in step matters more than it looks: if the
 * phone dithered differently, a deck built before a resync and one built after
 * would print at visibly different densities.
 */
object Dither {

    /** Matches momirdeck's --max-art-height. Keeps a slip inside a sleeve. */
    const val MAX_ART_HEIGHT = 300

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

        var height = Math.round(width * source.height.toFloat() / source.width)
        if (height <= 0) return null

        val scaled = Bitmap.createScaledBitmap(source, width, height, true)

        // Centre-crop rather than squash, so the pixel grid stays 1:1.
        val cropTop = if (height > MAX_ART_HEIGHT) (height - MAX_ART_HEIGHT) / 2 else 0
        if (height > MAX_ART_HEIGHT) height = MAX_ART_HEIGHT

        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, cropTop, width, height)
        if (scaled != source) scaled.recycle()

        // Grayscale, with the same contrast lift the builder applies. Thermal
        // paper has no midtones, so a flat original turns into mud without it.
        val grey = FloatArray(width * height)
        for (i in pixels.indices) {
            val p = pixels[i]
            val luminance = ((p shr 16 and 0xFF) * 77 + (p shr 8 and 0xFF) * 151 + (p and 0xFF) * 28) shr 8
            grey[i] = applyContrast(luminance.toFloat())
        }

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

    private fun applyContrast(value: Float): Float {
        // gamma 0.85 then contrast 1.25 around mid-grey, clamped.
        val gamma = Math.pow((value / 255f).toDouble(), 0.85).toFloat() * 255f
        return ((gamma - 128f) * 1.25f + 128f).coerceIn(0f, 255f)
    }

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
