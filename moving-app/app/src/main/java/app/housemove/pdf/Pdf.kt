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
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.File

/** Sheet layouts for QR labels. Sizes are in PDF points (1/72 inch). */
enum class LabelSheet(
    val label: String,
    val pageW: Int, val pageH: Int,
    val cols: Int, val rows: Int,
    val left: Float, val top: Float,
    val cellW: Float, val cellH: Float,
    val gapX: Float, val gapY: Float,
    val outline: Boolean,
) {
    A4_21("A4 sticker sheet · 21 per page (63.5 × 38.1 mm)", 595, 842, 3, 7, 20.4f, 42.8f, 180f, 108f, 7.1f, 0f, false),
    LETTER_10("Letter sticker sheet · 10 per page (4 × 2 in)", 612, 792, 2, 5, 11.25f, 36f, 288f, 144f, 13.5f, 0f, false),
    A4_PLAIN("A4 plain paper · 10 per page, cut out and tape on", 595, 842, 2, 5, 24f, 26f, 273.5f, 158f, 0f, 0f, true),
    LETTER_PLAIN("Letter plain paper · 10 per page, cut out and tape on", 612, 792, 2, 5, 24f, 26f, 282f, 148f, 0f, 0f, true);

    val perPage get() = cols * rows
}

fun labelCode(n: Int): String = "L" + n.toString().padStart(3, '0')

