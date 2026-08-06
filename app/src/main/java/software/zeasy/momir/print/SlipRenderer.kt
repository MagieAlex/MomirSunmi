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

    /** The real artwork, dithered, with a small QR set into its corner. */
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
 * comfortably inside 63 mm. Length is not. At 8 dots/mm the whole slip is 704
 * dots, and the layout gets what is left after both margins: the 12 mm at the
 * head that the head-to-tear-bar gap imposes, and the 5 mm fed at the foot so
 * the rules text is not flush with the tear. That leaves 568 dots. Nothing here
 * draws a margin - the paper above and below this bitmap was never printed on.
 *
 * When a card does not fit - and Eldrazi with six keywords do not fit - the
 * renderer walks a fixed ladder of increasingly aggressive fallbacks rather than
 * scaling everything down. Scaling would destroy the dithering; dropping
 * reminder text, re-flowing and cropping the art does not. Type never goes below
 * 20 px: under that, thermal bleed closes the counters of a, e, s and 8, and an
 * illegible line is worth less than no line. The last rung ellipsises the rules
 * text, and [Raster] never comes back longer than the budget.
 *
 * ## Where the QR goes
 *
 * The scanner resolves a slip back to its card by reading that QR, and that is
 * what makes "which tokens does this make" work at the table. A slip without one
 * would be a dead end.
 *
 * In QR mode it is the main event and sits in the body. In artwork mode it is
 * set into the picture's bottom-right corner, on its own white plate, and costs
 * **no length at all**. It used to sit in the header beside the name, which was
 * documented as costing "about 2 mm" and actually cost 50 to 78 dots - 6 to
 * 10 mm, an eighth of the slip - which is what drove ordinary cards to the
 * bottom of the ladder, printing 17 px text beside art cropped to half. The
 * corner plate spends about a fifth of the picture's area instead, in the corner
 * where a Magic art crop keeps its background. The header is still the fallback
 * for artwork too short to hold a plate.
 */
class SlipRenderer {

    private val width = EscPos.PRINT_WIDTH_DOTS

    // ---- metrics, all in printer dots -------------------------------------
    private val sideMargin = 10
    private val contentWidth = width - 2 * sideMargin
    private val blockGap = 10
    private val ruleThickness = 3
    private val badgeSize = 54
    private val gap = 12

    /** Between two abilities. Enough to read as a break, not as a paragraph. */
    private val abilityGap = 7

