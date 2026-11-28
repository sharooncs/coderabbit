package app.housemove.pdf

import app.housemove.data.File

/*
 * The small drawing interface HouseMove's printouts are drawn through (design/ios-plan.md, step 3b), so both phones lay
 * them out with the same code (Printouts.kt, HelperCard.kt, Decisions.kt): Android's PdfDocument canvas is behind it on
 * Android (app pdf/AndroidDraw.kt), UIKit's PDF renderer and CoreGraphics on the iPhone (sharedUi iosMain
 * pdf/IosDraw.kt). Coordinates are PDF points (1/72 inch) from the top left of the page, y going down, as on Android's
 * canvas, and every call says no more than Android's Paint did where the drawing was Android's alone, so Android's
 * pages come out as before.
 */

/** Colours as Android's Color names them, 0xAARRGGBB. */
object Ink {
    val BLACK: Int = 0xFF000000.toInt()
    val WHITE: Int = 0xFFFFFFFF.toInt()
    val DKGRAY: Int = 0xFF444444.toInt()
    val GRAY: Int = 0xFF888888.toInt()
    val LTGRAY: Int = 0xFFCCCCCC.toInt()

    /** An opaque colour from its red, green and blue (0 to 255), as Android's Color.rgb. */
    fun rgb(red: Int, green: Int, blue: Int): Int = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
}

/**
 * How a shape is drawn: filled with [color], or with [stroke] above 0, outlined with lines that wide, dashed when [dash]
 * gives the lengths of the dashes and gaps. [smooth] says the shape always asked Android for anti-aliasing
 * (Paint(ANTI_ALIAS_FLAG)); without it, it is drawn with Android's default Paint(). In a PDF that makes no difference,
 * but Android's pictures of the pages in its tests are drawn on bitmaps, where it does.
 */
class Pen(val color: Int, val stroke: Float = 0f, val smooth: Boolean = false, val dash: FloatArray? = null)

/** Text: its size in points, bold or not, its colour, and whether x is its middle rather than its start. */
class Font(val size: Float, val bold: Boolean = false, val color: Int = Ink.BLACK, val center: Boolean = false)

/** A photo read for a page ([PdfDoc.picture]): its size in pixels, upright, which gives its shape. */
interface Picture {
    val width: Int
    val height: Int

    /** Lets go of its pixels. It is not drawn again after this. */
    fun release()
}

/** One page being drawn. */
interface PdfPage {
    fun rect(left: Float, top: Float, right: Float, bottom: Float, pen: Pen)

    fun roundRect(left: Float, top: Float, right: Float, bottom: Float, radius: Float, pen: Pen)

    fun line(x1: Float, y1: Float, x2: Float, y2: Float, pen: Pen)

    fun circle(cx: Float, cy: Float, radius: Float, pen: Pen)

    /** [text] on one line, its baseline at [y]. */
    fun text(text: String, x: Float, y: Float, font: Font)

    /** How wide [text] is in [font]. */
    fun width(text: String, font: Font): Float

    /** [text] as a QR code with its quiet zone, black on white, [size] points square: the shared encoder's modules, as Android's ZXing draws them. */
    fun qr(text: String, left: Float, top: Float, size: Float)

    /** [picture] stretched to the box given. */
    fun picture(picture: Picture, left: Float, top: Float, right: Float, bottom: Float)
}

/** A PDF being made, a page at a time. */
interface PdfDoc {
    /** Starts a page of [w] by [h] points, ending the one before. */
    fun newPage(w: Int, h: Int): PdfPage

    /** Ends the page being drawn, if there is one. */
    fun endPage()

    /** The photo in [file], read no larger than about [maxPx] on its long side, or null when it cannot be read. */
    fun picture(file: File, maxPx: Int): Picture?
}

/** How a phone writes its printouts: PDFs drawn through [PdfDoc], and plain text (the inventory spreadsheet). */
interface PaperWriter {
    /** Writes the PDF [draw] draws to [file], and returns it. Throws when it cannot. */
    fun pdf(file: File, draw: (PdfDoc) -> Unit): File

    /** Writes [text] to [file] in UTF-8, and returns it. */
    fun text(file: File, text: String): File
}

/** The text size, from [start] down a point at a time to no less than [min], at which [text] fits in [maxWidth] (as the PDFs always shrank it). */
fun PdfPage.fitSize(text: String, start: Float, bold: Boolean, maxWidth: Float, min: Float = 6f): Float {
    var size = start
    while (width(text, Font(size, bold)) > maxWidth && size > min) size -= 1f
    return size
}

/** [text] cut short with "…" where it would be wider than [maxWidth] in [font]. */
fun PdfPage.ellipsize(text: String, font: Font, maxWidth: Float): String {
    if (width(text, font) <= maxWidth) return text
    var t = text
    while (t.isNotEmpty() && width("$t…", font) > maxWidth) t = t.dropLast(1)
    return "$t…"
}
