package app.housemove

import app.housemove.data.Crc32
import app.housemove.data.ZIP_STORED
import app.housemove.data.ZipIn
import app.housemove.data.ZipOut
import app.housemove.data.dosTime
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.TimeZone
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.random.Random

/**
 * The common zip writer and reader (Zip.kt, the iPhone's) against java.util.zip, which Android uses: what the iPhone
 * writes, Android reads, and the other way round. On the JVM the common writer's DEFLATE is java.util.zip.Deflater,
 * so its zips must be Java's byte for byte; on the iPhone the compressed bytes are zlib's and only have to read the
 * same (ZipCommonTest, golden backup.json).
 */
class ZipJvmTest {
    private class Entry(val name: String, val bytes: ByteArray, val stored: Boolean = false)

    private val time = 1_767_225_600_000L + 12_345_678L

    private fun common(entries: List<Entry>): ByteArray {
        val out = ByteArrayOutputStream()
        val dos = dosTime(time, TimeZone.getDefault().getOffset(time).toLong())
        ZipOut(out) { dos }.use { z ->
            for (e in entries) {
                if (e.stored) {
                    val crc = Crc32().apply { update(e.bytes, 0, e.bytes.size) }.value
                    z.putNextEntry(e.name, ZIP_STORED, e.bytes.size.toLong(), crc)
                } else {
                    z.putNextEntry(e.name)
                }
                z.write(e.bytes, 0, e.bytes.size)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** As Android's ZipWriter writes them (ZipOutputStream over a buffer), with the same time. */
    private fun java(entries: List<Entry>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out.buffered()).use { z ->
            for (e in entries) {
                val ze = ZipEntry(e.name)
                ze.time = time
                if (e.stored) {
                    ze.method = ZipEntry.STORED
                    ze.size = e.bytes.size.toLong()
                    ze.crc = CRC32().apply { update(e.bytes) }.value
                }
                z.putNextEntry(ze)
                z.write(e.bytes)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun randomEntries(r: Random): List<Entry> = List(r.nextInt(0, 8)) { i ->
        val size = when (r.nextInt(4)) {
            0 -> 0
            1 -> r.nextInt(1, 100)
            2 -> r.nextInt(100, 20_000)
            else -> r.nextInt(20_000, 300_000)
        }
        // Text that compresses, and noise that does not.
        val bytes = if (r.nextBoolean()) ByteArray(size) { "Plates, Mugs; ΟΔΟΣ cups ".encodeToByteArray()[it % 27] } else r.nextBytes(size)
        val name = listOf("data.json", "about.json", "photos/${r.nextLong().toULong()}.jpg", "photos/Zoë 🏠 $i.jpg", "dir/")[r.nextInt(5)] + i
        Entry(name, bytes, stored = r.nextInt(5) == 0)
    }

    @Test
    fun theCommonWriterWritesJavasZipByteForByte() {
        val r = Random(2026)
        repeat(60) {
            val entries = randomEntries(r)
            assertArrayEquals("zip of ${entries.map { it.name }}", java(entries), common(entries))
        }
    }

    @Test
    fun aZipOfMoreThan65535EntriesHasJavasZip64End() {
        val entries = List(70_000) { Entry("photos/$it.jpg", if (it % 1000 == 0) Random(it).nextBytes(500) else ByteArray(0)) }
        val made = common(entries)
        assertArrayEquals(java(entries), made)
        ZipFile(Files.createTempFile("hm", ".zip").toFile().apply { writeBytes(made); deleteOnExit() }).use { assertEquals(70_000, it.size()) }
    }

    @Test
    fun javaReadsWhatTheCommonWriterWrites() {
        val r = Random(7)
        repeat(30) {
            val entries = randomEntries(r)
            val zip = common(entries)
            // Streaming, as Android restores a backup.
            ZipInputStream(ByteArrayInputStream(zip)).use { z ->
                for (e in entries) {
                    val ze = z.nextEntry!!
                    assertEquals(e.name, ze.name)
                    assertArrayEquals(e.bytes, z.readBytes())
                }
                assertNull(z.nextEntry)
            }
            // And through the central directory.
            val f = Files.createTempFile("hm", ".zip").toFile()
            try {
                f.writeBytes(zip)
                ZipFile(f).use { zf ->
                    assertEquals(entries.size, zf.size())
                    for (e in entries) {
                        val ze = zf.getEntry(e.name)
                        assertEquals(e.bytes.size.toLong(), ze.size)
                        assertEquals(CRC32().apply { update(e.bytes) }.value, ze.crc)
                        assertArrayEquals(e.bytes, zf.getInputStream(ze).readBytes())
                    }
                }
            } finally {
                f.delete()
            }
        }
    }

    @Test
    fun theCommonReaderReadsWhatJavaWrites() {
        val r = Random(11)
        repeat(30) {
            val entries = randomEntries(r)
            val zip = java(entries)
            ZipIn(ByteArrayInputStream(zip)).use { z ->
                for (e in entries) {
                    val item = z.nextEntry()!!
                    assertEquals(e.name, item.name)
                    val got = ByteArrayOutputStream()
                    val buf = ByteArray(1 + r.nextInt(9000))
                    while (true) {
                        val n = z.read(buf, 0, buf.size)
                        if (n < 0) break
                        got.write(buf, 0, n)
                    }
                    assertArrayEquals(e.bytes, got.toByteArray())
                }
                assertNull(z.nextEntry())
            }
            // Entries skipped without being read are read past whole.
            ZipIn(ByteArrayInputStream(zip)).use { z ->
                for (e in entries) assertEquals(e.name, z.nextEntry()!!.name)
                assertNull(z.nextEntry())
            }
        }
    }

    @Test
    fun crc32IsJavas() {
        val r = Random(3)
        repeat(200) {
            val b = r.nextBytes(r.nextInt(0, 5000))
            val off = if (b.isEmpty()) 0 else r.nextInt(b.size)
            val len = r.nextInt(0, b.size - off + 1)
            val mine = Crc32().apply { update(b, off, len) }.value
            assertEquals(CRC32().apply { update(b, off, len) }.value, mine)
        }
    }

    @Test
    fun dosTimesAreJavasInEveryTimeZone() {
        val before = TimeZone.getDefault()
        try {
            for (zone in listOf("UTC", "Asia/Kathmandu", "America/St_Johns", "Pacific/Kiritimati", "Europe/London", "Australia/Lord_Howe")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                val r = Random(zone.hashCode())
                repeat(500) {
                    val t = r.nextLong(315_532_800_000L + 86_400_000L, 4_000_000_000_000L)
                    val e = ZipEntry("x")
                    e.time = t
                    val out = ByteArrayOutputStream()
                    ZipOutputStream(out).use { z ->
                        z.putNextEntry(e)
                        z.closeEntry()
                    }
                    val b = out.toByteArray()
                    val javaDos = (b[10].toLong() and 0xFF) or ((b[11].toLong() and 0xFF) shl 8) or ((b[12].toLong() and 0xFF) shl 16) or ((b[13].toLong() and 0xFF) shl 24)
                    assertEquals("$zone at $t", javaDos, dosTime(t, TimeZone.getDefault().getOffset(t).toLong()))
                }
            }
        } finally {
            TimeZone.setDefault(before)
        }
    }
}
