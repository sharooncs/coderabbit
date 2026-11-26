package app.housemove.pdf

import app.housemove.data.*

/*
 * What HouseMove prints, laid out once for both phones through the drawing interface (Draw.kt): sheets of QR labels and
 * hang tags, door signs, the inventory and the damage report, and the inventory as a spreadsheet. The Helper card and Ask
 * for decisions are HelperCard's and Decisions'. Android draws these on its PdfDocument (app pdf/), the iPhone with UIKit
 * (sharedUi iosMain pdf/). The layout is the one Android has always printed, call for call.
 */
object Printouts {
    /** The files the printouts are made in, in the folder files are shared from. */
    const val LABELS = "housemove-labels.pdf"
    const val HANG_TAGS = "housemove-hang-tags.pdf"
    const val DOOR_SIGNS = "housemove-door-signs.pdf"
    const val INVENTORY = "housemove-inventory.pdf"
    const val INVENTORY_CSV = "housemove-inventory.csv"
    const val DAMAGE_REPORT = "housemove-damage-report.pdf"

    // ---- labels and hang tags ----------------------------------------------------------------------------------

    /** Sheets of QR labels for [codes], each repeated [copies] times. [moveTag] goes in each QR code (see [labelQrText]). */
    fun labels(doc: PdfDoc, codes: List<String>, sheet: LabelSheet, copies: Int = 1, moveTag: String = "") {
        val all = codes.flatMap { c -> List(copies.coerceAtLeast(1)) { c } }
        all.chunked(sheet.perPage).forEach { chunk -> labelPage(doc.newPage(sheet.pageW, sheet.pageH), sheet, chunk, moveTag) }
        doc.endPage()
    }

    /** One page of labels. */
    fun labelPage(c: PdfPage, sheet: LabelSheet, codes: List<String>, moveTag: String = "") {
        val small = Font(8f, color = Ink.DKGRAY)
        val line = Pen(Ink.LTGRAY, stroke = 0.5f)
        codes.take(sheet.perPage).forEachIndexed { i, code ->
            val col = i % sheet.cols
            val row = i / sheet.cols
            val x = sheet.left + col * (sheet.cellW + sheet.gapX)
            val y = sheet.top + row * (sheet.cellH + sheet.gapY)
            if (sheet.outline) c.rect(x, y, x + sheet.cellW, y + sheet.cellH, line)
            val pad = 8f
            val qrSize = sheet.cellH - 2 * pad
            c.qr(labelQrText(code, moveTag), x + pad, y + pad, qrSize)
            val textX = x + pad + qrSize + 8f
            val textW = sheet.cellW - qrSize - 3 * pad - 4f
            val size = c.fitSize(code, (sheet.cellH * 0.34f).coerceAtMost(44f), bold = true, maxWidth = textW)
            c.text(code, textX, y + sheet.cellH * 0.48f, Font(size, bold = true))
            c.text("Room:", textX, y + sheet.cellH * 0.70f, small)
            c.line(textX + 26f, y + sheet.cellH * 0.71f, x + sheet.cellW - pad, y + sheet.cellH * 0.71f, line)
            c.text("HouseMove", textX, y + sheet.cellH - pad - 1f, small)
        }
    }

    /** Sheets of hang tags for [codes], for suitcases and bags; each code is repeated [copies] times. */
    fun hangTags(doc: PdfDoc, codes: List<String>, sheet: TagSheet, copies: Int = 1, moveTag: String = "") {
        val all = codes.flatMap { c -> List(copies.coerceAtLeast(1)) { c } }
        all.chunked(sheet.perPage).forEach { chunk -> tagPage(doc.newPage(sheet.pageW, sheet.pageH), sheet, chunk, moveTag) }
        doc.endPage()
    }

