@file:OptIn(ExperimentalForeignApi::class)

package app.housemove.pdf

import app.housemove.data.File
import app.housemove.data.IOException
import app.housemove.data.qrPixels
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.useContents
import kotlinx.cinterop.value
import platform.CoreGraphics.CGContextAddEllipseInRect
import platform.CoreGraphics.CGContextAddLineToPoint
import platform.CoreGraphics.CGContextAddPath
import platform.CoreGraphics.CGContextAddRect
import platform.CoreGraphics.CGContextBeginPath
import platform.CoreGraphics.CGContextFillPath
import platform.CoreGraphics.CGContextFillRect
import platform.CoreGraphics.CGContextMoveToPoint
import platform.CoreGraphics.CGContextRef
import platform.CoreGraphics.CGContextRestoreGState
import platform.CoreGraphics.CGContextSaveGState
import platform.CoreGraphics.CGContextSetLineDash
import platform.CoreGraphics.CGContextSetLineWidth
import platform.CoreGraphics.CGContextSetRGBFillColor
import platform.CoreGraphics.CGContextSetRGBStrokeColor
import platform.CoreGraphics.CGContextStrokePath
import platform.CoreGraphics.CGFloatVar
import platform.CoreGraphics.CGPathCreateWithRoundedRect
import platform.CoreGraphics.CGPathRelease
import platform.CoreGraphics.CGPointMake
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSError
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.writeToFile
import platform.UIKit.NSFontAttributeName
import platform.UIKit.NSForegroundColorAttributeName
import platform.UIKit.UIColor
import platform.UIKit.UIFont
import platform.UIKit.UIGraphicsGetCurrentContext
import platform.UIKit.UIGraphicsImageRenderer
import platform.UIKit.UIGraphicsImageRendererFormat
import platform.UIKit.UIGraphicsPDFRenderer
import platform.UIKit.UIGraphicsPDFRendererContext
import platform.UIKit.UIGraphicsPDFRendererFormat
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.drawAtPoint
import platform.UIKit.sizeWithAttributes
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/*
 * The printouts' drawing interface (commonMain pdf/Draw.kt) on the iPhone: UIKit's PDF renderer (UIGraphicsPDFRenderer)
 * makes the file, CoreGraphics draws the shapes, UIKit the text (the system font, embedded in the PDF) and the photos
 * (upright, read small and kept as JPEG), and QR codes are the shared encoder's modules as vector squares, so they stay
 * sharp at any printer's resolution. Coordinates are UIKit's: points from the top left, y going down, as the common
 * layout's. UIKit's drawing may be used off the main thread, where the screens make their files.
 */

/** The iPhone's PDFs and the inventory spreadsheet. */
object IosPaperWriter : PaperWriter {
    override fun pdf(file: File, draw: (PdfDoc) -> Unit): File {
        val url = NSURL.fileURLWithPath(file.path)
        // A page size for pages begun without one; every page here gives its own.
        val renderer = UIGraphicsPDFRenderer(bounds = CGRectMake(0.0, 0.0, 595.0, 842.0), format = UIGraphicsPDFRendererFormat())
        // An exception must not leave Kotlin through UIKit's block: it is kept, and thrown once UIKit is done.
        var failed: Throwable? = null
        val written = memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            renderer.writePDFToURL(url, withActions = { context ->
                if (context == null) {
                    failed = IOException("no PDF context")
                } else {
                    try {
                        draw(IosDoc(context))
                    } catch (t: Throwable) {
                        failed = t
                    }
                }
            }, error = error.ptr)
        }
        failed?.let { throw it }
        if (!written) throw IOException("could not write ${file.path}")
        return file
    }

    override fun text(file: File, text: String): File {
        @Suppress("CAST_NEVER_SUCCEEDS")
        val ok = (text as NSString).writeToFile(file.path, atomically = true, encoding = NSUTF8StringEncoding, error = null)
        if (!ok) throw IOException("could not write ${file.path}")
        return file
    }
}

