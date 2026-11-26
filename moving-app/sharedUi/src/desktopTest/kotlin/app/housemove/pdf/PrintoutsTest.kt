package app.housemove.pdf

import app.housemove.data.Box
import app.housemove.data.Home
import app.housemove.data.PhotoKind
import app.housemove.data.Qr
import app.housemove.data.Store
import app.housemove.data.labelQrText
import app.housemove.ui.iphone.DemoMove
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The printouts' shared layout (Printouts.kt, HelperCard.kt, Decisions.kt; step 3b of design/ios-plan.md), as both phones
 * draw it, through Java2D here: the pages, the words on them, every QR code read back as the code it was printed for, and
 * the photos. Each page is saved to build/screens/pdf-<printout>-<page>.png to look at (the JVM's fonts, not Roboto or San
 * Francisco). Android draws the same layout on its canvas (its PdfTest), the iPhone with UIKit (the app's PDF check).
 */
class PrintoutsTest {
    private val out = File(System.getProperty("screens.out") ?: "build/screens").apply { mkdirs() }
    private val root = Files.createTempDirectory("hm-printouts").toFile()

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    /** The sample move the iPhone's demo mode joins (Sam's, with a kitchen, a lounge and boxes A1 to C1). */
    private fun sample(): Store = Store(File(root, "move")).also { DemoMove.fill(it) }

    private fun keep(name: String, doc: AwtDoc) {
        doc.pages.forEachIndexed { i, p -> ImageIO.write(p.image, "png", File(out, "pdf-$name-${i + 1}.png")) }
    }

    private fun words(doc: AwtDoc): String = doc.pages.joinToString("\n") { it.texts.joinToString("\n") }

    /** The QR code in the box [x], [y], [w], [h] (points) of [page], read with ZXing, as Android's scanner reads labels. */
    private fun code(page: AwtPage, scale: Int, x: Float, y: Float, w: Float, h: Float): String? {
        val px = (x * scale).toInt()
        val py = (y * scale).toInt()
        val pw = (w * scale).toInt().coerceAtMost(page.image.width - px)
        val ph = (h * scale).toInt().coerceAtMost(page.image.height - py)
        val luma = ByteArray(pw * ph)
        for (j in 0 until ph) for (i in 0 until pw) {
            val c = page.image.getRGB(px + i, py + j)
            luma[j * pw + i] = (((c shr 16 and 0xFF) + (c shr 8 and 0xFF) + (c and 0xFF)) / 3).toByte()
        }
        return Qr.Decoder().decode(luma, pw, ph)
    }

    private fun photo(file: File, color: Color, w: Int = 640, h: Int = 480) {
        val image = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply {
            this.color = color
            fillRect(0, 0, w, h)
            this.color = Color.BLACK
            fillOval(w / 4, h / 4, w / 2, h / 2)
            dispose()
        }
        ImageIO.write(image, "jpg", file)
    }

    @Test
    fun labelSheetsCarryEveryCodeAsAQrCodeAndInWords() {
        val codes = (1..23).map { labelCode(it + 100) }
        for (sheet in LabelSheet.entries) {
            val doc = AwtDoc()
            Printouts.labels(doc, codes, sheet, copies = 2, moveTag = "K7Q2")
            assertEquals((codes.size * 2 + sheet.perPage - 1) / sheet.perPage, doc.pages.size, "${sheet.name}: pages for 23 codes twice")
            assertTrue(doc.pages.all { it.w == sheet.pageW && it.h == sheet.pageH }, "${sheet.name}: the sheet's paper")
            val first = doc.pages[0]
            val printed = codes.flatMap { listOf(it, it) }.take(sheet.perPage)
            printed.forEachIndexed { i, c ->
                val x = sheet.left + (i % sheet.cols) * (sheet.cellW + sheet.gapX)
                val y = sheet.top + (i / sheet.cols) * (sheet.cellH + sheet.gapY)
                assertEquals(labelQrText(c, "K7Q2"), code(first, doc.scale, x, y, sheet.cellW, sheet.cellH), "${sheet.name} label $i")
            }
            assertTrue(codes.all { it in words(doc) } && "HouseMove" in first.texts && "Room:" in first.texts, "${sheet.name}: the codes in words")
            if (sheet == LabelSheet.A4_21) keep("labels", doc)
        }
    }

    @Test
    fun hangTagsCarryTheirCodesAndAPlaceToWrite() {
        for (sheet in TagSheet.entries) {
            val codes = (1..sheet.perPage + 1).map { if (it % 2 == 0) labelCode(it + 200) else "B$it" }
            val doc = AwtDoc()
            Printouts.hangTags(doc, codes, sheet, moveTag = "K7Q2")
            assertEquals(2, doc.pages.size, "${sheet.name}: a second page for the ninth tag")
            codes.take(sheet.perPage).forEachIndexed { i, c ->
                val x = sheet.left + (i % sheet.cols) * sheet.cellW
                val y = sheet.top + (i / sheet.cols) * sheet.cellH
                assertEquals(labelQrText(c, "K7Q2"), code(doc.pages[0], doc.scale, x, y, sheet.cellW, sheet.cellH), "${sheet.name} tag $i")
            }
            assertEquals(sheet.perPage, doc.pages[0].texts.count { it == "Name / what's inside:" })
            if (sheet == TagSheet.A4_TAGS) keep("tags", doc)
        }
    }

    @Test
    fun doorSignsAreAPagePerNewHomeRoom() {
        val data = sample().data.value
        val rooms = data.roomsOf(Home.DEST)
        for (letter in listOf(false, true)) {
            val doc = AwtDoc()
            Printouts.doorSigns(doc, data, letter)
            assertEquals(rooms.size, doc.pages.size)
            assertTrue(doc.pages.all { it.w == (if (letter) 792 else 842) && it.h == (if (letter) 612 else 595) }, "landscape")
            rooms.forEachIndexed { i, r -> assertTrue(r.name in doc.pages[i].texts, "${r.name} on its sign") }
            if (!letter) keep("door-signs", doc)
        }
        val none = AwtDoc()
        Printouts.doorSigns(none, app.housemove.data.AppData(), letter = false)
        assertEquals(listOf("No new-home rooms yet. Add them under More → Rooms."), none.pages.single().texts)
    }

    @Test
    fun theHelperCardHasTwoCardsWithTheStepsAndTheDoorSigns() {
        val data = sample().data.value
        for (paper in HelperCard.Paper.entries) {
            val doc = AwtDoc()
            HelperCard.document(doc, data, paper)
            val page = doc.pages.single()
            assertEquals(paper.w to paper.h, page.w to page.h)
            assertEquals(2, page.texts.count { it == "Helping with Sample move" }, "two cards")
            for (step in HelperCard.steps(data)) assertEquals(2, page.texts.count { it == step.title }, step.title)
            for ((_, code, _) in HelperCard.doorSigns(data)) assertTrue(page.texts.count { it == code } >= 2, "door sign $code")
            assertTrue("✂ cut here" in page.texts)
            if (paper == HelperCard.Paper.A4) keep("helper-card", doc)
        }
    }

    @Test
    fun theInventoryRunsOverPagesWithEveryThingAndBoxOnIt() {
        val s = sample()
        val kitchen = s.data.value.roomsOf(Home.DEST).first().id
        repeat(70) { n -> s.put(Box(id = "x$n", label = labelCode(n + 300), contents = "Things for the shed, set $n", destRoomId = kitchen)) }
        val data = s.data.value
        val doc = AwtDoc()
        Printouts.inventory(doc, data)
        assertTrue(doc.pages.size >= 2, "the boxes run on to a second page (${doc.pages.size})")
        assertTrue(doc.pages.all { it.w == 595 && it.h == 842 })
        val all = words(doc)
        assertTrue("Sample move · inventory" in all)
        for (i in data.liveItems) assertTrue(doc.pages.any { p -> p.texts.any { it.contains(i.name) } }, i.name)
        for (b in data.liveBoxes) assertTrue(b.label in all, b.label)
        keep("inventory", doc)
    }

    @Test
    fun theDamageReportHasEachDamagedThingWithItsPhotos() {
        val s = sample()
        val a2 = s.data.value.liveBoxes.first { it.label == "A2" }
        s.put(a2.copy(damaged = true, damageNote = "Corner crushed"))
        for ((kind, color) in listOf(PhotoKind.CONDITION to Color.GREEN, PhotoKind.DAMAGE to Color.RED, PhotoKind.DAMAGE to Color.ORANGE)) {
            val f = s.newPhotoFile()
            photo(f, color)
            s.addPhoto(a2.id, kind, f)
        }
        val doc = AwtDoc()
        Printouts.damageReport(doc, s)
        val all = words(doc)
        assertTrue("Sample move · damage report" in all && "Damage: Corner crushed" in all && "Before the move" in all && "After the move" in all)
        assertEquals(3, doc.pages.sumOf { it.pictures }, "every photo drawn")
        keep("damage-report", doc)
        val nothing = AwtDoc()
        Printouts.damageReport(nothing, Store(File(root, "undamaged")).also { DemoMove.fill(it) })
        assertTrue("Nothing has been marked as damaged." in words(nothing))
    }

    @Test
    fun askForDecisionsHasEachUndecidedThingWithItsNumberAndPhoto() {
        val s = sample()
        val living = s.data.value.roomsOf(Home.ORIGIN).first { it.name == "Living room" }.id
        repeat(7) { n -> s.createItem("Picture frame ${n + 1}", living) }
        val lamp = s.data.value.liveItems.first { it.name == "Desk lamp" }
        s.newPhotoFile().also { photo(it, Color.BLUE, 480, 640); s.addPhoto(lamp.id, PhotoKind.GENERAL, it) }
        val asked = Decisions.toAsk(s.data.value)
        assertEquals(8, asked.size)
        val doc = AwtDoc()
        Decisions.document(doc, s)
        assertEquals(Decisions.pages(asked.size), doc.pages.size)
        for (n in 1..asked.size) assertTrue(doc.pages.any { "$n" in it.texts }, "number $n")
        assertEquals(1, doc.pages.sumOf { it.pictures }, "the lamp's photo")
        assertEquals(asked.size - 1, doc.pages.sumOf { p -> p.texts.count { it == "No photo" } })
        assertTrue(doc.pages.all { p -> p.texts.any { it.startsWith("Sample move: what should happen to these?") } })
        keep("ask-for-decisions", doc)
    }

    @Test
    fun theInventorySpreadsheetHasAHeaderAndARowForEachThingAndBox() {
        val data = sample().data.value
        val csv = Printouts.inventoryCsv(data).trim().lines()
        assertEquals(1 + data.liveItems.size + data.liveBoxes.size + data.liveEntries.size, csv.size)
        assertTrue(csv[0].startsWith("Type,Label,Name / contents"))
    }

    /** The files the papers are made in have Android's names, in the folder they are shared from. */
    @Test
    fun drawnPapersMakeTheirFilesUnderAndroidsNames() {
        val s = sample()
        val folder = File(root, "out").apply { mkdirs() }
        val papers = DrawnPapers(AwtPaperWriter) { File(folder, it) }
        val data = s.data.value
        assertEquals("housemove-labels.pdf", papers.labels(listOf("L001"), LabelSheet.A4_21, 1, "").name)
        assertEquals("housemove-hang-tags.pdf", papers.hangTags(listOf("B1"), TagSheet.A4_TAGS, "").name)
        assertEquals("housemove-door-signs.pdf", papers.doorSigns(data, false).name)
        assertEquals("housemove-helper-card-letter.pdf", papers.helperCard(data, HelperCard.Paper.LETTER).name)
        assertEquals("housemove-inventory.pdf", papers.inventory(data).name)
        assertEquals("housemove-inventory.csv", papers.inventoryCsv(data).name)
        assertEquals("housemove-damage-report.pdf", papers.damageReport(s).name)
        assertEquals("sample-move-what-to-decide.pdf", papers.decisions(s).name)
        assertTrue(File(folder, "housemove-door-signs.pdf-4.png").isFile, "a page each")
    }
}
