package app.housemove.pdf

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.widget.Toast
import androidx.core.content.FileProvider
import app.housemove.data.*
import app.housemove.ui.loadScaledBitmap
import java.io.File

// The sheets of labels and hang tags, and the codes on them, are in sharedUi (pdf/Sheets.kt): the screens choose them.

/** QR code as a bitmap with a quiet zone, black on white. */
fun qrBitmap(text: String, sizePx: Int): Bitmap {
    val matrix = Qr.matrix(text, sizePx)
    val pixels = IntArray(sizePx * sizePx)
    for (y in 0 until sizePx) for (x in 0 until sizePx) {
        pixels[y * sizePx + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
    }
    return Bitmap.createBitmap(pixels, sizePx, sizePx, Bitmap.Config.ARGB_8888)
}

private fun outFile(context: Context, name: String): File =
    File(File(context.cacheDir, "out").apply { mkdirs() }, name)

private fun textPaint(size: Float, bold: Boolean = false, color: Int = Color.BLACK) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    textSize = size
    this.color = color
    typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
}

/** Shrink the text size until [text] fits in [maxWidth]. */
private fun fitText(paint: Paint, text: String, maxWidth: Float, minSize: Float = 6f) {
    while (paint.measureText(text) > maxWidth && paint.textSize > minSize) paint.textSize -= 1f
}

private fun ellipsize(paint: Paint, text: String, maxWidth: Float): String {
    if (paint.measureText(text) <= maxWidth) return text
    var t = text
    while (t.isNotEmpty() && paint.measureText("$t…") > maxWidth) t = t.dropLast(1)
    return "$t…"
}

object Pdf {

    /** A sheet of QR labels for the given codes; each code is repeated [copies] times. [moveTag] goes in each QR code (see [labelQrText]). */
    fun labels(context: Context, codes: List<String>, sheet: LabelSheet, copies: Int = 1, moveTag: String = ""): File {
        val doc = PdfDocument()
        val all = codes.flatMap { c -> List(copies.coerceAtLeast(1)) { c } }
        all.chunked(sheet.perPage).forEachIndexed { pageIndex, chunk ->
            val page = doc.startPage(PdfDocument.PageInfo.Builder(sheet.pageW, sheet.pageH, pageIndex + 1).create())
            drawLabelPage(page.canvas, sheet, chunk, moveTag)
            doc.finishPage(page)
        }
        return write(doc, outFile(context, "housemove-labels.pdf"))
    }

    /** Draw one page of labels. Coordinates are PDF points. */
    fun drawLabelPage(c: Canvas, sheet: LabelSheet, codes: List<String>, moveTag: String = "") {
        val codePaint = textPaint(30f, bold = true)
        val small = textPaint(8f, color = Color.DKGRAY)
        val line = Paint().apply { color = Color.LTGRAY; style = Paint.Style.STROKE; strokeWidth = 0.5f }
        codes.take(sheet.perPage).forEachIndexed { i, code ->
            val col = i % sheet.cols
            val row = i / sheet.cols
            val x = sheet.left + col * (sheet.cellW + sheet.gapX)
            val y = sheet.top + row * (sheet.cellH + sheet.gapY)
            if (sheet.outline) c.drawRect(x, y, x + sheet.cellW, y + sheet.cellH, line)
            val pad = 8f
            val qrSize = sheet.cellH - 2 * pad
            val qr = qrBitmap(labelQrText(code, moveTag), 240)
            // No filtering: the squares of the code must stay sharp when scaled.
            c.drawBitmap(qr, null, RectF(x + pad, y + pad, x + pad + qrSize, y + pad + qrSize), Paint().apply { isFilterBitmap = false })
            val textX = x + pad + qrSize + 8f
            val textW = sheet.cellW - qrSize - 3 * pad - 4f
            codePaint.textSize = (sheet.cellH * 0.34f).coerceAtMost(44f)
            fitText(codePaint, code, textW)
            c.drawText(code, textX, y + sheet.cellH * 0.48f, codePaint)
            c.drawText("Room:", textX, y + sheet.cellH * 0.70f, small)
            c.drawLine(textX + 26f, y + sheet.cellH * 0.71f, x + sheet.cellW - pad, y + sheet.cellH * 0.71f, line)
            c.drawText("HouseMove", textX, y + sheet.cellH - pad - 1f, small)
        }
    }

