package app.housemove

import app.housemove.data.Backup
import app.housemove.data.Crc32
import app.housemove.data.EOFException
import app.housemove.data.IOException
import app.housemove.data.RawDeflater
import app.housemove.data.RawInflater
import app.housemove.data.ZIP_STORED
import app.housemove.data.ZipFormatException
import app.housemove.data.ZipIn
import app.housemove.data.ZipOut
import app.housemove.data.ZipReader
import app.housemove.data.ZipWriter
import app.housemove.data.dosTime
import app.housemove.data.peek
import app.housemove.data.sha256
import app.housemove.data.toHex
import app.housemove.data.write
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The common zip writer and reader (Zip.kt), which are the iPhone's, on every platform; and the platform's own
 * ZipWriter and ZipReader (java.util.zip on the JVM, these on the iPhone) on a backup Android 2.1.1 made
 * (golden/backup.json). ZipJvmTest checks the common ones against java.util.zip itself.
 */
class ZipCommonTest {
    private fun unhex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    private fun u16(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, at: Int) = u16(b, at).toLong() or (u16(b, at + 2).toLong() shl 16)

    private val goldenBackup: ByteArray by lazy {
        val f = Json.parseToJsonElement(GoldenFiles.text.getValue("backup.json")).jsonObject
        unhex(f.getValue("sample").jsonObject.getValue("hex").jsonPrimitive.content)
    }

    /** Every entry of [zip] read with the common reader, name to bytes, in order. */
    private fun readAll(zip: ByteArray): List<Pair<String, ByteArray>> {
        val out = ArrayList<Pair<String, ByteArray>>()
        ZipIn(bytesIn(zip)).use { z ->
            while (true) {
                val e = z.nextEntry() ?: break
                val b = BytesOut()
                val buf = ByteArray(777)
                while (true) {
                    val n = z.read(buf, 0, buf.size)
                    if (n < 0) break
                    b.stream.write(buf, 0, n)
                }
                out += e.name to b.bytes()
            }
        }
        return out
    }

    private fun write(entries: List<Pair<String, ByteArray>>, stored: Set<String> = emptySet(), time: Long = 0x5d783c27L): ByteArray {
        val out = BytesOut()
        ZipOut(out.stream) { time }.use { z ->
            for ((name, bytes) in entries) {
                if (name in stored) {
                    val crc = Crc32().apply { update(bytes, 0, bytes.size) }.value
                    z.putNextEntry(name, ZIP_STORED, bytes.size.toLong(), crc)
                } else {
                    z.putNextEntry(name)
                }
                // In uneven pieces, as files are copied.
                var at = 0
                var piece = 1
                while (at < bytes.size) {
                    val n = minOf(piece, bytes.size - at)
                    z.write(bytes, at, n)
                    at += n
                    piece = piece * 3 + 1
                }
                z.closeEntry()
            }
        }
        return out.bytes()
    }

    @Test
    fun crc32IsTheZipOne() {
        val c = Crc32()
        c.update("123456789".encodeToByteArray(), 0, 9)
        assertEquals(0xCBF43926L, c.value)
        c.reset()
        assertEquals(0L, c.value)
        val golden = goldenBackup
        // The golden photo's CRC as Android wrote it.
        val photo = "golden photo: plates".encodeToByteArray()
        c.update(photo, 0, photo.size)
        assertEquals("95221484", c.value.toString(16).padStart(8, '0'))
        assertTrue(golden.isNotEmpty())
    }

    @Test
    fun deflateAndInflateComeBackWhole() {
        for (size in listOf(0, 1, 100, 65_536, 400_000)) {
            val input = if (size == 400_000) ByteArray(size) { (it % 251).toByte() } else Random(size).nextBytes(size)
            val d = RawDeflater()
            val packed = BytesOut()
            d.deflate(input, 0, input.size) { b, n -> packed.stream.write(b, 0, n) }
            d.finish { b, n -> packed.stream.write(b, 0, n) }
            assertEquals(size.toLong(), d.bytesRead)
            val p = packed.bytes()
            assertEquals(p.size.toLong(), d.bytesWritten)
            d.end()
            val inf = RawInflater()
            // The stream given in pieces, with something after its end.
            val tail = byteArrayOf(1, 2, 3)
            val all = p + tail
            val out = ByteArray(size + 10)
            var made = 0
            var at = 0
            while (!inf.finished) {
                if (inf.needsInput) {
                    val n = minOf(1000, all.size - at)
                    inf.setInput(all, at, n)
                    at += n
                }
                made += inf.inflate(out, made, out.size - made)
            }
            assertEquals(size, made)
            assertContentEquals(input, out.copyOf(made))
            assertEquals(p.size.toLong(), inf.bytesRead)
            assertEquals(at - p.size, inf.remaining, "what came after the end is left")
            inf.end()
        }
    }

