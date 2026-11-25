package app.housemove.ui.iphone

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.housemove.data.roomColor
import app.housemove.ui.Night
import app.housemove.ui.icons.Icons
import app.housemove.ui.icons.automirrored.filled.ArrowBack
import app.housemove.ui.icons.filled.Settings

/*
 * The few pieces the first iPhone screens are built from, in the look of the Android app's kit (ui/Glass.kt,
 * ui/Visual.kt): glass panels, a glossy mint button for the one thing to do, glass for the others, and plain text
 * that wraps rather than being cut short, so every word fits at the largest text size. Every tap target is at least
 * 48 points; icons that only decorate say nothing to VoiceOver, and the words beside them are read instead.
 */

/** What a panel or button is tinted with. */
enum class Tone { Plain, Mint, Amber, Red }

private fun Tone.rimColor(): Color = when (this) {
    Tone.Plain -> Color.White
    Tone.Mint -> Night.MintLight
    Tone.Amber -> Night.Amber
    Tone.Red -> Night.Red
}

/** The colour for a tone's icon. */
fun Tone.accent(): Color = when (this) {
    Tone.Plain -> Night.Mint
    Tone.Mint -> Night.MintPale
    Tone.Amber -> Night.AmberPale
    Tone.Red -> Night.RedPale
}

/** A glass surface of [shape]: a white or coloured tint and a rim, as the Android app draws glass (without its shadow). */
fun Modifier.glass(shape: Shape = RoundedCornerShape(22.dp), tone: Tone = Tone.Plain): Modifier = this
    .drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val end = Offset(size.width, size.height)
        val fill = when (tone) {
            Tone.Plain -> Brush.linearGradient(
                0f to Night.GlassHi, 0.40f to Night.GlassMid, 0.65f to Night.GlassLow, 1f to Night.GlassEnd, start = Offset.Zero, end = end,
            )
            else -> {
                val c = when (tone) {
                    Tone.Mint -> Night.Mint
                    Tone.Amber -> Night.AmberDeep
                    else -> Night.RedDeep
                }
                Brush.linearGradient(0f to c.copy(alpha = 0.32f), 0.45f to c.copy(alpha = 0.17f), 1f to c.copy(alpha = 0.23f), start = Offset.Zero, end = end)
            }
        }
        val rc = tone.rimColor()
        val k = if (tone == Tone.Plain) 1f else 1.25f
        val rim = Brush.linearGradient(
            0f to rc.copy(alpha = (0.52f * k).coerceAtMost(1f)), 0.30f to rc.copy(alpha = 0.24f * k),
            0.70f to rc.copy(alpha = 0.13f * k), 1f to rc.copy(alpha = 0.26f * k), start = Offset.Zero, end = end,
        )
        val stroke = Stroke(1.dp.toPx())
        onDrawBehind {
            drawOutline(outline, fill)
            drawOutline(outline, rim, style = stroke)
        }
    }
    .clip(shape)

/** A glass panel with its content in a column. */
@Composable
fun Panel(modifier: Modifier = Modifier, tone: Tone = Tone.Plain, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().glass(RoundedCornerShape(22.dp), tone).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/**
 * A big button, as wide as the screen: the glossy mint one for the thing to do ([primary]), glass for the others. Its
 * words wrap onto more lines rather than being cut, at any text size.
 */
@Composable
fun BigButton(
    text: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    enabled: Boolean = true,
    tone: Tone = Tone.Plain,
) {
    val shape = RoundedCornerShape(28.dp)
    val fg = if (primary) Night.OnMint else if (tone == Tone.Red) Night.RedPale else Night.Text
    val look = if (primary) {
        Modifier.clip(shape).background(Brush.verticalGradient(0f to Color(0xFFA9F5DD), 0.42f to Night.Mint, 1f to Color(0xFF34BE98)))
            .border(1.dp, Color.White.copy(alpha = 0.55f), shape)
    } else Modifier.glass(shape, tone)
    Row(
        modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.45f).then(look)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = 56.dp).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
        }
        Text(text, style = MaterialTheme.typography.titleMedium, color = fg, textAlign = TextAlign.Center)
    }
}

/** A smaller glass button, as wide as its words. */
@Composable
fun SmallButton(text: String, icon: ImageVector?, onClick: () -> Unit, modifier: Modifier = Modifier, tone: Tone = Tone.Plain) {
    val shape = RoundedCornerShape(24.dp)
    Row(
        modifier.glass(shape, tone).clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = tone.accent(), modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = Night.Text)
    }
}

