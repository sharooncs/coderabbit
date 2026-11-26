package app.housemove.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.housemove.data.Action
import app.housemove.data.Backup
import app.housemove.ui.*
import app.housemove.ui.icons.Icons
import app.housemove.ui.icons.filled.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The backup on Sync and backup (Android's [SyncScreen], the iPhone's Get fresh data): when the last one was made, saving
 * and sharing one file with everything, restoring or merging one (Organisers), and the daily small backup's folder.
 * [busy] greys the buttons while the screen is doing something else; [onWorking] and [say] tell the screen what this
 * is doing and what came of it.
 */
@Composable
fun BackupSection(busy: Boolean, onWorking: (Boolean) -> Unit, say: (SyncReport?) -> Unit) {
    val store = LocalStore.current
    val context = LocalPlatform.current
    val device = deviceInfo()
    val data = appData()
    val scope = rememberCoroutineScope()

    val saveBackup = context.rememberSaveFile("application/zip") { target ->
        if (target != null) scope.launch {
            onWorking(true)
            val error = withContext(Dispatchers.IO) {
                try {
                    target.open()!!.use { Backup.export(store, it) }
                    null
                } catch (e: Exception) {
                    e.message ?: "unknown error"
                }
            }
            onWorking(false)
            if (error == null) store.setDevice { it.copy(lastBackupAt = nowMillis()) }
            say(if (error == null) SyncReport(true, "Backup saved", "") else SyncReport(false, "Backup failed", error))
        }
    }
    val openBackup = context.rememberOpenFile { file ->
        if (file != null) scope.launch {
            onWorking(true)
            val report = withContext(Dispatchers.IO) {
                try {
                    val r = file.open()!!.use { Backup.import(store, it) }
                    SyncReport(true, "Backup merged in", "${r.photos} new photo(s) added.")
                } catch (e: Exception) {
                    // Includes the Store's own reason when this person may not restore a backup.
                    SyncReport(false, "Could not restore that file", e.message ?: "")
                }
            }
            say(report)
            onWorking(false)
        }
    }

    Section("Backup") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.Backup, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Hint(
                (if (device.lastBackupAt == 0L) "No backup yet" else "Last backup ${agoText(device.lastBackupAt)}") +
                    " · one file with everything, ${data.livePhotos.size} photos",
            )
        }
        // Saving and sharing the file: where the phone can (Android; the iPhone from step 3b).
        if (saveBackup != null && context.shareFiles) Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(
                "Save backup", onClick = { saveBackup("housemove-backup-${isoToday()}.zip") },
                modifier = Modifier.weight(1f).fillMaxHeight(), icon = Icons.Default.SaveAlt, enabled = !busy,
            )
            PillButton(
                "Share backup", modifier = Modifier.weight(1f).fillMaxHeight(), icon = Icons.Default.Share, primary = false, enabled = !busy,
                onClick = {
                    scope.launch {
                        onWorking(true)
                        val file = withContext(Dispatchers.IO) {
                            try {
                                context.outFile("housemove-backup-${isoToday()}.zip").also { f ->
                                    f.outputStream().use { Backup.export(store, it) }
                                }
                            } catch (_: Exception) {
                                null
                            }
                        }
                        onWorking(false)
                        if (file != null) context.share(file, "application/zip") else say(SyncReport(false, "Could not create the backup", ""))
                    }
                },
            )
        }
        // Restoring is for Organisers; nobody else is shown a button that would only refuse.
        if (allowed(Action.RESTORE_BACKUP) && openBackup != null) {
            PillButton(
                "Restore or merge a backup file", onClick = { openBackup(listOf("application/zip", "application/octet-stream", "*/*")) },
                modifier = Modifier.fillMaxWidth(), icon = Icons.Default.Restore, primary = false, enabled = !busy,
            )
        }
        // The daily small backup of a move with a single Organiser: where it goes.
        DailyBackupRow()
    }
}
