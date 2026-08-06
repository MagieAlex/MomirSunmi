# Handoff

Working state as of 2026-08-06, so this can be picked up on another machine.
Delete this file when the work below is finished.

## Where things stand

The app grew three things this session: a Magic-native visual design, a
card-type selector so it rolls more than creatures, and the print button as a
struck seal carrying the planeswalker symbol. The corpus builder was widened to
match. Two audits were run over the app UI and the printed slip; most of what
they found is still open and is listed at the bottom.

Everything described here is committed and pushed. Nothing is half-applied in
the working tree.

## What is done

**Design.** Warm blacks and card-frame brass instead of the old blue-grey; serif
for anything naming a card; mana values drawn as generic mana symbols on stone
discs; hairlines that fade at their ends. `colors.xml`, `themes.xml`, the
`bg_*` drawables, `ManaWheel`, `PrintButton`, `GlowOverlay`.

**The seal.** `PrintButton` is a stone face with a brass rim, the five colours
set into it as gems (WUBRG clockwise from the top), and the planeswalker symbol
inlaid and lit from inside. The rim and the symbol take the rolled card's colour
identity and keep it until the result panel retires. The symbol outline in
`PrintButton.SIGIL` was traced from a vector original — do not redraw it by
hand, an earlier attempt from memory produced a three-pronged shape that was
recognisably not the symbol. The tracing script is described under *Scratch* below.

**The launcher icon is the seal.** `tools/make_icon.py` reads `SIGIL` out of
`PrintButton.kt` and the five colours out of `ManaColors.kt`, so the icon cannot
drift from the button. Change the button, re-run `python tools/make_icon.py`.

**Card-type modes.** A scrolling chip row picks Permanents / Creatures /
Artifacts / Enchantments / Planeswalkers / Spells. Each is a bitmask over the
new `cards.type_mask`; a card carries every bit its front-face type line names,
so an artifact creature answers to three of the six. Bits live in `CardTypes`,
the categories in `CardCategory`, and both sides — `CardRepository`,
`ScryfallSync`, and `momirdeck.py` — agree on the same values. Planeswalkers
carry `loyalty` through to the slip.

`CardRepository.ensureSchema` migrates an older corpus in place and backfills
`type_mask` from the stored type lines, so a database built before this change
still works — it simply holds creatures only until it is rebuilt.

**The print-mode row is gone** from the main screen; it is a switch in Settings
("Artwork on slips") now. So is the corpus counter that sat under the wordmark —
the numbers are in the Settings diagnostics block, and sync progress is the sync
icon rotating.

**Bugs fixed.** Per-frame shader allocation in `GlowOverlay` and per-frame string
and text-measure in `ManaWheel`; the printer is now asked *before* the success
animation plays; printing stops at the first failure; the round button no longer
accepts taps in the corners of its square; one haptic per press instead of two;
long-press-to-preview moved from 500 ms to 1200 ms; the busy button answers a
press instead of ignoring it; colourless is no longer indistinguishable from
white; multicolour stripes are flat bands instead of a smear; `lastManaValue` is
written on pause rather than on every notch of a fling; the token query moved off
the UI thread; the scanner's `MaterialButton` (black-on-black) became a TextView.

**`build-tokens` covers every card type**, not just creatures. This matters more
than it sounds: 3,796 token references from 3,615 cards onto 733 distinct tokens,
against 2,060 / 1,976 / 532 before.

## In flight — the corpus

`build-db` and `build-tokens` are done. **`build-art` was still running** when
this was written, about 1,250 of 13,127 artworks in, roughly 20 minutes left.

It is resumable and it lives outside git (`tools/momirdeck/out/` is ignored, the
files are ~430 MB of derived data). To carry on:

```bash
cd tools/momirdeck
python momirdeck.py build-art        # picks up where it stopped
python momirdeck.py stats
python momirdeck.py push             # adb push to the V2
```

