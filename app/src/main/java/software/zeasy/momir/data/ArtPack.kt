package software.zeasy.momir.data

import android.util.Log
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Random access into art.pack: one append-only file holding every card's artwork
 * as a packed 1-bit raster, 384 px wide, MSB first, 1 = burn.
 *
 * Format:
 *   0  magic "MOMIRART"     8 bytes
 *   8  version             u16 LE
 *  10  width in dots       u16 LE
 *  12  reserved            u32 LE
 *  16  blobs, back to back
 *
 * One file instead of 17k PNGs for two reasons. The V2's eMMC is slow at opening
 * small files, and 4 KB block granularity would waste roughly 40 % on 13 KB
 * images. More importantly the bytes here are already the exact payload the
 * print head wants, so a print does one seek and one read - no image decoding,
 * no bitmap allocation, no dithering at press time.
 */
class ArtPack(private val file: File) : Closeable {

    private var raf: RandomAccessFile? = null
    var width: Int = 0
        private set

    val isOpen: Boolean get() = raf != null

    fun open(): Boolean {
        if (raf != null) return true
        if (!file.exists() || file.length() < HEADER_LEN) return false
        return try {
            val handle = RandomAccessFile(file, "rw")
            val header = ByteArray(HEADER_LEN)
            handle.readFully(header)
            if (!header.startsWith(MAGIC)) {
                Log.e(TAG, "${file.name} is not an art pack")
                handle.close()
                return false
            }
            val version = readU16(header, 8)
            width = readU16(header, 10)
            if (version != VERSION) {
                Log.e(TAG, "art pack version $version, expected $VERSION")
                handle.close()
                return false
            }
            raf = handle
            true
        } catch (e: IOException) {
            Log.e(TAG, "Cannot open art pack", e)
            false
        }
    }

    /** Reads one card's raster. Returns null if the pack is truncated or unopened. */
    fun read(offset: Long, length: Int): ByteArray? {
        val handle = raf ?: return null
        return try {
            if (offset + length > handle.length()) {
                Log.w(TAG, "art blob at $offset+$length runs past end of pack")
                return null
            }
            val buffer = ByteArray(length)
            handle.seek(offset)
            handle.readFully(buffer)
            buffer
        } catch (e: IOException) {
            Log.e(TAG, "Cannot read art at $offset", e)
            null
        }
    }

    /** Appends a raster during a resync and returns the offset it landed at. */
    fun append(raster: ByteArray): Long? {
        val handle = raf ?: return null
        return try {
            val offset = handle.length()
            handle.seek(offset)
            handle.write(raster)
            offset
        } catch (e: IOException) {
            Log.e(TAG, "Cannot append art", e)
            null
        }
    }

    /** Creates an empty pack so a device with no corpus can still resync from scratch. */
    fun createIfMissing(widthDots: Int): Boolean {
        if (file.exists() && file.length() >= HEADER_LEN) return true
        return try {
            file.parentFile?.mkdirs()
            RandomAccessFile(file, "rw").use { handle ->
                handle.write(MAGIC)
                handle.write(u16(VERSION))
                handle.write(u16(widthDots))
                handle.write(byteArrayOf(0, 0, 0, 0))
            }
            true
        } catch (e: IOException) {
            Log.e(TAG, "Cannot create art pack", e)
            false
        }
    }

    fun sync() {
        try {
            raf?.fd?.sync()
        } catch (e: IOException) {
            Log.w(TAG, "fsync failed", e)
        }
    }

    override fun close() {
        try {
            raf?.close()
        } catch (e: IOException) {
            Log.w(TAG, "close failed", e)
        }
        raf = null
    }

    private fun readU16(b: ByteArray, at: Int) =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun u16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (i in prefix.indices) if (this[i] != prefix[i]) return false
        return true
    }

    companion object {
        private const val TAG = "ArtPack"
        private const val HEADER_LEN = 16
        private const val VERSION = 1
        private val MAGIC = "MOMIRART".toByteArray(Charsets.US_ASCII)
    }
}
