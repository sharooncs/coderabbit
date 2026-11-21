package app.housemove.ui

import android.app.Activity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/*
 * The "Night house" look: a deep navy ground with soft coloured glows, mint as the
 * accent, and translucent glass for every panel, chip and button.
 *
 * There is one colour scheme. The app is always dark, whatever the phone is set to,
 * because the look depends on light things glowing against the night.
 *
 * Material roles carry the look into stock components (buttons, switches, dialogs),
 * so a screen that uses plain Material widgets is already readable. [Night] holds the
 * colours Material has no role for. The glass itself lives in Glass.kt.
 */

/** The colours of the look that Material has no role for. */
object Night {
    // The ground: a vertical gradient, top to bottom.
    val Ground0 = Color(0xFF0A1320)
    val Ground1 = Color(0xFF101D2E)
    val Ground2 = Color(0xFF0B1725)

    /** Solid navy for things that must hide what is behind them (dialogs, menus, the dock). */
    val Panel = Color(0xFF14233A)
    val PanelHigh = Color(0xFF192B45)
    /** The same, tinted mint: a solid bar that says "done". */
    val PanelMint = Color(0xFF133B3D)

    // Text.
    val Text = Color(0xFFF2F6FA)
    val TextDim = Color(0xFFC3CFDB)
    val TextSoft = Color(0xFFD5DEE7)
    /** Text and icons on a white orb or pill. */
    val OnWhite = Color(0xFF0E1826)

    // Mint: the accent, and "done".
    val Mint = Color(0xFF62E3BB)
    val MintLight = Color(0xFF8FF0D2)
    val MintPale = Color(0xFFA6F7DE)
    val MintMid = Color(0xFF49D1A8)
    val MintDeep = Color(0xFF1C9778)
    val OnMint = Color(0xFF053326)

    // Amber: "in progress", and anything to look at.
    val Amber = Color(0xFFFFC861)
    val AmberPale = Color(0xFFFFE0A3)
    val AmberDeep = Color(0xFFF2A93B)
    val OnAmber = Color(0xFF2B1C00)

    /** Slate: "not started". A fill colour, too dim for text. */
    val Slate = Color(0xFF5B6F87)
    val SlateDeep = Color(0xFF303F53)

    /** Error red that keeps 4.5:1 on the ground and on glass. */
    val Red = Color(0xFFFF9E94)
    val RedPale = Color(0xFFFFD6D0)
    val RedDeep = Color(0xFFE5645A)
    /** A solid red that white text can sit on, for a banner that must be seen across a room. */
    val RedSolid = Color(0xFFC93A30)

    // Glass: white tints over the ground. See Modifier.glass.
    // The mock-up starts at 22 %; without a blur behind it that costs too much contrast
    // for secondary text over the glows, so the fill starts at 17 %.
    val GlassHi = Color(0x2BFFFFFF)   // 17 % at the top-left corner
    val GlassMid = Color(0x12FFFFFF)  // 7 %
    val GlassLow = Color(0x08FFFFFF)  // 3 %
    val GlassEnd = Color(0x1AFFFFFF)  // 10 % at the bottom-right corner
    val GlassRim = Color(0x3DFFFFFF)  // the 1dp border, 24 %
    val GlassRimHi = Color(0x80FFFFFF) // the highlight along the top-left edge
    val GlassSheen = Color(0x21FFFFFF) // the diagonal band on big panels, 13 %
    val Hairline = Color(0x29FFFFFF)  // dividers and quiet outlines, 16 %
    val Track = Color(0x29FFFFFF)     // the empty part of a progress bar or ring
    val Shadow = Color(0xFF02060C)

    // Glows behind the content.
    val GlowBlue = Color(0xFF4678FF)
    val GlowTeal = Color(0xFF2FD3A8)
    val GlowMint = Color(0xFF62E3BB)
    val GlowWarm = Color(0xFFFFC864)

    // Cardboard: the moving boxes in the drawings.
    val KraftTop0 = Color(0xFFF4D3A0)
    val KraftTop1 = Color(0xFFDCAE6E)
    val KraftLeft0 = Color(0xFFDCAE6C)
    val KraftLeft1 = Color(0xFFB58445)
    val KraftRight0 = Color(0xFFB58749)
    val KraftRight1 = Color(0xFF855E2C)
    val KraftTape = Color(0xFFF3D9AD)
}

