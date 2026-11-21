package app.housemove

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.toSize
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/*
 * The measuring part of the audit checks. Everything here works on what Compose
 * reports about a screen (its semantics tree) and on the rendered picture, and
 * knows nothing about HouseMove: no routes, no texts, no colours. AuditChecksTest
 * opens the screens and AuditReport writes up what is found.
 */

/** Android's minimum size for something you tap, in dp. */
internal const val MIN_TOUCH_DP = 48f

/** Contrast that normal text needs, and what large text needs (WCAG 2 level AA). */
internal const val CONTRAST_NORMAL = 4.5
internal const val CONTRAST_LARGE = 3.0

/** Text counts as large from this size, or from the smaller size when bold. */
internal const val LARGE_TEXT_SP = 24f
internal const val LARGE_BOLD_TEXT_SP = 19f

private val HIDDEN_KEYS = setOf("HideFromAccessibility", "InvisibleToUser")

// ---- the semantics tree ------------------------------------------------------------

/** One node of the unmerged semantics tree, with the few things the checks ask about it. */
internal class AuditNode(val node: SemanticsNode, val parent: AuditNode?, val order: Int) {
    /** [order] of the last node below this one; nodes with a higher order are drawn on top of it. */
    var last: Int = order
    val id: Int get() = node.id
    val config: SemanticsConfiguration = node.config

    /** Where it is laid out, in root pixels, whether or not that is on show. */
    val rect: Rect = Rect(node.positionInRoot, node.size.toSize())

    /** The part that scrolling areas and other clipping parents leave on show. */
    val shown: Rect = node.boundsInRoot
    val placed: Boolean = node.layoutInfo.isPlaced && rect.width > 0f && rect.height > 0f

    /** Nearest parent that scrolls up and down / left and right. */
    val vScroll: AuditNode? = parent?.let { if (it.config.contains(SemanticsProperties.VerticalScrollAxisRange)) it else it.vScroll }
    val hScroll: AuditNode? = parent?.let { if (it.config.contains(SemanticsProperties.HorizontalScrollAxisRange)) it else it.hScroll }
    val disabled: Boolean = config.contains(SemanticsProperties.Disabled) || parent?.disabled == true
    val hidden: Boolean = config.any { it.key.name in HIDDEN_KEYS } || parent?.hidden == true

    val text: String get() = config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty()
    val typed: String get() = config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    fun isInside(other: AuditNode) = order > other.order && order <= other.last

    fun textLayout(): TextLayoutResult? = try {
        val got = ArrayList<TextLayoutResult>()
        config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(got)
        got.firstOrNull()
    } catch (_: Exception) {
        null
    }
}

internal fun SemanticsConfiguration.labels(): List<String> =
    (getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty() + getOrNull(SemanticsProperties.ContentDescription).orEmpty())
        .map { it.trim() }.filter { it.isNotEmpty() }

/** A screen as it stands: every node, and what a screen reader makes of the ones it stops on. */
internal class AuditTree(root: SemanticsNode, mergedRoot: SemanticsNode) {
    val nodes = ArrayList<AuditNode>()
    private val mergedConfig = HashMap<Int, SemanticsConfiguration>()
    val widthPx = root.size.width.toFloat()
    val heightPx = root.size.height.toFloat()
    val density = root.layoutInfo.density.density
    val fontScale = root.layoutInfo.density.fontScale
    val screen = Rect(0f, 0f, widthPx, heightPx)

    init {
        add(root, null)
        collect(mergedRoot)
    }

    private fun add(n: SemanticsNode, parent: AuditNode?) {
        val a = AuditNode(n, parent, nodes.size)
        nodes += a
        for (c in n.children) add(c, a)
        a.last = nodes.size - 1
    }

    private fun collect(n: SemanticsNode) {
        mergedConfig[n.id] = n.config
        for (c in n.children) collect(c)
    }

    fun dp(px: Float): Float = px / density
    fun dp1(px: Float): Float = (px / density * 10f).roundToInt() / 10f

    /** What a screen reader reads for this node with its children merged in; null when it is merged into a parent. */
    fun merged(n: AuditNode): SemanticsConfiguration? = mergedConfig[n.id]

    /** Everything that can be tapped: buttons, rows, switches, check boxes, tabs, text fields. */
    val actionable: List<AuditNode> by lazy {
        nodes.filter { it.placed && !it.hidden && merged(it)?.contains(SemanticsActions.OnClick) == true }
    }

