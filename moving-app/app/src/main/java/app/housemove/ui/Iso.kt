package app.housemove.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/*
 * The two model drawings of the look, drawn with Canvas so they scale to any width:
 *
 *   IsoHouse   the old home from outside at night: lit windows, boxes by the door
 *   IsoRooms   the same home with the roof off: one coloured floor per room,
 *              box stacks where packing has got to
 *
 * Both are isometric. Coordinates are those of the design drawings (design/look-d),
 * which are 390 and 358 units wide; the canvas is scaled to fit. Nothing here uses a
 * blur filter: glows are radial gradients, soft light is a few enlarged faint copies.
 */

// ---- a little geometry ---------------------------------------------------------------

/** A closed polygon in drawing units, with the box it fits in (gradients run across that). */
private class Poly(vararg val v: Float) {
    val path = Path().apply {
        moveTo(v[0], v[1])
        var i = 2
        while (i < v.size) {
            lineTo(v[i], v[i + 1])
            i += 2
        }
        close()
    }
    val left: Float
    val top: Float
    val right: Float
    val bottom: Float
    val cx: Float
    val cy: Float

    init {
        var l = Float.MAX_VALUE
        var t = Float.MAX_VALUE
        var r = -Float.MAX_VALUE
        var b = -Float.MAX_VALUE
        var sx = 0f
        var sy = 0f
        var i = 0
        while (i < v.size) {
            l = minOf(l, v[i]); r = maxOf(r, v[i]); t = minOf(t, v[i + 1]); b = maxOf(b, v[i + 1])
            sx += v[i]; sy += v[i + 1]
            i += 2
        }
        left = l; top = t; right = r; bottom = b
        cx = sx / (v.size / 2); cy = sy / (v.size / 2)
    }
}

private fun DrawScope.fill(p: Poly, c: Color) = drawPath(p.path, c)

/** Top-to-bottom gradient across the polygon. */
private fun DrawScope.fillDown(p: Poly, c0: Color, c1: Color) =
    drawPath(p.path, Brush.verticalGradient(listOf(c0, c1), startY = p.top, endY = p.bottom))

/** Corner-to-corner gradient across the polygon. */
private fun DrawScope.fillAcross(p: Poly, c0: Color, c1: Color) =
    drawPath(p.path, Brush.linearGradient(listOf(c0, c1), start = Offset(p.left, p.top), end = Offset(p.right, p.bottom)))

/** Soft light in the shape of the polygon: a few enlarged, faint copies stand in for a blur. */
private fun DrawScope.haze(p: Poly, c: Color, alpha: Float, spread: Float = 0.55f) {
    val layers = 5
    for (i in 0 until layers) {
        val k = 1f + spread * i / (layers - 1)
        scale(k, k, pivot = Offset(p.cx, p.cy)) { drawPath(p.path, c.copy(alpha = alpha / layers * 1.25f)) }
    }
}

private fun DrawScope.outline(p: Poly, c: Color, width: Float) = drawPath(p.path, c, style = Stroke(width))

private fun DrawScope.seg(x0: Float, y0: Float, x1: Float, y1: Float, c: Color, width: Float, cap: StrokeCap = StrokeCap.Butt) =
    drawLine(c, Offset(x0, y0), Offset(x1, y1), strokeWidth = width, cap = cap)

private val WALL_L0 = Color(0xFFF8FAFD)
private val WALL_L1 = Color(0xFFB9C6D4)
private val GLASS0 = Color(0xFFFFF3CC)
private val GLASS1 = Color(0xFFFFB84D)
private val FRAME = Color(0xFF56657A)
private val SLAB_L0 = Color(0xFF1A2836)
private val SLAB_L1 = Color(0xFF0A121C)
private val SLAB_R0 = Color(0xFF0F1924)
private val SLAB_R1 = Color(0xFF060B12)
private val DARK = Color(0xFF04090F)
private val WARM = Color(0xFFFFC864)

/** A lit window: warm light spilling round it, then the pane and its frame. */
private fun DrawScope.litWindow(p: Poly) {
    haze(p, WARM, 0.50f)
    fillDown(p, GLASS0, GLASS1)
    outline(p, FRAME, 1.5f)
}

/** A glowing marker: where to look. */
private fun DrawScope.marker(x: Float, y: Float, c: Color, r: Float = 6.5f) {
    val at = Offset(x, y)
    drawCircle(Brush.radialGradient(0f to c.copy(alpha = 0.62f), 0.45f to c.copy(alpha = 0.42f), 1f to c.copy(alpha = 0f), center = at, radius = r * 2.7f), r * 2.7f, at)
    drawCircle(c.copy(alpha = 0.40f), r, at)
    drawCircle(Color.White.copy(alpha = 0.95f), r, at, style = Stroke(1.5f))
    drawCircle(Color.White, r * 0.4f, at)
}

