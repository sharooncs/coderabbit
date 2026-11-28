package app.housemove.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.housemove.data.Action
import app.housemove.data.AppData
import app.housemove.data.Part
import app.housemove.data.can
import app.housemove.data.uses
import app.housemove.data.waitingCount
import kotlin.math.sqrt

/*
 * The dock: the app's main navigation, a glass dome rising from the bottom edge with a
 * round button for each destination. The current one is the white orb. Buttons follow
 * the curve of the dome, so the middle ones sit highest.
 *
 * On Home the dome is taller and carries one large glowing button in the middle for the
 * thing done most often (see DockAction).
 *
 * When suggestions are waiting for this phone's person to approve, the Home button
 * carries their number on every screen but Home itself, which shows them in full.
 */

/** The one large button in the middle of Home's dock. */
data class DockAction(val label: String, val icon: ImageVector, val onClick: () -> Unit)

/** The destinations shown for this move: those for parts that are switched off are left out. */
fun dockTabs(data: AppData): List<Tab> = TABS.filter { t ->
    when (t.route) {
        "boxes" -> data.uses(Part.BOXES)
        "moveday" -> data.uses(Part.MOVE_DAY) || data.uses(Part.RECORDS) || data.uses(Part.UNPACK)
        else -> true
    }
}

private val ORB = 48.dp

/** How far a count badge reaches above the orb it sits on. */
private val BADGE_RISE = 6.dp
private val ACTION_ORB = 84.dp

/** Where everything in the dock goes, for one screen width. All distances from the dock's top-left. */
internal class DockPlan(
    val width: Dp,
    val height: Dp,
    /** How much of the dock's height the content must stay clear of. Less than [height]: the dome's shoulders are low. */
    val reserve: Dp,
    /** Top of the dome at the middle of the screen. */
    val apex: Dp,
    /** Half-axes of the ellipse the dome's edge is part of. */
    val a: Dp,
    val b: Dp,
    /** Width of one destination's column, and for each destination its left edge and the top of its orb. */
    val column: Dp,
    val tabX: List<Dp>,
    val tabY: List<Dp>,
    /** Top of the large middle button, when there is one. */
    val actionTop: Dp?,
) {
    /** How far below the apex the dome's edge is at [x]. */
    fun drop(x: Dp): Dp {
        val t = ((x - width / 2) / a).coerceIn(-1f, 1f)
        return b * (1f - sqrt(1f - t * t))
    }
}

/** How many columns the dock has: with a middle button and an even number of destinations, one is left free for it. */
private fun dockSlots(tabs: Int, tall: Boolean): Int = if (tall && tabs % 2 == 0) tabs + 1 else tabs.coerceAtLeast(1)

/** The width of one destination's column in a dock [width] wide. */
internal fun dockColumn(width: Dp, tabs: Int, tall: Boolean): Dp = width / dockSlots(tabs, tall)

internal fun dockPlan(width: Dp, tabs: Int, tall: Boolean, labelHeight: Dp): DockPlan {
    val apex = if (tall) 20.dp else 12.dp
    val edgeDrop = if (tall) 56.dp else 38.dp
    val gap = if (tall) 16.dp else 12.dp
    val a = width / 2 + 44.dp
    val edge = (width / 2) / a
    val b = edgeDrop / (1f - sqrt(1f - edge * edge))
    // With a middle button and an even number of destinations, the middle column is left free for it.
    val slots = dockSlots(tabs, tall)
    val column = width / slots
    val used = (0 until slots).filter { !(tall && tabs % 2 == 0 && it == slots / 2) }
    fun dropAt(x: Dp): Dp {
        val t = ((x - width / 2) / a).coerceIn(-1f, 1f)
        return b * (1f - sqrt(1f - t * t))
    }
    val tabX = used.map { column * it }
    val tabY = used.map { apex + dropAt(column * it + column / 2) + gap }
    val tabBottom = (tabY.maxOrNull() ?: apex) + ORB + 3.dp + labelHeight
    val middleTab = tall && tabs % 2 == 1
    val actionTop = when {
        !tall -> null
        // Below the middle destination and its label, clear of the ring round the button.
        middleTab -> apex + gap + ORB + 3.dp + labelHeight + 18.dp
        else -> apex + 22.dp
    }
    val actionBottom = if (actionTop == null) 0.dp else actionTop + ACTION_ORB + 12.dp + labelHeight + 2.dp
    val height = maxOf(tabBottom, actionBottom) + 8.dp
    val reserve = height - apex - edgeDrop * 0.3f
    return DockPlan(width, height, reserve, apex, a, b, column, tabX, tabY, actionTop)
}

