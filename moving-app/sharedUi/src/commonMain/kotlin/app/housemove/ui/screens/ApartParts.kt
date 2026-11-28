package app.housemove.ui.screens

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.housemove.data.*
import app.housemove.ui.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.housemove.data.IOException
import app.housemove.data.InputStream

/*
 * Working apart and the safety nets on screen (design 2.1, sections 4 and 5): sending and opening update files,
 * the daily small backup's folder and what Home asks for, a phone taken out of a move, "Done, thanks", marking the
 * move done, and what the phone says when it let go of a move by itself. The rules are the Store's; these only
 * ask and show.
 */

// ---- sending and opening update files --------------------------------------------------------

/** An update from someone removed from the move, which this phone's Organiser may take in as their work alone. */
class RemovedOffer(val file: PickedFile, val name: String, val sure: Boolean = false)

/** An update made the older way (format 1), which cannot show who made it: asked about before it is opened ([text]). */
class UnprovenOffer(val file: PickedFile, val text: String)

/**
 * Sending and opening update files from a screen: the file is made away from the screen and handed to the share
 * sheet; one picked to open goes into the move it is for ([Moves.openUpdate]), or into [store] when the app is
 * shown with one move on its own. [report] is what came of it, for the screen to show.
 */
class UpdateActions internal constructor(
    private val context: Platform,
    private val scope: CoroutineScope,
    private val moves: Moves?,
    private val store: Store?,
) {
    var busy by mutableStateOf(false)
        private set
    var report by mutableStateOf<SyncReport?>(null)
    var removedOffer by mutableStateOf<RemovedOffer?>(null)
    var unprovenOffer by mutableStateOf<UnprovenOffer?>(null)
    internal var picker: (() -> Unit)? = null

    /** Can this phone send an update (hand a file to the share sheet)? */
    val canSend: Boolean get() = context.shareFiles

    /** Can this phone open an update file someone sent? */
    val canOpen: Boolean get() = picker != null

    /**
     * Make an update of [s] and hand it to the share sheet: for every Organiser's and Decider's phone it knows, or
     * with [organisersOnly] for the Organisers'. [then] runs once it has been handed over.
     */
    fun send(s: Store, organisersOnly: Boolean = s.removedFromMove(), then: () -> Unit = {}) {
        if (busy) return
        busy = true
        report = null
        scope.launch(Dispatchers.Main) {
            var how: Update.Made? = null
            val made = withContext(Dispatchers.IO) {
                TransferTime.during(TransferTime.UPDATE_FILE) {
                    try {
                        val file = context.outFile(updateFileName(s))
                        how = file.outputStream().use { Update.write(s, it, organisersOnly) }
                        file to null
                    } catch (e: Exception) {
                        null to (e.message?.takeIf { it == Update.NO_RECIPIENTS } ?: Update.COULD_NOT_WRITE)
                    }
                }
            }
            busy = false
            val file = made.first
            if (file == null) {
                report = SyncReport(false, "Could not make an update", made.second ?: Update.COULD_NOT_WRITE)
                return@launch
            }
            // Sealed (2.5, format 2): each phone sees it came from this one. Otherwise, why not.
            val seal = how?.let { m ->
                when {
                    m.sealed -> " It shows them it came from this phone."
                    m.plainFor.isNotEmpty() -> " " + Update.madeTheOlderWay(m.plainFor)
                    else -> ""
                }
            } ?: ""
            report = SyncReport(true, "Update ready to send", "Choose the app to send it with. It opens on " + phonesText(s, organisersOnly) + "." + seal)
            try {
                context.share(file, Update.MIME)
            } catch (_: Exception) {
            }
            then()
        }
    }

    /** Pick an update file and open it. */
    fun open() {
        picker?.invoke()
    }

    /**
     * Open the update in [file]; with [takeInRemoved], the work of someone removed, as an Organiser may. One made the older
     * way (which cannot show who made it) is asked about first ([unprovenOffer]), and opened with [sure] once the person
     * says so.
     */
    fun openFile(file: PickedFile, takeInRemoved: Boolean = false, sure: Boolean = false) {
        busy = true
        report = null
        scope.launch(Dispatchers.Main) {
            val r = withContext(Dispatchers.IO) {
                fun read(): InputStream = file.open() ?: throw IOException("could not read")
                TransferTime.during(TransferTime.UPDATE_FILE) {
                    when {
                        moves != null -> moves.openUpdate({ read() }, takeInRemoved, askIfUnproven = !sure)
                        store != null -> try {
                            read().use { Update.open(store, it, takeInRemoved, askIfUnproven = !sure) }
                        } catch (_: Exception) {
                            Update.Opened(false, Update.NOT_AN_UPDATE)
                        }
                        else -> Update.Opened(false, Update.NOT_ON_PHONE)
                    }
                }
            }
            busy = false
            if (r.unproven) {
                unprovenOffer = UnprovenOffer(file, r.message)
                return@launch
            }
            if (r.fromRemoved != null && r.mayTakeIn && !takeInRemoved) removedOffer = RemovedOffer(file, r.fromName.ifBlank { "Their" }, sure)
            report = when {
                r.ok && r.message == Update.NOTHING_NEW -> SyncReport(true, "Nothing new", r.message)
                // Sealed by the phone it came from (format 2): the screen names that phone.
                r.ok && r.proven -> SyncReport(true, "Update from ${r.sender}", r.message)
                r.ok -> SyncReport(true, "Update taken in", r.message)
                else -> SyncReport(false, "Could not open the update", r.message)
            }
        }
    }
}