// ---- the house from outside -------------------------------------------------------------

/** Places on the house a marker can glow at. */
enum class HouseSpot(internal val x: Float, internal val y: Float) {
    ROOF(-24.2f, -28.3f),
    /** The window of the low extension at the front, next to the door. */
    EXTENSION(19.8f, 76.5f),
    FRONT_WINDOW(-55f, 22f),
    GABLE_WINDOW(88f, 50f),
    DOOR(-50.6f, 84f),
    /** The boxes standing outside. */
    BOXES(-92f, 58f),
}

/** A glowing marker on the house. Mint for done or fine, amber for "this is where work is going on". */
data class HouseMarker(val spot: HouseSpot, val amber: Boolean = false)

private object House {
    val towers = listOf(
        Poly(-209f, -16.5f, -165f, 5.5f, -165f, -71.3f, -209f, -93.3f) to 0.20f,
        Poly(-110f, -22f, -165f, 5.5f, -165f, -71.3f, -110f, -98.8f) to 0.11f,
        Poly(-154f, -120.8f, -110f, -98.8f, -165f, -71.3f, -209f, -93.3f) to 0.28f,
        Poly(99f, -27.5f, 143f, -5.5f, 143f, -87.1f, 99f, -109.1f) to 0.20f,
        Poly(143f, -5.5f, 187f, -27.5f, 187f, -109.1f, 143f, -87.1f) to 0.11f,
        Poly(143f, -131.1f, 187f, -109.1f, 143f, -87.1f, 99f, -109.1f) to 0.28f,
    )
    val towerLights = floatArrayOf(
        -198f, -40f, -176f, -29f, -198f, -58f, -176f, -47f, -198f, -76f, -176f, -65f,
        110f, -50f, 132f, -39f, 110f, -68f, 132f, -57f, 110f, -86f, 132f, -75f,
    )
    val slabL = Poly(-189.2f, 72.6f, -4.4f, 165f, -4.4f, 184.2f, -189.2f, 91.8f)
    val slabR = Poly(-4.4f, 165f, 184.8f, 70.4f, 184.8f, 89.6f, -4.4f, 184.2f)
    val ground = Poly(0f, -22f, 184.8f, 70.4f, -4.4f, 165f, -189.2f, 72.6f)
    val mat = Poly(-59.4f, 106.7f, -41.8f, 115.5f, -72.6f, 130.9f, -90.2f, 122.1f)
    val houseShadow = Poly(132f, 66f, -4.4f, 134.2f, 43.8f, 140.9f, 180.2f, 72.7f)
    val pools = listOf(
        Poly(-70.4f, 52.8f, -39.6f, 68.2f, -63.8f, 89.1f, -112.2f, 64.9f) to 0.26f,
        Poly(35.2f, 114.4f, 4.4f, 129.8f, 26.4f, 147.4f, 70.4f, 125.4f) to 0.32f,
        Poly(-30.8f, 121f, -13.2f, 129.8f, -33f, 146.3f, -63.8f, 130.9f) to 0.26f,
    )
    val wallL = Poly(-88f, 44f, 44f, 110f, 44f, 38f, -88f, -28f)
    val wallR = Poly(132f, 66f, 44f, 110f, 44f, 38f, 88f, -32f, 132f, -6f)
    val winFront = Poly(-70.4f, 28.8f, -39.6f, 44.2f, -39.6f, 15.4f, -70.4f, 0f)
    val winGable = Poly(105.6f, 55.2f, 70.4f, 72.8f, 70.4f, 44f, 105.6f, 26.4f)
    val roofFar = Poly(94.6f, -28.7f, 145.2f, 1.2f, 145.2f, 6f, 94.6f, -23.9f)
    val roof = Poly(-101.2f, -20.8f, 44f, 51.8f, 94.6f, -28.7f, -50.6f, -101.3f)
    val roofBand = Poly(-93.5f, -33f, 51.7f, 39.6f, 62.7f, 22.1f, -82.5f, -50.5f)
    val fasciaFront = Poly(-101.2f, -20.8f, 44f, 51.8f, 44f, 56.6f, -101.2f, -16f)
    val fasciaSide = Poly(44f, 51.8f, 94.6f, -28.7f, 94.6f, -23.9f, 44f, 56.6f)
    val chimL = Poly(26.4f, -12.2f, 39.6f, -5.6f, 39.6f, -35.6f, 26.4f, -42.2f)
    val chimR = Poly(52.8f, -26.6f, 39.6f, -5.6f, 39.6f, -35.6f, 52.8f, -42.2f)
    val chimTop = Poly(39.6f, -48.8f, 52.8f, -42.2f, 39.6f, -35.6f, 26.4f, -42.2f)
    val extL = Poly(-70.4f, 101.2f, -4.4f, 134.2f, -4.4f, 86.2f, -70.4f, 53.2f)
    val extR = Poly(44f, 110f, -4.4f, 134.2f, -4.4f, 86.2f, 44f, 62f)
    val extTop = Poly(-22f, 29f, 44f, 62f, -4.4f, 86.2f, -70.4f, 53.2f)
    val extFlat = Poly(-18.7f, 31.8f, 38.5f, 60.3f, -4.4f, 81.8f, -61.6f, 53.2f)
    val door = Poly(-59.4f, 106.7f, -41.8f, 115.5f, -41.8f, 78.3f, -59.4f, 69.5f)
    val winExtL = Poly(-30.8f, 101.8f, -13.2f, 110.6f, -13.2f, 91.4f, -30.8f, 82.6f)
    val winExtR = Poly(35.2f, 108.4f, 4.4f, 123.8f, 4.4f, 91.4f, 35.2f, 76f)

