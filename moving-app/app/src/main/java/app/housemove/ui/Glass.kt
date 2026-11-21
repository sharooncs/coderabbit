package app.housemove.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Glass: what every panel, pill, chip and round button of the look is made of.
 *
 * There is no blur behind it (that needs a library or a recent Android). Glass here is
 * four things drawn with ordinary gradients, so it looks the same on every phone:
 *
 *   tint     a diagonal white gradient, strongest at the top-left corner
 *   rim      a 1dp border that is brightest along the top-left edge
 *   shadow   a soft dark halo below, drawn outside the shape so it never dulls the tint
 *   sheen    on big panels only, a faint diagonal band of light
 *
 *   Modifier.glass    turns anything into a glass surface
 *   GlassSurface      a glass box
 *   GlassCard         a glass panel laid out as a column; a drop-in for Material's Card
 *   GlassPill         a fully round glass row: bars, big buttons, tags
 *   GlassChip         a small pill with a word or two: states and filters
 *   GlassOrb          a round glass button: plain, selected (white) or primary (glowing mint)
 *   GlassTag          an orb with two lines of text: a callout over a drawing
 */

/** What a glass surface is tinted with. Plain for almost everything; the others say something. */
enum class GlassTone {
    /** White-tinted glass. */
    Plain,
    /** Selected, done, or the thing to do next. */
    Mint,
    /** In progress, or worth a look. */
    Amber,
    /** Wrong, late or damaged. */
    Red,
    /** Solid white glass with dark text: the selected pill or orb. */
    White,
}

/** The text colour that goes on a tone. */
fun GlassTone.content(): Color = if (this == GlassTone.White) Night.OnWhite else Night.Text

/** The colour for a tone's accents: its icon, its small heading. */
fun GlassTone.accent(): Color = when (this) {
    GlassTone.Plain -> Night.Mint
    GlassTone.Mint -> Night.MintPale
    GlassTone.Amber -> Night.AmberPale
    GlassTone.Red -> Night.RedPale
    GlassTone.White -> Night.OnWhite
}

private fun GlassTone.fill(size: Size): Brush {
    val end = Offset(size.width, size.height)
    fun tint(c: Color, a0: Float, a1: Float, a2: Float) = Brush.linearGradient(
        0f to c.copy(alpha = a0), 0.45f to c.copy(alpha = a1), 1f to c.copy(alpha = a2), start = Offset.Zero, end = end,
    )
    return when (this) {
        GlassTone.Plain -> Brush.linearGradient(
            0f to Night.GlassHi, 0.40f to Night.GlassMid, 0.65f to Night.GlassLow, 1f to Night.GlassEnd,
            start = Offset.Zero, end = end,
        )
        GlassTone.Mint -> tint(Night.Mint, 0.30f, 0.15f, 0.21f)
        GlassTone.Amber -> tint(Night.AmberDeep, 0.30f, 0.15f, 0.21f)
        GlassTone.Red -> tint(Night.RedDeep, 0.34f, 0.18f, 0.24f)
        GlassTone.White -> Brush.linearGradient(
            0f to Color.White, 0.5f to Color(0xFFE6EEF5), 1f to Color(0xFFB4C3D3), start = Offset.Zero, end = end,
        )
    }
}

private fun GlassTone.rim(size: Size): Brush {
    val c = when (this) {
        GlassTone.Plain, GlassTone.White -> Color.White
        GlassTone.Mint -> Night.MintLight
        GlassTone.Amber -> Night.Amber
        GlassTone.Red -> Night.Red
    }
    val k = if (this == GlassTone.Plain) 1f else 1.25f
    return Brush.linearGradient(
        0f to c.copy(alpha = (0.52f * k).coerceAtMost(1f)), 0.30f to c.copy(alpha = 0.24f * k),
        0.70f to c.copy(alpha = 0.13f * k), 1f to c.copy(alpha = 0.26f * k),
        start = Offset.Zero, end = Offset(size.width, size.height),
    )
}

/**
 * A soft shadow below a rounded shape, built from widening layers so it needs no blur
 * filter. Nothing is drawn inside the shape: translucent glass must not be darkened by
 * its own shadow.
 */
