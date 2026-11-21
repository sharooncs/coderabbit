package app.housemove.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.housemove.data.*
import app.housemove.ui.*

private data class NextStep(val text: String, val button: String, val route: String)

/** The single most useful thing to do now, from where the move has got to. */
private fun nextStep(data: AppData): NextStep {
    val p = data.progress()
    val mv = data.movables()
    val delivered = mv.count { it.stage == Stage.DELIVERED || it.stage == Stage.DONE }
    val loaded = mv.count { it.stage == Stage.LOADED }
    return when {
        data.roomsOf(Home.ORIGIN).isEmpty() || data.roomsOf(Home.DEST).isEmpty() ->
            NextStep("Add the rooms of both homes so things can be given a place.", "Add rooms", "rooms")
        data.liveItems.isEmpty() && data.liveBoxes.isEmpty() ->
            NextStep("Walk through each room and photograph the big things: furniture, appliances, electronics.", "Start the walk-through", "walk")
        p.triage.done < p.triage.total ->
            NextStep("Decide what happens to each thing before packing it. ${p.triage.total - p.triage.done} left.", "Triage", "triage")
        p.destinations.done < p.destinations.total ->
            NextStep("Give every item you are moving a room in the new home and check it fits.", "Plan destinations", "plan")
        loaded > 0 && delivered < mv.size ->
            NextStep("$loaded on the van. Scan each thing as it comes off.", "Unload", "unload")
        mv.isNotEmpty() && delivered == mv.size && p.unpacked.done < p.unpacked.total ->
            NextStep("Everything has arrived. Open the First night boxes first.", "Unpack", "unpack")
        mv.isNotEmpty() && p.unpacked.done == p.unpacked.total ->
            NextStep("Everything is unpacked. Export the inventory and wrap up.", "Reports and close-out", "reports")
        (data.move.moveDay ?: Long.MAX_VALUE) <= today() && mv.any { it.stage == Stage.AT_ORIGIN } ->
            NextStep("Move day: scan everything onto the van.", "Load the van", "load")
        else ->
            NextStep("Pack room by room. Label, photograph the open box, say what's inside, seal.", "Pack a box", "pack")
    }
}

@Composable
fun HomeScreen() {
    val nav = LocalNav.current
    val store = LocalStore.current
    val data = appData()
    val device = deviceInfo()
    val move = data.move
    val progress = data.progress()

    Screen(title = move.name.ifBlank { "HouseMove" }, back = false, tab = "home", actions = { TopActions() }) {
        ScrollColumn {
            // Countdown
            Card(
                Modifier.fillMaxWidth().clickable { nav.open("settings") },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Column(Modifier.padding(16.dp)) {
                    val day = move.moveDay
                    if (day == null) {
                        Text("No move day set", style = MaterialTheme.typography.headlineSmall)
                        Text("Tap to set the date. The timeline counts back from it.")
                    } else {
                        val d = day - today()
                        Text(
                            when {
                                d > 1 -> "$d days to go"
                                d == 1L -> "Moving tomorrow"
                                d == 0L -> "Move day"
                                d == -1L -> "Moved yesterday"
                                else -> "Moved ${-d} days ago"
                            },
                            style = MaterialTheme.typography.headlineMedium,
                        )
                        Text("${fmtDay(day)} · ${move.originName} → ${move.destName}")
                    }
                }
            }

            // Next step
            val next = nextStep(data)
            Section("Next") {
                Text(next.text)
                Button(onClick = { nav.open(next.route) }) { Text(next.button) }
            }

            // Shortcuts
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Shortcut(Icons.Default.PhotoCamera, "Walk-through", Modifier.weight(1f)) { nav.open("walk") }
                Shortcut(Icons.Default.Inventory2, "Pack a box", Modifier.weight(1f)) { nav.open("pack") }
                Shortcut(Icons.Default.QrCodeScanner, "Scan", Modifier.weight(1f)) { nav.open("scan") }
                Shortcut(Icons.Default.Search, "Find", Modifier.weight(1f)) { nav.open("search") }
            }

            // Needs attention
            val issues = data.issues()
            if (issues.isNotEmpty()) {
                Section("Needs attention") {
                    issues.forEach { issue ->
                        Row(
                            Modifier.fillMaxWidth().clickable { nav.open(issue.route) }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (issue.severe) Icons.Default.ErrorOutline else Icons.Default.Info,
                                contentDescription = null,
                                tint = if (issue.severe) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(issue.text, modifier = Modifier.weight(1f))
                            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            // Progress
            Section("Progress") {
                Hint("${data.liveItems.size} items catalogued · ${data.liveBoxes.size} boxes")
                ProgressRow("Items triaged", progress.triage)
                ProgressRow("Items with a destination", progress.destinations)
                ProgressRow("Rooms packed", progress.roomsPacked)
                ProgressRow("Boxes sealed", progress.sealed)
                ProgressRow("Loaded", progress.loaded)
                ProgressRow("Delivered", progress.delivered)
                ProgressRow("Unpacked / in place", progress.unpacked)
            }

            // Tasks
            val open = data.liveTasks.filter { !it.done }.sortedBy { data.dueDay(it) ?: Long.MAX_VALUE }
            Section("Timeline", action = { TextButton(onClick = { nav.open("tasks") }) { Text("All tasks") } }) {
                ProgressRow("Tasks done", progress.tasks)
                if (open.isEmpty()) Hint(if (data.liveTasks.isEmpty()) "No tasks yet. Open the timeline to add the checklist." else "Nothing left to do.")
                open.take(5).forEach { t ->
                    val due = data.dueDay(t)
                    val late = due != null && due < today()
                    CheckRow(
                        t.title,
                        checked = false,
                        onChange = { done -> store.put(t.copy(done = done)) },
                        sub = if (due == null) t.category else "${fmtDayShort(due)} · ${relDay(due)}" + if (late) " · overdue" else "",
                    )
                }
            }

            // Load estimate
            val v = data.volume()
            if (v.totalM3 > 0) {
                Section("Load estimate") {
                    Text("${fmtNum(v.totalM3)} m³ · about ${v.trips} trip${if (v.trips == 1) "" else "s"} in a ${fmtNum(move.vanCapacityM3)} m³ van")
                    Hint(
                        "Furniture ${fmtNum(v.itemsM3)} m³, boxes ${fmtNum(v.boxesM3)} m³, plus 20% for gaps." +
                            if (v.itemsWithoutSize > 0) " ${v.itemsWithoutSize} item(s) have no size yet and are guessed." else ""
                    )
                }
            }

            // Sync reminder
            Section("Phones") {
                Text(
                    if (device.lastSyncAt == 0L) "This phone has not synced with another phone yet."
                    else "Last synced ${agoText(device.lastSyncAt)}.",
                )
                Hint("Changes made on one phone reach the others only when you sync. Do it whenever you are together.")
                OutlinedButton(onClick = { nav.open("sync") }) { Text("Sync and backup") }
            }
        }
    }
}

fun agoText(millis: Long, now: Long = System.currentTimeMillis()): String {
    val mins = ((now - millis) / 60_000).coerceAtLeast(0)
    return when {
        mins < 1 -> "just now"
        mins < 60 -> "$mins min ago"
        mins < 60 * 24 -> "${mins / 60} h ago"
        else -> "${mins / (60 * 24)} day${if (mins / (60 * 24) == 1L) "" else "s"} ago"
    }
}

@Composable
private fun Shortcut(icon: ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, modifier = modifier, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 12.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null)
            Spacer(Modifier.height(4.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 2)
        }
    }
}
