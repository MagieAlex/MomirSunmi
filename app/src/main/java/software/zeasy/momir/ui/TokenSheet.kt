package software.zeasy.momir.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.Window
import software.zeasy.momir.R
import software.zeasy.momir.data.Token
import software.zeasy.momir.databinding.DialogTokensBinding
import software.zeasy.momir.databinding.ItemTokenBinding
import software.zeasy.momir.print.SlipContent

/**
 * The sheet that comes up when a creature makes tokens.
 *
 * Three things it does that the list dialog it replaces did not:
 *
 * - It says where it came from. Scanning a slip across the table opens this with
 *   no other context on screen, so the header carries a "scanned slip" stamp and
 *   the card's name in full rather than leaving the player to work out which of
 *   the six slips in front of them the app is talking about.
 * - It shows each token as a token: colour stripe, power/toughness, name, type.
 * - It takes a quantity. A Rhys the Redeemed player does not want six trips
 *   through a dialog, and the printer is happy to run six slips off one press.
 *
 * @param onPrint given the tokens to print and how many of each. Printing is the
 *   activity's job - it owns the printer, the glow and the busy state.
 */
class TokenSheet(
    private val activity: Activity,
    private val cardName: String,
    private val tokens: List<Token>,
    private val fromScan: Boolean,
    private val onPrint: (tokens: List<Token>, each: Int) -> Unit,
    private val onDismiss: () -> Unit = {},
) {

    private val binding = DialogTokensBinding.inflate(activity.layoutInflater)
    private val rows = ArrayList<ItemTokenBinding>(tokens.size)

    private var selected = 0
    private var quantity = 1

    private val dialog = Dialog(activity, R.style.Theme_Momir_Sheet).apply {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(binding.root)
        setCanceledOnTouchOutside(true)
        setOnDismissListener { onDismiss() }
    }

    fun show() {
        if (tokens.isEmpty()) return

        buildHeader()
        buildRows()
        buildQuantity()
        buildActions()
        select(0)

        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            // Full width, on the bottom edge. A sheet that floats in the middle
            // of the screen is a dialog wearing a sheet's clothes.
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
        }
        dialog.show()
    }

    // ------------------------------------------------------------------------

    private fun buildHeader() {
        binding.sourceChip.setText(
            if (fromScan) R.string.tokens_chip_scanned else R.string.tokens_chip_creates
        )
        binding.sheetTitle.text = cardName
        binding.sheetSubtitle.text = activity.resources
            .getQuantityString(R.plurals.tokens_subtitle, tokens.size, tokens.size)
    }

    private fun buildRows() {
        val inflater = activity.layoutInflater
        tokens.forEachIndexed { index, token ->
            val row = ItemTokenBinding.inflate(inflater, binding.tokenList, false)

            row.tokenName.text = token.name
            row.tokenPt.text = token.powerToughness.orEmpty()
            // Invisible, not gone: a Treasure has no power and toughness, and
            // letting its name slide left would break the column the others make.
            row.tokenPt.visibility = if (token.powerToughness == null) View.INVISIBLE else View.VISIBLE
            row.tokenType.text = listOf(token.typeLine, SlipContent.colorLabel(token.colors))
                .filter { it.isNotBlank() }
                .joinToString(SEPARATOR)
            row.tokenStripe.background = stripeFor(token.colors)

            row.root.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                select(index)
            }
            binding.tokenList.addView(row.root)
            rows += row
        }

        // Four rows is a full sheet; beyond that the list scrolls rather than
        // pushing the print button off a 5" screen.
        if (tokens.size > MAX_VISIBLE_ROWS) {
            val density = activity.resources.displayMetrics.density
            binding.tokenScroll.layoutParams.height = (MAX_VISIBLE_ROWS * ROW_HEIGHT_DP * density).toInt()
        }
    }

    private fun buildQuantity() {
        binding.quantityMinus.setOnClickListener { step(-1, it) }
        binding.quantityPlus.setOnClickListener { step(1, it) }
        showQuantity()
    }

    private fun buildActions() {
        // "One of each" only means something when there is more than one.
        binding.printEachButton.visibility = if (tokens.size > 1) View.VISIBLE else View.GONE

        binding.printButton.setOnClickListener {
            dialog.dismiss()
            onPrint(listOf(tokens[selected]), quantity)
        }
        binding.printEachButton.setOnClickListener {
            dialog.dismiss()
            onPrint(tokens, quantity)
        }
        binding.closeButton.setOnClickListener { dialog.dismiss() }
    }

    // ------------------------------------------------------------------------

    private fun select(index: Int) {
        selected = index
        rows.forEachIndexed { position, row ->
            val active = position == index
            row.root.isActivated = active
            row.tokenCheck.visibility = if (active) View.VISIBLE else View.INVISIBLE
        }
        showPrintLabel()
    }

    private fun step(delta: Int, view: View) {
        val next = (quantity + delta).coerceIn(1, MAX_QUANTITY)
        if (next == quantity) return
        quantity = next
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        showQuantity()
        showPrintLabel()
    }

    private fun showQuantity() {
        binding.quantityValue.text = quantity.toString()
        binding.quantityMinus.isEnabled = quantity > 1
        binding.quantityPlus.isEnabled = quantity < MAX_QUANTITY
        binding.quantityMinus.alpha = if (quantity > 1) 1f else 0.35f
        binding.quantityPlus.alpha = if (quantity < MAX_QUANTITY) 1f else 0.35f
    }

    private fun showPrintLabel() {
        binding.printButton.text =
            activity.getString(R.string.tokens_print, quantity, tokens[selected].name)
    }

    /**
     * The row's colour stripe. A single colour is flat; a gold-and-green Rhys
     * token gets both, in WUBRG order, so it matches the glow the print itself
     * throws off.
     */
    private fun stripeFor(colors: String): GradientDrawable = ManaColors.stripe(
        ManaColors.forIdentity(colors), activity.resources.displayMetrics.density,
    )

    private companion object {
        const val MAX_QUANTITY = 20
        const val MAX_VISIBLE_ROWS = 4

        /** Matches item_token.xml: 58 dp row plus its 8 dp bottom margin. */
        const val ROW_HEIGHT_DP = 66

        const val SEPARATOR = "  ·  "
    }
}
