package software.zeasy.momir.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.util.Log
import java.io.File

/**
 * Read/write access to momir.db.
 *
 * The database is not bundled in the APK. It lives in the app's external files
 * directory so that `momirdeck push` can drop a fresh 200 MB corpus onto the
 * device over adb without rebuilding anything, and so a resync can grow it in
 * place. Nothing here needs a runtime permission: an app always owns
 * getExternalFilesDir().
 */
class CardRepository(private val context: Context) {

    private var db: SQLiteDatabase? = null

    val databaseFile: File
        get() = File(context.getExternalFilesDir(null), DB_NAME)

    val artPackFile: File
        get() = File(context.getExternalFilesDir(null), ART_NAME)

    val isReady: Boolean get() = db != null

    /** Opens the corpus. Returns false if it has not been pushed to the device yet. */
    fun open(): Boolean {
        if (db != null) return true
        val file = databaseFile
        if (!file.exists()) {
            Log.w(TAG, "No corpus at ${file.absolutePath}")
            return false
        }
        return try {
            db = SQLiteDatabase.openDatabase(
                file.absolutePath, null,
                SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
            )
            ensureSchema()
            true
        } catch (e: SQLiteException) {
            Log.e(TAG, "Cannot open corpus", e)
            db = null
            false
        }
    }

    fun close() {
        db?.close()
        db = null
    }

    /**
     * Creates anything a corpus built by an older momirdeck is missing, so a
     * database pushed before tokens existed still opens and still works - it
     * just has no token rows until the next build or resync.
     */
    private fun ensureSchema() {
        val database = db ?: return
        database.execSQL(
            """CREATE TABLE IF NOT EXISTS tokens (
                   oracle_id TEXT PRIMARY KEY, name TEXT NOT NULL,
                   type_line TEXT NOT NULL DEFAULT '', oracle_text TEXT NOT NULL DEFAULT '',
                   power TEXT, toughness TEXT, colors TEXT NOT NULL DEFAULT '',
                   scryfall_uri TEXT NOT NULL DEFAULT '', art_uri TEXT NOT NULL DEFAULT '',
                   art_off INTEGER, art_len INTEGER, art_h INTEGER)"""
        )
        database.execSQL(
            """CREATE TABLE IF NOT EXISTS card_tokens (
                   card_oracle_id TEXT NOT NULL, token_oracle_id TEXT NOT NULL,
                   PRIMARY KEY (card_oracle_id, token_oracle_id))"""
        )
        database.execSQL("CREATE INDEX IF NOT EXISTS ix_card_tokens ON card_tokens(card_oracle_id)")
        database.execSQL("CREATE INDEX IF NOT EXISTS ix_cards_uri ON cards(scryfall_uri)")

        // Columns added after the first release. CREATE TABLE IF NOT EXISTS does
        // not add them to a table that already exists, so an older corpus needs
        // them applied by hand. The value stays empty until the next build or
        // resync, which just means a colourless print animation.
        addColumnIfMissing("cards", "color_identity", "TEXT NOT NULL DEFAULT ''")
        addColumnIfMissing("cards", "type_mask", "INTEGER NOT NULL DEFAULT 0")
        addColumnIfMissing("cards", "loyalty", "TEXT")
        // After the column, never before it: on a corpus that predates type_mask
        // the index would name a column that is not there yet and the statement
        // would fail, leaving every later query without it.
        database.execSQL("CREATE INDEX IF NOT EXISTS ix_cards_type_mv ON cards(type_mask, mv)")
        backfillTypeMask()
    }

    /**
     * Gives a pre-category corpus its type bits back, read off the type line it
     * already stores.
     *
     * Such a corpus holds creatures and nothing else, so afterwards Creatures
     * and Permanents are populated and the other categories are empty - which is
     * the honest answer until the corpus is rebuilt.
     *
     * One statement, one scan. Doing it row by row would be 17,000 updates on
     * eMMC, and doing it per distinct type line would be a scan per type line.
     */
    private fun backfillTypeMask() {
        val database = db ?: return
        if (scalar("SELECT COUNT(*) FROM cards WHERE type_mask = 0") == 0) return

        // Everything before the dash: the types, without the subtypes and
        // without the far side of a "//". Mirrors CardTypes.maskOf.
        val dash = CardTypes.SUBTYPE_DASH
        val cut = "CASE WHEN instr(type_line, '$dash') > 0 " +
            "THEN instr(type_line, '$dash') - 1 ELSE length(type_line) END"
        val head = "substr(type_line, 1, $cut)"
        val bits = CardTypes.KEYWORDS.joinToString(" + ") { (word, bit) ->
            "(CASE WHEN $head LIKE '%$word%' THEN $bit ELSE 0 END)"
        }

        try {
            database.execSQL("UPDATE cards SET type_mask = $bits WHERE type_mask = 0")
            Log.i(TAG, "Migrated: backfilled cards.type_mask")
        } catch (e: SQLiteException) {
            Log.e(TAG, "Cannot backfill type_mask", e)
        }
    }