    @Test
    fun whatTheWriterWritesTheReaderReads() {
        val entries = listOf(
            "data.json" to "{\"version\":5,\"move\":{\"name\":\"Maple Road\"}}".encodeToByteArray(),
            "about.json" to ByteArray(0),
            "photos/zoë 🏠.jpg" to Random(3).nextBytes(150_000),
            "photos/stored.bin" to Random(4).nextBytes(20_000),
            "photos/" to ByteArray(0),
        )
        val zip = write(entries, stored = setOf("photos/stored.bin"))
        val back = readAll(zip)
        assertEquals(entries.map { it.first }, back.map { it.first })
        for ((a, b) in entries.zip(back)) assertContentEquals(a.second, b.second, a.first)
        // Through the platform's reader too (java.util.zip on the JVM).
        ZipReader(bytesIn(zip)).use { r ->
            for ((name, bytes) in entries) {
                val e = r.nextEntry()!!
                assertEquals(name, e.name)
                assertEquals(name.endsWith("/"), e.isDirectory)
                assertContentEquals(bytes, r.readBytes())
                r.closeEntry()
            }
            assertNull(r.nextEntry())
        }
    }

    @Test
    fun entriesAreWrittenAsJavaWritesThem() {
        val zip = write(listOf("data.json" to "abc".encodeToByteArray(), "photos/x.jpg" to ByteArray(10)), stored = emptySet(), time = 0x5d783c27L)
        // The first local header: version 20, flags 0x0808, DEFLATE, the time given, no sizes, no extra field.
        assertEquals(0x04034b50L, u32(zip, 0))
        assertEquals(20, u16(zip, 4))
        assertEquals(0x0808, u16(zip, 6))
        assertEquals(8, u16(zip, 8))
        assertEquals(0x5d783c27L, u32(zip, 10))
        assertEquals(0L, u32(zip, 14))
        assertEquals(0L, u32(zip, 18))
        assertEquals(0L, u32(zip, 22))
        assertEquals(9, u16(zip, 26))
        assertEquals(0, u16(zip, 28))
        // The central directory agrees, entry by entry, and ends the zip.
        var end = zip.size - 22
        assertEquals(0x06054b50L, u32(zip, end))
        assertEquals(2, u16(zip, end + 10))
        var p = u32(zip, end + 16).toInt()
        for (name in listOf("data.json", "photos/x.jpg")) {
            assertEquals(0x02014b50L, u32(zip, p))
            assertEquals(20, u16(zip, p + 4), "version made by")
            assertEquals(20, u16(zip, p + 6), "version needed")
            assertEquals(0x0808, u16(zip, p + 8))
            assertEquals(8, u16(zip, p + 10))
            assertEquals(0, u16(zip, p + 30), "extra")
            assertEquals(0, u16(zip, p + 32), "comment")
            val nameLen = u16(zip, p + 28)
            assertEquals(name, zip.copyOfRange(p + 46, p + 46 + nameLen).decodeToString())
            // The data descriptor after the data, with its signature, says what the central directory says.
            val local = u32(zip, p + 42).toInt()
            val descriptor = local + 30 + nameLen + u32(zip, p + 20).toInt()
            assertEquals(0x08074b50L, u32(zip, descriptor))
            assertEquals(u32(zip, p + 16), u32(zip, descriptor + 4), "crc")
            assertEquals(u32(zip, p + 20), u32(zip, descriptor + 8), "compressed size")
            assertEquals(u32(zip, p + 24), u32(zip, descriptor + 12), "size")
            p += 46 + nameLen
        }
        end = p
        assertEquals(0x06054b50L, u32(zip, end))
    }

