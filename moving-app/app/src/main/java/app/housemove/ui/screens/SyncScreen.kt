package app.housemove.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.housemove.data.Action
import app.housemove.data.Backup
import app.housemove.pdf.Share
import app.housemove.pdf.qrBitmap
import app.housemove.sync.WifiSync
import app.housemove.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * Phones meeting, and backups. [code] is a sync code or invite scanned somewhere else in
 * the app (the general scanner): it is used straight away, so nobody scans twice.
 */
@Composable
fun SyncScreen(code: String? = null) {
    val store = LocalStore.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val device = deviceInfo()
    val data = appData()
    val scope = rememberCoroutineScope()
    val addressesNow = LocalSyncAddresses.current
    val joiner = rememberJoiner()
    val state = appState()

    /** Use a scanned code: a sync code syncs at once; an invite made for a person goes to the join screen, which asks first. */
    fun use(text: String): Boolean {
        val invite = WifiSync.Invite.parse(text) ?: return false
        if (invite.personId == null) return joiner.join(text)
        state.pendingInvite = text.trim()
        nav.open("join")
        return true
    }

    var host by remember { mutableStateOf<WifiSync.Host?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    /** What a hosted sync or a backup led to; a scan's result is in [joiner]. Only the latest of the two is shown. */
    var note by remember { mutableStateOf<SyncReport?>(null) }
    val busy = working || joiner.busy

    fun say(report: SyncReport?) {
        joiner.report = null
        note = report
    }

    DisposableEffect(Unit) { onDispose { host?.stop() } }

    var codeUsed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(code) {
        if (code != null && !codeUsed) {
            codeUsed = true
            use(code)
        }
    }

    val saveBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch {
            working = true
            val error = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(uri)!!.use { Backup.export(store, it) }
                    null
                } catch (e: Exception) {
                    e.message ?: "unknown error"
                }
            }
            working = false
            if (error == null) store.setDevice { it.copy(lastBackupAt = System.currentTimeMillis()) }
            say(if (error == null) SyncReport(true, "Backup saved", "") else SyncReport(false, "Backup failed", error))
        }
    }
    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            working = true
            val report = withContext(Dispatchers.IO) {
                try {
                    val r = context.contentResolver.openInputStream(uri)!!.use { Backup.import(store, it) }
                    SyncReport(true, "Backup merged in", "${r.photos} new photo(s) added.")
                } catch (e: Exception) {
                    // Includes the Store's own reason when this person may not restore a backup.
                    SyncReport(false, "Could not restore that file", e.message ?: "")
                }
            }
            say(report)
            working = false
        }
    }

    Screen("Sync and backup") {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        ScrollColumn {
            (joiner.report ?: note)?.let { SyncReportCard(it) }

            // Who this phone is, once there is more than one person to tell apart.
            val mine = myPerson()
            if (data.shared() && mine != null) {
                PersonPill(
                    mine, "This phone is ${mine.shownName()}", if (allowed(Action.MANAGE_PEOPLE)) "Add a new phone under People" else "Everyone on the move",
                    onClick = { nav.open("people") },
                )
            }

            // Phones of a move with others in it sync by themselves on the same Wi-Fi; the code below stays for the rest.
            if (data.shared()) AutoSyncSection()
            // And away from it: through the internet mailbox on the family laptop (2.5).
            if (data.shared()) MailboxSection()

            val h = host
            // With no Wi-Fi there is no code to show or scan: said first, in the sync's own words, until it is back.
            var looked by remember { mutableIntStateOf(0) }
            val problem = remember(looked, h) { if (addressesNow().isEmpty()) WifiSync.NO_NETWORK else null }
            if (h == null && problem != null) {
                Section(tone = GlassTone.Red) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Default.WifiOff, contentDescription = null, tint = Night.RedPale)
                        Text(problem, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    }
                    GlassChip("Check again", icon = Icons.Default.Refresh, onClick = { looked++ })
                }
            }
            if (h == null) {
                // Two phones, changes going both ways: the picture is the explanation.
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    GlassOrb(Icons.Default.PhoneAndroid, null, size = 64.dp)
                    Icon(Icons.Default.SyncAlt, contentDescription = null, tint = Night.MintLight, modifier = Modifier.padding(horizontal = 14.dp).size(30.dp))
                    GlassOrb(Icons.Default.PhoneAndroid, null, size = 64.dp)
                }
                BigAction(
                    Icons.Default.QrCode2, "Show sync code",
                    onClick = {
                        try {
                            // Only a move with a single person lets an unknown phone in by this code; see Joiner.
                            host = WifiSync.host(store, letNewPhonesIn = store.isAlone()) { r ->
                                scope.launch {
                                    if (r.listsSynced()) store.setDevice { it.copy(lastSyncAt = System.currentTimeMillis(), lastSyncWith = r.peerName) }
                                    say(syncReport(r.copy(joinedAs = null), store))
                                }
                            }
                            say(null)
                        } catch (_: Exception) {
                            say(SyncReport(false, "Could not start", "This phone could not start showing its code. Try again, or restart HouseMove."))
                        }
                    },
                )
                BigAction(Icons.Default.QrCodeScanner, "Scan sync code", onClick = { scanning = true }, primary = false, enabled = !busy)
            } else {
                val addresses = remember(h) { addressesNow() }
                if (addresses.isEmpty()) {
                    Section(tone = GlassTone.Red) { Text(WifiSync.NO_NETWORK) }
                } else {
                    val code = remember(h) { h.invite(addresses).encode() }
                    val qr = remember(code) { qrBitmap(code, 640).asImageBitmap() }
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Image(
                            qr, contentDescription = "Sync code",
                            modifier = Modifier.size(260.dp).clip(RoundedCornerShape(20.dp)).background(Color.White).padding(10.dp)
                                .semantics { this[InviteText] = code },
                        )
                        Hint("On the other phone: Sync and backup → Scan sync code")
                    }
                }
                BigAction(Icons.Default.Close, "Stop showing the code", onClick = { h.stop(); host = null }, primary = false)
            }
            // A move with others in it says when it last synced, and with whom, above (AutoSyncSection).
            if (!data.shared()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    if (device.lastSyncAt == 0L) Icons.Default.SyncProblem else Icons.Default.CheckCircle, contentDescription = null,
                    tint = if (device.lastSyncAt == 0L) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(18.dp),
                )
                Hint(
                    (if (device.lastSyncAt == 0L) "Not synced yet" else "Last synced ${agoText(device.lastSyncAt)}") + " · both phones on the same Wi-Fi or hotspot",
                )
            }

            // Phones that are not together: update files, and asking family who never visit.
            WorkingApartSection()

            Section("Backup") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Backup, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Hint(
                        (if (device.lastBackupAt == 0L) "No backup yet" else "Last backup ${agoText(device.lastBackupAt)}") +
                            " · one file with everything, ${data.livePhotos.size} photos",
                    )
                }
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton(
                        "Save backup", onClick = { saveBackup.launch("housemove-backup-${LocalDate.now()}.zip") },
                        modifier = Modifier.weight(1f).fillMaxHeight(), icon = Icons.Default.SaveAlt, enabled = !busy,
                    )
                    PillButton(
                        "Share backup", modifier = Modifier.weight(1f).fillMaxHeight(), icon = Icons.Default.Share, primary = false, enabled = !busy,
                        onClick = {
                            scope.launch {
                                working = true
                                val file = withContext(Dispatchers.IO) {
                                    try {
                                        Share.outFile(context, "housemove-backup-${LocalDate.now()}.zip").also { f ->
                                            f.outputStream().use { Backup.export(store, it) }
                                        }
                                    } catch (_: Exception) {
                                        null
                                    }
                                }
                                working = false
                                if (file != null) Share.send(context, file, "application/zip") else say(SyncReport(false, "Could not create the backup", ""))
                            }
                        },
                    )
                }
                // Restoring is for Organisers; nobody else is shown a button that would only refuse.
                if (allowed(Action.RESTORE_BACKUP)) {
                    PillButton(
                        "Restore or merge a backup file", onClick = { openBackup.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                        modifier = Modifier.fillMaxWidth(), icon = Icons.Default.Restore, primary = false, enabled = !busy,
                    )
                }
                // The daily small backup of a move with a single Organiser: where it goes.
                DailyBackupRow()
            }

            Fold("How syncing works", Icons.Default.HelpOutline) {
                Text("Each phone keeps its own full copy and works without internet. Syncing swaps the changes both ways, photos included.")
                Text("On the same Wi-Fi, the phones of a move sync by themselves while HouseMove is open. The code is for the first sync after an update, phones with an older HouseMove, and Wi-Fi that keeps phones apart (a phone's hotspot always works).")
                Text("If both phones changed the same thing, the most recent change wins.")
                Text("A new phone joins with an invite from People. All phones must be on the same version of the app.")
                Text("A phone with no HouseMove, or an older one, can get this phone's copy: Quick Share sends it without internet.")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton("Share the app", onClick = { shareTheApp(context, scope) }, icon = Icons.Default.Share, primary = false)
                    DownloadCodeButton()
                }
            }
        }
    }

    if (scanning) {
        ScanDialog(
            title = "Scan the code or invite on the other phone",
            raw = true,
            // A sync code is far too long to type unless there is no other way: the field waits behind a chip.
            typingFolded = true,
            onClose = { scanning = false },
            onCode = { text ->
                note = null
                if (use(text)) scanning = false
                else toast(context, "That is not a sync code. On the other phone tap \"Show sync code\".")
            },
        )
    }
}
