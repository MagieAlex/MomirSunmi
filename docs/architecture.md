# Architecture

## The shape of the thing

```
   YOUR PC                          THE SUNMI V2
   ┌────────────────────┐           ┌──────────────────────────────────┐
   │ momirdeck.py       │  adb push │  momir.db     11 MB   SQLite     │
   │  build-db          ├──────────►│  art.pack    238 MB   1-bit art  │
   │  build-tokens      │           │                                  │
   │  build-art         │           │  ┌────────────────────────────┐  │
   └─────────┬──────────┘           │  │ app                        │  │
             │                      │  │   dial → random card       │  │
             │ bulk JSONL           │  │   renderer → 384px raster  │  │
             ▼                      │  │   ESC/POS → AIDL           │  │
      ┌─────────────┐               │  └─────────────┬──────────────┘  │
      │  Scryfall   │◄──────────────┤  resync        │                 │
      └─────────────┘   HTTPS       │                ▼                 │
                                    │        woyou.aidlservice.jiuiv5  │
                                    │                │                 │
                                    │                ▼   58 mm paper   │
                                    │           print head             │
                                    └──────────────────────────────────┘
```

There is no server. The PC-side builder is a convenience — it does in half an
hour what the device would take considerably longer to do — but the device can
build its whole corpus itself over WiFi if you let it.

## The device sets the constraints

Everything unusual about this codebase traces back to the hardware:

| | |
|---|---|
| Android | **7.1.1, API 25** |
| CPU | **armeabi-v7a**, 32-bit only |
| RAM | **909 MB** total, ~340 MB actually free |
| Free storage | **1.8 GB** on `/data` |
| Screen | 720 × 1440, 320 dpi |
| Printer | 58 mm thermal, **384 dots** printable, 203 dpi |

Consequences that show up throughout the code:

- **Views and Canvas, not Compose.** Compose runs on API 21+, but a
  recomposition loop on this CPU is felt on every frame. The dial and the button
  are custom `View`s with `onDraw`.
- **Everything streams.** The Scryfall Oracle export is ~180 MB expanded. It is
  never held in memory; the resync reads it a line at a time from a JSONL
  stream. See [data-pipeline.md](data-pipeline.md).
- **Artwork is pre-rendered.** Decoding a JPEG and dithering it at print time
  would cost hundreds of milliseconds per slip. Instead the bytes in `art.pack`
  *are* the printer payload — one seek, one read, straight out to the head.
- **One pack file, not 17,000 images.** eMMC is slow at opening small files, and
  4 KB block granularity would waste ~40 % on 13 KB images.

## Modules

### `data`

`CardRepository` opens `momir.db` from the app's external files directory —
chosen so `momirdeck push` can drop a fresh corpus over adb without touching the
APK, and so no runtime permission is needed.

`ArtPack` is a `RandomAccessFile` over one append-only blob. The database stores
`(offset, length, height)` per card; reading artwork is a seek and a read.

Schema is created on open with `CREATE TABLE IF NOT EXISTS`, so a corpus built
by an older `momirdeck` still opens — it simply has no token rows until the next
build or resync.

Each card carries a `type_mask`: one bit per card type, every bit its front-face
type line names, so an artifact creature is `CREATURE|ARTIFACT` and answers to
both. A category is a mask, and selecting one is `type_mask & :mask != 0` over
`ix_cards_type_mv(type_mask, mv)`. The bit values live in `CardTypes` and are
shared with the Python builder; the categories that combine them are
`CardCategory`, which also carries the label and the plural nouns so the screen
cannot say "creatures" while the dial is rolling planeswalkers. Planeswalkers
also carry `loyalty`, because a planeswalker slip without its starting loyalty
is not something you can play with.

`ensureSchema` adds both columns to a corpus that predates them, adds the index
*after* the column it names, and backfills `type_mask` from the `type_line`
already stored — one `UPDATE`, one scan, reading the types off the part before
the em dash. Such a corpus holds creatures only, so afterwards Creatures and
Permanents are populated and the rest are honestly empty until it is rebuilt.

### `print`

The interesting part.

- `SlipRenderer` lays a card out on a 384-dot-wide canvas and converts it to
  packed 1-bit rows. It enforces the sleeve-length budget by walking a ladder of
  fallbacks rather than scaling. See [printing.md](printing.md).
- `SlipContent` is what both a `Card` and a `Token` collapse into, so one layout
  path serves both.
- `EscPos` builds the handful of commands needed: reset, align, raster, feed.
- `SunmiPrinter` binds the AIDL service and pushes one payload per slip. See
  [sunmi-aidl.md](sunmi-aidl.md) for why the interface is trimmed.
- `QrCode` wraps ZXing's encoder — deliberately the low-level `Encoder`, not
  `QRCodeWriter`, so modules land on exact dot boundaries.

### `sync`

`ScryfallSync` streams the bulk export, filters it with the same rules the
Python builder uses, and inserts what is new. Then it fetches artwork for cards
that lack it and dithers on-device with the same Floyd-Steinberg parameters, so
artwork pulled by a resync is indistinguishable from artwork built on a PC.

It runs inside a foreground `Service` with a wake lock. A partial sync is
harmless — the corpus only grows and the pack is append-only — but one that dies
at card 9,000 every time is not useful.

On screen a running sync is just the sync icon turning; the stage and the
progress bar live in its notification, which is where they can still be read
after the app has been swiped away. The activity applies the last status when it
re-attaches its listener, so coming back to a sync that has been running for
twenty minutes still shows something turning.

### `ui`

