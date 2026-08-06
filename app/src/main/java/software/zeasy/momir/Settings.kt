package software.zeasy.momir

import android.content.Context
import software.zeasy.momir.data.CardCategory
import software.zeasy.momir.print.EscPos
import software.zeasy.momir.print.PrintMode

/**
 * User preferences, backed by SharedPreferences.
 *
 * The paper geometry lives here rather than as constants because the distance
 * from print head to tear bar varies between V2 units and paper rolls, and it is
 * the one number that decides whether a slip fits a sleeve. It is measurable in
 * thirty seconds: print, tear, hold a ruler against it, adjust.
 */
class Settings(context: Context) {

    private val prefs = context.getSharedPreferences("momir", Context.MODE_PRIVATE)

    var printMode: PrintMode
        get() = runCatching { PrintMode.valueOf(prefs.getString(KEY_MODE, null) ?: "") }
            .getOrDefault(PrintMode.QR)
        set(value) = prefs.edit().putString(KEY_MODE, value.name).apply()

    /** What the dial rolls. Momir Basic is creatures, so that is where it starts. */
    var cardCategory: CardCategory
        get() = runCatching { CardCategory.valueOf(prefs.getString(KEY_CATEGORY, null) ?: "") }
            .getOrDefault(CardCategory.CREATURES)
        set(value) = prefs.edit().putString(KEY_CATEGORY, value.name).apply()

    /**
     * Whether the line under the dial counts what the roll can land on.
     *
     * On by default, because "604 creatures at mana value 7" is the one thing
     * on the screen that says the corpus is real. Off for anyone who has read it
     * once and would rather have the dial to itself - it is the same number
     * every time you come back to a mana value.
     */
    var showCounts: Boolean
        get() = prefs.getBoolean(KEY_SHOW_COUNTS, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_COUNTS, value).apply()

    var copies: Int
        get() = prefs.getInt(KEY_COPIES, 1).coerceIn(1, 5)
        set(value) = prefs.edit().putInt(KEY_COPIES, value.coerceIn(1, 5)).apply()

    /**
     * Slip length, and every slip gets exactly this - it is not a ceiling.
     *
     * Uniform slips stack and shuffle like cards; slips that vary with how much
     * rules text a creature happens to have stack like receipts. 88 mm is the
     * long edge of a real Magic card, so at the default a printed slip is the
     * same length as the card it sits next to in a sleeve.
     */
    var slipLengthMm: Float
        get() = prefs.getFloat(KEY_SLIP_LENGTH, DEFAULT_SLIP_LENGTH_MM).coerceIn(50f, 88f)
        set(value) = prefs.edit().putFloat(KEY_SLIP_LENGTH, value.coerceIn(50f, 88f)).apply()

    /**
     * The gap between the print head and the tear bar.
     *
     * This one number is spent twice. Paper that has already travelled past the
     * head when a slip starts can never be printed on, so the gap is the slip's
     * head margin whether anyone asked for one or not; and the same distance has
     * to be fed after the last dot before the finished slip reaches the tear bar.
     */
    var tearGapMm: Float
        get() = prefs.getFloat(KEY_TEAR_GAP, DEFAULT_TEAR_GAP_MM).coerceIn(0f, 30f)
        set(value) = prefs.edit().putFloat(KEY_TEAR_GAP, value.coerceIn(0f, 30f)).apply()

    /**
     * White paper below the last dot.
     *
     * Feed exactly the head-to-tear gap and the slip is torn off flush with the
     * final row: 12 mm of white above the card name and none at all under the
     * rules text, which reads as a printer that ran out of something. Feeding
     * *more* than the gap costs nothing but the layout's own space, and the
     * excess comes out as a foot margin.
     */
    var bottomMarginMm: Float
        get() = prefs.getFloat(KEY_BOTTOM_MARGIN, DEFAULT_BOTTOM_MARGIN_MM).coerceIn(0f, 20f)
        set(value) = prefs.edit().putFloat(KEY_BOTTOM_MARGIN, value.coerceIn(0f, 20f)).apply()

    var lastManaValue: Int
        get() = prefs.getInt(KEY_LAST_MV, 3)
        set(value) = prefs.edit().putInt(KEY_LAST_MV, value).apply()

    /** How many dots the layout occupies, once both margins are paid for. */
    val contentBudgetDots: Int
        get() = (
            EscPos.mmToDots(slipLengthMm) -
                EscPos.mmToDots(tearGapMm) -
                EscPos.mmToDots(bottomMarginMm)
            ).coerceAtLeast(200)

    /** Paper fed after the last dot: the foot margin, then the tear bar. */
    val printFeedDots: Int
        get() = EscPos.mmToDots(bottomMarginMm) + EscPos.mmToDots(tearGapMm)

    /** The head margin the geometry imposes, in dots. Nothing can be drawn in it. */
    val headMarginDots: Int
        get() = EscPos.mmToDots(tearGapMm)

    val bottomMarginDots: Int
        get() = EscPos.mmToDots(bottomMarginMm)

    /**
     * What a slip carrying [contentDots] of layout actually measures once it has
     * been torn off - which is not the height of the raster, because the paper
     * above and below it was never printed on.
     */
    fun printedLengthMm(contentDots: Int): Float =
        contentDots / EscPos.DOTS_PER_MM + tearGapMm + bottomMarginMm

    companion object {
        private const val KEY_MODE = "print_mode"
        private const val KEY_CATEGORY = "card_category"
        private const val KEY_COPIES = "copies"
        private const val KEY_SHOW_COUNTS = "show_counts"
        private const val KEY_SLIP_LENGTH = "max_slip_mm"

        /** Was the whole feed before the foot margin was split out of it. */
        private const val KEY_TEAR_GAP = "tear_feed_mm"
        private const val KEY_BOTTOM_MARGIN = "bottom_margin_mm"
        private const val KEY_LAST_MV = "last_mana_value"

        /** The long edge of a Magic card. */
        const val DEFAULT_SLIP_LENGTH_MM = 88f

        /** Measured on a V2; adjust in settings if your tear comes out short. */
        const val DEFAULT_TEAR_GAP_MM = 12f

        /**
         * Not symmetric with the 12 mm head margin, on purpose. The head margin
         * is what the hardware costs; matching it at the foot would spend 24 mm
         * of an 88 mm card on white paper. 5 mm is enough to read as a margin.
         */
        const val DEFAULT_BOTTOM_MARGIN_MM = 5f
    }
}