    @Test
    fun theReaderTakesDescriptorsWithoutTheirSignature() {
        val entries = listOf("a.json" to "one".encodeToByteArray(), "b.json" to Random(9).nextBytes(5000))
        val zip = write(entries)
        // Take out each descriptor's signature (allowed by the zip format; java.util.zip reads both).
        val out = ArrayList<Byte>()
        var i = 0
        while (i < zip.size) {
            if (i + 4 <= zip.size && u32(zip, i) == 0x08074b50L) {
                i += 4
                continue
            }
            out += zip[i]
            i++
        }
        val back = readAll(out.toByteArray())
        assertEquals(entries.map { it.first }, back.map { it.first })
        for ((a, b) in entries.zip(back)) assertContentEquals(a.second, b.second)
    }

    @Test
    fun aDamagedZipIsAnIOException() {
        val zip = write(listOf("data.json" to Random(1).nextBytes(4000)), stored = emptySet())
        // A byte of the data spoiled: the inflater or the CRC finds it.
        val spoiled = zip.copyOf().also { it[60] = (it[60] + 1).toByte() }
        assertFailsWith<IOException> { readAll(spoiled) }
        // Cut short in the data.
        assertFailsWith<IOException> { readAll(zip.copyOf(100)) }
        // A CRC that does not match the stored data.
        val stored = write(listOf("s.bin" to ByteArray(50) { it.toByte() }), stored = setOf("s.bin"))
        val badCrc = stored.copyOf().also { it[14] = (it[14] + 1).toByte() }
        assertFailsWith<ZipFormatException> { readAll(badCrc) }
        // Not a zip at all: no entries.
        assertEquals(0, readAll("not a zip at all, but long enough to be read".encodeToByteArray()).size)
        assertFailsWith<EOFException> {
            ZipIn(bytesIn(zip.copyOf(36))).use { z ->
                z.nextEntry()
            }
        }
    }

    @Test
    fun theGoldenBackupReadsWithTheCommonReader() {
        val entries = readAll(goldenBackup)
        assertEquals(listOf("data.json", "about.json", "photos/00000000-0000-4000-8000-000000000011.jpg"), entries.map { it.first })
        assertEquals(GoldenFiles.text.getValue("move-data.json"), entries[0].second.decodeToString())
        assertEquals("b245d82575e8753cda5bdd1c55c927dab1ee1feb7afeeab6a85f92082e79a5ef", sha256(entries[0].second).toHex())
        assertEquals("golden photo: plates", entries[2].second.decodeToString())
        val about = Json.parseToJsonElement(entries[1].second.decodeToString()).jsonObject
        assertEquals("m3906c46cfd44f4074ce7", about.getValue("moveId").jsonPrimitive.content)
    }

    @Test
    fun theGoldenBackupPeeksWithThePlatformsReader() {
        val peek = Backup.peek(bytesIn(goldenBackup))
        assertEquals("m3906c46cfd44f4074ce7", peek.moveId)
        assertEquals("Maple Road", peek.moveName)
        assertEquals("00000000-0000-4000-8000-000000000001", peek.by)
        assertFalse(peek.small)
    }

    @Test
    fun thePlatformsWriterWritesWhatTheCommonReaderReads() {
        val out = BytesOut()
        ZipWriter(out.stream).use { z ->
            z.put("data.json") { "{}".encodeToByteArray() }
            z.put("about.json") { ByteArray(0) }
        }
        val back = readAll(out.bytes())
        assertEquals(listOf("data.json", "about.json"), back.map { it.first })
        assertEquals("{}", back[0].second.decodeToString())
    }

    @Test
    fun dosTimesAreJavasLocalTimes() {
        // 2026-01-01 00:00:00 UTC in UTC, and in UTC+5:45 (05:45).
        val t = 1_767_225_600_000L
        assertEquals(((2026L - 1980) shl 25) or (1L shl 21) or (1L shl 16), dosTime(t, 0))
        assertEquals(((2026L - 1980) shl 25) or (1L shl 21) or (1L shl 16) or (5L shl 11) or (45L shl 5), dosTime(t, (5 * 60 + 45) * 60_000L))
        // Seconds are halved: 23:59:59 on 31 December 2025.
        assertEquals(((2025L - 1980) shl 25) or (12L shl 21) or (31L shl 16) or (23L shl 11) or (59L shl 5) or 29L, dosTime(t - 1000, 0))
        // Before 1980: 1 January 1980.
        assertEquals((1L shl 21) or (1L shl 16), dosTime(0, 0))
        // 29 February 2024, 12:34:56.
        assertEquals(((2024L - 1980) shl 25) or (2L shl 21) or (29L shl 16) or (12L shl 11) or (34L shl 5) or 28L, dosTime(1_709_210_096_000L, 0))
    }
}