    private fun addColumnIfMissing(table: String, column: String, declaration: String) {
        val database = db ?: return
        val present = database.rawQuery("PRAGMA table_info($table)", null).use { c ->
            val nameIndex = c.getColumnIndex("name")
            generateSequence { if (c.moveToNext()) c.getString(nameIndex) else null }.toSet()
        }
        if (column in present) return
        try {
            database.execSQL("ALTER TABLE $table ADD COLUMN $column $declaration")
            Log.i(TAG, "Migrated: added $table.$column")
        } catch (e: SQLiteException) {
            Log.e(TAG, "Cannot add $table.$column", e)
        }
    }

    fun cardCount(): Int = scalar("SELECT COUNT(*) FROM cards")

    fun cardCount(category: CardCategory): Int =
        scalar("SELECT COUNT(*) FROM cards WHERE ${matching(category)}")

    fun tokenCount(): Int = scalar("SELECT COUNT(*) FROM tokens")

    fun artCount(): Int = scalar("SELECT COUNT(*) FROM cards WHERE art_off IS NOT NULL")

    fun artCount(category: CardCategory): Int =
        scalar("SELECT COUNT(*) FROM cards WHERE art_off IS NOT NULL AND ${matching(category)}")

    /**
     * A card belongs to a category if it carries any of its bits, so an artifact
     * creature answers to both. Interpolated rather than bound because the mask
     * is an Int off an enum - there is no string here to inject.
     */
    private fun matching(category: CardCategory) = "type_mask & ${category.mask} != 0"

    fun meta(key: String): String? {
        val database = db ?: return null
        database.rawQuery("SELECT v FROM meta WHERE k = ?", arrayOf(key)).use { c ->
            return if (c.moveToFirst()) c.getString(0) else null
        }
    }

    fun setMeta(key: String, value: String) {
        db?.execSQL("INSERT OR REPLACE INTO meta(k, v) VALUES (?, ?)", arrayOf(key, value))
    }

    /**
     * Every mana value this category actually has, with its count.
     *
     * Momir's X can be anything, but Magic has never printed a creature at MV 14
     * or above 16, a planeswalker below 2, or a sorcery anywhere near the top of
     * the range, so the wheel only ever offers values that can produce a card.
     */
    fun manaValueCounts(category: CardCategory): List<ManaValueBucket> {
        val database = db ?: return emptyList()
        val out = ArrayList<ManaValueBucket>(17)
        database.rawQuery(
            "SELECT mv, COUNT(*) FROM cards WHERE ${matching(category)} GROUP BY mv ORDER BY mv",
            null,
        ).use { c ->
            while (c.moveToNext()) out.add(ManaValueBucket(c.getInt(0), c.getInt(1)))
        }
        return out
    }

    /** The Momir Vig activation itself: a uniformly random card at this mana value. */
    fun randomCard(manaValue: Int, category: CardCategory): Card? {
        val database = db ?: return null
        database.rawQuery(
            "$SELECT_COLUMNS WHERE mv = ? AND ${matching(category)} ORDER BY RANDOM() LIMIT 1",
            arrayOf(manaValue.toString()),
        ).use { c ->
            return if (c.moveToFirst()) c.toCard() else null
        }
    }

