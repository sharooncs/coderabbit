package app.housemove.ui.screens

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import app.housemove.data.*
import app.housemove.ui.*
import java.io.File

/*
 * What the item, packing and move-day screens all use, so that the same thing looks and
 * reads the same wherever it turns up:
 *
 *   Words        money, countOf, whoIs
 *   Counting     carriedBy, carriedCounts, InsideChip: what a container holds, counted one way
 *   Rules        okOrSay, mayOrSay, mayDelete: when the Store says no, its reason is always shown
 *   Suggestions  WaitingTag, ProposalReview, DeclinedNote: shown on the thing they are about
 *   Entry        AmountPicker: a sum of money; QuickAdd: a name, both without the keyboard
 *   Lists        ProgressCard, ListHeading, MenuChip, RoomFilterChip, ListFab, RowButton,
 *                DeleteButton, narrowScreen
 *
 * Who may do what is always asked of the Store (allowed(), store.can, mayCarryOut and
 * the like), never worked out on a screen.
 */

// ---- words ---------------------------------------------------------------------------

/** "£ 80", "£ 62.50", or "80" when the move has no currency. Pence are shown in full once there are any. */
internal fun money(currency: String, amount: Double): String {
    val whole = amount == Math.rint(amount)
    val number = if (whole) fmtNum(amount) else String.format(java.util.Locale.ROOT, "%.2f", amount)
    return (currency.trim() + " " + number).trim()
}

/** "1 thing", "3 things". */
internal fun countOf(n: Int, one: String, many: String = one + "s") = "$n " + if (n == 1) one else many

/** A person as others are named on this phone: "you" for its own person. Someone removed keeps their name. */
internal fun whoIs(data: AppData, personId: String?, me: Person?): String =
    if (personId != null && personId == me?.id) "you" else data.personName(personId).ifBlank { "someone" }

// ---- counting what a container holds -----------------------------------------------------

/**
 * How many catalogued things a container carries, counted the same way on every screen:
 * the items packed in it, the containers inside it, and the items in those. Books and
 * products scanned in are counted on their own ("2 scanned"); the written list of what is
 * inside is not counted at all. Each thing is counted once, under the container it is in.
 */
internal fun AppData.carriedBy(boxId: String): Int =
    itemsIn(boxId).size + childrenOf(boxId).sumOf { 1 + itemsIn(it.id).size }

/** [carriedBy] for every container at once, in one pass, so a long list stays quick. */
internal fun AppData.carriedCounts(): Map<String, Int> {
    if (items.none { it.boxId != null && !it.deleted } && boxes.none { it.parentId != null && !it.deleted }) return emptyMap()
    val out = HashMap<String, Int>()
    val outer = HashMap<String, String>()
    val live = liveBoxes
    for (b in live) {
        val p = parentOf(b) ?: continue
        outer[b.id] = p.id
        out.merge(p.id, 1, Int::plus)
    }
    val ids = live.map { it.id }.toSet()
    for (i in liveItems) {
        val b = i.boxId?.takeIf { it in ids } ?: continue
        out.merge(b, 1, Int::plus)
        outer[b]?.let { out.merge(it, 1, Int::plus) }
    }
    return out
}

/** "3 things inside": what a container carries, as [carriedBy] counts it. Nothing when it carries nothing. */
@Composable
internal fun InsideChip(n: Int, modifier: Modifier = Modifier) {
    if (n > 0) GlassChip(countOf(n, "thing") + " inside", modifier, icon = Icons.Default.Layers)
}

/**
 * A small phone, or large text on a medium one: things side by side go one under the other,
 * rows of tiles get fewer columns. [limit] is the screen width in dp, at normal text size,
 * below which that happens.
 */
@Composable
internal fun narrowScreen(limit: Float = 340f): Boolean = LocalConfiguration.current.let { it.screenWidthDp / it.fontScale < limit }

// ---- saying it, with the keyboard to fall back on -------------------------------------------

/** Can a few words be spoken on this phone? Where they cannot, the microphone is not shown (see SpeakButton). */
@Composable
internal fun canSpeak(): Boolean {
    if (LocalSpeech.current != null) return true
    val context = LocalContext.current
    return remember { Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).resolveActivity(context.packageManager) != null }
}