    private val black = Paint().apply { color = Color.BLACK; isAntiAlias = true }
    private val white = Paint().apply { color = Color.WHITE }
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
     * documentation's screenshots produced - without feeding a metre of thermal
     * paper through the printer to see whether a line wrapped correctly.
     */
    fun compose(
        content: SlipContent,
        mode: PrintMode,
        art: ByteArray?,
        artHeight: Int,
        budgetDots: Int,
    ): Bitmap {
        val wantsArt = mode == PrintMode.ARTWORK && art != null && artHeight > 0
        val qrMatrix = if (content.linkUri.isNotBlank()) QrCode.encode(content.linkUri) else null

        // Two headers, because whether the code lands in the header changes how
        // much width the card name has and therefore how tall the header is. The
        // ladder decides between them per rung; building both up front costs two
        // text layouts and keeps the decision honest.
        val headerQrSize = qrMatrix?.let { HEADER_QR_MODULE * (it.size + QR_QUIET_MODULES * 2) } ?: 0
        val headerWithQr = header(content, headerQrSize)
        val headerPlain = header(content, 0)

        val typeRow = typeRow(content)

        val abilities = mapOf(
            true to RulesText.abilities(content.rulesText, keepReminders = true),
            false to RulesText.abilities(content.rulesText, keepReminders = false),
        )

        val plan = choosePlan(
            budget = budgetDots,
            headerWithQr = headerWithQr,
            headerPlain = headerPlain,
            typeRowHeight = typeRow.height,
            abilities = abilities,
            wantsArt = wantsArt,
            artHeight = artHeight,
            qrMatrix = qrMatrix,
        )

        val header = if (plan.qrInHeader) headerWithQr else headerPlain
        val bodyQr = if (!wantsArt) qrMatrix else null
        val bodyHeight = when {
            wantsArt -> plan.artRows
            bodyQr != null -> plan.qrModule * (bodyQr.size + QR_QUIET_MODULES * 2)
            else -> 0
        }

        var fixed = header.height + blockGap + ruleThickness + blockGap + typeRow.height
        if (bodyHeight > 0) fixed += blockGap + ruleThickness + blockGap

        // What is left goes to the rules text, which is also what stops the
        // ellipsised rung from drawing off the bottom of the bitmap.
        val rulesPaint = textPaint(plan.rulesSize)
        val rulesRoom = budgetDots - fixed - bodyHeight - blockGap - ruleThickness - blockGap
        val rulesLayouts = layoutRules(abilities.getValue(plan.keepReminders), rulesPaint, rulesRoom)
        val rulesHeight = if (rulesLayouts.isEmpty()) 0 else {
            blockGap + ruleThickness + blockGap + stackHeight(rulesLayouts)
        }

        // Every slip comes out the same length, so a stack of them behaves like a
        // stack of cards. Whatever a particular card does not need is given to the
        // space around the picture rather than left as a blank tail, which would
        // read as a mistake rather than as layout.
        val slack = (budgetDots - fixed - bodyHeight - rulesHeight).coerceAtLeast(0)
        val bodyPad = if (bodyHeight > 0) slack / 2 else 0
        // With no picture to breathe around, the slack goes above the rules
        // block, which puts the text on the foot margin instead of leaving a
        // hand's width of blank paper under it.
        val rulesLead = if (bodyHeight > 0) 0 else slack

        // ---- draw ----------------------------------------------------------
        val bitmap = Bitmap.createBitmap(width, budgetDots, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        var y = 0

        if (content.badge != null) {
            // An outlined ring, not a filled disc. A solid 54-dot circle dumps a
            // lot of heat into one spot and bleeds on cheap paper.
            drawBadge(canvas, sideMargin, y + (header.height - badgeSize) / 2, content.badge)
        }
        if (plan.qrInHeader && qrMatrix != null) {
            drawQr(canvas, qrMatrix, HEADER_QR_MODULE, width - sideMargin - headerQrSize, y)
        }
        // Centred against the badge rather than hung from the top of the row: a
        // one-line name used to sit 7 dots above the ring's optical centre.
        canvas.save()
        canvas.translate(
            header.titleLeft.toFloat(),
            (y + (header.height - header.name.height) / 2).toFloat(),
        )
        header.name.draw(canvas)
        canvas.restore()

        y += header.height + blockGap
        drawRule(canvas, y)
        y += ruleThickness + blockGap

        drawTypeRow(canvas, typeRow, y)
        y += typeRow.height

        if (bodyHeight > 0) {
            y += blockGap
            drawRule(canvas, y)
            y += ruleThickness + blockGap + bodyPad

            if (wantsArt && art != null) {
                drawArt(canvas, art, artHeight, plan.artRows, y)
                if (plan.qrInArt && qrMatrix != null) {
                    drawCornerQr(canvas, qrMatrix, artTop = y, artRows = plan.artRows)
                }
            } else if (bodyQr != null) {
                val size = plan.qrModule * (bodyQr.size + QR_QUIET_MODULES * 2)
                drawQr(canvas, bodyQr, plan.qrModule, (width - size) / 2, y)
            }
            y += bodyHeight + bodyPad
        }

        if (rulesLayouts.isNotEmpty()) {
            y += rulesLead + blockGap
            drawRule(canvas, y)
            y += ruleThickness + blockGap
            rulesLayouts.forEach { layout ->
                canvas.save()
                canvas.translate(sideMargin.toFloat(), y.toFloat())
                layout.draw(canvas)
                canvas.restore()
                y += layout.height + abilityGap
            }
        }

        return bitmap
    }

    /**
     * The whole torn-off slip, margins included, for looking at rather than
     * printing.
     *
     * [compose] draws the layout and nothing else, because the printer is never
     * asked to burn a margin. That makes its bitmap the wrong thing to check a
     * layout against: it is 568 dots of content where the object in your hand is
     * 704 dots with 12 mm of white above it and 5 mm below.
     */
    fun composePaper(
        content: SlipContent,
        mode: PrintMode,
        art: ByteArray?,
        artHeight: Int,
        budgetDots: Int,
        headMarginDots: Int,
        bottomMarginDots: Int,
    ): Bitmap {
        val layout = compose(content, mode, art, artHeight, budgetDots)
        val paper = Bitmap.createBitmap(
            width,
            headMarginDots + layout.height + bottomMarginDots,
            Bitmap.Config.ARGB_8888,
        )
        Canvas(paper).apply {
            drawColor(Color.WHITE)
            drawBitmap(layout, 0f, headMarginDots.toFloat(), bitmapPaint)
        }
        layout.recycle()
        return paper
    }

    // ------------------------------------------------------------------------
    // Blocks
    // ------------------------------------------------------------------------

    private class Header(val name: StaticLayout, val titleLeft: Int, val height: Int)

    private fun header(content: SlipContent, qrSize: Int): Header {
        val badgeWidth = if (content.badge != null) badgeSize + gap else 0
        val titleLeft = sideMargin + badgeWidth
        val titleWidth = width - sideMargin - titleLeft - (if (qrSize > 0) qrSize + gap else 0)

        // The floor is above the type line's size on purpose: a card name set
        // smaller than the type under it stops being the heading of the slip.
        val name = fitToLines(content.title, textPaint(NAME_SIZE, bold = true), titleWidth, 2, NAME_MIN_SIZE)
        val height = maxOf(if (content.badge != null) badgeSize else 0, name.height, qrSize)
        return Header(name, titleLeft, height)
    }

    /**
     * Type line on the left, power/toughness or loyalty in a box on the right.
     *
     * The box is the point. P/T used to be 23 px text at the end of the type
     * line, where a real card puts it in a box you find without looking; the row
     * already reserves enough height for 32 px in a stroked box, so this is the
     * cheapest legibility on the slip.
     */
    private class TypeRow(
        val type: StaticLayout,
        val corner: String?,
        val cornerLabel: String?,
        val cornerPaint: TextPaint,
        val labelPaint: TextPaint,
        val boxWidth: Int,
        val boxHeight: Int,
        val height: Int,
    )

    private fun typeRow(content: SlipContent): TypeRow {
        val cornerPaint = textPaint(CORNER_SIZE, bold = true)
        val labelPaint = textPaint(14f).apply { letterSpacing = 0.14f }

        // A card has power/toughness or starting loyalty, never both, so they
        // share the corner. The word is what stops a loyalty of 4 being read as
        // the back half of a P/T.
        val corner = content.powerToughness?.takeIf { it.isNotBlank() }
            ?: content.loyalty?.takeIf { it.isNotBlank() }
        val label = if (content.powerToughness.isNullOrBlank() && corner != null) LOYALTY_LABEL else null

        var boxWidth = 0
        var boxHeight = 0
        if (corner != null) {
            val bounds = Rect()
            cornerPaint.getTextBounds(corner, 0, corner.length, bounds)
            // Sized off the glyphs, not the font: digits and a slash have no
            // descender, and a box drawn around the font's full line height
            // would float above its own contents.
            boxWidth = bounds.width() + 2 * BOX_PAD_X + 2 * BOX_STROKE
            boxHeight = bounds.height() + 2 * BOX_PAD_Y + 2 * BOX_STROKE
        }

        val labelWidth = label?.let { labelPaint.measureText(it).toInt() + LOYALTY_GAP } ?: 0
        val reserved = if (corner == null) 0 else boxWidth + labelWidth + gap
        val type = fitToLines(content.typeLine, textPaint(TYPE_SIZE), contentWidth - reserved, 2, 15f)

        return TypeRow(
            type = type,
            corner = corner,
            cornerLabel = label,
            cornerPaint = cornerPaint,
            labelPaint = labelPaint,
            boxWidth = boxWidth,
            boxHeight = boxHeight,
            height = maxOf(type.height, boxHeight),
        )
    }

    private fun drawTypeRow(canvas: Canvas, row: TypeRow, y: Int) {
        canvas.save()
        canvas.translate(sideMargin.toFloat(), y.toFloat())
        row.type.draw(canvas)
        canvas.restore()

        val corner = row.corner ?: return

        // Centred on the type line's first line, not hung off its baseline,
        // which used to leave the P/T riding 3.5 dots low.
        val firstLineMiddle = y + (row.type.getLineTop(0) + row.type.getLineBottom(0)) / 2
        val boxRight = (width - sideMargin).toFloat()
        val boxLeft = boxRight - row.boxWidth
        val boxTop = (firstLineMiddle - row.boxHeight / 2).toFloat()
        val boxBottom = boxTop + row.boxHeight

        val stroke = Paint(black).apply {
            style = Paint.Style.STROKE
            strokeWidth = BOX_STROKE.toFloat()
        }
        canvas.drawRoundRect(
            boxLeft + BOX_STROKE / 2f, boxTop + BOX_STROKE / 2f,
            boxRight - BOX_STROKE / 2f, boxBottom - BOX_STROKE / 2f,
            BOX_RADIUS, BOX_RADIUS, stroke,
        )

        val bounds = Rect()
        row.cornerPaint.getTextBounds(corner, 0, corner.length, bounds)
        canvas.drawText(
            corner,
            boxLeft + (row.boxWidth - bounds.width()) / 2f - bounds.left,
            boxTop + row.boxHeight / 2f + bounds.height() / 2f,
            row.cornerPaint,
        )

        row.cornerLabel?.let { label ->
            val metrics = row.labelPaint.fontMetrics
            canvas.drawText(
                label,
                boxLeft - LOYALTY_GAP - row.labelPaint.measureText(label),
                boxTop + row.boxHeight / 2f - (metrics.ascent + metrics.descent) / 2f,
                row.labelPaint,
            )
        }
    }

    // ------------------------------------------------------------------------
    // Fitting
    // ------------------------------------------------------------------------

    private data class Plan(
        val rulesSize: Float,
        val keepReminders: Boolean,
        val artRows: Int,
        val qrModule: Int,
        val qrInArt: Boolean,
        val qrInHeader: Boolean,
    )

    /** One rung: how big the rules text is, whether it keeps its asides, how much art survives. */
    private data class Rung(val rulesSize: Float, val keepReminders: Boolean, val artScale: Float)

    /**
     * Walks a fixed ladder of compromises, least destructive first, and takes the
     * first rung that fits.
     *
     * The order says what the slip is for. Reminder text goes before type is
     * shrunk, because a slip is read at arm's length across a table and some
     * cards spend four of six lines explaining flying. Type gives ground before
     * the artwork, because dropping the rules text one point is barely visible
     * and cropping the picture is - but it stops at 20 px, where thermal bleed
     * starts closing letters.
     */
    private fun choosePlan(
        budget: Int,
        headerWithQr: Header,
        headerPlain: Header,
        typeRowHeight: Int,
        abilities: Map<Boolean, List<CharSequence>>,
        wantsArt: Boolean,
        artHeight: Int,
        qrMatrix: QrCode.Matrix?,
    ): Plan {
        val plateSize = qrMatrix?.let { CORNER_QR_MODULE * (it.size + QR_QUIET_MODULES * 2) } ?: 0

        fun rungPlan(rung: Rung, force: Boolean): Plan? {
            val artRows = if (wantsArt) (artHeight * rung.artScale).toInt() else 0
            // The picture hosts the code when there is room for the whole plate
            // inside it; otherwise the header takes it back and pays in length.
            val qrInArt = wantsArt && qrMatrix != null && artRows >= plateSize
            val qrInHeader = wantsArt && qrMatrix != null && !qrInArt
            val header = if (qrInHeader) headerWithQr else headerPlain

            var fixed = header.height + blockGap + ruleThickness + blockGap + typeRowHeight
            val rules = abilities.getValue(rung.keepReminders)
            val rulesHeight = naturalRulesHeight(rules, rung.rulesSize)

            val qrModule = if (!wantsArt && qrMatrix != null) {
                // Sized against what is genuinely left over - the fallback used
                // to size it against the budget minus the fixed blocks only, so
                // on exactly the card that had too much text the code grew and
                // starved it further.
                // rulesHeight already carries its own separator; this subtracts
                // the one that will go above the code itself.
                largestQrModule(qrMatrix.size, budget - fixed - rulesHeight - (2 * blockGap + ruleThickness))
            } else 0
            val bodyHeight = if (wantsArt) artRows else qrModule * ((qrMatrix?.size ?: 0) + QR_QUIET_MODULES * 2)
            if (bodyHeight > 0) fixed += blockGap + ruleThickness + blockGap

            if (!force && fixed + bodyHeight + rulesHeight > budget) return null
            return Plan(rung.rulesSize, rung.keepReminders, artRows, qrModule, qrInArt, qrInHeader)
        }

        LADDER.forEach { rung -> rungPlan(rung, force = false)?.let { return it } }
        // Nothing fits. Take the tightest rung; [layoutRules] ellipsises the
        // rules text into whatever vertical space is actually left, and prints
        // nothing at all rather than a line that would fall off the bitmap.
        return rungPlan(LADDER.last(), force = true)!!
    }

    private fun naturalRulesHeight(abilities: List<CharSequence>, size: Float): Int {
        if (abilities.isEmpty()) return 0
        val paint = textPaint(size)
        val layouts = abilities.map { buildLayout(it, paint, contentWidth) }
        return stackHeight(layouts) + blockGap + ruleThickness + blockGap
    }

    /**
     * Fills [available] with as many abilities as fit, ellipsising the one that
     * runs off the end.
     *
     * An ability is only cut where there is room for at least one line of it. A
     * lone ellipsis under a rule is worth less than the rule not being there.
     */
    private fun layoutRules(
        abilities: List<CharSequence>,
        paint: TextPaint,
        available: Int,
    ): List<StaticLayout> {
        if (abilities.isEmpty() || available <= 0) return emptyList()

        val out = ArrayList<StaticLayout>(abilities.size)
        var used = 0
        for (ability in abilities) {
            val lead = if (out.isEmpty()) 0 else abilityGap
            val full = buildLayout(ability, paint, contentWidth)
            if (used + lead + full.height <= available) {
                out += full
                used += lead + full.height
                continue
            }
            val lines = fittingLines(full, available - used - lead)
            if (lines > 0) out += buildLayout(ability, paint, contentWidth, maxLines = lines)
            break
        }
        return out
    }

    private fun fittingLines(layout: StaticLayout, room: Int): Int {
        var lines = 0
        while (lines < layout.lineCount && layout.getLineBottom(lines) <= room) lines++
        return lines
    }

    private fun stackHeight(layouts: List<StaticLayout>): Int =
        layouts.sumOf { it.height } + abilityGap * (layouts.size - 1).coerceAtLeast(0)

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

    /**
     * Sets the code into the artwork's bottom-right corner on a white plate.
     *
     * The plate carries the full four-module quiet zone the spec asks for, so
     * the decoder never has to find the symbol against a dithered background.
     * Flush with two edges rather than floating inside the picture: a corner
     * that has been cut out reads as layout, an island reads as a sticker.
     */
    private fun drawCornerQr(canvas: Canvas, matrix: QrCode.Matrix, artTop: Int, artRows: Int) {
        val plate = CORNER_QR_MODULE * (matrix.size + QR_QUIET_MODULES * 2)
        val left = width - plate
        val top = artTop + artRows - plate
        canvas.drawRect(
            left.toFloat(), top.toFloat(),
            (left + plate).toFloat(), (top + plate).toFloat(),
            white,
        )
        drawQr(canvas, matrix, CORNER_QR_MODULE, left, top)
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
        // Thirty-two characters is a narrow measure, and the default greedy
        // wrap rags badly at it - "Whenever this creature" followed by a line
        // holding one word. Both of these cost layout time and nothing else.
        .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
        .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
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

        private const val NAME_SIZE = 34f

        /** Above [TYPE_SIZE]: the heading of a slip may not end up smaller than its subheading. */
        private const val NAME_MIN_SIZE = 23f
        private const val TYPE_SIZE = 21f

        /** Power/toughness, and loyalty, at the size a card prints them. */
        private const val CORNER_SIZE = 32f
        private const val BOX_PAD_X = 9
        private const val BOX_PAD_Y = 5
        private const val BOX_STROKE = 3
        private const val BOX_RADIUS = 5f

        /** Small caps beside the box, in the corner power/toughness would take. */
        private const val LOYALTY_LABEL = "LOYALTY"
        private const val LOYALTY_GAP = 6

        /**
         * Four modules, which is what the spec asks for. It used to be two, which
         * worked because every code was surrounded by the layout's own white -
         * and then stopped being true the moment one was set into artwork.
         */
        private const val QR_QUIET_MODULES = 4
        private const val QR_MIN_MODULE = 4

        /**
         * A body QR is the only thing on that part of the slip, so it used to
         * grow to 9 dots per module - 1.1 mm, on a code a phone reads from 20 cm
         * away, eating three fifths of the paper. Six is 0.75 mm and scans just
         * as well; the space goes to the rules text instead.
         */
        private const val QR_MAX_MODULE = 6

        /**
         * 4 dots per module is 0.5 mm, the size a QR on a business card uses.
         * 3 would fit more comfortably, but thermal dots bleed into their
         * neighbours and 0.375 mm modules start closing up the gaps that a
         * decoder needs.
         */
        private const val HEADER_QR_MODULE = 4

        /** The plate set into the artwork. Same module size, same reasoning. */
        private const val CORNER_QR_MODULE = 4

        private val LADDER = listOf(
            Rung(24f, keepReminders = true, artScale = 1.00f),
            Rung(24f, keepReminders = true, artScale = 0.92f),
            Rung(24f, keepReminders = false, artScale = 0.92f),
            Rung(24f, keepReminders = false, artScale = 0.82f),
            Rung(22f, keepReminders = false, artScale = 0.82f),
            Rung(22f, keepReminders = false, artScale = 0.70f),
            Rung(20f, keepReminders = false, artScale = 0.70f),
            Rung(20f, keepReminders = false, artScale = 0.58f),
            Rung(20f, keepReminders = false, artScale = 0.50f),
        )
    }
}
