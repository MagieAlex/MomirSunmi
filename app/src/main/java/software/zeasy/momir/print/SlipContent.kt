package software.zeasy.momir.print

import software.zeasy.momir.data.Card
import software.zeasy.momir.data.Token

/**
 * What actually goes on a slip, independent of whether it came from a creature
 * card or a token. Both print through the same layout code; the only visible
 * difference is that a creature carries a mana value badge and a token carries
 * a "TOKEN" kicker instead.
 */
data class SlipContent(
    val title: String,
    /** Mana value, drawn in the ring. Null for tokens, which have no mana value. */
    val badge: String?,
    /** Small line under the title: mana cost, or the word TOKEN. */
    val kicker: String,
    val typeLine: String,
    val powerToughness: String?,
    val rulesText: String,
    val linkUri: String,
    val artOffset: Long?,
    val artLength: Int?,
    val artHeight: Int?,
) {
    val hasArt: Boolean get() = artOffset != null && artLength != null && artHeight != null

    companion object {

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
            // {5} under a badge that already says 5 is noise. Only generic-plus-
            // coloured costs actually tell you something the mana value does not.
            val cost = card.plainManaCost.takeIf { it != badge }.orEmpty()
            return SlipContent(
                title = card.name,
                badge = badge,
                kicker = cost,
                typeLine = card.typeLine,
                powerToughness = card.powerToughness,
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
            kicker = "TOKEN",
            typeLine = token.typeLine,
            powerToughness = token.powerToughness,
            rulesText = token.oracleText,
            linkUri = shortenScryfallUri(token.scryfallUri),
            artOffset = token.artOffset,
            artLength = token.artLength,
            artHeight = token.artHeight,
        )
    }
}