/** "maple-road-update-2026-11-22.housemove" */
private fun updateFileName(s: Store): String {
    val slug = s.data.value.move.name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(24).trim('-').ifBlank { "move" }
    return "$slug-update-${isoToday()}.${Update.EXTENSION}"
}

/** The phones an update from [s] opens on, by name: "Asha's phone and Dee's phone". */
private fun phonesText(s: Store, organisersOnly: Boolean): String {
    val names = s.updateRecipients(organisersOnly).map { (p, ph) -> ph.name.ifBlank { p.shownName() + "'s phone" } }.distinct()
    return when (names.size) {
        0 -> "no phone yet"
        1 -> names[0]
        else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
    }
}

/** Sending and opening update files from this screen ([UpdateActions]), for the move in [store] if there is one. */
@Composable
fun rememberUpdateActions(store: Store?): UpdateActions {
    val context = LocalPlatform.current
    val local = rememberCoroutineScope()
    // A send that ends by removing the move outlives the screen it was started from.
    val scope = LocalAppState.current?.scope ?: local
    val moves = LocalMoves.current
    val actions = remember(store, moves) { UpdateActions(context, scope, moves, store) }
    val pick = context.rememberOpenFile { file -> if (file != null) actions.openFile(file) }
    actions.picker = pick?.let { { it(listOf(Update.MIME, "application/octet-stream", "*/*")) } }
    return actions
}

/**
 * What sending or opening an update asks: whether to open one made the older way, which cannot show who made it; and
 * whether to take in the work of someone removed from the move.
 */
@Composable
fun UpdateDialogs(a: UpdateActions) {
    a.unprovenOffer?.let { asked ->
        val close = { a.unprovenOffer = null }
        GlassDialog(
            "Open this update?", onDismiss = close, icon = Icons.Default.HelpOutline, text = asked.text,
            buttons = {
                DialogCancel(close, "Not now")
                PillButton("Open it", onClick = { close(); a.openFile(asked.file, sure = true) }, icon = Icons.Default.FileOpen)
            },
        )
        return
    }
    val offer = a.removedOffer ?: return
    val close = { a.removedOffer = null }
    GlassDialog(
        "Take in ${offer.name}'s work?", onDismiss = close, icon = Icons.Default.PersonOff,
        text = "${offer.name} has been removed from this move. You can still take in what they did. Anything they had no right to do is turned away, as at a sync.",
        buttons = {
            DialogCancel(close, "Not now")
            PillButton("Take in their work", onClick = { close(); a.openFile(offer.file, takeInRemoved = true, sure = offer.sure) }, icon = Icons.Default.MoveToInbox)
        },
    )
}