internal fun DrawScope.softShadow(outline: Outline, radius: Float, dy: Float = radius * 0.5f, strength: Float = 0.42f, color: Color = Night.Shadow) {
    if (radius <= 0f) return
    val rr = when (outline) {
        is Outline.Rounded -> outline.roundRect
        is Outline.Rectangle -> RoundRect(outline.rect)
        else -> return
    }
    val corner = rr.topLeftCornerRadius.x
    val steps = 7
    // Per-layer alpha so the layers together reach [strength] next to the shape.
    val a = 1f - Math.pow(1.0 - strength, 1.0 / steps).toFloat()
    clipPath(Path().apply { addRoundRect(rr) }, ClipOp.Difference) {
        for (i in steps downTo 1) {
            val grow = radius * i / steps
            drawRoundRect(
                color.copy(alpha = a),
                topLeft = Offset(rr.left - grow, rr.top - grow * 0.6f + dy),
                size = Size(rr.width + 2 * grow, rr.height + grow * 1.6f),
                cornerRadius = CornerRadius(corner + grow),
            )
        }
    }
}

/**
 * Makes this a glass surface of the given [shape]: shadow, tint, optional [sheen] and rim,
 * and clips what is inside to the shape. Put it before `clickable` so ripples are clipped.
 */
fun Modifier.glass(
    shape: Shape = RoundedCornerShape(24.dp),
    tone: GlassTone = GlassTone.Plain,
    sheen: Boolean = false,
    shadow: Dp = 8.dp,
): Modifier = this
    .drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val fill = tone.fill(size)
        val rim = tone.rim(size)
        // The band of light: 112 degrees, clear until 28 %, brightest at 44 %, gone by 58 %.
        val band = if (!sheen) null else Brush.linearGradient(
            0.28f to Color.Transparent, 0.44f to Night.GlassSheen, 0.58f to Color.Transparent,
            start = Offset(0f, size.height * 0.2f), end = Offset(size.width, size.height * 0.8f),
        )
        val stroke = Stroke(1.dp.toPx())
        val shadowPx = shadow.toPx()
        onDrawBehind {
            softShadow(outline, shadowPx)
            drawOutline(outline, fill)
            if (band != null) drawOutline(outline, band)
            drawOutline(outline, rim, style = stroke)
        }
    }
    .clip(shape)

/** A glass box. [onClick] makes it a button. */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    tone: GlassTone = GlassTone.Plain,
    sheen: Boolean = false,
    shadow: Dp = 8.dp,
    onClick: (() -> Unit)? = null,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier.glass(shape, tone, sheen, shadow).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = contentAlignment,
    ) {
        CompositionLocalProvider(LocalContentColor provides tone.content()) { content() }
    }
}

/**
 * A glass panel whose content is a column. Like Material's Card it adds no padding of
 * its own, so it can replace one without anything inside moving.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    tone: GlassTone = GlassTone.Plain,
    onClick: (() -> Unit)? = null,
    shape: Shape = RoundedCornerShape(24.dp),
    sheen: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.glass(shape, tone, sheen).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        CompositionLocalProvider(LocalContentColor provides tone.content()) { content() }
    }
}

/** A fully round glass row: a bar across the screen, a wide button, a tag. At least 48dp tall when it can be tapped. */
@Composable
fun GlassPill(
    modifier: Modifier = Modifier,
    tone: GlassTone = GlassTone.Plain,
    onClick: (() -> Unit)? = null,
    sheen: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier
            .glass(CircleShape, tone, sheen)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick).heightIn(min = 48.dp) else Modifier)
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = horizontalArrangement,
    ) {
        CompositionLocalProvider(LocalContentColor provides tone.content()) { content() }
    }
}

/**
 * A small pill carrying a word or two. Without [onClick] it states something ("On track");
 * with it, it is a filter or a choice, and [selected] fills it with mint and adds a tick,
 * so the choice does not depend on colour alone.
 */
