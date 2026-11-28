package app.housemove.data

/*
 * Zips as Android's HouseMove 2.1.1 writes and reads them (backups, with the photos inside), in common Kotlin for the
 * iPhone (step 1b of design/ios-plan.md). Android and the JVM keep java.util.zip itself (Files.jvm.kt); the iPhone's
 * ZipWriter and ZipReader (Files.ios.kt) are these.
 *
 * [ZipOut] writes what java.util.zip.ZipOutputStream writes for the entries the app makes: version 20, flags 0x0808
 * (a data descriptor with its signature after the data, and UTF-8 names), DEFLATE at zlib's default level, no extra
 * fields, no comments; ZIP64 records only when a size, an offset or the number of entries needs them, as Java writes
 * them. [ZipIn] reads as java.util.zip.ZipInputStream reads, entry by entry from the start: DEFLATE and stored
 * entries, data descriptors with or without their signature, and it checks each entry's CRC-32 and sizes. The
 * compression itself is zlib's (raw DEFLATE, windowBits -15): [RawDeflater] and [RawInflater].
 */

/** What is wrong with a zip being read or written: java.util.zip.ZipException's place. */
internal class ZipFormatException(message: String) : IOException(message)

/** Raw DEFLATE (no zlib header or trailer) at zlib's default level: java.util.zip.Deflater(-1, true) on Android and the JVM, zlib on the iPhone. */
internal expect class RawDeflater() {
    /** Compresses [len] bytes of [b] from [off]; whatever compressed bytes come of it so far go to [out] (bytes, count). */
    fun deflate(b: ByteArray, off: Int, len: Int, out: (ByteArray, Int) -> Unit)

    /** Ends the stream: the rest of the compressed bytes go to [out]. */
    fun finish(out: (ByteArray, Int) -> Unit)

    /** Uncompressed bytes taken in since the last [reset]. */
    val bytesRead: Long

    /** Compressed bytes given out since the last [reset]. */
    val bytesWritten: Long

    /** Ready for a new stream. */
    fun reset()

    /** Frees what it holds; it cannot be used afterwards. */
    fun end()
}

/** Raw DEFLATE decompression: java.util.zip.Inflater(true) on Android and the JVM, zlib on the iPhone. */
internal expect class RawInflater() {
    /** The compressed bytes to take next. Given only when [needsInput]. */
    fun setInput(b: ByteArray, off: Int, len: Int)

    /** Up to [len] bytes into [b] from [off]: how many came out; 0 when it needs input or has finished. Throws ZipFormatException on data that is not DEFLATE. */
    fun inflate(b: ByteArray, off: Int, len: Int): Int

    /** The end of the compressed stream has been reached. */
    val finished: Boolean

    /** All the input given has been taken. */
    val needsInput: Boolean

    /** Input given and not taken: what follows the end of the stream, once [finished]. */
    val remaining: Int

    /** Compressed bytes taken since the last [reset]. */
    val bytesRead: Long

    /** Uncompressed bytes given out since the last [reset]. */
    val bytesWritten: Long

    fun reset()

    fun end()
}

/** CRC-32 (the zip and zlib one, polynomial 0xEDB88320), as java.util.zip.CRC32. */
internal class Crc32 {
    private var crc = 0.inv()

    fun update(b: ByteArray, off: Int, len: Int) {
        var c = crc
        for (i in off until off + len) c = TABLE[(c xor b[i].toInt()) and 0xFF] xor (c ushr 8)
        crc = c
    }

    /** The CRC of what was given since the last [reset], from 0 to 2^32 - 1. */
    val value: Long get() = crc.inv().toLong() and 0xFFFFFFFFL

    fun reset() {
        crc = 0.inv()
    }

    private companion object {
        val TABLE = IntArray(256) { n ->
            var c = n
            repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
            c
        }
    }
}

