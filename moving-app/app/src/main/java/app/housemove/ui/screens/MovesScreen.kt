package app.housemove.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import app.housemove.ui.icons.Icons
import app.housemove.ui.icons.automirrored.filled.Send
import app.housemove.ui.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role as SemanticsRole
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.housemove.data.*
import app.housemove.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** One move as its card shows it, worked out once when the list opens. */
private class MoveSummary(
    val card: MoveCard,
    val store: Store?,
    val here: Boolean,
) {
    private val data = store?.data?.value
    private val device = store?.device?.value
    val name: String = data?.move?.name?.ifBlank { null } ?: card.name.ifBlank { "A move" }
    val shared: Boolean = data?.shared() == true
    val role: Role? = if (data != null && device != null) data.roleOf(device) else card.role
    val kind: MoveKind = data?.move?.kind ?: MoveKind.SELF
    val days: String? = data?.daysToGoText()
    val done: Float = data?.doneFraction() ?: 0f
    val synced: Long = device?.lastSyncAt ?: 0L
    val needsYou: Boolean = !here && store?.needsYou() == true
    val earlier: Boolean = card.archived || data?.finished() == true
    val setUp: Boolean = data?.move?.setupDone != false

    /** Helpers make no backups: what Helpers are not sent is not on their phone to save (the Store's rule). */
    val mayBackUp: Boolean = store?.whyNoBackup() == null
    val backedUp: Long = device?.lastBackupAt ?: 0L

    /** This phone has been taken out of the move: the card says so, and offers to send its last work. */
    val removed: Boolean = store?.removedFromMove() ?: card.removed
}

/**
 * Your moves: a card for each move on the phone, with its name, this phone's role in it, the
 * days to go, how far it has got and when it last synced; a dot on one that needs its person.
 * Tapping a card opens that move. Each card can be backed up, archived, removed from this phone
 * (first saying what no other phone has, and offering to sync or back up), or deleted while
 * nobody else has ever joined it. Start or join another from here; finished moves fold away
 * under "Earlier moves".
 */
