@file:OptIn(ExperimentalForeignApi::class)

package app.housemove

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.housemove.data.AppFolders
import app.housemove.data.File
import app.housemove.data.Home
import app.housemove.data.PhotoKind
import app.housemove.data.Store
import app.housemove.data.labelQrText
import app.housemove.pdf.Decisions
import app.housemove.pdf.DrawnPapers
import app.housemove.pdf.HelperCard
import app.housemove.pdf.IosPaperWriter
import app.housemove.pdf.LabelSheet
import app.housemove.pdf.TagSheet
import app.housemove.pdf.labelCode
import app.housemove.ui.AppBackground
import app.housemove.ui.HouseMoveTheme
import app.housemove.ui.Night
import app.housemove.ui.iphone.DemoMove
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.CoreGraphics.CGImageCreateWithImageInRect
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.CoreImage.CIDetector
import platform.CoreImage.CIDetectorAccuracy
import platform.CoreImage.CIDetectorAccuracyHigh
import platform.CoreImage.CIDetectorTypeQRCode
import platform.CoreImage.CIImage
import platform.CoreImage.CIQRCodeFeature
import platform.Foundation.NSFileManager
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSURL
import platform.Foundation.writeToFile
import platform.PDFKit.PDFDocument
import platform.PDFKit.kPDFDisplayBoxMediaBox
import platform.UIKit.UIColor
import platform.UIKit.UIGraphicsImageRenderer
import platform.UIKit.UIGraphicsImageRendererFormat
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIRectFill

/*
 * The PDF check's screen, for the UI tests in CI (LaunchTests.testPrintoutsComeOutOnTheIphone; step 3b): launched with
 * `-HMPdfCheck YES`, the app makes every printout from the sample move with the iPhone's own drawing (UIKit's PDF
 * renderer, pdf/IosDraw.kt), reads each back with PDFKit, and shows one line per check and the summary, "PDF check
 * passed" or "PDF check failed": the pages and their sizes, the words on them, every QR code on a label sheet and a
 * sheet of hang tags read back by CoreImage as the code it was printed for, and the photos in the damage report and Ask
 * for decisions. Like demo mode, only Xcode and the UI tests can pass launch arguments. Its files are in Caches.
 */

/** Was the app launched with `-HMPdfCheck YES`? */
internal fun pdfCheckRequested(): Boolean {
    val args = NSProcessInfo.processInfo.arguments.map { it.toString() }
    val i = args.indexOf("-HMPdfCheck")
    return i >= 0 && args.getOrNull(i + 1) == "YES"
}

