package app.housemove.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Share as ShareIcon
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.housemove.data.Action
import app.housemove.data.Part
import app.housemove.data.PhotoKind
import app.housemove.data.Ratio
import app.housemove.data.fmtNum
import app.housemove.data.movables
import app.housemove.data.movingItems
import app.housemove.data.uses
import app.housemove.pdf.Csv
import app.housemove.pdf.Pdf
import app.housemove.pdf.Share
import app.housemove.ui.LocalNav
import app.housemove.ui.LocalStore
import app.housemove.ui.PhotoThumb
import app.housemove.ui.Pic
import app.housemove.ui.Ring
import app.housemove.ui.Screen
import app.housemove.ui.ScrollColumn
import app.housemove.ui.StepRow
import app.housemove.ui.TileGrid
import app.housemove.ui.appData
import app.housemove.ui.deviceInfo
import app.housemove.ui.open
import app.housemove.ui.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import app.housemove.data.*
import app.housemove.ui.*

/** Paperwork at the end: inventory, damage report and the last few jobs. */
@Composable
fun ReportsScreen() {
    val store = LocalStore.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val data = appData()
    var busy by remember { mutableStateOf(false) }

    // Counted as everywhere else: what is carried. A container inside another, and an item
    // packed in a container, arrive with it and are not counted again.
    val all = data.movables()
    val items = data.looseItems()
    val boxes = data.topBoxes()
    val damaged = all.filter { it.damaged }
    val paid = data.liveExpenses.filter { !it.planned }.sumOf { it.amount }
    val tasks = data.liveTasks
    val tasksLeft = tasks.count { !it.done }
    val damagePhoto = damaged.firstNotNullOfOrNull { m -> data.photosOf(m.id, PhotoKind.DAMAGE).firstOrNull() }
    val device = deviceInfo()
    // A Helper is not shown costs, and their phone is not sent them.
    val seesCosts = allowed(Action.SUGGEST)

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
            // How the move went, in four numbers.
            val stats = listOfNotNull(
                MdStatOf("Items moved") { MdStatRing(Ratio(items.count { mdArrived(it.stage) }, items.size)) },
                MdStatOf("Boxes moved") { MdStatRing(Ratio(boxes.count { mdArrived(it.stage) }, boxes.size)) }
                    .takeIf { boxes.isNotEmpty() || data.uses(Part.BOXES) },
                MdStatOf("Damaged") { MdStatIcon(Pic.Damage, damaged.size.toString(), error = damaged.isNotEmpty()) },
                MdStatOf("Total paid") { MdStatIcon(Icons.Default.Payments, money(data.move.currency, paid)) }
                    .takeIf { seesCosts && (paid != 0.0 || data.uses(Part.BUDGET)) },
            )
            // Four in a row; two rows of two on a small phone, so no number is cut short.
            TileGrid(stats, columns = if (narrowScreen()) 2 else stats.size) { stat, m -> MdStat(stat.label, m, stat.picture) }
            // An Organiser marks the move done; Helpers' copies then expire a few days later.
            MoveDoneRow()

            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())

            MdReport(
                Icons.Default.PictureAsPdf, "Inventory", "Every item and box, with its room and size.",
                enabled = !busy,
                onOpen = { produce("application/pdf", open = true) { Pdf.inventory(context, data) } },
                onShare = { produce("application/pdf", open = false) { Pdf.inventory(context, data) } },
            )
            MdReport(
                Icons.Default.TableChart, "Inventory spreadsheet", "The same list for Excel or Google Sheets.",
                enabled = !busy,
                onOpen = null,
                onShare = { produce("text/csv", open = false) { Csv.inventory(context, data) } },
            )
            MdReport(
                Pic.Damage, "Damage report",
                if (damaged.isEmpty()) "Nothing is marked as damaged."
                else "${damaged.size} damaged, with before and after photos.",
                enabled = !busy,
                onOpen = { produce("application/pdf", open = true) { Pdf.damageReport(context, store) } },
                onShare = { produce("application/pdf", open = false) { Pdf.damageReport(context, store) } },
                // Led by a real photo of the damage when there is one.
                picture = if (damagePhoto == null) null else {
                    { PhotoThumb(damagePhoto, Modifier.size(56.dp).clip(RoundedCornerShape(16.dp))) }
                },
            )

            // The last few jobs, on one glass panel like the Move day steps.
            GlassCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(26.dp), sheen = true) {
                Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (data.uses(Part.TIMELINE)) StepRow(
                        Icons.Default.Checklist,
                        if (tasksLeft == 0) "All tasks done" else countOf(tasksLeft, "task") + " left",
                        onClick = { nav.open("tasks") },
                        ratio = Ratio(tasks.size - tasksLeft, tasks.size),
                        status = "Address, deposit, final bills",
                    )
                    StepRow(
                        Icons.Default.Backup, "Back up the data", onClick = { nav.open("sync") },
                        status = if (device.lastBackupAt == 0L) "One file, photos included"
                        else "Last backup ${agoText(device.lastBackupAt)}",
                        done = device.lastBackupAt != 0L,
                    )
                }
            }
        }
    }
}

private class MdStatOf(val label: String, val picture: @Composable () -> Unit)

/** One number of the summary: a picture or ring on top, what it counts underneath. */
@Composable
private fun MdStat(label: String, modifier: Modifier = Modifier, picture: @Composable () -> Unit) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.heightIn(min = 56.dp), contentAlignment = Alignment.Center) { picture() }
        Text(
            label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center, maxLines = 2,
        )
    }
}

@Composable
private fun MdStatRing(ratio: Ratio) {
    Ring(ratio, size = 56.dp, stroke = 5.dp, color = MaterialTheme.colorScheme.tertiary) {
        Text("${ratio.done}/${ratio.total}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun MdStatIcon(icon: ImageVector, value: String, error: Boolean = false) {
    val tint = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(26.dp))
        Text(
            value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
            color = if (error) tint else MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** One report: its picture, what it is for in one line, and the buttons to open or share it. */
@Composable
private fun MdReport(
    icon: ImageVector,
    title: String,
    purpose: String,
    enabled: Boolean,
    onOpen: (() -> Unit)?,
    onShare: () -> Unit,
    picture: (@Composable () -> Unit)? = null,
) {
    GlassCard(Modifier.fillMaxWidth(), sheen = true) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (picture != null) picture() else Box(
                    Modifier.size(56.dp).glass(RoundedCornerShape(16.dp), shadow = 0.dp),
                    contentAlignment = Alignment.Center,
                ) { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp)) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(purpose, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            FileButtons("Open", enabled, onOpen = onOpen, onShare = onShare)
        }
    }
}
