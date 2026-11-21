package app.housemove.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.housemove.data.*
import app.housemove.ui.*
import java.util.Locale

private fun oneDecimal(v: Double): String = String.format(Locale.US, "%.1f", v)

/**
 * Give every moving item a room in the new home and check it will get through the door.
 *
 * Nothing has to be picked by hand: each thing without a room is offered the room its old
 * room points to, and one button accepts them all. An Organiser's choice is the plan; a
 * Decider's is a suggestion that waits, shown on the item, for an Organiser to approve.
 */
@Composable
fun PlanScreen() {
    val store = LocalStore.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val data = appData()
    deviceInfo() // whose phone this is decides what is offered below
    val mayDecide = store.can(Action.DECIDE)
    val maySuggest = store.can(Action.SUGGEST)
    val me = store.me?.id

    // Counted as Home and the Items tab count them: something packed in a container goes where
    // that goes, so it needs no room of its own and is not listed here.
    val moving = data.looseItems()
    val seen = rememberSeenAnswers()
    val destRooms = data.roomsOf(Home.DEST)
    val homeless = moving.filter { data.room(it.destRoomId) == null }.sortedBy { it.createdAt }
    val waiting = data.waitingProposals().filter { it.kind == ProposalKind.ITEM_ROOM }.groupBy { it.targetId }
    val suggested = homeless.mapNotNull { x -> data.suggestDestRoom(x.originRoomId)?.let { x.id to it } }.toMap()
    // What one tap can settle: everything with a likely room, less what this person has already put forward.
    val toAccept = homeless.filter { x -> suggested[x.id] != null && (mayDecide || waiting[x.id].orEmpty().none { it.by == me }) }
    val inches = data.move.useInches
    val vehicle = data.vehicle()

    fun edit(itemId: String, f: (Item) -> Item) {
        store.data.value.item(itemId)?.let { store.put(f(it)) }
    }

    Screen("Room plan") {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (destRooms.isEmpty()) {
                item(key = "norooms") {
                    EmptyState(
                        Pic.Room, "Add the new home's rooms",
                        text = "Each room gets a colour, so things go straight to the right door.",
                        action = "Add rooms", onAction = { nav.open("rooms") },
                    )
                }
            } else if (moving.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        Icons.Default.Map, "Nothing to place yet",
                        text = "Things you mark Move appear here to be given a room.",
                        action = if (data.uses(Part.TRIAGE)) "Decide what moves" else "Start the walk-through",
                        onAction = { nav.open(if (data.uses(Part.TRIAGE)) "triage" else "walk") },
                    )
                }
            } else {
                item(key = "summary") {
                    val wontFit = moving.count { data.fitOf(it) == Fit.NO && !it.needsDisassembly }
                    val asked = waiting.values.sumOf { it.size }
                    ProgressCard(
                        Ratio(moving.size - homeless.size, moving.size),
                        if (homeless.isEmpty()) "Everything has a room" else "${moving.size - homeless.size} of ${moving.size} have a room",
                    ) {
                        if (wontFit > 0 || asked > 0) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (wontFit > 0) IconFlag(Icons.Default.Warning, "$wontFit won't fit", error = true)
                                if (asked > 0) IconFlag(Icons.Default.HourglassTop, "$asked waiting for approval")
                            }
                        }
                    }
                }
            }

            if (homeless.isNotEmpty()) {
                if (toAccept.isNotEmpty() && maySuggest) {
                    item(key = "accept") {
                        BigAction(
                            Icons.Default.DoneAll,
                            if (mayDecide) "Accept all suggestions" else "Suggest all ${toAccept.size} as shown",
                            onClick = {
                                var ok = true
                                for (x in toAccept) ok = ok && store.planOrSuggestRoom(context, x.id, suggested.getValue(x.id).id)
                            },
                        )
                    }
                }
                item(key = "needs") {
                    ListHeading("Needs a room", icon = Pic.Room, count = homeless.size)
                }
                items(homeless, key = { "h-" + it.id }) { x ->
                    HomelessCard(
                        x = x,
                        data = data,
                        suggestion = suggested[x.id],
                        waiting = waiting[x.id].orEmpty(),
                        declined = data.declinedOn(x.id, store.me, seen.ids, ProposalKind.ITEM_ROOM),
                        onSeen = { seen.mark(it.id) },
                        maySuggest = maySuggest && destRooms.isNotEmpty(),
                        onOpen = { nav.open("item/${x.id}") },
                        onPick = { rid -> store.planOrSuggestRoom(context, x.id, rid) },
                    )
                }
            }

            if (moving.isNotEmpty()) {
                // Rooms with something going to them get a block each; the rest share one quiet line.
                val (used, unused) = destRooms.partition { r -> moving.any { it.destRoomId == r.id } }
                items(used, key = { "r-" + it.id }) { room ->
                    val here = moving.filter { it.destRoomId == room.id }.sortedBy { it.name.lowercase() }
                    RoomBlock(
                        room = room,
                        here = here,
                        data = data,
                        inches = inches,
                        waiting = waiting,
                        declined = { x -> data.declinedOn(x.id, store.me, seen.ids, ProposalKind.ITEM_ROOM) },
                        onSeen = { seen.mark(it.id) },
                        onOpen = { nav.open("item/${it.id}") },
                        onTakeApart = { x -> edit(x.id) { it.copy(needsDisassembly = true) } },
                        onAddDoor = { nav.open("rooms") },
                    )
                }
                if (unused.isNotEmpty()) {
                    item(key = "unused") {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Nothing going here yet", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                unused.forEach { RoomBadge(it) }
                            }
                        }
                    }
                }
            }

            val v = data.volume()
            if (v.totalM3 > 0.0) item(key = "load") {
                Fold(
                    "${vehicle.replaceFirstChar { it.uppercase() }} load estimate", Icons.Default.LocalShipping,
                    summary = "About ${oneDecimal(v.totalM3)} m³ · ${v.trips} trip${if (v.trips == 1) "" else "s"}",
                    key = "load",
                ) {
                    Text(
                        "About ${oneDecimal(v.totalM3)} m³ (${oneDecimal(v.totalM3 * 35.31)} ft³)",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        "That is about ${v.trips} trip${if (v.trips == 1) "" else "s"} in a ${fmtNum(data.move.vanCapacityM3)} m³ $vehicle.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        "Furniture ${oneDecimal(v.itemsM3)} m³ + boxes ${oneDecimal(v.boxesM3)} m³, plus some slack.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (v.itemsWithoutSize > 0) {
                        IconFlag(
                            Pic.Size,
                            "${v.itemsWithoutSize} item${if (v.itemsWithoutSize == 1) "" else "s"} with no size, counted at $DEFAULT_ITEM_M3 m³ each",
                        )
                    }
                }
            }
        }
    }
}

