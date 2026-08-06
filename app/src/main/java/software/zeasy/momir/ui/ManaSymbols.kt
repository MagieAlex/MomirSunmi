package software.zeasy.momir.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.text.Spannable
import android.text.SpannableStringBuilder
import androidx.core.content.ContextCompat
import software.zeasy.momir.R

/**
 * Magic's mana symbols, as the game draws them.
 *
 * The drawables are Scryfall's own SVGs converted to vector drawables by
 * tools/mana_symbols.py, which also generates the table below - so a symbol
 * cannot be in one and missing from the other. Rebuild both with:
 *
 * ```
 * python tools/mana_symbols.py
 * ```
 *
 * They are shipped rather than fetched because this app works with no network
 * at all, which is the whole point of it. A slip prints the same costs as
 * letters - thermal paper has no mana font, and 48 mm has no room for one.
 */
object ManaSymbols {

    /** Scryfall's token, without the braces, to the drawable that draws it. */
    private val SYMBOLS: Map<String, Int> = mapOf(
        "0" to R.drawable.mana_0,
        "1" to R.drawable.mana_1,
        "10" to R.drawable.mana_10,
        "11" to R.drawable.mana_11,
        "12" to R.drawable.mana_12,
        "13" to R.drawable.mana_13,
        "14" to R.drawable.mana_14,
        "15" to R.drawable.mana_15,
        "16" to R.drawable.mana_16,
        "2" to R.drawable.mana_2,
        "2/B" to R.drawable.mana_2b,
        "2/G" to R.drawable.mana_2g,
        "2/R" to R.drawable.mana_2r,
        "2/U" to R.drawable.mana_2u,
        "2/W" to R.drawable.mana_2w,
        "20" to R.drawable.mana_20,
        "3" to R.drawable.mana_3,
        "4" to R.drawable.mana_4,
        "5" to R.drawable.mana_5,
        "6" to R.drawable.mana_6,
        "7" to R.drawable.mana_7,
        "8" to R.drawable.mana_8,
        "9" to R.drawable.mana_9,
        "B" to R.drawable.mana_b,
        "B/G" to R.drawable.mana_bg,
        "B/P" to R.drawable.mana_bp,
        "B/R" to R.drawable.mana_br,
        "C" to R.drawable.mana_c,
        "C/B" to R.drawable.mana_cb,
        "C/G" to R.drawable.mana_cg,
        "C/R" to R.drawable.mana_cr,
        "C/U" to R.drawable.mana_cu,
        "C/W" to R.drawable.mana_cw,
        "E" to R.drawable.mana_e,
        "G" to R.drawable.mana_g,
        "G/P" to R.drawable.mana_gp,
        "G/U" to R.drawable.mana_gu,
        "G/U/P" to R.drawable.mana_gup,
        "G/W" to R.drawable.mana_gw,
        "G/W/P" to R.drawable.mana_gwp,
        "H" to R.drawable.mana_h,
        "P" to R.drawable.mana_p,
        "Q" to R.drawable.mana_q,
        "R" to R.drawable.mana_r,
        "R/G" to R.drawable.mana_rg,
        "R/G/P" to R.drawable.mana_rgp,
        "R/P" to R.drawable.mana_rp,
        "R/W" to R.drawable.mana_rw,
        "R/W/P" to R.drawable.mana_rwp,
        "S" to R.drawable.mana_s,
        "T" to R.drawable.mana_t,
        "U" to R.drawable.mana_u,
        "U/B" to R.drawable.mana_ub,
        "U/P" to R.drawable.mana_up,
        "U/R" to R.drawable.mana_ur,
        "W" to R.drawable.mana_w,
        "W/B" to R.drawable.mana_wb,
        "W/P" to R.drawable.mana_wp,
        "W/U" to R.drawable.mana_wu,
        "X" to R.drawable.mana_x,
    )

    /** The `{...}` runs this can draw. */
    private val TOKEN = Regex("\\{([^{}]+)\\}")

    /**
     * Replaces every `{...}` in [text] with its symbol, sized to [sizePx].
     *
     * Spans already on the text - the italics reminder text carries - survive,
     * because the replacement happens in place and backwards, so no earlier
     * offset moves under a span that was set on it.
     *
     * A token with no drawable is left exactly as it was written. Scryfall
     * invents symbols faster than a corpus gets rebuilt, and `{∞}` spelled out
     * is better than a gap.
     */
    fun render(context: Context, text: CharSequence, sizePx: Int): CharSequence {
        val matches = TOKEN.findAll(text).toList()
        if (matches.isEmpty()) return text

        val out = SpannableStringBuilder(text)
        for (match in matches.asReversed()) {
            val drawable = drawable(context, match.groupValues[1], sizePx) ?: continue
            val start = match.range.first
            out.replace(start, match.range.last + 1, PLACEHOLDER)
            out.setSpan(
                CenteredImageSpan(drawable),
                start,
                start + PLACEHOLDER.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        return out
    }

    fun drawable(context: Context, token: String, sizePx: Int): Drawable? {
        val id = SYMBOLS[token.uppercase()] ?: return null
        val drawable = ContextCompat.getDrawable(context, id) ?: return null
        drawable.setBounds(0, 0, sizePx, sizePx)
        return drawable
    }

    /** One character to hang the span on. A space keeps the wrap points honest. */
    private const val PLACEHOLDER = " "
}