/**
 * The microphone for a few optional words that have no field of their own (why something
 * is declined, a note with a question). On a phone with no speech service the microphone
 * cannot be shown, so a keyboard button takes its place and opens a line to type on
 * ([typing] says whether it is open; show it with [TypedLine]). Typing is never the only
 * way where speech works, and never out of reach where it does not.
 */
@Composable
internal fun SayOrType(prompt: String, sayIt: String, typeIt: String, typing: Boolean, onType: () -> Unit, size: Dp = 40.dp, onText: (String) -> Unit) {
    if (canSpeak()) SpeakButton(prompt, contentDescription = sayIt, size = size, onText = onText)
    else GlassOrb(Icons.Default.Keyboard, typeIt, kind = if (typing) OrbKind.Selected else OrbKind.Plain, size = size, onClick = onType)
}

/** One line to type a few words on, with the button that sends them. Goes with [SayOrType]. */
@Composable
internal fun TypedLine(label: String, send: String, modifier: Modifier = Modifier, onText: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = text, onValueChange = { text = it }, label = { Text(label, maxLines = 1) }, singleLine = true,
            colors = glassFieldColors(), modifier = Modifier.weight(1f),
        )
        RowButton(
            send,
            onClick = {
                if (text.isNotBlank()) {
                    onText(text.trim())
                    text = ""
                }
            },
            modifier = Modifier.enabledLook(text.isNotBlank()),
        )
    }
}

/**
 * Adding something by name without the keyboard. One line reads [label]: tapping it opens
 * the keyboard's way ([onType]), for a phone with no speech or a name that is hard to say;
 * the microphone beside it takes a spoken name. Under the line, [chips] offers the names
 * most likely wanted, one tap each, so the usual things need neither. [name] is what a
 * screen reader calls the line.
 */
@Composable
internal fun QuickAdd(
    label: String,
    name: String,
    prompt: String,
    sayIt: String,
    chips: List<String>,
    onType: () -> Unit,
    modifier: Modifier = Modifier,
    onAdd: (String) -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        GlassPill(Modifier.fillMaxWidth(), sheen = true, contentPadding = PaddingValues(4.dp)) {
            Row(
                Modifier.weight(1f).clip(CircleShape).clickable(role = Role.Button, onClick = onType)
                    .semantics { contentDescription = name }.heightIn(min = 48.dp).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Add, contentDescription = null, tint = Night.Mint)
                Spacer(Modifier.width(10.dp))
                Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Icon(Icons.Default.Keyboard, contentDescription = null, tint = Night.TextDim, modifier = Modifier.size(20.dp))
            }
            if (canSpeak()) {
                Spacer(Modifier.width(6.dp))
                SpeakButton(prompt, contentDescription = sayIt, onText = onAdd)
            }
        }
        if (chips.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                chips.forEach { c -> GlassChip(c, icon = Icons.Default.Add, onClick = { onAdd(c) }) }
            }
        }
    }
}

// ---- the camera, with a way forward when it is refused ---------------------------------------

/**
 * The camera for taking photographs. When the phone will not let the app use it, this
 * says so and offers three ways forward instead of a dead end: choose photos already on
 * the phone, open the phone's settings to allow the camera, or go back.
 */
