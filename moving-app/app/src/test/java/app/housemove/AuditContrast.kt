package app.housemove

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsProperties
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/*
 * The part of the audit checks that looks at the rendered picture: the contrast of
 * every text against what is really behind it, and the marked copies of pictures.
 * Like AuditEngine it knows nothing about HouseMove, its colours or its look.
 *
 * How a text is measured, in short. Its lines are cut into patches a few letters
 * wide, so the background is judged where the letters are and a gradient is
 * followed. In each patch:
 *
 *  - If the text was given a solid colour and that colour is among the pixels, the
 *    letters are known exactly. Everything not touching a letter is background, and
 *    the patch scores the contrast that four fifths of that background reaches or
 *    beats. That is exact on a plain background and fair on a gradient, a glow or
 *    a photo.
 *  - Otherwise (a see-through text colour, a see-through layer over the text, no
 *    colour given) the background is the commonest colour in the patch, and the
 *    text colour is the given colour painted over it, or failing that the pixels
 *    that differ most from the background. Here a patterned background cannot be
 *    measured and is reported as such.
 *
 * The text as a whole gets the score of its weaker patches (a quarter of the way up).
 */

// ---- pixels and colours ------------------------------------------------------------

/** A rendered screen as plain pixels. */
internal class Pixels(val width: Int, val height: Int, val argb: IntArray)

internal fun readPixels(file: File): Pixels {
    val bitmap = BitmapFactory.decodeFile(file.path) ?: error("cannot read the picture $file")
    val px = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(px, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    val pixels = Pixels(bitmap.width, bitmap.height, px)
    bitmap.recycle()
    return pixels
}

private val LINEAR = DoubleArray(256) { i ->
    val s = i / 255.0
    if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
}

internal fun luminance(rgb: Int): Double =
    0.2126 * LINEAR[rgb shr 16 and 0xFF] + 0.7152 * LINEAR[rgb shr 8 and 0xFF] + 0.0722 * LINEAR[rgb and 0xFF]

/** WCAG contrast ratio of two opaque colours, 1 (same) to 21 (black on white). */
internal fun contrastRatio(a: Int, b: Int): Double {
    val la = luminance(a)
    val lb = luminance(b)
    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
}

internal fun hex(rgb: Int): String = "#%06X".format(rgb and 0xFFFFFF)

/** How far apart two colours are: the largest difference in red, green or blue, 0 to 255. */
private fun apart(a: Int, b: Int): Int = max(
    max(abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF)), abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF))),
    abs((a and 0xFF) - (b and 0xFF)),
)

