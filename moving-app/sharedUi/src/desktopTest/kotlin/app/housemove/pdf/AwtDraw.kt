package app.housemove.pdf

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import app.housemove.data.qrPixels
import java.awt.Font as AwtFont

/*
 * The printouts' drawing interface (commonMain pdf/Draw.kt) on a plain JVM, for the tests: each page drawn with Java2D
 * on a picture at [AwtDoc.scale] pixels per point, every line of text kept, and the photos counted. QR codes are drawn
 * as the iPhone draws them (the shared encoder's squares, filled as one path). The fonts are the JVM's, so the words
 * are not quite where Roboto or San Francisco put them; the layout is.
 */

/** A PDF drawn on pictures, a page each. */
class AwtDoc(val scale: Int = 2) : PdfDoc {
    val pages = ArrayList<AwtPage>()

    override fun newPage(w: Int, h: Int): PdfPage = AwtPage(w, h, scale).also { pages += it }

    override fun endPage() {}

    override fun picture(file: app.housemove.data.File, maxPx: Int): Picture? = try {
        ImageIO.read(file)?.let { AwtPicture(it) }
    } catch (_: Exception) {
        null
    }
}

class AwtPicture(val image: BufferedImage) : Picture {
    override val width: Int get() = image.width
    override val height: Int get() = image.height
    var released = false

    override fun release() {
        released = true
    }
}

/** One page, [w] by [h] points. */
class AwtPage(val w: Int, val h: Int, scale: Int) : PdfPage {
    val image = BufferedImage(w * scale, h * scale, BufferedImage.TYPE_INT_RGB)
    private val g: Graphics2D = image.createGraphics().apply {
        color = Color.WHITE
        fillRect(0, 0, image.width, image.height)
        scale(scale.toDouble(), scale.toDouble())
        setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
    }

    /** Every line of text drawn, in order. */
    val texts = ArrayList<String>()

    /** The photos drawn on it. */
    var pictures = 0

    private fun color(argb: Int) = Color(argb, true)

    private fun draw(shape: java.awt.Shape, pen: Pen) {
        g.color = color(pen.color)
        if (pen.stroke > 0f) {
            g.stroke = if (pen.dash != null) BasicStroke(pen.stroke, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, pen.dash, 0f) else BasicStroke(pen.stroke)
            g.draw(shape)
        } else {
            g.fill(shape)
        }
    }

    override fun rect(left: Float, top: Float, right: Float, bottom: Float, pen: Pen) = draw(Rectangle2D.Float(left, top, right - left, bottom - top), pen)

    override fun roundRect(left: Float, top: Float, right: Float, bottom: Float, radius: Float, pen: Pen) =
        draw(RoundRectangle2D.Float(left, top, right - left, bottom - top, 2 * radius, 2 * radius), pen)

    override fun line(x1: Float, y1: Float, x2: Float, y2: Float, pen: Pen) =
        draw(Line2D.Float(x1, y1, x2, y2), if (pen.stroke > 0f) pen else Pen(pen.color, stroke = 1f, dash = pen.dash))

    override fun circle(cx: Float, cy: Float, radius: Float, pen: Pen) = draw(Ellipse2D.Float(cx - radius, cy - radius, 2 * radius, 2 * radius), pen)

    private fun awt(font: Font): AwtFont = AwtFont(AwtFont.SANS_SERIF, if (font.bold) AwtFont.BOLD else AwtFont.PLAIN, 1).deriveFont(font.size)

    override fun text(text: String, x: Float, y: Float, font: Font) {
        texts += text
        g.font = awt(font)
        g.color = color(font.color)
        g.drawString(text, if (font.center) x - width(text, font) / 2f else x, y)
    }

    override fun width(text: String, font: Font): Float = awt(font).getStringBounds(text, g.fontRenderContext).width.toFloat()

    override fun qr(text: String, left: Float, top: Float, size: Float) {
        val code = qrPixels(text, 240)
        val k = size / code.size
        g.color = Color.WHITE
        g.fill(Rectangle2D.Float(left, top, size, size))
        val path = Path2D.Float()
        for (y in 0 until code.size) {
            var x = 0
            while (x < code.size) {
                if (!code[x, y]) {
                    x++
                    continue
                }
                val start = x
                while (x < code.size && code[x, y]) x++
                path.append(Rectangle2D.Float(left + start * k, top + y * k, (x - start) * k, k), false)
            }
        }
        g.color = Color.BLACK
        g.fill(path)
    }

    override fun picture(picture: Picture, left: Float, top: Float, right: Float, bottom: Float) {
        val p = picture as AwtPicture
        check(!p.released) { "a photo drawn after it was let go of" }
        pictures++
        val t = AffineTransform()
        t.translate(left.toDouble(), top.toDouble())
        t.scale((right - left).toDouble() / p.width, (bottom - top).toDouble() / p.height)
        g.drawImage(p.image, t, null)
    }
}

/** The printouts on a plain JVM: each page as a PNG beside [file] (file-1.png, …), and the words on them in [file]. */
object AwtPaperWriter : PaperWriter {
    override fun pdf(file: File, draw: (PdfDoc) -> Unit): File {
        val doc = AwtDoc()
        draw(doc)
        file.writeText(doc.pages.joinToString("\u000C") { it.texts.joinToString("\n") })
        doc.pages.forEachIndexed { i, p -> ImageIO.write(p.image, "png", File(file.path + "-${i + 1}.png")) }
        return file
    }

    override fun text(file: File, text: String): File = file.also { it.writeText(text) }
}