    /**
     * One page of hang tags. Each tag has a cut line, a punch-hole mark at the left, the QR code, the code in large type
     * and a line to write on.
     */
    fun tagPage(c: PdfPage, sheet: TagSheet, codes: List<String>, moveTag: String = "") {
        val small = Font(8f, color = Ink.DKGRAY)
        val cut = Pen(Ink.GRAY, stroke = 0.7f, smooth = true, dash = floatArrayOf(4f, 3f))
        val mark = Pen(Ink.BLACK, stroke = 1f, smooth = true)
        val line = Pen(Ink.GRAY, stroke = 0.7f)
        codes.take(sheet.perPage).forEachIndexed { i, code ->
            val x = sheet.left + (i % sheet.cols) * sheet.cellW
            val y = sheet.top + (i / sheet.cols) * sheet.cellH
            val pad = 10f
            c.roundRect(x + 3f, y + 3f, x + sheet.cellW - 3f, y + sheet.cellH - 3f, 14f, cut)
            // Where to punch the hole for the string: a circle with a cross through its middle.
            val (hx, hy) = sheet.hole(i)
            c.circle(hx, hy, TAG_HOLE_R, mark)
            c.line(hx - TAG_HOLE_R - 3f, hy, hx + TAG_HOLE_R + 3f, hy, mark)
            c.line(hx, hy - TAG_HOLE_R - 3f, hx, hy + TAG_HOLE_R + 3f, mark)

            val bodyX = x + TAG_HOLE_X * 2f
            val writeY = y + sheet.cellH - 18f
            val qrSize = (writeY - 22f - (y + pad)).coerceAtMost(sheet.cellW * 0.48f)
            c.qr(labelQrText(code, moveTag), bodyX, y + pad, qrSize)
            val textX = bodyX + qrSize + 8f
            val textW = x + sheet.cellW - pad - textX
            val size = c.fitSize(code, 40f, bold = true, maxWidth = textW, min = 12f)
            c.text(code, textX, y + pad + qrSize * 0.55f, Font(size, bold = true))
            c.text("HouseMove", textX, y + pad + qrSize * 0.55f + 14f, small)
            c.text("Name / what's inside:", bodyX, writeY - 8f, small)
            c.line(bodyX, writeY + 4f, x + sheet.cellW - pad, writeY + 4f, line)
        }
    }

    // ---- door signs ------------------------------------------------------------------------------------------

    /** One landscape page per new-home room, in the room's colour, to tape on its door; A4 or, with [letter], Letter. */
    fun doorSigns(doc: PdfDoc, data: AppData, letter: Boolean) {
        val w = if (letter) 792 else 842
        val h = if (letter) 612 else 595
        data.roomsOf(Home.DEST).forEach { room -> doorSign(doc.newPage(w, h), w, h, room, data.move.destName) }
        if (data.roomsOf(Home.DEST).isEmpty()) {
            doc.newPage(w, h).text("No new-home rooms yet. Add them under More → Rooms.", 40f, 80f, Font(18f))
        }
        doc.endPage()
    }

    /** One door sign on a page of [w] by [h] points: the room's name on its colour, its code below, big. */
    fun doorSign(c: PdfPage, w: Int, h: Int, room: Room, destName: String) {
        val rc = roomColor(room.color)
        val band = h * 0.68f
        c.rect(0f, 0f, w.toFloat(), band, Pen(rc.argb.toInt()))
        val fg = if (rc.darkText) Ink.BLACK else Ink.WHITE
        val nameSize = c.fitSize(room.name, 130f, bold = true, maxWidth = w - 80f, min = 30f)
        c.text(room.name, w / 2f, band * 0.52f + nameSize * 0.3f, Font(nameSize, bold = true, color = fg, center = true))
        if (room.floor.isNotBlank()) {
            val floorSize = c.fitSize(room.floor, 34f, bold = false, maxWidth = w - 80f, min = 14f)
            c.text(room.floor, w / 2f, band - 36f, Font(floorSize, color = fg, center = true))
        }
        c.text(room.code.ifBlank { suggestRoomCode(room.name) }, w / 2f, band + (h - band) * 0.62f, Font(90f, bold = true, center = true))
        c.text("${rc.name} · $destName", w / 2f, h - 22f, Font(18f, color = Ink.DKGRAY, center = true))
    }

    // ---- reports ---------------------------------------------------------------------------------------------

    /** Flowing text over A4 pages, with simple columns. */
    private class Pager(val doc: PdfDoc, val w: Int = 595, val h: Int = 842) {
        val margin = 36f
        var page: PdfPage? = null
        var y = 0f
        val canvas: PdfPage get() = page!!

        fun newPage() {
            page = doc.newPage(w, h)
            y = margin
        }

        fun need(height: Float) {
            if (page == null || y + height > h - margin) newPage()
        }

        fun text(s: String, size: Float = 10f, bold: Boolean = false, color: Int = Ink.BLACK, gap: Float = 4f) {
            need(size + gap)
            y += size
            val f = Font(size, bold, color)
            canvas.text(canvas.ellipsize(s, f, w - 2 * margin), margin, y, f)
            y += gap
        }