/** A PDF being drawn by UIKit's renderer: each page begun in [context] with its own size. */
private class IosDoc(private val context: UIGraphicsPDFRendererContext) : PdfDoc {
    override fun newPage(w: Int, h: Int): PdfPage {
        // UIKit ends the page before, if any.
        context.beginPageWithBounds(CGRectMake(0.0, 0.0, w.toDouble(), h.toDouble()), pageInfo = emptyMap<Any?, Any?>())
        return IosPage(UIGraphicsGetCurrentContext() ?: throw IOException("no page"))
    }

    // UIKit ends a page when the next one begins, or when the document ends.
    override fun endPage() {}

    override fun picture(file: File, maxPx: Int): Picture? = IosPicture.read(file, maxPx)
}

/** A photo as UIKit reads it: upright (its orientation applied), no larger than asked on its long side. */
class IosPicture(val image: UIImage) : Picture {
    override val width: Int = image.size.useContents { width }.toInt()
    override val height: Int = image.size.useContents { height }.toInt()

    // UIKit lets go of it once nothing holds it.
    override fun release() {}

    companion object {
        /**
         * The photo in [file], or null when it cannot be read. A larger one is drawn again at [maxPx] on its long side and
         * kept as JPEG, so the PDF holds a small JPEG (as Android's does) rather than every pixel of the original.
         */
        fun read(file: File, maxPx: Int): IosPicture? {
            val image = UIImage.imageWithContentsOfFile(file.path) ?: return null
            val (w, h) = image.size.useContents { width to height }
            if (w < 1.0 || h < 1.0) return null
            val longest = max(w, h)
            if (longest <= maxPx) return IosPicture(image)
            val k = maxPx / longest
            val tw = max(1.0, floor(w * k))
            val th = max(1.0, floor(h * k))
            val format = UIGraphicsImageRendererFormat.preferredFormat().apply {
                scale = 1.0
                opaque = true
            }
            val small = UIGraphicsImageRenderer(size = CGSizeMake(tw, th), format = format).imageWithActions { _ ->
                image.drawInRect(CGRectMake(0.0, 0.0, tw, th))
            }
            val jpeg = UIImageJPEGRepresentation(small, 0.85)?.let { UIImage.imageWithData(it) }
            return IosPicture(jpeg ?: small)
        }
    }
}

/** One page, drawn in the UIKit graphics context [ctx] the renderer begun it in. */
private class IosPage(private val ctx: CGContextRef) : PdfPage {
    private fun Int.channel(shift: Int): Double = ((this shr shift) and 0xFF) / 255.0

    private fun fill(color: Int) = CGContextSetRGBFillColor(ctx, color.channel(16), color.channel(8), color.channel(0), color.channel(24))

    private fun uiColor(color: Int): UIColor =
        UIColor.colorWithRed(color.channel(16), green = color.channel(8), blue = color.channel(0), alpha = color.channel(24))

    /** Draws the path added by [path] as [pen] says: filled, or stroked (dashed when it is). */
    private fun draw(pen: Pen, path: () -> Unit) {
        CGContextSaveGState(ctx)
        CGContextBeginPath(ctx)
        path()
        if (pen.stroke > 0f) {
            CGContextSetRGBStrokeColor(ctx, pen.color.channel(16), pen.color.channel(8), pen.color.channel(0), pen.color.channel(24))
            CGContextSetLineWidth(ctx, pen.stroke.toDouble())
            val dash = pen.dash
            if (dash != null && dash.isNotEmpty()) memScoped {
                val lengths = allocArray<CGFloatVar>(dash.size)
                dash.forEachIndexed { i, d -> lengths[i] = d.toDouble() }
                CGContextSetLineDash(ctx, 0.0, lengths, dash.size.toULong())
                CGContextStrokePath(ctx)
            } else {
                CGContextStrokePath(ctx)
            }
        } else {
            fill(pen.color)
            CGContextFillPath(ctx)
        }
        CGContextRestoreGState(ctx)
    }

    override fun rect(left: Float, top: Float, right: Float, bottom: Float, pen: Pen) = draw(pen) {
        CGContextAddRect(ctx, CGRectMake(left.toDouble(), top.toDouble(), (right - left).toDouble(), (bottom - top).toDouble()))
    }