/**
 * The MS-DOS time and date java.util.zip writes for an entry made at [epochMillis], in local time ([offsetMillis] from
 * UTC then): the date in the high 16 bits, the time in the low 16, seconds halved (ZipUtils.javaToExtendedDosTime, of
 * which the zip keeps these 32 bits). Before 1980 it is 1 January 1980, 00:00, as in Java (which then also adds a time
 * field to the entry; a phone's clock is not that far wrong).
 */
internal fun dosTime(epochMillis: Long, offsetMillis: Long): Long {
    val local = epochMillis + offsetMillis
    val day = local.floorDiv(86_400_000L)
    val ms = local - day * 86_400_000L
    // The civil date of an epoch day (Howard Hinnant's days_from_civil, inverted).
    val z = day + 719_468
    val era = z.floorDiv(146_097)
    val doe = z - era * 146_097
    val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = if (mp < 10) mp + 3 else mp - 9
    val y = yoe + era * 400 + if (m <= 2) 1 else 0
    if (y < 1980) return (1L shl 21) or (1L shl 16)
    val secs = ms / 1000
    val hour = secs / 3600
    val minute = (secs / 60) % 60
    val second = secs % 60
    return (((y - 1980) shl 25) or (m shl 21) or (d shl 16) or (hour shl 11) or (minute shl 5) or (second shr 1)) and 0xFFFFFFFFL
}

private const val LOCSIG = 0x04034b50L
private const val EXTSIG = 0x08074b50L
private const val CENSIG = 0x02014b50L
private const val ENDSIG = 0x06054b50L
private const val ZIP64_ENDSIG = 0x06064b50L
private const val ZIP64_LOCSIG = 0x07064b50L
private const val ZIP64_MAGICVAL = 0xFFFFFFFFL
private const val ZIP64_MAGICCOUNT = 0xFFFF
private const val ZIP64_EXTID = 0x0001
private const val USE_UTF8 = 0x800
internal const val ZIP_STORED = 0
internal const val ZIP_DEFLATED = 8

/** A zip entry's name as zip names are written: strict UTF-8 (java.util.zip refuses a lone surrogate too). */
private fun nameBytes(name: String): ByteArray = try {
    name.encodeToByteArray(throwOnInvalidSequence = true)
} catch (e: CharacterCodingException) {
    throw IllegalArgumentException("entry name is not UTF-16: $e")
}

/**
 * Writes a zip to [out] as java.util.zip.ZipOutputStream does, entry by entry: [putNextEntry], [write], [closeEntry],
 * and [close] for the central directory. [dosTime] gives each entry's time as the zip stores it (see [dosTime]): the
 * phone's clock in local time. A DEFLATED entry is followed by a data descriptor; a STORED one, whose CRC and size the
 * caller gives, is not (as Java requires and writes them).
 */
internal class ZipOut(private val out: OutputStream, private val dosTime: () -> Long) : AutoCloseable {
    private class Entry(
        val name: ByteArray, val method: Int, val flag: Int, val time: Long, val offset: Long,
        var crc: Long, var csize: Long, var size: Long,
    ) {
        /** Where the entry's data begins. */
        var dataStart = 0L
    }

    private val entries = ArrayList<Entry>()
    private val names = HashSet<String>()
    private var current: Entry? = null
    private var written = 0L
    private val deflater = RawDeflater()
    private val crc = Crc32()
    private var finished = false
    private var closed = false
    private val small = ByteArray(8)

    private fun put(b: ByteArray, off: Int = 0, len: Int = b.size) {
        out.write(b, off, len)
        written += len
    }

    private fun short(v: Int) {
        small[0] = v.toByte()
        small[1] = (v ushr 8).toByte()
        put(small, 0, 2)
    }

    private fun int(v: Long) {
        for (i in 0 until 4) small[i] = (v ushr (8 * i)).toByte()
        put(small, 0, 4)
    }

    private fun long(v: Long) {
        for (i in 0 until 8) small[i] = (v ushr (8 * i)).toByte()
        put(small, 0, 8)
    }