    // The boxes by the door: one, one in front of it, and a smaller one on top of the first.
    val shadowA = Poly(-114.4f, 77f, -96.8f, 85.8f, -81.4f, 88f, -63.8f, 79.2f, -81.4f, 70.4f, -96.8f, 68.2f)
    val shadowB = Poly(-101.2f, 92.4f, -83.6f, 101.2f, -68.2f, 103.4f, -50.6f, 94.6f, -68.2f, 85.8f, -83.6f, 83.6f)
    val boxA = Triple(
        Poly(-114.4f, 77f, -96.8f, 85.8f, -96.8f, 69f, -114.4f, 60.2f),
        Poly(-79.2f, 77f, -96.8f, 85.8f, -96.8f, 69f, -79.2f, 60.2f),
        Poly(-96.8f, 51.4f, -79.2f, 60.2f, -96.8f, 69f, -114.4f, 60.2f),
    )
    val boxTop = Triple(
        Poly(-111.1f, 60.8f, -96.8f, 67.9f, -96.8f, 53.5f, -111.1f, 46.4f),
        Poly(-82.5f, 60.8f, -96.8f, 67.9f, -96.8f, 53.5f, -82.5f, 46.4f),
        Poly(-96.8f, 39.2f, -82.5f, 46.4f, -96.8f, 53.5f, -111.1f, 46.4f),
    )
    val boxB = Triple(
        Poly(-101.2f, 92.4f, -83.6f, 101.2f, -83.6f, 84.4f, -101.2f, 75.6f),
        Poly(-66f, 92.4f, -83.6f, 101.2f, -83.6f, 84.4f, -66f, 75.6f),
        Poly(-83.6f, 66.8f, -66f, 75.6f, -83.6f, 84.4f, -101.2f, 75.6f),
    )
}

private fun DrawScope.kraftBox(faces: Triple<Poly, Poly, Poly>) {
    fillDown(faces.first, Night.KraftLeft0, Night.KraftLeft1)
    fillDown(faces.second, Night.KraftRight0, Night.KraftRight1)
    fillAcross(faces.third, Night.KraftTop0, Night.KraftTop1)
}

private fun DrawScope.bush(cx: Float, cy: Float, r: Float) {
    scale(1f, 0.32f, pivot = Offset(cx + 4f, cy + r * 0.8f)) { drawCircle(DARK.copy(alpha = 0.4f), r * 1.27f, Offset(cx + 4f, cy + r * 0.8f)) }
    val hot = Offset(cx - r * 0.3f, cy - r * 0.4f)
    drawCircle(Brush.radialGradient(listOf(Color(0xFF8CF0CD), Color(0xFF17604C)), center = hot, radius = r * 1.5f), r, Offset(cx, cy))
}

