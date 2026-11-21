package app.housemove.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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

    // On a small screen or with large text, the picture buttons go two to a row so their words fit.
    val config = LocalConfiguration.current
    val narrow = config.screenWidthDp / config.fontScale < 340

    Screen("Triage") {
        when {
            all.isEmpty() -> ScrollColumn {
                EmptyState(
                    Icons.Default.Rule, "Nothing to decide yet",
                    text = "Photograph the big things first, then decide here.",
                    action = "Start the walk-through", onAction = { nav.open("walk") },
                )
            }

            current == null -> ScrollColumn {
                EmptyState(Icons.Default.CheckCircle, "All decided", text = "Every item has a decision.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Disposition.entries.filter { it != Disposition.UNDECIDED }.forEach { d ->
                        CountTile(d.icon(), all.count { it.disposition == d }, d.label, Modifier.weight(1f))
                    }
                }
                if (data.uses(Part.PLAN)) {
                    BigAction(Icons.Default.Map, "Plan the new rooms", onClick = { nav.open("plan") })
                }
                BigAction(Icons.Default.Sell, "Sell, donate and bin list", onClick = { nav.open("disposal") }, primary = !data.uses(Part.PLAN))
            }

            else -> ScrollColumn {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Ring(Ratio(all.size - undecided.size, all.size), size = 40.dp, stroke = 5.dp) {
                        Text("${undecided.size}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text("left to decide", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { nav.open("item/${current.id}") }) { Text("Open details") }
                }

                val photo = data.photosOf(current.id).firstOrNull()
                Box(
                    Modifier.fillMaxWidth().height(250.dp).clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable(onClickLabel = "Open details") { nav.open("item/${current.id}") },
                    contentAlignment = Alignment.Center,
                ) {
                    if (photo != null) {
                        AsyncImage(
                            model = store.photoFile(photo),
                            contentDescription = "Photo of ${current.title()}",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Icon(
                            Pic.Item,
                            contentDescription = "No photo",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(72.dp),
                        )
                    }
                    RoomBadge(data.room(current.originRoomId), Modifier.align(Alignment.TopStart).padding(10.dp))
                }

                TextInput(
                    value = current.name,
                    onChange = { v -> store.data.value.item(current.id)?.let { store.put(it.copy(name = v)) } },
                    label = "What is it?",
                    key = current.id,
                    mic = true,
                )

                BigAction(Disposition.MOVE.icon(), Disposition.MOVE.label, onClick = { decide(current.id, Disposition.MOVE) })
                listOf(Disposition.SELL, Disposition.DONATE, Disposition.BIN, Disposition.STORE).chunked(if (narrow) 2 else 4).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { d -> PictureButton(d.icon(), d.label, Modifier.weight(1f)) { decide(current.id, d) } }
                    }
                }
                OutlinedButton(
                    onClick = {
                        val next = skipped + current.id
                        skipped = if (undecided.all { it.id in next }) emptySet() else next
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Default.SkipNext, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Skip for now")
                }
                Hint("Not used in a year? It probably should not make the trip.")
            }
        }
    }
}

/** A large button with its picture above its word. */
@Composable
private fun PictureButton(icon: ImageVector, text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 76.dp),
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp))
            Text(text, style = MaterialTheme.typography.titleSmall, maxLines = 1)
        }
    }
}

/** How many items got one decision: picture, number, word. */
@Composable
private fun CountTile(icon: ImageVector, count: Int, label: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier
            .glass(RoundedCornerShape(18.dp), shadow = 5.dp)
            .padding(vertical = 10.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = scheme.primary)
        Text("$count", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center, maxLines = 1)
    }
}