    /**
     * Cards whose name contains [query], for the search screen.
     *
     * Ordered so that what you typed comes first: names that *start* with the
     * query, then the rest, alphabetically inside each. Typing "bolas" should
     * offer Bolas's Citadel before Nicol Bolas, God-Pharaoh, and typing "lotus"
     * should not bury Black Lotus under thirty cards with "Lotus" in the middle.
     *
     * A `LIKE '%...%'` cannot use an index, so this is a table scan over 30,000
     * rows. On the V2 that is a handful of milliseconds - it is still an IO
     * thread's job, but it does not need an FTS table to be one.
     */
    fun search(query: String, limit: Int = 60): List<Card> {
        val database = db ?: return emptyList()
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()

        // LIKE's own wildcards, typed by someone looking for a card called "50%".
        val escaped = trimmed.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        val out = ArrayList<Card>(limit)
        database.rawQuery(
            """
            $SELECT_COLUMNS
            WHERE name LIKE ? ESCAPE '\'
            ORDER BY (CASE WHEN name LIKE ? ESCAPE '\' THEN 0 ELSE 1 END), name
            LIMIT ?
            """.trimIndent(),
            arrayOf("%$escaped%", "$escaped%", limit.toString()),
        ).use { c ->
            while (c.moveToNext()) out += c.toCard()
        }
        return out
    }

    fun cardByOracleId(oracleId: String): Card? {
        val database = db ?: return null
        database.rawQuery("$SELECT_COLUMNS WHERE oracle_id = ?", arrayOf(oracleId)).use { c ->
            return if (c.moveToFirst()) c.toCard() else null
        }
    }

    /**
     * Resolves a scanned QR back to a card. No network involved.
     *
     * Three attempts, narrowest first:
     *
     *  1. Exact. Works when the stored URL is already slug-free.
     *  2. Prefix. Slips encode the shortened ".../card/set/number" form to keep
     *     the QR small, while the corpus stores Scryfall's full URL with the name
     *     slug on the end - so the scan is a prefix of the row.
     *  3. Slug suffix. Catches a slip printed before a corpus rebuild moved the
     *     card to a different representative printing: the set and number in the
     *     path change, the name at the end does not.
     */
    fun cardByScryfallUri(uri: String): Card? {
        val database = db ?: return null
        val cleaned = uri.substringBefore('?').trimEnd('/')

        database.rawQuery("$SELECT_COLUMNS WHERE scryfall_uri = ?", arrayOf(cleaned)).use { c ->
            if (c.moveToFirst()) return c.toCard()
        }

        // Indexed prefix scan - SQLite can use ix_cards_uri for LIKE 'literal%'.
        database.rawQuery(
            "$SELECT_COLUMNS WHERE scryfall_uri LIKE ? LIMIT 1",
            arrayOf("$cleaned/%"),
        ).use { c ->
            if (c.moveToFirst()) return c.toCard()
        }

        val slug = cleaned.substringAfterLast('/')
        if (slug.isBlank() || slug.toIntOrNull() != null) return null
        database.rawQuery(
            "$SELECT_COLUMNS WHERE scryfall_uri LIKE ? LIMIT 1",
            arrayOf("%/$slug"),
        ).use { c ->
            return if (c.moveToFirst()) c.toCard() else null
        }
    }