private fun DrawScope.drawHouse(boxes: Int, markers: List<HouseMarker>) {
    // Faint towers behind: the town the house stands in.
    for ((p, a) in House.towers) fill(p, Color(0xFF96B4DC).copy(alpha = a * 0.8f))
    var i = 0
    while (i < House.towerLights.size) {
        seg(House.towerLights[i], House.towerLights[i + 1], House.towerLights[i + 2], House.towerLights[i + 3], Color(0xFFFFD78C).copy(alpha = 0.40f), 3f)
        i += 4
    }
    // The plot: a mint glow under a dark slab.
    glow(Night.GlowMint, Offset(-2.2f, 196f), 258f, 66f, 0.46f)
    fillDown(House.slabL, SLAB_L0, SLAB_L1)
    fillDown(House.slabR, SLAB_R0, SLAB_R1)
    val g = House.ground
    val gc = Offset(g.left + (g.right - g.left) * 0.45f, (g.top + g.bottom) / 2f)
    drawPath(g.path, Color(0xFF15222F))
    clipPath(g.path) {
        // Lighter in the middle; the round gradient is squashed to lie flat on the ground.
        val r = (g.right - g.left) * 0.6f
        scale(1f, 0.5f, pivot = gc) {
            drawCircle(Brush.radialGradient(listOf(Color(0xFF31496A), Color(0xFF15222F)), center = gc, radius = r), r, gc)
        }
    }
    seg(-189.2f, 72.6f, -4.4f, 165f, Color.White.copy(alpha = 0.34f), 1.2f)
    seg(-4.4f, 165f, 184.8f, 70.4f, Color.White.copy(alpha = 0.34f), 1.2f)
    fill(House.mat, Color(0xFF2C4058))
    fill(House.houseShadow, DARK.copy(alpha = 0.42f))
    for ((p, a) in House.pools) haze(p, Color(0xFFFFCD6E), a, spread = 0.3f)

    // The main house.
    fillDown(House.wallL, WALL_L0, WALL_L1)
    fillDown(House.wallR, Color(0xFFA9B8C9), Color(0xFF70829A))
    litWindow(House.winFront)
    litWindow(House.winGable)
    seg(-55f, 36.5f, -55f, 7.7f, FRAME, 1.2f)
    seg(88f, 64f, 88f, 35.2f, FRAME, 1.2f)
    seg(-70.4f, 14.4f, -39.6f, 29.8f, FRAME, 1.2f)
    seg(105.6f, 40.8f, 70.4f, 58.4f, FRAME, 1.2f)
    fill(House.roofFar, Color(0xFF172230))
    fillAcross(House.roof, Color(0xFF41597A), Color(0xFF152030))
    fill(House.roofBand, Color.White.copy(alpha = 0.08f))
    val seam = Color.White.copy(alpha = 0.10f)
    seg(-75.9f, -61.1f, 69.3f, 11.5f, seam, 1.2f)
    seg(-64.9f, -2.7f, -14.3f, -83.2f, seam, 1.2f)
    seg(-28.6f, 15.5f, 22f, -65f, seam, 1.2f)
    seg(7.7f, 33.6f, 58.3f, -46.8f, seam, 1.2f)
    fill(House.fasciaFront, Color(0xFF0F1823))
    fill(House.fasciaSide, Color(0xFF22303F))
    seg(-50.6f, -101.3f, 94.6f, -28.7f, Color.White.copy(alpha = 0.35f), 1.2f)
    fillDown(House.chimL, WALL_L0, WALL_L1)
    fillDown(House.chimR, Color(0xFFA9B8C9), Color(0xFF70829A))
    fill(House.chimTop, Color(0xFFF6F8FB))

    // The low extension with the front door.
    fillDown(House.extL, WALL_L0, WALL_L1)
    fillDown(House.extR, Color(0xFFA9B8C9), Color(0xFF70829A))
    fill(House.extTop, Color(0xFFF1F4F8))
    fillAcross(House.extFlat, Color(0xFFC8D2DE), Color(0xFF97A8BB))
    fillDown(House.door, Color(0xFF3D5270), Color(0xFF1A2636))
    drawCircle(Color(0xFFFFD98A), 1.6f, Offset(-45.1f, 95.85f))
    litWindow(House.winExtL)
    litWindow(House.winExtR)
    seg(19.8f, 116.1f, 19.8f, 83.7f, FRAME, 1.2f)
    seg(-22f, 106.2f, -22f, 87f, FRAME, 1.2f)

    bush(-149.6f, 69.3f, 11f)
    bush(-2.2f, 144.5f, 12f)

    // Moving boxes waiting by the door.
    val n = boxes.coerceIn(0, 3)
    if (n >= 1) fill(House.shadowA, DARK.copy(alpha = 0.38f))
    if (n >= 2) fill(House.shadowB, DARK.copy(alpha = 0.38f))
    if (n >= 1) kraftBox(House.boxA)
    if (n >= 3) kraftBox(House.boxTop)
    if (n >= 2) kraftBox(House.boxB)
    if (n == 1 || n == 2) seg(-88f, 55.8f, -105.6f, 64.6f, Night.KraftTape, 2.4f)
    if (n >= 2) seg(-74.8f, 71.2f, -92.4f, 80f, Night.KraftTape, 2.4f)
    if (n >= 3) seg(-89.8f, 42.7f, -104.1f, 49.9f, Night.KraftTape, 2.4f)

    for (m in markers) marker(m.spot.x, m.spot.y, if (m.amber) Night.Amber else Night.Mint)
}

/**
 * The old home as a lit model house at night. [boxes] (0 to 3) is how many moving boxes
 * stand by the door; [markers] glow at places on it. Fills the width it is given and
 * keeps the proportions of the drawing (390 : 322).
 */
