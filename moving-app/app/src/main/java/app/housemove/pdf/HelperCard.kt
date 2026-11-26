package app.housemove.pdf

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import app.housemove.data.*
import java.io.File

/*
 * The half-page card for Helpers (design 2.1, section 2), drawn as a PDF. What it says (its steps, the door signs,
 * the code to write on a box with no label) is HelperCard, shared with the screens (sharedUi pdf/HelperCard.kt).
 */

/** A PDF of one page with two cards, to print and cut in half. */
fun HelperCard.make(context: Context, data: AppData, paper: HelperCard.Paper): File {
    val doc = PdfDocument()
    val page = doc.startPage(PdfDocument.PageInfo.Builder(paper.w, paper.h, 1).create())
    drawPage(page.canvas, paper.w, paper.h, data)
    doc.finishPage(page)
    val file = File(File(context.cacheDir, "out").apply { mkdirs() }, "housemove-helper-card-${paper.name.lowercase()}.pdf")
    file.outputStream().use { doc.writeTo(it) }
    doc.close()
    return file
}

/** Two cards on a page of [w] by [h] points, with a dashed line to cut along between them. */
fun HelperCard.drawPage(c: Canvas, w: Int, h: Int, data: AppData) {
    val half = h / 2f
    drawCard(c, 0f, 0f, w.toFloat(), half, data)
    drawCard(c, 0f, half, w.toFloat(), half, data)
    val cut = Paint().apply {
        color = Color.GRAY
        strokeWidth = 0.8f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(6f, 4f), 0f)
    }
    c.drawLine(18f, half, w - 18f, half, cut)
    c.drawText("✂ cut here", 22f, half - 4f, text(7f, color = Color.GRAY))
}

private fun text(size: Float, bold: Boolean = false, color: Int = Color.BLACK) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    textSize = size
    this.color = color
    typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
}

/** [s] broken into lines no wider than [width]. */
private fun wrap(paint: Paint, s: String, width: Float): List<String> {
    val out = ArrayList<String>()
    var line = ""
    for (word in s.split(' ')) {
        val next = if (line.isEmpty()) word else "$line $word"
        if (paint.measureText(next) <= width || line.isEmpty()) line = next else {
            out += line
            line = word
        }
    }
    if (line.isNotEmpty()) out += line
    return out
}

/** One card in the box [x], [y], [w], [h]: the steps on the left, the door signs on the right. */
fun HelperCard.drawCard(c: Canvas, x: Float, y: Float, w: Float, h: Float, data: AppData) {
    val m = 26f
    val left = x + m
    val signsW = 150f
    val stepsW = w - 2 * m - signsW - 16f
    var top = y + m + 14f

    val title = text(17f, bold = true)
    c.drawText("Helping with ${data.move.name.ifBlank { "the move" }}", left, top, title)
    top += 15f
    val sub = listOfNotNull(
        if (data.move.kind == MoveKind.INVENTORY) data.move.originName else "${data.move.originName} → ${data.move.destName}",
        data.move.moveDay?.let { fmtDay(it) },
    ).joinToString(" · ")
    c.drawText(sub, left, top, text(9.5f, color = Color.DKGRAY))
    top += 18f

    val bold = text(11f, bold = true)
    val body = text(9.5f, color = Color.rgb(40, 40, 40))
    val num = text(12f, bold = true, color = Color.WHITE)
    val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(28, 120, 96) }
    steps(data).forEachIndexed { i, s ->
        c.drawCircle(left + 9f, top + 3f, 9f, dot)
        val n = "${i + 1}"
        c.drawText(n, left + 9f - num.measureText(n) / 2f, top + 7f, num)
        val tx = left + 26f
        c.drawText(s.title, tx, top + 7f, bold)
        var ly = top + 20f
        for (line in wrap(body, s.how, stepsW - 26f)) {
            c.drawText(line, tx, ly, body)
            ly += 12f
        }
        top = ly + 6f
    }

    // The door signs: each room of the new home in its colour, with its code.
    val sx = x + w - m - signsW
    var sy = y + m + 4f
    c.drawText("Door signs", sx, sy + 10f, text(11f, bold = true))
    sy += 20f
    val signs = doorSigns(data)
    val rowH = 22f
    val fits = ((y + h - m - 30f - sy) / rowH).toInt().coerceAtLeast(1)
    signs.take(fits).forEach { (rc, code, name) ->
        val r = RectF(sx, sy, sx + 44f, sy + rowH - 4f)
        c.drawRoundRect(r, 4f, 4f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = rc.argb.toInt() })
        val codePaint = text(9f, bold = true, color = if (rc.darkText) Color.BLACK else Color.WHITE)
        c.drawText(code, r.centerX() - codePaint.measureText(code) / 2f, r.centerY() + 3f, codePaint)
        val namePaint = text(9.5f)
        var shown = name
        while (shown.isNotEmpty() && namePaint.measureText(shown) > signsW - 52f) shown = shown.dropLast(1)
        c.drawText(if (shown == name) name else "$shown…", sx + 50f, sy + 12f, namePaint)
        sy += rowH
    }
    if (signs.size > fits) c.drawText("and ${signs.size - fits} more", sx, sy + 10f, text(8.5f, color = Color.DKGRAY))
    if (signs.isEmpty()) {
        for (line in wrap(text(9f), "The Organiser prints a sign for each room's door.", signsW)) {
            c.drawText(line, sx, sy + 10f, text(9f, color = Color.DKGRAY))
            sy += 12f
        }
    }

    val foot = text(8.5f, color = Color.DKGRAY)
    c.drawText("With the app: scan, tap, done. Without it: this card.", left, y + h - m + 4f, foot)
}
