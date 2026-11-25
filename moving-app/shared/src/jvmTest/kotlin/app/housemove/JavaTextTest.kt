package app.housemove

import app.housemove.data.JavaText
import app.housemove.data.Qr
import app.housemove.data.qrPixels
import app.housemove.data.toHex
import app.housemove.sync.WireReader
import app.housemove.sync.WireWriter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.time.LocalDate
import java.util.Random

/**
 * The iPhone's text rules (JavaText.kt) and its sync wire (sync/Wire.kt) against the JVM's own functions, which
 * Android uses, on many inputs the golden fixtures do not hold: random ones, and every value of the hard ranges. The
 * same code runs on the iPhone, so what passes here is what the iPhone does (GoldenCommonTest checks it there too).
 */
class JavaTextTest {
    private val random = Random(20261125)

    /** HM_JAVATEXT_TIMES=20 tools/test.sh --tests app.housemove.JavaTextTest tries twenty times as many random values. */
    private val times = System.getenv("HM_JAVATEXT_TIMES")?.toIntOrNull() ?: 1

    /** Strings rich in what tells encoders apart: NUL, controls, two- and three-byte characters, pairs and lone surrogates. */
    private fun text(max: Int = 12): String {
        val n = random.nextInt(max + 1)
        val b = StringBuilder()
        repeat(n) {
            when (random.nextInt(9)) {
                0 -> b.append(random.nextInt(0x80).toChar())
                1 -> b.append(listOf('\u0000', ' ', '+', '%', '*', '~', '-', '.', '_', ':').random(random.asKotlinRandom()))
                2 -> b.append((0x80 + random.nextInt(0x780)).toChar())
                3 -> b.append((0x800 + random.nextInt(0xF800)).toChar())
                4 -> b.append((0xD800 + random.nextInt(0x400)).toChar())
                5 -> b.append((0xDC00 + random.nextInt(0x400)).toChar())
                6 -> b.appendCodePoint(0x10000 + random.nextInt(0x100000))
                7 -> b.append(listOf("🏠", "Zoë", "日本", "﻿", "￿", "߿", "ࠀ").random(random.asKotlinRandom()))
                else -> b.append(('a' + random.nextInt(26)))
            }
        }
        return b.toString()
    }

    private fun java.util.Random.asKotlinRandom(): kotlin.random.Random = kotlin.random.Random(nextLong())

    private fun errorOr(block: () -> String): String = try {
        block()
    } catch (e: Exception) {
        "error: " + e.javaClass.simpleName
    }

    @Test
    fun utf8IsToByteArray() {
        repeat(200_000 * times) {
            val s = text()
            assertArrayEquals(s, s.toByteArray(), JavaText.utf8(s))
        }
    }

    /** Valid UTF-8 for random text, then spoiled: a byte changed, one cut off, a stray one added. */
    private fun bytes(): ByteArray {
        val b = text(6).toByteArray().toMutableList()
        repeat(random.nextInt(3)) {
            when (random.nextInt(4)) {
                0 -> if (b.isNotEmpty()) b[random.nextInt(b.size)] = random.nextInt(256).toByte()
                1 -> if (b.isNotEmpty()) b.removeAt(b.size - 1)
                2 -> b.add(random.nextInt(b.size + 1), listOf(0x80, 0xBF, 0xC0, 0xC1, 0xE0, 0xED, 0xF0, 0xF4, 0xF5, 0xFF).random(random.asKotlinRandom()).toByte())
                else -> b.add(random.nextInt(b.size + 1), random.nextInt(256).toByte())
            }
        }
        return b.toByteArray()
    }

