package app.housemove.ui.iphone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.housemove.data.Store
import app.housemove.data.Action
import app.housemove.sync.AutoSync
import app.housemove.sync.MailboxSync
import androidx.compose.material3.LinearProgressIndicator
import app.housemove.ui.LocalPlatform
import app.housemove.ui.Night
import app.housemove.ui.Screen
import app.housemove.ui.screens.BackupSection
import app.housemove.ui.screens.SyncReport
import app.housemove.ui.screens.SyncReportCard
import app.housemove.ui.screens.WorkingApartSection
import app.housemove.ui.screens.unsharedWork
import app.housemove.ui.icons.filled.PhonelinkErase
import app.housemove.ui.icons.Icons
import app.housemove.ui.icons.filled.CheckCircle
import app.housemove.ui.icons.filled.CloudOff
import app.housemove.ui.icons.filled.Close
import app.housemove.ui.icons.filled.Description
import app.housemove.ui.icons.filled.QrCode2
import app.housemove.ui.icons.filled.Settings
import app.housemove.ui.icons.filled.Sync
import app.housemove.ui.icons.filled.WifiOff
import kotlinx.coroutines.delay

/*
 * Getting fresh data: a sync with the Android phone, as two Android phones sync. One shows a sync code and the other
 * scans it. The iPhone shows its code for the Android phone to scan (More, Sync and backup, Scan sync code), or takes
 * the Android phone's code: scanned here where it has a camera (FreshScans.kt), through the iPhone's Camera (Open in
 * HouseMove), or pasted. In the shared app this is the iPhone's Sync and backup (LocalSyncScreen), and where the move
 * is left.
 */

/** The iPhone's Sync and backup: [code] is a sync code that came with the route, used once. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FreshScreen(model: AppModel, store: Store, code: String? = null) {
    val device by store.device.collectAsState()
    val fresh = model.fresh
    var typed by remember { mutableStateOf("") }
    fun go() {
        val text = typed
        typed = ""
        model.use(text)
    }
    var codeUsed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(code) {
        if (code != null && !codeUsed) {
            codeUsed = true
            model.use(code)
        }
    }
    // Leaving the screen stops showing this iPhone's code, as Back did.
    DisposableEffect(Unit) { onDispose { model.stopCode() } }
    Screen("Get fresh data") {
        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Syncing by itself first: with it on, there is usually nothing else to do here.
            if (fresh !is Fresh.Showing) model.autoSync?.let { AutoPanel(model, it, device.lastSyncWith, device.lastSyncAt) }
            // Then the internet mailbox, for the phones that are not on this Wi-Fi.
            if (fresh !is Fresh.Showing) MailboxPanel(model, store)
            // While the code shows, it and its steps come first.
            if (fresh !is Fresh.Showing) Hint("Changed something on the Android phone? Bring it to this iPhone. Both phones need to be on the same Wi-Fi.")
            val report = when (fresh) {
                is Fresh.Idle -> fresh.report
                is Fresh.Showing -> fresh.report
                Fresh.Syncing -> null
            }
            report?.let { ReportPanel(it, onSettings = { model.openSettings() }, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }

            when (fresh) {
                Fresh.Syncing -> Row(
                    Modifier.fillMaxWidth().padding(vertical = 12.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(color = Night.Mint, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.width(14.dp))
                    Text("Syncing with the Android phone…", style = MaterialTheme.typography.titleMedium, color = Night.Text)
                }
                is Fresh.Showing -> {
                    fresh.warning?.let { ReportPanel(it, onSettings = { model.openSettings() }) }
                    Heading("This iPhone's sync code")
                    QrCode(
                        fresh.code, "Sync code for the Android phone to scan",
                        Modifier.align(Alignment.CenterHorizontally).size(260.dp).testTag("sync-code"),
                    )
                    Panel {
                        Text(
                            "On the Android phone, tap More, then Sync and backup, then Scan sync code. Point it at this code.",
                            style = MaterialTheme.typography.bodyLarge, color = Night.Text,
                        )
                        Hint("Keep this screen open until it says Up to date.")
                    }
                    SmallButton("Stop showing the code", Icons.Filled.Close, onClick = { model.stopCode() })
                }
                is Fresh.Idle -> {
                    Heading("Show this iPhone's code")
                    Hint("The Android phone scans it with its camera.")
                    BigButton("Show this iPhone's sync code", Icons.Filled.QrCode2, onClick = { model.showCode() })
                }
            }

            if (fresh !is Fresh.Showing) {
                Heading("Or use the Android phone's code", Modifier.padding(top = 8.dp))
                ScanSyncCode(model)
                Panel {
                    Text(
                        if (LocalPlatform.current.camera) {
                            "On the Android phone, tap More, then Sync and backup, then Show sync code. Scan it with the button above, or point this iPhone's Camera at it and tap Open in HouseMove."
                        } else {
                            "On the Android phone, tap More, then Sync and backup, then Show sync code. Point this iPhone's Camera at it and tap Open in HouseMove."
                        },
                        style = MaterialTheme.typography.bodyLarge, color = Night.Text,
                    )
                    Hint("Or copy the code and paste it here.")
                }
                // Demo mode only (the UI tests): the sync code of the sample move this app hosts inside itself.
                model.demo?.let { demo ->
                    Panel(tone = Tone.Amber) {
                        Text("Demo mode", style = MaterialTheme.typography.titleMedium, color = Night.Text)
                        Hint("The sample move's sync code:")
                        Text(demo.syncCode, style = MaterialTheme.typography.bodySmall, color = Night.Text, modifier = Modifier.testTag("demo-sync"))
                    }
                }
                CodeField(typed, { typed = it }, "Sync code", onGo = { go() }, modifier = Modifier.testTag("sync-field").widthIn(min = 120.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SmallButton("Paste", Icons.Filled.Description, onClick = {
                        val text = model.paste()
                        if (text == null) model.noteNothingToPaste() else model.use(text)
                    })
                    SmallButton("Sync", Icons.Filled.Sync, onClick = { go() }, tone = Tone.Mint)
                }
            }
            Hint(if (device.lastSyncAt == 0L) "Not synced yet." else "Last synced ${Words.ago(device.lastSyncAt)}.")

            // Files (step 3b), as on Android's Sync and backup: update files and Ask for decisions for phones that are
            // not together, and the backup, through the share sheet and Files. Where files cannot be handed on, none of it.
            if (LocalPlatform.current.shareFiles) FilesPart()

            // Leaving the move on this iPhone: the Android phone keeps it.
            Heading("This iPhone", Modifier.padding(top = 8.dp))
            BigButton(
                "Leave this move on this iPhone", Icons.Filled.PhonelinkErase, onClick = { model.askToLeave(true) },
                primary = false, tone = Tone.Red, enabled = !model.busy,
            )
            Hint(model.buildLabel, Modifier.fillMaxWidth(), center = true)
        }
    }
    if (model.askingToLeave) {
        val unshared = remember(store) { model.moves.removing(store.moveId)?.unshared }
        LeaveDialog(
            store.data.value.move.name.ifBlank { "this move" }, unshared?.takeIf { !it.nothing }?.let { unsharedWork(it) },
            onLeave = { model.leave() }, onKeep = { model.askToLeave(false) },
        )
    }
}

/**
 * Android's Working apart and Backup sections (ApartParts.kt, BackupParts.kt), on the iPhone's Sync and backup: Send update
 * and Open update, Ask for decisions, and saving, sharing and (for an Organiser) merging a backup. What came of a backup
 * is said above it.
 */