private val Scheme = darkColorScheme(
    primary = Night.Mint,
    onPrimary = Night.OnMint,
    primaryContainer = Color(0x3362E3BB),
    onPrimaryContainer = Color(0xFFC8FFEE),
    inversePrimary = Night.MintDeep,
    secondary = Night.Amber,
    onSecondary = Night.OnAmber,
    // Selected chips and tonal buttons: glass with a mint tint.
    secondaryContainer = Color(0x3D62E3BB),
    onSecondaryContainer = Color(0xFFC8FFEE),
    tertiary = Night.MintLight,
    onTertiary = Night.OnMint,
    tertiaryContainer = Color(0x4D2FB893),
    onTertiaryContainer = Color(0xFFC8FFEE),
    error = Night.Red,
    onError = Color(0xFF4A0D08),
    errorContainer = Color(0x3DFF6B5E),
    onErrorContainer = Night.RedPale,
    background = Night.Ground1,
    onBackground = Night.Text,
    surface = Color(0xFF0F1B2B),
    onSurface = Night.Text,
    surfaceVariant = Color(0x1FFFFFFF),
    onSurfaceVariant = Night.TextDim,
    surfaceTint = Night.Mint,
    inverseSurface = Color(0xFFE6EEF5),
    inverseOnSurface = Night.OnWhite,
    outline = Color(0xFF8494A8),
    outlineVariant = Night.Hairline,
    scrim = Color(0xFF000000),
    surfaceBright = Night.PanelHigh,
    surfaceDim = Night.Ground0,
    // Menus, dialogs and pickers float over other content, so they are solid.
    surfaceContainerLowest = Night.Ground0,
    surfaceContainerLow = Color(0xFF101C2D),
    surfaceContainer = Night.Panel,
    surfaceContainerHigh = Night.PanelHigh,
    // Stock cards, switch tracks and filled fields sit on the ground: a glass tint.
    surfaceContainerHighest = Color(0x1AFFFFFF),
)

/**
 * The typeface of the look. The design uses "Outfit"; to bundle it, put the font in
 * res/font and change this one line, e.g. FontFamily(Font(R.font.outfit, ...)).
 */
val AppFont: FontFamily = FontFamily.Default

private fun style(size: Int, line: Int, weight: FontWeight, spacing: Double = 0.0) = TextStyle(
    fontFamily = AppFont, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight, letterSpacing = spacing.sp,
)

private val Type = Typography(
    displayLarge = style(52, 58, FontWeight.Medium, -0.5),
    displayMedium = style(42, 48, FontWeight.Medium, -0.25),
    displaySmall = style(34, 40, FontWeight.Medium),
    headlineLarge = style(30, 36, FontWeight.Medium),
    headlineMedium = style(28, 33, FontWeight.Medium),
    headlineSmall = style(24, 30, FontWeight.Medium),
    titleLarge = style(22, 27, FontWeight.Medium),
    titleMedium = style(16, 22, FontWeight.SemiBold, 0.1),
    titleSmall = style(14, 20, FontWeight.SemiBold, 0.1),
    bodyLarge = style(16, 23, FontWeight.Normal, 0.15),
    bodyMedium = style(14, 20, FontWeight.Normal, 0.15),
    bodySmall = style(12, 17, FontWeight.Normal, 0.2),
    labelLarge = style(14, 20, FontWeight.SemiBold, 0.1),
    labelMedium = style(12, 16, FontWeight.Medium, 0.3),
    labelSmall = style(11, 15, FontWeight.Medium, 0.3),
)

private val Corners = Shapes(
    extraSmall = RoundedCornerShape(12.dp), // text fields, menus
    small = RoundedCornerShape(14.dp),      // chips
    medium = RoundedCornerShape(20.dp),     // cards
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(30.dp), // dialogs
)

@Composable
fun HouseMoveTheme(content: @Composable () -> Unit) {
    // Always dark: tell the system to draw light status and navigation bar icons.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }
    MaterialTheme(colorScheme = Scheme, typography = Type, shapes = Corners, content = content)
}

/**
 * The ground every screen stands on: the navy gradient with a blue glow top-left,
 * a teal one mid-right and mint rising from the bottom. Drawn once, behind the
 * whole app, so screens (which are transparent) share it and it never moves.
 */
@Composable
fun AppBackground(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize().drawBehind { drawNightGround() }) {
        CompositionLocalProvider(LocalContentColor provides Night.Text, content = content)
    }
}

/** An elliptical glow: [color] at the centre fading to nothing at 72 % of the radii. */
internal fun DrawScope.glow(color: Color, center: Offset, rx: Float, ry: Float, alpha: Float) {
    if (rx <= 0f || ry <= 0f) return
    scale(1f, ry / rx, pivot = center) {
        drawCircle(
            Brush.radialGradient(
                0f to color.copy(alpha = alpha), 0.72f to color.copy(alpha = 0f), 1f to color.copy(alpha = 0f),
                center = center, radius = rx,
            ),
            radius = rx, center = center,
        )
    }
}

internal fun DrawScope.drawNightGround() {
    val w = size.width
    val h = size.height
    drawRect(Brush.verticalGradient(0f to Night.Ground0, 0.55f to Night.Ground1, 1f to Night.Ground2), size = Size(w, h))
    // Strengths are set so secondary text on glass keeps 4.5:1 even at a glow's centre.
    glow(Night.GlowBlue, Offset(w * 0.18f, h * 0.24f), w * 0.46f, h * 0.24f, 0.28f)
    glow(Night.GlowTeal, Offset(w * 0.80f, h * 0.50f), w * 0.54f, h * 0.30f, 0.16f)
    glow(Night.GlowMint, Offset(w * 0.50f, h * 1.06f), w * 0.90f, h * 0.26f, 0.26f)
}