@Composable
internal fun PhotoCamera(hint: String, onPhoto: (File) -> Unit, onClose: () -> Unit) {
    val store = LocalStore.current
    val context = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        uris.forEach { uri ->
            val f = store.newPhotoFile()
            if (importImage(context, uri, f)) onPhoto(f)
        }
        if (uris.isNotEmpty()) onClose()
    }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }
    // The phone asks once by itself. Until the answer is yes, this is what is behind its question.
    if (granted) {
        CameraDialog(hint = hint, newFile = { store.newPhotoFile() }, onPhoto = onPhoto, onClose = onClose)
        return
    }
    Dialog(onDismissRequest = onClose) {
        Column(
            Modifier.fillMaxWidth().drawBehind { drawRoundRect(Night.Panel, cornerRadius = CornerRadius(28.dp.toPx())) }
                .glass(RoundedCornerShape(28.dp), sheen = true, shadow = 0.dp).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(40.dp).drawBehind { drawOrb(OrbKind.Amber, ring = false) }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.NoPhotography, contentDescription = null, tint = Night.OnAmber, modifier = Modifier.size(20.dp))
                }
                Text("The camera is switched off for HouseMove", style = MaterialTheme.typography.titleMedium, color = Night.Text, modifier = Modifier.weight(1f))
            }
            Text(
                "You can still add photos you have already taken. To use the camera here, allow it for HouseMove in the phone's settings.",
                style = MaterialTheme.typography.bodyMedium, color = Night.TextSoft,
            )
            BigAction(Icons.Default.PhotoLibrary, "Choose photos instead", onClick = {
                gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            })
            BigAction(Icons.Default.Settings, "Open the phone's settings", primary = false, onClick = {
                try {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (_: ActivityNotFoundException) {
                    toast(context, "Open Settings, then Apps, HouseMove, Permissions.")
                }
                onClose()
            })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RowButton("Ask again", onClick = { ask.launch(Manifest.permission.CAMERA) }, modifier = Modifier.weight(1f), forward = false)
                RowButton("Not now", onClick = onClose, modifier = Modifier.weight(1f), forward = false)
            }
        }
    }
}

// ---- when the Store says no ------------------------------------------------------------

/** The Store said no: tell the person why. True when the call went through. */
internal fun Outcome<*>.okOrSay(context: Context): Boolean {
    reason?.let { toast(context, it) }
    return ok
}

/**
 * For the Store's plain saves, which cannot answer: ask first whether this phone's person
 * may do [action], and say why not. True when the save may go ahead.
 */
internal fun Store.mayOrSay(context: Context, action: Action): Boolean {
    val why = data.value.refusal(me, action) ?: return true
    toast(context, why)
    return false
}

/**
 * Whether deleting a record is offered. The Store has no rule for deleting; where several
 * people share the move, taking something off the list for everyone is not offered to a
 * Helper. With one person, or with nobody an Organiser, it is always offered.
 */
@Composable
internal fun mayDelete(): Boolean = !appData().shared() || allowed(Action.SUGGEST)

// ---- buttons ---------------------------------------------------------------------------

/** How a [RowButton] is drawn: the thing to do, something that carries a row forward, or a step back. */
internal enum class RowButtonLook { Primary, Forward, Back }

/**
 * A pill-shaped button at least 48dp tall, for the end of a row or under a card ("Load",
 * "Approve", "Undo"). Glowing mint for the one thing to do, mint-tinted glass when it
 * carries the row forward, clear glass when it takes something back.
 */
