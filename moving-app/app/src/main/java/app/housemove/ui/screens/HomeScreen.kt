package app.housemove.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.housemove.data.*
import app.housemove.ui.*

private data class NextStep(val text: String, val button: String, val route: String, val icon: ImageVector)

/** The single most useful thing to do now, from where the move has got to. */
private fun nextStep(data: AppData): NextStep {
    val p = data.progress()
    val mv = data.movables()
    val delivered = mv.count { it.stage == Stage.DELIVERED || it.stage == Stage.DONE }
    val loaded = mv.count { it.stage == Stage.LOADED }
    return when {
        data.roomsOf(Home.ORIGIN).isEmpty() || data.roomsOf(Home.DEST).isEmpty() ->
            NextStep("Add the rooms of both homes so things can be given a place.", "Add rooms", "rooms", Icons.Default.MeetingRoom)
        data.liveItems.isEmpty() && data.liveBoxes.isEmpty() ->
            NextStep("Photograph the big things, room by room.", "Start the walk-through", "walk", Icons.Default.PhotoCamera)
        p.triage.done < p.triage.total ->
            NextStep("Decide what happens to each thing. ${p.triage.total - p.triage.done} left.", "Triage", "triage", Icons.Default.Rule)
        p.destinations.done < p.destinations.total ->
            NextStep("Give every item a room in the new home.", "Plan destinations", "plan", Icons.Default.Map)
        loaded > 0 && delivered < mv.size ->
            NextStep("$loaded on the van. Scan each thing as it comes off.", "Unload", "unload", Icons.Default.Download)
        mv.isNotEmpty() && delivered == mv.size && p.unpacked.done < p.unpacked.total ->
            NextStep("Everything has arrived. First-night boxes first.", "Unpack", "unpack", Icons.Default.Unarchive)
        mv.isNotEmpty() && p.unpacked.done == p.unpacked.total ->
            NextStep("Everything is unpacked. Save the inventory.", "Reports and close-out", "reports", Icons.Default.Description)
        (data.move.moveDay ?: Long.MAX_VALUE) <= today() && mv.any { it.stage == Stage.AT_ORIGIN } ->
            NextStep("Move day: scan everything onto the van.", "Load the van", "load", Icons.Default.LocalShipping)
        else ->
            NextStep("Pack room by room: label, photo, contents, seal.", "Pack a box", "pack", Icons.Default.Inventory2)
    }
}

@Composable
fun HomeScreen() {
    val nav = LocalNav.current
    val store = LocalStore.current
    val data = appData()
    val device = deviceInfo()
    val move = data.move
    val now = currentPhase(data)
    // -1 follows the phase the move is in; tapping the strip looks at another one.
    var picked by rememberSaveable { mutableIntStateOf(-1) }
    val shown = if (picked < 0) now else picked

    Screen(title = move.name.ifBlank { "HouseMove" }, back = false, tab = "home", actions = { TopActions() }) {
        ScrollColumn {
            var warning by remember { mutableStateOf(store.loadWarning) }
            warning?.let { w ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(w, color = MaterialTheme.colorScheme.onErrorContainer)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { nav.open("sync") }) { Text("Sync and backup") }
                            TextButton(onClick = { store.clearLoadWarning(); warning = null }) { Text("Dismiss") }
                        }
                    }
                }
            }

            // Countdown and the one thing to do next, together.
            val next = nextStep(data)
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth().clickable { nav.open("settings") }, verticalAlignment = Alignment.CenterVertically) {
                        val day = move.moveDay
                        val d = day?.let { it - today() }
                        Text(
                            when {
                                d == null -> "?"
                                d >= 0 -> "$d"
                                else -> "+${-d}"
                            },
                            style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                when {
                                    d == null -> "No move day set"
                                    d > 1 -> "days to go"
                                    d == 1L -> "day to go"
                                    d == 0L -> "Move day is today"
                                    d == -1L -> "day since the move"
                                    else -> "days since the move"
                                },
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                if (day == null) "Tap to set the date" else fmtDay(day),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text(next.text, style = MaterialTheme.typography.bodyLarge)
                    Button(onClick = { nav.open(next.route) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                        Icon(next.icon, contentDescription = null)
                        Spacer(Modifier.width(10.dp))
                        Text(next.button, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }

            // The two things done over and over.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { nav.open("walk") }, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                    Icon(Icons.Default.PhotoCamera, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Walk-through")
                }
                FilledTonalButton(onClick = { nav.open("pack") }, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                    Icon(Icons.Default.Inventory2, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Pack a box")
                }
            }

            // Where the move is, and what belongs to each phase.
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    JourneyStrip(data, selected = shown, current = now, onSelect = { picked = it })
                    val phase = JOURNEY[shown]
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(phase.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Text(
                            if (shown == now) "You are here" else phase.timing,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (shown == now) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    PhaseTiles(phase, data, device)
                }
            }

            // Needs attention: the worst three, the rest on request.
            val issues = data.issues()
            if (issues.isNotEmpty()) {
                var all by rememberSaveable { mutableStateOf(false) }
                Section("Needs attention") {
                    (if (all) issues else issues.take(3)).forEach { issue ->
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
                    if (issues.size > 3) {
                        TextButton(onClick = { all = !all }) { Text(if (all) "Show fewer" else "Show ${issues.size - 3} more") }
                    }
                }
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
