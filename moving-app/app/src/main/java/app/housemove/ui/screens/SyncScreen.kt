package app.housemove.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.housemove.data.Backup
import app.housemove.pdf.Share
import app.housemove.pdf.qrBitmap
import app.housemove.sync.WifiSync
import app.housemove.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

private fun describe(r: WifiSync.Result, myLetter: String): String = when {
    !r.ok -> "Sync failed: ${r.error}"
    else -> "Synced with ${r.peerName.ifBlank { "the other phone" }}. Photos sent ${r.photosSent}, received ${r.photosReceived}." +
        if (r.peerLetter.equals(myLetter, ignoreCase = true)) {
            "\nBoth phones use the letter $myLetter for handwritten codes. Change it on one of them in Settings."
        } else ""
}

@Composable
fun SyncScreen() {
    val store = LocalStore.current
    val context = LocalContext.current
    val device = deviceInfo()
    val data = appData()
    val scope = rememberCoroutineScope()

    var host by remember { mutableStateOf<WifiSync.Host?>(null) }
    var joining by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) { onDispose { host?.stop() } }

    fun onSynced(r: WifiSync.Result) {
        if (r.ok) store.setDevice { it.copy(lastSyncAt = System.currentTimeMillis()) }
        message = describe(r, store.device.value.letter)
    }

    val saveBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch {
            busy = true
            val error = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(uri)!!.use { Backup.export(store, it) }
                    null
                } catch (e: Exception) {
                    e.message ?: "unknown error"
                }
            }
            busy = false
            if (error == null) store.setDevice { it.copy(lastBackupAt = System.currentTimeMillis()) }
            message = if (error == null) "Backup saved." else "Backup failed: $error"
        }
    }
    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            message = withContext(Dispatchers.IO) {
                try {
                    val r = context.contentResolver.openInputStream(uri)!!.use { Backup.import(store, it) }
                    "Backup merged in. ${r.photos} new photo(s) added."
                } catch (e: Exception) {
                    "Could not read that file: ${e.message}"
                }
            }
            busy = false
        }
    }

    Screen("Sync and backup") {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        ScrollColumn {
            message?.let { m ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth()) {
                    Text(m, Modifier.padding(16.dp))
                }
            }

            Section("Sync two phones") {
                Text("Each phone keeps its own full copy and works without internet. Syncing swaps the changes both ways, including photos.")
                Hint("Both phones must be on the same Wi-Fi, or one connected to the other's hotspot. If both changed the same thing, the most recent change wins.")
                val h = host
                if (h == null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                try {
                                    host = WifiSync.host(store) { r -> scope.launch { onSynced(r) } }
                                    message = null
                                } catch (e: Exception) {
                                    message = "Could not start: ${e.message}"
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("Show sync code") }
                        OutlinedButton(onClick = { joining = true }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Scan sync code") }
                    }
                    Hint("One phone shows the code, the other scans it. Either way round gives the same result.")
                } else {
                    val addresses = remember { WifiSync.localAddresses() }
                    if (addresses.isEmpty()) {
                        Text("This phone is not on a Wi-Fi network or hotspot. Connect, then try again.", color = MaterialTheme.colorScheme.error)
                    } else {
                        val qr = remember(h) { qrBitmap(h.invite(addresses).encode(), 640).asImageBitmap() }
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Image(qr, contentDescription = "Sync code", modifier = Modifier.size(260.dp).background(Color.White).padding(8.dp))
                            Hint("On the other phone: More → Sync and backup → Scan sync code")
                        }
                    }
                    OutlinedButton(onClick = { h.stop(); host = null }) { Text("Stop showing the code") }
                }
                Hint(
                    if (device.lastSyncAt == 0L) "Not synced yet."
                    else "Last synced ${agoText(device.lastSyncAt)}.",
                )
            }

            Section("This phone") {
                TextInput(device.name, { v -> store.setDevice { it.copy(name = v) } }, "Who uses this phone?", key = "n")
                Hint("Handwritten box codes from this phone start with ${device.letter}. Each phone needs its own letter; change it in Settings.")
            }

            Section("Backup") {
                Text("A backup is one file with all the data and photos (${data.livePhotos.size} photos).")
                Hint(
                    (if (device.lastBackupAt == 0L) "No backup saved yet. " else "Last backup ${agoText(device.lastBackupAt)}. ") +
                        "Save one before move day, somewhere off this phone.",
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = !busy,
                        onClick = { saveBackup.launch("housemove-backup-${LocalDate.now()}.zip") },
                        modifier = Modifier.weight(1f),
                    ) { Text("Save backup") }
                    OutlinedButton(
                        enabled = !busy,
                        onClick = {
                            scope.launch {
                                busy = true
                                val file = withContext(Dispatchers.IO) {
                                    try {
                                        Share.outFile(context, "housemove-backup-${LocalDate.now()}.zip").also { f ->
                                            f.outputStream().use { Backup.export(store, it) }
                                        }
                                    } catch (_: Exception) {
                                        null
                                    }
                                }
                                busy = false
                                if (file != null) Share.send(context, file, "application/zip") else message = "Could not create the backup."
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Share backup") }
                }
                OutlinedButton(
                    enabled = !busy,
                    onClick = { openBackup.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                ) { Text("Restore or merge a backup file") }
                Hint("Restoring adds the backup to what is already here; nothing on this phone is removed. It also works as a way to combine phones that cannot reach the same Wi-Fi.")
            }
        }
    }

    if (joining) {
        ScanDialog(
            title = "Scan the code on the other phone",
            raw = true,
            onClose = { joining = false },
            onCode = { text ->
                val invite = WifiSync.Invite.parse(text)
                if (invite == null) {
                    toast(context, "That is not a sync code. On the other phone tap \"Show sync code\".")
                } else {
                    joining = false
                    scope.launch {
                        busy = true
                        message = "Syncing…"
                        val r = withContext(Dispatchers.IO) { WifiSync.join(store, invite) }
                        busy = false
                        onSynced(r)
                    }
                }
            },
        )
    }
}
