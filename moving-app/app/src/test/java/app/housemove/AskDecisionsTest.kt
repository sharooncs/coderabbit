package app.housemove

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import app.housemove.data.*
import app.housemove.pdf.Decisions
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Ask for decisions (design 2.1, section 4): the picture list for family who never visit, drawn as the PDF draws it,
 * and the small copies of photos the app makes for update files and the daily backup.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class AskDecisionsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var now = 1_000_000L

    @After
    fun reset() {
        Shrink.jpeg = null
    }

    /** A canvas that keeps every line of text drawn on it. */
    private class Words(bmp: Bitmap) : Canvas(bmp) {
        val said = ArrayList<String>()
        override fun drawText(text: String, x: Float, y: Float, paint: Paint) {
            said += text
            super.drawText(text, x, y, paint)
        }
    }

    private fun picture(file: File, w: Int, h: Int) {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.rgb(180, 120, 60))
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
    }

    @Test
    fun theListHasEachUndecidedThingWithItsNumberPhotoNameAndRoomAndNoPrices() {
        val s = Store(tmp.newFolder("asha")) { now++ }
        s.setMove { it.copy(name = "Maple Road") }
        val kitchen = s.createRoom(Home.ORIGIN, "Kitchen")
        val hall = s.createRoom(Home.ORIGIN, "Hall")
        val names = (1..8).map { "Chair $it" }
        val items = names.map { s.createItem(it, if (it.endsWith("1")) hall.id else kitchen.id) }
        val decided = s.createItem("Fridge", kitchen.id)
        assertTrue(s.decide(decided.id, Disposition.SELL, 250.0).ok)
        assertTrue(s.setDetails(items[0].id) { it.copy(serial = "SN-77", value = 99.0) }.ok)
        val photo = s.newPhotoFile().also { picture(it, 400, 300) }
        s.addPhoto(items[1].id, PhotoKind.GENERAL, photo)
        val serial = s.newPhotoFile().also { picture(it, 400, 300) }
        s.addPhoto(items[2].id, PhotoKind.SERIAL, serial)

        val data = s.data.value
        val ask = Decisions.toAsk(data)
        assertEquals(8, ask.size)
        assertFalse(ask.any { it.id == decided.id })
        assertEquals(2, Decisions.pages(ask.size))
        // Room by room, as Triage lists them.
        assertEquals("Chair 1", ask.first { data.room(it.originRoomId)?.name == "Hall" }.name)
        // The serial label is never the picture.
        assertNull(Decisions.photoOf(data, items[2]))
        assertEquals(photo.name, Decisions.photoOf(data, items[1])?.file)

        val all = ArrayList<String>()
        for (page in 0 until Decisions.pages(ask.size)) {
            val bmp = Bitmap.createBitmap(595, 842, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
            val c = Words(bmp)
            var drawn = 0
            Decisions.drawPage(c, data, ask, page) { item -> if (item.id == items[1].id) { drawn++; Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888) } else null }
            all += c.said
            if (page == 0) assertTrue(c.said.any { it.startsWith("Maple Road: what should happen") })
            assertTrue(c.said.any { it.contains("Page ${page + 1} of 2") })
        }
        for (n in names) assertTrue("$n is on the list", n in all)
        for (i in 1..8) assertTrue("number $i is on the list", "$i" in all)
        assertTrue("Kitchen" in all && "Hall" in all)
        for (a in listOf("Move", "Sell", "Donate", "Bin", "Store")) assertTrue(a in all)
        // Nothing of what Helpers are not sent: no prices, values or serial numbers.
        assertFalse(all.any { "SN-77" in it || "250" in it || "99" in it || "Fridge" in it })
    }

    @Test
    fun anEmptyListSaysSo() {
        val s = Store(tmp.newFolder("empty")) { now++ }
        val bmp = Bitmap.createBitmap(595, 842, Bitmap.Config.ARGB_8888)
        val c = Words(bmp)
        Decisions.drawPage(c, s.data.value, emptyList(), 0) { null }
        assertTrue("Every item has a decision." in c.said)
    }

    @Test
    fun smallCopiesAreAbout1024PixelsAndSmallerThanThePhoto() {
        val big = File(tmp.root, "big.jpg").also { picture(it, 3000, 2000) }
        val small = app.housemove.ui.shrinkJpeg(big, Shrink.SIDE, Shrink.QUALITY)!!
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(small, 0, small.size, opts)
        assertEquals(1024, maxOf(opts.outWidth, opts.outHeight))
        assertTrue(small.size < big.length())
        // Through the data layer's hook, as update files and the daily backup make them.
        Shrink.jpeg = { f, side, q -> app.housemove.ui.shrinkJpeg(f, side, q) }
        assertArrayEquals(small, Shrink.copyOf(big))
        // Something that is not a picture goes as it is.
        val odd = File(tmp.root, "odd.jpg").apply { writeBytes(ByteArray(50) { 7 }) }
        assertArrayEquals(odd.readBytes(), Shrink.copyOf(odd))
    }
}