The screen is dressed as a Magic card rather than as an Android app: warm blacks,
a brass frame gold, a serif for anything that names a card, and hairlines that
fade out at their ends the way a card frame's rules do. None of it costs
anything at draw time — the palette is `colors.xml`, the frame is four `<shape>`
drawables, and the two custom views below build everything from circles,
gradients and cached shaders.

The header is the wordmark and three actions, and nothing else. It used to carry
a corpus counter — cards, artworks, tokens — which was static, was a boast, and
occupied the most valuable strip on the screen. The same numbers are in the
settings diagnostics, which is where someone who wants them goes looking.

The category chips under the header say what the dial rolls: permanents,
creatures, artifacts, enchantments, planeswalkers or spells. Six of them are
about twice the width of the screen, so the row scrolls, with a fading edge to
say so and the selected chip scrolled to the middle whenever it changes — a
choice you cannot see is a choice you assume was lost. The selected chip wears
the same brass plate a card's title bar is struck from; the rest are bare type.

Changing the category re-reads the mana value buckets, the counts and the noun,
because they all belong to it: planeswalkers start at mana value 2, sorceries
stop long before creatures do. The dial lands on the nearest value the new
category actually has rather than snapping to the bottom. A category the corpus
cannot serve at all gets an empty dial, a disabled print button and a line
saying so.

There is no print-mode control on the main screen. An artwork slip carries a QR
too, so the choice is only whether the middle of the slip is a picture or a big
code — not worth a permanent row on the one screen that matters. It is a switch
in settings, and it writes the same `PrintMode` the renderer has always taken.

`ManaWheel` is a drum picker. Items sit on a virtual cylinder; distance from
centre becomes an angle, and that one angle drives vertical position (through
`sin`, so items bunch towards the rim), size and opacity (`cos`). That single
mapping is what makes it read as rotation rather than a fading list.

Each value is drawn as the generic mana symbol it is: a pale stone disc, lit from
the upper left, with the number struck into it in a dark serif. One
`RadialGradient` serves all five visible discs — each is drawn at the origin and
placed by translating and scaling the canvas, so the shader rides along in the
item's own coordinate space instead of being rebuilt per size.

It only offers mana values that exist. A dial you can spin to 14 — where Magic
has never printed a creature — is a bug you can land on.

`PrintButton` is a ~200 dp circular target, because it gets pressed with a thumb
while the other hand holds cards. Long-pressing it renders a preview instead of
printing.

It is drawn as a struck seal: a brass rim carrying the five colours of Magic as
gems, WUBRG clockwise from the top, over a stone face lit from above. The
pentagon is the colour wheel every player already has in their head, and it says
what the button rolls without a word of explanation. When a creature comes back,
its own colours light and the rim *keeps* them until the result panel retires, so
a glance at the button says what just came out of the printer.

Cost: circles, arcs, one `RadialGradient` for the face and one `LinearGradient`
across the rim, both cached in `onSizeChanged`. Nothing is allocated in
`onDraw`, and the press dip is a canvas transform rather than recomputed
geometry, which is what lets those cached shaders stay valid at every scale. The
idle breath on the rim only runs while the window has focus, and only repaints
when its value has moved enough to see.

`GlowOverlay` is the print animation: the screen edge takes the card's colour
identity and a band of it sweeps up and off the top edge, which on a V2 is where
the paper emerges.

The obvious way to draw a glow is `BlurMaskFilter`. It is also unsupported by
the hardware-accelerated canvas, so reaching for it silently forces the view
into a software layer — a full-screen 720 × 1440 bitmap re-rasterised on the CPU
every frame, which on this chip is a slideshow. Everything in the overlay is
built from gradients and stacked translucent fills instead: the edge halo is
fourteen rounded-rect strokes of growing width and cubed-falloff alpha, the band
is thin rects whose alpha follows a bell curve, and a multicolour identity
becomes a `SweepGradient` around the perimeter. Measured on-device during the
animation: ~35 fps.

Colours come from `ManaColors`, which is deliberately not the printed frame
palette — those are read by reflected light off white cardboard and come out
muddy as emitted light on a near-black screen. Black gets violet, because there
is no such thing as a black glow and every digital Magic client has made the
same substitution for twenty years.

`TokenSheet` is the sheet a creature's tokens come up in — from the panel at the
bottom of the screen, or straight from a scan. It carries a stamp saying which of
those two it was, because a scan happens with the camera in your hand and no
other context on screen. Each token is shown as a token (colour stripe, P/T,
name, type line), and the quantity stepper next to the print button is there
because a Rhys the Redeemed player wants six Elves, not six trips through a
dialog. The layout renders once no matter how many slips come out of it.

Its buttons are `TextView`s, not `Button`s: under a MaterialComponents theme
every `<Button>` is inflated as a `MaterialButton`, which brings its own
background and text colour and quietly ignores the style it was given — which is
how a print action ends up as black text on a black slab.

The result panel at the bottom of the main screen retires itself: 15 seconds
after a print, or 45 if the card makes tokens, since then the panel is also the
only way back into the sheet. It fades to `INVISIBLE` rather than `GONE`, because
the print button is anchored to it and would otherwise drop down the screen.

`ScannerActivity` uses `android.hardware.Camera` on purpose: on API 25, Camera2
runs at LEGACY hardware level, which is the old pipeline behind a newer
interface plus a state machine you do not need. Camera1's preview callback hands
over an NV21 buffer, which is exactly the luminance plane ZXing wants.

## Threading

Every database query, layout pass, dither blit and Binder round-trip happens off
the main thread via coroutines on `Dispatchers.IO`. On this device that is the
difference between a dial that spins and one that stutters whenever you print.

The printer connection is bound once and held for the life of the activity —
binding costs a few hundred milliseconds, and a Momir game is many small prints
in quick succession.