@Composable
fun MovesScreen() {
    val moves = LocalMoves.current
    val store = LocalStore.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val state = appState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { moves?.refresh() }
    val cards = moves?.cards?.collectAsState()?.value
    val here = store.moveId
    val summaries = remember(cards) {
        (cards ?: listOf(MoveCard(moveId = here, folder = "", name = store.data.value.move.name))).map { c ->
            val mine = c.matches(here)
            MoveSummary(c, if (mine) store else moves?.store(c.moveId), mine)
        }
    }
    var acting by remember { mutableStateOf<MoveSummary?>(null) }
    var removing by remember { mutableStateOf<Pair<MoveSummary, Removal>?>(null) }
    var deleting by remember { mutableStateOf<MoveSummary?>(null) }
    var working by remember { mutableStateOf(false) }
    var said by remember { mutableStateOf<SyncReport?>(null) }
    // Send update and Open update from a card (ApartParts).
    val updates = rememberUpdateActions(store)

    // Backing up a move from its card: the file is chosen, then written away from the screen.
    var backingUp by rememberSaveable { mutableStateOf<String?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val id = backingUp
        backingUp = null
        val s = id?.let { if (store.identity.value.matches(it)) store else moves?.store(it) }
        if (uri == null || s == null) return@rememberLauncherForActivityResult
        working = true
        scope.launch(Dispatchers.Main) {
            val error = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(uri)!!.use { Backup.export(s, it) }
                    null
                } catch (e: Exception) {
                    e.message ?: "The file could not be written."
                }
            }
            working = false
            if (error == null) s.setDevice { it.copy(lastBackupAt = System.currentTimeMillis()) }
            said = if (error == null) SyncReport(true, "Backup saved", s.data.value.move.name.ifBlank { "The move" } + " is in the file you chose.")
            else SyncReport(false, "Backup failed", error)
        }
    }
    fun backUp(s: MoveSummary) {
        backingUp = s.card.moveId
        save.launch("housemove-" + s.name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "move" } + "-${LocalDate.now()}.zip")
    }

    fun open(s: MoveSummary) {
        if (s.here) {
            nav.navigate("home") { popUpTo(0) { inclusive = true } }
            return
        }
        moves?.open(s.card.moveId)
    }

    Screen("Your moves") {
        if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
        ScrollColumn {
            said?.let { SyncReportCard(it) }
            updates.report?.let { SyncReportCard(it) }
            val (earlier, now) = summaries.partition { it.earlier && !it.here }
            now.forEach { s -> MoveCardView(s, onOpen = { open(s) }, onMore = if (moves != null || s.here) ({ acting = s }) else null) }
            if (moves != null) {
                ActionPill(Icons.Default.Add, "Start a new move", "You set it up, then invite the others") {
                    state.returnTo = here
                    moves.create()
                }
            }
            ActionPill(Icons.Default.QrCodeScanner, "Join a move", "Scan the invite on their phone") { nav.open("join") }
            if (earlier.isNotEmpty()) {
                Fold("Earlier moves", Icons.Default.History, summary = earlier.joinToString(" · ") { it.name }) {
                    earlier.forEach { s -> MoveCardView(s, onOpen = { open(s) }, onMore = { acting = s }) }
                }
            }
            Text(
                "Lost your phone? Restore a backup", style = MaterialTheme.typography.labelLarge, color = Night.MintPale, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.CenterHorizontally).clip(RoundedCornerShape(14.dp))
                    .clickable(role = SemanticsRole.Button) { nav.open("lost") }.heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 14.dp),
            )
        }
    }

    // What can be done with one move.
    acting?.let { s ->
        val mayBackUp = s.mayBackUp
        val canDelete = moves?.whyNotDelete(s.card.moveId) == null && moves != null
        GlassDialog(
            s.name, onDismiss = { acting = null }, icon = s.kind.icon(),
            text = listOfNotNull(s.role?.takeIf { s.shared }?.let { "You are ${it.withArticle()}." }, s.days).joinToString(" ").ifBlank { null },
            buttons = { DialogCancel({ acting = null }, "Close") },
        ) {
            // Taken out of the move: its last work goes to an Organiser as an update, and then the move goes.
            if (s.removed && s.store != null && moves != null) {
                MenuButton(Icons.AutoMirrored.Filled.Send, "Send my work to an Organiser") {
                    acting = null
                    updates.send(s.store, organisersOnly = true) { moves.remove(s.card.moveId, evenIfUnshared = true) }
                }
            } else if (s.shared && s.store != null) {
                // Not together: what this phone has, as an update through any messaging app.
                MenuButton(Icons.AutoMirrored.Filled.Send, "Send update") { acting = null; updates.send(s.store) }
                MenuButton(Icons.Default.FileOpen, "Open update") { acting = null; updates.open() }
            }
            // Helpers make no backups: what Helpers are not sent is not on their phone to save.
            if (mayBackUp) MenuButton(Icons.Default.SaveAlt, "Back up") { acting = null; backUp(s) }
            if (moves != null) {
                if (s.card.archived) MenuButton(Icons.Default.Unarchive, "Bring back from Earlier moves") { acting = null; moves.archive(s.card.moveId, false) }
                else MenuButton(Icons.Default.Archive, "Archive") { acting = null; moves.archive(s.card.moveId, true) }
                MenuButton(Icons.Default.PhonelinkErase, "Remove from this phone") {
                    acting = null
                    moves.removing(s.card.moveId)?.let { removing = s to it }
                }
                if (canDelete) MenuButton(Icons.Default.DeleteForever, "Delete", danger = true) { acting = null; deleting = s }
            }
        }
    }

    removing?.let { (s, r) ->
        val m = moves ?: return@let
        val close = { removing = null }
        when {
            // The last Organiser of a move others are in: it stays.
            r.refusal != null -> GlassDialog(
                "${s.name} stays on this phone", onDismiss = close, icon = Icons.Default.Shield, text = r.refusal,
                buttons = {
                    DialogCancel(close, "OK")
                    if (s.here) PillButton("People", onClick = { close(); nav.open("people") }, icon = Icons.Default.Groups)
                },
            )
            r.unshared.nothing -> ConfirmDialog(
                "Remove ${s.name} from this phone?",
                "Every other phone in it keeps it. You can join it again with an invite.",
                confirm = "Remove", onConfirm = { m.remove(s.card.moveId).okOrSay(context) }, onDismiss = close,
            )
            else -> GlassDialog(
                "Not on any other phone yet", onDismiss = close, icon = Icons.Default.CloudOff,
                text = unsharedText(r.unshared),
                buttons = { DialogCancel(close) },
            ) {
                // Sync is done from inside the move: it opens there.
                MenuButton(Icons.Default.Sync, "Sync first") {
                    close()
                    if (s.here) nav.open("sync") else {
                        state.pendingRoute = "sync"
                        m.open(s.card.moveId)
                    }
                }
                // An update reaches the Organisers and Deciders when the phones cannot meet; then the move can go.
                if (s.store != null && s.store.updateRecipients().isNotEmpty()) {
                    MenuButton(Icons.AutoMirrored.Filled.Send, "Send update first") {
                        close()
                        updates.send(s.store)
                    }
                }
                if (s.mayBackUp) MenuButton(Icons.Default.SaveAlt, "Back up first") { close(); backUp(s) }
                MenuButton(Icons.Default.PhonelinkErase, "Remove anyway", danger = true) {
                    close()
                    m.remove(s.card.moveId, evenIfUnshared = true).okOrSay(context)
                }
            }
        }
    }

    UpdateDialogs(updates)

    deleting?.let { s ->
        ConfirmDialog(
            "Delete ${s.name}?",
            "Nobody else has joined it, so this is its only copy: everything in it goes, photos too. This cannot be undone.",
            confirm = "Delete", onConfirm = { moves?.delete(s.card.moveId)?.okOrSay(context) }, onDismiss = { deleting = null },
        )
    }
}

