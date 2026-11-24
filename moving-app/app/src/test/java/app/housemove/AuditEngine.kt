package app.housemove

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.toSize
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * The measuring part of the audit checks that works on what Compose reports about a
 * screen (its semantics tree): touch targets, names, text fields, text that does not
 * fit. The part that looks at the rendered picture is in AuditContrast.kt.
 *
 * Nothing here knows about HouseMove: no routes, no texts, no colours. AuditChecksTest
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

    /**
     * Only the outline of a rounded or clipped layer, with nothing of its own to show or read. Compose adds
     * these to the tree from 1.9 on: a text field has one round its frame and one round the typing.
     */
    val shapeOnly: Boolean = config.all { it.key.name == "Shape" }

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

internal fun area(r: Rect): Float = max(r.width, 0f) * max(r.height, 0f)

internal fun overlap(a: Rect, b: Rect): Float =
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
            if (r.contains(Offset(x, y))) return true
            for (b in rivals) if (b.rect.contains(Offset(x, y))) {
                takers += b
                return false
            }
            val own = gapSquared(r, x, y)
            for (b in rivals) {
                val small = b.rect.width < minPx - 0.5f || b.rect.height < minPx - 0.5f
                if (small && grown(b.rect).contains(Offset(x, y)) && gapSquared(b.rect, x, y) < own) {
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
            val tooWide = (0 until lines).maxOfOrNull { abs(layout.getLineRight(it) - layout.getLineLeft(it)) }?.let { it > layout.size.width + slack } == true
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