    /** Sheets of hang tags for the given codes, for suitcases and bags; each code is repeated [copies] times. */
    fun hangTags(context: Context, codes: List<String>, sheet: TagSheet, copies: Int = 1, moveTag: String = ""): File {
        val doc = PdfDocument()
        val all = codes.flatMap { c -> List(copies.coerceAtLeast(1)) { c } }
        all.chunked(sheet.perPage).forEachIndexed { pageIndex, chunk ->
            val page = doc.startPage(PdfDocument.PageInfo.Builder(sheet.pageW, sheet.pageH, pageIndex + 1).create())
            drawTagPage(page.canvas, sheet, chunk, moveTag)
            doc.finishPage(page)
        }
        return write(doc, outFile(context, "housemove-hang-tags.pdf"))
    }

    /**
     * Draw one page of hang tags. Each tag has a cut line, a punch-hole mark at
     * the left, the QR code, the code in large type and a line to write on.
     */
    fun drawTagPage(c: Canvas, sheet: TagSheet, codes: List<String>, moveTag: String = "") {
        val codePaint = textPaint(40f, bold = true)
        val small = textPaint(8f, color = Color.DKGRAY)
        val cut = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.GRAY; style = Paint.Style.STROKE; strokeWidth = 0.7f
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(4f, 3f), 0f)
        }
        val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 1f }
        val line = Paint().apply { color = Color.GRAY; style = Paint.Style.STROKE; strokeWidth = 0.7f }
        codes.take(sheet.perPage).forEachIndexed { i, code ->
            val x = sheet.left + (i % sheet.cols) * sheet.cellW
            val y = sheet.top + (i / sheet.cols) * sheet.cellH
            val pad = 10f
            c.drawRoundRect(RectF(x + 3f, y + 3f, x + sheet.cellW - 3f, y + sheet.cellH - 3f), 14f, 14f, cut)
            // Where to punch the hole for the string: a circle with a cross through its middle.
            val (hx, hy) = sheet.hole(i)
            c.drawCircle(hx, hy, TAG_HOLE_R, mark)
            c.drawLine(hx - TAG_HOLE_R - 3f, hy, hx + TAG_HOLE_R + 3f, hy, mark)
            c.drawLine(hx, hy - TAG_HOLE_R - 3f, hx, hy + TAG_HOLE_R + 3f, mark)

            val bodyX = x + TAG_HOLE_X * 2f
            val writeY = y + sheet.cellH - 18f
            val qrSize = (writeY - 22f - (y + pad)).coerceAtMost(sheet.cellW * 0.48f)
            val qr = qrBitmap(labelQrText(code, moveTag), 240)
            // No filtering: the squares of the code must stay sharp when scaled.
            c.drawBitmap(qr, null, RectF(bodyX, y + pad, bodyX + qrSize, y + pad + qrSize), Paint().apply { isFilterBitmap = false })
            val textX = bodyX + qrSize + 8f
            val textW = x + sheet.cellW - pad - textX
            codePaint.textSize = 40f
            fitText(codePaint, code, textW, minSize = 12f)
            c.drawText(code, textX, y + pad + qrSize * 0.55f, codePaint)
            c.drawText("HouseMove", textX, y + pad + qrSize * 0.55f + 14f, small)
            c.drawText("Name / what's inside:", bodyX, writeY - 8f, small)
            c.drawLine(bodyX, writeY + 4f, x + sheet.cellW - pad, writeY + 4f, line)
        }
    }

    /** One landscape page per new-home room, in the room's colour, to tape on its door. */
    fun doorSigns(context: Context, data: AppData, letter: Boolean): File {
        val doc = PdfDocument()
        val w = if (letter) 792 else 842
        val h = if (letter) 612 else 595
        data.roomsOf(Home.DEST).forEachIndexed { i, room ->
            val page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, i + 1).create())
            drawDoorSign(page.canvas, w, h, room, data.move.destName)
            doc.finishPage(page)
        }
        if (data.roomsOf(Home.DEST).isEmpty()) {
            val page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, 1).create())
            page.canvas.drawText("No new-home rooms yet. Add them under More → Rooms.", 40f, 80f, textPaint(18f))
            doc.finishPage(page)
        }
        return write(doc, outFile(context, "housemove-door-signs.pdf"))
    }

    fun drawDoorSign(c: Canvas, w: Int, h: Int, room: Room, destName: String) {
        val rc = roomColor(room.color)
        val band = h * 0.68f
        c.drawRect(0f, 0f, w.toFloat(), band, Paint().apply { color = rc.argb.toInt() })
        val fg = if (rc.darkText) Color.BLACK else Color.WHITE
        val name = textPaint(130f, bold = true, color = fg).apply { textAlign = Paint.Align.CENTER }
        fitText(name, room.name, w - 80f, minSize = 30f)
        c.drawText(room.name, w / 2f, band * 0.52f + name.textSize * 0.3f, name)
        if (room.floor.isNotBlank()) {
            val floor = textPaint(34f, color = fg).apply { textAlign = Paint.Align.CENTER }
            fitText(floor, room.floor, w - 80f, minSize = 14f)
            c.drawText(room.floor, w / 2f, band - 36f, floor)
        }
        val code = textPaint(90f, bold = true).apply { textAlign = Paint.Align.CENTER }
        c.drawText(room.code.ifBlank { suggestRoomCode(room.name) }, w / 2f, band + (h - band) * 0.62f, code)
        val foot = textPaint(18f, color = Color.DKGRAY).apply { textAlign = Paint.Align.CENTER }
        c.drawText("${rc.name} · $destName", w / 2f, h - 22f, foot)
    }

    /** Flowing text pages with simple column support. */
    private class Pager(val doc: PdfDocument, val w: Int = 595, val h: Int = 842) {
        val margin = 36f
        var pageNo = 0
        var page: PdfDocument.Page? = null
        var y = 0f
        val canvas: Canvas get() = page!!.canvas

        fun newPage() {
            page?.let { doc.finishPage(it) }
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, pageNo).create())
            y = margin
        }

        fun need(height: Float) {
            if (page == null || y + height > h - margin) newPage()
        }

        fun text(s: String, size: Float = 10f, bold: Boolean = false, color: Int = Color.BLACK, gap: Float = 4f) {
            need(size + gap)
            y += size
            val p = textPaint(size, bold, color)
            canvas.drawText(ellipsize(p, s, w - 2 * margin), margin, y, p)
            y += gap
        }

        /** One table row. [cols] are (x offset from the left margin, text). */
        fun row(cols: List<Pair<Float, String>>, size: Float = 9f, bold: Boolean = false) {
            need(size + 5f)
            y += size
            val p = textPaint(size, bold)
            cols.forEachIndexed { i, (x, s) ->
                val next = cols.getOrNull(i + 1)?.first ?: (w - 2 * margin)
                canvas.drawText(ellipsize(p, s, next - x - 4f), margin + x, y, p)
            }
            y += 5f
        }

        fun rule() {
            need(6f)
            canvas.drawLine(margin, y, w - margin, y, Paint().apply { color = Color.LTGRAY; strokeWidth = 0.7f })
            y += 6f
        }

        fun space(v: Float) {
            y += v
        }

        fun finish() {
            page?.let { doc.finishPage(it) }
            page = null
        }
    }

    private fun dims(i: Item): String =
        if (i.widthCm == null && i.depthCm == null && i.heightCm == null) ""
        else listOf(i.widthCm, i.depthCm, i.heightCm).joinToString("×") { fmtNum(it).ifEmpty { "?" } }

    /** Everything being moved: items by old-home room, then boxes, with a volume estimate. */
    fun inventory(context: Context, held: AppData): File {
        // Values, makes, models and serial numbers are kept apart since 2.1 (see filledIn); this phone shows what it holds.
        val data = held.filledIn()
        val doc = PdfDocument()
        val p = Pager(doc)
        p.newPage()
        p.text(data.move.name.ifBlank { "House move" } + " · inventory", 18f, bold = true, gap = 8f)
        p.text("${data.move.originName} → ${data.move.destName} · move day: ${fmtDay(data.move.moveDay)}", 10f, color = Color.DKGRAY)
        val v = data.volume()
        p.text(
            "Estimated load: ${fmtNum(v.totalM3)} m³ (${fmtNum(v.totalM3 * 35.31)} ft³) including packing slack · " +
                "${v.trips} trip(s) in a ${fmtNum(data.move.vanCapacityM3)} m³ van",
            10f, color = Color.DKGRAY,
        )
        if (v.itemsWithoutSize > 0) p.text("${v.itemsWithoutSize} item(s) have no dimensions and are counted at ${DEFAULT_ITEM_M3} m³ each.", 9f, color = Color.DKGRAY)
        p.space(8f)

        p.text("Items", 14f, bold = true, gap = 6f)
        val itemCols = listOf(0f, 170f, 250f, 330f, 400f, 460f)
        p.row(itemCols.zip(listOf("Item", "From", "To", "Size cm", "Decision", "Notes")), bold = true)
        p.rule()
        val items = data.liveItems.sortedWith(compareBy({ data.room(it.originRoomId)?.name ?: "~" }, { it.name }))
        for (i in items) {
            val flags = listOfNotNull(
                "fragile".takeIf { i.fragile }, "2-person".takeIf { i.twoPerson },
                "disassemble".takeIf { i.needsDisassembly }, "DAMAGED".takeIf { i.damaged },
            ).joinToString(", ")
            p.row(
                itemCols.zip(
                    listOf(
                        (i.label?.let { "[$it] " } ?: "") + i.title() + (data.containerOf(i)?.let { " (in ${it.label})" } ?: ""),
                        data.room(i.originRoomId)?.name ?: "–",
                        data.room(i.destRoomId)?.name ?: "–",
                        dims(i), i.disposition.label, flags,
                    )
                )
            )
        }
        if (items.isEmpty()) p.text("No items catalogued.", 9f, color = Color.DKGRAY)
        p.space(12f)

        val devices = items.filter { it.make.isNotBlank() || it.model.isNotBlank() || it.serial.isNotBlank() }
        if (devices.isNotEmpty()) {
            p.text("Make, model and serial numbers", 14f, bold = true, gap = 6f)
            val devCols = listOf(0f, 150f, 250f, 350f, 460f)
            p.row(devCols.zip(listOf("Item", "Make", "Model", "Serial number", "Warranty")), bold = true)
            p.rule()
            for (i in devices) {
                p.row(devCols.zip(listOf(i.title(), i.make, i.model, i.serial, i.warrantyDay?.let { fmtDayShort(it) } ?: "")))
                if (i.accessories.isNotBlank()) {
                    p.row(listOf(12f to ("With it: " + i.accessories.replace('\n', ' ') + if (i.accessoryBox.isBlank()) "" else " (box ${i.accessoryBox})")), size = 8f)
                }
            }
            p.space(12f)
        }

        p.text("Boxes and other containers", 14f, bold = true, gap = 6f)
        val boxCols = listOf(0f, 46f, 112f, 176f, 248f, 320f, 378f)
        p.row(boxCols.zip(listOf("Code", "Kind", "Category", "From", "To", "Priority", "Contents")), bold = true)
        p.rule()
        val boxes = data.liveBoxes.sortedBy { it.label }
        for (b in boxes) {
            val flags = listOfNotNull("FRAGILE".takeIf { b.fragile }, "HEAVY".takeIf { b.heavy }, "DAMAGED".takeIf { b.damaged }).joinToString(" ")
            val inside = data.parentOf(b)?.let { "(in ${it.label}) " } ?: ""
            val holds = data.itemsIn(b.id).takeIf { it.isNotEmpty() }?.let { list -> " · holds: " + list.joinToString(", ") { it.title() } } ?: ""
            p.row(
                boxCols.zip(
                    listOf(
                        b.label, b.kind.label, b.category.ifBlank { "–" },
                        data.room(b.originRoomId)?.name ?: "–", data.room(b.destRoomId)?.name ?: "–",
                        b.priority.label,
                        (flags + " " + inside + listOf(b.name, b.contents.replace('\n', ' ')).filter { it.isNotBlank() }.joinToString(": ") + holds + scannedSummary(data, b.id)).trim(),
                    )
                )
            )
        }
        if (boxes.isEmpty()) p.text("No boxes packed yet.", 9f, color = Color.DKGRAY)
        p.finish()
        return write(doc, outFile(context, "housemove-inventory.pdf"))
    }

    /** Damaged boxes and items with their before and after photos. */
    fun damageReport(context: Context, store: Store): File {
        // With the values, makes, models and serial numbers this phone holds (see filledIn).
        val data = store.data.value.filledIn()
        val doc = PdfDocument()
        val p = Pager(doc)
        p.newPage()
        p.text(data.move.name.ifBlank { "House move" } + " · damage report", 18f, bold = true, gap = 8f)
        p.text("${data.move.originName} → ${data.move.destName} · move day: ${fmtDay(data.move.moveDay)}", 10f, color = Color.DKGRAY)
        p.space(10f)
        val damaged = data.movables().filter { it.damaged }
        if (damaged.isEmpty()) p.text("Nothing has been marked as damaged.", 11f)
        for (m in damaged) {
            p.need(60f)
            p.text(m.title, 13f, bold = true)
            val note = if (m.isBox) data.box(m.id)?.damageNote else data.item(m.id)?.damageNote
            if (!note.isNullOrBlank()) p.text("Damage: $note", 10f)
            val device = if (m.isBox) null else data.item(m.id)
            val ident = listOfNotNull(
                device?.makeModel()?.takeIf { it.isNotBlank() },
                device?.serial?.takeIf { it.isNotBlank() }?.let { "serial number $it" },
            ).joinToString(", ")
            if (ident.isNotBlank()) p.text(ident, 10f)
            val value = if (m.isBox) null else data.item(m.id)?.value
            if (value != null) p.text("Declared value: ${data.move.currency} ${fmtNum(value)}".trim(), 10f)
            for ((title, kind) in listOf("Before the move" to PhotoKind.CONDITION, "After the move" to PhotoKind.DAMAGE)) {
                val photos = data.photosOf(m.id, kind)
                if (photos.isEmpty()) continue
                p.text(title, 10f, bold = true, color = Color.DKGRAY)
                val cellW = 255f
                val cellH = 190f
                photos.chunked(2).forEach { pair ->
                    p.need(cellH + 8f)
                    pair.forEachIndexed { i, photo ->
                        val bmp = loadScaledBitmap(store.photoFile(photo), 900) ?: return@forEachIndexed
                        val scale = minOf(cellW / bmp.width, cellH / bmp.height)
                        val x = p.margin + i * (cellW + 12f)
                        p.canvas.drawBitmap(bmp, null, RectF(x, p.y, x + bmp.width * scale, p.y + bmp.height * scale), null)
                        bmp.recycle()
                    }
                    p.space(cellH + 8f)
                }
            }
            p.rule()
        }
        p.finish()
        return write(doc, outFile(context, "housemove-damage-report.pdf"))
    }

    /** " · 12 scanned in: Dune, Emma, …" for the inventory's contents column. */
    private fun scannedSummary(data: AppData, boxId: String): String {
        val entries = data.entriesOf(boxId)
        if (entries.isEmpty()) return ""
        val names = entries.take(4).joinToString(", ") { it.title.ifBlank { it.code } }
        return " · ${entries.size} scanned in: $names" + if (entries.size > 4) ", …" else ""
    }

    private fun write(doc: PdfDocument, file: File): File {
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
        return file
    }
}