/**
 * Something that is moving and has no room yet. The likely room is already picked: one tap
 * on it settles the matter, and "Other room" shows them all. With nothing to go on, the
 * rooms are simply there to choose from.
 */
@Composable
private fun HomelessCard(
    x: Item,
    data: AppData,
    suggestion: Room?,
    waiting: List<Proposal>,
    declined: Proposal?,
    onSeen: (Proposal) -> Unit,
    maySuggest: Boolean,
    onOpen: () -> Unit,
    onPick: (String) -> Unit,
) {
    val mayDecide = allowed(Action.DECIDE)
    var choosing by rememberSaveable(x.id) { mutableStateOf(false) }
    GlassCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onOpen),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ItemPhoto(x.id, Modifier.size(60.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(x.title(), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "From " + (data.room(x.originRoomId)?.name ?: "no room"), style = MaterialTheme.typography.bodySmall,
                        color = Night.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Night.TextDim)
            }
            waiting.forEach { p -> Suggestion(p, data) }
            // The room this person put forward was turned down: they are told why, once.
            if (declined != null) DeclinedNote(declined, data, what = data.proposalText(declined), onSeen = { onSeen(declined) })
            if (maySuggest) {
                // Someone has already put a room forward: that is the thing to settle, so the likely room steps back.
                val settled = waiting.isNotEmpty()
                when {
                    choosing || (suggestion == null && !settled) ->
                        RoomTiles(Home.DEST, if (settled) null else suggestion?.id, onPick = { rid -> if (rid != null) onPick(rid) }, allowNone = false)

                    settled -> GlassChip("Other room", onClick = { choosing = true })

                    suggestion != null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassPill(
                            Modifier.weight(1f), tone = GlassTone.Mint, onClick = { onPick(suggestion.id) },
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        ) {
                            ColorDot(suggestion.color, Modifier.size(18.dp))
                            Spacer(Modifier.width(10.dp))
                            // Two lines on a small phone rather than "Main bedr…".
                            Text(
                                suggestion.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(Icons.Default.Check, contentDescription = if (mayDecide) "Accept" else "Suggest", tint = Night.MintPale, modifier = Modifier.size(20.dp))
                        }
                        GlassChip("Other room", onClick = { choosing = true })
                    }
                }
            }
        }
    }
}