    private fun ensureOpen() {
        if (closed) throw IOException("Stream closed")
    }

    /**
     * Begins the entry [name]. A STORED entry needs its [size] and [crc] (and is written with them before its data);
     * a DEFLATED one works them out as its bytes come.
     */
    fun putNextEntry(name: String, method: Int = ZIP_DEFLATED, size: Long = -1, crc: Long = -1) {
        ensureOpen()
        if (name.length > 0xFFFF) throw IllegalArgumentException("entry name too long")
        if (current != null) closeEntry()
        val flag = when (method) {
            ZIP_DEFLATED -> 8
            ZIP_STORED -> {
                if (size < 0 || crc < 0) throw ZipFormatException("STORED entry missing size, compressed size, or crc-32")
                0
            }
            else -> throw ZipFormatException("unsupported compression method")
        } or USE_UTF8
        if (!names.add(name)) throw ZipFormatException("duplicate entry: $name")
        val e = Entry(nameBytes(name), method, flag, dosTime(), written, crc, if (method == ZIP_STORED) size else -1, size)
        current = e
        writeLoc(e)
        e.dataStart = written
    }

    private fun version(e: Entry) = if (e.method == ZIP_DEFLATED) 20 else 10

    private fun writeLoc(e: Entry) {
        int(LOCSIG)
        if (e.flag and 8 == 8) {
            short(version(e))
            short(e.flag)
            short(e.method)
            int(e.time)
            // The CRC and the sizes follow the data, in the data descriptor.
            int(0)
            int(0)
            int(0)
        } else {
            val zip64 = e.csize >= ZIP64_MAGICVAL || e.size >= ZIP64_MAGICVAL
            short(if (zip64) 45 else version(e))
            short(e.flag)
            short(e.method)
            int(e.time)
            int(e.crc)
            if (zip64) {
                int(ZIP64_MAGICVAL)
                int(ZIP64_MAGICVAL)
            } else {
                int(e.csize)
                int(e.size)
            }
        }
        short(e.name.size)
        val zip64 = e.flag and 8 == 0 && (e.csize >= ZIP64_MAGICVAL || e.size >= ZIP64_MAGICVAL)
        short(if (zip64) 20 else 0)
        put(e.name)
        if (zip64) {
            short(ZIP64_EXTID)
            short(16)
            long(e.size)
            long(e.csize)
        }
    }

    fun write(b: ByteArray, off: Int, len: Int) {
        ensureOpen()
        if (off < 0 || len < 0 || off > b.size - len) throw IndexOutOfBoundsException()
        if (len == 0) return
        val e = current ?: throw ZipFormatException("no current ZIP entry")
        when (e.method) {
            ZIP_DEFLATED -> deflater.deflate(b, off, len) { bytes, n -> put(bytes, 0, n) }
            else -> {
                if (written - e.dataStart + len > e.size) throw ZipFormatException("attempt to write past end of STORED entry")
                put(b, off, len)
            }
        }
        crc.update(b, off, len)
    }

    fun closeEntry() {
        ensureOpen()
        val e = current ?: return
        when (e.method) {
            ZIP_DEFLATED -> {
                deflater.finish { bytes, n -> put(bytes, 0, n) }
                e.size = deflater.bytesRead
                e.csize = deflater.bytesWritten
                e.crc = crc.value
                // The data descriptor, with its signature.
                int(EXTSIG)
                int(e.crc)
                if (e.csize >= ZIP64_MAGICVAL || e.size >= ZIP64_MAGICVAL) {
                    long(e.csize)
                    long(e.size)
                } else {
                    int(e.csize)
                    int(e.size)
                }
                deflater.reset()
            }
            else -> {
                val data = written - e.dataStart
                if (data != e.size) throw ZipFormatException("invalid entry size (expected ${e.size} but got $data bytes)")
                if (crc.value != e.crc) throw ZipFormatException("invalid entry crc-32 (expected 0x${e.crc.toString(16)} but got 0x${crc.value.toString(16)})")
            }
        }
        crc.reset()
        entries += e
        current = null
    }

