package app.housemove.pdf

import app.housemove.data.*

/**
 * "Ask for decisions" (design 2.1, section 4): a picture list of the things nobody has decided on yet, for family who
 * never visit. Each has its number, its photo, its name and the room it is in, and the five answers to tick; they
 * reply in any messaging app ("3 sell"), and the Organiser taps the answers into Triage. Nothing else is printed:
 * no prices, values or serial numbers.
 *
 * What is on it and how it is laid out are here, shared by the screens and both phones' PDFs (drawn through Draw.kt:
 * Android's canvas in app pdf/, UIKit on the iPhone).
 */
object Decisions {
    /** Things on one page. */
    const val PER_PAGE = 6

    /** The answers, in the app's words. */
    val ANSWERS = listOf(Disposition.MOVE, Disposition.SELL, Disposition.DONATE, Disposition.BIN, Disposition.STORE).map { it.label }

    /** What there is to ask about: the things with no decision yet, room by room, as Triage lists them. */
    fun toAsk(data: AppData): List<Item> = data.liveItems.filter { it.disposition == Disposition.UNDECIDED }.sortedWith(
        compareBy({ data.room(it.originRoomId)?.sort ?: Int.MAX_VALUE }, { data.room(it.originRoomId)?.name ?: "~" }, { it.title().lowercase() }, { it.id }),
    )

    /** How many pages [n] things take. */
    fun pages(n: Int): Int = maxOf(1, (n + PER_PAGE - 1) / PER_PAGE)

    /** The photo printed for an item: its first ordinary photo (never a receipt or a serial label). */
    fun photoOf(data: AppData, item: Item): Photo? =
        data.photosOf(item.id).firstOrNull { it.kind == PhotoKind.GENERAL } ?: data.photosOf(item.id).firstOrNull { it.kind !in HIDDEN_FROM_HELPERS }

    // ---- the pages -------------------------------------------------------------------------------------------

    /** Its pages are A4: 595 by 842 points. */
    const val PAGE_W = 595
    const val PAGE_H = 842
    private const val MARGIN = 36f

    /** The file the list is made in: "maple-road-what-to-decide.pdf". */
    fun fileName(data: AppData): String {
        val slug = data.move.name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "move" }
        return "$slug-what-to-decide.pdf"
    }

    /** The whole list as a PDF, each thing with its first ordinary photo, read small. */
    fun document(doc: PdfDoc, store: Store) {
        val data = store.data.value
        val items = toAsk(data)
        for (page in 0 until pages(items.size)) {
            val c = doc.newPage(PAGE_W, PAGE_H)
            val shown = ArrayList<Picture>()
            draw(c, data, items, page) { item -> photoOf(data, item)?.let { doc.picture(store.photoFile(it), 600) }?.also { shown += it } }
            doc.endPage()
            shown.forEach { it.release() }
        }
    }

    /** Page [page] (from 0) of the list onto [c]: [items] are the whole list, and [picture] gives an item's photo, or null. */
    fun draw(c: PdfPage, data: AppData, items: List<Item>, page: Int, picture: (Item) -> Picture?) {
        val all = pages(items.size)
        var y = MARGIN + 18f
        val title = data.move.name.ifBlank { "Our move" } + ": what should happen to these?"
        c.text(c.ellipsize(title, Font(18f, true), PAGE_W - 2 * MARGIN), MARGIN, y, Font(18f, true))
        y += 20f
        val how = "Reply with each number and one word: " + ANSWERS.joinToString(", ") + ". Page ${page + 1} of $all."
        c.text(c.ellipsize(how, Font(10f, color = Ink.DKGRAY), PAGE_W - 2 * MARGIN), MARGIN, y, Font(10f, color = Ink.DKGRAY))
        y += 14f
        val line = Pen(Ink.LTGRAY, stroke = 0.8f)
        c.line(MARGIN, y, PAGE_W - MARGIN, y, line)
        y += 8f
        if (items.isEmpty()) {
            c.text("Every item has a decision.", MARGIN, y + 20f, Font(12f))
            return
        }
        val rowH = (PAGE_H - y - MARGIN) / PER_PAGE
        val photoW = 132f
        val photoH = rowH - 16f
        items.drop(page * PER_PAGE).take(PER_PAGE).forEachIndexed { i, item ->
            val top = y + i * rowH
            val n = page * PER_PAGE + i + 1
            // The number to reply with, large.
            c.text("$n", MARGIN, top + 26f, Font(22f, true))
            val px = MARGIN + 40f
            // The photo's box.
            val left = px
            val boxTop = top + 6f
            val right = px + photoW
            val bottom = top + 6f + photoH
            val pic = picture(item)
            if (pic != null) {
                val scale = minOf((right - left) / pic.width, (bottom - boxTop) / pic.height)
                val w = pic.width * scale
                val h = pic.height * scale
                c.picture(pic, left, boxTop, left + w, boxTop + h)
            } else {
                c.rect(left, boxTop, right, bottom, Pen(Ink.rgb(236, 238, 241)))
                c.text("No photo", left + 10f, (boxTop + bottom) * 0.5f + 4f, Font(10f, color = Ink.GRAY))
            }
            val tx = right + 16f
            val tw = PAGE_W - MARGIN - tx
            c.text(c.ellipsize(item.title(), Font(15f, true), tw), tx, top + 26f, Font(15f, true))
            val room = data.room(item.originRoomId)?.name
            c.text(c.ellipsize(room ?: "Room not known", Font(11f, color = Ink.DKGRAY), tw), tx, top + 44f, Font(11f, color = Ink.DKGRAY))
            // The five answers, each with a box to tick on paper.
            var ax = tx
            val ay = top + 72f
            val small = Font(11f)
            for (a in ANSWERS) {
                c.rect(ax, ay - 10f, ax + 11f, ay + 1f, Pen(Ink.DKGRAY, stroke = 0.8f))
                c.text(a, ax + 15f, ay, small)
                ax += 15f + c.width(a, small) + 14f
            }
            if (i < PER_PAGE - 1) c.line(MARGIN, top + rowH, PAGE_W - MARGIN, top + rowH, line)
        }
    }
}