    override fun roundRect(left: Float, top: Float, right: Float, bottom: Float, radius: Float, pen: Pen) = draw(pen) {
        val w = (right - left).toDouble()
        val h = (bottom - top).toDouble()
        // CoreGraphics refuses a corner larger than half the side.
        val r = min(radius.toDouble(), min(w, h) / 2.0).coerceAtLeast(0.0)
        val path = CGPathCreateWithRoundedRect(CGRectMake(left.toDouble(), top.toDouble(), w, h), r, r, null)
        CGContextAddPath(ctx, path)
        CGPathRelease(path)
    }

    override fun line(x1: Float, y1: Float, x2: Float, y2: Float, pen: Pen) {
        // A line is always drawn as a line, as on Android's canvas, whatever the pen says about filling.
        val lined = if (pen.stroke > 0f) pen else Pen(pen.color, stroke = 1f, smooth = pen.smooth, dash = pen.dash)
        draw(lined) {
            CGContextMoveToPoint(ctx, x1.toDouble(), y1.toDouble())
            CGContextAddLineToPoint(ctx, x2.toDouble(), y2.toDouble())
        }
    }

    override fun circle(cx: Float, cy: Float, radius: Float, pen: Pen) = draw(pen) {
        CGContextAddEllipseInRect(ctx, CGRectMake((cx - radius).toDouble(), (cy - radius).toDouble(), 2.0 * radius, 2.0 * radius))
    }

    private fun uiFont(font: Font): UIFont =
        if (font.bold) UIFont.boldSystemFontOfSize(font.size.toDouble()) else UIFont.systemFontOfSize(font.size.toDouble())

    private fun attributes(font: Font, ui: UIFont): Map<Any?, Any?> = mapOf(NSFontAttributeName to ui, NSForegroundColorAttributeName to uiColor(font.color))

    override fun text(text: String, x: Float, y: Float, font: Font) {
        if (text.isEmpty()) return
        val ui = uiFont(font)
        val attrs = attributes(font, ui)
        @Suppress("CAST_NEVER_SUCCEEDS")
        val s = text as NSString
        val left = if (font.center) x - s.sizeWithAttributes(attrs).useContents { width }.toFloat() / 2f else x
        // UIKit draws a line of text from the top of its line: the baseline is the font's ascender below that.
        s.drawAtPoint(CGPointMake(left.toDouble(), y.toDouble() - ui.ascender), withAttributes = attrs)
    }

    override fun width(text: String, font: Font): Float {
        if (text.isEmpty()) return 0f
        val ui = uiFont(font)
        @Suppress("CAST_NEVER_SUCCEEDS")
        return (text as NSString).sizeWithAttributes(attributes(font, ui)).useContents { width }.toFloat()
    }

    override fun qr(text: String, left: Float, top: Float, size: Float) {
        // The same 240-pixel picture Android draws (its modules and quiet zone), as squares: one path, filled once, so
        // neighbouring squares meet without a seam.
        val px = 240
        val code = qrPixels(text, px)
        val k = size.toDouble() / code.size
        val x0 = left.toDouble()
        val y0 = top.toDouble()
        fill(Ink.WHITE)
        CGContextFillRect(ctx, CGRectMake(x0, y0, size.toDouble(), size.toDouble()))
        CGContextSaveGState(ctx)
        CGContextBeginPath(ctx)
        for (y in 0 until code.size) {
            var x = 0
            while (x < code.size) {
                if (!code[x, y]) {
                    x++
                    continue
                }
                val start = x
                while (x < code.size && code[x, y]) x++
                CGContextAddRect(ctx, CGRectMake(x0 + start * k, y0 + y * k, (x - start) * k, k))
            }
        }
        fill(Ink.BLACK)
        CGContextFillPath(ctx)
        CGContextRestoreGState(ctx)
    }

    override fun picture(picture: Picture, left: Float, top: Float, right: Float, bottom: Float) {
        (picture as IosPicture).image.drawInRect(CGRectMake(left.toDouble(), top.toDouble(), (right - left).toDouble(), (bottom - top).toDouble()))
    }
}