On a machine with no corpus at all, pull the one off the device first rather
than downloading 250 MB of artwork again — and put it in **`out/`**, which is
where momirdeck's default `--db` and `--pack` point:

```bash
adb pull /sdcard/Android/data/software.zeasy.momir/files/momir.db  tools/momirdeck/out/
adb pull /sdcard/Android/data/software.zeasy.momir/files/art.pack  tools/momirdeck/out/
```

Getting that path wrong is what cost half an hour this session: `build-db` saw
an empty database, reported every card as new, and `build-art` started re-fetching
all 31,156 artworks instead of the 13,127 that were missing.

Expected result: **30,423 cards** — 17,497 creatures, 3,620 instants, 3,562
enchantments, 3,507 artifacts, 3,385 sorceries, 287 planeswalkers, 36 battles.
Plain lands are deliberately excluded. `art.pack` ends up around 410 MB against
1.4 GB free on the device.

## The device

Sunmi V2, serial `VB5221AU20249`, Android 7.1, 720 × 1440 at 320 dpi, so 1 dp = 2 px.

- Build: `JAVA_HOME` = `C:\Program Files\Android\Android Studio\jbr` (JDK 21),
  `ANDROID_HOME` = `%LOCALAPPDATA%\Android\Sdk`, then `.\gradlew.bat assembleDebug`.
- Install with `adb install -r`. **Never uninstall** — that deletes the corpus in
  `/sdcard/Android/data/software.zeasy.momir/files/`. If a signature mismatch
  forces one (it did once, the previous install came from another machine's debug
  keystore), move `momir.db` and `art.pack` somewhere else under `/sdcard` first;
  within the same volume that is a rename, not a 250 MB copy.
- Screenshots: `adb shell screencap -p /sdcard/s.png` then `adb pull`. Do **not**
  pipe `adb exec-out screencap` into a PowerShell redirect, it corrupts the file.
- **Long-press the print button to render a slip without printing it.** It writes
  `preview.png` into the app's files directory and plays the same animation. Use
  it for every layout change; do not burn paper to look at a layout.
- The device's owner settings: Creatures, mana value 7, artwork on. Leave them
  as you find them.

## What is still open

### The slip — from the print audit, in value order

1. **Every slip has 12 mm of blank paper at the head and 0.75 mm at the foot.**
   The tear bar sits 96 dots downstream of the head, so the first 96 dots of a
   new slip are paper that already passed the head and can never be printed on.
   Against that, `bottomPad` is 6 dots. Fix by splitting `Settings.contentBudgetDots`
   into a content budget and a print feed: feed *more* than the head-to-tear
   distance and the excess becomes a foot margin. `bottomMarginMm = 5f` gives 568
   content + 136 feed = the same 704 dots, with 12 mm / 5 mm margins. Verify with
   a ruler before committing. Note also that every image in `docs/images/` is a
   `preview.png` — the 608-dot canvas, never the 704-dot object that comes out.
2. **Artwork mode bottoms out its own fallback ladder on ordinary cards.** 269 of
   608 dots are spent before any art or rules text; the renderer lands on 17 px
   text with the art cropped to 50 %. The header QR is the main culprit: it costs
   50–78 dots, not the "about 2 mm" claimed in `docs/printing.md` and the
   `SlipRenderer` KDoc — that figure is wrong and should be corrected. Options:
   pull the type line up beside the QR, or knock the QR into the artwork's
   bottom-right corner for zero length.
3. **Floor the text ladder at 20 px.** Below that, thermal bleed closes the
   counters of `a`, `e`, `s`, `8`. Buy the space by dropping parenthetical
   reminder text *before* shrinking type — some cards spend four of six lines on
   a reminder every player knows.
4. **Abilities run together.** `\n` between two abilities is typographically
   identical to a wrap. Give each ability its own layout with a ~7-dot gap.
   Italicise reminder text (`sans-serif` italic ships with Android). Strip the
   braces: `{2}{U}, {T}:` is twelve characters for three symbols.
