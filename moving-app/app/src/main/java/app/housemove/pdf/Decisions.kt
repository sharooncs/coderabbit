package app.housemove.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import app.housemove.data.*
import app.housemove.ui.loadScaledBitmap
import java.io.File

/*
 * "Ask for decisions" (design 2.1, section 4), drawn as a PDF: a picture list of the things nobody has decided on yet,
 * for family who never visit. What is on it (Decisions: the things, the answers, the pages) is shared with the screens
 * (sharedUi pdf/Decisions.kt).
 */

private const val W = 595
private const val H = 842
private const val MARGIN = 36f

private fun paint(size: Float, bold: Boolean = false, color: Int = Color.BLACK) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    textSize = size
    this.color = color
    typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
}

private fun fit(p: Paint, text: String, width: Float): String {
    if (p.measureText(text) <= width) return text
    var t = text
    while (t.isNotEmpty() && p.measureText("$t…") > width) t = t.dropLast(1)
    return "$t…"
}

/**
 * Draw page [page] (from 0) of the list onto [c], a page of 595 by 842 points: [items] are the whole list, and
 * [picture] gives an item's photo, or null.
 */
fun Decisions.drawPage(c: Canvas, data: AppData, items: List<Item>, page: Int, picture: (Item) -> Bitmap?) {
    val all = pages(items.size)
    var y = MARGIN + 18f
    val title = data.move.name.ifBlank { "Our move" } + ": what should happen to these?"
    c.drawText(fit(paint(18f, true), title, W - 2 * MARGIN), MARGIN, y, paint(18f, true))
    y += 20f
    val how = "Reply with each number and one word: " + ANSWERS.joinToString(", ") + ". Page ${page + 1} of $all."
    c.drawText(fit(paint(10f, color = Color.DKGRAY), how, W - 2 * MARGIN), MARGIN, y, paint(10f, color = Color.DKGRAY))
    y += 14f
    val line = Paint().apply { color = Color.LTGRAY; strokeWidth = 0.8f }
    c.drawLine(MARGIN, y, W - MARGIN, y, line)
    y += 8f
    if (items.isEmpty()) {
        c.drawText("Every item has a decision.", MARGIN, y + 20f, paint(12f))
        return
    }
    val rowH = (H - y - MARGIN) / PER_PAGE
    val photoW = 132f
    val photoH = rowH - 16f
    items.drop(page * PER_PAGE).take(PER_PAGE).forEachIndexed { i, item ->
        val top = y + i * rowH
        val n = page * PER_PAGE + i + 1
        // The number to reply with, large.
        c.drawText("$n", MARGIN, top + 26f, paint(22f, true))
        val px = MARGIN + 40f
        val box = RectF(px, top + 6f, px + photoW, top + 6f + photoH)
        val bmp = picture(item)
        if (bmp != null) {
            val scale = minOf(box.width() / bmp.width, box.height() / bmp.height)
            val w = bmp.width * scale
            val h = bmp.height * scale
            c.drawBitmap(bmp, null, RectF(box.left, box.top, box.left + w, box.top + h), null)
        } else {
            c.drawRect(box, Paint().apply { color = Color.rgb(236, 238, 241) })
            c.drawText("No photo", box.left + 10f, box.centerY() + 4f, paint(10f, color = Color.GRAY))
        }
        val tx = box.right + 16f
        val tw = W - MARGIN - tx
        c.drawText(fit(paint(15f, true), item.title(), tw), tx, top + 26f, paint(15f, true))
        val room = data.room(item.originRoomId)?.name
        c.drawText(fit(paint(11f, color = Color.DKGRAY), room ?: "Room not known", tw), tx, top + 44f, paint(11f, color = Color.DKGRAY))
        // The five answers, each with a box to tick on paper.
        var ax = tx
        val ay = top + 72f
        val small = paint(11f)
        for (a in ANSWERS) {
            c.drawRect(RectF(ax, ay - 10f, ax + 11f, ay + 1f), Paint().apply { style = Paint.Style.STROKE; color = Color.DKGRAY; strokeWidth = 0.8f })
            c.drawText(a, ax + 15f, ay, small)
            ax += 15f + small.measureText(a) + 14f
        }
        if (i < PER_PAGE - 1) c.drawLine(MARGIN, top + rowH, W - MARGIN, top + rowH, line)
    }
}

/** The list as a PDF in the shareable folder, for the share sheet. */
fun Decisions.pdf(context: Context, store: Store): File {
    val data = store.data.value
    val items = toAsk(data)
    val doc = PdfDocument()
    for (page in 0 until pages(items.size)) {
        val p = doc.startPage(PdfDocument.PageInfo.Builder(W, H, page + 1).create())
        val shown = ArrayList<Bitmap>()
        drawPage(p.canvas, data, items, page) { item -> photoOf(data, item)?.let { loadScaledBitmap(store.photoFile(it), 600) }?.also { shown += it } }
        doc.finishPage(p)
        shown.forEach { it.recycle() }
    }
    val slug = data.move.name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "move" }
    val file = Share.outFile(context, "$slug-what-to-decide.pdf")
    file.outputStream().use { doc.writeTo(it) }
    doc.close()
    return file
}