    fun isEditable(n: AuditNode): Boolean = merged(n)?.contains(SemanticsActions.SetText) == true

    /** Its name for a screen reader, or "" when it has none. A text field's typed text is not a name. */
    fun name(n: AuditNode): String = merged(n)?.labels().orEmpty().joinToString(" · ")

    fun role(n: AuditNode): String {
        val m = merged(n) ?: n.config
        m.getOrNull(SemanticsProperties.Role)?.let { return it.toString() }
        return when {
            m.contains(SemanticsActions.SetText) -> "Text field"
            m.contains(SemanticsProperties.ToggleableState) -> "Toggle"
            m.contains(SemanticsProperties.Selected) -> "Choice"
            else -> "Clickable"
        }
    }

    fun state(n: AuditNode): String {
        val m = merged(n) ?: n.config
        m.getOrNull(SemanticsProperties.ToggleableState)?.let { return it.toString().lowercase() }
        m.getOrNull(SemanticsProperties.Selected)?.let { return if (it) "selected" else "not selected" }
        return ""
    }

    /** The closest text on the screen, to help a person find a node that has no name of its own. */
    fun nearestText(n: AuditNode): String {
        var best = ""
        var bestGap = 160f * density
        for (t in nodes) {
            if (!t.placed || t === n) continue
            val s = t.text.trim()
            if (s.isEmpty()) continue
            val dx = max(max(t.rect.left - n.rect.right, n.rect.left - t.rect.right), 0f)
            val dy = max(max(t.rect.top - n.rect.bottom, n.rect.top - t.rect.bottom), 0f)
            // Text on the same line is a better pointer than text above or below.
            val gap = dx + dy * 3f
            if (gap < bestGap) {
                bestGap = gap
                best = s
            }
        }
        return best
    }
}

internal fun clip(s: String, n: Int = 70): String {
    val one = s.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
    return if (one.length <= n) one else one.take(n - 1).trimEnd() + "…"
}

private fun area(r: Rect): Float = max(r.width, 0f) * max(r.height, 0f)

private fun overlap(a: Rect, b: Rect): Float =
    max(min(a.right, b.right) - max(a.left, b.left), 0f) * max(min(a.bottom, b.bottom) - max(a.top, b.top), 0f)

// ---- 1. touch targets --------------------------------------------------------------

/**
 * Controls drawn smaller than 48x48dp, each with the area that really answers to a finger.
 *
 * Compose stretches the touch area of a small control to 48dp where it can: a touch
 * just outside it still counts, unless it lands on another control or is nearer to
 * another small one. So the size a control is drawn at says little. This works out
 * what is left of the 48dp once neighbours and the screen edge have taken their share,
 * measured across the middle of the control in both directions.
 */
