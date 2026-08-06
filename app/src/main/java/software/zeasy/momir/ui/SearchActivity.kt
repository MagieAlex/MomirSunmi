package software.zeasy.momir.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import software.zeasy.momir.R
import software.zeasy.momir.data.Card
import software.zeasy.momir.data.CardRepository
import software.zeasy.momir.databinding.ActivitySearchBinding
import software.zeasy.momir.databinding.ItemCardResultBinding

/**
 * Find a card by name and print it.
 *
 * The dial is the point of the app, but it is a dial: it rolls. Sooner or later
 * somebody wants *this* card - the token their opponent just made, the creature
 * they are trying to remember the wording of, a Sol Ring for the pile. Every one
 * of the 30,423 is on the device already; all that was missing was a way to
 * name one.
 *
 * It returns an oracle id rather than doing anything itself, the way
 * [ScannerActivity] returns the text it read. Printing belongs to the activity
 * that owns the printer, and the card is shown first - a search hit that printed
 * on touch would turn a mis-tap into a slip.
 */
class SearchActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySearchBinding
    private lateinit var repository: CardRepository

    /**
     * The query is re-run [DEBOUNCE_MS] after the last keystroke, not on each
     * one. A `LIKE '%...%'` is a scan of 30,000 rows; typing "urza" would start
     * four of them, and the first three answer a question nobody is asking any
     * more.
     */
    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = CardRepository(this)
        lifecycleScope.launch { withContext(Dispatchers.IO) { repository.open() } }

        binding.closeButton.setOnClickListener { finish() }
        binding.queryField.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = schedule(s?.toString().orEmpty())
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        })

        binding.queryField.requestFocus()
        // The keyboard is why this is a screen; opening without it would make
        // the first thing anyone does here a tap on the field they are already in.
        binding.queryField.post {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.showSoftInput(binding.queryField, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        pending?.let { handler.removeCallbacks(it) }
        repository.close()
    }

    private fun schedule(query: String) {
        pending?.let { handler.removeCallbacks(it) }
        val next = Runnable { run(query) }
        pending = next
        handler.postDelayed(next, DEBOUNCE_MS)
    }

    private fun run(query: String) {
        if (query.isBlank()) {
            show(emptyList(), typed = false)
            return
        }
        lifecycleScope.launch {
            val cards = withContext(Dispatchers.IO) { repository.search(query) }
            show(cards, typed = true)
        }
    }

    private fun show(cards: List<Card>, typed: Boolean) {
        binding.resultList.removeAllViews()
        binding.searchHint.visibility = if (cards.isEmpty()) View.VISIBLE else View.GONE
        binding.searchHint.setText(
            if (typed) R.string.search_nothing else R.string.search_empty
        )

        cards.forEach { card ->
            val row = ItemCardResultBinding.inflate(layoutInflater, binding.resultList, false)
            row.resultBadge.text = card.manaValue.toString()
            row.resultCardName.text = card.name
            row.resultCardType.text = card.typeLine
            row.root.setOnClickListener { choose(card) }
            binding.resultList.addView(row.root)
        }
    }

    private fun choose(card: Card) {
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_ORACLE_ID, card.oracleId))
        finish()
    }

    companion object {
        const val EXTRA_ORACLE_ID = "oracle_id"
        const val REQUEST_SEARCH = 2002

        private const val DEBOUNCE_MS = 220L
    }
}
