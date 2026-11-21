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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.housemove.data.*
import app.housemove.ui.*

/**
 * Decide the fate of each catalogued item, one at a time, from its picture.
 *
 * An Organiser's tap decides. A Decider gets the same picture choices as a suggestion,
 * which then waits for approval. A Helper cannot choose, and instead flags the item as
 * needing a decision. An Organiser finds what others suggested or asked on the item itself,
 * first in the queue, with Approve and Decline.
 */
@Composable
fun TriageScreen() {
    val store = LocalStore.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val data = appData()
    deviceInfo() // whose phone this is decides what is offered below
    val mayDecide = store.can(Action.DECIDE)
    val maySuggest = store.can(Action.SUGGEST)
    val me = store.me?.id

    val all = data.liveItems
    val waiting = data.waitingProposals()
        .filter { it.kind == ProposalKind.ITEM_DECISION || it.kind == ProposalKind.QUESTION }
        .groupBy { it.targetId }
    // What is still to do here: for an Organiser everything undecided and everything someone has
    // suggested or asked about; for the others, the undecided things they have not spoken up on yet.
    val queue = all.filter { x ->
        if (mayDecide) x.disposition == Disposition.UNDECIDED || waiting[x.id] != null
        else x.disposition == Disposition.UNDECIDED && waiting[x.id].orEmpty().none { it.by == me }
    }.sortedWith(
        compareBy(
            { if (mayDecide && waiting[it.id] != null) 0 else 1 },
            { data.room(it.originRoomId)?.sort ?: Int.MAX_VALUE }, { data.room(it.originRoomId)?.name ?: "~" }, { it.createdAt },
        )
    )
    // Items skipped this visit go to the back of the queue.
    var skipped by remember { mutableStateOf(setOf<String>()) }
    val current = queue.firstOrNull { it.id !in skipped } ?: queue.firstOrNull()

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
                val mineWaiting = data.waitingProposals().count { it.by == me && it.kind != ProposalKind.COST && it.kind != ProposalKind.TASK }
                when {
                    mayDecide -> EmptyState(Icons.Default.CheckCircle, "All decided", text = "Every item has a decision.")
                    mineWaiting > 0 -> EmptyState(
                        Icons.Default.HourglassTop, if (maySuggest) "All suggested" else "All asked",
                        text = "$mineWaiting waiting for an Organiser.",
                    )
                    else -> EmptyState(Icons.Default.CheckCircle, "Nothing left to decide", text = "Every item has a decision.")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Disposition.entries.filter { it != Disposition.UNDECIDED }.forEach { d ->
                        CountTile(d.icon(), all.count { it.disposition == d }, d.label, Modifier.weight(1f))
                    }
                }
                if (maySuggest) {
                    if (data.uses(Part.PLAN)) {
                        BigAction(Icons.Default.Map, "Plan the new rooms", onClick = { nav.open("plan") })
                    }
                    BigAction(Icons.Default.Sell, "Sell, donate and bin list", onClick = { nav.open("disposal") }, primary = !data.uses(Part.PLAN))
                }
            }

            else -> ScrollColumn {
                val suggestions = waiting[current.id].orEmpty()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Ring(Ratio(all.size - queue.size, all.size), size = 40.dp, stroke = 5.dp) {
                        Text("${queue.size}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        when {
                            mayDecide -> "left to decide"
                            maySuggest -> "left to suggest"
                            else -> "with no decision yet"
                        },
                        style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { nav.open("item/${current.id}") }) { Text("Open details") }
                }

                TriagePhoto(current, data, narrow) { nav.open("item/${current.id}") }

                // What others have suggested or asked: an Organiser settles it here, the rest see that it waits.
                suggestions.forEach { p -> if (mayDecide) ProposalReview(p, data) else WaitingTag(p, data) }

                fun done() {
                    skipped = skipped - current.id
                }

                when {
                    maySuggest -> {
                        // A Decider choosing Sell names an asking price first; everything else is one tap.
                        var pricing by remember(current.id) { mutableStateOf(false) }
                        var price by remember(current.id) { mutableStateOf(current.price) }
                        fun pick(d: Disposition, asking: Double? = null) {
                            if (!store.decideOrSuggest(context, current.id, d, asking)) return
                            // An Organiser choosing what it already was has said no to what others suggested instead.
                            if (mayDecide && d == current.disposition) {
                                store.data.value.waitingFor(current.id, ProposalKind.ITEM_DECISION).forEach { store.decline(it.id, "It stays as it is: ${d.label}") }
                            }
                            done()
                        }
                        if (!mayDecide) Text("Suggest what happens to it", style = MaterialTheme.typography.titleMedium)
                        if (pricing) {
                            Section {
                                AmountPicker(price, { price = it }, "Asking price", data.move.currency)
                                BigAction(
                                    Icons.Default.Sell, "Suggest selling" + (price?.let { " for " + money(data.move.currency, it) } ?: ""),
                                    onClick = { pick(Disposition.SELL, price) },
                                )
                                TextButton(onClick = { pricing = false }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Something else") }
                            }
                        } else {
                            BigAction(Disposition.MOVE.icon(), Disposition.MOVE.label, onClick = { pick(Disposition.MOVE) })
                            listOf(Disposition.SELL, Disposition.DONATE, Disposition.BIN, Disposition.STORE).chunked(if (narrow) 2 else 4).forEach { row ->
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    row.forEach { d ->
                                        PictureButton(d.icon(), d.label, Modifier.weight(1f)) {
                                            if (d == Disposition.SELL && !mayDecide) pricing = true else pick(d)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    else -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        // A Helper does not choose; one tap tells the Organisers this one needs them.
                        BigAction(
                            Icons.Default.QuestionMark, "Needs a decision",
                            onClick = { if (store.ask(current.id).okOrSay(context)) done() },
                            modifier = Modifier.weight(1f),
                        )
                        SpeakButton("What should they know about it?", contentDescription = "Ask with a spoken note", size = 56.dp) { said ->
                            if (store.ask(current.id, said).okOrSay(context)) done()
                        }
                    }
                }

                if (queue.size > 1) {
                    GlassPill(
                        Modifier.fillMaxWidth(),
                        onClick = {
                            val next = skipped + current.id
                            skipped = if (queue.all { it.id in next }) emptySet() else next
                        },
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(Icons.Default.SkipNext, contentDescription = null, tint = Night.TextDim)
                        Spacer(Modifier.width(8.dp))
                        Text("Skip for now", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

/** The thing being decided: its photo as large as the screen allows, with its room and name written on it. */
@Composable
private fun TriagePhoto(x: Item, data: AppData, narrow: Boolean, onOpen: () -> Unit) {
    val shape = RoundedCornerShape(26.dp)
    Box(
        Modifier.fillMaxWidth().height(if (narrow) 190.dp else 250.dp).clip(shape).border(1.dp, Night.GlassRim, shape)
            .clickable(onClickLabel = "Open details", onClick = onOpen),
    ) {
        ItemPhoto(x.id, Modifier.fillMaxSize(), shape, iconSize = 72.dp)
        Box(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().height(84.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xD9081220))))
        )
        RoomBadge(data.room(x.originRoomId), Modifier.align(Alignment.TopStart).padding(12.dp))
        // Something already decided is here because someone suggests otherwise: say how it stands now.
        if (x.disposition != Disposition.UNDECIDED) {
            val (state, tone) = data.itemState(x)
            PhotoMark(x.disposition.icon(), "Now: $state", Modifier.align(Alignment.TopEnd).padding(12.dp), tint = tone.onPhoto())
        }
        Text(
            x.title(), color = Color.White, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        )
    }
}

/** A large glass button with its picture above its word. */
@Composable
private fun PictureButton(icon: ImageVector, text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier.glass(RoundedCornerShape(20.dp), shadow = 5.dp).clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 76.dp).padding(horizontal = 4.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        Icon(icon, contentDescription = null, tint = Night.Mint, modifier = Modifier.size(28.dp))
        Text(text, style = MaterialTheme.typography.titleSmall, maxLines = 1)
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