internal fun touchTargets(tree: AuditTree, screen: String, view: Int): List<TouchTarget> {
    val minPx = MIN_TOUCH_DP * tree.density
    val all = tree.actionable
    val out = ArrayList<TouchTarget>()

    fun grown(r: Rect): Rect {
        val dx = max((minPx - r.width) / 2f, 0f)
        val dy = max((minPx - r.height) / 2f, 0f)
        return Rect(r.left - dx, r.top - dy, r.right + dx, r.bottom + dy)
    }

    fun gapSquared(r: Rect, x: Float, y: Float): Float {
        val dx = max(max(r.left - x, x - r.right), 0f)
        val dy = max(max(r.top - y, y - r.bottom), 0f)
        return dx * dx + dy * dy
    }

    for (a in all) {
        val r = a.rect
        if (r.width >= minPx - 0.5f && r.height >= minPx - 0.5f) continue
        var e = grown(r)
        // A finger cannot land beyond the edge of the screen, unless the control can be scrolled away from it.
        if (a.hScroll == null) e = Rect(max(e.left, 0f), e.top, min(e.right, tree.widthPx), e.bottom)
        if (a.vScroll == null) e = Rect(e.left, max(e.top, 0f), e.right, min(e.bottom, tree.heightPx))
        // Neighbours that can take touches from it: live controls that scroll together with it.
        // A control inside it or around it does not compete (the inner one wins its own stretch).
        val rivals = all.filter { b ->
            b !== a && !b.disabled && !a.isInside(b) && !b.isInside(a) && b.vScroll === a.vScroll &&
                (b.hScroll === a.hScroll || (area(a.shown) > 0f && area(b.shown) > 0f)) && overlap(grown(b.rect), e) > 0f
        }
        val takers = LinkedHashSet<AuditNode>()

        fun mine(x: Float, y: Float): Boolean {
            if (r.contains(androidx.compose.ui.geometry.Offset(x, y))) return true
            for (b in rivals) if (b.rect.contains(androidx.compose.ui.geometry.Offset(x, y))) {
                takers += b
                return false
            }
            val own = gapSquared(r, x, y)
            for (b in rivals) {
                val small = b.rect.width < minPx - 0.5f || b.rect.height < minPx - 0.5f
                if (small && grown(b.rect).contains(androidx.compose.ui.geometry.Offset(x, y)) && gapSquared(b.rect, x, y) < own) {
                    takers += b
                    return false
                }
            }
            return true
        }

        val step = tree.density
        var w = 0
        var x = e.left + step / 2f
        while (x < e.right) {
            if (mine(x, r.center.y)) w++
            x += step
        }
        var h = 0
        var y = e.top + step / 2f
        while (y < e.bottom) {
            if (mine(r.center.x, y)) h++
            y += step
        }
        val touchW = min(w.toFloat(), tree.dp1(e.width))
        val touchH = min(h.toFloat(), tree.dp1(e.height))
        out += TouchTarget(
            screen = screen,
            label = clip(tree.name(a).ifEmpty { "(no name) near \"${clip(tree.nearestText(a), 30)}\"" }),
            role = tree.role(a),
            widthDp = tree.dp1(r.width), heightDp = tree.dp1(r.height),
            touchWidthDp = touchW, touchHeightDp = touchH,
            tooSmall = touchW < MIN_TOUCH_DP - 0.5f || touchH < MIN_TOUCH_DP - 0.5f,
            crowdedBy = takers.map { clip(tree.name(it).ifEmpty { tree.role(it) }, 40) }.distinct().take(3),
            onTopOf = takers.any { it.rect.left <= r.left && it.rect.top <= r.top && it.rect.right >= r.right && it.rect.bottom >= r.bottom },
            atEdge = e.width < minPx - 0.5f || e.height < minPx - 0.5f,
            disabled = a.disabled,
            xDp = tree.dp1(r.left), yDp = tree.dp1(r.top), view = view, key = "${a.id}|${tree.name(a)}",
        )
    }
    return out
}

// ---- 2. names for screen readers ---------------------------------------------------

/**
 * Controls a screen reader stops on but cannot name: no text and no description,
 * neither on the control nor on anything that is merged into it.
 */
internal fun unnamed(tree: AuditTree, screen: String, view: Int): List<Unnamed> {
    val all = tree.actionable
    val out = ArrayList<Unnamed>()
    for (a in all) {
        if (tree.name(a).isNotEmpty()) continue
        // Content that is read as an element of its own right inside it still tells the listener what it is.
        val inner = tree.nodes.subList(a.order + 1, a.last + 1).filter { d ->
            d.placed && !d.hidden && all.none { b -> b !== a && b.isInside(a) && (d === b || d.isInside(b)) }
        }
        if (inner.any { it.config.labels().isNotEmpty() }) continue
        val around = generateSequence(a.parent) { it.parent }.firstOrNull { it in all && tree.name(it).isNotEmpty() }
        out += Unnamed(
            screen = screen, role = tree.role(a), state = tree.state(a),
            widthDp = tree.dp1(a.rect.width), heightDp = tree.dp1(a.rect.height),
            xDp = tree.dp1(a.rect.left), yDp = tree.dp1(a.rect.top),
            insideNamed = around?.let { clip(tree.name(it), 50) }.orEmpty(),
            near = clip(tree.nearestText(a), 50),
            textField = tree.isEditable(a),
            view = view, key = "${a.id}|${tree.role(a)}|${around?.id}",
        )
    }
    return out
}

// ---- 5. typing needed --------------------------------------------------------------

