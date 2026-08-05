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

### `ui`

`ManaWheel` is a drum picker. Items sit on a virtual cylinder; distance from
centre becomes an angle, and that one angle drives vertical position (through
`sin`, so items bunch towards the rim), size and opacity (`cos`). That single
mapping is what makes it read as rotation rather than a fading list.

It only offers mana values that exist. A dial you can spin to 14 — where Magic
has never printed a creature — is a bug you can land on.

`PrintButton` is a ~200 dp circular target, because it gets pressed with a thumb
while the other hand holds cards. Long-pressing it renders a preview instead of
printing.

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
