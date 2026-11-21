package app.housemove.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.housemove.data.fmtNum
import app.housemove.data.movables
import app.housemove.data.movingItems
import app.housemove.pdf.Csv
import app.housemove.pdf.Pdf
import app.housemove.pdf.Share
import app.housemove.ui.Hint
import app.housemove.ui.LocalNav
import app.housemove.ui.LocalStore
import app.housemove.ui.MenuRow
import app.housemove.ui.Screen
import app.housemove.ui.ScrollColumn
import app.housemove.ui.Section
import app.housemove.ui.appData
import app.housemove.ui.open
import app.housemove.ui.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Paperwork at the end: inventory, damage report and the last few jobs. */
@Composable
fun ReportsScreen() {
    val store = LocalStore.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val data = appData()
    var busy by remember { mutableStateOf(false) }

    val all = data.movables()
    val items = data.movingItems()
    val damaged = all.count { it.damaged }
    val paid = data.liveExpenses.filter { !it.planned }.sumOf { it.amount }
    val tasksLeft = data.liveTasks.count { !it.done }

    // Build the file off the main thread, then hand it to another app.
    fun produce(mime: String, open: Boolean, make: () -> File) {
        if (busy) return
        busy = true
        scope.launch {
            val file = try {
                withContext(Dispatchers.IO) { make() }
            } catch (_: Exception) {
                null
            }
            busy = false
            when {
                file == null -> toast(context, "Could not create the file. Please try again.")
                open -> Share.open(context, file, mime)
                else -> Share.send(context, file, mime)
            }
        }
    }

    Screen("Reports and close-out") {
        ScrollColumn {
            Section("Summary") {
                MdStatRow("Items moved", "${items.count { mdArrived(it.stage) }} of ${items.size}")
                MdStatRow("Boxes", "${data.liveBoxes.count { mdArrived(it.stage) }} of ${data.liveBoxes.size} delivered")
                MdStatRow("Damaged", damaged.toString())
                MdStatRow("Total paid", (data.move.currency.trim() + " " + fmtNum(paid)).trim())
            }

            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())

            Section("Inventory (PDF)") {
                Hint("Every item and box with rooms, sizes and the load estimate. Open it to read or print.")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        enabled = !busy,
                        onClick = { produce("application/pdf", open = true) { Pdf.inventory(context, data) } },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("Open") }
                    OutlinedButton(
                        enabled = !busy,
                        onClick = { produce("application/pdf", open = false) { Pdf.inventory(context, data) } },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("Share") }
                }
            }

            Section("Inventory (spreadsheet)") {
                Hint("The same list as a CSV file for Excel or Google Sheets.")
                OutlinedButton(
                    enabled = !busy,
                    onClick = { produce("text/csv", open = false) { Csv.inventory(context, data) } },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Share") }
            }

            Section("Damage report (PDF)") {
                Hint(
                    if (damaged == 0) "Nothing is marked as damaged. Mark damage while unloading, or on the box or item itself."
                    else "$damaged damaged, each with its photos from before and after the move. Useful for the van hire company or your insurer."
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        enabled = !busy,
                        onClick = { produce("application/pdf", open = true) { Pdf.damageReport(context, store) } },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("Open") }
                    OutlinedButton(
                        enabled = !busy,
                        onClick = { produce("application/pdf", open = false) { Pdf.damageReport(context, store) } },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("Share") }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column {
                    Text(
                        "Close-out checklist",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                    )
                    MenuRow(
                        Icons.Default.Checklist,
                        if (tasksLeft == 0) "All tasks done" else if (tasksLeft == 1) "1 task left" else "$tasksLeft tasks left",
                        "Address changes, deposit, final bills",
                    ) { nav.open("tasks") }
                    HorizontalDivider()
                    MenuRow(
                        Icons.Default.Backup,
                        "Back up the data",
                        "Save one file with everything, photos included",
                    ) { nav.open("sync") }
                    HorizontalDivider()
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("• Keep the inventory PDF for your home insurance.", style = MaterialTheme.typography.bodyMedium)
                        Text("• Flatten the boxes and pass them on to someone else who is moving.", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun MdStatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}