/** What removing a move would lose, in a sentence: how many changes and photos, and some of what they are. */
private fun unsharedText(u: Unshared): String {
    val parts = listOfNotNull(
        if (u.changes > 0) countOf(u.changes, "change") else null,
        if (u.photos > 0) countOf(u.photos, "photo") else null,
    ).joinToString(" and ")
    val some = if (u.things.isEmpty()) "" else ", such as " + u.things.take(3).joinToString(", ")
    return "This phone has $parts that no other phone has yet$some. Sync, send an update or back up first, so nothing is lost."
}

/** A row of a list of things to do with a move: a picture and the words, the full width, 48dp at least. */
@Composable
private fun MenuButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, danger: Boolean = false, onClick: () -> Unit) {
    PillButton(label, onClick = onClick, icon = icon, primary = false, tone = if (danger) GlassTone.Red else GlassTone.Plain, modifier = Modifier.fillMaxWidth())
}

/**
 * One move's card: its kind's picture (with a dot when it needs its person), its name, this
 * phone's role, the days to go, how far it has got and when it last synced. The move open now
 * says so. [onMore] opens what can be done with it.
 */
@Composable
private fun MoveCardView(s: MoveSummary, onOpen: () -> Unit, onMore: (() -> Unit)?) {
    GlassCard(Modifier.fillMaxWidth(), onClick = onOpen, shape = RoundedCornerShape(26.dp), sheen = s.here, tone = if (s.here) GlassTone.Mint else GlassTone.Plain) {
        Row(Modifier.padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                GlassOrb(s.kind.icon(), null, size = 48.dp)
                if (s.needsYou) NeedsYouDot(Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = 2.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(s.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (s.here) GlassChip("Open now", Modifier.align(Alignment.CenterVertically), tone = GlassTone.Mint)
                    if (s.needsYou) GlassChip("Needs you", Modifier.align(Alignment.CenterVertically), tone = GlassTone.Amber)
                    if (s.removed) GlassChip("No longer in it", Modifier.align(Alignment.CenterVertically), tone = GlassTone.Amber)
                    if (!s.setUp) GlassChip("Not set up yet", Modifier.align(Alignment.CenterVertically), tone = GlassTone.Amber)
                }
                Meter(s.done, Modifier.fillMaxWidth().padding(top = 2.dp), height = 6.dp)
                Text(
                    // "Decider · 9 days to go · 40% done · synced 2 h ago"; a move of one person says when it was last backed up.
                    listOfNotNull(
                        s.role?.label?.takeIf { s.shared },
                        s.days,
                        "${(s.done * 100).toInt()}% done",
                        when {
                            s.shared -> if (s.synced == 0L) "not synced yet" else "synced ${agoText(s.synced)}"
                            s.backedUp == 0L -> "not backed up yet"
                            else -> "backed up ${agoText(s.backedUp)}"
                        },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = Night.TextDim,
                )
            }
            if (onMore != null) GlassOrb(Icons.Default.MoreVert, "Back up, archive or remove ${s.name}", size = 40.dp, onClick = onMore)
        }
    }
}