/**
 * One new-home room: its colour as a bar and a line of light under its name, then what is
 * going there and whether each thing fits through its door.
 */
@Composable
private fun RoomBlock(
    room: Room,
    here: List<Item>,
    data: AppData,
    inches: Boolean,
    waiting: Map<String, List<Proposal>>,
    declined: (Item) -> Proposal?,
    onSeen: (Proposal) -> Unit,
    onOpen: (Item) -> Unit,
    onTakeApart: (Item) -> Unit,
    onAddDoor: () -> Unit,
) {
    val colour = roomColor(room.color).color()
    val door = room.doorWidthCm?.takeIf { it > 0 }
    GlassCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), sheen = true) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp).heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(width = 10.dp, height = 36.dp).clip(CircleShape).background(colour).border(1.dp, Color.White.copy(alpha = 0.45f), CircleShape))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(room.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${here.size} item${if (here.size == 1) "" else "s"}", color = Night.TextDim, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
            // On a small screen or with large text the room's name comes first, so the door says less.
            val narrow = narrowScreen()
            if (door != null) {
                val width = if (inches) "${fmtNum(door / 2.54)} in" else "${fmtNum(door)} cm"
                GlassChip(if (narrow) width else "$width door", icon = Icons.Default.DoorFront)
                Spacer(Modifier.width(6.dp))
            } else {
                GlassChip(if (narrow) "Door" else "Add door width", icon = if (narrow) Icons.Default.Add else Icons.Default.DoorFront, onClick = onAddDoor)
            }
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(Brush.horizontalGradient(listOf(colour, colour.copy(alpha = 0.12f)))))
        if (here.isNotEmpty()) {
            Column(Modifier.padding(vertical = 6.dp)) {
                here.forEach { x ->
                    val fit = data.fitOf(x)
                    val stuck = fit == Fit.NO && !x.needsDisassembly
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpen(x) }.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ItemPhoto(x.id, Modifier.size(48.dp), RoundedCornerShape(12.dp), iconSize = 22.dp)
                        Text(
                            x.title(), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        when (fit) {
                            Fit.OK -> Icon(Icons.Default.CheckCircle, contentDescription = "Fits", tint = Night.MintLight, modifier = Modifier.size(20.dp))
                            Fit.TIGHT -> IconFlag(Icons.Default.Warning, "Tight fit")
                            Fit.NO -> if (x.needsDisassembly) IconFlag(Pic.TakeApart, "Take apart") else IconFlag(Icons.Default.Warning, "Won't fit", error = true)
                            Fit.UNKNOWN -> Unit
                        }
                    }
                    if (stuck) {
                        Row(Modifier.fillMaxWidth().padding(end = 4.dp), horizontalArrangement = Arrangement.End) {
                            GlassChip("Mark: take apart", icon = Pic.TakeApart, onClick = { onTakeApart(x) })
                        }
                    }
                    // Someone would rather it went elsewhere: the suggestion sits on the item it is about.
                    waiting[x.id].orEmpty().forEach { p ->
                        Box(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                            Suggestion(p, data)
                        }
                    }
                    declined(x)?.let { p ->
                        DeclinedNote(p, data, Modifier.padding(horizontal = 12.dp, vertical = 4.dp), what = data.proposalText(p), onSeen = { onSeen(p) })
                    }
                }
            }
        }
    }
}