@Composable
fun GlassChip(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tone: GlassTone = GlassTone.Plain,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
) {
    val shown = if (selected) GlassTone.Mint else tone
    val fg = when (shown) {
        GlassTone.Plain -> Night.Text
        else -> shown.accent()
    }
    val tap = if (onClick == null) Modifier else Modifier
        .minimumInteractiveComponentSize()
        .clip(CircleShape)
        .clickable(role = Role.Checkbox, onClick = onClick)
        .semantics { this.selected = selected }
    Box(modifier.then(tap), contentAlignment = Alignment.Center) {
        Row(
            Modifier.glass(CircleShape, shown, shadow = 0.dp).heightIn(min = if (onClick != null) 34.dp else 24.dp)
                .padding(horizontal = if (onClick != null) 14.dp else 11.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (selected && onClick != null && icon == null && leading == null) {
                Icon(Icons.Default.Check, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
            }
            leading?.invoke()
            if (icon != null) Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
            Text(
                text, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = if (onClick != null) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

// ---- orbs --------------------------------------------------------------------------

/** The flavours of round button. */
enum class OrbKind {
    /** Clear glass with a highlight at the upper left. */
    Plain,
    /** White glass: the current place, or the person. */
    Selected,
    /** Glowing mint: the main thing to do. One per screen. */
    Primary,
    /** Warm amber: marks something in progress. Used for small markers, not for buttons. */
    Amber,
}

fun OrbKind.content(): Color = when (this) {
    OrbKind.Plain -> Night.Text
    OrbKind.Selected -> Night.OnWhite
    OrbKind.Primary -> Night.OnMint
    OrbKind.Amber -> Night.OnAmber
}

/**
 * Draws a convex glass ball filling the bounds. The light comes from the upper left.
 * [halo] is what the ball throws around itself (shadow or glow); [ring] is the wide
 * faint ring round a primary ball, for the one big button of a screen.
 */
internal fun DrawScope.drawOrb(kind: OrbKind, halo: Boolean = true, ring: Boolean = true) {
    val d = size.minDimension
    val r = d / 2f
    val c = Offset(size.width / 2f, size.height / 2f)
    val hot = Offset(c.x - r + d * 0.30f, c.y - r + d * 0.22f)
    val reach = d * 1.05f
    val ball = Path().apply { addOval(Rect(c.x - r, c.y - r, c.x + r, c.y + r)) }

    // What the ball throws around itself: a glow for the bright ones, a shadow for all.
    if (halo) clipPath(ball, ClipOp.Difference) {
        if (kind == OrbKind.Primary) {
            if (ring) {
                val rr = r + d * 0.115f
                drawCircle(Night.Mint.copy(alpha = 0.16f), rr, c)
                drawCircle(Color.White.copy(alpha = 0.12f), rr, c, style = Stroke(1.dp.toPx()))
            }
            val reachOut = if (ring) 2.0f else 1.5f
            val gc = Offset(c.x, c.y + if (ring) d * 0.16f else 0f)
            drawCircle(
                Brush.radialGradient(0f to Color(0xFF28DCAA).copy(alpha = 0.60f), 0.55f to Color(0xFF28DCAA).copy(alpha = 0.34f), 1f to Color.Transparent, center = gc, radius = r * reachOut),
                r * reachOut, gc,
            )
        } else {
            val sc = Offset(c.x, c.y + d * 0.15f)
            drawCircle(
                Brush.radialGradient(0f to Night.Shadow.copy(alpha = 0.55f), 0.62f to Night.Shadow.copy(alpha = 0.42f), 1f to Color.Transparent, center = sc, radius = r * 1.5f),
                r * 1.5f, sc,
            )
        }
        if (kind == OrbKind.Selected) {
            drawCircle(Brush.radialGradient(0f to Color.White.copy(alpha = 0.34f), 0.6f to Color.White.copy(alpha = 0.22f), 1f to Color.Transparent, center = c, radius = r * 1.45f), r * 1.45f, c)
        }
        if (kind == OrbKind.Amber) {
            drawCircle(Brush.radialGradient(0f to Night.Amber.copy(alpha = 0.5f), 0.6f to Night.Amber.copy(alpha = 0.3f), 1f to Color.Transparent, center = c, radius = r * 1.5f), r * 1.5f, c)
        }
    }

    val body = when (kind) {
        OrbKind.Plain -> Brush.radialGradient(
            0f to Color.White.copy(alpha = 0.46f), 0.42f to Color.White.copy(alpha = 0.12f),
            0.70f to Color.White.copy(alpha = 0.05f), 1f to Color.White.copy(alpha = 0.14f), center = hot, radius = reach,
        )
        OrbKind.Selected -> Brush.radialGradient(0f to Color.White, 0.48f to Color(0xFFE6EEF5), 1f to Color(0xFFB4C3D3), center = hot, radius = reach)
        OrbKind.Primary -> Brush.radialGradient(
            0f to Color(0xFFE6FFF7), 0.22f to Color(0xFF86F0CF), 0.55f to Night.MintMid, 1f to Night.MintDeep, center = hot, radius = reach,
        )
        OrbKind.Amber -> Brush.radialGradient(0f to Color(0xFFFFF3CF), 0.35f to Color(0xFFFFD27A), 1f to Night.AmberDeep, center = hot, radius = reach)
    }
    drawCircle(body, r, c)
    // The underside is in shade.
    val shade = when (kind) {
        OrbKind.Plain -> Color.White.copy(alpha = 0.05f)
        OrbKind.Selected -> Color(0xFF3C5064).copy(alpha = 0.30f)
        OrbKind.Primary -> Color(0xFF063C2C).copy(alpha = 0.42f)
        OrbKind.Amber -> Color(0xFF784600).copy(alpha = 0.32f)
    }
    drawCircle(Brush.verticalGradient(0.62f to Color.Transparent, 1f to shade, startY = c.y - r, endY = c.y + r), r, c)
    // Rim: brightest at the top, where the light catches it.
    val rimTop = when (kind) {
        OrbKind.Plain -> 0.62f
        OrbKind.Selected -> 0.9f
        OrbKind.Primary -> 0.8f
        OrbKind.Amber -> 0.85f
    }
    val rimRest = if (kind == OrbKind.Plain) 0.26f else 0.0f
    val w = 1.dp.toPx()
    drawCircle(
        Brush.verticalGradient(0f to Color.White.copy(alpha = rimTop), 0.5f to Color.White.copy(alpha = rimRest), 1f to Color.White.copy(alpha = rimRest), startY = c.y - r, endY = c.y + r),
        r - w / 2f, c, style = Stroke(w),
    )
}

/**
 * A round glass button with a picture. Give every one a [contentDescription]; there is no
 * label. Without [onClick] it is only decoration next to a number or a name.
 * Smaller than 48dp it still takes a 48dp touch target when it can be tapped.
 */
@Composable
fun GlassOrb(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    kind: OrbKind = OrbKind.Plain,
    size: Dp = 48.dp,
    badge: String? = null,
    onClick: (() -> Unit)? = null,
) {
    GlassOrb(modifier, kind, size, badge, onClick, contentDescription) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(size * 0.46f))
    }
}

/** A round glass button with anything in the middle: an initial, a number. */
@Composable
fun GlassOrb(
    modifier: Modifier = Modifier,
    kind: OrbKind = OrbKind.Plain,
    size: Dp = 48.dp,
    badge: String? = null,
    onClick: (() -> Unit)? = null,
    contentDescription: String? = null,
    content: @Composable () -> Unit,
) {
    // The ball draws its shadow and glow outside its own circle, so only the inside is clipped.
    val tap = if (onClick == null) Modifier else Modifier.clip(CircleShape).clickable(role = Role.Button, onClick = onClick)
    val described = if (contentDescription == null) Modifier else Modifier.semantics(mergeDescendants = onClick == null) {
        this.contentDescription = contentDescription
    }
    Box(modifier.then(if (onClick != null) Modifier.minimumInteractiveComponentSize() else Modifier), contentAlignment = Alignment.Center) {
        Box(Modifier.size(size).drawBehind { drawOrb(kind) }.then(tap).then(described), contentAlignment = Alignment.Center) {
            CompositionLocalProvider(LocalContentColor provides kind.content(), content = content)
        }
        if (badge != null) {
            Box(
                Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-4).dp).defaultMinSize(minWidth = 22.dp, minHeight = 22.dp)
                    .drawBehind { drawOrb(OrbKind.Primary, ring = false) }.padding(horizontal = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(badge, color = Night.OnMint, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
    }
}

/**
 * A callout: a small bright orb and two lines, in a glass pill. Used over the house
 * drawings to say what a glowing marker means ("Kitchen is next", "6 boxes left").
 */
@Composable
fun GlassTag(
    title: String,
    value: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    kind: OrbKind = OrbKind.Amber,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier.glass(CircleShape, shadow = 12.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(start = 6.dp, top = 6.dp, bottom = 6.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(36.dp).drawBehind { drawOrb(kind, ring = false) }, contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = kind.content(), modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f, fill = false)) {
            Text(title, color = Night.TextSoft, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(value, color = Night.Text, style = MaterialTheme.typography.titleSmall, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Dims a control that cannot be used right now. */
internal fun Modifier.enabledLook(enabled: Boolean): Modifier = if (enabled) this else alpha(0.45f)
