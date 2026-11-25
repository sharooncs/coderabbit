package app.housemove

import app.housemove.data.QrEncoder
import app.housemove.data.qrPixels
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The common QR encoder (QrEncoder.kt, the part of ZXing the app uses) against the QR codes Android 2.1.1 draws
 * (golden/qr.json), module for module, on every platform: a label printed on an iPhone is the label an Android phone
 * prints. QrEncoderJvmTest checks it against ZXing itself on many more texts.
 */
class QrCommonTest {
    private val matrices = Json.parseToJsonElement(GoldenFiles.text.getValue("qr.json")).jsonObject.getValue("matrices").jsonArray.map { it.jsonObject }

    @Test
    fun theGoldenQrCodesComeOutModuleForModule() {
        assertEquals(4, matrices.size)
        for (m in matrices) {
            val name = m.getValue("name").jsonPrimitive.content
            val text = m.getValue("text").jsonPrimitive.content
            val code = QrEncoder.encode(text)
            assertEquals(m.getValue("mode").jsonPrimitive.content, code.mode, "$name: mode")
            assertEquals(m.getValue("version").jsonPrimitive.int, code.version, "$name: version")
            assertEquals("M", m.getValue("errorCorrection").jsonPrimitive.content)
            assertEquals(m.getValue("mask").jsonPrimitive.int, code.mask, "$name: mask")
            assertEquals(m.getValue("modules").jsonPrimitive.int, code.size, "$name: modules")
            val rows = m.getValue("rows").jsonArray.map { it.jsonPrimitive.content }
            for (y in 0 until code.size) {
                val row = (0 until code.size).joinToString("") { x -> if (code[x, y]) "#" else "." }
                assertEquals(rows[y], row, "$name: row $y")
            }
        }
    }

    @Test
    fun theGoldenQrCodesAreDrawnAsLabelSheetsAndScreensDrawThem() {
        for (m in matrices) {
            val name = m.getValue("name").jsonPrimitive.content
            val text = m.getValue("text").jsonPrimitive.content
            val rows = m.getValue("rows").jsonArray.map { it.jsonPrimitive.content }
            val modules = rows.size
            for (d in m.getValue("drawn").jsonArray.map { it.jsonObject }) {
                val px = d.getValue("sizePx").jsonPrimitive.int
                val scale = d.getValue("scale").jsonPrimitive.int
                val pad = d.getValue("padding").jsonPrimitive.int
                assertTrue(d.getValue("isTheModulesScaled").jsonPrimitive.content == "true")
                val p = qrPixels(text, px)
                assertEquals(px, p.size, "$name at $px px")
                for (y in 0 until px) for (x in 0 until px) {
                    val mx = (x - pad).floorDiv(scale)
                    val my = (y - pad).floorDiv(scale)
                    val on = mx in 0 until modules && my in 0 until modules && rows[my][mx] == '#'
                    if (p[x, y] != on) throw AssertionError("$name at $px px: pixel ($x, $y) is ${p[x, y]}")
                }
            }
        }
    }

    @Test
    fun aDrawingSmallerThanTheCodeIsTheCodeWithItsQuietZone() {
        val p = qrPixels("HM:L042", 10)
        assertEquals(21 + 2, p.size)
        assertFailsWith<IllegalArgumentException> { qrPixels("", 100) }
    }
}
