package software.zeasy.momir.data

/**
 * A token a creature can put onto the battlefield.
 *
 * Momir hands you a random creature, and roughly one in nine of those makes
 * something else the moment it lands - a Zombie, a Treasure, a Food. Without a
 * physical token you are back to scribbling on dice, so the app knows about
 * these and can print them on the spot.
 *
 * Sourced from Scryfall's `all_parts`, which is the game's own answer to
 * "what does this card create".
 */
data class Token(
    val oracleId: String,
    val name: String,
    val typeLine: String,
    val oracleText: String,
    val power: String?,
    val toughness: String?,
    /** Concatenated WUBRG letters; empty means colorless. */
    val colors: String,
    val scryfallUri: String,
    val artOffset: Long?,
    val artLength: Int?,
    val artHeight: Int?,
) {
    val hasArt: Boolean get() = artOffset != null && artLength != null && artHeight != null

    val powerToughness: String?
        get() = if (power != null && toughness != null) "$power/$toughness" else null

    /** "2/2 Black Zombie", the way a player would say it out loud. */
    val shortLabel: String
        get() = listOfNotNull(powerToughness, colorNames.ifEmpty { null }, name)
            .joinToString(" ")

    private val colorNames: String
        get() = colors.mapNotNull { COLOR_NAMES[it] }.joinToString(" ")

    companion object {
        private val COLOR_NAMES = mapOf(
            'W' to "White", 'U' to "Blue", 'B' to "Black", 'R' to "Red", 'G' to "Green",
        )
    }
}
