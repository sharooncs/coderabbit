@file:OptIn(ExperimentalForeignApi::class)

package app.housemove.pdf

import app.housemove.data.File
import app.housemove.data.IOException
import app.housemove.data.qrPixels
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRect
import platform.CoreGraphics.CGPDFContextCreateWithURL
import platform.CoreGraphics.CGPDFContextClose
import platform.CoreGraphics.CGContextBeginPage
import platform.CoreGraphics.CGContextEndPage
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGContextTranslateCTM
import platform.CoreGraphics.CGContextScaleCTM
import platform.UIKit.UIGraphicsPushContext
import platform.UIKit.UIGraphicsPopContext
import platform.Foundation.CFBridgingRetain
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFURLRef
import kotlinx.cinterop.reinterpret
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
import platform.Foundation.NSFileManager
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
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.drawAtPoint
import platform.UIKit.sizeWithAttributes
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/*
 * The printouts' drawing interface (commonMain pdf/Draw.kt) on the iPhone: CoreGraphics' PDF context makes the file, CoreGraphics draws the shapes, UIKit the text (the system font, embedded in the PDF) and the photos
 * (upright, read small and kept as JPEG), and QR codes are the shared encoder's modules as vector squares, so they stay
 * sharp at any printer's resolution. Coordinates are UIKit's: points from the top left, y going down, as the common
 * layout's. UIKit's drawing may be used off the main thread, where the screens make their files.
 */

/** The iPhone's PDFs and the inventory spreadsheet. */
object IosPaperWriter : PaperWriter {
    /**
     * A PDF made by CoreGraphics' PDF context, each page begun with its own media box (A4, Letter, landscape), and
     * pushed as UIKit's current context, turned so y goes down, for UIKit's text and photos. (UIKit's PDF renderer gave
     * a Letter page the renderer's own A4 size.)
     */
    override fun pdf(file: File, draw: (PdfDoc) -> Unit): File {
        // A printout made again under the same name is a new file, never the old one written over in place.
        NSFileManager.defaultManager.removeItemAtPath(file.path, error = null)
        val retained = CFBridgingRetain(NSURL.fileURLWithPath(file.path)) ?: throw IOException("no URL for ${file.path}")
        val url: CFURLRef = retained.reinterpret()
        val ctx = try {
            memScoped {
                // The size of a page begun without one; every page here gives its own.
                val a4 = alloc<CGRect>().apply { origin.x = 0.0; origin.y = 0.0; size.width = 595.0; size.height = 842.0 }
                CGPDFContextCreateWithURL(url, a4.ptr, null)
            }
        } finally {
            CFRelease(url)
        } ?: throw IOException("could not write ${file.path}")
        val doc = IosDoc(ctx)
        try {
            draw(doc)
        } finally {
            doc.close()
            CGPDFContextClose(ctx)
            CGContextRelease(ctx)
        }
        return file
    }

    override fun text(file: File, text: String): File {
        @Suppress("CAST_NEVER_SUCCEEDS")
        val ok = (text as NSString).writeToFile(file.path, atomically = true, encoding = NSUTF8StringEncoding, error = null)
        if (!ok) throw IOException("could not write ${file.path}")
        return file
    }
}

/** A PDF being drawn in CoreGraphics' PDF context [ctx]: each page begun with its own size, drawn with UIKit's y going down. */
private class IosDoc(private val ctx: CGContextRef) : PdfDoc {
    private var open = false

    override fun newPage(w: Int, h: Int): PdfPage {
        endPage()
        memScoped {
            val box = alloc<CGRect>().apply { origin.x = 0.0; origin.y = 0.0; size.width = w.toDouble(); size.height = h.toDouble() }
            CGContextBeginPage(ctx, box.ptr)
        }
        // PDF's y goes up from the bottom; UIKit's, and the layout's, down from the top.
        CGContextSaveGState(ctx)
        CGContextTranslateCTM(ctx, 0.0, h.toDouble())
        CGContextScaleCTM(ctx, 1.0, -1.0)
        UIGraphicsPushContext(ctx)
        open = true
        return IosPage(ctx)
    }

    override fun endPage() {
        if (!open) return
        UIGraphicsPopContext()
        CGContextRestoreGState(ctx)
        CGContextEndPage(ctx)
        open = false
    }

    /** Ends the last page, if one is open: before the PDF is closed. */
    fun close() = endPage()

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
