package software.zeasy.momir.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable

/**
 * Magic's five colours, translated into something that can glow on a dark screen.
 *
 * These are not the printed frame colours. A card frame sits on white cardboard
 * and is read by reflected light; a glow sits on a near-black screen and is read
 * as emitted light, so the same hex values come out muddy. Every colour here is
 * pushed toward the saturated, luminous end.
 *
 * Black is the interesting one. There is no such thing as a black glow, so it
 * gets violet — which is how black mana has been rendered in every digital Magic
 * client for twenty years, and reads instantly to anyone who has played one.
 */
object ManaColors {

    private val WHITE = Color.parseColor("#F6E9B4")
    private val BLUE = Color.parseColor("#3B9EE5")
    private val BLACK = Color.parseColor("#9B6BD4")
    private val RED = Color.parseColor("#EF4A3C")
    private val GREEN = Color.parseColor("#3FBF6A")

    /**
     * Colourless and artifacts: a cold, dim pewter.
     *
     * It used to be a bright silver, which on a 4 dp stripe or an 11 dp gem was
     * indistinguishable from white mana at arm's length - and since colourless
     * artifacts are a large slice of the corpus and most of the token list, that
     * was a lie told often. Colourless is the *absence* of colour, so it should
     * sit below all five rather than beside them.
     */
    val COLORLESS = Color.parseColor("#7C8797")

    /**
     * The colour wheel, clockwise from the top: white, blue, black, red, green.
     *
     * This is the order the five symbols sit in on the back of every Magic card,
     * so anything that arranges them in a ring - the print button's rim, for one
     * - has to walk this array rather than picking its own arrangement.
     */
    val PENTAGON = intArrayOf(WHITE, BLUE, BLACK, RED, GREEN)

    private fun of(symbol: Char): Int? = when (symbol) {
        'W' -> WHITE
        'U' -> BLUE
        'B' -> BLACK
        'R' -> RED
        'G' -> GREEN
        else -> null
    }

    /**
     * "GW" -> [green, white]. Empty or unrecognised gives silver.
     *
     * Kept in WUBRG order rather than the order the letters happen to arrive in,
     * because that is the order players read a colour pair in, and a gradient
     * that runs the other way looks subtly wrong to them without their being
     * able to say why.
     */
    fun forIdentity(identity: String): List<Int> {
        val colors = WUBRG.mapNotNull { symbol ->
            if (identity.contains(symbol)) of(symbol) else null
        }
        return colors.ifEmpty { listOf(COLORLESS) }
    }

    /**
     * The colour-identity stripe used on the result panel and on every token row.
     *
     * Each colour is given twice. `GradientDrawable` spaces its stops evenly, so
     * a five-colour identity across a 44 dp bar was nine dp per colour and all of
     * it transition - a rainbow smear rather than an identity. Doubling the stops
     * gives each colour a flat band and confines the blend to the joins, at no
     * cost at all.
     */
    fun stripe(colors: List<Int>, density: Float): GradientDrawable {
        val ramp = IntArray(colors.size * 2)
        colors.forEachIndexed { index, color ->
            ramp[index * 2] = color
            ramp[index * 2 + 1] = color
        }
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, ramp).apply {
            cornerRadius = 2f * density
        }
    }

    private const val WUBRG = "WUBRG"
}