/**
 * On the Sync screen: for phones that are not together, Send update and Open update, and Ask for decisions for
 * family who never visit. Not shown on a move of one person: there is nobody to send to.
 */
@Composable
fun WorkingApartSection() {
    val store = LocalStore.current
    val nav = LocalNav.current
    val data = appData()
    if (!data.shared()) return
    val updates = rememberUpdateActions(store)
    // Updates and the picture list are files for other apps: a phone that cannot hand files over yet leaves this out.
    if (!updates.canSend) return
    Section("Working apart") {
        if (updates.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        updates.report?.let { SyncReportCard(it) }
        Hint("Not together? Send what this phone has as an update through any messaging app. Only the move's Organisers and Deciders can open it.")
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(
                "Send update", onClick = { updates.send(store) }, icon = Icons.AutoMirrored.Filled.Send, enabled = !updates.busy,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            if (updates.canOpen) PillButton(
                "Open update", onClick = { updates.open() }, icon = Icons.Default.FileOpen, primary = false, enabled = !updates.busy,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
        if (allowed(Action.DECIDE) || allowed(Action.SUGGEST)) {
            PillButton(
                "Ask for decisions", onClick = { nav.open("askdecisions") }, icon = Icons.Default.HowToVote, primary = false,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    UpdateDialogs(updates)
}

// ---- the daily small backup ----------------------------------------------------------------

/** The folder picker for the daily small backup: the folder picked is kept, and today's backup written to it at once. */
@Composable
fun rememberBackupFolderPicker(store: Store, onDone: (Boolean) -> Unit = {}): (() -> Unit)? {
    val context = LocalPlatform.current
    val moves = LocalMoves.current
    val scope = rememberCoroutineScope()
    return context.rememberFolderPicker { folder ->
        if (folder == null) return@rememberFolderPicker
        if (!folder.keep()) {
            onDone(false)
            return@rememberFolderPicker
        }
        store.phone.updateSettings { it.copy(backupFolder = folder.saved) }
        scope.launch(Dispatchers.Main) { onDone(withContext(Dispatchers.IO) { backUpNow(context, store, moves) }) }
    }
}

/** Write [store]'s small backup to its folder now, whether or not today's is written. False when it could not. */
private fun backUpNow(context: Platform, store: Store, moves: Moves?): Boolean {
    val place = context.backupFolder(store.phone.settings.value.backupFolder) ?: return false
    val ok = DailyBackup.run(store, place, today())
    moves?.refresh()
    return ok
}

/**
 * In the Sync screen's backup section, for a move with a single Organiser on that Organiser's phone: where the daily
 * small backup goes, and the way to choose or change the folder.
 */
@Composable
fun DailyBackupRow() {
    val store = LocalStore.current
    val context = LocalPlatform.current
    val data = appData()
    val device = deviceInfo()
    val settings by store.phone.settings.collectAsState()
    val applies = remember(data, device) { DailyBackup.applies(store) }
    if (!applies) return
    val folder = remember(settings.backupFolder) { if (context.backupFolder(settings.backupFolder) == null) "" else context.backupFolderName(settings.backupFolder).ifBlank { "the folder you chose" } }
    // A phone with no folder to pick yet (the iPhone, until step 3) leaves the daily backup out.
    val pick = rememberBackupFolderPicker(store) { ok -> toast(context, if (ok) "Backed up to the folder." else "That folder cannot be written to. Choose another.") } ?: return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Default.EventRepeat, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Hint(
            if (folder.isEmpty()) "A small backup every day: choose a folder once (Drive, Documents or a USB stick)."
            else "A small backup every day to $folder" + if (device.dailyBackupDay == today()) ", done today." else ".",
            Modifier.weight(1f),
        )
    }
    PillButton(if (folder.isEmpty()) "Choose a folder" else "Change the folder", onClick = pick, icon = Icons.Default.CreateNewFolder, primary = false, modifier = Modifier.fillMaxWidth())
}

/**
 * On the Organiser's Home, for a move with a single Organiser: a nudge when the last backup is over a week old (or
 * there has never been one), and a few days before move day the question again: "Add a second Organiser?".
 */
@Composable
fun SafetyNudges() {
    val store = LocalStore.current
    val nav = LocalNav.current
    val context = LocalPlatform.current
    val moves = LocalMoves.current
    val data = appData()
    val device = deviceInfo()
    val settings by store.phone.settings.collectAsState()
    val hasPlace = remember(settings.backupFolder) { context.backupFolder(settings.backupFolder) != null }
    val n = remember(data, device, hasPlace) { DailyBackup.nudges(store, today(), nowMillis(), hasPlace) }
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    val pick = rememberBackupFolderPicker(store) { ok -> if (!ok) toast(context, "That folder cannot be written to. Choose another.") }
    // The backup nudge needs a folder to back up to: a phone that has none yet (the iPhone, until step 3) does not show it.
    if (n.backup && pick != null) {
        Section(tone = GlassTone.Amber) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassOrb(Icons.Default.Backup, null, size = 40.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        when {
                            n.backupDays != null -> "The last backup is ${countOf(n.backupDays!!.toInt(), "day")} old"
                            hasPlace -> "No backup yet"
                            else -> "Keep a small backup each day"
                        },
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        if (hasPlace) "The folder may be full or gone. Back up now, or choose another folder."
                        else "Choose a folder once: Drive, Documents or a USB stick. A small copy of the move goes there every day, so it never depends on this phone alone.",
                        style = MaterialTheme.typography.bodySmall, color = Night.TextSoft,
                    )
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton(if (hasPlace) "Back up now" else "Choose a folder", enabled = !working, icon = if (hasPlace) Icons.Default.Backup else Icons.Default.CreateNewFolder, onClick = {
                    if (!hasPlace) pick.invoke() else {
                        working = true
                        scope.launch(Dispatchers.Main) {
                            val ok = withContext(Dispatchers.IO) { backUpNow(context, store, moves) }
                            working = false
                            toast(context, if (ok) "Backed up." else "That folder cannot be written to. Choose another.")
                        }
                    }
                })
                if (hasPlace) PillButton("Choose another folder", onClick = pick, primary = false)
            }
        }
    }
    if (n.secondOrganiser) {
        Section(tone = GlassTone.Amber) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassOrb(Icons.Default.GroupAdd, null, size = 40.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Add a second Organiser?", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Move day is close. With a second Organiser the move goes on even if this phone is lost.",
                        style = MaterialTheme.typography.bodySmall, color = Night.TextSoft,
                    )
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("Add an Organiser", onClick = { nav.open("people/add") }, icon = Icons.Default.PersonAdd)
                PillButton("Not now", onClick = { store.declineSecondOrganiser() }, primary = false)
            }
        }
    }
}

// ---- taken out of a move ---------------------------------------------------------------------

/**
 * On Home for a phone taken out of its move: it says so, offers to send what no Organiser has yet to an Organiser as
 * an update, and then takes the move off this phone. With nothing unsent, it offers to take it off at once.
 */
@Composable
fun RemovedBanner(modifier: Modifier = Modifier) {
    val store = LocalStore.current
    val moves = LocalMoves.current
    val context = LocalPlatform.current
    val data = appData()
    val device = deviceInfo()
    val removed = remember(data, device) { store.removedFromMove() }
    if (!removed) return
    val unshared = remember(data, device) { store.unsharedWithDeciders() }
    val updates = rememberUpdateActions(store)
    var confirm by remember { mutableStateOf(false) }
    val name = data.move.name.ifBlank { "this move" }
    fun leave() {
        moves?.remove(store.moveId, evenIfUnshared = true)?.okOrSay(context)
    }
    GlassCard(modifier.fillMaxWidth(), tone = GlassTone.Amber, shape = RoundedCornerShape(22.dp), sheen = true) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassOrb(Icons.Default.PersonOff, null, size = 40.dp)
                Text("You are no longer in $name", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            }
            Text(
                if (unshared.nothing) "Everything you did is on an Organiser's or Decider's phone. You can take the move off this phone."
                else "This phone has ${unsharedWork(unshared)} that no Organiser has yet. Send it to an Organiser as an update, and the move then comes off this phone.",
                style = MaterialTheme.typography.bodyMedium, color = Night.TextSoft,
            )
            updates.report?.takeIf { !it.ok }?.let { SyncReportCard(it) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!unshared.nothing && updates.canSend) {
                    PillButton("Send my work", enabled = !updates.busy, icon = Icons.AutoMirrored.Filled.Send, onClick = {
                        updates.send(store, organisersOnly = true) { leave() }
                    })
                }
                if (moves != null) {
                    PillButton(
                        if (unshared.nothing) "Remove from this phone" else "Remove without sending",
                        onClick = { if (unshared.nothing) leave() else confirm = true }, primary = unshared.nothing,
                        icon = Icons.Default.PhonelinkErase,
                    )
                }
            }
        }
    }
    if (confirm) {
        ConfirmDialog(
            "Remove without sending?", "What this phone has that no Organiser has yet goes with it.",
            confirm = "Remove", onConfirm = { leave() }, onDismiss = { confirm = false },
        )
    }
}