@Composable
internal fun RowButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    forward: Boolean = true,
    icon: ImageVector? = null,
    look: RowButtonLook = if (forward) RowButtonLook.Forward else RowButtonLook.Back,
) {
    val fg = when (look) {
        RowButtonLook.Primary -> Night.OnMint
        RowButtonLook.Forward -> Night.MintPale
        RowButtonLook.Back -> Night.Text
    }
    val body = when (look) {
        RowButtonLook.Primary -> Modifier.mintPill()
        RowButtonLook.Forward -> Modifier.glass(CircleShape, GlassTone.Mint, shadow = 0.dp)
        RowButtonLook.Back -> Modifier.glass(CircleShape, GlassTone.Plain, shadow = 0.dp)
    }
    Row(
        modifier.then(body).clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp).widthIn(min = 68.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The button that floats over a list, above the dock: a glowing mint pill with a picture
 * and two or three words. One per screen.
 */
@Composable
internal fun ListFab(icon: ImageVector, text: String, onClick: () -> Unit) {
    Row(
        Modifier.mintPill().clickable(role = Role.Button, onClick = onClick).heightIn(min = 56.dp).padding(start = 18.dp, end = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Night.OnMint)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.titleMedium, color = Night.OnMint, maxLines = 1)
    }
}

/**
 * Taking a record off the list for everyone ("Delete item"): red words and a bin on red
 * glass, the same on every page and always the last thing on it. It is not undone, so
 * [onClick] should ask first. Whether it is offered at all is [mayDelete]'s answer.
 */
@Composable
internal fun DeleteButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.glass(CircleShape, GlassTone.Red, shadow = 0.dp).clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Default.Delete, contentDescription = null, tint = Night.RedPale, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = Night.RedPale, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ---- the tops and headings of lists ------------------------------------------------------

/**
 * The number a screen is about: a glass panel with a ring that has how many are done in
 * the middle (a tick once all are), the same said as a sentence beside it, an optional
 * [sub] line, and room [below] for chips that belong with the count (trips, rooms).
 * [tone] tints the panel. Every list screen opens with this one, whichever part of the
 * app it is in.
 */
@Composable
internal fun ProgressCard(
    ratio: Ratio,
    title: String,
    modifier: Modifier = Modifier,
    sub: String? = null,
    tone: GlassTone = GlassTone.Plain,
    below: @Composable ColumnScope.() -> Unit = {},
) {
    GlassCard(modifier.fillMaxWidth(), tone = tone, shape = RoundedCornerShape(26.dp), sheen = true) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            val done = ratio.total > 0 && ratio.done >= ratio.total
            Ring(ratio, size = 60.dp, stroke = 7.dp, color = if (done) Night.MintLight else Night.Mint) {
                if (done) Icon(Icons.Default.Check, contentDescription = "Done", tint = Night.MintLight, modifier = Modifier.size(28.dp))
                else Text("${ratio.done}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (!sub.isNullOrBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = Night.TextSoft)
                below()
            }
        }
    }
}

/**
 * The heading over a group of rows, on the open ground: an optional picture, the name, how
 * many there are as a small chip, and a line or a button at the end. The same for rooms on
 * Load, decisions on the leaving list, and what is inside a container.
 */
@Composable
internal fun ListHeading(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    count: Int? = null,
    trailing: String? = null,
    tint: androidx.compose.ui.graphics.Color = Night.Mint,
    action: (@Composable () -> Unit)? = null,
) {
    // Only as wide as its words, unless something at the end has to be pushed to the far side.
    val pushed = trailing != null || action != null
    Row(modifier.heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(if (pushed) Modifier.weight(1f) else Modifier, verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(
                title, style = MaterialTheme.typography.titleSmall, color = Night.Text, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = (if (pushed || count != null) Modifier.weight(1f, fill = false) else Modifier).semantics { heading() },
            )
            if (count != null) {
                Spacer(Modifier.width(8.dp))
                GlassChip(count.toString())
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            Text(trailing, style = MaterialTheme.typography.labelMedium, color = Night.TextDim, textAlign = TextAlign.End, maxLines = 1)
        }
        if (action != null) {
            Spacer(Modifier.width(8.dp))
            action()
        }
    }
}

/**
 * The two things to do with a file the app makes (a sheet of labels, a report): open it,
 * or hand it to another app. Side by side where there is room, stacked on a narrow screen
 * or with large text. [onOpen] is null for a file with nothing on the phone to open it.
 */
@Composable
internal fun FileButtons(
    openLabel: String,
    enabled: Boolean,
    onOpen: (() -> Unit)?,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
    openIcon: ImageVector = Icons.Default.OpenInNew,
    busy: Boolean = false,
) {
    val open: @Composable (Modifier) -> Unit = { m ->
        Row(
            m.enabledLook(enabled).mintPill().clickable(enabled = enabled && onOpen != null, role = Role.Button) { onOpen?.invoke() }
                .heightIn(min = 52.dp).padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
        ) {
            // While the file is being made, the picture on the button turns into a spinner.
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = Night.OnMint, strokeWidth = 2.dp)
            else Icon(openIcon, contentDescription = null, tint = Night.OnMint, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(openLabel, style = MaterialTheme.typography.labelLarge, color = Night.OnMint, maxLines = 1)
        }
    }
    val share: @Composable (Modifier) -> Unit = { m ->
        Row(
            m.enabledLook(enabled).glass(CircleShape, shadow = 0.dp).clickable(enabled = enabled, role = Role.Button, onClick = onShare)
                .heightIn(min = 52.dp).padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Default.Share, contentDescription = null, tint = Night.Text, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Share", style = MaterialTheme.typography.labelLarge, color = Night.Text, maxLines = 1)
        }
    }
    when {
        onOpen == null -> share(modifier.fillMaxWidth())
        narrowScreen(360f) -> Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            open(Modifier.fillMaxWidth())
            share(Modifier.fillMaxWidth())
        }
        else -> Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            open(Modifier.weight(1f))
            share(Modifier)
        }
    }
}