    @Test
    fun decodeUtf8IsDecodeToString() {
        repeat(300_000 * times) {
            val b = if (it % 3 == 0) ByteArray(random.nextInt(10)) { random.nextInt(256).toByte() } else bytes()
            assertEquals(b.toHex(), b.decodeToString(), JavaText.decodeUtf8(b))
        }
        // Every one- and two-byte sequence, and three- and four-byte ones over the bytes that matter.
        for (x in 0 until 256) assertEquals(String(byteArrayOf(x.toByte())), JavaText.decodeUtf8(byteArrayOf(x.toByte())))
        for (x in 0 until 65536) {
            val b = byteArrayOf((x shr 8).toByte(), x.toByte())
            assertEquals(b.toHex(), b.decodeToString(), JavaText.decodeUtf8(b))
        }
        val edges = listOf(0x00, 0x41, 0x7F, 0x80, 0x8F, 0x90, 0x9F, 0xA0, 0xBF, 0xC0, 0xC2, 0xE0, 0xF0, 0xFF)
        for (b1 in 0xC0..0xFF) for (b2 in 0 until 256) for (b3 in edges) {
            val b = byteArrayOf(b1.toByte(), b2.toByte(), b3.toByte())
            assertEquals(b.toHex(), b.decodeToString(), JavaText.decodeUtf8(b))
            if (b1 >= 0xF0) for (b4 in edges) {
                val c = byteArrayOf(b1.toByte(), b2.toByte(), b3.toByte(), b4.toByte())
                assertEquals(c.toHex(), c.decodeToString(), JavaText.decodeUtf8(c))
            }
        }
    }

    private fun writeUtf(s: String): ByteArray = ByteArrayOutputStream().also { DataOutputStream(it).writeUTF(s) }.toByteArray()

    @Test
    fun modifiedUtf8IsWriteUtfAndReadUtf() {
        repeat(200_000 * times) {
            val s = text(16)
            val made = JavaText.modifiedUtf8(s)
            assertArrayEquals(s, writeUtf(s), made)
            assertEquals(s, JavaText.readModifiedUtf8(made.copyOfRange(2, made.size)))
        }
        // Bytes after a length that may be anything, and may be cut short: the same text, or the same error.
        repeat(300_000 * times) {
            val body = if (it % 2 == 0) bytes() else ByteArray(random.nextInt(8)) { random.nextInt(256).toByte() }
            val length = if (random.nextInt(5) == 0) body.size + random.nextInt(3) else body.size
            val all = byteArrayOf((length shr 8).toByte(), length.toByte()) + body
            val jvm = errorOr { DataInputStream(ByteArrayInputStream(all)).readUTF() }
            val wire = errorOr { reader(all).readUTF() }
            assertEquals(all.toHex(), jvm, wire)
        }
    }

    private fun reader(bytes: ByteArray): WireReader {
        val input = ByteArrayInputStream(bytes)
        return WireReader { b, off, len -> input.read(b, off, len) }
    }

    @Test
    fun theWireIsDataOutputStreamAndDataInputStream() {
        repeat(20_000 * times) {
            val jvm = ByteArrayOutputStream()
            val d = DataOutputStream(jvm)
            val mine = ByteArrayOutputStream()
            val w = WireWriter { mine.write(it) }
            val ops = ArrayList<Pair<Int, Any>>()
            repeat(random.nextInt(8)) {
                when (random.nextInt(4)) {
                    0 -> random.nextInt().let { v -> d.writeInt(v); w.writeInt(v); ops += 0 to v }
                    1 -> random.nextLong().let { v -> d.writeLong(v); w.writeLong(v); ops += 1 to v }
                    2 -> text().let { v -> d.writeUTF(v); w.writeUTF(v); ops += 2 to v }
                    else -> ByteArray(random.nextInt(5)) { random.nextInt(256).toByte() }.let { v -> d.write(v); w.write(v); ops += 3 to v }
                }
            }
            d.flush()
            assertArrayEquals(jvm.toByteArray(), mine.toByteArray())
            val r = reader(mine.toByteArray())
            for ((op, v) in ops) when (op) {
                0 -> assertEquals(v, r.readInt())
                1 -> assertEquals(v, r.readLong())
                2 -> assertEquals(v, r.readUTF())
                else -> assertArrayEquals(v as ByteArray, ByteArray(v.size).also { r.readFully(it) })
            }
            assertEquals(-1, r.read(ByteArray(1), 0, 1))
        }
    }

    private fun checkDouble(d: Double) = assertEquals("bits ${java.lang.Long.toHexString(d.toRawBits())}", d.toString(), JavaText.doubleText(d))