/** "3 changes and 2 photos" */
internal fun unsharedWork(u: Unshared): String = listOfNotNull(
    if (u.changes > 0) countOf(u.changes, "change") else null,
    if (u.photos > 0) countOf(u.photos, "photo") else null,
).joinToString(" and ").ifBlank { "nothing" }

// ---- Done, thanks; the move done --------------------------------------------------------------

/** On a Helper's page, for an Organiser: "Done, thanks Sam", and once said, what happens next. */
@Composable
fun ThanksHelper(p: Person) {
    val store = LocalStore.current
    val context = LocalPlatform.current
    val data = appData()
    val name = p.shownName()
    val thanked = remember(data, p.id) { data.roles().thankedAt(p.id) != null }
    Section {
        if (thanked) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassOrb(Icons.Default.VolunteerActivism, null, size = 40.dp)
                Text(
                    "Thanked. At $name's next sync with an Organiser or a Decider, their phone hands in all their work and lets go of the move. Then they are taken off it.",
                    style = MaterialTheme.typography.bodyMedium, color = Night.TextSoft, modifier = Modifier.weight(1f),
                )
            }
        } else {
            Text("Has $name finished? Their phone lets go of the move once an Organiser's or a Decider's phone has all their work.", style = MaterialTheme.typography.bodyMedium)
            BigAction(Icons.Default.VolunteerActivism, "Done, thanks $name", onClick = { store.thank(p.id).orSay(context) }, primary = false)
        }
    }
}