@Composable
fun IsoHouse(
    modifier: Modifier = Modifier,
    boxes: Int = 0,
    markers: List<HouseMarker> = emptyList(),
    contentDescription: String = "Your home as a lit model house at night" + when (boxes.coerceIn(0, 3)) {
        0 -> ""
        1 -> ", with a moving box by the door"
        else -> ", with moving boxes by the door"
    },
) {
    Canvas(
        modifier.fillMaxWidth().aspectRatio(390f / 322f).semantics {
            this.contentDescription = contentDescription
            role = Role.Image
        },
    ) {
        val s = size.width / 390f
        scale(s, s, pivot = Offset.Zero) {
            translate(195f, 112f) { drawHouse(boxes, markers) }
        }
    }
}

// ---- the rooms with the roof off -----------------------------------------------------------

/** How far packing has got in a room. Each state has its own floor colour and its own contents, so colour is never the only sign. */
enum class RoomState(val label: String) {
    /** Marked fully packed: teal floor, sealed stacks. */
    PACKED("packed"),
    /** Boxes started: amber floor, single boxes. */
    PACKING("being packed"),
    /** Nothing sealed yet: slate floor, the furniture still in place. */
    NOT_STARTED("not started"),
    /** No boxes belong to it: slate floor, empty. */
    NO_BOXES("no boxes"),
}

/** One room of the cutaway. [stacks] is how many piles of boxes to show (0 to 3; narrow rooms show fewer). */
data class IsoRoom(val id: String, val name: String, val state: RoomState, val stacks: Int = 0)

/** At most this many rooms are drawn; the rest are counted in a "+N more" note. */
const val ISO_MAX_ROOMS = 12

/** A room's patch of the floor grid, which is 8 wide (x) and 6 deep (y). */
internal class RoomCell(val room: IsoRoom, val x0: Float, val y0: Float, val x1: Float, val y1: Float) {
    val w get() = x1 - x0
    val d get() = y1 - y0
}

private const val GRID_W = 8f
private const val GRID_D = 6f
private const val MID = 3f

/**
 * Two rows behind the two back walls: the first half of the rooms along the back, the
 * rest along the front. A single room has the whole floor. Where both rows have the
 * same few rooms, the front row's walls are shifted so the plan does not look like a grid.
 */
internal fun layoutRooms(rooms: List<IsoRoom>): List<RoomCell> {
    val shown = rooms.take(ISO_MAX_ROOMS)
    if (shown.isEmpty()) return emptyList()
    if (shown.size == 1) return listOf(RoomCell(shown[0], 0f, 0f, GRID_W, GRID_D))
    val back = (shown.size + 1) / 2
    val front = shown.size - back
    val cells = ArrayList<RoomCell>()
    fun row(list: List<IsoRoom>, y0: Float, y1: Float, shift: Float) {
        val k = list.size
        val step = GRID_W / k
        list.forEachIndexed { i, r ->
            val a = if (i == 0) 0f else i * step + shift
            val b = if (i == k - 1) GRID_W else (i + 1) * step + shift
            cells += RoomCell(r, a, y0, b, y1)
        }
    }
    row(shown.subList(0, back), 0f, MID, 0f)
    row(shown.subList(back, shown.size), MID, GRID_D, if (front == back && front in 2..3) GRID_W / front * 0.25f else 0f)
    return cells
}

// The projection of the design: x' = (x - y) * 24, y' = (x + y) * 12 - z * 26.
private fun px(x: Float, y: Float) = (x - y) * 24f
private fun py(x: Float, y: Float, z: Float = 0f) = (x + y) * 12f - z * 26f

/** A flat quad on the floor (or at height [z]). */
private fun floorQuad(x0: Float, y0: Float, x1: Float, y1: Float, z: Float = 0f) =
    Poly(px(x0, y0), py(x0, y0, z), px(x1, y0), py(x1, y0, z), px(x1, y1), py(x1, y1, z), px(x0, y1), py(x0, y1, z))

/** An upright quad along x at depth [y]: the face that looks towards the front left. */
private fun faceY(x0: Float, x1: Float, y: Float, z0: Float, z1: Float) =
    Poly(px(x0, y), py(x0, y, z0), px(x1, y), py(x1, y, z0), px(x1, y), py(x1, y, z1), px(x0, y), py(x0, y, z1))

/** An upright quad along y at [x]: the face that looks towards the front right. */
private fun faceX(x: Float, y0: Float, y1: Float, z0: Float, z1: Float) =
    Poly(px(x, y0), py(x, y0, z0), px(x, y1), py(x, y1, z0), px(x, y1), py(x, y1, z1), px(x, y0), py(x, y0, z1))

