# momirdeck

Builds the offline card corpus for MomirSunmi. Stdlib plus Pillow, nothing else.

```bash
pip install Pillow

python momirdeck.py build-db        # every rollable card   ~4 s
python momirdeck.py build-tokens    # what creatures make   ~1 min
python momirdeck.py build-art       # artwork               ~50 min, resumable
python momirdeck.py stats           # what you got
python momirdeck.py push            # adb push to the device
```

Output goes to `out/` (git-ignored — it is some 430 MB of derived data, rebuild
it rather than commit it).

## Commands

### `build-db`

Streams Scryfall's `oracle_cards` JSONL export and writes every rollable card to
`momir.db`: creatures, artifacts, enchantments, planeswalkers, battles, instants
and sorceries. Roughly 30,300 of them. Prints a type breakdown and a mana value
histogram when done.

Cards whose only type is land are left out on purpose — nobody rolls a random
Forest, and the artwork would cost build time and device storage for nothing. A
land creature still gets in, on its creature bit.

Each row carries a `type_mask` of the front face's types (an artifact creature is
`CREATURE|ARTIFACT`), and planeswalkers carry their starting loyalty. Both are
mirrored in the app; the bit values are in
[../../docs/data-pipeline.md](../../docs/data-pipeline.md) and must never be
renumbered.

Re-running merges: existing rows are refreshed, new cards inserted, and cards
that no longer qualify are removed. Artwork offsets are never touched, so a
rebuild does not orphan the pack. `--no-prune` keeps rows that stopped
qualifying.

Opening a `momir.db` built before a column existed migrates it in place. For
`type_mask` that includes a backfill from the stored type lines, so an old
corpus keeps working — as the creature-only corpus it is — until the next
`build-db` widens it.

### `build-tokens`

Reads `all_parts` off each creature to find the tokens it creates, then resolves
them through `/cards/collection` in batches of 75. Populates `tokens` and
`card_tokens`.

Creatures only, deliberately: the device shows the token sheet as part of a
creature's slip, and no other type asks for it.

Run it after `build-db`, then run `build-art` again to fetch the token artwork.

### `build-art`

Downloads `art_crop` for everything without artwork, dithers it to 384 px
1-bit, and appends it to `art.pack`. It works off "no artwork yet" rather than
off a type, so the cards that arrived with the widened filter need nothing
special — run it again and it fetches the ~12,800 new ones, about twenty minutes
and 170 MB.

Resumable by construction — the pack is append-only and offsets commit every 250
images. If it dies, run it again.

```
--workers N           parallel downloads (default 8)
--rps N               global rate limit (default 10, which is what Scryfall asks)
--limit N             only fetch N, for a quick trial run
--max-art-height N    dots; keeps slips inside a sleeve (default 300)
--gamma F             <1 brightens midtones before dithering (default 0.85)
--contrast F          default 1.25
```

`--gamma` and `--contrast` are the knobs to reach for if prints come out muddy
or too dark. They only affect images fetched from then on, so delete `art.pack`
and clear the offsets if you want to re-dither everything.

### `push`

`adb push` of both files to
`/sdcard/Android/data/software.zeasy.momir/files/`. Finds `adb` on `PATH` or in
the usual SDK locations; `--adb` overrides.

## Global options

```
--db PATH      default out/momir.db
--pack PATH    default out/art.pack
```

## Notes

- The filter that decides what gets into the corpus, and the type bits that
  decide what a roll can return, are documented in
  [../../docs/data-pipeline.md](../../docs/data-pipeline.md). It is subtler than
  it looks, and it is mirrored in the Android app's resync — change both.
- Please leave the rate limiting and the `User-Agent` alone. Scryfall gives this
  data away for free and asks for very little in return.