// ---- filters ---------------------------------------------------------------------------

/**
 * A filter chip that opens a short list to choose from, so a whole row of chips is not
 * needed for it. [text] is what is chosen now; [selected] tints it when that is not "all".
 * [menu] is given a function that closes the list.
 */
@Composable
internal fun MenuChip(
    text: String,
    selected: Boolean,
    description: String,
    modifier: Modifier = Modifier,
    leading: @Composable () -> Unit = {},
    menu: @Composable ColumnScope.(close: () -> Unit) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        // Built like a GlassChip, with room for the arrow that says it opens a list.
        Box(
            Modifier.minimumInteractiveComponentSize().clip(CircleShape).clickable(role = Role.DropdownList) { open = true },
            contentAlignment = Alignment.Center,
        ) {
            Row(
                Modifier.glass(CircleShape, if (selected) GlassTone.Mint else GlassTone.Plain, shadow = 0.dp)
                    .heightIn(min = 34.dp).padding(start = 12.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CompositionLocalProvider(LocalContentColor provides if (selected) Night.MintPale else Night.Text) {
                    leading()
                    Text(text, maxLines = 1, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
                    Icon(Icons.Default.ArrowDropDown, contentDescription = description, modifier = Modifier.size(18.dp))
                }
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) { menu { open = false } }
    }
}

/** What stands for "no room" among the choices of a [RoomFilterChip]. */
internal const val NO_ROOM = "none"

/**
 * The room filter of a list as one chip that opens the rooms. New-home rooms show their
 * colour. [withNoRoom] adds a "No room" choice, reported as [NO_ROOM].
 */
@Composable
internal fun RoomFilterChip(rooms: List<Room>, selectedId: String?, onPick: (String?) -> Unit, withNoRoom: Boolean = false) {
    val selected = rooms.firstOrNull { it.id == selectedId }
    val coloured = rooms.any { it.home == Home.DEST }
    MenuChip(
        text = selected?.name ?: if (selectedId == NO_ROOM) "No room" else "All rooms",
        selected = selected != null || selectedId == NO_ROOM,
        description = "Choose a room",
        leading = {
            if (selected != null && coloured) ColorDot(selected.color)
            else Icon(Pic.Room, contentDescription = null, modifier = Modifier.size(16.dp))
        },
    ) { close ->
        DropdownMenuItem(
            text = { Text("All rooms") },
            leadingIcon = { Icon(Pic.Room, contentDescription = null, modifier = Modifier.size(18.dp)) },
            onClick = { onPick(null); close() },
        )
        rooms.forEach { r ->
            DropdownMenuItem(
                text = { Text(r.name) },
                leadingIcon = if (coloured) ({ ColorDot(r.color) }) else null,
                onClick = { onPick(r.id); close() },
            )
        }
        if (withNoRoom) DropdownMenuItem(text = { Text("No room") }, onClick = { onPick(NO_ROOM); close() })
    }
}

// ---- suggestions, on the thing they are about --------------------------------------------

/** One-tap reasons for saying no, the same ones the Approvals inbox offers for each kind of suggestion. */
internal fun reasonsToDecline(kind: ProposalKind): List<String> = when (kind) {
    ProposalKind.ITEM_DECISION -> listOf("We're keeping it", "Not yet", "Different price", "Let's talk first")
    ProposalKind.ITEM_ROOM -> listOf("Won't fit there", "Not yet", "Let's talk first")
    ProposalKind.COST -> listOf("Too expensive", "Not needed", "Not yet", "Let's talk first")
    ProposalKind.TASK -> listOf("Not needed", "Already planned", "Let's talk first")
    ProposalKind.QUESTION -> listOf("Not sure yet", "Ask me in person")
}

/** What a suggestion asks for, short enough for a callout on the thing itself: "Sell for £ 80", "To Kitchen". */
internal fun AppData.proposalText(p: Proposal): String = when (p.kind) {
    ProposalKind.ITEM_DECISION -> (p.disposition ?: Disposition.UNDECIDED).label + (p.price?.let { " for " + money(move.currency, it) } ?: "")
    ProposalKind.ITEM_ROOM -> "To " + (room(p.roomId)?.name ?: "no room")
    ProposalKind.QUESTION -> p.note.ifBlank { "What should happen to it?" }
    else -> describe(p)
}

/** The two lines every waiting suggestion is announced with: that it waits, and who wants what. */
private fun waitingLines(p: Proposal, data: AppData, me: Person?, what: String?): Pair<String, String> {
    val who = whoIs(data, p.by, me)
    val name = who.replaceFirstChar { it.uppercase() }
    return if (p.kind == ProposalKind.QUESTION) "Waiting for an answer" to (name + " asked" + if (p.note.isBlank()) "" else ": " + p.note)
    else "Waiting for approval" to if (what.isNullOrBlank()) "Suggested by $who" else "$name: $what"
}

private fun waitingIcon(p: Proposal) = if (p.kind == ProposalKind.QUESTION) Icons.Default.QuestionMark else Icons.Default.HourglassTop

/**
 * The amber callout on anything with a suggestion waiting: "Waiting for approval", and who
 * suggested what. [what] is what it would change, when the row it sits on does not already
 * say so. [compact] is the same callout drawn smaller, for a card in a grid.
 */
@Composable
internal fun WaitingTag(p: Proposal, data: AppData, modifier: Modifier = Modifier, compact: Boolean = false, what: String? = data.proposalText(p)) {
    val (title, value) = waitingLines(p, data, LocalStore.current.me, what)
    val icon = waitingIcon(p)
    if (compact) {
        // In a narrow card the hourglass and one word say that it waits; who wants what gets the room.
        Row(
            modifier.glass(RoundedCornerShape(16.dp), shadow = 4.dp).semantics(mergeDescendants = true) { contentDescription = "$title. $value" }
                .padding(start = 4.dp, top = 4.dp, bottom = 4.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(24.dp).drawBehind { drawOrb(OrbKind.Amber, halo = false) }, contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = Night.OnAmber, modifier = Modifier.size(13.dp))
            }
            Text(
                value, color = Night.Text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
            )
        }
        return
    }
    Row(
        modifier.glass(RoundedCornerShape(24.dp), shadow = 8.dp).padding(start = 6.dp, top = 6.dp, bottom = 6.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(36.dp).drawBehind { drawOrb(OrbKind.Amber, ring = false) }, contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = Night.OnAmber, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f, fill = false)) {
            Text(title, color = Night.AmberPale, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(value, color = Night.Text, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (p.kind != ProposalKind.QUESTION && p.note.isNotBlank()) Text("“${p.note}”", style = MaterialTheme.typography.bodySmall, color = Night.TextSoft)
        }
    }
}

/**
 * A waiting suggestion as an Organiser sees it, on the thing it is about: who asks for
 * what, with Approve and Decline right there. Declining offers a few reasons to tap, the
 * microphone for any other, and "No reason". A question has nothing to approve: it is
 * answered by deciding, or put off with "Not now".
 */
@Composable
internal fun ProposalReview(p: Proposal, data: AppData, modifier: Modifier = Modifier, what: String? = data.proposalText(p)) {
    val store = LocalStore.current
    val context = LocalContext.current
    val question = p.kind == ProposalKind.QUESTION
    val (title, value) = waitingLines(p, data, store.me, what)
    var declining by rememberSaveable(p.id) { mutableStateOf(false) }
    GlassCard(modifier.fillMaxWidth(), tone = GlassTone.Amber, shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(36.dp).drawBehind { drawOrb(OrbKind.Amber, ring = false) }, contentAlignment = Alignment.Center) {
                    Icon(waitingIcon(p), contentDescription = null, tint = Night.OnAmber, modifier = Modifier.size(18.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.bodySmall, color = Night.AmberPale, maxLines = 1)
                    Text(value, style = MaterialTheme.typography.titleSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    if (!question && p.note.isNotBlank()) Text("“${p.note}”", style = MaterialTheme.typography.bodySmall, color = Night.TextSoft)
                }
            }
            if (!declining) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (question) {
                        Text("Choose what happens to it to answer.", style = MaterialTheme.typography.bodySmall, color = Night.TextSoft, modifier = Modifier.weight(1f))
                        RowButton("Not now", onClick = { declining = true }, forward = false)
                    } else {
                        RowButton("Approve", onClick = { store.approve(p.id).okOrSay(context) }, Modifier.weight(1f), icon = Icons.Default.Check, look = RowButtonLook.Primary)
                        RowButton("Decline", onClick = { declining = true }, Modifier.weight(1f), forward = false, icon = Icons.Default.Close)
                    }
                }
            } else {
                var typing by rememberSaveable(p.id) { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Why not? One tap declines it.", style = MaterialTheme.typography.labelLarge, color = Night.AmberPale, modifier = Modifier.weight(1f))
                    // Any other reason is said. A phone with no speech gets the keyboard in the microphone's place.
                    SayOrType("Why not?", "Say why not", "Type why not", typing, onType = { typing = !typing }) { why -> store.decline(p.id, why).okOrSay(context) }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    reasonsToDecline(p.kind).forEach { reason -> GlassChip(reason, onClick = { store.decline(p.id, reason).okOrSay(context) }) }
                    GlassChip("No reason", onClick = { store.decline(p.id).okOrSay(context) })
                    GlassChip("Keep it waiting", icon = Icons.Default.Undo, tone = GlassTone.Amber, onClick = { declining = false })
                }
                if (typing) TypedLine("Why not?", "Decline") { why -> store.decline(p.id, why).okOrSay(context) }
            }
        }
    }
}

