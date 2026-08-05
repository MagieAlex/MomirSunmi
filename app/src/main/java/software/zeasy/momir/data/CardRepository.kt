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

    fun tokenCount(): Int = scalar("SELECT COUNT(*) FROM tokens")

    fun artCount(): Int = scalar("SELECT COUNT(*) FROM cards WHERE art_off IS NOT NULL")

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
     * Every mana value that actually has creatures, with its count.
     *
     * Momir's X can be anything, but Magic has never printed a creature at MV 14
     * or above 16, so the wheel only ever offers values that can produce a token.
     */
    fun manaValueCounts(): List<ManaValueBucket> {
        val database = db ?: return emptyList()
        val out = ArrayList<ManaValueBucket>(17)
        database.rawQuery(
            "SELECT mv, COUNT(*) FROM cards GROUP BY mv ORDER BY mv", null,
        ).use { c ->
            while (c.moveToNext()) out.add(ManaValueBucket(c.getInt(0), c.getInt(1)))
        }
        return out
    }

    /** The Momir Vig activation itself: a uniformly random creature at this mana value. */
    fun randomCard(manaValue: Int): Card? {
        val database = db ?: return null
        database.rawQuery(
            "$SELECT_COLUMNS WHERE mv = ? ORDER BY RANDOM() LIMIT 1",
            arrayOf(manaValue.toString()),
        ).use { c ->
            return if (c.moveToFirst()) c.toCard() else null
        }
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

    /** The tokens a given creature can put onto the battlefield. */
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
            """UPDATE cards SET name=?, mana_cost=?, mv=?, type_line=?, oracle_text=?,
                   power=?, toughness=?, color_identity=?, scryfall_uri=?, art_uri=?
               WHERE oracle_id=?"""
        ).use { stmt ->
            stmt.bindString(1, card.name)
            stmt.bindString(2, card.manaCost)
            stmt.bindLong(3, card.manaValue.toLong())
            stmt.bindString(4, card.typeLine)
            stmt.bindString(5, card.oracleText)
            card.power?.let { stmt.bindString(6, it) } ?: stmt.bindNull(6)
            card.toughness?.let { stmt.bindString(7, it) } ?: stmt.bindNull(7)
            stmt.bindString(8, card.colorIdentity)
            stmt.bindString(9, card.scryfallUri)
            stmt.bindString(10, artUri)
            stmt.bindString(11, card.oracleId)
            stmt.executeUpdateDelete()
        }
        return updated > 0
    }

    fun insert(card: Card, artUri: String) {
        db?.execSQL(
            """INSERT OR IGNORE INTO cards(oracle_id, name, mana_cost, mv, type_line,
                   oracle_text, power, toughness, color_identity, scryfall_uri, art_uri)
               VALUES (?,?,?,?,?,?,?,?,?,?,?)""",
            arrayOf(
                card.oracleId, card.name, card.manaCost, card.manaValue, card.typeLine,
                card.oracleText, card.power, card.toughness, card.colorIdentity,
                card.scryfallUri, artUri,
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
        colorIdentity = if (isNull(8)) "" else getString(8),
        scryfallUri = getString(9),
        artOffset = if (isNull(10)) null else getLong(10),
        artLength = if (isNull(11)) null else getInt(11),
        artHeight = if (isNull(12)) null else getInt(12),
    )

    companion object {
        private const val TAG = "CardRepository"
        const val DB_NAME = "momir.db"
        const val ART_NAME = "art.pack"

        private const val SELECT_COLUMNS =
            "SELECT oracle_id, name, mana_cost, mv, type_line, oracle_text, power, " +
                "toughness, color_identity, scryfall_uri, art_off, art_len, art_h FROM cards"
    }
}

data class ManaValueBucket(val manaValue: Int, val count: Int)
