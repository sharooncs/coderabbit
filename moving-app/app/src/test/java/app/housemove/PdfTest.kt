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
import app.housemove.pdf.TAG_HOLE_R
import app.housemove.pdf.TagSheet
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

    /** Hang tags for suitcases and bags: same codes and QR as the stickers, with a mark where to punch the hole. */
    @Test
    fun everyHangTagScansBackToItsCodeAndHasItsPunchMark() {
        val decoder = Qr.Decoder()
        assertEquals(2, TagSheet.entries.size) // A4 and Letter
        for (sheet in TagSheet.entries) {
            // The tags fill the page without running off it.
            assertEquals(sheet.pageW.toFloat(), 2 * sheet.left + sheet.cols * sheet.cellW, 0.6f)
            assertTrue(sheet.top + sheet.rows * sheet.cellH <= sheet.pageH)
            // Handwritten codes as well as printed ones, since luggage is often labelled by hand.
            val codes = (1..sheet.perPage).map { if (it % 2 == 0) labelCode(it + 200) else "B$it" }
            val scale = 2
            val bmp = render(sheet.pageW, sheet.pageH, scale) { Pdf.drawTagPage(it, sheet, codes) }
            save(bmp, "tags-${sheet.name}.png")
            codes.forEachIndexed { i, code ->
                val x = ((sheet.left + (i % sheet.cols) * sheet.cellW) * scale).toInt()
                val y = ((sheet.top + (i / sheet.cols) * sheet.cellH) * scale).toInt()
                val w = (sheet.cellW * scale).toInt().coerceAtMost(bmp.width - x)
                val h = (sheet.cellH * scale).toInt().coerceAtMost(bmp.height - y)
                val px = IntArray(w * h)
                bmp.getPixels(px, 0, w, x, y, w, h)
                val luma = ByteArray(w * h) { n -> val p = px[n]; ((Color.red(p) + Color.green(p) + Color.blue(p)) / 3).toByte() }
                assertEquals("${sheet.name} tag $i", QR_PREFIX + code, decoder.decode(luma, w, h))
                // The punch mark: a ring with a cross through it, clear of the code.
                val (hx, hy) = sheet.hole(i)
                fun dark(atX: Float, atY: Float) = bmp.getPixel((atX * scale).toInt(), (atY * scale).toInt()).let { Color.red(it) < 140 }
                assertTrue("${sheet.name} tag $i ring", dark(hx + TAG_HOLE_R, hy) || dark(hx + TAG_HOLE_R - 0.5f, hy))
                assertTrue("${sheet.name} tag $i cross", dark(hx, hy))
                assertTrue("${sheet.name} tag $i inside the ring is empty", !dark(hx + TAG_HOLE_R / 2, hy + TAG_HOLE_R / 2))
            }
        }
        // A part-filled page draws only the tags asked for.
        val one = render(TagSheet.A4_TAGS.pageW, TagSheet.A4_TAGS.pageH, 1) { Pdf.drawTagPage(it, TagSheet.A4_TAGS, listOf("B1")) }
        val (hx, hy) = TagSheet.A4_TAGS.hole(1)
        assertEquals(Color.WHITE, one.getPixel(hx.toInt(), hy.toInt()))
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

    @Test
    fun csvSaysWhatKindOfContainerEachIsAndWhatIsPackedInWhat() {
        val s = Store(tmp.newFolder("csv2"))
        val suitcase = s.createBox("B1", null, ContainerKind.SUITCASE)
        s.put(s.data.value.box(suitcase.id)!!.copy(category = "Clothes", name = "Suitcase 1", contents = "Shirts"))
        val bag = s.createBox("B2", null, ContainerKind.BAG)
        s.nest(bag.id, suitcase.id)
        val carton = s.createBox("L001", null)
        s.scanInto(carton.id, "9780140328721", title = "Fantastic Mr. Fox")
        val camera = s.createItem("Camera", null)
        s.putInto(camera.id, suitcase.id)
        s.createItem("Sofa", null)

        val lines = Csv.inventory(context, s.data.value).readText().trim().lines().map { it.split(",") }
        val header = lines[0]
        // The columns a spreadsheet may already use are where they were; the new ones are at the end.
        assertEquals(listOf("Type", "Label", "Name / contents"), header.take(3))
        assertEquals(listOf("Container kind", "Category", "Packed in"), header.takeLast(3))
        assertTrue(lines.all { it.size == header.size })
        fun row(type: String, name: String) = lines.first { it[0] == type && it[2] == name }
        assertEquals(listOf("Suitcase", "Clothes", "B1"), row("Item", "Camera").takeLast(3))
        assertEquals(listOf("", "", ""), row("Item", "Sofa").takeLast(3))
        assertEquals(listOf("Suitcase", "Clothes", ""), row("Box", "Suitcase 1: Shirts").takeLast(3))
        assertEquals(listOf("Bag", "", "B1"), lines.first { it[0] == "Box" && it[1] == "B2" }.takeLast(3))
        assertEquals(listOf("Carton", "", ""), lines.first { it[0] == "Box" && it[1] == "L001" }.takeLast(3))
        assertEquals(listOf("Carton", "", "L001"), row("In box", "Fantastic Mr. Fox").takeLast(3))
    }
}