/** A waiting suggestion on its target, as whoever is holding the phone sees it: to settle for an Organiser, to wait on for the rest. */
@Composable
internal fun Suggestion(p: Proposal, data: AppData, modifier: Modifier = Modifier, what: String? = data.proposalText(p)) {
    if (allowed(Action.APPROVE)) ProposalReview(p, data, modifier, what) else WaitingTag(p, data, modifier, what = what)
}

// ---- answers this person has read ----------------------------------------------------------

private const val SEEN_PREFS = "housemove-answers"
private const val SEEN_KEY = "seen"

/**
 * Which answers to their own suggestions this phone's person has read. A declined
 * suggestion is shown, with its reason, until they tap "OK"; this is where that tap is
 * remembered. It is kept on the phone only: it is about what this person has seen.
 */
internal class SeenAnswers(private val context: Context) {
    private val prefs get() = context.getSharedPreferences(SEEN_PREFS, Context.MODE_PRIVATE)
    var ids by mutableStateOf<Set<String>>(prefs.getStringSet(SEEN_KEY, emptySet())?.toSet() ?: emptySet())
        private set

    fun mark(id: String) {
        // Only the latest few hundred are worth keeping: older answers are long read.
        ids = (ids + id).toList().takeLast(400).toSet()
        prefs.edit().putStringSet(SEEN_KEY, ids).apply()
    }
}

