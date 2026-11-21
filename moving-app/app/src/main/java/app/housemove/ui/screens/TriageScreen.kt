package app.housemove.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import app.housemove.data.*
import app.housemove.ui.*
import coil.compose.AsyncImage

/** Decide the fate of each catalogued item, one at a time. */
@Composable
fun TriageScreen() {
    val store = LocalStore.current
    val nav = LocalNav.current
    val data = appData()

    val all = data.liveItems
    val undecided = all.filter { it.disposition == Disposition.UNDECIDED }
        .sortedWith(compareBy({ data.room(it.originRoomId)?.sort ?: Int.MAX_VALUE }, { data.room(it.originRoomId)?.name ?: "~" }, { it.createdAt }))
    // Items skipped this visit go to the back of the queue.
    var skipped by remember { mutableStateOf(setOf<String>()) }
    val current = undecided.firstOrNull { it.id !in skipped } ?: undecided.firstOrNull()

    fun decide(itemId: String, d: Disposition) {
        val now = store.data.value
        val cur = now.item(itemId) ?: return
        store.put(
            cur.copy(
                disposition = d,
                destRoomId = if (d == Disposition.MOVE && cur.destRoomId == null) now.room(cur.originRoomId)?.mapsTo else cur.destRoomId,
            )
        )
    }

    Screen("Triage") {
        when {
            all.isEmpty() -> ScrollColumn {
                Section("Nothing to decide yet") {
                    Text("Do the walk-through first: one photo of each big thing in every room. Then come back here to decide what happens to each one.")
                    Button(onClick = { nav.open("walk") }) { Text("Start the walk-through") }
                }
            }

            current == null -> ScrollColumn {
                Section("All decided") {
                    Text("Every item has a decision. Here is the split:")
                    Disposition.entries.filter { it != Disposition.UNDECIDED }.forEach { d ->
                        Row {
                            Text(d.label, modifier = Modifier.weight(1f))
                            Text(all.count { it.disposition == d }.toString(), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
                Button(onClick = { nav.open("plan") }, modifier = Modifier.fillMaxWidth()) { Text("Plan the new rooms") }
                OutlinedButton(onClick = { nav.open("disposal") }, modifier = Modifier.fillMaxWidth()) { Text("Sell, donate and bin list") }
            }

            else -> ScrollColumn {
                val decided = all.size - undecided.size
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${undecided.size} left to decide", style = MaterialTheme.typography.titleMedium)
                    LinearProgressIndicator(
                        progress = { if (all.isEmpty()) 0f else decided.toFloat() / all.size },
                        modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                    )
                }

                val photo = data.photosOf(current.id).firstOrNull()
                val photoModifier = Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { nav.open("item/${current.id}") }
                if (photo != null) {
                    AsyncImage(
                        model = store.photoFile(photo),
                        contentDescription = "Photo of ${current.title()}",
                        contentScale = ContentScale.Fit,
                        modifier = photoModifier,
                    )
                } else {
                    Box(photoModifier, contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Chair,
                            contentDescription = "No photo",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(64.dp),
                        )
                    }
                }

                TextInput(
                    value = current.name,
                    onChange = { v -> store.data.value.item(current.id)?.let { store.put(it.copy(name = v)) } },
                    label = "What is it?",
                    key = current.id,
                    mic = true,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Hint("In the old home:")
                    RoomBadge(data.room(current.originRoomId))
                }

                Button(
                    onClick = { decide(current.id, Disposition.MOVE) },
                    modifier = Modifier.fillMaxWidth().height(60.dp),
                ) {
                    Icon(Icons.Default.LocalShipping, contentDescription = null)
                    Spacer(Modifier.width(10.dp))
                    Text("Move", style = MaterialTheme.typography.titleMedium)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceButton("Sell", Modifier.weight(1f)) { decide(current.id, Disposition.SELL) }
                    ChoiceButton("Donate", Modifier.weight(1f)) { decide(current.id, Disposition.DONATE) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceButton("Bin", Modifier.weight(1f)) { decide(current.id, Disposition.BIN) }
                    ChoiceButton("Store", Modifier.weight(1f)) { decide(current.id, Disposition.STORE) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            val next = skipped + current.id
                            skipped = if (undecided.all { it.id in next }) emptySet() else next
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Skip for now") }
                    TextButton(onClick = { nav.open("item/${current.id}") }, modifier = Modifier.weight(1f)) { Text("Open details") }
                }
                Hint("Not sure? If you have not used it in a year and would not buy it again, it probably should not make the trip.")
            }
        }
    }
}

@Composable
private fun ChoiceButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, modifier = modifier.height(56.dp)) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}
