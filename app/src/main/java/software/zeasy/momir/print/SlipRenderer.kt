package software.zeasy.momir.print

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils

enum class PrintMode {
    /** A big QR to the Scryfall page, above the rules text. */
    QR,

    /** The real artwork, dithered, plus a small QR tucked into the header. */
    ARTWORK,
}

/** A finished slip: packed 1 bpp, 384 dots wide, ready for GS v 0. */
class Raster(val bytes: ByteArray, val height: Int) {
    val heightMm: Float get() = height / EscPos.DOTS_PER_MM
}

/**
 * Lays a card or token out on 58 mm thermal paper and hands back printer-ready bits.
 *
 * Every slip carries the same four things - name with mana value, type line with
 * power/toughness, a QR to the Scryfall page, and the full rules text - because
 * a slip that leaves any of them out is not a card you can actually play with.
 * The mode only decides what fills the middle: a large QR, or the artwork.
 *
 * ## The length budget
 *
 * A printed slip has to slide into a normal Magic sleeve, so it may not be
 * longer than a real card: 63 x 88 mm. Width is free - 384 dots is 48 mm,
 * comfortably inside 63 mm. Length is not. At 8 dots/mm the whole slip must fit
 * in 704 dots, and part of that is spent feeding paper past the tear bar, which
 * on a V2 sits about 12 mm above the print head. What is left is the layout's.
 *
 * When a card does not fit - and Eldrazi with six keywords do not fit - the
 * renderer walks a fixed ladder of increasingly aggressive fallbacks rather than
 * scaling everything down. Scaling would destroy the dithering; re-flowing text
 * and cropping the art does not. The last rung ellipsises the rules text, and
 * [Raster] never comes back longer than the budget.
 *
 * ## Why artwork slips still get a QR
 *
 * The scanner resolves a slip back to its card by reading that QR, and that is
 * what makes "which tokens does this make" work at the table. An artwork slip
 * without one would be a dead end. Putting it in the header beside the name
 * costs about 2 mm of length; putting it under the rules text would cost 14 mm
 * and force the artwork to be cropped to pay for it.
 */
class SlipRenderer {

    private val width = EscPos.PRINT_WIDTH_DOTS

    // ---- metrics, all in printer dots -------------------------------------
    private val sideMargin = 10
    private val contentWidth = width - 2 * sideMargin
    private val topPad = 8
    private val bottomPad = 6
    private val blockGap = 10
    private val ruleThickness = 3
    private val badgeSize = 54
    private val gap = 12

    private val black = Paint().apply { color = Color.BLACK; isAntiAlias = true }
    private val bitmapPaint = Paint().apply { isFilterBitmap = false; isDither = false }

    fun render(
        content: SlipContent,
        mode: PrintMode,
        art: ByteArray?,
        artHeight: Int,
        budgetDots: Int,
    ): Raster {
        val bitmap = compose(content, mode, art, artHeight, budgetDots)
        val height = bitmap.height
        val raster = toRaster(bitmap, height)
        bitmap.recycle()
        return Raster(raster, height)
    }

