package software.zeasy.momir.data

/**
 * One Momir-legal creature, exactly as the builder wrote it into momir.db.
 *
 * [artOffset]/[artLength]/[artHeight] point into art.pack. They are null while
 * artwork for this card has not been fetched yet, which is the normal state for
 * cards that arrived through an on-device resync but whose art is still queued.
 */
data class Card(
    val oracleId: String,
    val name: String,
    val manaCost: String,
    val manaValue: Int,
    val typeLine: String,
    val oracleText: String,
    val power: String?,
    val toughness: String?,
    /** WUBRG letters, empty for colourless. Drives the colour of the print animation. */
    val colorIdentity: String,
    val scryfallUri: String,
    val artOffset: Long?,
    val artLength: Int?,
    val artHeight: Int?,
) {
    val hasArt: Boolean get() = artOffset != null && artLength != null && artHeight != null

    /** "3/4", or null for creatures that somehow lack P/T in the data. */
    val powerToughness: String?
        get() = if (power != null && toughness != null) "$power/$toughness" else null

    /**
     * "{2}{G}{G}" -> "2GG". Braces eat a lot of width on a 48 mm slip and add
     * nothing once the mana value is already printed in the badge.
     */
    val plainManaCost: String
        get() = manaCost.replace("{", "").replace("}", " ").trim().replace("  ", " ")
}