    /** Ends the zip: the central directory and its end record. The stream stays open. */
    fun finish() {
        ensureOpen()
        if (finished) return
        if (current != null) closeEntry()
        val off = written
        for (e in entries) writeCen(e)
        writeEnd(off, written - off)
        finished = true
    }

    private fun writeCen(e: Entry) {
        var csize = e.csize
        var size = e.size
        var offset = e.offset
        var elen64 = 0
        if (e.csize >= ZIP64_MAGICVAL) {
            csize = ZIP64_MAGICVAL
            elen64 += 8
        }
        if (e.size >= ZIP64_MAGICVAL) {
            size = ZIP64_MAGICVAL
            elen64 += 8
        }
        if (e.offset >= ZIP64_MAGICVAL) {
            offset = ZIP64_MAGICVAL
            elen64 += 8
        }
        val zip64 = elen64 > 0
        int(CENSIG)
        short(if (zip64) 45 else version(e)) // version made by
        short(if (zip64) 45 else version(e)) // version needed to extract
        short(e.flag)
        short(e.method)
        int(e.time)
        int(e.crc)
        int(csize)
        int(size)
        short(e.name.size)
        short(if (zip64) elen64 + 4 else 0)
        short(0) // comment length
        short(0) // starting disk number
        short(0) // internal file attributes
        int(0) // external file attributes
        int(offset)
        put(e.name)
        if (zip64) {
            short(ZIP64_EXTID)
            short(elen64)
            if (size == ZIP64_MAGICVAL) long(e.size)
            if (csize == ZIP64_MAGICVAL) long(e.csize)
            if (offset == ZIP64_MAGICVAL) long(e.offset)
        }
    }

    private fun writeEnd(off: Long, len: Long) {
        var zip64 = false
        var xlen = len
        var xoff = off
        if (xlen >= ZIP64_MAGICVAL) {
            xlen = ZIP64_MAGICVAL
            zip64 = true
        }
        if (xoff >= ZIP64_MAGICVAL) {
            xoff = ZIP64_MAGICVAL
            zip64 = true
        }
        var count = entries.size
        if (count >= ZIP64_MAGICCOUNT) {
            zip64 = true
            count = ZIP64_MAGICCOUNT
        }
        if (zip64) {
            val off64 = written
            int(ZIP64_ENDSIG)
            long(56 - 12L)
            short(45)
            short(45)
            int(0)
            int(0)
            long(entries.size.toLong())
            long(entries.size.toLong())
            long(len)
            long(off)
            int(ZIP64_LOCSIG)
            int(0)
            long(off64)
            int(1)
        }
        int(ENDSIG)
        short(0)
        short(0)
        short(count)
        short(count)
        int(xlen)
        int(xoff)
        short(0) // comment length
    }

    /** Ends the zip ([finish]) and closes [out], as ZipOutputStream.close does. */
    override fun close() {
        if (closed) return
        try {
            finish()
        } finally {
            closed = true
            deflater.end()
            out.close()
        }
    }
}

/**
 * Reads a zip from [input] entry by entry, as java.util.zip.ZipInputStream does: from the start, through the local
 * headers, until something that is not one (the central directory). Each entry's CRC-32 and sizes are checked when
 * its end is read; anything wrong is a ZipFormatException, and a zip cut short an EOFException.
 */
internal class ZipIn(private val input: InputStream) : AutoCloseable {
    private class Entry(val name: String, val method: Int, val flag: Int, var crc: Long, var csize: Long, var size: Long)

    // What has been read from [input] and not used yet: a pushback buffer, as ZipInputStream's.
    private val buf = ByteArray(8192)
    private var pos = 0
    private var lim = 0
    private val tmp = ByteArray(30)

    private val inflater = RawInflater()
    private val crc = Crc32()
    private var entry: Entry? = null
    private var remaining = 0L
    private var closed = false