    /**
     * Draws the slip and stops there, so a caller can look at it.
     *
     * Splitting this out of [render] means the layout can be checked - and the
     * README screenshots produced - without feeding a metre of thermal paper
     * through the printer to see whether a line wrapped correctly.
     */
    fun compose(
        content: SlipContent,
        mode: PrintMode,
        art: ByteArray?,
        artHeight: Int,
        budgetDots: Int,
    ): Bitmap {
        val wantsArt = mode == PrintMode.ARTWORK && art != null && artHeight > 0
        val hasLink = content.linkUri.isNotBlank()
        val hasBadge = content.badge != null

        // In artwork mode the QR moves up into the header and shrinks; otherwise
        // it is the main event and sits in the body at whatever size fits.
        val qrMatrix = if (hasLink) QrCode.encode(content.linkUri) else null
        val headerQr = if (wantsArt) qrMatrix else null
        val headerQrSize = headerQr?.let {
            HEADER_QR_MODULE * (it.size + QR_QUIET_MODULES * 2)
        } ?: 0

        val badgeWidth = if (hasBadge) badgeSize + gap else 0
        val titleLeft = sideMargin + badgeWidth
        val titleWidth = width - sideMargin - titleLeft - (if (headerQrSize > 0) headerQrSize + gap else 0)

        val namePaint = textPaint(34f, bold = true)
        val nameLayout = fitToLines(content.title, namePaint, titleWidth, 2, 20f)

        val kickerPaint = textPaint(20f)
        val kickerHeight = if (content.kicker.isNotEmpty()) (kickerPaint.fontSpacing + 2).toInt() else 0

        val typePaint = textPaint(21f)
        val ptPaint = textPaint(23f, bold = true)
        val ptText = content.powerToughness.orEmpty()
        val ptWidth = if (ptText.isEmpty()) 0 else ptPaint.measureText(ptText).toInt() + 8
        val typeLayout = fitToLines(content.typeLine, typePaint, contentWidth - ptWidth, 2, 15f)

        val headerHeight = maxOf(
            if (hasBadge) badgeSize else 0,
            nameLayout.height + kickerHeight,
            headerQrSize,
        )
        val typeHeight = maxOf(typeLayout.height, if (ptText.isEmpty()) 0 else ptPaint.fontSpacing.toInt())

        val bodyQr = if (!wantsArt) qrMatrix else null
        val hasBody = wantsArt || bodyQr != null

        var fixed = topPad + headerHeight + blockGap + ruleThickness + blockGap + typeHeight + bottomPad
        if (hasBody) fixed += blockGap + ruleThickness + blockGap

        val plan = choosePlan(
            budget = budgetDots,
            fixed = fixed,
            rulesText = content.rulesText,
            wantsArt = wantsArt,
            artHeight = artHeight,
            qrSize = bodyQr?.size ?: 0,
        )

        val bodyHeight = when {
            wantsArt -> plan.artRows
            bodyQr != null -> plan.qrModule * (bodyQr.size + QR_QUIET_MODULES * 2)
            else -> 0
        }

        val rulesPaint = textPaint(plan.rulesSize)
        val rulesLayout = if (content.rulesText.isNotBlank()) {
            buildLayout(content.rulesText, rulesPaint, contentWidth, maxLines = plan.rulesMaxLines)
        } else null
        val rulesHeight = rulesLayout?.let { it.height + blockGap + ruleThickness + blockGap } ?: 0

        val total = (fixed + bodyHeight + rulesHeight).coerceAtMost(budgetDots)

        // ---- draw ----------------------------------------------------------
        val bitmap = Bitmap.createBitmap(width, total, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        var y = topPad

        if (hasBadge) {
            // An outlined ring, not a filled disc. A solid 54-dot circle dumps a
            // lot of heat into one spot and bleeds on cheap paper.
            drawBadge(canvas, sideMargin, y + (headerHeight - badgeSize) / 2, content.badge!!)
        }

        if (headerQr != null) {
            drawQr(canvas, headerQr, HEADER_QR_MODULE, width - sideMargin - headerQrSize, y)
        }

        canvas.save()
        canvas.translate(titleLeft.toFloat(), y.toFloat())
        nameLayout.draw(canvas)
        canvas.restore()

        if (content.kicker.isNotEmpty()) {
            // Clip to the title column. Reaper King's cost is "2/W 2/U 2/B 2/R 2/G",
            // which is wide enough to run under the header QR if left unbounded.
            val kicker = TextUtils.ellipsize(
                content.kicker, kickerPaint, titleWidth.toFloat(), TextUtils.TruncateAt.END,
            )
            canvas.drawText(
                kicker, 0, kicker.length,
                titleLeft.toFloat(),
                (y + nameLayout.height + kickerPaint.textSize).toFloat(),
                kickerPaint,
            )
        }

        y += headerHeight + blockGap
        drawRule(canvas, y)
        y += ruleThickness + blockGap

        canvas.save()
        canvas.translate(sideMargin.toFloat(), y.toFloat())
        typeLayout.draw(canvas)
        canvas.restore()
        if (ptText.isNotEmpty()) {
            canvas.drawText(
                ptText,
                width - sideMargin - ptPaint.measureText(ptText),
                (y + ptPaint.textSize).toFloat(),
                ptPaint,
            )
        }
        y += typeHeight

        if (bodyHeight > 0) {
            y += blockGap
            drawRule(canvas, y)
            y += ruleThickness + blockGap

            if (wantsArt && art != null) {
                drawArt(canvas, art, artHeight, plan.artRows, y)
            } else if (bodyQr != null) {
                val size = plan.qrModule * (bodyQr.size + QR_QUIET_MODULES * 2)
                drawQr(canvas, bodyQr, plan.qrModule, (width - size) / 2, y)
            }
            y += bodyHeight
        }

        if (rulesLayout != null) {
            y += blockGap
            drawRule(canvas, y)
            y += ruleThickness + blockGap
            canvas.save()
            canvas.translate(sideMargin.toFloat(), y.toFloat())
            rulesLayout.draw(canvas)
            canvas.restore()
        }

        return bitmap
    }

    // ------------------------------------------------------------------------
    // Fitting
    // ------------------------------------------------------------------------

    private data class Plan(
        val rulesSize: Float,
        val artRows: Int,
        val qrModule: Int,
        val rulesMaxLines: Int,
    )

    /**
     * Walks a fixed ladder of compromises, least destructive first, and takes the
     * first rung that fits. Dropping the rules text one point is barely visible;
     * cropping the artwork is, so text gives ground first.
     */
    private fun choosePlan(
        budget: Int,
        fixed: Int,
        rulesText: String,
        wantsArt: Boolean,
        artHeight: Int,
        qrSize: Int,
    ): Plan {
        val ladder = listOf(
            24f to 1.00f,
            24f to 0.92f,
            22f to 0.92f,
            22f to 0.82f,
            20f to 0.82f,
            20f to 0.70f,
            18f to 0.70f,
            18f to 0.58f,
            17f to 0.50f,
        )

        for ((rulesSize, artScale) in ladder) {
            val artRows = if (wantsArt) (artHeight * artScale).toInt() else 0
            val qrModule = if (!wantsArt && qrSize > 0) largestQrModule(qrSize, budget - fixed) else 0
            val bodyHeight = if (wantsArt) artRows else qrModule * (qrSize + QR_QUIET_MODULES * 2)

            val rulesHeight = if (rulesText.isNotBlank()) {
                buildLayout(rulesText, textPaint(rulesSize), contentWidth).height +
                    blockGap + ruleThickness + blockGap
            } else 0

            if (fixed + bodyHeight + rulesHeight <= budget) {
                return Plan(rulesSize, artRows, qrModule, Int.MAX_VALUE)
            }
        }

        // Nothing on the ladder fits. Take the tightest rung and ellipsise the
        // rules text into whatever vertical space is actually left.
        val rulesSize = 17f
        val artRows = if (wantsArt) (artHeight * 0.50f).toInt() else 0
        val qrModule = if (!wantsArt && qrSize > 0) largestQrModule(qrSize, budget - fixed) else 0
        val bodyHeight = if (wantsArt) artRows else qrModule * (qrSize + QR_QUIET_MODULES * 2)

        val available = budget - fixed - bodyHeight - blockGap - ruleThickness - blockGap
        val lineHeight = textPaint(rulesSize).fontSpacing
        val maxLines = if (lineHeight > 0) (available / lineHeight).toInt() else 0

        return Plan(rulesSize, artRows, qrModule, maxLines.coerceAtLeast(1))
    }

    private fun largestQrModule(matrixSize: Int, availableHeight: Int): Int {
        val totalModules = matrixSize + QR_QUIET_MODULES * 2
        for (module in QR_MAX_MODULE downTo QR_MIN_MODULE) {
            val size = module * totalModules
            if (size <= contentWidth && size <= availableHeight) return module
        }
        return QR_MIN_MODULE
    }

    // ------------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------------

    private fun drawRule(canvas: Canvas, y: Int) {
        canvas.drawRect(
            sideMargin.toFloat(), y.toFloat(),
            (width - sideMargin).toFloat(), (y + ruleThickness).toFloat(),
            black,
        )
    }

    private fun drawBadge(canvas: Canvas, x: Int, y: Int, label: String) {
        val stroke = 4f
        val radius = badgeSize / 2f
        val cx = x + radius
        val cy = y + radius

        val ring = Paint(black).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
        }
        canvas.drawCircle(cx, cy, radius - stroke / 2f, ring)

        val numberPaint = textPaint(if (label.length > 1) 28f else 32f, bold = true)
        val bounds = Rect()
        numberPaint.getTextBounds(label, 0, label.length, bounds)
        canvas.drawText(
            label,
            cx - bounds.width() / 2f - bounds.left,
            cy + bounds.height() / 2f - (bounds.height() + bounds.top),
            numberPaint,
        )
    }