/** Every field on the screen that takes typed text, with the label a person sees on it. */
internal fun textFields(tree: AuditTree, screen: String): List<TypedField> =
    tree.actionable.filter { tree.isEditable(it) }.map { f ->
        val labels = tree.merged(f)?.labels().orEmpty()
        TypedField(
            screen = screen,
            label = clip(labels.firstOrNull() ?: "(no label)", 60),
            hint = clip(labels.drop(1).joinToString(" · "), 60),
            filled = f.typed.isNotBlank(),
            key = "${f.id}|${labels.firstOrNull()}",
        )
    }

// ---- 4. cut-off text, things beyond the screen --------------------------------------

internal const val CUT_ELLIPSIS = "ellipsis"
internal const val CUT_CLIPPED = "clipped"
internal const val CUT_BEYOND_WIDTH = "beyond-width"
internal const val CUT_UNREACHABLE = "unreachable"
internal const val CUT_BY_PARENT = "cut-by-parent"
internal const val CUT_SPILLS = "spills"

/**
 * Text that does not fit and controls that leave the screen, for one screen at one size.
 * Things that are merely scrolled out of view are not reported: only what no scrolling brings back.
 */
internal fun cutOffs(tree: AuditTree, screen: String): List<CutOff> {
    val out = ArrayList<CutOff>()
    val slack = tree.density // 1dp of rounding
    val reported = ArrayList<AuditNode>()
    val all = tree.actionable
    for (n in tree.nodes) {
        if (!n.placed || n.hidden) continue
        val isText = n.config.contains(SemanticsProperties.Text)
        val isControl = n in all
        if (!isText && !isControl) continue
        val what = clip(if (isText) n.text else tree.name(n).ifEmpty { tree.role(n) }, 80)
        if (what.isBlank()) continue

        if (isText && !tree.isEditable(n)) n.textLayout()?.let { layout ->
            // Not layout.hasVisualOverflow: for plain texts Compose rebuilds this layout at the full width
            // on offer, so "narrower than the paragraph" says nothing. Lines and heights are reliable.
            val para = layout.multiParagraph
            val lines = layout.lineCount
            val ellipsised = (0 until lines).any { layout.isLineEllipsized(it) }
            val tooTall = para.didExceedMaxLines || layout.size.height < para.height - 0.5f
            // Line widths from left and right edge: getLineWidth would count the space a wrapped line ends in.
            val tooWide = (0 until lines).maxOfOrNull { kotlin.math.abs(layout.getLineRight(it) - layout.getLineLeft(it)) }?.let { it > layout.size.width + slack } == true
            if (ellipsised) {
                val total = layout.layoutInput.text.length
                val visible = layout.getLineEnd(lines - 1, visibleEnd = true)
                val max = layout.layoutInput.maxLines
                val limit = if (max in 1..99) " (limit $max line${if (max == 1) "" else "s"})" else ""
                out += CutOff(
                    screen, CUT_ELLIPSIS, what,
                    (if (visible <= 0) "nothing of it is left, only …" else "ends in … after $visible of $total characters") + limit,
                    shownChars = visible, totalChars = total,
                )
            } else if (tooTall || tooWide) {
                val how = listOfNotNull(if (tooWide) "wider" else null, if (tooTall) "taller" else null).joinToString(" and ")
                out += CutOff(screen, CUT_CLIPPED, what, "the text is $how than the space it gets, and is cut without …")
            }
        }

        if (reported.any { n.isInside(it) }) continue
        val r = n.rect
        val s = n.shown
        when {
            n.hScroll == null && (r.left < -slack || r.right > tree.widthPx + slack) -> {
                reported += n
                val by = tree.dp1(max(-r.left, r.right - tree.widthPx))
                out += CutOff(screen, CUT_BEYOND_WIDTH, what, "reaches ${by}dp beyond the side of the screen")
            }
            n.vScroll == null && (r.bottom > tree.heightPx + slack || r.top < -slack) -> {
                reported += n
                val by = tree.dp1(max(-r.top, r.bottom - tree.heightPx))
                out += CutOff(screen, CUT_UNREACHABLE, what, "${by}dp of it is above or below the screen, and the screen does not scroll there")
            }
            area(s) <= 0f && n.vScroll == null && n.hScroll == null -> {
                reported += n
                out += CutOff(screen, CUT_BY_PARENT, what, "hidden completely by the area it sits in")
            }
            area(s) > 0f && n.hScroll == null && r.width - s.width > slack -> {
                reported += n
                out += CutOff(screen, CUT_BY_PARENT, what, "${tree.dp1(r.width - s.width)}dp of its width is cut away by the area it sits in")
            }
            area(s) > 0f && n.vScroll == null && r.height - s.height > slack -> {
                reported += n
                out += CutOff(screen, CUT_BY_PARENT, what, "${tree.dp1(r.height - s.height)}dp of its height is cut away by the area it sits in")
            }
            isText -> {
                // Text that has grown out of the button or row it belongs to.
                val home = generateSequence(n.parent) { it.parent }.firstOrNull { it in all }
                if (home != null) {
                    val over = max(max(home.rect.left - r.left, r.right - home.rect.right), max(home.rect.top - r.top, r.bottom - home.rect.bottom))
                    if (over > 2f * slack) {
                        out += CutOff(screen, CUT_SPILLS, what, "sticks ${tree.dp1(over)}dp out of the control it belongs to (\"${clip(tree.name(home), 30)}\")")
                    }
                }
            }
        }
    }
    return out
}