/** The labels under the dock's buttons: 11 sp on one line, as at the normal text size. */
private val LABEL = TextStyle(fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 0.3.sp)

/** The dock's labels at a large text size ([rememberDockLabels]): their size, and the [height] the dock keeps for them. */
internal class DockLabels(val fontSize: TextUnit, val lineHeight: TextUnit, val height: Dp)

/**
 * How the dock's labels are written when the text is larger than normal and one of them would be cut at the edge of its
 * [column] (at the largest sizes: "Move dc"), each measured as it is written ([current]'s label is the bolder): on up to
 * two lines, broken between words, and smaller only as far as the longest word needs to fit. Null at the normal text
 * size, and wherever every label fits as it is: then they are written as they always were.
 */
@Composable
internal fun rememberDockLabels(tabs: List<Tab>, column: Dp, current: String): DockLabels? {
    val density = LocalDensity.current
    // At the normal size and smaller, nothing is measured: the labels are as they always were.
    if (density.fontScale <= 1f) return null
    val measurer = rememberTextMeasurer()
    val style = LocalTextStyle.current.merge(LABEL)
    return remember(tabs, column, current, density, style) {
        fun styleOf(t: Tab) = style.copy(fontWeight = if (t.route == current) FontWeight.SemiBold else FontWeight.Medium)
        fun width(text: String, s: TextStyle) = measurer.measure(text, s, softWrap = false, maxLines = 1, density = density).size.width
        // A label is cut once its letters reach past the column; the last 2 points of its measured width are only the
        // letter spacing after its last letter and the letters' own margins, which nobody sees go.
        val columnPx = with(density) { (column + 2.dp).toPx() }
        if (tabs.all { width(it.label, styleOf(it)) <= columnPx }) return@remember null
        // A little air between neighbouring labels.
        val room = with(density) { (column - 4.dp).toPx() }
        val widestWord = tabs.flatMap { t -> t.label.split(' ').map { width(it, styleOf(t)) } }.max().coerceAtLeast(1)
        val k = minOf(1f, room / widestWord)
        val lines = tabs.maxOf {
            val smaller = styleOf(it).copy(fontSize = LABEL.fontSize * k, lineHeight = LABEL.lineHeight * k)
            measurer.measure(it.label, smaller, maxLines = 2, constraints = Constraints(maxWidth = room.toInt()), density = density).lineCount
        }
        val lineHeight = LABEL.lineHeight * k
        DockLabels(LABEL.fontSize * k, lineHeight, with(density) { lineHeight.toDp() * lines + 1.dp })
    }
}

