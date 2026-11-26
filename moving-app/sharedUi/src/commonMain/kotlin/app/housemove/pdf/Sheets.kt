package app.housemove.pdf

/*
 * The papers HouseMove prints, as the screens choose them: the sheets of labels and hang tags and their codes. The
 * drawing of the PDFs is Android's (app pdf/); these are shared so that the screens are.
 */

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

/**
 * Sheets of hang tags for suitcases and bags, which a sticker does not stay on.
 * Printed on paper or card, cut out along the outline, punched at the mark and
 * tied to the handle. They carry the same codes and QR as the sticker sheets.
 */
enum class TagSheet(
    val label: String,
    val pageW: Int, val pageH: Int,
    val cols: Int, val rows: Int,
    val left: Float, val top: Float,
    val cellW: Float, val cellH: Float,
) {
    A4_TAGS("A4 hang tags · 8 per page, cut out, punch and tie on", 595, 842, 2, 4, 24f, 25f, 273.5f, 198f),
    LETTER_TAGS("Letter hang tags · 8 per page, cut out, punch and tie on", 612, 792, 2, 4, 24f, 24f, 282f, 186f);

    val perPage get() = cols * rows

    /** Centre of the punch-hole mark of tag [index] on the page, in points. */
    fun hole(index: Int): Pair<Float, Float> =
        (left + (index % cols) * cellW + TAG_HOLE_X) to (top + (index / cols) * cellH + cellH / 2f)
}

/** Distance of the punch-hole mark from the left edge of a tag, and its radius, in points. */
const val TAG_HOLE_X = 24f
const val TAG_HOLE_R = 8f

fun labelCode(n: Int): String = "L" + n.toString().padStart(3, '0')