/** QR code as a bitmap with a quiet zone, black on white. */
fun qrBitmap(text: String, sizePx: Int): Bitmap {
    val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 1)
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
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

    /** A sheet of QR labels for the given codes; each code is repeated [copies] times. */
    fun labels(context: Context, codes: List<String>, sheet: LabelSheet, copies: Int = 1): File {
        val doc = PdfDocument()
        val all = codes.flatMap { c -> List(copies.coerceAtLeast(1)) { c } }
        val codePaint = textPaint(30f, bold = true)
        val small = textPaint(8f, color = Color.DKGRAY)
        val line = Paint().apply { color = Color.LTGRAY; style = Paint.Style.STROKE; strokeWidth = 0.5f }
        all.chunked(sheet.perPage).forEachIndexed { pageIndex, chunk ->
            val page = doc.startPage(PdfDocument.PageInfo.Builder(sheet.pageW, sheet.pageH, pageIndex + 1).create())
            val c = page.canvas
            chunk.forEachIndexed { i, code ->
                val col = i % sheet.cols
                val row = i / sheet.cols
                val x = sheet.left + col * (sheet.cellW + sheet.gapX)
                val y = sheet.top + row * (sheet.cellH + sheet.gapY)
                if (sheet.outline) c.drawRect(x, y, x + sheet.cellW, y + sheet.cellH, line)
                val pad = 8f
                val qrSize = sheet.cellH - 2 * pad
                val qr = qrBitmap(QR_PREFIX + code, 240)
                c.drawBitmap(qr, null, RectF(x + pad, y + pad, x + pad + qrSize, y + pad + qrSize), null)
                val textX = x + pad + qrSize + 8f
                val textW = sheet.cellW - qrSize - 3 * pad - 4f
                codePaint.textSize = (sheet.cellH * 0.34f).coerceAtMost(44f)
                fitText(codePaint, code, textW)
                c.drawText(code, textX, y + sheet.cellH * 0.48f, codePaint)
                c.drawText("Room:", textX, y + sheet.cellH * 0.70f, small)
                c.drawLine(textX + 26f, y + sheet.cellH * 0.71f, x + sheet.cellW - pad, y + sheet.cellH * 0.71f, line)
                c.drawText("HouseMove", textX, y + sheet.cellH - pad - 1f, small)
            }
            doc.finishPage(page)
        }
        return write(doc, outFile(context, "housemove-labels.pdf"))
    }

    /** One landscape page per new-home room, in the room's colour, to tape on its door. */
    fun doorSigns(context: Context, data: AppData, letter: Boolean): File {
        val doc = PdfDocument()
        val w = if (letter) 792 else 842
        val h = if (letter) 612 else 595
        data.roomsOf(Home.DEST).forEachIndexed { i, room ->
            val page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, i + 1).create())
            val c = page.canvas
            val rc = roomColor(room.color)
            val band = h * 0.68f
            c.drawRect(0f, 0f, w.toFloat(), band, Paint().apply { color = rc.argb.toInt() })
            val fg = if (rc.darkText) Color.BLACK else Color.WHITE
            val name = textPaint(130f, bold = true, color = fg).apply { textAlign = Paint.Align.CENTER }
            fitText(name, room.name, w - 80f, minSize = 30f)
            c.drawText(room.name, w / 2f, band * 0.52f + name.textSize * 0.3f, name)
            if (room.floor.isNotBlank()) {
                val floor = textPaint(34f, color = fg).apply { textAlign = Paint.Align.CENTER }
                c.drawText(room.floor, w / 2f, band - 36f, floor)
            }
            val code = textPaint(90f, bold = true).apply { textAlign = Paint.Align.CENTER }
            c.drawText(room.code.ifBlank { suggestRoomCode(room.name) }, w / 2f, band + (h - band) * 0.62f, code)
            val foot = textPaint(18f, color = Color.DKGRAY).apply { textAlign = Paint.Align.CENTER }
            c.drawText("${rc.name} · ${data.move.destName}", w / 2f, h - 22f, foot)
            doc.finishPage(page)
        }
        if (data.roomsOf(Home.DEST).isEmpty()) {
            val page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, 1).create())
            page.canvas.drawText("No new-home rooms yet. Add them under More → Rooms.", 40f, 80f, textPaint(18f))
            doc.finishPage(page)
        }
        return write(doc, outFile(context, "housemove-door-signs.pdf"))
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
    fun inventory(context: Context, data: AppData): File {
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
                        (i.label?.let { "[$it] " } ?: "") + i.title(),
                        data.room(i.originRoomId)?.name ?: "–",
                        data.room(i.destRoomId)?.name ?: "–",
                        dims(i), i.disposition.label, flags,
                    )
                )
            )
        }
        if (items.isEmpty()) p.text("No items catalogued.", 9f, color = Color.DKGRAY)
        p.space(12f)

        p.text("Boxes", 14f, bold = true, gap = 6f)
        val boxCols = listOf(0f, 50f, 140f, 230f, 300f)
        p.row(boxCols.zip(listOf("Box", "From", "To", "Priority", "Contents")), bold = true)
        p.rule()
        val boxes = data.liveBoxes.sortedBy { it.label }
        for (b in boxes) {
            val flags = listOfNotNull("FRAGILE".takeIf { b.fragile }, "HEAVY".takeIf { b.heavy }, "DAMAGED".takeIf { b.damaged }).joinToString(" ")
            p.row(
                boxCols.zip(
                    listOf(
                        b.label, data.room(b.originRoomId)?.name ?: "–", data.room(b.destRoomId)?.name ?: "–",
                        b.priority.label, (flags + " " + b.contents.replace('\n', ' ')).trim(),
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
        val data = store.data.value
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
    fun inventory(context: Context, data: AppData): File {
        val sb = StringBuilder()
        sb.append("Type,Label,Name / contents,From room,To room,Decision,Status,Width cm,Depth cm,Height cm,Value,Fragile,Damaged,Notes\n")
        for (i in data.liveItems) {
            sb.append(
                listOf(
                    "Item", i.label, i.title(), data.room(i.originRoomId)?.name, data.room(i.destRoomId)?.name,
                    i.disposition.label, i.stage.label, fmtNum(i.widthCm), fmtNum(i.depthCm), fmtNum(i.heightCm), fmtNum(i.value),
                    if (i.fragile) "yes" else "", if (i.damaged) "yes: ${i.damageNote}" else "", i.notes,
                ).joinToString(",") { cell(it) }
            ).append('\n')
        }
        for (b in data.liveBoxes) {
            sb.append(
                listOf(
                    "Box", b.label, b.contents, data.room(b.originRoomId)?.name, data.room(b.destRoomId)?.name,
                    "Move", b.stage.label, "", "", "", "",
                    if (b.fragile) "yes" else "", if (b.damaged) "yes: ${b.damageNote}" else "", b.notes,
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
