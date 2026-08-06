package software.zeasy.momir.data

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import software.zeasy.momir.R

/**
 * What the dial rolls.
 *
 * The categories overlap, deliberately. An artifact creature comes up under
 * Creatures, under Artifacts and under Permanents, because it is all three -
 * a player who asked for a random artifact and got Solemn Simulacrum got what
 * they asked for.
 *
 * Lands are left out of Permanents on purpose. Momir hands you something to
 * play; a random Island is not a game.
 *
 * The label and the nouns live here beside the mask rather than in a parallel
 * table in the activity, so a seventh category cannot be half-added.
 */
enum class CardCategory(
    val mask: Int,
    @StringRes val label: Int,
    /** "%1$s creatures". Every line that counts cards is this phrase in a frame. */
    @PluralsRes val tally: Int,
    @StringRes val noneAtManaValue: Int,
) {
    PERMANENTS(
        CardTypes.CREATURE or CardTypes.ARTIFACT or CardTypes.ENCHANTMENT or
            CardTypes.PLANESWALKER or CardTypes.BATTLE,
        R.string.category_permanents, R.plurals.count_permanents, R.string.none_permanents,
    ),
    CREATURES(
        CardTypes.CREATURE,
        R.string.category_creatures, R.plurals.count_creatures, R.string.none_creatures,
    ),
    ARTIFACTS(
        CardTypes.ARTIFACT,
        R.string.category_artifacts, R.plurals.count_artifacts, R.string.none_artifacts,
    ),
    ENCHANTMENTS(
        CardTypes.ENCHANTMENT,
        R.string.category_enchantments, R.plurals.count_enchantments, R.string.none_enchantments,
    ),
    PLANESWALKERS(
        CardTypes.PLANESWALKER,
        R.string.category_planeswalkers, R.plurals.count_planeswalkers, R.string.none_planeswalkers,
    ),
    SPELLS(
        CardTypes.INSTANT or CardTypes.SORCERY,
        R.string.category_spells, R.plurals.count_spells, R.string.none_spells,
    ),
}
