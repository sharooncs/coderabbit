package app.housemove

import app.housemove.data.*
import app.housemove.sync.WifiSync
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket

/** Barcodes that are already on things, electronics details, and phones on different versions. */
class BarcodeTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(name: String) = Store(tmp.newFolder(name))

    /** A camera frame showing one barcode, optionally a quarter turn round as a sideways phone sees it. */
    private fun frame(text: String, format: BarcodeFormat, w: Int, h: Int, sideways: Boolean = false): Triple<ByteArray, Int, Int> {
        val m = MultiFormatWriter().encode(text, format, w, h)
        val fw = 640
        val fh = 480
        val luma = ByteArray(fw * fh) { 160.toByte() }
        for (y in 0 until m.height) for (x in 0 until m.width) {
            val (fx, fy) = if (sideways) (300 + y) to (40 + x) else (140 + x) to (180 + y)
            luma[fy * fw + fx] = (if (m.get(x, y)) 25 else 235).toByte()
        }
        return Triple(luma, fw, fh)
    }

    @Test
    fun bookAndProductBarcodesAreReadEitherWayRound() {
        val products = Qr.Decoder(ScanFormats.PRODUCTS)
        for (sideways in listOf(false, true)) {
            val (isbn, w, h) = frame("9780140328721", BarcodeFormat.EAN_13, 330, 90, sideways)
            assertEquals("9780140328721", products.decode(isbn, w, h))
            val (ean8, w2, h2) = frame("96385074", BarcodeFormat.EAN_8, 240, 90, sideways)
            assertEquals("96385074", products.decode(ean8, w2, h2))
        }
        val (upc, w, h) = frame("036000291452", BarcodeFormat.UPC_A, 330, 90)
        assertEquals("036000291452", products.decode(upc, w, h))
        assertNull(products.decode(ByteArray(640 * 480) { 128.toByte() }, 640, 480))
    }

    @Test
    fun serialNumberBarcodesAreRead() {
        val serials = Qr.Decoder(ScanFormats.SERIALS)
        for (sideways in listOf(false, true)) {
            val (code128, w, h) = frame("0AB3HCPT500123X", BarcodeFormat.CODE_128, 380, 70, sideways)
            assertEquals("0AB3HCPT500123X", serials.decode(code128, w, h))
        }
        val (code39, w, h) = frame("SN12345678", BarcodeFormat.CODE_39, 420, 70)
        assertEquals("SN12345678", serials.decode(code39, w, h))
    }

    @Test
    fun eachScannerOnlyAcceptsItsOwnKindOfCode() {
        val (isbn, w, h) = frame("9780140328721", BarcodeFormat.EAN_13, 330, 90)
        val (qr, qw, qh) = frame(QR_PREFIX + "L042", BarcodeFormat.QR_CODE, 200, 200)
        // The move-day label scanner must never mistake a book in view for a box label.
        assertNull(Qr.Decoder(ScanFormats.LABELS).decode(isbn, w, h))
        assertEquals(QR_PREFIX + "L042", Qr.Decoder(ScanFormats.LABELS).decode(qr, qw, qh))
        // The scan-into-box scanner ignores HouseMove stickers.
        assertNull(Qr.Decoder(ScanFormats.PRODUCTS).decode(qr, qw, qh))
        // "What is this?" reads both.
        val any = Qr.Decoder(ScanFormats.ANY)
        assertEquals("9780140328721", any.decode(isbn, w, h))
        assertEquals(QR_PREFIX + "L042", any.decode(qr, qw, qh))
    }

    @Test
    fun isbnAndBarcodeNumberRecognition() {
        assertTrue(isIsbn("9780140328721"))
        assertTrue(isIsbn("978-0-14-032872-1"))
        assertTrue(isIsbn("0140328726"))
        assertTrue(isIsbn("080442957X"))
        assertFalse(isIsbn("9780140328722")) // wrong check digit
        assertFalse(isIsbn("5012345678900")) // a product, not a book
        assertFalse(isIsbn("Dune"))
        assertTrue(looksLikeBarcodeNumber("5012345678900"))
        assertTrue(looksLikeBarcodeNumber("96385074"))
        assertFalse(looksLikeBarcodeNumber("Garden tools"))
        assertFalse(looksLikeBarcodeNumber("L042"))
        assertFalse(looksLikeBarcodeNumber("1984")) // a title that happens to be digits
    }

    @Test
    fun catalogueReplyIsUnderstood() {
        val reply = """{"ISBN:9780140328721": {"url": "https://openlibrary.org/books/OL7353617M/Fantastic_Mr._Fox",
            "key": "/books/OL7353617M", "title": "Fantastic Mr. Fox", "authors": [{"url": "https://openlibrary.org/authors/OL34184A/Roald_Dahl",
            "name": "Roald Dahl"}, {"name": "Quentin Blake"}], "number_of_pages": 96, "publish_date": "October 1, 1988"}}"""
        assertEquals(BookInfo("Fantastic Mr. Fox", "Roald Dahl, Quentin Blake"), BookLookup.parse(reply))
        assertEquals(BookInfo("Dune: A novel", ""), BookLookup.parse("""{"ISBN:1": {"title": "Dune", "subtitle": "A novel"}}"""))
        assertNull(BookLookup.parse("{}")) // not in the catalogue
        assertNull(BookLookup.parse("<html>Service unavailable</html>"))
        assertNull(BookLookup.parse("""{"ISBN:1": {"authors": []}}"""))
        assertTrue(BookLookup.url("9780140328721").startsWith("https://openlibrary.org/api/books?bibkeys=ISBN:9780140328721"))
    }

    @Test
    fun scanningThingsIntoBoxes() {
        val s = store("scan")
        val a = s.createBox("L001", null)
        val b = s.createBox("L002", null)

        val first = s.scanInto(a.id, "9780140328721", title = "Fantastic Mr. Fox", author = "Roald Dahl")
        assertTrue(first is ScanIn.Added)
        assertTrue(s.scanInto(a.id, "9780140328721") is ScanIn.AlreadyHere)
        assertEquals(1, s.data.value.entriesOf(a.id).size)

        // A book is one physical thing: scanning it into another box moves it.
        val moved = s.scanInto(b.id, "9780140328721")
        assertTrue(moved is ScanIn.Moved && moved.fromLabel == "L001")
        assertTrue(s.data.value.entriesOf(a.id).isEmpty())
        assertEquals("Fantastic Mr. Fox", s.data.value.entriesOf(b.id).single().title)

        // Identical products share a barcode, so they can be in two boxes; a name given once is reused.
        s.scanInto(a.id, "5012345678900").entry.let { s.put(it.copy(title = "Jam jars")) }
        val again = s.scanInto(b.id, "5012345678900")
        assertTrue(again is ScanIn.Added)
        assertEquals("Jam jars", again.entry.title)

        // Typed names with no barcode.
        s.scanInto(a.id, "", title = "Photo albums")
        s.scanInto(a.id, "", title = "Photo albums")
        assertEquals(3, s.data.value.entriesOf(a.id).size)

        val d = s.data.value
        assertEquals("L002", d.search("fantastic fox").single().movable.label)
        assertEquals("L002", d.search("dahl").single().movable.label)
        assertEquals(setOf("L001", "L002"), d.search("jam").map { it.movable.label }.toSet())
        assertEquals("L001", d.search("albums").single().movable.label)
        assertEquals("L002", d.findByAnyCode("9780140328721")!!.label)
        assertNull(d.findByLabel("9780140328721")) // a book barcode is never a box label
        assertTrue(d.issues().none { it.text.contains("no contents") }) // scanned boxes count as described

        // Deleting the box removes its list, on every phone.
        s.delete(d.box(b.id)!!)
        assertTrue(s.data.value.entriesOf(b.id).isEmpty())
        assertNull(s.data.value.findByAnyCode("9780140328721"))
        val other = store("other")
        other.mergeIn(d)
        other.mergeIn(s.data.value)
        assertTrue(other.data.value.liveEntries.none { it.boxId == b.id })
    }

    @Test
    fun twoPhonesScanningIntoTheSameBoxKeepBothLists() {
        val a = store("a")
        val b = store("b")
        val box = a.createBox("L001", null)
        b.mergeIn(a.data.value)
        a.scanInto(box.id, "", title = "Dune")
        b.scanInto(box.id, "", title = "Emma")
        b.mergeIn(a.mergeIn(b.data.value))
        for (s in listOf(a, b)) assertEquals(setOf("Dune", "Emma"), s.data.value.entriesOf(box.id).map { it.title }.toSet())
    }

    @Test
    fun electronicsAreRecognisedSearchableAndFlagged() {
        assertTrue(looksElectronic("Samsung TV"))
        assertTrue(looksElectronic("Fridge-freezer"))
        assertTrue(looksElectronic("Washing machine"))
        assertTrue(looksElectronic("Kids' PlayStation"))
        assertFalse(looksElectronic("Coffee table"))
        assertFalse(looksElectronic("TV stand"))
        assertFalse(looksElectronic("Computer desk"))
        assertFalse(looksElectronic("Speaker brackets"))
        assertFalse(looksElectronic("Oak wardrobe"))
        assertFalse(looksElectronic("Hooverphonic vinyl"))

        val s = store("tv")
        val tv = s.createItem("Living room TV", null)
        s.put(s.data.value.item(tv.id)!!.copy(disposition = Disposition.MOVE, make = "Samsung", model = "QE55Q60", serial = "0AB3HCPT500123X", keepUpright = true))
        val bike = s.createItem("Road bike", null)
        s.put(s.data.value.item(bike.id)!!.copy(disposition = Disposition.MOVE, electronic = true))
        val lamp = s.createItem("Old monitor", null)
        s.put(s.data.value.item(lamp.id)!!.copy(disposition = Disposition.MOVE, electronic = false))
        val d = s.data.value

        assertTrue(d.item(tv.id)!!.isElectronic())
        assertTrue(d.item(bike.id)!!.isElectronic())   // switched on by hand
        assertFalse(d.item(lamp.id)!!.isElectronic())  // switched off by hand
        assertEquals("Samsung QE55Q60", d.item(tv.id)!!.makeModel())
        assertEquals(tv.id, d.search("qe55q60").single().movable.id)
        assertEquals(tv.id, d.search("0ab3hcpt").single().movable.id)
        assertEquals(tv.id, d.findByAnyCode("0ab3hcpt500123x")!!.id)
        assertTrue(d.item(tv.id)!!.toMovable().upright)
        assertTrue(Box(id = "b", label = "L1", thisWayUp = true).toMovable().upright)
        // Only the bike is missing a serial number: the TV has one, the monitor opted out.
        assertTrue(d.issues().any { it.route == "electronics" && it.text.startsWith("1 device") })

        assertTrue(handlingTips("Samsung TV").any { it.contains("upright") })
        assertTrue(handlingTips("Fridge-freezer").any { it.contains("defrost", ignoreCase = true) })
        assertTrue(handlingTips("Washing machine").any { it.contains("transit bolts") })
        assertTrue(handlingTips("Oak wardrobe").isEmpty())
    }

    @Test
    fun phonesOnDifferentVersionsRefuseToSyncAndNewerBackupsAreRefused() {
        val a = store("host")
        a.createBox("L001", null)
        var hostError: String? = null
        val host = WifiSync.host(a) { hostError = it.error }
        try {
            // A phone on a newer data layout.
            Socket("127.0.0.1", host.port).use { s ->
                val out = DataOutputStream(s.getOutputStream())
                val din = DataInputStream(s.getInputStream())
                out.writeUTF("HMSYNC2"); out.writeUTF(host.token); out.writeUTF("Future"); out.writeUTF("F"); out.writeInt(SCHEMA + 1); out.flush()
                assertEquals("VERSION", din.readUTF())
            }
            Thread.sleep(200)
            assertEquals(WifiSync.VERSION_MISMATCH, hostError)
            // A phone on the first version of the protocol.
            Socket("127.0.0.1", host.port).use { s ->
                val out = DataOutputStream(s.getOutputStream())
                out.writeUTF("HMSYNC1"); out.writeUTF(host.token); out.writeUTF("Old"); out.writeUTF("O"); out.flush()
                assertEquals("NO", DataInputStream(s.getInputStream()).readUTF())
            }
            assertEquals(1, a.data.value.liveBoxes.size)
            // Same version still syncs.
            val b = store("joiner")
            assertTrue(WifiSync.join(b, host.invite(listOf("127.0.0.1"))).ok)
        } finally {
            host.stop()
        }

        val future = AppData(version = SCHEMA + 1, boxes = listOf(Box(id = "x", label = "Z9")))
        val zip = ByteArrayOutputStream().also { o ->
            java.util.zip.ZipOutputStream(o).use { z ->
                z.putNextEntry(java.util.zip.ZipEntry("data.json"))
                z.write(AppJson.encodeToString(AppData.serializer(), future).toByteArray())
                z.closeEntry()
            }
        }.toByteArray()
        try {
            Backup.import(a, ByteArrayInputStream(zip))
            fail("a newer backup must be refused")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("newer version"))
        }
        assertNull(a.data.value.findByLabel("Z9"))
        // The version is always written, so an older app can tell.
        assertTrue(AppJson.encodeToString(AppData.serializer(), AppData()).contains("\"version\":$SCHEMA"))
    }

    @Test
    fun dataSavedByVersionOneStillLoadsWithNewFieldsEmpty() {
        val dir = tmp.newFolder("v1")
        java.io.File(dir, "data.json").writeText(
            """{"move":{"name":"M","setupDone":true},"items":[{"id":"i1","name":"Television","disposition":"MOVE"}],
               "boxes":[{"id":"b1","label":"L001","contents":"Books"}]}"""
        )
        val d = Store(dir).data.value
        assertEquals("", d.item("i1")!!.serial)
        assertTrue(d.item("i1")!!.isElectronic())
        assertTrue(d.liveEntries.isEmpty())
        assertEquals(SCHEMA, d.version)
    }
}