    /**
     * The tokens a given card can put onto the battlefield. Empty for the great
     * majority of them, and for everything that is not a creature - the builder
     * only links what Scryfall's `all_parts` says makes a token.
     */
    fun tokensFor(cardOracleId: String): List<Token> {
        val database = db ?: return emptyList()
        val out = ArrayList<Token>(2)
        database.rawQuery(
            """SELECT t.oracle_id, t.name, t.type_line, t.oracle_text, t.power, t.toughness,
                      t.colors, t.scryfall_uri, t.art_off, t.art_len, t.art_h
                 FROM tokens t
                 JOIN card_tokens ct ON ct.token_oracle_id = t.oracle_id
                WHERE ct.card_oracle_id = ?
                ORDER BY t.name""",
            arrayOf(cardOracleId),
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    Token(
                        oracleId = c.getString(0),
                        name = c.getString(1),
                        typeLine = c.getString(2),
                        oracleText = c.getString(3),
                        power = if (c.isNull(4)) null else c.getString(4),
                        toughness = if (c.isNull(5)) null else c.getString(5),
                        colors = c.getString(6),
                        scryfallUri = c.getString(7),
                        artOffset = if (c.isNull(8)) null else c.getLong(8),
                        artLength = if (c.isNull(9)) null else c.getInt(9),
                        artHeight = if (c.isNull(10)) null else c.getInt(10),
                    )
                )
            }
        }
        return out
    }

    /** Cards that still need artwork, oldest-first, for the resync to work through. */
    fun cardsMissingArt(limit: Int): List<Pair<String, String>> {
        val database = db ?: return emptyList()
        val out = ArrayList<Pair<String, String>>()
        database.rawQuery(
            "SELECT oracle_id, art_uri FROM cards WHERE art_off IS NULL AND art_uri <> '' LIMIT ?",
            arrayOf(limit.toString()),
        ).use { c ->
            while (c.moveToNext()) out.add(c.getString(0) to c.getString(1))
        }
        return out
    }

    fun recordArt(oracleId: String, offset: Long, length: Int, height: Int) {
        db?.execSQL(
            "UPDATE cards SET art_off = ?, art_len = ?, art_h = ? WHERE oracle_id = ?",
            arrayOf(offset, length, height, oracleId),
        )
    }

    /** Refreshes an existing row. Returns false if the card is not in the corpus yet. */
    fun updateExisting(card: Card, artUri: String): Boolean {
        val database = db ?: return false
        // Never touch art_* here. A refresh of oracle text must not orphan artwork
        // that is already sitting in the pack.
        val updated = database.compileStatement(
            """UPDATE cards SET name=?, mana_cost=?, mv=?, type_line=?, type_mask=?,
                   oracle_text=?, power=?, toughness=?, loyalty=?, color_identity=?,
                   scryfall_uri=?, art_uri=?
               WHERE oracle_id=?"""
        ).use { stmt ->
            stmt.bindString(1, card.name)
            stmt.bindString(2, card.manaCost)
            stmt.bindLong(3, card.manaValue.toLong())
            stmt.bindString(4, card.typeLine)
            stmt.bindLong(5, CardTypes.maskOf(card.typeLine).toLong())
            stmt.bindString(6, card.oracleText)
            card.power?.let { stmt.bindString(7, it) } ?: stmt.bindNull(7)
            card.toughness?.let { stmt.bindString(8, it) } ?: stmt.bindNull(8)
            card.loyalty?.let { stmt.bindString(9, it) } ?: stmt.bindNull(9)
            stmt.bindString(10, card.colorIdentity)
            stmt.bindString(11, card.scryfallUri)
            stmt.bindString(12, artUri)
            stmt.bindString(13, card.oracleId)
            stmt.executeUpdateDelete()
        }
        return updated > 0
    }

    fun insert(card: Card, artUri: String) {
        db?.execSQL(
            """INSERT OR IGNORE INTO cards(oracle_id, name, mana_cost, mv, type_line, type_mask,
                   oracle_text, power, toughness, loyalty, color_identity, scryfall_uri, art_uri)
               VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            arrayOf(
                card.oracleId, card.name, card.manaCost, card.manaValue, card.typeLine,
                CardTypes.maskOf(card.typeLine), card.oracleText, card.power, card.toughness,
                card.loyalty, card.colorIdentity, card.scryfallUri, artUri,
            ),
        )
    }

    fun beginTransaction() = db?.beginTransaction()
    fun setTransactionSuccessful() = db?.setTransactionSuccessful()
    fun endTransaction() = db?.endTransaction()

    private fun scalar(sql: String): Int {
        val database = db ?: return 0
        database.rawQuery(sql, null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    private fun android.database.Cursor.toCard() = Card(
        oracleId = getString(0),
        name = getString(1),
        manaCost = getString(2),
        manaValue = getInt(3),
        typeLine = getString(4),
        oracleText = getString(5),
        power = if (isNull(6)) null else getString(6),
        toughness = if (isNull(7)) null else getString(7),
        loyalty = if (isNull(8)) null else getString(8),
        colorIdentity = if (isNull(9)) "" else getString(9),
        scryfallUri = getString(10),
        artOffset = if (isNull(11)) null else getLong(11),
        artLength = if (isNull(12)) null else getInt(12),
        artHeight = if (isNull(13)) null else getInt(13),
    )

    companion object {
        private const val TAG = "CardRepository"
        const val DB_NAME = "momir.db"
        const val ART_NAME = "art.pack"

        private const val SELECT_COLUMNS =
            "SELECT oracle_id, name, mana_cost, mv, type_line, oracle_text, power, " +
                "toughness, loyalty, color_identity, scryfall_uri, art_off, art_len, art_h " +
                "FROM cards"
    }
}

data class ManaValueBucket(val manaValue: Int, val count: Int)