/**
 * For an Organiser, on the close-out screen of a move others are in: the move is done. A few days later Helpers'
 * phones let go of it, once an Organiser's or a Decider's phone has all their work.
 */
@Composable
fun MoveDoneRow() {
    val store = LocalStore.current
    val context = LocalPlatform.current
    val data = appData()
    if (!data.shared() || !allowed(Action.MOVE_SETTINGS)) return
    Section {
        SwitchRow(
            "The move is done", data.move.doneDay != null, { done -> store.markDone(done).okOrSay(context) },
            sub = "Helpers' phones let go of it $HELPER_COPY_DAYS days later, once an Organiser or Decider has all their work.",
        )
    }
}

// ---- Lost your phone? --------------------------------------------------------------------------

/** One daily backup found in a folder, as the lost-phone screen lists it. */
private class FolderBackup(val place: BackupPlace, val name: String, val move: String, val day: Long)

/**
 * On "Lost your phone?": choose the folder the daily small backup went to, and restore the newest backup of a move
 * in it ([onRestore] is given a way to read it, through the restore rules). The folder is kept, so this phone goes on
 * writing the daily backup there.
 */
@Composable
fun DailyBackupChooser(working: Boolean, onRestore: (() -> InputStream) -> Unit) {
    val context = LocalPlatform.current
    val moves = LocalMoves.current
    val scope = rememberCoroutineScope()
    var found by remember { mutableStateOf<List<FolderBackup>?>(null) }
    var looking by remember { mutableStateOf(false) }
    val pick = context.rememberFolderPicker { folder ->
        if (folder == null) return@rememberFolderPicker
        if (folder.keep()) moves?.phone?.updateSettings { it.copy(backupFolder = folder.saved) }
        looking = true
        scope.launch(Dispatchers.Main) {
            found = withContext(Dispatchers.IO) {
                try {
                    val place = folder.place()
                    DailyBackup.newestOfEach(place).map { f ->
                        val move = try {
                            place.read(f.name).use { Backup.peek(it).moveName }
                        } catch (_: Exception) {
                            ""
                        }
                        FolderBackup(place, f.name, move.ifBlank { "A move" }, f.day)
                    }
                } catch (_: Exception) {
                    emptyList()
                }
            }
            looking = false
        }
    }
    // A phone with no folder to pick yet (the iPhone, until step 3) has no daily backup to restore from.
    if (pick == null) return
    BigAction(Icons.Default.Folder, "Choose the backup folder", onClick = { pick() }, enabled = !working && !looking)
    if (looking) LinearProgressIndicator(Modifier.fillMaxWidth())
    val list = found ?: return
    if (list.isEmpty()) {
        Hint("There is no daily backup in that folder. Choose the folder HouseMove saved to, or a backup file.")
        return
    }
    Section("Daily backups in that folder") {
        list.forEach { b ->
            MenuRow(
                Icons.Default.Restore, b.move,
                sub = "Saved " + dayLongText(b.day),
                onClick = { if (!working) onRestore { b.place.read(b.name) } },
            )
        }
    }
}