// ---- 3. contrast -------------------------------------------------------------------

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

private fun apart(a: Int, b: Int): Int = max(
    max(kotlin.math.abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF)), kotlin.math.abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF))),
    kotlin.math.abs((a and 0xFF) - (b and 0xFF)),
)

/** [argb] painted over the opaque colour [under]. */
private fun over(argb: Int, under: Int): Int {
    val a = (argb ushr 24) / 255f
    fun mix(shift: Int) = ((argb shr shift and 0xFF) * a + (under shr shift and 0xFF) * (1f - a)).roundToInt().coerceIn(0, 255)
    return (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
}

private class IntBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
    fun holds(x: Int, y: Int) = x in left until right && y in top until bottom
}

/** What one small patch of a text says about its colours. */
private class Patch(val ratio: Double, val text: Int, val background: Int, val method: String, val weight: Int)

private const val PATCH_NO_TEXT = 0
private const val PATCH_BUSY = 1

private val buckets = IntArray(32768)

/**
 * Looks at the pixels of one patch of a text.
 *
 * Background: the commonest colour in the patch. Text: when the colour the text was
 * given (painted over that background) really is among the pixels, that colour is
 * used, since it is exact; otherwise the text colour is taken from the pixels that
 * differ most from the background, which copes with see-through layers and anything
 * else that changes what ends up on screen.
 */
private fun patch(px: Pixels, box: IntBox, declared: Int?, holes: List<IntBox>): Any {
    val colours = IntArray(box.width * box.height)
    var n = 0
    for (y in box.top until box.bottom) {
        val row = y * px.width
        for (x in box.left until box.right) {
            if (holes.isNotEmpty() && holes.any { it.holds(x, y) }) continue
            colours[n++] = px.argb[row + x] and 0xFFFFFF
        }
    }
    if (n < 40) return PATCH_NO_TEXT

    // Background: the fullest bucket of similar colours, then everything close to its average.
    var top = 0
    for (i in 0 until n) {
        val c = colours[i]
        val k = (c shr 19 and 0x1F shl 10) or (c shr 11 and 0x1F shl 5) or (c shr 3 and 0x1F)
        if (++buckets[k] > buckets[top]) top = k
    }
    var r = 0L
    var g = 0L
    var b = 0L
    var count = 0
    for (i in 0 until n) {
        val c = colours[i]
        val k = (c shr 19 and 0x1F shl 10) or (c shr 11 and 0x1F shl 5) or (c shr 3 and 0x1F)
        buckets[k] = 0
        if (k == top) {
            r += c shr 16 and 0xFF; g += c shr 8 and 0xFF; b += c and 0xFF; count++
        }
    }
    val rough = ((r / count).toInt() shl 16) or ((g / count).toInt() shl 8) or (b / count).toInt()
    r = 0; g = 0; b = 0; count = 0
    for (i in 0 until n) {
        val c = colours[i]
        if (apart(c, rough) <= 10) {
            r += c shr 16 and 0xFF; g += c shr 8 and 0xFF; b += c and 0xFF; count++
        }
    }
    val background = ((r / count).toInt() shl 16) or ((g / count).toInt() shl 8) or (b / count).toInt()
    if (count < n * 0.3f) return PATCH_BUSY

    val given = declared?.let { over(it, background) }
    val givenRatio = given?.let { contrastRatio(it, background) }

    // Everything that is clearly not background: the text and its soft edges.
    val bgLum = luminance(background)
    val ink = ArrayList<Pair<Double, Int>>()
    var seen = 0
    for (i in 0 until n) {
        val c = colours[i]
        if (apart(c, background) <= 14) continue
        val l = luminance(c)
        ink += ((max(l, bgLum) + 0.05) / (min(l, bgLum) + 0.05)) to c
        if (given != null && apart(c, given) <= 16) seen++
    }
    if (ink.size < max(8, n / 100)) {
        // Nothing stands out. If the text was given a colour next to the background, that is the finding.
        return if (given != null && givenRatio!! < 1.35) Patch(givenRatio, given, background, METHOD_GIVEN_ONLY, max(n / 10, 1)) else PATCH_NO_TEXT
    }
    if (given != null && seen >= max(6, ink.size * 12 / 100)) {
        return Patch(givenRatio!!, given, background, METHOD_GIVEN, ink.size)
    }
    ink.sortByDescending { it.first }
    val strongest = ink.subList(0, max(4, ink.size * 8 / 100).coerceAtMost(ink.size))
    r = 0; g = 0; b = 0
    for ((_, c) in strongest) {
        r += c shr 16 and 0xFF; g += c shr 8 and 0xFF; b += c and 0xFF
    }
    val text = ((r / strongest.size).toInt() shl 16) or ((g / strongest.size).toInt() shl 8) or (b / strongest.size).toInt()
    return Patch(contrastRatio(text, background), text, background, METHOD_PIXELS, ink.size)
}

