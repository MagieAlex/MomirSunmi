package software.zeasy.momir.print

import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan

/**
 * Turns Scryfall's oracle text into the abilities a slip prints.
 *
 * Oracle text arrives as one blob with newlines in it, and a newline between two
 * abilities is typographically identical to a wrap inside one - so a creature
 * with flying and a tap ability prints as a single grey paragraph in which the
 * reader has to find the boundary themselves. Each ability gets its own layout
 * and its own gap instead.
 *
 * Two other things happen here, both of them about the 32-character measure a
 * 58 mm slip gives you:
 *
 *  - **Braces come off.** `{2}{U}, {T}:` is twelve characters for three symbols.
 *    Without a mana font there is nothing to draw inside them, so they are
 *    twelve characters that carry no more meaning than `2U, T:`.
 *  - **Reminder text can be dropped.** It is set in italic when it is kept, and
 *    thrown away entirely before the type is shrunk, because a player who needs
 *    the layout to explain flying is not helped by reading it at 17 px either.
 */
object RulesText {

    /** `{2}{U}` -> `2U`, `{T}` -> `T`, `{W/P}` -> `W/P`. */
    private val SYMBOL = Regex("\\{([^{}]*)\\}")

    /** Reminder text: a parenthesised run, which Magic never nests. */
    private val REMINDER = Regex("\\(([^()]*)\\)")

    /**
     * One entry per ability, in print order.
     *
     * With [keepReminders] false, parenthesised runs are removed and any ability
     * that was nothing but a reminder goes with them.
     */
    fun abilities(oracle: String, keepReminders: Boolean): List<CharSequence> =
        oracle.split('\n')
            .map { SYMBOL.replace(it) { match -> match.groupValues[1] }.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { if (keepReminders) italicise(it) else stripReminders(it) }

    /** Marks the parenthesised runs italic, so a reminder reads as an aside. */
    private fun italicise(ability: String): CharSequence {
        val matches = REMINDER.findAll(ability).toList()
        if (matches.isEmpty()) return ability
        val spanned = SpannableString(ability)
        matches.forEach {
            spanned.setSpan(
                StyleSpan(Typeface.ITALIC),
                it.range.first,
                it.range.last + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        return spanned
    }

    /** Null when nothing but the reminder was there - "(Do this.)" on its own line. */
    private fun stripReminders(ability: String): CharSequence? {
        val stripped = REMINDER.replace(ability, "")
            // The space in front of a removed reminder stays behind, and so does
            // the one after it when the reminder sat mid-sentence.
            .replace(Regex("[ \\t]{2,}"), " ")
            .trim()
        return stripped.ifEmpty { null }
    }
}