// ---- said by the app itself --------------------------------------------------------------------

/** What the phone says once after letting go of a move by itself ([Moves.tidy]): a Helper thanked, or a move done. */
@Composable
fun Farewells(moves: Moves) {
    val said by moves.farewells.collectAsState()
    if (said.isEmpty()) return
    GlassDialog(
        "Taken off this phone", onDismiss = { moves.farewellsSaid() }, icon = Icons.Default.VolunteerActivism,
        text = said.joinToString("\n\n"),
        buttons = { PillButton("OK", onClick = { moves.farewellsSaid() }) },
    )
}

/** An update file another app asked this one to open: it goes into its move, and what came of it is said. */
@Composable
fun IncomingUpdates(moves: Moves) {
    val current by moves.current.collectAsState()
    IncomingUpdatesFor(current)
}

/**
 * [IncomingUpdates] for the move in [store] (the one in use; the iPhone's one move, with [LocalMoves] not given): an
 * update file opened with HouseMove goes into it, and what came of it is said.
 */
@Composable
fun IncomingUpdatesFor(store: Store?) {
    val context = LocalPlatform.current
    val incoming = context.incomingUpdate()
    val updates = rememberUpdateActions(store)
    LaunchedEffect(incoming) {
        val file = incoming ?: return@LaunchedEffect
        context.incomingUpdateTaken()
        updates.openFile(file)
    }
    if (updates.removedOffer == null) updates.report?.let { r ->
        GlassDialog(
            r.title, onDismiss = { updates.report = null }, icon = if (r.ok) Icons.Default.MoveToInbox else Icons.Default.ErrorOutline, text = r.text,
            buttons = { PillButton("OK", onClick = { updates.report = null }) },
        )
    }
    UpdateDialogs(updates)
}
