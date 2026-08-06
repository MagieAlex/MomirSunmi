package software.zeasy.momir.print

import software.zeasy.momir.data.Card
import software.zeasy.momir.data.Token

/**
 * What actually goes on a slip, independent of whether it came from a creature
 * card or a token. Both print through the same layout code; the only visible
 * difference is that a card carries a mana value badge and a token's type line
 * says "Token" in it, which Scryfall's own type line already does.
 */
data class SlipContent(
    val title: String,
    /** Mana value, drawn in the ring. Null for tokens, which have no mana value. */
    val badge: String?,
    /**
     * Type line, with the card's colour in front of it: "Blue Creature - Human
     * Wizard".
     *
     * A thermal slip is monochrome, so nothing on the paper says what colour the
     * card is, and Momir hands you a token *copy* - colour is what decides
     * whether it can be targeted. It goes in the type line because that is where
     * a player already reads it and where it costs no extra rows: it used to be
     * its own 19 px line under the type line, 25 dots that no card could spare.
     */
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
         * Four and five colour cards fall back to letters, because "White / Blue
         * / Black / Red / Green" is thirty-five characters, and any player who
         * has a five-colour creature in front of them can read WUBRG.
         *
         * This is the on-screen form. The slip uses [colorAdjective].
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
         * The same colours as a word you can put in front of a noun: "Blue",
         * "White-Black", "Colorless", "WUBRG".
         *
         * Hyphenated rather than slashed for two and three colours, because
         * "White-Black Creature - Vampire" is the phrase a player says out loud
         * and "White / Black Creature" is a database field.
         */
        fun colorAdjective(symbols: String): String {
            val ordered = WUBRG.filter { symbols.contains(it) }
            return when {
                ordered.isEmpty() -> "Colorless"
                ordered.length <= 3 -> ordered.map { COLOR_NAMES.getValue(it) }.joinToString("-")
                // Four and five colours as letters, unspaced: at that point the
                // adjective is longer than the type it qualifies.
                else -> ordered
            }
        }

        /** "Blue" + "Creature - Bird" -> "Blue Creature - Bird". */
        private fun coloured(symbols: String, typeLine: String): String {
            val adjective = colorAdjective(symbols)
            return if (typeLine.isBlank()) adjective else "$adjective $typeLine"
        }

        /**
         * "https://scryfall.com/card/mh1/57/windreaver" -> ".../card/mh1/57"
         *
         * The name slug is decoration - Scryfall resolves set plus collector
         * number on its own. Dropping it takes a typical card URL from 44 bytes
         * to 32, which is the difference between a version 4 QR (33 modules) and
         * a version 3 one (29). Four modules fewer is 16 dots of slip length in
         * the header, and a smaller hole in the artwork when the code is set into
         * its corner.
         */
        fun shortenScryfallUri(uri: String): String {
            val marker = "/card/"
            val at = uri.indexOf(marker)
            if (at < 0) return uri
            val parts = uri.substring(at + marker.length).split('/')
            if (parts.size < 2) return uri
            return uri.substring(0, at + marker.length) + parts[0] + "/" + parts[1]
        }

        fun of(card: Card) = SlipContent(
            title = card.name,
            badge = card.manaValue.toString(),
            typeLine = coloured(card.colorIdentity, card.typeLine),
            powerToughness = card.powerToughness,
            loyalty = card.loyalty,
            rulesText = card.oracleText,
            linkUri = shortenScryfallUri(card.scryfallUri),
            artOffset = card.artOffset,
            artLength = card.artLength,
            artHeight = card.artHeight,
        )

        fun of(token: Token) = SlipContent(
            title = token.name,
            badge = null,
            // Scryfall's token type lines already start with "Token", so the
            // slip says "Black Token Creature - Zombie" without being told to.
            typeLine = coloured(token.colors, token.typeLine),
            powerToughness = token.powerToughness,
            loyalty = null,
            rulesText = token.oracleText,
            linkUri = shortenScryfallUri(token.scryfallUri),
            artOffset = token.artOffset,
            artLength = token.artLength,
            artHeight = token.artHeight,
        )
    }
}