    /**
     * Blits pre-dithered artwork at exactly 1:1. No scaling, no filtering - the
     * whole point of storing 1-bit rasters is that these pixels reach the print
     * head untouched. Cropping takes rows off the top and bottom evenly so the
     * subject stays centred.
     */
    private fun drawArt(canvas: Canvas, art: ByteArray, sourceRows: Int, targetRows: Int, y: Int) {
        val rows = targetRows.coerceAtMost(sourceRows)
        if (rows <= 0) return
        val firstRow = (sourceRows - rows) / 2
        val stride = EscPos.BYTES_PER_ROW

        val pixels = IntArray(width * rows)
        for (row in 0 until rows) {
            val base = (firstRow + row) * stride
            if (base + stride > art.size) break
            val out = row * width
            for (byteIndex in 0 until stride) {
                val packed = art[base + byteIndex].toInt()
                val columnBase = byteIndex * 8
                for (bit in 0 until 8) {
                    val isBurn = (packed shr (7 - bit)) and 1 == 1
                    pixels[out + columnBase + bit] = if (isBurn) Color.BLACK else Color.WHITE
                }
            }
        }

        val artBitmap = Bitmap.createBitmap(pixels, width, rows, Bitmap.Config.ARGB_8888)
        canvas.drawBitmap(artBitmap, 0f, y.toFloat(), bitmapPaint)
        artBitmap.recycle()
    }