    private fun ensureOpen() {
        if (closed) throw IOException("Stream closed")
    }

    /** Some bytes, from what was read already or else from [input]; -1 at the end. */
    private fun readRaw(b: ByteArray, off: Int, len: Int): Int {
        if (pos < lim) {
            val n = minOf(len, lim - pos)
            buf.copyInto(b, off, pos, pos + n)
            pos += n
            return n
        }
        return input.read(b, off, len)
    }

    private fun readFully(b: ByteArray, off: Int, len: Int) {
        var n = 0
        while (n < len) {
            val r = readRaw(b, off + n, len - n)
            if (r < 0) throw EOFException()
            n += r
        }
    }

    private fun u16(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, at: Int) = u16(b, at).toLong() or (u16(b, at + 2).toLong() shl 16)

    private fun u64(b: ByteArray, at: Int) = u32(b, at) or (u32(b, at + 4) shl 32)

    /** The next entry, or null after the last. What was left of the entry before is read (and checked) first. */
    fun nextEntry(): ZipItem? {
        ensureOpen()
        if (entry != null) closeEntry()
        crc.reset()
        inflater.reset()
        val e = readLoc() ?: return null
        entry = e
        if (e.method == ZIP_STORED) remaining = e.size
        return ZipItem(e.name, e.name.endsWith("/"))
    }

    private fun readLoc(): Entry? {
        try {
            readFully(tmp, 0, 30)
        } catch (_: EOFException) {
            return null
        }
        if (u32(tmp, 0) != LOCSIG) return null
        val flag = u16(tmp, 6)
        val nameLen = u16(tmp, 26)
        val nb = ByteArray(nameLen)
        readFully(nb, 0, nameLen)
        val name = try {
            nb.decodeToString(throwOnInvalidSequence = true)
        } catch (_: CharacterCodingException) {
            throw ZipFormatException("invalid entry name")
        }
        if (flag and 1 == 1) throw ZipFormatException("encrypted ZIP entry not supported")
        val method = u16(tmp, 8)
        val e = if (flag and 8 == 8) {
            if (method != ZIP_DEFLATED) throw ZipFormatException("only DEFLATED entries can have EXT descriptor")
            Entry(name, method, flag, -1, -1, -1)
        } else {
            Entry(name, method, flag, u32(tmp, 14), u32(tmp, 18), u32(tmp, 22))
        }
        val extraLen = u16(tmp, 28)
        if (extraLen > 0) {
            val extra = ByteArray(extraLen)
            readFully(extra, 0, extraLen)
            // Sizes that did not fit in 32 bits are in the ZIP64 extra field, as Java reads them.
            if (e.size == ZIP64_MAGICVAL || e.csize == ZIP64_MAGICVAL) zip64Sizes(e, extra)
        }
        return e
    }

    private fun zip64Sizes(e: Entry, extra: ByteArray) {
        var off = 0
        while (off + 4 <= extra.size) {
            val tag = u16(extra, off)
            val size = u16(extra, off + 2)
            off += 4
            if (off + size > extra.size) break
            if (tag == ZIP64_EXTID) {
                var p = off
                if (e.size == ZIP64_MAGICVAL && p + 8 <= off + size) {
                    e.size = u64(extra, p)
                    p += 8
                }
                if (e.csize == ZIP64_MAGICVAL && p + 8 <= off + size) e.csize = u64(extra, p)
                return
            }
            off += size
        }
    }