@Composable
internal fun rememberSeenAnswers(): SeenAnswers {
    val context = LocalContext.current.applicationContext
    return remember(context) { SeenAnswers(context) }
}

/**
 * This person's own suggestions that were declined and that they have not read yet,
 * newest first. [kinds] narrows it to what a screen is about.
 */
internal fun AppData.declinedFor(me: Person?, seen: Set<String>, vararg kinds: ProposalKind): List<Proposal> =
    proposalsBy(me?.id).filter { it.state == ProposalState.DECLINED && it.id !in seen && (kinds.isEmpty() || it.kind in kinds) }

/** The unread "declined" answer to this person's latest suggestion about one thing, if there is one. */
internal fun AppData.declinedOn(targetId: String, me: Person?, seen: Set<String>, vararg kinds: ProposalKind): Proposal? {
    val mine = proposalsFor(targetId).filter { it.by == me?.id && (kinds.isEmpty() || it.kind in kinds) }
    return mine.firstOrNull()?.takeIf { it.state == ProposalState.DECLINED && it.id !in seen }
}

/**
 * What became of a suggestion that was declined, for the person who made it: who said no
 * and why, with "OK" to put it away once read. [what] is what was suggested, when the
 * row this sits on does not already say so.
 */
@Composable
internal fun DeclinedNote(p: Proposal, data: AppData, modifier: Modifier = Modifier, what: String? = null, onSeen: (() -> Unit)? = null) {
    val by = data.personName(p.decidedBy)
    Row(
        modifier.fillMaxWidth().glass(RoundedCornerShape(18.dp), GlassTone.Red, shadow = 0.dp).padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp)
            .heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Default.Block, contentDescription = null, tint = Night.RedPale, modifier = Modifier.size(18.dp))
        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
            Text(
                "Declined" + (if (by.isBlank()) "" else " by $by") + (if (p.reason.isBlank()) "" else ": " + p.reason),
                style = MaterialTheme.typography.bodyMedium, color = Night.Text,
            )
            if (!what.isNullOrBlank()) Text("You suggested: $what", style = MaterialTheme.typography.bodySmall, color = Night.TextSoft)
        }
        if (onSeen != null) RowButton("OK", onClick = onSeen, forward = false)
    }
}

