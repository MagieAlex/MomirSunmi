package software.zeasy.momir

import android.content.Context
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

    var copies: Int
        get() = prefs.getInt(KEY_COPIES, 1).coerceIn(1, 5)
        set(value) = prefs.edit().putInt(KEY_COPIES, value.coerceIn(1, 5)).apply()

    /** Total slip length. A real Magic card is 88 mm, so that is the ceiling. */
    var maxSlipMm: Float
        get() = prefs.getFloat(KEY_MAX_SLIP, DEFAULT_MAX_SLIP_MM).coerceIn(50f, 88f)
        set(value) = prefs.edit().putFloat(KEY_MAX_SLIP, value.coerceIn(50f, 88f)).apply()

    /** Paper fed after the last dot so the slip clears the tear bar. */
    var tearFeedMm: Float
        get() = prefs.getFloat(KEY_TEAR_FEED, DEFAULT_TEAR_FEED_MM).coerceIn(0f, 30f)
        set(value) = prefs.edit().putFloat(KEY_TEAR_FEED, value.coerceIn(0f, 30f)).apply()

    var lastManaValue: Int
        get() = prefs.getInt(KEY_LAST_MV, 3)
        set(value) = prefs.edit().putInt(KEY_LAST_MV, value).apply()

    /** How many dots the layout may actually occupy, once the feed is paid for. */
    val contentBudgetDots: Int
        get() = (EscPos.mmToDots(maxSlipMm) - EscPos.mmToDots(tearFeedMm)).coerceAtLeast(200)

    val tearFeedDots: Int
        get() = EscPos.mmToDots(tearFeedMm)

    companion object {
        private const val KEY_MODE = "print_mode"
        private const val KEY_COPIES = "copies"
        private const val KEY_MAX_SLIP = "max_slip_mm"
        private const val KEY_TEAR_FEED = "tear_feed_mm"
        private const val KEY_LAST_MV = "last_mana_value"

        /** The long edge of a Magic card. */
        const val DEFAULT_MAX_SLIP_MM = 88f

        /** Measured on a V2; adjust in settings if your tear comes out short. */
        const val DEFAULT_TEAR_FEED_MM = 12f
    }
}