@Composable
internal fun PdfCheckScreen() {
    var lines by remember { mutableStateOf(listOf("PDF check running")) }
    LaunchedEffect(Unit) {
        lines = withContext(Dispatchers.Default) {
            val result = try {
                PdfCheck.run()
            } catch (e: Throwable) {
                listOf(PdfCheck.Line(false, "the check itself: ${e.message ?: e.toString()}"))
            }
            listOf(if (result.all { it.ok }) "PDF check passed" else "PDF check failed") + result.map { it.text }
        }
    }
    HouseMoveTheme {
        AppBackground {
            Column(
                Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                for (line in lines) Text(line, color = Night.Text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

internal object PdfCheck {
    class Line(val ok: Boolean, what: String) {
        val text = (if (ok) "Check passed: " else "Check failed: ") + what
    }

    private class Read(val pages: Int, val text: String, val bytes: Long, val doc: PDFDocument?)

    private fun read(file: File): Read {
        // A file PDFKit cannot read gives no document: the checks then say what is missing.
        val doc: PDFDocument? = try {
            PDFDocument(uRL = NSURL.fileURLWithPath(file.path))
        } catch (_: Throwable) {
            null
        }
        val size = (NSFileManager.defaultManager.contentsAtPath(file.path)?.length ?: 0uL).toLong()
        return Read(doc?.pageCount?.toInt() ?: 0, doc?.string ?: "", size, doc)
    }

    /** The page's size in points. */
    private fun pageSize(doc: PDFDocument?, index: Int): Pair<Int, Int> =
        doc?.pageAtIndex(index.toULong())?.boundsForBox(kPDFDisplayBoxMediaBox)?.useContents { size.width.toInt() to size.height.toInt() } ?: (0 to 0)

    /**
     * The QR code in each box of [boxes] (points from the top left) on page [index], read by CoreImage from the page
     * drawn at three pixels per point; null where none was read.
     */
    private fun codes(doc: PDFDocument?, index: Int, boxes: List<List<Float>>): List<String?> {
        val page = doc?.pageAtIndex(index.toULong()) ?: return boxes.map { null }
        val (w, h) = pageSize(doc, index)
        val k = 3.0
        val size = CGSizeMake(w * k, h * k)
        val thumbnail = page.thumbnailOfSize(size, forBox = kPDFDisplayBoxMediaBox)
        // On white, one pixel per pixel, as a printer's paper is: the page itself may be transparent.
        val format = UIGraphicsImageRendererFormat.preferredFormat().apply {
            scale = 1.0
            opaque = true
        }
        val image = UIGraphicsImageRenderer(size = size, format = format).imageWithActions { _ ->
            UIColor.whiteColor.setFill()
            UIRectFill(CGRectMake(0.0, 0.0, w * k, h * k))
            thumbnail.drawInRect(CGRectMake(0.0, 0.0, w * k, h * k))
        }
        val cg = image.CGImage ?: return boxes.map { null }
        val detector = CIDetector.detectorOfType(CIDetectorTypeQRCode, context = null, options = mapOf(CIDetectorAccuracy to CIDetectorAccuracyHigh))
        return boxes.map { (x, y, bw, bh) ->
            val cell = CGImageCreateWithImageInRect(cg, CGRectMake(x * k, y * k, bw * k, bh * k)) ?: return@map null
            val features = detector?.featuresInImage(CIImage(cGImage = cell)) ?: emptyList<Any?>()
            CGImageRelease(cell)
            features.firstNotNullOfOrNull { (it as? CIQRCodeFeature)?.messageString }
        }
    }

    /** A plain JPEG photo of [color] in [file]. */
    private fun photo(file: File, color: UIColor) {
        val image = UIGraphicsImageRenderer(size = CGSizeMake(640.0, 480.0)).imageWithActions { _ ->
            color.setFill()
            UIRectFill(CGRectMake(0.0, 0.0, 640.0, 480.0))
        }
        UIImageJPEGRepresentation(image, 0.9)?.writeToFile(file.path, atomically = true)
    }

    fun run(): List<Line> {
        val out = ArrayList<Line>()
        fun check(ok: Boolean, what: String) {
            out += Line(ok, what)
        }

        val root = File(AppFolders.caches, "pdf-check")
        NSFileManager.defaultManager.removeItemAtPath(root.path, error = null)
        val outDir = File(root, "out")
        for (d in listOf(root, outDir)) NSFileManager.defaultManager.createDirectoryAtPath(d.path, withIntermediateDirectories = true, attributes = null, error = null)
        val store = Store(File(root, "move"))
        DemoMove.fill(store)
        // Each printout in a file of its own: the same name made twice (A4, then Letter) must not read back the first.
        var made = 0
        val papers = DrawnPapers(IosPaperWriter) { File(outDir, "${++made}-$it") }
        val data = store.data.value
        val tag = "K7Q2"

        // A sheet of labels: one page per 21, A4, every code read back from its QR code.
        val codes = (1..23).map { labelCode(it + 100) }
        val sheet = LabelSheet.A4_21
        val labels = read(papers.labels(codes, sheet, 1, tag))
        check(labels.pages == 2, "labels: 23 codes on A4 sheets of 21 make 2 pages (${labels.pages})")
        check(pageSize(labels.doc, 0) == (595 to 842), "labels: the page is A4, 595 by 842 points (${pageSize(labels.doc, 0)})")
        check(codes.all { it in labels.text } && "HouseMove" in labels.text, "labels: every code is printed in words")
        val cells = (0 until sheet.perPage).map { i ->
            val x = sheet.left + (i % sheet.cols) * (sheet.cellW + sheet.gapX)
            val y = sheet.top + (i / sheet.cols) * (sheet.cellH + sheet.gapY)
            listOf(x, y, sheet.cellH, sheet.cellH)
        }
        val read = codes(labels.doc, 0, cells)
        val wrong = codes.take(sheet.perPage).indices.filter { read[it] != labelQrText(codes[it], tag) }
        check(wrong.isEmpty(), "labels: all 21 QR codes on the first page read back as their codes" + if (wrong.isEmpty()) "" else " (not: ${wrong.map { codes[it] to read[it] }})")
        val letter = read(papers.labels(codes.take(3), LabelSheet.LETTER_10, 2, tag))
        check(letter.pages == 1 && pageSize(letter.doc, 0) == (612 to 792), "labels: Letter sheets are 612 by 792 points, 6 labels on 1 page (${letter.pages}, ${pageSize(letter.doc, 0)})")

        // Hang tags: the codes read back, and their words.
        val tagSheet = TagSheet.A4_TAGS
        val tagCodes = (1..tagSheet.perPage).map { if (it % 2 == 0) labelCode(it + 200) else "B$it" }
        val tags = read(papers.hangTags(tagCodes, tagSheet, tag))
        check(tags.pages == 1 && "Name / what's inside:" in tags.text, "hang tags: 1 page with the line to write on")
        val tagCells = tagCodes.indices.map { i ->
            val x = tagSheet.left + (i % tagSheet.cols) * tagSheet.cellW
            val y = tagSheet.top + (i / tagSheet.cols) * tagSheet.cellH
            listOf(x, y, tagSheet.cellW, tagSheet.cellH)
        }
        val tagRead = codes(tags.doc, 0, tagCells)
        val tagWrong = tagCodes.indices.filter { tagRead[it] != labelQrText(tagCodes[it], tag) }
        check(tagWrong.isEmpty(), "hang tags: all 8 QR codes read back as their codes" + if (tagWrong.isEmpty()) "" else " (not: ${tagWrong.map { tagCodes[it] to tagRead[it] }})")

        // Door signs: a landscape page per new-home room, with its name and code.
        val rooms = data.roomsOf(Home.DEST)
        val signs = read(papers.doorSigns(data, letter = false))
        check(signs.pages == rooms.size && pageSize(signs.doc, 0) == (842 to 595), "door signs: ${rooms.size} landscape A4 pages (${signs.pages})")
        check(rooms.all { it.name in signs.text }, "door signs: every new-home room's name is on its sign")

        // The Helper card, both papers.
        for (paper in HelperCard.Paper.entries) {
            val card = read(papers.helperCard(data, paper))
            check(
                card.pages == 1 && pageSize(card.doc, 0) == (paper.w to paper.h) && "Helping with Sample move" in card.text && "Door signs" in card.text,
                "Helper card (${paper.name}): one page of ${paper.w} by ${paper.h} points, with its steps and door signs" +
                    " (${card.pages}, ${pageSize(card.doc, 0)}, ${"Helping with Sample move" in card.text}, ${"Door signs" in card.text})",
            )
        }

        // The inventory and its spreadsheet.
        val inventory = read(papers.inventory(data))
        check(inventory.pages >= 1 && "Sample move · inventory" in inventory.text && "Kettle" in inventory.text && "A1" in inventory.text, "inventory: the move's things and boxes")
        val csv = papers.inventoryCsv(data)
        val rows = (NSFileManager.defaultManager.contentsAtPath(csv.path)?.length ?: 0uL).toLong()
        check(rows > 200, "inventory spreadsheet: written ($rows bytes)")

        // The damage report and Ask for decisions, before and after a photo: the photo is in the PDF.
        val box = data.liveBoxes.first { it.label == "A2" }
        store.put(box.copy(damaged = true, damageNote = "Corner crushed"))
        val without = read(papers.damageReport(store))
        val damagePhoto = store.newPhotoFile().also { photo(it, UIColor.redColor) }
        store.addPhoto(box.id, PhotoKind.DAMAGE, damagePhoto)
        val damage = read(papers.damageReport(store))
        check(damage.pages == 1 && "damage report" in damage.text && "Corner crushed" in damage.text, "damage report: the damaged box and its note")
        check(damage.bytes > without.bytes + 1000, "damage report: the photo is in it (${without.bytes} bytes without, ${damage.bytes} with)")
        val lamp = data.liveItems.first { it.name == "Desk lamp" }
        val plain = read(papers.decisions(store))
        store.addPhoto(lamp.id, PhotoKind.GENERAL, store.newPhotoFile().also { photo(it, UIColor.blueColor) })
        val asked = read(papers.decisions(store))
        val n = Decisions.toAsk(store.data.value).size
        check(asked.pages == Decisions.pages(n) && "what should happen to these?" in asked.text && "Desk lamp" in asked.text, "Ask for decisions: $n thing(s) on ${asked.pages} page(s)")
        check(asked.bytes > plain.bytes + 1000, "Ask for decisions: the photo is in it (${plain.bytes} bytes without, ${asked.bytes} with)")
        store.close()
        return out
    }
}