    @Test
    fun doubleTextIsDoubleToString() {
        repeat(100_000 * times) { checkDouble(Double.fromBits(random.nextLong())) }
        // The smallest subnormals, where the shortest decimal has one digit and the JVM gives two.
        for (m in 1L..3_000L * times) checkDouble(Double.fromBits(m))
        // Powers of two and of ten, and their neighbours.
        for (e in -1074..1023) {
            val p = Math.scalb(1.0, e)
            checkDouble(p)
            checkDouble(Math.nextUp(p))
            checkDouble(Math.nextDown(p))
        }
        for (e in -323..308) {
            val p = "1e$e".toDouble()
            for (x in listOf(p, Math.nextUp(p), Math.nextDown(p), 2 * p, 5 * p, 9.5 * p)) checkDouble(x)
        }
        // Around where the plain text ends, and short decimals, which have a short shortest form.
        for (edge in listOf(1e-3, 1e7)) {
            var x = edge
            repeat(1_000 * times) { checkDouble(x); x = Math.nextUp(x) }
            x = edge
            repeat(1_000 * times) { checkDouble(x); x = Math.nextDown(x) }
        }
        repeat(30_000 * times) {
            val digits = (1..(1 + random.nextInt(17))).map { random.nextInt(10) }.joinToString("")
            checkDouble("0.${digits}E${random.nextInt(640) - 330}".toDouble())
        }
        repeat(10_000 * times) { checkDouble(random.nextLong().toDouble()); checkDouble(random.nextInt(100_000).toDouble() / 100) }
        for (d in listOf(0.0, -0.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.MAX_VALUE, -Double.MIN_VALUE)) checkDouble(d)
    }

    @Test
    fun urlCodecIsUrlEncoderAndUrlDecoder() {
        repeat(200_000 * times) {
            val s = text()
            assertEquals(s, URLEncoder.encode(s, "UTF-8"), JavaText.urlEncode(s))
        }
        val pieces = listOf("%", "+", "%2", "%41", "%e6%97%a5", "%C3", "%ED%A0%80", "%-1", "%+F", "%-0", "%G1", "%１２", "%٣٣", "a", "日", " ", "%%", "%F0%9F%8F%A0")
        repeat(200_000 * times) {
            val s = (0 until random.nextInt(5)).joinToString("") { pieces[random.nextInt(pieces.size)] } +
                if (random.nextBoolean()) "%" + Integer.toHexString(random.nextInt(256)) else ""
            assertEquals(s, errorOr { URLDecoder.decode(s, "UTF-8") }, errorOr { JavaText.urlDecode(s) })
        }
    }

    @Test
    fun isoDatesAreLocalDate() {
        for (day in -800_000L..3_000_000L) assertEquals(LocalDate.ofEpochDay(day).toString(), JavaText.isoDate(day))
        val years = listOf(0, 1, 4, 99, 100, 400, 1600, 1900, 1970, 2000, 2024, 2025, 2026, 2100, 2400, 9999) + (0 until 300).map { random.nextInt(10_000) }
        for (y in years) for (m in 0..13) for (d in 0..32) {
            val s = "%04d-%02d-%02d".format(y, m, d)
            val jvm = try {
                LocalDate.parse(s).toEpochDay().toString()
            } catch (_: Exception) {
                "refused"
            }
            val mine = try {
                JavaText.parseIsoDate(s).toString()
            } catch (_: IllegalArgumentException) {
                "refused"
            }
            assertEquals(s, jvm, mine)
        }
    }

    @Test
    fun theCommonTestsRandomIsJavaUtilRandom() {
        for (seed in listOf(42L, 0L, -1L, 20261125L)) {
            val java = Random(seed)
            val common = JavaRandom(seed)
            for (i in 0 until 10_000) {
                val bound = listOf(3, 5, 6, 8, 26, 1000, Int.MAX_VALUE)[i % 7]
                assertEquals(java.nextInt(bound), common.nextInt(bound))
            }
        }
    }

    @Test
    fun hexIsPercent02x() {
        for (x in 0 until 256) assertEquals("%02x".format(x.toByte()), byteArrayOf(x.toByte()).toHex())
    }

    @Test
    fun qrPixelsAreQrMatrix() {
        for (t in listOf("HM2:8F0B:L042", "HM:L042", "HMSYNC:192.168.1.23:40123:ABCDEFGH")) for (px in listOf(240, 640)) {
            val m = Qr.matrix(t, px)
            val p = qrPixels(t, px)
            assertEquals(m.width, p.size)
            for (y in 0 until px) for (x in 0 until px) assertEquals(m.get(x, y), p[x, y])
        }
    }
}