5. **P/T is 23 px at the end of the type line**, where a real card puts it in a
   box you find without looking. 32 px in a stroked box costs nothing, the row
   already reserves the height.
6. **Colour belongs in the type line**, as the adjective players already say —
   "Blue Creature — Human Wizard" — not on a 19 px subline. Saves 25 dots.
7. `setBreakStrategy(HIGH_QUALITY)` and hyphenation on the rules block: the
   32-character measure rags badly with the default greedy wrap.
8. Two alignment defects: the card name sits 7 dots above the badge's optical
   centre in a one-line header, and P/T rides 3.5 dots below the type line's
   baseline.
9. **QR sizes should trade.** The body code is over-specified at 8.3 px/module
   and eats ~60 % of a vanilla slip; the header code is marginal at 3.6. And
   `QR_QUIET_MODULES = 2` where the spec says 4.
10. **`Dither.kt` diverges from `momirdeck.py`**: the device path is missing the
    `autocontrast` pass and scales bilinear where Pillow uses Lanczos. A corpus
    built on a PC and one topped up by an on-device resync print at visibly
    different densities — exactly what both files' comments claim to prevent.
    Fix on the device side. Do not touch the Python dither while a build is
    running, or the pack ends up with two sets of parameters in it.
11. Bugs: the fallback path sizes the QR against space it has not got
    (`budget - fixed`, missing `- rulesHeight`) and starves the text on exactly
    the card that needed room; `maxLines.coerceAtLeast(1)` can draw past the
    bitmap; a slip with no body leaves the whole slack as blank tail; `fitToLines`
    can set a card name smaller than the type line.

### The app — from the UI audit

- **Nine error states are stock Android toasts**, including printer-not-connected
  and print-failed. The result panel is already anchored, animated and
  self-retiring; reuse it with a danger-coloured stripe and delete the toasts.
  Add a printer-state dot in the header — `SunmiPrinter.onServiceDisconnected`
  already fires and does nothing.
- **Thirteen text sizes and nine letter-spacings.** `TextAppearance.Momir.CardName`
  declares 19sp and is overridden at all four call sites. Collapse to a scale in
  `themes.xml` and delete the per-view overrides. This is the single clearest
  sign the design is tuned rather than systematic.
- The seal's face is darker than the background it sits on, and the gems are at
  alpha 115 — the colour wheel is invisible until something is printed, which is
  after it would have explained anything.
- The glow's halo is brightest at the corners; the paper comes out of the top.
  Four gradient-filled rects, weighted to the top edge, would say so and cost
  less fill than fourteen stroked round-rects.
- The token sheet is a floating dialog over the result panel, so the card name
  appears twice at once. Make it a real bottom sheet.
- The empty state prints `no_corpus_title` twice.
- `ManaWheel.VISIBLE_ITEMS = 5` is aspirational — the layout gives it room for
  three, which reads as a stepper rather than a drum.

### Loose ends

- **README does not mention the card-type modes yet.** It still describes a
  creature-only app.
- `docs/images/app.png`, `glow.png` and `tokens.png` are current as of the design
  work but predate the planeswalker symbol on the button — re-shoot them.
- The corner-tap fix, the printer pre-check and the circular hit test were all
  verified on the device; the slip changes above have not been tested on paper at
  all.

## Scratch

The SVG tracing script that produced `PrintButton.SIGIL` is not in the repo — it
was a one-off. If the symbol ever needs re-tracing: parse the path properly, the
source mixes `c` and `l` commands and reading a lineto as part of a curve shifts
every number after it by two. Promote lines to cubics so the Kotlin side keeps a
single segment type, undo the negative Y scale in the SVG's transform, normalise
into a box 100 tall, and check mirror symmetry as a sanity test — the correct
outline came out symmetrical to within 0.18 units.