@Composable
private fun FilesPart() {
    var note by remember { mutableStateOf<SyncReport?>(null) }
    var working by remember { mutableStateOf(false) }
    WorkingApartSection()
    if (working) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Night.Mint)
    note?.let { SyncReportCard(it) }
    BackupSection(working, onWorking = { working = it }, say = { note = it })
}

/**
 * "Leave … on this iPhone?". The Android phone keeps the move; nothing is sent to it. [unshared] says what this iPhone
 * has that no other phone has yet ("3 changes and 1 photo"), which goes with it.
 */
@Composable
private fun LeaveDialog(move: String, unshared: String?, onLeave: () -> Unit, onKeep: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeep,
        containerColor = Night.Panel,
        titleContentColor = Night.Text,
        textContentColor = Night.TextSoft,
        title = { Text("Leave $move on this iPhone?", style = MaterialTheme.typography.titleLarge) },
        text = {
            // Scrolls at the largest text sizes, so the buttons below stay on the screen.
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "This iPhone forgets the move: its rooms, items and boxes. The Android phone keeps all of it, and nothing is sent to it. You can join again later with a new invite.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (unshared != null) {
                    Text(
                        "This iPhone has $unshared that no other phone has yet. They go with it. Get fresh data with the Android phone first to keep them.",
                        style = MaterialTheme.typography.bodyLarge, color = Night.AmberPale,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onLeave, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Leave", color = Night.Red, style = MaterialTheme.typography.titleMedium)
            }
        },
        dismissButton = {
            TextButton(onClick = onKeep, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Keep it", color = Night.MintLight, style = MaterialTheme.typography.titleMedium)
            }
        },
    )
}

/**
 * Syncing by itself (design/auto-sync.md), as Android's Sync and backup shows it: one line ("Up to date with Asha's phone
 * · 2 min ago", "Looking for the family's phones on this Wi-Fi"), a hint when there is something to say, and the switch.
 * [lastWith] and [lastAt] are the move's last sync, by code or by itself.
 */