    /** Up to [len] bytes of the current entry into [b] from [off]: how many, or -1 at its end. */
    fun read(b: ByteArray, off: Int, len: Int): Int {
        ensureOpen()
        if (off < 0 || len < 0 || off > b.size - len) throw IndexOutOfBoundsException()
        if (len == 0) return 0
        val e = entry ?: return -1
        when (e.method) {
            ZIP_DEFLATED -> {
                val n = inflate(b, off, len)
                if (n == -1) {
                    readEnd(e)
                    entry = null
                } else {
                    crc.update(b, off, n)
                }
                return n
            }
            ZIP_STORED -> {
                if (remaining <= 0) {
                    entry = null
                    return -1
                }
                val want = if (len > remaining) remaining.toInt() else len
                val n = readRaw(b, off, want)
                if (n == -1) throw ZipFormatException("unexpected EOF")
                crc.update(b, off, n)
                remaining -= n
                if (remaining == 0L && e.crc != crc.value) {
                    throw ZipFormatException("invalid entry CRC (expected 0x${e.crc.toString(16)} but got 0x${crc.value.toString(16)})")
                }
                return n
            }
            else -> throw ZipFormatException("invalid compression method")
        }
    }

    /** As InflaterInputStream.read: -1 once the compressed stream has ended. */
    private fun inflate(b: ByteArray, off: Int, len: Int): Int {
        while (true) {
            if (inflater.finished) return -1
            if (inflater.needsInput) {
                if (pos == lim) {
                    val n = input.read(buf, 0, buf.size)
                    if (n < 0) throw EOFException("Unexpected end of ZLIB input stream")
                    pos = 0
                    lim = n
                }
                inflater.setInput(buf, pos, lim - pos)
                pos = lim
            }
            val n = inflater.inflate(b, off, len)
            if (n > 0) return n
        }
    }

    /** After a DEFLATED entry's data: its data descriptor, then the checks of its sizes and CRC. */
    private fun readEnd(e: Entry) {
        // What the inflater was given past the end of the stream belongs to what follows.
        pos = lim - inflater.remaining
        if (e.flag and 8 == 8) {
            if (inflater.bytesWritten > ZIP64_MAGICVAL || inflater.bytesRead > ZIP64_MAGICVAL) {
                readFully(tmp, 0, 24)
                val sig = u32(tmp, 0)
                if (sig != EXTSIG) {
                    e.crc = sig
                    e.csize = u64(tmp, 4)
                    e.size = u64(tmp, 12)
                    unread(tmp, 20, 4)
                } else {
                    e.crc = u32(tmp, 4)
                    e.csize = u64(tmp, 8)
                    e.size = u64(tmp, 16)
                }
            } else {
                readFully(tmp, 0, 16)
                val sig = u32(tmp, 0)
                if (sig != EXTSIG) {
                    e.crc = sig
                    e.csize = u32(tmp, 4)
                    e.size = u32(tmp, 8)
                    unread(tmp, 12, 4)
                } else {
                    e.crc = u32(tmp, 4)
                    e.csize = u32(tmp, 8)
                    e.size = u32(tmp, 12)
                }
            }
        }
        if (e.size != inflater.bytesWritten) throw ZipFormatException("invalid entry size (expected ${e.size} but got ${inflater.bytesWritten} bytes)")
        if (e.csize != inflater.bytesRead) throw ZipFormatException("invalid entry compressed size (expected ${e.csize} but got ${inflater.bytesRead} bytes)")
        if (e.crc != crc.value) throw ZipFormatException("invalid entry CRC (expected 0x${e.crc.toString(16)} but got 0x${crc.value.toString(16)})")
    }

    /** Puts [len] bytes of [b] from [off] back in front of what is still to be read. */
    private fun unread(b: ByteArray, off: Int, len: Int) {
        if (pos >= len) {
            pos -= len
            b.copyInto(buf, pos, off, off + len)
        } else {
            val rest = buf.copyOfRange(pos, lim)
            b.copyInto(buf, 0, off, off + len)
            rest.copyInto(buf, len)
            pos = 0
            lim = len + rest.size
        }
    }

    /** Reads (and checks) what is left of the current entry. */
    fun closeEntry() {
        ensureOpen()
        val skip = ByteArray(512)
        while (read(skip, 0, skip.size) != -1) {
            // read to the end
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        inflater.end()
        input.close()
    }
}
