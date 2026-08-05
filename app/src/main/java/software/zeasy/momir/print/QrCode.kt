package software.zeasy.momir.print

import android.util.Log
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder

/**
 * Thin wrapper over ZXing's encoder.
 *
 * Deliberately not QRCodeWriter: that resamples the symbol to a requested pixel
 * size, which on a 203 dpi head means module edges land mid-dot and the code
 * comes out fuzzy. Taking the raw matrix and painting each module as an exact
 * integer number of dots keeps every edge on a dot boundary, which is what makes
 * a thermal-printed QR scan first time.
 */
object QrCode {

    class Matrix(private val dark: Array<BooleanArray>) {
        val size: Int get() = dark.size
        fun isDark(x: Int, y: Int): Boolean = dark[y][x]
    }

    /**
     * Error correction M (15 %). Thermal slips get thumbed, folded and left in
     * a sleeve; L would be smaller but does not survive a smudge across a
     * timing pattern.
     */
    fun encode(content: String): Matrix? {
        if (content.isBlank()) return null
        return try {
            val hints = mapOf(EncodeHintType.CHARACTER_SET to "UTF-8")
            val code = Encoder.encode(content, ErrorCorrectionLevel.M, hints)
            val matrix = code.matrix ?: return null

            val dark = Array(matrix.height) { y ->
                BooleanArray(matrix.width) { x -> matrix.get(x, y).toInt() == 1 }
            }
            Matrix(dark)
        } catch (e: Exception) {
            Log.e("QrCode", "Cannot encode '$content'", e)
            null
        }
    }
}