/** The shadow of something standing on [x0..x1] x [y0..y1]: light comes from the front left. */
private fun castShadow(x0: Float, y0: Float, x1: Float, y1: Float): Poly {
    val sx = 0.45f
    val sy = -0.25f
    return Poly(
        px(x0, y1), py(x0, y1), px(x1, y1), py(x1, y1), px(x1 + sx, y1 + sy), py(x1 + sx, y1 + sy),
        px(x1 + sx, y0 + sy), py(x1 + sx, y0 + sy), px(x0 + sx, y0 + sy), py(x0 + sx, y0 + sy), px(x0, y0), py(x0, y0),
    )
}

private const val BOX = 0.8f

/** A block with its three visible faces. */
private fun DrawScope.block(x0: Float, y0: Float, x1: Float, y1: Float, z0: Float, z1: Float, left: Pair<Color, Color>, right: Pair<Color, Color>, top: Pair<Color, Color>) {
    fillDown(faceY(x0, x1, y1, z0, z1), left.first, left.second)
    fillDown(faceX(x1, y0, y1, z0, z1), right.first, right.second)
    fillAcross(floorQuad(x0, y0, x1, y1, z1), top.first, top.second)
}

private val KRAFT_L = Night.KraftLeft0 to Night.KraftLeft1
private val KRAFT_R = Night.KraftRight0 to Night.KraftRight1
private val KRAFT_T = Night.KraftTop0 to Night.KraftTop1

/** Where the piles of boxes stand in a room, back to front, as (centre x, centre y, boxes high). */
private fun stacksOf(cell: RoomCell): List<Triple<Float, Float, Int>> {
    val want = cell.room.stacks.coerceIn(0, 3)
    if (want == 0) return emptyList()
    val tall = if (cell.room.state == RoomState.PACKED) 2 else 1
    val spots = when {
        // A wide room: the arrangement of the design drawing.
        cell.w >= 2.6f -> listOf(Triple(0.40f, 0.43f, tall), Triple(0.675f, 0.60f, 1), Triple(0.28f, 0.80f, 1))
        // A narrow one: a single file down the middle.
        else -> listOf(Triple(0.5f, 0.36f, tall), Triple(0.5f, 0.74f, 1))
    }
    return spots.take(want).map { (fx, fy, h) ->
        val cx = (cell.x0 + fx * cell.w).coerceIn(cell.x0 + 0.52f, cell.x1 - 0.52f)
        val cy = (cell.y0 + fy * cell.d).coerceIn(cell.y0 + 0.55f, cell.y1 - 0.5f)
        Triple(cx, cy, h)
    }.sortedBy { it.first + it.second }
}

/** The things in one room: its boxes, or its furniture if packing has not begun. Returns where a marker for it goes. */
private fun DrawScope.roomContents(cell: RoomCell, tapes: MutableList<FloatArray>): Offset {
    val stacks = stacksOf(cell)
    if (stacks.isEmpty() && cell.room.state == RoomState.NOT_STARTED) {
        // A bed with a blanket and a pillow: still lived in.
        val bw = minOf(1.9f, cell.w - 0.5f)
        val bd = minOf(1.6f, cell.d - 0.9f)
        if (bw >= 0.7f && bd >= 0.8f) {
            val x0 = cell.x0 + minOf(0.6f, (cell.w - bw) / 2f)
            val y0 = cell.y0 + (cell.d - bd) * 0.5f
            val x1 = x0 + bw
            val y1 = y0 + bd
            fill(castShadow(x0, y0, x1, y1), DARK.copy(alpha = 0.38f))
            val grey = Color(0xFFC5CFDA)
            block(x0, y0, x1, y1, 0f, 0.45f, grey to grey, Color(0xFF9AA9BA) to Color(0xFF9AA9BA), Color(0xFFEEF2F7) to Color(0xFFEEF2F7))
            fillAcross(floorQuad(x0 + bw * 0.34f, y0, x1, y1, 0.45f), Color(0xFF7FA2D8), Color(0xFF456494))
            val pw = minOf(0.4f, bw * 0.24f)
            block(
                x0 + 0.12f, y0 + bd * 0.19f, x0 + 0.12f + pw, y1 - bd * 0.19f, 0.45f, 0.59f,
                Color(0xFFD5DDE6) to Color(0xFFD5DDE6), Color(0xFFB4C0CD) to Color(0xFFB4C0CD), Color.White to Color.White,
            )
            return Offset(px((x0 + x1) / 2f, (y0 + y1) / 2f), py((x0 + x1) / 2f, (y0 + y1) / 2f, 0.45f))
        }
    }
    var at = Offset(px((cell.x0 + cell.x1) / 2f, (cell.y0 + cell.y1) / 2f), py((cell.x0 + cell.x1) / 2f, (cell.y0 + cell.y1) / 2f))
    val h = BOX / 2f
    for ((cx, cy, _) in stacks) fill(castShadow(cx - h, cy - h, cx + h, cy + h), DARK.copy(alpha = 0.38f))
    for ((cx, cy, high) in stacks) {
        for (level in 0 until high) {
            block(cx - h, cy - h, cx + h, cy + h, level * BOX, (level + 1) * BOX, KRAFT_L, KRAFT_R, KRAFT_T)
        }
        val z = high * BOX
        tapes += floatArrayOf(px(cx, cy - h), py(cx, cy - h, z), px(cx, cy + h), py(cx, cy + h, z))
        at = Offset(px(cx, cy), py(cx, cy, z))
    }
    return at
}