@Composable
private fun AutoPanel(model: AppModel, engine: AutoSync, lastWith: String, lastAt: Long) {
    val live by engine.status.collectAsState()
    val on = model.autoSyncOn
    val status = (if (on) live else live.copy(state = AutoSync.State.OFF)).copy(lastWith = lastWith, lastAt = lastAt)
        .let { s -> if (s.state == AutoSync.State.UP_TO_DATE && s.lastAt == 0L) s.copy(state = AutoSync.State.LOOKING) else s }
    // "2 min ago" moves on with the clock.
    var now by remember { mutableStateOf(Words.nowMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Words.nowMillis()
            delay(15_000)
        }
    }
    val tone = if (status.state == AutoSync.State.UP_TO_DATE) Tone.Mint else Tone.Plain
    Panel(Modifier.testTag("auto-sync"), tone = tone) {
        Row(Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically) {
            val icon = when (status.state) {
                AutoSync.State.UP_TO_DATE -> Icons.Filled.CheckCircle
                AutoSync.State.NO_WIFI, AutoSync.State.DENIED -> Icons.Filled.WifiOff
                else -> Icons.Filled.Sync
            }
            Icon(icon, contentDescription = null, tint = tone.accent(), modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(status.line(now), style = MaterialTheme.typography.titleMedium, color = Night.Text)
                status.hint(now)?.let { Hint(it) }
            }
        }
        if (status.state == AutoSync.State.DENIED) SmallButton("Open Settings", Icons.Filled.Settings, { model.openSettings() }, tone = Tone.Red)
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = on, role = Role.Switch, onValueChange = { model.setAutoSync(it) }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Sync by itself on Wi-Fi", style = MaterialTheme.typography.bodyLarge, color = Night.Text, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            Switch(checked = on, onCheckedChange = null, colors = SwitchDefaults.colors(checkedTrackColor = Night.Mint, checkedThumbColor = Night.OnMint))
        }
    }
}

/**
 * The internet mailbox (design/encryption.md, "Mailbox as built"), as Android's Sync and backup shows it: one line ("Up to
 * date over the internet · 2 min ago", "No internet mailbox yet", or what went wrong), a hint, and the switch. An
 * Organiser scans the mailbox laptop's code here where the iPhone has a camera (FreshScans.kt), points the Camera at it
 * and taps Open in HouseMove, or pastes it into the code field below.
 */
@Composable
private fun MailboxPanel(model: AppModel, store: Store) {
    val data by store.data.collectAsState()
    val engine = model.mailbox
    val flow = remember(engine) { engine?.status ?: kotlinx.coroutines.flow.MutableStateFlow(MailboxSync.Status(MailboxSync.State.NOT_SET_UP)) }
    val live by flow.collectAsState()
    val on = model.mailboxOn
    val status = when {
        !on -> MailboxSync.Status(MailboxSync.State.OFF)
        data.mailbox.url.isBlank() -> MailboxSync.Status(MailboxSync.State.NOT_SET_UP)
        engine == null -> MailboxSync.Status(MailboxSync.State.ALONE)
        live.state == MailboxSync.State.NOT_SET_UP || live.state == MailboxSync.State.OFF -> MailboxSync.Status(MailboxSync.State.WORKING)
        else -> live
    }
    val organiser = store.can(Action.MOVE_SETTINGS)
    var now by remember { mutableStateOf(Words.nowMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Words.nowMillis()
            delay(15_000)
        }
    }
    val tone = if (status.state == MailboxSync.State.UP_TO_DATE) Tone.Mint else Tone.Plain
    Panel(Modifier.testTag("mailbox"), tone = tone) {
        Row(Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically) {
            val icon = when (status.state) {
                MailboxSync.State.UP_TO_DATE -> Icons.Filled.CheckCircle
                MailboxSync.State.OFF, MailboxSync.State.PROBLEM -> Icons.Filled.CloudOff
                else -> Icons.Filled.Sync
            }
            Icon(icon, contentDescription = null, tint = tone.accent(), modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(status.line(now), style = MaterialTheme.typography.titleMedium, color = Night.Text)
                status.hint(now, organiser)?.let { Hint(it) }
                if (organiser && on && (status.state == MailboxSync.State.NOT_SET_UP || status.problem == MailboxSync.Problem.CODE_REFUSED)) {
                    Hint(
                        if (LocalPlatform.current.camera) "Scan the code the laptop shows, point the Camera at it and tap Open in HouseMove, or paste it below."
                        else "On this iPhone: point the Camera at the code and tap Open in HouseMove, or paste it below.",
                    )
                }
            }
        }
        if (organiser && on) ScanMailboxCode(model, hasOne = data.mailbox.url.isNotBlank())
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = on, role = Role.Switch, onValueChange = { model.setMailbox(it) }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Internet mailbox on this iPhone", style = MaterialTheme.typography.bodyLarge, color = Night.Text, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            Switch(checked = on, onCheckedChange = null, colors = SwitchDefaults.colors(checkedTrackColor = Night.Mint, checkedThumbColor = Night.OnMint))
        }
    }
}