internal const val METHOD_GIVEN = "given colour, seen in the pixels, on the measured background"
internal const val METHOD_GIVEN_ONLY = "given colour on the measured background (too close to tell apart in the pixels)"
internal const val METHOD_PIXELS = "both colours from the pixels"

/** A text that shows weaker than the colour it was given, as last seen. */
internal class Dimmed(val result: LowContrast, val line: String, val covered: Boolean)

/** What the contrast check made of one screen, over all the scrolled views of it. */
internal class ContrastResult {
    /** Texts held back because they were dimmer on screen than given; see [settle]. */
    val dimmed = LinkedHashMap<String, Dimmed>()

    /**
     * Call after the last view. A text that stayed dim wherever it scrolled is a finding,
     * unless something is drawn over it: then nothing can be said about the text itself.
     */
    fun settle() {
        for (d in dimmed.values) {
            if (d.covered) {
                notMeasured += NotMeasured(d.result.screen, d.result.text, "it is partly under something drawn on top of it")
            } else {
                measured++
                all += d.line
                low += d.result
            }
        }
        dimmed.clear()
    }

    val low = ArrayList<LowContrast>()

    /** Texts that pass, but by less than half as much again as they need. */
    val close = ArrayList<LowContrast>()
    val notMeasured = ArrayList<NotMeasured>()
    /** Every text that was measured, one line each, for checks/contrast.csv. */
    val all = ArrayList<String>()
    var measured = 0
    var disabledSkipped = 0
    var symbolsSkipped = 0
}

/**
 * Estimates the contrast of every text that is on show in [px], the picture of this view.
 * [done] remembers what was dealt with in earlier views of the same screen, so a text is
 * looked at once, at the first view that shows enough of it.
 */
