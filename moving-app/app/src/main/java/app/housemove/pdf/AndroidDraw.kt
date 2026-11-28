package app.housemove.pdf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import app.housemove.ui.loadScaledBitmap
import java.io.File

/*
 * The printouts' drawing interface (sharedUi pdf/Draw.kt) on Android: a canvas, which is a PdfDocument's page when a PDF
 * is made, and a bitmap's in the tests. Each call makes the Paint the PDFs always made for it, so the pages are drawn as
 * they were before the layout was shared with the iPhone (design/ios-plan.md, step 3b).
 */

/** A page drawn on Android's [canvas]. */
class AndroidPage(val canvas: Canvas) : PdfPage {
    private fun paint(pen: Pen): Paint = (if (pen.smooth) Paint(Paint.ANTI_ALIAS_FLAG) else Paint()).apply {
        color = pen.color
        if (pen.stroke > 0f) {
            style = Paint.Style.STROKE
            strokeWidth = pen.stroke
        }
        pen.dash?.let { pathEffect = DashPathEffect(it, 0f) }
    }

    private fun paint(font: Font): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = font.size
        color = font.color
        typeface = if (font.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        if (font.center) textAlign = Paint.Align.CENTER
    }

    override fun rect(left: Float, top: Float, right: Float, bottom: Float, pen: Pen) = canvas.drawRect(left, top, right, bottom, paint(pen))

    override fun roundRect(left: Float, top: Float, right: Float, bottom: Float, radius: Float, pen: Pen) =
        canvas.drawRoundRect(RectF(left, top, right, bottom), radius, radius, paint(pen))

    override fun line(x1: Float, y1: Float, x2: Float, y2: Float, pen: Pen) = canvas.drawLine(x1, y1, x2, y2, paint(pen))

    override fun circle(cx: Float, cy: Float, radius: Float, pen: Pen) = canvas.drawCircle(cx, cy, radius, paint(pen))

    override fun text(text: String, x: Float, y: Float, font: Font) = canvas.drawText(text, x, y, paint(font))

    override fun width(text: String, font: Font): Float = paint(font).measureText(text)

    override fun qr(text: String, left: Float, top: Float, size: Float) {
        // No filtering: the squares of the code must stay sharp when scaled.
        canvas.drawBitmap(qrBitmap(text, 240), null, RectF(left, top, left + size, top + size), Paint().apply { isFilterBitmap = false })
    }

    override fun picture(picture: Picture, left: Float, top: Float, right: Float, bottom: Float) {
        canvas.drawBitmap((picture as BitmapPicture).bitmap, null, RectF(left, top, right, bottom), null)
    }
}

/** A photo as Android decodes it: upright, scaled down when it is large. */
class BitmapPicture(val bitmap: Bitmap) : Picture {
    override val width: Int get() = bitmap.width
    override val height: Int get() = bitmap.height

    override fun release() = bitmap.recycle()
}

/** A PdfDocument being drawn, a page at a time. */
private class AndroidDoc(private val doc: PdfDocument) : PdfDoc {
    private var page: PdfDocument.Page? = null
    private var pages = 0

    override fun newPage(w: Int, h: Int): PdfPage {
        endPage()
        pages++
        val p = doc.startPage(PdfDocument.PageInfo.Builder(w, h, pages).create())
        page = p
        return AndroidPage(p.canvas)
    }

    override fun endPage() {
        page?.let { doc.finishPage(it) }
        page = null
    }

    override fun picture(file: app.housemove.data.File, maxPx: Int): Picture? = loadScaledBitmap(file, maxPx)?.let { BitmapPicture(it) }
}

/** Android's PDFs, and the inventory spreadsheet: [PaperWriter] with PdfDocument. */
object AndroidPdf : PaperWriter {
    override fun pdf(file: File, draw: (PdfDoc) -> Unit): File {
        val doc = PdfDocument()
        try {
            AndroidDoc(doc).also(draw).endPage()
            file.outputStream().use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
        return file
    }

    override fun text(file: File, text: String): File {
        file.writeText(text)
        return file
    }
}
