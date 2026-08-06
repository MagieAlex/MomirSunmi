package software.zeasy.momir.print

import software.zeasy.momir.data.Card
import software.zeasy.momir.data.Token

/**
 * What actually goes on a slip, independent of whether it came from a creature
 * card or a token. Both print through the same layout code; the only visible
 * difference is that a creature carries a mana value badge and a token says so
 * in its subline.
 */
data class SlipContent(
    val title: String,
    /** Mana value, drawn in the ring. Null for tokens, which have no mana value. */
    val badge: String?,
    /**
     * Full-width line under the type line: mana cost and colour, or TOKEN and
     * colour. Colour is spelled out because a thermal slip is monochrome - there
     * is nothing on the paper that tells you a Devoid creature is colourless, or
     * that a card with no mana cost at all is red.
     */
    val subline: String,
    val typeLine: String,
    val powerToughness: String?,
    /**
     * Starting loyalty. Printed in the same corner power/toughness takes, since
     * no card ever has both, but under its own label - a planeswalker slip that
     * said "4" where a creature says "3/4" would read as half a P/T.
     */
    val loyalty: String?,
    val rulesText: String,
    val linkUri: String,
    val artOffset: Long?,
    val artLength: Int?,
    val artHeight: Int?,
) {
    val hasArt: Boolean get() = artOffset != null && artLength != null && artHeight != null

    companion object {

        private const val WUBRG = "WUBRG"
        private val COLOR_NAMES = mapOf(
            'W' to "White", 'U' to "Blue", 'B' to "Black", 'R' to "Red", 'G' to "Green",
        )

        /**
         * "GW" -> "White / Green", "" -> "Colorless", "WUBRG" -> "W U B R G".
         *
         * Always WUBRG order, never the order the letters happened to arrive in -
         * that is the order players say a colour pair in.
         *
         * Four and five colour cards fall back to letters. "White / Blue / Black
         * / Red / Green" is thirty-five characters that would push the type line
         * onto a second row, and any player who has a five-colour creature in
         * front of them can read WUBRG.
         */
        fun colorLabel(symbols: String): String {
            val ordered = WUBRG.filter { symbols.contains(it) }
            return when {
                ordered.isEmpty() -> "Colorless"
                ordered.length <= 3 -> ordered.map { COLOR_NAMES.getValue(it) }.joinToString(" / ")
                else -> ordered.toCharArray().joinToString(" ")
            }
        }

        /**
         * "https://scryfall.com/card/mh1/57/windreaver" -> ".../card/mh1/57"
         *
         * The name slug is decoration - Scryfall resolves set plus collector
         * number on its own. Dropping it takes a typical card URL from 44 bytes
         * to 32, which is the difference between a version 4 QR (33 modules) and
         * a version 3 one (29). That is what lets the code sit in an artwork
         * slip's header without squeezing the card name into a column so narrow
         * that "Windreaver" breaks across two lines.
         */
        fun shortenScryfallUri(uri: String): String {
            val marker = "/card/"
            val at = uri.indexOf(marker)
            if (at < 0) return uri
            val parts = uri.substring(at + marker.length).split('/')
            if (parts.size < 2) return uri
            return uri.substring(0, at + marker.length) + parts[0] + "/" + parts[1]
        }

        fun of(card: Card): SlipContent {
            val badge = card.manaValue.toString()
            // {5} next to a badge that already says 5 is noise. Only a cost with
            // colour in it tells you something the mana value does not.
            val cost = card.plainManaCost.takeIf { it != badge }.orEmpty()
            val subline = listOf(cost, colorLabel(card.colorIdentity))
                .filter { it.isNotEmpty() }
                .joinToString(SEPARATOR)

            return SlipContent(
                title = card.name,
                badge = badge,
                subline = subline,
                typeLine = card.typeLine,
                powerToughness = card.powerToughness,
                loyalty = card.loyalty,
                rulesText = card.oracleText,
                linkUri = shortenScryfallUri(card.scryfallUri),
                artOffset = card.artOffset,
                artLength = card.artLength,
                artHeight = card.artHeight,
            )
        }

        fun of(token: Token) = SlipContent(
            title = token.name,
            badge = null,
            subline = "TOKEN" + SEPARATOR + colorLabel(token.colors),
            typeLine = token.typeLine,
            powerToughness = token.powerToughness,
            loyalty = null,
            rulesText = token.oracleText,
            linkUri = shortenScryfallUri(token.scryfallUri),
            artOffset = token.artOffset,
            artLength = token.artLength,
            artHeight = token.artHeight,
        )

        private const val SEPARATOR = "  ·  "
    }
}
