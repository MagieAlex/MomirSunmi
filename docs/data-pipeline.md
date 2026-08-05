# Data pipeline

How Scryfall's bulk export becomes 249 MB of offline card data, and — more
importantly — exactly which cards count as Momir-legal and why.

## The three build steps

```bash
python momirdeck.py build-db       # creatures        ~2 s
python momirdeck.py build-tokens   # what they make   ~1 min
python momirdeck.py build-art      # artwork          ~30 min, resumable
python momirdeck.py stats          # what you ended up with
python momirdeck.py push           # adb push to the device
```

Output lands in `tools/momirdeck/out/`:

| File | Size | Contents |
|---|---|---|
| `momir.db` | 11 MB | 17,497 creatures, 532 tokens, 2,060 creature→token links |
| `art.pack` | 238 MB | 18,029 pre-dithered 1-bit artworks |

## Streaming, not loading

Scryfall now publishes bulk data as **gzipped JSONL** — one complete card object
per line. The Oracle export is 23 MB compressed, about 180 MB expanded.

That format is what makes on-device resync possible at all. The V2 has 909 MB of
RAM with roughly 340 MB free; parsing 180 MB as a single JSON array would kill
the app. As JSONL it is a loop over `readLine()` and peak memory is one card.

Both the Python builder and the Kotlin resync stream it the same way. (Both also
keep a fallback path for the legacy single-array form.)

## Which cards count

The intent is Scryfall's `t:creature -is:alchemy -is:funny -is:digital`. Getting
there took two corrections that are worth writing down, because the obvious
implementation is wrong in both directions.

### Paper-ness comes from legality, not the `digital` flag

The natural filter is `card["digital"] == false` and `"paper" in card["games"]`.
It is wrong, and it silently drops real cards.

`oracle_cards` contains **one representative printing per card**, and Scryfall
picks "the most up-to-date recognizable version". For older cards that is often
an MTGO-only reprint. Memnite's representative printing is an MTGO duel deck:

```
Memnite               set=td2  set_type=duel_deck  digital=True   games=[mtgo]
Shield Sphere         set=me1  set_type=masters    digital=True   games=[mtgo]
Kobolds of Kher Keep  set=me3  set_type=masters    digital=True   games=[mtgo]
```

All three are perfectly normal pieces of cardboard. The naive filter threw them
out, along with 200-odd others.

Legality is printing-independent, so that is what the filter uses:

```python
PAPER_LEGALITY_FORMATS = ("vintage", "legacy", "commander")

def is_paper_card(card):
    legalities = card.get("legalities") or {}
    return any(legalities.get(f, "not_legal") != "not_legal"
               for f in PAPER_LEGALITY_FORMATS)
```

This also fixes the *other* direction. Mystery Booster playtest cards like
`Gobland` are `games=[paper]` and `digital=false` — they genuinely exist on
cardboard — but they were never tournament-legal and are not real Magic cards.
`vintage=not_legal` catches them.

Alchemy rebalances fall out for free: they are legal only in `alchemy`,
`historic` and `timeless`, so none of the three paper formats matches.

### Transforming cards are judged by their front face

Scryfall's `t:creature` matches a card if *either* face is a creature. That
returns things like:

```
Westvale Abbey // Ormendahl, Profane Prince   [transform]  Land // Creature
Azusa's Many Journeys // Likeness of Seeker   [transform]  Saga // Creature
```

Westvale Abbey is a land. Momir Vig copies creature *cards*, and a card's type
is its front face. So the filter uses the front face only:

```python
BOTH_HALVES_ON_FRONT = {"split", "adventure", "flip"}   # both halves are printed on the front

def type_line_for_filter(card):
    if card.get("layout") in BOTH_HALVES_ON_FRONT:
        return card.get("type_line", "")     # whole line counts
    return front_face(card).get("type_line", "")
```

This is a **deliberate divergence** from Scryfall's count, worth exactly 78
cards.

### Verifying it

The corrected filter's per-mana-value counts match Scryfall's search API exactly
at the top end, where the numbers are small enough to check by hand:

| Mana value | This filter | Scryfall `unique=cards` |
|---:|---:|---:|
| 10 | 33 | 33 |
| 11 | 12 | 12 |
| 12 | 11 | 11 |
| 13 | 1 | 1 |
| 15 | 4 | 4 |
| 16 | 1 | 1 |
| 0 | 21 | 23 |

The two extra at mana value 0 are Westvale Abbey and Hostile Hostel — the
transform-card divergence above, showing up precisely where it should.

There is no creature at mana value 14, and none above 16. The dial reflects
that.

### Everything else the filter drops

```python
EXCLUDED_LAYOUTS   = {token, double_faced_token, emblem, art_series, vanguard,
                      scheme, planar, augment, host, reversible_card}
EXCLUDED_SET_TYPES = {funny, memorabilia, token, minigame, alchemy}
```

Plus names starting `A-` (Alchemy rebalances) and type lines containing `Token`.

> **If you change any of this**, change it in *both* `tools/momirdeck/momirdeck.py`
> and `app/src/main/java/software/zeasy/momir/sync/ScryfallSync.kt`. A resync
> that disagrees with the pushed corpus is a confusing bug to chase.

## Tokens

Scryfall models "what does this card create" as `all_parts` — a list of related
objects, of which the ones with `component == "token"` are what the card puts
onto the battlefield.

Across all 17,497 creatures:

```
2,060 token references from 1,976 creatures (11.3 %)
  979 distinct token printings referenced
  532 distinct tokens after collapsing to Oracle identity
```

The catch: `all_parts` references tokens by **printing id**, not Oracle id, so
it cannot be joined against `oracle_cards` directly — that file holds a
*different* printing of the same token.

Rather than stream the 74 MB all-printings export just to build an id map,
`build-tokens` collects the referenced printing ids (under a thousand across the
whole game) and resolves them through `/cards/collection` in batches of 75.
Thirteen requests.

Then printing-level edges collapse to Oracle-level ones, so two creatures
pointing at two different printings of the same 2/2 black Zombie end up sharing
one token row.

## Artwork

`build-art` walks every card and token without artwork, downloads the
`art_crop`, and produces a packed 1-bit raster. Details of the dithering are in
[printing.md](printing.md).

It is resumable by construction: the pack is append-only and offsets are
committed to the database every 250 images. If it dies, run it again.

Rate limiting is a global 10 requests/second, which is what Scryfall asks for.
17,497 images take about 29 minutes. Please do not raise it.

## The art pack format

```
offset  0   magic "MOMIRART"    8 bytes
offset  8   version             u16 LE
offset 10   width in dots       u16 LE   (384)
offset 12   reserved            u32 LE
offset 16   blobs, back to back
```

Each blob is `48 × height` bytes: packed 1 bpp, MSB first, **1 = burn**. That is
byte-for-byte what `GS v 0` wants, so printing artwork involves no decoding and
no bitmap allocation.

`build-db` prunes rows that no longer qualify. Their artwork stays in the pack as
dead space — cheaper than rewriting a 238 MB file for a few hundred rows.

## On-device resync

The Resync button does the same work over WiFi:

1. `GET /bulk-data`, compare `updated_at` against what the corpus recorded. If
   unchanged, skip straight to artwork.
2. Stream the Oracle JSONL, filter, insert what is new.
3. Fetch, dither and append artwork for anything missing, at 10 requests/second.

A set release adds a few hundred creatures, so in practice this is a couple of
minutes. Cards are never deleted on-device — pruning only happens in the PC
builder, where it is easy to verify.
