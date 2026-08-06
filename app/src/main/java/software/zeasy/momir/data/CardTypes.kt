package software.zeasy.momir.data

/**
 * A card's types, as bits in one integer column.
 *
 * A card carries every type its front-face type line names, so an artifact
 * creature is `CREATURE or ARTIFACT`. That is what lets a single indexed
 * comparison answer "give me something that is an artifact" without caring what
 * else the card also happens to be.
 *
 * These values are shared with tools/momirdeck/momirdeck.py, which writes them.
 * Any change here needs the same change there and a rebuild, or the two sides
 * would quietly disagree about what a 3 means.
 */
object CardTypes {

    const val CREATURE = 1
    const val ARTIFACT = 2
    const val ENCHANTMENT = 4
    const val PLANESWALKER = 8
    const val LAND = 16
    const val BATTLE = 32
    const val INSTANT = 64
    const val SORCERY = 128

    /**
     * Scryfall separates types from subtypes with an em dash, not a hyphen. The
     * two are indistinguishable in a diff and only one of them matches.
     */
    const val SUBTYPE_DASH = '—'

    /**
     * Everything the dial can offer: every bit that appears in some category.
     * A card that is only a land answers to none of them, and one that nothing
     * can roll is not worth a row, let alone 13 KB of artwork.
     */
    const val ROLLABLE = CREATURE or ARTIFACT or ENCHANTMENT or PLANESWALKER or
        BATTLE or INSTANT or SORCERY

    /** Word to bit, in the order Magic's own type line lists them. */
    val KEYWORDS = listOf(
        "Creature" to CREATURE,
        "Artifact" to ARTIFACT,
        "Enchantment" to ENCHANTMENT,
        "Planeswalker" to PLANESWALKER,
        "Land" to LAND,
        "Battle" to BATTLE,
        "Instant" to INSTANT,
        "Sorcery" to SORCERY,
    )

    /**
     * "Legendary Artifact Creature - Golem" -> `ARTIFACT or CREATURE`.
     *
     * Only the part before the dash is read. Everything after it is subtypes -
     * Equipment, Aura, Saga - and a subtype named after a type would otherwise
     * hand the card a bit it does not have. Cutting there also drops the far
     * side of a "//", which belongs to a face this app never rolls.
     */
    fun maskOf(typeLine: String): Int {
        val head = typeLine.substringBefore(SUBTYPE_DASH)
        var mask = 0
        for ((word, bit) in KEYWORDS) if (head.contains(word)) mask = mask or bit
        return mask
    }
}
