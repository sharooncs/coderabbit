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
import androidx.compose.ui.text.style.TextOverflow
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
            joiner.join(code)
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
                GlassPill(
                    Modifier.fillMaxWidth(), onClick = { nav.open("people") },
                    contentPadding = PaddingValues(start = 8.dp, top = 8.dp, bottom = 8.dp, end = 14.dp),
                ) {
                    PersonOrb(mine, size = 40.dp, me = true)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("This phone is ${mine.shownName()}", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (allowed(Action.MANAGE_PEOPLE)) "Add a new phone under People" else "Everyone on the move",
                            style = MaterialTheme.typography.bodySmall, color = Night.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    RoleChip(mine.role)
                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Night.TextDim)
                }
            }

            val h = host
            if (h == null) {
                // Two phones, changes going both ways: the picture is the explanation.
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.PhoneAndroid, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
                    Icon(Icons.Default.SyncAlt, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp).size(30.dp))
                    Icon(Icons.Default.PhoneAndroid, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
                }
                BigAction(
                    Icons.Default.QrCode2, "Show sync code",
                    onClick = {
                        try {
                            // Only a move with a single person lets an unknown phone in by this code; see Joiner.
                            host = WifiSync.host(store, letNewPhonesIn = store.isAlone()) { r ->
                                scope.launch {
                                    if (r.ok) store.setDevice { it.copy(lastSyncAt = System.currentTimeMillis()) }
                                    say(syncReport(r.copy(joinedAs = null), store))
                                }
                            }
                            say(null)
                        } catch (e: Exception) {
                            say(SyncReport(false, "Could not start", e.message ?: ""))
                        }
                    },
                )
                BigAction(Icons.Default.QrCodeScanner, "Scan sync code", onClick = { scanning = true }, primary = false, enabled = !busy)
            } else {
                val addresses = remember(h) { addressesNow() }
                if (addresses.isEmpty()) {
                    Section(tone = GlassTone.Red) { Text("This phone is not on a Wi-Fi network or hotspot. Connect, then try again.") }
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
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    if (device.lastSyncAt == 0L) Icons.Default.SyncProblem else Icons.Default.CheckCircle, contentDescription = null,
                    tint = if (device.lastSyncAt == 0L) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(18.dp),
                )
                Hint(
                    (if (device.lastSyncAt == 0L) "Not synced yet" else "Last synced ${agoText(device.lastSyncAt)}") + " · both phones on the same Wi-Fi or hotspot",
                )
            }

            Section("Backup") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Backup, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Hint(
                        (if (device.lastBackupAt == 0L) "No backup yet" else "Last backup ${agoText(device.lastBackupAt)}") +
                            " · one file with everything, ${data.livePhotos.size} photos",
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = !busy,
                        onClick = { saveBackup.launch("housemove-backup-${LocalDate.now()}.zip") },
                        modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                    ) { Text("Save backup") }
                    OutlinedButton(
                        enabled = !busy,
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
                        modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                    ) { Text("Share backup") }
                }
                // Restoring is for Organisers; nobody else is shown a button that would only refuse.
                if (allowed(Action.RESTORE_BACKUP)) {
                    OutlinedButton(
                        enabled = !busy,
                        onClick = { openBackup.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Restore or merge a backup file") }
                }
            }

            Fold("How syncing works", Icons.Default.HelpOutline) {
                Text("Each phone keeps its own full copy and works without internet. Syncing swaps the changes both ways, photos included.")
                Text("If both phones changed the same thing, the most recent change wins.")
                Text("A new phone joins with an invite from People. All phones must be on the same version of the app.")
            }
        }
    }

    if (scanning) {
        ScanDialog(
            title = "Scan the code or invite on the other phone",
            raw = true,
            onClose = { scanning = false },
            onCode = { text ->
                note = null
                if (joiner.join(text)) scanning = false
                else toast(context, "That is not a sync code. On the other phone tap \"Show sync code\".")
            },
        )
    }
}