// ---- money without the keyboard -------------------------------------------------------------

private fun stepFor(v: Double): Double = when {
    v < 20 -> 1.0
    v < 100 -> 5.0
    v < 500 -> 10.0
    v < 2000 -> 50.0
    else -> 100.0
}

internal val PRICE_PRESETS = listOf(5.0, 10.0, 20.0, 50.0, 100.0, 200.0, 500.0)

/**
 * A sum of money without a keyboard: likely amounts to tap, and round buttons to adjust
 * it. A price moves in sensible steps with minus and plus; where the exact sum matters
 * ([fine], for costs) there are four buttons, for ones and for tens. The keyboard is one
 * tap away behind "Other amount" for anything else. Null means none has been set.
 */
@Composable
internal fun AmountPicker(
    value: Double?,
    onChange: (Double?) -> Unit,
    label: String,
    currency: String,
    modifier: Modifier = Modifier,
    presets: List<Double> = PRICE_PRESETS,
    fine: Boolean = false,
) {
    val v = value ?: 0.0
    var typing by rememberSaveable(label) { mutableStateOf(false) }
    fun set(next: Double) = onChange(if (next <= 0.0) null else next)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val shown = if (value == null) "Not set" else money(currency, value)
        val colour = if (value == null) Night.TextDim else Night.Text
        if (fine) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = Night.TextDim)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Nudge("−10", "$label: 10 less") { set(v - 10) }
                Nudge("−1", "$label: 1 less") { set(v - 1) }
                Text(
                    shown, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = colour,
                    textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.weight(1f),
                )
                Nudge("+1", "$label: 1 more") { set(v + 1) }
                Nudge("+10", "$label: 10 more") { set(v + 10) }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.labelLarge, color = Night.TextDim)
                    Text(shown, style = MaterialTheme.typography.headlineSmall, color = colour, maxLines = 1)
                }
                GlassOrb(Icons.Default.Remove, "$label: less", size = 44.dp, onClick = { set(v - stepFor(v - 0.01)) })
                Spacer(Modifier.width(8.dp))
                GlassOrb(Icons.Default.Add, "$label: more", size = 44.dp, onClick = { set(v + stepFor(v)) })
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            presets.distinct().forEach { p -> GlassChip(money(currency, p), selected = value == p, onClick = { set(p) }) }
            GlassChip("Other amount", icon = Icons.Default.Keyboard, selected = typing, onClick = { typing = !typing })
        }
        if (typing) {
            NumberInput(
                value, { set(it ?: 0.0) }, if (currency.isBlank()) label else "$label (${currency.trim()})",
                Modifier.fillMaxWidth(), key = "$label-typed",
            )
        }
    }
}

@Composable
private fun Nudge(text: String, description: String, onClick: () -> Unit) {
    GlassOrb(contentDescription = description, size = 42.dp, onClick = onClick) {
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}
