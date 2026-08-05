package software.zeasy.momir.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import software.zeasy.momir.R
import software.zeasy.momir.Settings
import software.zeasy.momir.data.ArtPack
import software.zeasy.momir.data.Card
import software.zeasy.momir.data.CardRepository
import software.zeasy.momir.data.Token
import software.zeasy.momir.databinding.ActivityMainBinding
import software.zeasy.momir.databinding.DialogSettingsBinding
import software.zeasy.momir.print.EscPos
import software.zeasy.momir.print.PrintMode
import software.zeasy.momir.print.PrintResult
import software.zeasy.momir.print.Raster
import software.zeasy.momir.print.SlipContent
import software.zeasy.momir.print.SlipRenderer
import software.zeasy.momir.print.SunmiPrinter
import software.zeasy.momir.sync.SyncService
import java.text.NumberFormat
import java.util.Locale

/**
 * One screen: pick a mana value, hit the button, take the slip.
 *
 * Everything expensive - the database query, the layout pass, the dither blit,
 * the Binder round-trip - happens off the main thread. On a 909 MB armeabi-v7a
 * device that is the difference between a dial that spins and one that stutters
 * every time you print.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: Settings
    private lateinit var repository: CardRepository
    private lateinit var artPack: ArtPack
    private lateinit var printer: SunmiPrinter

    private val renderer = SlipRenderer()

    /**
     * Pinned to US rather than the device locale. The app is English-only, and on
     * a German-configured V2 the default formatter renders 17,497 as "17.497",
     * which an English reader takes for a decimal.
     */
    private val numberFormat: NumberFormat = NumberFormat.getIntegerInstance(Locale.US)

    /** The creature currently on the result card, so its tokens stay reachable. */
    private var lastCard: Card? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = Settings(this)
        repository = CardRepository(this)
        artPack = ArtPack(repository.artPackFile)
        printer = SunmiPrinter(this)

        setUpModeSelector()
        binding.manaWheel.onSelectionChanged = { manaValue ->
            settings.lastManaValue = manaValue
            updateCountLabel(manaValue)
        }

        binding.printButton.label = getString(R.string.print)
        binding.printButton.onPress = { rollAndPrint() }
        binding.printButton.onLongPress = { previewSlip() }
        binding.settingsButton.setOnClickListener { showSettings() }
        binding.syncButton.setOnClickListener { startResync() }
        binding.scanButton.setOnClickListener { startScan() }
        binding.resultTokens.setOnClickListener { lastCard?.let { showTokens(it) } }

        lifecycleScope.launch {
            val opened = withContext(Dispatchers.IO) {
                val ok = repository.open()
                if (ok) artPack.open()
                ok
            }
            showCorpusState(opened)
            printer.connect()
        }
    }

    override fun onStart() {
        super.onStart()
        SyncService.listener = { status -> onSyncStatus(status) }
    }

    override fun onStop() {
        super.onStop()
        SyncService.listener = null
    }

    override fun onDestroy() {
        super.onDestroy()
        printer.disconnect()
        artPack.close()
        repository.close()
    }

    // ------------------------------------------------------------------------
    // Setup
    // ------------------------------------------------------------------------

    private fun setUpModeSelector() {
        val segments = listOf(
            binding.modeQr to PrintMode.QR,
            binding.modeArt to PrintMode.ARTWORK,
        )
        segments.forEach { (view, mode) ->
            view.setOnClickListener {
                settings.printMode = mode
                highlightMode(segments)
            }
        }
        highlightMode(segments)
    }

    private fun highlightMode(segments: List<Pair<TextView, PrintMode>>) {
        val active = settings.printMode
        segments.forEach { (view, mode) -> view.isSelected = mode == active }
    }

    private fun showCorpusState(opened: Boolean) {
        val hasCards = opened && repository.cardCount() > 0
        val visibility = if (hasCards) View.VISIBLE else View.GONE
        binding.emptyState.visibility = if (hasCards) View.GONE else View.VISIBLE
        binding.manaWheel.visibility = visibility
        binding.printButton.visibility = visibility
        binding.countLabel.visibility = visibility
        binding.resultCard.visibility = visibility
        binding.scanButton.visibility = visibility

        if (!hasCards) {
            binding.corpusInfo.text = getString(R.string.no_corpus_title)
            return
        }

        // Read the saved value before touching the wheel: assigning values resets
        // the wheel's selection, and settings.lastManaValue is written from the
        // wheel's own callback.
        val restore = settings.lastManaValue
        binding.manaWheel.values = repository.manaValueCounts().map { it.manaValue }
        binding.manaWheel.setSelectedValue(restore)
        updateCountLabel(binding.manaWheel.selectedValue ?: restore)

        val cards = numberFormat.format(repository.cardCount())
        val artworks = numberFormat.format(repository.artCount())
        val tokens = repository.tokenCount()
        binding.corpusInfo.text = if (tokens > 0) {
            getString(R.string.corpus_summary_tokens, cards, artworks, numberFormat.format(tokens))
        } else {
            getString(R.string.corpus_summary, cards, artworks)
        }
    }

    private fun updateCountLabel(manaValue: Int) {
        val count = repository.manaValueCounts().firstOrNull { it.manaValue == manaValue }?.count ?: 0
        binding.countLabel.text =
            getString(R.string.creatures_at_mv, numberFormat.format(count), manaValue)
    }

    // ------------------------------------------------------------------------
    // Printing
    // ------------------------------------------------------------------------

    private fun rollAndPrint() {
        val manaValue = binding.manaWheel.selectedValue ?: return
        binding.printButton.isBusy = true

        // Answer the press immediately, before anything has been rolled. The
        // roll takes a few milliseconds, but a button that does nothing until
        // the database comes back feels broken.
        binding.printButton.flash(ContextCompat.getColor(this, R.color.accent_soft))

        lifecycleScope.launch {
            val card = withContext(Dispatchers.IO) { repository.randomCard(manaValue) }
            if (card == null) {
                binding.printButton.isBusy = false
                toast(getString(R.string.no_card_found, manaValue))
                return@launch
            }

            playPrintGlow(card.colorIdentity)
            // Name it now, not after printing. The glow already shows this card's
            // colours, and leaving the previous card's name underneath for the
            // second and a half the printer takes just looks like a stale screen.
            showResult(card, null)

            val raster = printSlip(SlipContent.of(card))
            binding.printButton.isBusy = false
            if (raster != null) showResult(card, raster.height)
        }
    }

    /**
     * Runs the colour-identity glow: the button takes the card's colours, then a
     * band of them sweeps up the screen and out of the top edge, which is where
     * the V2's paper actually emerges.
     */
    private fun playPrintGlow(colorIdentity: String) {
        val colors = ManaColors.forIdentity(colorIdentity)
        binding.printButton.flash(colors.first())

        // Anchor the band on the button, in overlay coordinates.
        val button = binding.printButton
        val originY = button.y + button.height / 2f
        binding.glowOverlay.play(colors, originY)
    }

    /**
     * Renders and prints one slip. Returns the raster so the caller can report
     * how long the thing actually came out, or null if anything went wrong.
     */
    private suspend fun printSlip(content: SlipContent): Raster? {
        if (!printer.isConnected && !printer.connect()) {
            toast(getString(R.string.printer_not_connected))
            return null
        }

        val mode = settings.printMode
        val budget = settings.contentBudgetDots

        val raster = withContext(Dispatchers.IO) {
            val art = if (mode == PrintMode.ARTWORK && content.hasArt) {
                artPack.read(content.artOffset!!, content.artLength!!)
            } else null

            // Fall back to the QR layout rather than printing a card-shaped blank
            // when this particular subject has no artwork yet.
            val effectiveMode = if (mode == PrintMode.ARTWORK && art == null) PrintMode.QR else mode

            renderer.render(
                content = content,
                mode = effectiveMode,
                art = art,
                artHeight = content.artHeight ?: 0,
                budgetDots = budget,
            )
        }

        var failure: String? = null
        repeat(settings.copies) {
            when (val result = printer.print(raster, settings.tearFeedDots)) {
                is PrintResult.Success -> Unit
                is PrintResult.Failure -> failure = result.message
            }
        }

        failure?.let {
            toast(getString(R.string.print_failed, it))
            return null
        }
        return raster
    }

    /**
     * Long press: roll a creature and write what would have been printed to
     * preview.png instead of printing it. Checking a layout change costs an
     * `adb pull` rather than half a metre of paper.
     */
    private fun previewSlip() {
        val manaValue = binding.manaWheel.selectedValue ?: return
        lifecycleScope.launch {
            val card = withContext(Dispatchers.IO) { repository.randomCard(manaValue) }
            if (card == null) {
                toast(getString(R.string.no_card_found, manaValue))
                return@launch
            }

            // Preview runs the glow too, so the animation can be worked on
            // without feeding paper through the printer for every tweak.
            playPrintGlow(card.colorIdentity)

            val height = withContext(Dispatchers.IO) {
                val content = SlipContent.of(card)
                val mode = settings.printMode
                val art = if (mode == PrintMode.ARTWORK && content.hasArt) {
                    artPack.read(content.artOffset!!, content.artLength!!)
                } else null
                val effectiveMode = if (mode == PrintMode.ARTWORK && art == null) PrintMode.QR else mode

                val bitmap = renderer.compose(
                    content, effectiveMode, art, content.artHeight ?: 0, settings.contentBudgetDots,
                )
                java.io.FileOutputStream(java.io.File(getExternalFilesDir(null), "preview.png")).use { out ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                }
                val h = bitmap.height
                bitmap.recycle()
                h
            }

            showResult(card, height)
            toast(getString(R.string.preview_written, card.name))
        }
    }

    /** [contentDots] is null until the slip has actually been rendered. */
    private fun showResult(card: Card, contentDots: Int?) {
        lastCard = card
        binding.resultName.text = card.name
        binding.resultDetail.text = listOfNotNull(
            card.typeLine.takeIf { it.isNotBlank() },
            card.powerToughness,
            contentDots?.let {
                String.format(Locale.US, "%.0f mm", it / EscPos.DOTS_PER_MM + settings.tearFeedMm)
            },
        ).joinToString(" · ")

        val tokens = repository.tokensFor(card.oracleId)
        if (tokens.isEmpty()) {
            binding.resultTokens.visibility = View.GONE
        } else {
            binding.resultTokens.visibility = View.VISIBLE
            binding.resultTokens.text = resources.getQuantityString(R.plurals.tokens_button, tokens.size, tokens.size)
        }
    }

    // ------------------------------------------------------------------------
    // Tokens
    // ------------------------------------------------------------------------

    private fun showTokens(card: Card) {
        val tokens = repository.tokensFor(card.oracleId)
        if (tokens.isEmpty()) {
            toast(getString(R.string.tokens_none, card.name))
            return
        }

        val labels = tokens.map { it.shortLabel }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.tokens_title, card.name))
            .setItems(labels) { _, which -> printTokens(listOf(tokens[which])) }
            .setNeutralButton(R.string.tokens_print_all) { _, _ -> printTokens(tokens) }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun printTokens(tokens: List<Token>) {
        binding.printButton.isBusy = true
        lifecycleScope.launch {
            for (token in tokens) {
                // Tokens carry plain colours rather than a colour identity, but
                // they read the same way on screen.
                playPrintGlow(token.colors)
                if (printSlip(SlipContent.of(token)) == null) break
            }
            binding.printButton.isBusy = false
        }
    }

    // ------------------------------------------------------------------------
    // Scanning
    // ------------------------------------------------------------------------

    private fun startScan() {
        startActivityForResult(
            Intent(this, ScannerActivity::class.java),
            ScannerActivity.REQUEST_SCAN,
        )
    }

    @Deprecated("startActivityForResult is the right size of tool for one result on API 25")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != ScannerActivity.REQUEST_SCAN || resultCode != Activity.RESULT_OK) return

        val text = data?.getStringExtra(ScannerActivity.EXTRA_RESULT) ?: return
        if (!text.contains("scryfall.com")) {
            toast(getString(R.string.scan_not_a_card, text.take(60)))
            return
        }

        lifecycleScope.launch {
            val card = withContext(Dispatchers.IO) { repository.cardByScryfallUri(text) }
            if (card == null) {
                toast(getString(R.string.scan_not_found))
                return@launch
            }
            lastCard = card
            binding.resultName.text = card.name
            binding.resultDetail.text = listOfNotNull(
                card.typeLine.takeIf { it.isNotBlank() },
                card.powerToughness,
            ).joinToString(" · ")

            val tokens = repository.tokensFor(card.oracleId)
            binding.resultTokens.visibility = if (tokens.isEmpty()) View.GONE else View.VISIBLE
            if (tokens.isNotEmpty()) {
                binding.resultTokens.text = resources.getQuantityString(R.plurals.tokens_button, tokens.size, tokens.size)
            }
            showTokens(card)
        }
    }

    // ------------------------------------------------------------------------
    // Settings
    // ------------------------------------------------------------------------

    private fun showSettings() {
        val view = DialogSettingsBinding.inflate(layoutInflater)

        view.copiesSeek.progress = settings.copies - 1
        view.slipSeek.progress = (settings.slipLengthMm - SLIP_MIN_MM).toInt()
        view.feedSeek.progress = settings.tearFeedMm.toInt()

        fun refreshLabels() {
            view.copiesLabel.text = "${getString(R.string.settings_copies)}: ${view.copiesSeek.progress + 1}"
            view.slipLabel.text =
                "${getString(R.string.settings_slip_length)}: ${view.slipSeek.progress + SLIP_MIN_MM.toInt()} mm"
            view.feedLabel.text = "${getString(R.string.settings_tear_feed)}: ${view.feedSeek.progress} mm"
        }
        refreshLabels()

        val watcher = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = refreshLabels()
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        }
        view.copiesSeek.setOnSeekBarChangeListener(watcher)
        view.slipSeek.setOnSeekBarChangeListener(watcher)
        view.feedSeek.setOnSeekBarChangeListener(watcher)

        view.diagnostics.text = buildDiagnostics()
        view.testPrintButton.setOnClickListener { printTestPattern() }

        AlertDialog.Builder(this)
            .setTitle(R.string.settings)
            .setView(view.root)
            .setPositiveButton(R.string.close) { _, _ ->
                settings.copies = view.copiesSeek.progress + 1
                settings.slipLengthMm = view.slipSeek.progress + SLIP_MIN_MM
                settings.tearFeedMm = view.feedSeek.progress.toFloat()
            }
            .show()
    }

    private fun buildDiagnostics(): String {
        val printerLines = printer.diagnostics().map { (key, value) -> "$key  $value" }
        val corpusLines = if (repository.isReady) {
            listOf(
                "cards   ${numberFormat.format(repository.cardCount())}",
                "art     ${numberFormat.format(repository.artCount())}",
                "tokens  ${numberFormat.format(repository.tokenCount())}",
                "bulk    ${repository.meta("bulk_updated_at") ?: "?"}",
            )
        } else listOf("corpus  not loaded")
        return (printerLines + corpusLines).joinToString("\n")
    }

    /**
     * Prints a calibration slip so the tear-feed setting can be dialled in
     * without guessing: print it, tear it, hold it against a ruler and compare
     * with the length the app reports.
     */
    private fun printTestPattern() {
        lifecycleScope.launch {
            val content = SlipContent(
                title = "Test Slip",
                badge = "8",
                subline = "CALIBRATION",
                typeLine = "Tear here and measure",
                powerToughness = null,
                rulesText = "Tear this off and hold it against a ruler. Adjust \"feed after " +
                    "printing\" until the measured length matches what the app reports, then " +
                    "check that it slides into a sleeve. A Magic card is 88 mm long.",
                linkUri = "https://scryfall.com/card/test/1/test-slip",
                artOffset = null, artLength = null, artHeight = null,
            )
            val raster = printSlip(content)
            if (raster != null) {
                toast(getString(R.string.slip_length_toast, raster.heightMm + settings.tearFeedMm))
            }
        }
    }

    // ------------------------------------------------------------------------
    // Sync
    // ------------------------------------------------------------------------

    private fun startResync() {
        if (SyncService.lastStatus is SyncService.Status.Running) {
            toast(getString(R.string.sync_already_running))
            return
        }
        SyncService.start(this)
        toast(getString(R.string.sync_running, "starting"))
    }

    private fun onSyncStatus(status: SyncService.Status) {
        when (status) {
            is SyncService.Status.Running ->
                binding.corpusInfo.text = getString(R.string.sync_running, status.stage)

            is SyncService.Status.Finished -> {
                val outcome = status.outcome
                if (outcome.error != null) {
                    toast(getString(R.string.sync_failed, outcome.error))
                } else {
                    toast(getString(R.string.sync_done, outcome.newCards, outcome.newArtwork))
                }
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        repository.close()
                        artPack.close()
                        repository.open()
                        artPack.open()
                    }
                    showCorpusState(repository.isReady)
                }
            }

            SyncService.Status.Idle -> Unit
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val SLIP_MIN_MM = 50f
    }
}