/** A low wall between rooms along y at [x], from [y0] to [y1]. */
private fun DrawScope.partitionX(x: Float, y0: Float, y1: Float) {
    val t = 0.058f
    fillDown(faceY(x - t, x + t, y1, 0f, 0.5f), WALL_L0, WALL_L1)
    fillDown(faceX(x + t, y0, y1, 0f, 0.5f), Color(0xFFB4C2D1), Color(0xFF7C8DA3))
    fill(floorQuad(x - t, y0, x + t, y1, 0.5f), Color.White)
}

/** A low wall between the two rows along x at [y]. */
private fun DrawScope.partitionY(y: Float, x0: Float, x1: Float) {
    val t = 0.058f
    fillDown(faceY(x0, x1, y + t, 0f, 0.5f), WALL_L0, WALL_L1)
    fillDown(faceX(x1, y - t, y + t, 0f, 0.5f), Color(0xFFB4C2D1), Color(0xFF7C8DA3))
    fill(floorQuad(x0, y - t, x1, y + t, 0.5f), Color.White)
}

private fun floorColors(state: RoomState): Pair<Color, Color> = when (state) {
    RoomState.PACKED -> Color(0xFF45C4A4) to Color(0xFF1C7461)
    RoomState.PACKING -> Color(0xFFE6B25C) to Color(0xFFA36F2B)
    RoomState.NOT_STARTED -> Color(0xFF566D8A) to Color(0xFF303F53)
    RoomState.NO_BOXES -> Color(0xFF475A73) to Color(0xFF283546)
}

private fun DrawScope.drawRooms(cells: List<RoomCell>, selectedId: String?) {
    glow(Night.GlowMint, Offset(24f, 194f), 236f, 60f, 0.42f)
    fillDown(Poly(-144f, 72f, 48f, 168f, 48f, 188.8f, -144f, 92.8f), SLAB_L0, SLAB_L1)
    fillDown(Poly(48f, 168f, 192f, 96f, 192f, 116.8f, 48f, 188.8f), SLAB_R0, SLAB_R1)

    // Floors, coloured by how far each room has got.
    if (cells.isEmpty()) fillAcross(floorQuad(0f, 0f, GRID_W, GRID_D), Color(0xFF475A73), Color(0xFF283546))
    for (c in cells) {
        val (c0, c1) = floorColors(c.room.state)
        fillAcross(floorQuad(c.x0, c.y0, c.x1, c.y1), c0, c1)
    }
    for (y in listOf(1f, 2f, 4f, 5f)) seg(px(0f, y), py(0f, y), px(GRID_W, y), py(GRID_W, y), Color.White.copy(alpha = 0.07f), 1f)
    seg(px(0f, GRID_D), py(0f, GRID_D), px(GRID_W, GRID_D), py(GRID_W, GRID_D), Color.White.copy(alpha = 0.4f), 1.2f)
    seg(px(GRID_W, GRID_D), py(GRID_W, GRID_D), px(GRID_W, 0f), py(GRID_W, 0f), Color.White.copy(alpha = 0.4f), 1.2f)
    // Window light lying on the floor.
    val pool = Color(0xFFFFDC8C).copy(alpha = 0.26f)
    fill(Poly(px(5.2f, 0.15f), py(5.2f, 0.15f), px(6.8f, 0.15f), py(6.8f, 0.15f), px(7.4f, 2f), py(7.4f, 2f), px(5.8f, 2f), py(5.8f, 2f)), pool)
    fill(Poly(px(0.15f, 1f), py(0.15f, 1f), px(0.15f, 2.2f), py(0.15f, 2.2f), px(1.9f, 2.7f), py(1.9f, 2.7f), px(1.9f, 1.5f), py(1.9f, 1.5f)), pool)

    // The two back walls, with their windows and the door.
    val wallH = 2.4f
    fillDown(faceY(0f, GRID_W, 0f, 0f, wallH), Color(0xFFB4C2D1), Color(0xFF7C8DA3))
    fillDown(faceX(0f, 0f, GRID_D, 0f, wallH), WALL_L0, WALL_L1)
    val t = 0.25f
    fill(Poly(px(0f, 0f), py(0f, 0f, wallH), px(GRID_W, 0f), py(GRID_W, 0f, wallH), px(GRID_W, -t), py(GRID_W, -t, wallH), px(-t, -t), py(-t, -t, wallH)), Color.White)
    fill(Poly(px(0f, 0f), py(0f, 0f, wallH), px(0f, GRID_D), py(0f, GRID_D, wallH), px(-t, GRID_D), py(-t, GRID_D, wallH), px(-t, -t), py(-t, -t, wallH)), Color.White)
    fill(faceY(0f, GRID_W, 0f, 0f, 0.3f), Color(0xFF0A121C).copy(alpha = 0.20f))
    fill(faceX(0f, 0f, GRID_D, 0f, 0.3f), Color(0xFF0A121C).copy(alpha = 0.14f))
    litWindow(faceY(5.2f, 6.8f, 0f, 0.9f, 1.9f))
    litWindow(faceX(0f, 1f, 2.2f, 0.9f, 1.9f))
    seg(px(6f, 0f), py(6f, 0f, 0.9f), px(6f, 0f), py(6f, 0f, 1.9f), FRAME, 1.2f)
    seg(px(0f, 1.6f), py(0f, 1.6f, 0.9f), px(0f, 1.6f), py(0f, 1.6f, 1.9f), FRAME, 1.2f)
    fillDown(faceX(0f, 4f, 4.9f, 0f, 1.9f), Color(0xFF8D9BAB), Color(0xFF5C6B7D))

    // Back row, then the wall between the rows, then the front row: far things first.
    val tapes = ArrayList<FloatArray>()
    var mark: Pair<Offset, RoomState>? = null
    val single = cells.size == 1
    fun rowOf(y0: Float) = cells.filter { it.y0 == y0 }.sortedBy { it.x0 }
    for ((index, row) in listOf(rowOf(0f), if (single) emptyList() else rowOf(MID)).withIndex()) {
        if (index == 1 && row.isNotEmpty()) partitionY(MID, 0f, GRID_W)
        for (c in row) {
            val at = roomContents(c, tapes)
            if (c.room.id == selectedId) mark = at to c.room.state
            if (c.x1 < GRID_W) partitionX(c.x1, c.y0, c.y1)
        }
    }
    for (l in tapes) seg(l[0], l[1], l[2], l[3], Night.KraftTape, 2.4f)
    mark?.let { (at, state) ->
        marker(at.x, at.y, when (state) {
            RoomState.PACKED -> Night.Mint
            RoomState.PACKING -> Night.Amber
            else -> Color(0xFFBFD2EA)
        }, r = 7f)
    }
}