    private fun drawQr(canvas: Canvas, matrix: QrCode.Matrix, module: Int, left: Int, top: Int) {
        // Quiet zone is white paper already; only the dark modules need ink.
        for (row in 0 until matrix.size) {
            for (col in 0 until matrix.size) {
                if (!matrix.isDark(col, row)) continue
                val px = left + (col + QR_QUIET_MODULES) * module
                val py = top + (row + QR_QUIET_MODULES) * module
                canvas.drawRect(
                    px.toFloat(), py.toFloat(),
                    (px + module).toFloat(), (py + module).toFloat(),
                    black,
                )
            }
        }
    }

    // ------------------------------------------------------------------------
    // Text helpers
    // ------------------------------------------------------------------------

    private fun textPaint(size: Float, bold: Boolean = false) = TextPaint().apply {
        color = Color.BLACK
        textSize = size
        isAntiAlias = true
        typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun buildLayout(
        text: CharSequence,
        paint: TextPaint,
        width: Int,
        maxLines: Int = Int.MAX_VALUE,
    ): StaticLayout = StaticLayout.Builder
        .obtain(text, 0, text.length, paint, width.coerceAtLeast(1))
        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setLineSpacing(1f, 1.0f)
        .setIncludePad(false)
        .apply {
            if (maxLines != Int.MAX_VALUE) {
                setMaxLines(maxLines)
                setEllipsize(TextUtils.TruncateAt.END)
            }
        }
        .build()

    /**
     * Shrinks type until it fits in [maxLines] *without breaking a word*.
     *
     * Counting lines alone is not enough. StaticLayout will happily split a word
     * mid-character when it cannot fit, so "Windreaver" comes out as "Windrea /
     * ver" on two tidy lines and the line-count check waves it through. Sizing
     * against the longest word first is what actually prevents that.
     *
     * "Asmoranomardicadaistinaculdacar" is 31 unbreakable characters and will
     * still hit [minSize] and get split - but by then there is no alternative.
     */
    private fun fitToLines(
        text: CharSequence,
        paint: TextPaint,
        width: Int,
        maxLines: Int,
        minSize: Float,
    ): StaticLayout {
        val longestWord = text.split(' ', '\n').maxByOrNull { paint.measureText(it) }.orEmpty()
        while (paint.measureText(longestWord) > width && paint.textSize > minSize) {
            paint.textSize -= 1f
        }

        var layout = buildLayout(text, paint, width)
        while (layout.lineCount > maxLines && paint.textSize > minSize) {
            paint.textSize -= 1f
            layout = buildLayout(text, paint, width)
        }
        return if (layout.lineCount > maxLines) buildLayout(text, paint, width, maxLines) else layout
    }

    // ------------------------------------------------------------------------
    // Bitmap -> 1 bpp
    // ------------------------------------------------------------------------

    /**
     * A plain threshold, deliberately.
     *
     * Artwork arrives already dithered to pure black and white, so any threshold
     * reproduces it bit-for-bit. Text and QR modules are the only things drawn
     * with antialiasing, and biasing the cut well above the midpoint keeps thin
     * stems solid instead of letting them dissolve into grey that rounds to
     * white. On paper that is the difference between legible and not.
     */
    private fun toRaster(bitmap: Bitmap, height: Int): ByteArray {
        val stride = EscPos.BYTES_PER_ROW
        val out = ByteArray(stride * height)
        val row = IntArray(width)

        for (y in 0 until height) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1)
            val rowBase = y * stride
            for (x in 0 until width) {
                val pixel = row[x]
                val luminance = ((pixel shr 16 and 0xFF) * 77 +
                    (pixel shr 8 and 0xFF) * 151 +
                    (pixel and 0xFF) * 28) shr 8
                if (luminance < BURN_THRESHOLD) {
                    val index = rowBase + (x shr 3)
                    out[index] = (out[index].toInt() or (0x80 shr (x and 7))).toByte()
                }
            }
        }
        return out
    }

    companion object {
        /** Above the midpoint on purpose - see [toRaster]. */
        private const val BURN_THRESHOLD = 170

        private const val QR_QUIET_MODULES = 2
        private const val QR_MIN_MODULE = 4

        /**
         * Capped at 6 dots (0.75 mm) per module. Larger scans no better and every
         * extra step costs about 4 mm of slip length, which is length the rules
         * text needs more than the QR does.
         */
        private const val QR_MAX_MODULE = 6

        /**
         * 4 dots per module is 0.5 mm, the size a QR on a business card uses.
         * 3 would fit more comfortably, but thermal dots bleed into their
         * neighbours and 0.375 mm modules start closing up the gaps that a
         * decoder needs. The extra ~5 mm is paid for by cropping the artwork,
         * which the length ladder does on its own.
         */
        private const val HEADER_QR_MODULE = 4
    }
}
