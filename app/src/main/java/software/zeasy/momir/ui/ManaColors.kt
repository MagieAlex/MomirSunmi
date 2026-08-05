package software.zeasy.momir.ui

import android.graphics.Color

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

    /** Colourless and artifacts: a cold silver, distinct from all five. */
    private val COLORLESS = Color.parseColor("#B9C2CF")

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

    private const val WUBRG = "WUBRG"
}