/** Which room is under a point of the drawing (in its own units), or null. */
private fun roomAt(cells: List<RoomCell>, u: Float, v: Float): RoomCell? {
    val a = u / 24f
    val b = v / 12f
    val x = (a + b) / 2f
    val y = (b - a) / 2f
    return cells.firstOrNull { x >= it.x0 && x < it.x1 && y >= it.y0 && y < it.y1 }
}

/**
 * The home with its roof off: every room a floor coloured by how far packing has got
 * (teal packed, amber being packed, slate not started), with box stacks, or the
 * furniture still in place. Tapping a floor reports that room through [onRoom]; the
 * room with [selectedId] gets a glowing marker.
 *
 * Works for one room up to [ISO_MAX_ROOMS]; beyond that the rest are counted in a note.
 * The floors are small targets, so always offer the rooms as a list of buttons as well.
 */
@Composable
fun IsoRooms(
    rooms: List<IsoRoom>,
    modifier: Modifier = Modifier,
    selectedId: String? = null,
    onRoom: (IsoRoom) -> Unit = {},
    contentDescription: String = if (rooms.isEmpty()) "The home as a model with the roof off. No rooms yet."
    else "The home as a lit model with the roof off: " + rooms.joinToString(", ") { "${it.name} ${it.state.label}" },
) {
    val cells = remember(rooms) { layoutRooms(rooms) }
    val tap by rememberUpdatedState(onRoom)
    Box(modifier.fillMaxWidth()) {
        Canvas(
            Modifier.fillMaxWidth().aspectRatio(358f / 284f)
                .semantics {
                    this.contentDescription = contentDescription
                    role = Role.Image
                }
                .pointerInput(cells) {
                    detectTapGestures { pos ->
                        val s = size.width / 358f
                        roomAt(cells, pos.x / s - 155f, pos.y / s - 74f)?.let { tap(it.room) }
                    }
                },
        ) {
            val s = size.width / 358f
            scale(s, s, pivot = Offset.Zero) {
                translate(155f, 74f) { drawRooms(cells, selectedId) }
            }
        }
        if (rooms.size > ISO_MAX_ROOMS) {
            GlassChip("+${rooms.size - ISO_MAX_ROOMS} more", Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 4.dp))
        }
    }
}