        /** One table row. [cols] are (x offset from the left margin, text). */
        fun row(cols: List<Pair<Float, String>>, size: Float = 9f, bold: Boolean = false) {
            need(size + 5f)
            y += size
            val f = Font(size, bold)
            cols.forEachIndexed { i, (x, s) ->
                val next = cols.getOrNull(i + 1)?.first ?: (w - 2 * margin)
                canvas.text(canvas.ellipsize(s, f, next - x - 4f), margin + x, y, f)
            }
            y += 5f
        }

        fun rule() {
            need(6f)
            canvas.line(margin, y, w - margin, y, Pen(Ink.LTGRAY, stroke = 0.7f))
            y += 6f
        }

        fun space(v: Float) {
            y += v
        }

        fun finish() {
            doc.endPage()
            page = null
        }
    }

    private fun dims(i: Item): String =
        if (i.widthCm == null && i.depthCm == null && i.heightCm == null) ""
        else listOf(i.widthCm, i.depthCm, i.heightCm).joinToString("×") { fmtNum(it).ifEmpty { "?" } }

    /** Everything being moved: items by old-home room, then boxes, with a volume estimate. */
    fun inventory(doc: PdfDoc, held: AppData) {
        // Values, makes, models and serial numbers are kept apart since 2.1 (see filledIn); this phone shows what it holds.
        val data = held.filledIn()
        val p = Pager(doc)
        p.newPage()
        p.text(data.move.name.ifBlank { "House move" } + " · inventory", 18f, bold = true, gap = 8f)
        p.text("${data.move.originName} → ${data.move.destName} · move day: ${fmtDay(data.move.moveDay)}", 10f, color = Ink.DKGRAY)
        val v = data.volume()
        p.text(
            "Estimated load: ${fmtNum(v.totalM3)} m³ (${fmtNum(v.totalM3 * 35.31)} ft³) including packing slack · " +
                "${v.trips} trip(s) in a ${fmtNum(data.move.vanCapacityM3)} m³ van",
            10f, color = Ink.DKGRAY,
        )
        if (v.itemsWithoutSize > 0) p.text("${v.itemsWithoutSize} item(s) have no dimensions and are counted at ${DEFAULT_ITEM_M3} m³ each.", 9f, color = Ink.DKGRAY)
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
        if (items.isEmpty()) p.text("No items catalogued.", 9f, color = Ink.DKGRAY)
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
        if (boxes.isEmpty()) p.text("No boxes packed yet.", 9f, color = Ink.DKGRAY)
        p.finish()
    }

    /** Damaged boxes and items with their before and after photos. */
    fun damageReport(doc: PdfDoc, store: Store) {
        // With the values, makes, models and serial numbers this phone holds (see filledIn).
        val data = store.data.value.filledIn()
        val p = Pager(doc)
        p.newPage()
        p.text(data.move.name.ifBlank { "House move" } + " · damage report", 18f, bold = true, gap = 8f)
        p.text("${data.move.originName} → ${data.move.destName} · move day: ${fmtDay(data.move.moveDay)}", 10f, color = Ink.DKGRAY)
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
                p.text(title, 10f, bold = true, color = Ink.DKGRAY)
                val cellW = 255f
                val cellH = 190f
                photos.chunked(2).forEach { pair ->
                    p.need(cellH + 8f)
                    pair.forEachIndexed { i, photo ->
                        val pic = doc.picture(store.photoFile(photo), 900) ?: return@forEachIndexed
                        val scale = minOf(cellW / pic.width, cellH / pic.height)
                        val x = p.margin + i * (cellW + 12f)
                        p.canvas.picture(pic, x, p.y, x + pic.width * scale, p.y + pic.height * scale)
                        pic.release()
                    }
                    p.space(cellH + 8f)
                }
            }
            p.rule()
        }
        p.finish()
    }

    /** " · 12 scanned in: Dune, Emma, …" for the inventory's contents column. */
    private fun scannedSummary(data: AppData, boxId: String): String {
        val entries = data.entriesOf(boxId)
        if (entries.isEmpty()) return ""
        val names = entries.take(4).joinToString(", ") { it.title.ifBlank { it.code } }
        return " · ${entries.size} scanned in: $names" + if (entries.size > 4) ", …" else ""
    }

    // ---- the inventory as a spreadsheet ---------------------------------------------------------------------

    private fun cell(s: String?): String {
        val t = (s ?: "").replace("\r", " ").replace("\n", " ")
        return if (t.any { it == ',' || it == '"' }) "\"" + t.replace("\"", "\"\"") + "\"" else t
    }

    /** Items and boxes as one spreadsheet-friendly file (CSV). */
    fun inventoryCsv(held: AppData): String {
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
        return sb.toString()
    }
}
