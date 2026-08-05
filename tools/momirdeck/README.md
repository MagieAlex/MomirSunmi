# momirdeck

Builds the offline card corpus for MomirSunmi. Stdlib plus Pillow, nothing else.

```bash
pip install Pillow

python momirdeck.py build-db        # creatures        ~2 s
python momirdeck.py build-tokens    # what they make   ~1 min
python momirdeck.py build-art       # artwork          ~30 min, resumable
python momirdeck.py stats           # what you got
python momirdeck.py push            # adb push to the device
```

Output goes to `out/` (git-ignored — it is 249 MB of derived data, rebuild it
rather than commit it).

## Commands

### `build-db`

Streams Scryfall's `oracle_cards` JSONL export and writes every Momir-legal
creature to `momir.db`. Prints a mana value histogram when done.

Re-running merges: existing rows are refreshed, new cards inserted, and cards
that no longer qualify are removed. Artwork offsets are never touched, so a
rebuild does not orphan the pack. `--no-prune` keeps rows that stopped
qualifying.

### `build-tokens`

Reads `all_parts` off each creature to find the tokens it creates, then resolves
them through `/cards/collection` in batches of 75. Populates `tokens` and
`card_tokens`.

Run it after `build-db`, then run `build-art` again to fetch the token artwork.

### `build-art`

Downloads `art_crop` for everything without artwork, dithers it to 384 px
1-bit, and appends it to `art.pack`.

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

- The filter that decides what counts as a Momir-legal creature is documented in
  [../../docs/data-pipeline.md](../../docs/data-pipeline.md). It is subtler than
  it looks, and it is mirrored in the Android app's resync — change both.
- Please leave the rate limiting and the `User-Agent` alone. Scryfall gives this
  data away for free and asks for very little in return.
