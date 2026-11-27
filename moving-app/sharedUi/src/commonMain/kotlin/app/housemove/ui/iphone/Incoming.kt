package app.housemove.ui.iphone

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.housemove.data.Backup
import app.housemove.data.Store
import app.housemove.data.TransferTime
import app.housemove.ui.GlassDialog
import app.housemove.ui.PillButton
import app.housemove.ui.icons.Icons
import app.housemove.ui.icons.filled.ErrorOutline
import app.housemove.ui.icons.filled.Restore
import app.housemove.ui.screens.IncomingUpdatesFor
import app.housemove.ui.screens.SyncReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

/**
 * Files other apps hand to HouseMove on the iPhone ("Open in HouseMove", from Files, Mail, Messages or AirDrop; step 3b
 * of design/ios-plan.md), for the move on this iPhone. An update file goes into the move as on Android
 * ([IncomingUpdatesFor]: "Update from Priya's phone"). A backup is merged into it as Restore or merge a backup file
 * does, which only an Organiser may: anyone else is told so, and nothing changes. What came of it is said.
 */
@Composable
internal fun IncomingFiles(model: AppModel, store: Store) {
    IncomingUpdatesFor(store)
    val files = model.hooks.files ?: return
    val backup = files.incoming?.takeIf { it.backup }
    var report by remember { mutableStateOf<SyncReport?>(null) }
    LaunchedEffect(backup) {
        val file = backup ?: return@LaunchedEffect
        files.incomingTaken()
        report = withContext(Dispatchers.IO) {
            TransferTime.during(TransferTime.BACKUP) {
                try {
                    val r = file.file.open()!!.use { Backup.import(store, it) }
                    SyncReport(true, "Backup merged in", "${r.photos} new photo(s) added.")
                } catch (e: Exception) {
                    // Includes the Store's own reason when this person may not restore a backup.
                    SyncReport(false, "Could not restore that file", e.message ?: Backup.NOT_A_BACKUP)
                }
            }
        }
    }
    report?.let { r ->
        GlassDialog(
            r.title, onDismiss = { report = null }, icon = if (r.ok) Icons.Default.Restore else Icons.Default.ErrorOutline, text = r.text,
            buttons = { PillButton("OK", onClick = { report = null }) },
        )
    }
}