object Csv {
    private fun cell(s: String?): String {
        val t = (s ?: "").replace("\r", " ").replace("\n", " ")
        return if (t.any { it == ',' || it == '"' }) "\"" + t.replace("\"", "\"\"") + "\"" else t
    }

    /** Items and boxes as one spreadsheet-friendly file. */
    fun inventory(context: Context, held: AppData): File {
        // With the values, makes, models and serial numbers this phone holds (see filledIn).
        val data = held.filledIn()
        val sb = StringBuilder()
        // The last three columns were added in 2.0, after the ones a spreadsheet may already rely on:
        // what kind of container a row is (or is in), its category, and the container something is packed in.
        sb.append("Type,Label,Name / contents,From room,To room,Decision,Status,Width cm,Depth cm,Height cm,Value,Fragile,Damaged,Notes,Make,Model,Serial number,Warranty until,Travels with it,Container kind,Category,Packed in\n")
        for (i in data.liveItems) {
            val container = data.containerOf(i)
            sb.append(
                listOf(
                    "Item", i.label, i.title(), data.room(i.originRoomId)?.name, data.room(i.destRoomId)?.name,
                    i.disposition.label, data.statusOf(i.toMovable()), fmtNum(i.widthCm), fmtNum(i.depthCm), fmtNum(i.heightCm), fmtNum(i.value),
                    if (i.fragile) "yes" else "", if (i.damaged) "yes: ${i.damageNote}" else "", i.notes,
                    i.make, i.model, i.serial, i.warrantyDay?.let { fmtDay(it) } ?: "", i.accessories,
                    container?.kind?.label, container?.category, container?.label,
                ).joinToString(",") { cell(it) }
            ).append('\n')
        }
        for (b in data.liveBoxes) {
            sb.append(
                listOf(
                    "Box", b.label, listOf(b.name, b.contents).filter { it.isNotBlank() }.joinToString(": "),
                    data.room(b.originRoomId)?.name, data.room(b.destRoomId)?.name,
                    "Move", data.statusOf(b.toMovable()), "", "", "", "",
                    if (b.fragile) "yes" else "", if (b.damaged) "yes: ${b.damageNote}" else "", b.notes,
                    "", "", "", "", "",
                    b.kind.label, b.category, data.parentOf(b)?.label,
                ).joinToString(",") { cell(it) }
            ).append('\n')
        }
        for (e in data.liveEntries) {
            val b = data.box(e.boxId) ?: continue
            sb.append(
                listOf(
                    "In box", b.label, e.title.ifBlank { "Barcode ${e.code}" }, data.room(b.originRoomId)?.name, data.room(b.destRoomId)?.name,
                    "Move", data.statusOf(b.toMovable()), "", "", "", "", "", "", listOf(e.author, e.code).filter { it.isNotBlank() }.joinToString(" / "),
                    "", "", "", "", "",
                    b.kind.label, b.category, b.label,
                ).joinToString(",") { cell(it) }
            ).append('\n')
        }
        val f = outFile(context, "housemove-inventory.csv")
        f.writeText(sb.toString())
        return f
    }
}

object Share {
    private fun uri(context: Context, file: File) =
        FileProvider.getUriForFile(context, context.packageName + ".files", file)

    /** Open in whatever app handles the type (a PDF viewer can print it). Falls back to sharing. */
    fun open(context: Context, file: File, mime: String) {
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri(context, file), mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            send(context, file, mime)
        }
    }

    fun send(context: Context, file: File, mime: String) {
        val intent = Intent(Intent.ACTION_SEND).setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri(context, file))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            context.startActivity(Intent.createChooser(intent, "Share ${file.name}"))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "No app found to share this file.", Toast.LENGTH_LONG).show()
        }
    }

    /** A file in the shareable output folder. */
    fun outFile(context: Context, name: String): File = app.housemove.pdf.outFile(context, name)
}
