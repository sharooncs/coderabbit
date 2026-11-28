package app.housemove.pdf

import app.housemove.data.*

/**
 * The half-page card for Helpers (design 2.1, section 2): five steps that also work without the app,
 * the door-sign colours of the new home, and what to write on a box with no label ("write KIT on two
 * sides"). A page holds two cards, one above the other, to cut apart. A4 or Letter.
 *
 * Its words and its layout are here, shared by the screens and both phones' PDFs (drawn through Draw.kt: Android's
 * canvas in app pdf/, UIKit on the iPhone).
 */
object HelperCard {

    /** Page sizes in PDF points. */
    enum class Paper(val w: Int, val h: Int) { A4(595, 842), LETTER(612, 792) }

    /** One step on the card: a few words in bold, then one line on how. */
    class Step(val title: String, val how: String)

    /** The code to write on a box with no label: the new kitchen's, or the first new room's, else "KIT". */
    fun exampleCode(data: AppData): String {
        val rooms = data.roomsOf(Home.DEST)
        val r = rooms.firstOrNull { it.name.contains("kitchen", ignoreCase = true) } ?: rooms.firstOrNull()
        return r?.let { it.code.ifBlank { suggestRoomCode(it.name) } } ?: "KIT"
    }

    /** The five steps, in the words of the move ("van" or "truck", the new home's name). */
    fun steps(data: AppData): List<Step> {
        val van = data.vehicle()
        val code = exampleCode(data)
        val dest = data.move.destName.ifBlank { "the new home" }
        return listOf(
            Step("Pack one room at a time", "Heavy things in small boxes, light things in big ones. Fill each box to the top and tape it shut."),
            Step("Mark every box", "Stick a label on the top and one side. No label? Write the room's code on two sides: $code."),
            Step("Load the $van", "Heavy and big things first, against the front. First-night boxes last. With the app, scan each label as it goes on."),
            Step("Carry it to its colour", "Every room in $dest has a sign on its door in its own colour. Match the code on the box to the sign."),
            Step("Say when something is wrong", "Damaged? Photograph it before it is moved again. Not sure where it goes? Ask an Organiser."),
        )
    }

    /** The new home's rooms as their door signs: colour, code and name. */
    fun doorSigns(data: AppData): List<Triple<RoomColor, String, String>> =
        data.roomsOf(Home.DEST).map { r -> Triple(roomColor(r.color), r.code.ifBlank { suggestRoomCode(r.name) }, r.name) }

    // ---- the page --------------------------------------------------------------------------------------------

    /** The file the card is made in, for each paper size. */
    fun fileName(paper: Paper): String = "housemove-helper-card-${paper.name.lowercase()}.pdf"

    /** The PDF: one page with two cards, to print and cut in half. */
    fun document(doc: PdfDoc, data: AppData, paper: Paper) {
        page(doc.newPage(paper.w, paper.h), paper.w, paper.h, data)
        doc.endPage()
    }

    /** Two cards on a page of [w] by [h] points, with a dashed line to cut along between them. */
    fun page(c: PdfPage, w: Int, h: Int, data: AppData) {
        val half = h / 2f
        card(c, 0f, 0f, w.toFloat(), half, data)
        card(c, 0f, half, w.toFloat(), half, data)
        c.line(18f, half, w - 18f, half, Pen(Ink.GRAY, stroke = 0.8f, dash = floatArrayOf(6f, 4f)))
        c.text("✂ cut here", 22f, half - 4f, Font(7f, color = Ink.GRAY))
    }

    /** [s] broken into lines no wider than [width] in [font]. */
    private fun wrap(c: PdfPage, font: Font, s: String, width: Float): List<String> {
        val out = ArrayList<String>()
        var line = ""
        for (word in s.split(' ')) {
            val next = if (line.isEmpty()) word else "$line $word"
            if (c.width(next, font) <= width || line.isEmpty()) line = next else {
                out += line
                line = word
            }
        }
        if (line.isNotEmpty()) out += line
        return out
    }

    /** One card in the box [x], [y], [w], [h]: the steps on the left, the door signs on the right. */
    fun card(c: PdfPage, x: Float, y: Float, w: Float, h: Float, data: AppData) {
        val m = 26f
        val left = x + m
        val signsW = 150f
        val stepsW = w - 2 * m - signsW - 16f
        var top = y + m + 14f

        c.text("Helping with ${data.move.name.ifBlank { "the move" }}", left, top, Font(17f, bold = true))
        top += 15f
        val sub = listOfNotNull(
            if (data.move.kind == MoveKind.INVENTORY) data.move.originName else "${data.move.originName} → ${data.move.destName}",
            data.move.moveDay?.let { fmtDay(it) },
        ).joinToString(" · ")
        c.text(sub, left, top, Font(9.5f, color = Ink.DKGRAY))
        top += 18f

        val bold = Font(11f, bold = true)
        val body = Font(9.5f, color = Ink.rgb(40, 40, 40))
        val num = Font(12f, bold = true, color = Ink.WHITE)
        val dot = Pen(Ink.rgb(28, 120, 96), smooth = true)
        steps(data).forEachIndexed { i, s ->
            c.circle(left + 9f, top + 3f, 9f, dot)
            val n = "${i + 1}"
            c.text(n, left + 9f - c.width(n, num) / 2f, top + 7f, num)
            val tx = left + 26f
            c.text(s.title, tx, top + 7f, bold)
            var ly = top + 20f
            for (line in wrap(c, body, s.how, stepsW - 26f)) {
                c.text(line, tx, ly, body)
                ly += 12f
            }
            top = ly + 6f
        }

        // The door signs: each room of the new home in its colour, with its code.
        val sx = x + w - m - signsW
        var sy = y + m + 4f
        c.text("Door signs", sx, sy + 10f, Font(11f, bold = true))
        sy += 20f
        val signs = doorSigns(data)
        val rowH = 22f
        val fits = ((y + h - m - 30f - sy) / rowH).toInt().coerceAtLeast(1)
        signs.take(fits).forEach { (rc, code, name) ->
            val r = sx + 44f
            val b = sy + rowH - 4f
            c.roundRect(sx, sy, r, b, 4f, Pen(rc.argb.toInt(), smooth = true))
            val codeFont = Font(9f, bold = true, color = if (rc.darkText) Ink.BLACK else Ink.WHITE)
            c.text(code, (sx + r) * 0.5f - c.width(code, codeFont) / 2f, (sy + b) * 0.5f + 3f, codeFont)
            val nameFont = Font(9.5f)
            var shown = name
            while (shown.isNotEmpty() && c.width(shown, nameFont) > signsW - 52f) shown = shown.dropLast(1)
            c.text(if (shown == name) name else "$shown…", sx + 50f, sy + 12f, nameFont)
            sy += rowH
        }
        if (signs.size > fits) c.text("and ${signs.size - fits} more", sx, sy + 10f, Font(8.5f, color = Ink.DKGRAY))
        if (signs.isEmpty()) {
            for (line in wrap(c, Font(9f), "The Organiser prints a sign for each room's door.", signsW)) {
                c.text(line, sx, sy + 10f, Font(9f, color = Ink.DKGRAY))
                sy += 12f
            }
        }

        c.text("With the app: scan, tap, done. Without it: this card.", left, y + h - m + 4f, Font(8.5f, color = Ink.DKGRAY))
    }
}