/** [argb] painted over the opaque colour [under]. */
private fun over(argb: Int, under: Int): Int {
    val a = (argb ushr 24) / 255f
    fun mix(shift: Int) = ((argb shr shift and 0xFF) * a + (under shr shift and 0xFF) * (1f - a)).roundToInt().coerceIn(0, 255)
    return (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
}

private fun average(colours: IntArray, count: Int, keep: (Int) -> Boolean): Pair<Int, Int> {
    var r = 0L
    var g = 0L
    var b = 0L
    var n = 0
    for (i in 0 until count) {
        val c = colours[i]
        if (c >= 0 && keep(c)) {
            r += c shr 16 and 0xFF; g += c shr 8 and 0xFF; b += c and 0xFF; n++
        }
    }
    return if (n == 0) 0 to 0 else (((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()) to n
}

// ---- one patch of a text -----------------------------------------------------------

private class IntBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
    fun holds(x: Int, y: Int) = x in left until right && y in top until bottom
}

/** What one patch of a text says about its colours. [weight] is how much text the patch holds. */
private class Patch(val ratio: Double, val text: Int, val background: Int, val method: String, val weight: Int)

private const val PATCH_NO_TEXT = 0
private const val PATCH_BUSY = 1

internal const val METHOD_GIVEN = "given colour, seen in the pixels, against the background around the letters"
internal const val METHOD_GIVEN_ONLY = "given colour against the measured background (too alike to tell apart in the pixels)"
internal const val METHOD_PIXELS = "both colours from the pixels"

private val buckets = IntArray(32768)

private fun bucket(c: Int) = (c shr 19 and 0x1F shl 10) or (c shr 11 and 0x1F shl 5) or (c shr 3 and 0x1F)

/**
 * Measure one patch. [given] is the colour the text was given (with its alpha), if any.
 * [holes] are left out of the patch. [typed] is for what is typed into a text field: the
 * patch then also holds the frame of the field, so less of it is text.
 * The answer is a [Patch], or [PATCH_NO_TEXT] / [PATCH_BUSY].
 */
private fun patch(px: Pixels, box: IntBox, given: Int?, holes: List<IntBox>, typed: Boolean): Any {
    val w = box.width
    val h = box.height
    val colours = IntArray(w * h) // -1 where a hole is
    var n = 0
    for (y in 0 until h) {
        val row = (box.top + y) * px.width + box.left
        for (x in 0 until w) {
            val out = holes.isNotEmpty() && holes.any { it.holds(box.left + x, box.top + y) }
            colours[y * w + x] = if (out) -1 else px.argb[row + x] and 0xFFFFFF
            if (!out) n++
        }
    }
    if (n < 40) return PATCH_NO_TEXT
    val size = w * h

    // A solid given colour that is there to be seen: the letters are known exactly.
    if (given != null && (given ushr 24) >= 250) {
        val ink = given and 0xFFFFFF
        val letter = BooleanArray(size)
        var letters = 0
        for (i in 0 until size) if (colours[i] >= 0 && apart(colours[i], ink) <= 16) {
            letter[i] = true
            letters++
        }
        if (letters >= max(6, if (typed) 0 else n * 15 / 1000) && letters < n * 0.7) {
            // Background is whatever is not within two pixels of a letter (that rim is the letters' soft edge).
            val around = LongArray(n)
            var m = 0
            for (y in 0 until h) for (x in 0 until w) {
                val c = colours[y * w + x]
                if (c < 0) continue
                var near = false
                var dy = -2
                while (dy <= 2 && !near) {
                    val yy = y + dy
                    if (yy in 0 until h) {
                        var dx = -2
                        while (dx <= 2) {
                            val xx = x + dx
                            if (xx in 0 until w && letter[yy * w + xx]) {
                                near = true
                                break
                            }
                            dx++
                        }
                    }
                    dy++
                }
                if (!near) around[m++] = ((contrastRatio(ink, c) * 1000).toLong() shl 24) or c.toLong()
            }
            if (m >= max(20, n / 10)) {
                java.util.Arrays.sort(around, 0, m)
                // The contrast that four fifths of the background reaches or beats.
                val at = around[m / 5]
                return Patch((at shr 24) / 1000.0, ink, (at and 0xFFFFFF).toInt(), METHOD_GIVEN, letters)
            }
        }
    }

    // Otherwise: the background is the fullest bucket of similar colours, then everything close to its average.
    var top = -1
    for (i in 0 until size) {
        val c = colours[i]
        if (c < 0) continue
        val k = bucket(c)
        if (++buckets[k] > (if (top < 0) 0 else buckets[top])) top = k
    }
    for (i in 0 until size) if (colours[i] >= 0) buckets[bucket(colours[i])] = 0
    val rough = average(colours, size) { bucket(it) == top }.first
    val (background, behind) = average(colours, size) { apart(it, rough) <= 10 }
    if (behind < n * 0.3f) return PATCH_BUSY

    val painted = given?.let { over(it, background) }
    val paintedRatio = painted?.let { contrastRatio(it, background) }

    // Everything that is clearly not background: the text and its soft edges.
    val backgroundLum = luminance(background)
    val ink = ArrayList<Pair<Double, Int>>()
    var seen = 0
    for (i in 0 until size) {
        val c = colours[i]
        if (c < 0 || apart(c, background) <= 14) continue
        val l = luminance(c)
        ink += ((max(l, backgroundLum) + 0.05) / (min(l, backgroundLum) + 0.05)) to c
        if (painted != null && apart(c, painted) <= 16) seen++
    }
    if (ink.size < max(8, n / 100)) {
        // Nothing stands out. If the text was given a colour next to the background, that is the finding.
        return if (painted != null && paintedRatio!! < 1.35) Patch(paintedRatio, painted, background, METHOD_GIVEN_ONLY, max(n / 10, 1)) else PATCH_NO_TEXT
    }
    if (painted != null && seen >= max(6, if (typed) 0 else ink.size * 12 / 100)) {
        return Patch(paintedRatio!!, painted, background, METHOD_GIVEN, ink.size)
    }
    ink.sortByDescending { it.first }
    val strongest = ink.subList(0, max(4, ink.size * 8 / 100).coerceAtMost(ink.size))
    var r = 0L
    var g = 0L
    var b = 0L
    for ((_, c) in strongest) {
        r += c shr 16 and 0xFF; g += c shr 8 and 0xFF; b += c and 0xFF
    }
    val text = ((r / strongest.size).toInt() shl 16) or ((g / strongest.size).toInt() shl 8) or (b / strongest.size).toInt()
    return Patch(contrastRatio(text, background), text, background, METHOD_PIXELS, ink.size)
}

// ---- every text of a screen --------------------------------------------------------

/** What was made of one text at one view of the screen. */
private class Reading(
    /** How far the text was from the middle of its scrolling area, 0 to 1; 0 when it does not scroll. */
    val offCentre: Float,
    val result: LowContrast,
    val line: String,
    /** Why it could not be measured, or "". */
    val why: String,
)

/** What the contrast check makes of one screen, over all the scrolled views of it. */
internal class ContrastResult {
    val low = ArrayList<LowContrast>()

    /** Texts that pass, but by less than half as much again as they need. */
    val close = ArrayList<LowContrast>()
    val notMeasured = ArrayList<NotMeasured>()

    /** Every text that was measured, one line each, for checks/contrast.csv. */
    val all = ArrayList<String>()
    var measured = 0
    var disabledSkipped = 0
    var symbolsSkipped = 0

    private val readings = LinkedHashMap<String, Reading>()
    private val closed = HashSet<String>()

    internal fun isClosed(key: String) = key in closed
    internal fun close(key: String) {
        closed += key
    }

    private fun offCentreOf(key: String): Float = readings[key]?.offCentre ?: Float.MAX_VALUE
    internal fun wants(key: String, offCentre: Float) = offCentre < offCentreOf(key)
    private fun keep(key: String, reading: Reading) {
        readings[key] = reading
    }

    internal fun offer(key: String, offCentre: Float, result: LowContrast, line: String, why: String) {
        keep(key, Reading(offCentre, result, line, why))
        if (offCentre <= 0f) close(key)
    }

    /**
     * Call after the last view. A text on a scrolling screen may have been read more
     * than once; the reading nearest the middle of the screen counts, because near the
     * edges it may lie under a bar or a dock, which says nothing about the text.
     */
    fun settle() {
        for (r in readings.values) {
            if (r.why.isNotEmpty()) {
                notMeasured += NotMeasured(r.result.screen, r.result.text, r.why)
                continue
            }
            measured++
            all += r.line
            if (r.result.ratio < r.result.required) low += r.result else if (r.result.ratio < r.result.required * 1.5) close += r.result
        }
        readings.clear()
    }
}

/**
 * Read the contrast of every text that is on show in [px], the picture of this view.
 * Call it for each view of a screen with the same [into], then [ContrastResult.settle].
 */
internal fun contrast(tree: AuditTree, px: Pixels, screen: String, view: Int, into: ContrastResult) {
    val scale = px.width / tree.widthPx
    for (n in tree.nodes) {
        if (!n.placed || n.hidden) continue
        val editable = tree.isEditable(n) && n.typed.isNotBlank()
        val words = if (editable) n.typed else n.text
        if (words.isBlank() || (!editable && !n.config.contains(SemanticsProperties.Text))) continue
        val key = "${n.id}|$words"
        if (into.isClosed(key)) continue
        // Greyed-out controls are exempt from the contrast rules; symbols and emoji are not text.
        if (n.disabled) {
            into.close(key); into.disabledSkipped++; continue
        }
        if (words.none { it.isLetterOrDigit() }) {
            into.close(key); into.symbolsSkipped++; continue
        }
        val shown = n.shown.intersect(tree.screen)
        if (shown.width < 6f * tree.density || shown.height < 6f * tree.density || area(shown) < 0.6f * area(n.rect)) continue // wait for a view that shows it

        // Texts that scroll are read again in later views, and the reading nearest the middle is kept.
        val port = n.vScroll?.shown?.intersect(tree.screen)
        val offCentre = if (port == null || port.height <= 0f) 0f else (abs(shown.center.y - port.center.y) / port.height).coerceAtLeast(0.001f)
        if (!into.wants(key, offCentre)) continue

        val layout = n.textLayout()
        val style = layout?.layoutInput?.style
        val given = style?.color?.takeIf { it.isSpecified }?.toArgb()
        val sizeSp = style?.fontSize?.takeIf { it.isSp }?.value ?: 0f
        val bold = (style?.fontWeight?.weight ?: 400) >= 700
        if (given != null && (given ushr 24) < 26) {
            into.close(key) // deliberately invisible
            continue
        }

        fun box(r: Rect): IntBox? {
            val c = r.intersect(shown)
            val b = IntBox(
                (c.left * scale).roundToInt().coerceIn(0, px.width), (c.top * scale).roundToInt().coerceIn(0, px.height),
                (c.right * scale).roundToInt().coerceIn(0, px.width), (c.bottom * scale).roundToInt().coerceIn(0, px.height),
            )
            return if (b.width >= 4 && b.height >= 4) b else null
        }

        val patches = ArrayList<IntBox>()
        var holes = emptyList<IntBox>()
        if (editable || layout == null) {
            box(n.rect)?.let { patches += it }
            // In a text field only the typed text is wanted: leave out its label, icons and helper text
            // (not the outlines of its frame and of the typing, which would leave nothing to read).
            if (editable) holes = tree.nodes.subList(n.order + 1, n.last + 1).filter { it.placed && !it.shapeOnly }.mapNotNull { box(it.rect) }
        } else {
            // For plain texts Compose rebuilds the layout at the full width on offer, so a centred or
            // right-aligned line has to be put back where it sits in the real, narrower text.
            val para = layout.multiParagraph
            for (line in 0 until layout.lineCount) {
                val width = abs(layout.getLineRight(line) - layout.getLineLeft(line))
                val spare = para.width - width
                var left = min(layout.getLineLeft(line), layout.getLineRight(line))
                if (spare > 0.5f && para.width > n.rect.width + 0.5f) left = (left * (n.rect.width - width) / spare)
                left = left.coerceIn(0f, max(n.rect.width - width, 0f))
                val whole = box(
                    Rect(n.rect.left + left, n.rect.top + layout.getLineTop(line), n.rect.left + left + width, n.rect.top + layout.getLineBottom(line)),
                ) ?: continue
                val piece = max(whole.height * 3, 48)
                var x = whole.left
                while (x < whole.right) {
                    val end = if (whole.right - (x + piece) < piece / 2) whole.right else x + piece
                    patches += IntBox(x, whole.top, end, whole.bottom)
                    x = end
                }
            }
        }
        var busy = 0
        val found = ArrayList<Patch>()
        for (p in patches) {
            when (val got = patch(px, p, given, holes, editable)) {
                is Patch -> found += got
                PATCH_BUSY -> busy++
            }
        }
        val about = clip(words, 60)
        val large = sizeSp >= LARGE_TEXT_SP || (bold && sizeSp >= LARGE_BOLD_TEXT_SP)
        val needed = if (large) CONTRAST_LARGE else CONTRAST_NORMAL

        fun reading(pick: Patch?, why: String) {
            val result = LowContrast(
                screen = screen, text = about, ratio = pick?.let { (it.ratio * 100).roundToInt() / 100.0 } ?: 0.0, required = needed,
                textColor = pick?.let { hex(it.text) }.orEmpty(), background = pick?.let { hex(it.background) }.orEmpty(), sizeSp = sizeSp, bold = bold,
                method = pick?.method.orEmpty(), borderline = pick != null && pick.method == METHOD_PIXELS && pick.ratio > needed * 0.9 && pick.ratio < needed, view = view,
                xDp = tree.dp1(n.rect.left), yDp = tree.dp1(n.rect.top), widthDp = tree.dp1(n.rect.width), heightDp = tree.dp1(n.rect.height),
            )
            val line = listOf(
                screen, view, "%.2f".format(pick?.ratio ?: 0.0), needed, result.textColor, result.background, given?.let { "#%08X".format(it) } ?: "",
                sizeSp, if (bold) "bold" else "", result.method.substringBefore(','), "\"" + about.replace('"', '\'') + "\"",
            ).joinToString(",")
            into.offer(key, offCentre, result, line, why)
        }

        if (found.isEmpty() || busy > found.size) {
            reading(null, if (busy > 0) "it is drawn over a picture or a pattern, in a see-through colour or under a see-through layer" else "no text could be made out in the pixels (hidden or see-through)")
            continue
        }
        // The weaker end of the text counts, but not a single odd patch: take the patch a quarter of the way up.
        found.sortBy { it.ratio }
        val quarter = found.sumOf { it.weight } * 0.25
        var sum = 0.0
        val pick = found.first { sum += it.weight; sum >= quarter }

        var why = ""
        if (pick.method == METHOD_PIXELS && pick.ratio < needed) {
            if (editable) {
                why = "what is typed in the field could not be told apart from the frame of the field"
            } else if (given != null && pick.ratio < contrastRatio(over(given, pick.background), pick.background) * 0.8) {
                // Too weak, and weaker on screen than the colour it was given. With something drawn over it
                // (a bar, a see-through sheet) that says nothing about the text itself.
                val covered = tree.nodes.any { it.order > n.last && it.placed && overlap(it.shown, shown) > 0.1f * area(shown) }
                if (covered) why = "it is partly under something drawn on top of it"
            }
        }
        reading(pick, why)
    }
}

// ---- pictures with the findings marked ---------------------------------------------

internal const val MARK_TOUCH = 0xFFE53935.toInt() // red: less than 48dp to touch
internal const val MARK_NAME = 0xFF8E24AA.toInt() // purple: no name for screen readers
internal const val MARK_CONTRAST = 0xFFFB8C00.toInt() // orange: low contrast

/** A box to draw on a picture, in dp. */
internal class Mark(val xDp: Float, val yDp: Float, val widthDp: Float, val heightDp: Float, val colour: Int)

/** Save a copy of [picture] with a coloured box around each finding, so a person can see what is meant. */
internal fun markUp(picture: File, into: File, marks: List<Mark>, density: Float) {
    val source = BitmapFactory.decodeFile(picture.path) ?: return
    val copy = source.copy(Bitmap.Config.ARGB_8888, true)
    source.recycle()
    val canvas = Canvas(copy)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * density }
    val gap = 2f * density
    for (m in marks) {
        paint.color = m.colour
        canvas.drawRect(m.xDp * density - gap, m.yDp * density - gap, (m.xDp + m.widthDp) * density + gap, (m.yDp + m.heightDp) * density + gap, paint)
    }
    into.parentFile?.mkdirs()
    into.outputStream().use { copy.compress(Bitmap.CompressFormat.PNG, 100, it) }
    copy.recycle()
}