internal fun contrast(tree: AuditTree, px: Pixels, screen: String, view: Int, done: MutableSet<String>, into: ContrastResult) {
    val scale = px.width / tree.widthPx
    for (n in tree.nodes) {
        if (!n.placed || n.hidden) continue
        val editable = tree.isEditable(n) && n.typed.isNotBlank()
        val words = if (editable) n.typed else n.text
        if (words.isBlank() || (!editable && !n.config.contains(SemanticsProperties.Text))) continue
        val key = "${n.id}|$words"
        if (key in done) continue
        // Greyed-out controls are exempt from the contrast rules; symbols and emoji are not text.
        if (n.disabled) {
            done += key; into.disabledSkipped++; continue
        }
        if (words.none { it.isLetterOrDigit() }) {
            done += key; into.symbolsSkipped++; continue
        }
        val shown = n.shown.intersect(tree.screen)
        if (shown.width < 6f * tree.density || shown.height < 6f * tree.density || area(shown) < 0.6f * area(n.rect)) continue // wait for a view that shows it
        done += key

        val layout = n.textLayout()
        val style = layout?.layoutInput?.style
        val declared = style?.color?.takeIf { it.isSpecified }?.toArgb()
        val sizeSp = style?.fontSize?.takeIf { it.isSp }?.value ?: 0f
        val bold = (style?.fontWeight?.weight ?: 400) >= 700
        if (declared != null && (declared ushr 24) < 26) continue // deliberately invisible

        fun box(r: Rect): IntBox? {
            val c = r.intersect(shown)
            val b = IntBox(
                (c.left * scale).roundToInt().coerceIn(0, px.width), (c.top * scale).roundToInt().coerceIn(0, px.height),
                (c.right * scale).roundToInt().coerceIn(0, px.width), (c.bottom * scale).roundToInt().coerceIn(0, px.height),
            )
            return if (b.width >= 4 && b.height >= 4) b else null
        }

        // Patches to look at: each line of the text, cut into pieces a few letters wide,
        // so that a background that changes along the text is still judged locally.
        val patches = ArrayList<IntBox>()
        var holes = emptyList<IntBox>()
        if (editable || layout == null) {
            box(n.rect)?.let { patches += it }
            // In a text field only the typed text is wanted: leave out its label, icons and helper text.
            if (editable) holes = tree.nodes.subList(n.order + 1, n.last + 1).filter { it.placed }.mapNotNull { box(it.rect) }
        } else {
            // For plain texts Compose rebuilds the layout at the full width on offer, so a centred or
            // right-aligned line has to be put back where it sits in the real, narrower text.
            val para = layout.multiParagraph
            for (line in 0 until layout.lineCount) {
                val width = kotlin.math.abs(layout.getLineRight(line) - layout.getLineLeft(line))
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
            when (val got = patch(px, p, declared, holes)) {
                is Patch -> found += got
                PATCH_BUSY -> busy++
            }
        }
        val about = clip(words, 60)
        if (found.isEmpty() || busy > found.size) {
            into.notMeasured += NotMeasured(screen, about, if (busy > 0) "it is drawn over a picture or a pattern" else "no text could be made out in the pixels (hidden or see-through)")
            continue
        }
        // The weaker end of the text counts, but not a single odd patch: take the patch a quarter of the way up.
        found.sortBy { it.ratio }
        val quarter = found.sumOf { it.weight } * 0.25
        var sum = 0.0
        val pick = found.first { sum += it.weight; sum >= quarter }

        val large = sizeSp >= LARGE_TEXT_SP || (bold && sizeSp >= LARGE_BOLD_TEXT_SP)
        val needed = if (large) CONTRAST_LARGE else CONTRAST_NORMAL
        val result = LowContrast(
            screen = screen, text = about, ratio = (pick.ratio * 100).roundToInt() / 100.0, required = needed,
            textColor = hex(pick.text), background = hex(pick.background), sizeSp = sizeSp, bold = bold,
            method = pick.method, borderline = pick.method == METHOD_PIXELS && pick.ratio > needed * 0.9, view = view,
            xDp = tree.dp1(n.rect.left), yDp = tree.dp1(n.rect.top), widthDp = tree.dp1(n.rect.width), heightDp = tree.dp1(n.rect.height),
        )
        val line = listOf(
            screen, view, "%.2f".format(pick.ratio), needed, hex(pick.text), hex(pick.background), declared?.let { "#%08X".format(it) } ?: "",
            sizeSp, if (bold) "bold" else "", pick.method.substringBefore(','), "\"" + about.replace('"', '\'') + "\"",
        ).joinToString(",")
        if (pick.ratio < needed && pick.method == METHOD_PIXELS && declared != null &&
            pick.ratio < contrastRatio(over(declared, pick.background), pick.background) * 0.8
        ) {
            // Too weak, and weaker on screen than the colour it was given. That can be the text's own doing
            // (a see-through layer around it), or it may only be lying under a bar at this point of scrolling.
            // So it is held back and looked at again in the next views; ContrastResult.settle decides.
            done -= key
            val covered = tree.nodes.any { it.order > n.last && it.placed && overlap(it.shown, shown) > 0.1f * area(shown) }
            into.dimmed[key] = Dimmed(result, line, covered)
            continue
        }
        into.dimmed.remove(key)
        into.measured++
        into.all += line
        if (pick.ratio < needed) into.low += result else if (pick.ratio < needed * 1.5) into.close += result
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
