package app.housemove

import app.housemove.data.Qr
import app.housemove.data.QrEncoder
import app.housemove.data.labelQrText
import app.housemove.data.qrPixels
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/**
 * The common QR encoder (QrEncoder.kt, the part of ZXing 3.5.3 the app uses, which the iPhone draws with) against
 * ZXing itself, which Android draws with: the same mode, version, mask and modules for every text tried, and
 * qrPixels the same pixels as Qr.matrix. That is why the JVM's qrPixels is the common encoder too; ZXing stays on
 * Android for Qr.matrix (the label PDFs) and for reading codes with the camera.
 */
class QrEncoderJvmTest {
    private val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 1)

    private fun same(text: String) {
        val z = Encoder.encode(text, ErrorCorrectionLevel.M, hints)
        val c = QrEncoder.encode(text)
        assertEquals("mode of '$text'", z.mode.toString(), c.mode)
        assertEquals("version of '$text'", z.version.versionNumber, c.version)
        assertEquals("mask of '$text'", z.maskPattern, c.mask)
        val m = z.matrix
        assertEquals(m.width, c.size)
        for (y in 0 until m.height) for (x in 0 until m.width) {
            if ((m.get(x, y).toInt() == 1) != c[x, y]) throw AssertionError("'$text': module ($x, $y) differs")
        }
    }

    private fun randomText(r: Random): String {
        val len = when (r.nextInt(4)) {
            0 -> r.nextInt(1, 20)
            1 -> r.nextInt(20, 120)
            2 -> r.nextInt(120, 400)
            else -> r.nextInt(400, 1200)
        }
        val alphabet = when (r.nextInt(6)) {
            0 -> "0123456789"
            1 -> "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:"
            2 -> "abcdefghijklmnopqrstuvwxyzABC0123456789:+%.,-_~*"
            3 -> "aéüßÿ€ΟΔΟΣ日本\u0000ÿĀ"
            4 -> "HM2:L0123456789ABCDEF"
            else -> null
        }
        return if (alphabet == null) {
            buildString { repeat(len) { append(r.nextInt(0, 0x10000).toChar()) } }
        } else {
            buildString { repeat(len) { append(alphabet[r.nextInt(alphabet.length)]) } }
        }
    }

    @Test
    fun theCommonEncoderMakesZxingsModules() {
        val r = Random(2026)
        repeat(3000) { same(randomText(r)) }
        for (t in listOf("0", "A", "a", "HM:L042", labelQrText("L042", "8F0B"), "🏠", "x\uD800y", "\uDC00", "1".repeat(2000), "A".repeat(1500))) same(t)
        // Every length of a typical invite, to go through the versions.
        val invite = "HMSYNC:192.168.1.23,10.0.0.7:40123:GQ7K2MXP:00000000-0000-4000-8000-000000000020:DECIDER:Priya+%F0%9F%8F%A0:m3906c46cfd44f4074ce7:"
        for (n in 0..600) same(invite + "M".repeat(n) + "a")
    }

    @Test
    fun qrPixelsAreQrMatrixPixels() {
        val r = Random(5)
        repeat(300) {
            val text = randomText(r).take(300)
            val px = listOf(0, 10, 100, 240, 333, 640, 1000)[r.nextInt(7)]
            val z = Qr.matrix(text, px)
            val p = qrPixels(text, px)
            assertEquals(z.width, p.size)
            for (y in 0 until z.height) for (x in 0 until z.width) {
                if (z.get(x, y) != p[x, y]) throw AssertionError("'$text' at $px px: pixel ($x, $y) differs")
            }
        }
    }
}
