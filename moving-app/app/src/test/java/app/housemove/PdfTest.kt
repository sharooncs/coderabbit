package app.housemove

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import app.housemove.data.*
import app.housemove.pdf.Csv
import app.housemove.pdf.LabelSheet
import app.housemove.pdf.Pdf
import app.housemove.pdf.labelCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * PdfDocument itself is not available in the JVM test runtime, so these tests
 * draw the same pages onto bitmaps: every printed label must scan back to its code.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class PdfTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun render(w: Int, h: Int, scale: Int, draw: (Canvas) -> Unit): Bitmap {
        val bmp = Bitmap.createBitmap(w * scale, h * scale, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        val c = Canvas(bmp)
        c.scale(scale.toFloat(), scale.toFloat())
        draw(c)
        return bmp
    }

    private fun save(bmp: Bitmap, name: String) {
        val f = File("build/screens/$name").apply { parentFile?.mkdirs() }
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun everyPrintedLabelScansBackToItsCode() {
        val decoder = Qr.Decoder()
        for (sheet in LabelSheet.entries) {
            val codes = (1..sheet.perPage).map { labelCode(it + 100) }
            // 2 px per point is about 144 dpi, well below a printer and close to what a phone sees.
            val scale = 2
            val bmp = render(sheet.pageW, sheet.pageH, scale) { Pdf.drawLabelPage(it, sheet, codes) }
            save(bmp, "labels-${sheet.name}.png")
            codes.forEachIndexed { i, code ->
                val x = ((sheet.left + (i % sheet.cols) * (sheet.cellW + sheet.gapX)) * scale).toInt()
                val y = ((sheet.top + (i / sheet.cols) * (sheet.cellH + sheet.gapY)) * scale).toInt()
                val w = (sheet.cellW * scale).toInt().coerceAtMost(bmp.width - x)
                val h = (sheet.cellH * scale).toInt().coerceAtMost(bmp.height - y)
                val px = IntArray(w * h)
                bmp.getPixels(px, 0, w, x, y, w, h)
                val luma = ByteArray(w * h) { n -> val p = px[n]; ((Color.red(p) + Color.green(p) + Color.blue(p)) / 3).toByte() }
                assertEquals("${sheet.name} label $i", QR_PREFIX + code, decoder.decode(luma, w, h))
            }
        }
    }

    @Test
    fun doorSignsDrawForLongAndShortNames() {
        val s = Store(tmp.newFolder("signs"))
        val rooms = listOf(
            s.createRoom(Home.DEST, "Kitchen").copy(floor = "Ground floor"),
            s.createRoom(Home.DEST, "Children's bedroom at the back of the house").copy(floor = "Upstairs, second door on the left past the bathroom"),
        )
        rooms.forEachIndexed { i, room ->
            save(render(842, 595, 1) { Pdf.drawDoorSign(it, 842, 595, room, "New home") }, "door-sign-$i.png")
        }
    }

    @Test
    fun csvEscapesAndListsEverything() {
        val s = Store(tmp.newFolder("csv"))
        val k = s.createRoom(Home.DEST, "Kitchen")
        s.put(Item(id = "i", name = "Table, \"oak\"", destRoomId = k.id, disposition = Disposition.MOVE, damaged = true, damageNote = "Leg cracked"))
        repeat(80) { n -> s.put(Box(id = "b$n", label = labelCode(n + 1), contents = "Things\nmore", destRoomId = k.id)) }
        val csv = Csv.inventory(context, s.data.value).readText()
        assertEquals(82, csv.trim().lines().size)
        assertTrue(csv.contains("\"Table, \"\"oak\"\"\""))
    }
}