@Composable
internal fun Dock(current: String, tabs: List<Tab>, plan: DockPlan, action: DockAction?, labels: DockLabels?, modifier: Modifier = Modifier) {
    val nav = LocalNav.current
    // Suggestions waiting for this person to approve: counted on the Home button, where the strip to review them is.
    val data = appData()
    val waiting = if (data.can(deviceInfo(), Action.APPROVE)) data.waitingCount() else 0
    Box(
        modifier.fillMaxWidth().drawBehind { drawDome(plan, glowStrength = if (action != null) 0.55f else 0.40f) }.navigationBarsPadding().height(plan.height),
    ) {
        tabs.forEachIndexed { i, t ->
            if (i >= plan.tabX.size) return@forEachIndexed
            val selected = t.route == current
            val badge = if (t.route == "home" && waiting > 0 && nav.currentDestination?.route != "home") "$waiting" else null
            Column(
                // The button starts a little above its orb, so the count on the Home orb is inside what is tapped.
                Modifier.offset(x = plan.tabX[i], y = plan.tabY[i] - BADGE_RISE).width(plan.column)
                    .selectable(
                        selected = selected,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(bounded = false, radius = 40.dp),
                        role = Role.Tab,
                        onClick = {
                            // A screen that sits on top of a destination (Rooms on Home) is left behind
                            // first, not remembered: the Home button always shows Home.
                            if (TABS.none { it.route == nav.currentDestination?.route }) nav.popBackStack()
                            if (nav.currentDestination?.route != t.route) nav.goTab(t.route)
                        },
                    )
                    .then(if (badge != null) Modifier.semantics { stateDescription = "$badge waiting for approval" } else Modifier)
                    .padding(top = BADGE_RISE),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                GlassOrb(t.icon, null, kind = if (selected) OrbKind.Selected else OrbKind.Plain, size = ORB, badge = badge)
                Spacer(Modifier.height(3.dp))
                if (labels == null) Text(
                    t.label,
                    fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 0.3.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    color = Night.Text, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip, textAlign = TextAlign.Center,
                ) else Text(
                    // The largest text sizes: up to two lines, no wider than the column ([rememberDockLabels]).
                    t.label,
                    fontSize = labels.fontSize, lineHeight = labels.lineHeight, letterSpacing = LABEL.letterSpacing,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    color = Night.Text, maxLines = 2, overflow = TextOverflow.Clip, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 2.dp),
                )
            }
        }
        if (action != null && plan.actionTop != null) {
            Column(
                Modifier.align(Alignment.TopCenter).offset(y = plan.actionTop).widthIn(max = plan.width - 32.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(bounded = false, radius = 56.dp),
                        role = Role.Button, onClick = action.onClick,
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.size(ACTION_ORB).drawBehind { drawOrb(OrbKind.Primary) }, contentAlignment = Alignment.Center) {
                    Icon(action.icon, contentDescription = null, tint = Night.OnMint, modifier = Modifier.size(38.dp))
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    action.label, style = MaterialTheme.typography.labelLarge, fontSize = 13.sp, lineHeight = 16.sp,
                    fontWeight = FontWeight.Medium, color = Night.Text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    // The mint light is brightest right here: a quiet dark pill keeps the words readable on it.
                    modifier = Modifier.padding(horizontal = 10.dp).drawBehind {
                        val grow = 10.dp.toPx()
                        val rise = 2.dp.toPx()
                        drawRoundRect(
                            Color(0xFF052A24).copy(alpha = 0.55f), topLeft = Offset(-grow, -rise),
                            size = Size(size.width + 2 * grow, size.height + 2 * rise), cornerRadius = CornerRadius(size.height),
                        )
                    },
                )
            }
        }
    }
}

/** The glass dome: a dark body so nothing shows through, mint light rising inside it, a bright rim. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDome(plan: DockPlan, glowStrength: Float) {
    val w = size.width
    val h = size.height
    val apex = plan.apex.toPx()
    fun y(x: Float) = apex + plan.drop(x.toDp()).toPx()
    val step = 6.dp.toPx()
    val curve = Path().apply {
        moveTo(0f, y(0f))
        var x = step
        while (x < w) {
            lineTo(x, y(x))
            x += step
        }
        lineTo(w, y(w))
    }
    val dome = Path().apply {
        addPath(curve)
        lineTo(w, h)
        lineTo(0f, h)
        close()
    }
    // A shadow thrown upwards onto the page.
    clipPath(dome, ClipOp.Difference) {
        val layers = 6
        for (i in layers downTo 1) {
            translate(top = -3.dp.toPx() * i) { drawPath(dome, Night.Shadow.copy(alpha = 0.07f)) }
        }
    }
    drawPath(dome, Brush.verticalGradient(0f to Color(0xFF15253A), 1f to Color(0xFF0B1826), startY = apex, endY = h))
    clipPath(dome) {
        glow(Night.GlowMint, Offset(w / 2f, h * 1.04f), w * 0.9f, (h - apex) * 0.95f, glowStrength)
        drawRect(
            Brush.verticalGradient(0f to Color.White.copy(alpha = 0.20f), 0.26f to Color.White.copy(alpha = 0.07f), 1f to Color.White.copy(alpha = 0.02f), startY = apex, endY = h),
        )
        // A second, fainter line inside the rim, as on the edge of thick glass.
        translate(top = 11.dp.toPx()) { drawPath(curve, Color.White.copy(alpha = 0.13f), style = Stroke(1.dp.toPx())) }
        translate(top = 1.5.dp.toPx()) { drawPath(curve, Color.White.copy(alpha = 0.34f), style = Stroke(2.dp.toPx())) }
    }
    drawPath(curve, Color.White.copy(alpha = 0.42f), style = Stroke(1.dp.toPx()))
}
