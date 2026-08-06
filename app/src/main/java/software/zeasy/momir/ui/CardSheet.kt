package software.zeasy.momir.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.TextView
import androidx.core.content.ContextCompat
import software.zeasy.momir.R
import software.zeasy.momir.data.Card
import software.zeasy.momir.databinding.DialogCardBinding
import software.zeasy.momir.databinding.ItemAbilityBinding
import software.zeasy.momir.print.EscPos
import software.zeasy.momir.print.RulesText
import software.zeasy.momir.print.SlipContent

/**
 * The card behind the slip.
 *
 * The result panel names what was printed and how long it came out; tapping that
 * name opens this. It exists because the slip is 48 mm wide and often across the
 * table by the time somebody wants to check what an ability actually said - and
 * because everything needed to show the card is already on the device, artwork
 * included. Nothing here touches the network.
 *
 * Laid out in the order a Magic card is: title bar, art window, type line with
 * power/toughness in its box, text box. Same abilities-one-per-block treatment
 * the slip gives them, and reminder text stays, in italic, because a screen has
 * room for it where 568 dots of paper does not.
 *
 * ## The artwork is shown on paper
 *
 * What art.pack holds is a 1-bit Floyd-Steinberg dither prepared for a thermal
 * head: one bit per dot, 1 = burn. Painted white-on-black to match the app it
 * would be a photographic negative, so the art window is paper-coloured and the
 * set bits are ink. It is the same image the slip prints, which is the point -
 * this is a view of the card you are holding, not a different picture of it.
 *
 * @param onTokens opened from the footer when the card creates any. Printing
 *   stays the activity's job; this sheet only ever shows.
 */
class CardSheet(
    private val activity: Activity,
    private val card: Card,
    private val tokenCount: Int,
    /** The card's raster from art.pack, already read off the main thread. */
    private val art: ByteArray?,
    private val onTokens: () -> Unit,
    private val onDismiss: () -> Unit = {},
) {

    private val binding = DialogCardBinding.inflate(activity.layoutInflater)

    private val dialog = Dialog(activity, R.style.Theme_Momir_Sheet).apply {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(binding.root)
        setCanceledOnTouchOutside(true)
        setOnDismissListener { onDismiss() }
    }

    fun show() {
        bindTitle()
        bindArt()
        bindTypeLine()
        bindRules()
        bindActions()

        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
        }
        dialog.show()
        capHeight()
    }

    // ------------------------------------------------------------------------

    private fun bindTitle() {
        val badge = card.manaValue.toString()
        binding.cardBadge.text = badge
        binding.cardName.text = card.name
        // Same rule the slip applies: a single generic {7} beside a ring that
        // already says 7 is noise, and a card with no mana cost at all - Kobolds
        // of Kher Keep, an Eldrazi Spawn - has nothing to put here.
        val cost = card.manaCost.takeIf {
            it.isNotBlank() && card.plainManaCost != badge
        }
        binding.cardCost.visibility = if (cost == null) View.GONE else View.VISIBLE
        binding.cardCost.text = cost?.let { symbols(it, binding.cardCost) } ?: ""
    }

    private fun bindArt() {
        val height = card.artHeight ?: 0
        val bitmap = if (art != null && height > 0) toBitmap(art, height) else null
        if (bitmap == null) {
            binding.cardArt.visibility = View.GONE
            return
        }
        binding.cardArt.visibility = View.VISIBLE
        binding.cardArt.setImageBitmap(bitmap)
    }

    private fun bindTypeLine() {
        binding.cardType.text =
            listOf(SlipContent.colorAdjective(card.colorIdentity), card.typeLine)
                .filter { it.isNotBlank() }
                .joinToString(" ")

        // Power/toughness or loyalty, never both - and the label the slip prints
        // is unnecessary here, where the type line above it is fully legible.
        val corner = card.powerToughness ?: card.loyalty
        binding.cardCorner.visibility = if (corner == null) View.GONE else View.VISIBLE
        binding.cardCorner.text = corner.orEmpty()
    }

    private fun bindRules() {
        // Braces kept, unlike the slip's: there is a mana font here to put in
        // them. Reminder text stays too - a screen has room for it where 568
        // dots of paper does not.
        val abilities = RulesText.abilities(
            card.oracleText,
            keepReminders = true,
            stripSymbols = false,
        )
        binding.cardRulesRule.visibility = if (abilities.isEmpty()) View.GONE else View.VISIBLE
        abilities.forEach { ability ->
            val row = ItemAbilityBinding.inflate(activity.layoutInflater, binding.cardRules, false)
            row.root.text = symbols(ability, row.root)
            binding.cardRules.addView(row.root)
        }
    }

    /**
     * Swaps `{...}` for the real symbol, sized to the type it sits in.
     *
     * Slightly larger than the text, because a disc reads smaller than a letter
     * of the same height - the ink is spread around a circle rather than up a
     * stem.
     */
    private fun symbols(text: CharSequence, view: TextView): CharSequence =
        ManaSymbols.render(activity, text, (view.textSize * SYMBOL_SCALE).toInt())

    private fun bindActions() {
        binding.cardTokens.visibility = if (tokenCount > 0) View.VISIBLE else View.GONE
        binding.cardTokens.text = activity.resources
            .getQuantityString(R.plurals.tokens_button, tokenCount, tokenCount)
        binding.cardTokens.setOnClickListener {
            dialog.dismiss()
            onTokens()
        }
        binding.cardClose.setOnClickListener { dialog.dismiss() }
    }

    /**
     * Keeps the sheet on the screen.
     *
     * A six-ability planeswalker with a tall art crop is more than a 1440 px
     * device has, and a sheet that overruns pushes its own buttons off the
     * bottom. The scrolling part gives way; the title bar and the actions do not.
     */
    private fun capHeight() {
        val scroll = binding.cardScroll
        scroll.post {
            val cap = (activity.resources.displayMetrics.heightPixels * MAX_SCROLL_FRACTION).toInt()
            if (scroll.height > cap) {
                scroll.layoutParams.height = cap
                scroll.requestLayout()
            }
        }
    }

    /**
     * Unpacks a 1 bpp raster into a bitmap, ink on paper.
     *
     * Left at its native 384 dots wide and scaled up by the ImageView, which
     * filters it - on a screen that reads as a soft engraving, where nearest
     * neighbour at 1.7x would enlarge the dither into visible chequerboard.
     */
    private fun toBitmap(raster: ByteArray, rows: Int): Bitmap? {
        val width = EscPos.PRINT_WIDTH_DOTS
        val stride = EscPos.BYTES_PER_ROW
        if (raster.size < stride) return null
        val available = minOf(rows, raster.size / stride)
        if (available <= 0) return null

        val ink = ContextCompat.getColor(activity, R.color.ink)
        val paper = ContextCompat.getColor(activity, R.color.paper)
        val pixels = IntArray(width * available)
        for (row in 0 until available) {
            val base = row * stride
            val out = row * width
            for (byteIndex in 0 until stride) {
                val packed = raster[base + byteIndex].toInt()
                val columnBase = byteIndex * 8
                for (bit in 0 until 8) {
                    val isInk = (packed shr (7 - bit)) and 1 == 1
                    pixels[out + columnBase + bit] = if (isInk) ink else paper
                }
            }
        }
        return Bitmap.createBitmap(pixels, width, available, Bitmap.Config.ARGB_8888)
    }

    private companion object {
        /** How much of the screen the scrolling part may take before it scrolls. */
        const val MAX_SCROLL_FRACTION = 0.60f

        /** Symbol size against the type it is set in. See [symbols]. */
        const val SYMBOL_SCALE = 1.12f
    }
}