/** A short line that explains, in the softer text colour. */
@Composable
fun Hint(text: String, modifier: Modifier = Modifier, center: Boolean = false) {
    Text(
        text, style = MaterialTheme.typography.bodyMedium, color = Night.TextDim, modifier = modifier,
        textAlign = if (center) TextAlign.Center else TextAlign.Start,
    )
}

/** A heading inside a screen, for VoiceOver's headings rotor too. */
@Composable
fun Heading(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleLarge, color = Night.Text, modifier = modifier.semantics { heading() })
}

/** A word on a small coloured tag: amber for "take care", red for "something is wrong". */
@Composable
fun Flag(text: String, error: Boolean = false) {
    val (bg, fg) = if (error) Night.RedDeep.copy(alpha = 0.30f) to Night.RedPale else Night.AmberDeep.copy(alpha = 0.24f) to Night.AmberPale
    Text(
        text, color = fg, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(bg).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** A room's colour, as on its tape and door sign. Only decoration: its name is written beside it. */
@Composable
fun Swatch(colorIndex: Int, modifier: Modifier = Modifier, size: Int = 28) {
    val c = Color(roomColor(colorIndex).argb)
    Box(modifier.size(size.dp).clip(CircleShape).background(c).border(1.5.dp, Color.White.copy(alpha = 0.55f), CircleShape))
}

/** A room's colour as a tag with its name on it, black or white text, whichever reads better. */
@Composable
fun RoomTag(name: String, colorIndex: Int) {
    val c = Color(roomColor(colorIndex).argb)
    val text = if (c.luminance() > 0.179f) Color.Black else Color.White
    Text(
        name, style = MaterialTheme.typography.labelMedium, color = text,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(c).border(1.dp, Color.White.copy(alpha = 0.42f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/**
 * The frame of every screen: an optional Back button, the title as a heading, the [body] (which scrolls itself) and,
 * fixed at the bottom so it is always in reach, the [bottom] buttons.
 */
@Composable
fun Frame(
    title: String,
    onBack: (() -> Unit)? = null,
    subtitle: String? = null,
    bottom: (@Composable ColumnScope.() -> Unit)? = null,
    body: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = if (onBack != null) 4.dp else 20.dp, end = 20.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                Box(
                    Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = "Back", onClick = onBack)
                        .semantics { contentDescription = "Back" }.testTag("back"),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = Night.Text)
                }
                Spacer(Modifier.width(4.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, color = Night.Text, modifier = Modifier.semantics { heading() })
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Night.TextDim)
            }
        }
        Column(Modifier.weight(1f).fillMaxWidth(), content = body)
        if (bottom != null) {
            Column(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = bottom,
            )
        }
    }
}

/**
 * A one-line text field for a code: no capitals, no autocorrection, so what is pasted or typed stays as it is.
 * Return ([ImeAction.Go]) does [onGo].
 */
@Composable
fun CodeField(value: String, onChange: (String) -> Unit, label: String, onGo: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go,
        ),
        keyboardActions = KeyboardActions(onGo = { onGo() }),
        textStyle = MaterialTheme.typography.bodyLarge,
        colors = fieldColors(),
        modifier = modifier.fillMaxWidth().widthIn(min = 120.dp),
    )
}

/** A one-line text field for words (a name, a search). */
@Composable
fun WordsField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    leading: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        leadingIcon = leading?.let { { Icon(it, contentDescription = null) } },
        trailingIcon = trailing,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        textStyle = MaterialTheme.typography.bodyLarge,
        colors = fieldColors(),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Night.Text,
    unfocusedTextColor = Night.Text,
    focusedBorderColor = Night.Mint,
    unfocusedBorderColor = Color(0xFF8494A8),
    focusedLabelColor = Night.MintLight,
    unfocusedLabelColor = Night.TextDim,
    cursorColor = Night.Mint,
    focusedContainerColor = Color(0x14FFFFFF),
    unfocusedContainerColor = Color(0x0DFFFFFF),
    focusedLeadingIconColor = Night.TextDim,
    unfocusedLeadingIconColor = Night.TextDim,
)

/** A report as a panel: mint when it went well, red when not, with Open Settings when that is the fix. */
@Composable
fun ReportPanel(report: Report, onSettings: (() -> Unit)?, modifier: Modifier = Modifier) {
    val tone = if (report.ok) Tone.Mint else Tone.Red
    Panel(modifier, tone) {
        Text(report.title, style = MaterialTheme.typography.titleMedium, color = Night.Text, modifier = Modifier.semantics { heading() })
        Text(report.text, style = MaterialTheme.typography.bodyMedium, color = Night.Text)
        if (report.settings && onSettings != null) {
            SmallButton("Open Settings", Icons.Filled.Settings, onSettings, tone = Tone.Red)
        }
    }
}
