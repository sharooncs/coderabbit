package app.housemove.ui.iphone

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role as SemanticsRole
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.housemove.data.AppData
import app.housemove.data.Box as MoveBox
import app.housemove.data.Disposition
import app.housemove.data.Home
import app.housemove.data.Item
import app.housemove.data.Priority
import app.housemove.data.Room
import app.housemove.data.Stage
import app.housemove.data.Store
import app.housemove.data.childrenOf
import app.housemove.data.containerOf
import app.housemove.data.itemsIn
import app.housemove.data.parentOf
import app.housemove.data.roomColor
import app.housemove.data.search
import app.housemove.data.title
import app.housemove.data.toMovable
import app.housemove.data.vehicle
import app.housemove.data.whereIs
import app.housemove.ui.Night
import app.housemove.ui.icons.Icons
import app.housemove.ui.icons.filled.Chair
import app.housemove.ui.icons.filled.ChevronRight
import app.housemove.ui.icons.filled.Close
import app.housemove.ui.icons.filled.Inventory2
import app.housemove.ui.icons.filled.PhonelinkErase
import app.housemove.ui.icons.filled.Search
import app.housemove.ui.icons.filled.SearchOff
import app.housemove.ui.icons.filled.Sync

/*
 * The move, read-only: its rooms (with their colours and how many things and boxes each holds), one room's items and
 * boxes with where each one is, and a search. The same facts the Android app shows, from the same shared code
 * (whereIs, search, Box.title).
 */

/** The things of one room: for an old-home room what is in it, for a new-home room what goes there. */
internal class RoomThings(val items: List<Item>, val boxes: List<MoveBox>) {
    val text: String
        get() = if (items.isEmpty() && boxes.isEmpty()) "Nothing yet"
        else Words.count(items.size, "item") + " · " + Words.count(boxes.size, "box", "boxes")
}

internal fun AppData.thingsOf(roomId: String): RoomThings {
    val room = room(roomId)
    fun mine(origin: String?, dest: String?) = when {
        roomId == Page.NO_ROOM -> room(origin) == null
        room?.home == Home.DEST -> dest == roomId
        else -> origin == roomId
    }
    return RoomThings(
        liveItems.filter { mine(it.originRoomId, it.destRoomId) }.sortedBy { it.title().lowercase() },
        liveBoxes.filter { mine(it.originRoomId, it.destRoomId) }.sortedBy { it.label.lowercase() },
    )
}

/** How an item stands, in a word or two, as the Android app's item cards say it. */
internal fun AppData.stateOf(x: Item): String = when (x.disposition) {
    Disposition.UNDECIDED -> "Undecided"
    Disposition.MOVE -> when (x.stage) {
        Stage.AT_ORIGIN -> "Move"
        Stage.LOADED -> "On the ${vehicle()}"
        Stage.STORED -> "In storage"
        Stage.DELIVERED -> "Delivered"
        Stage.DONE -> "In place"
    }
    Disposition.SELL -> if (x.gone) "Sold" else if (x.listed) "Listed" else "To sell"
    Disposition.DONATE -> if (x.gone) "Donated" else "Donate"
    Disposition.BIN -> if (x.gone) "Binned" else "Bin"
    Disposition.STORE -> "Store"
}

// ---- rooms -----------------------------------------------------------------------------------------------------

@Composable
fun RoomsScreen(model: AppModel, store: Store, listState: LazyListState = rememberLazyListState()) {
    val data by store.data.collectAsState()
    val device by store.device.collectAsState()
    val me = data.person(device.personId)
    val origin = data.roomsOf(Home.ORIGIN)
    val dest = data.roomsOf(Home.DEST)
    val homeless = remember(data) { data.thingsOf(Page.NO_ROOM) }
    Frame(
        data.move.name.ifBlank { "Your move" },
        subtitle = me?.let { "You are ${it.name.ifBlank { "on the move" }} · ${it.role.label}" },
        bottom = { BigButton("Get fresh data", Icons.Filled.Sync, onClick = { model.open(Page.Fresh) }) },
    ) {
        LazyColumn(
            Modifier.fillMaxSize().testTag("rooms"),
            state = listState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            model.welcome?.let { w ->
                item(key = "welcome") {
                    Panel(tone = Tone.Mint) {
                        Text(w.title, style = MaterialTheme.typography.titleMedium, color = Night.Text)
                        Text(w.text, style = MaterialTheme.typography.bodyMedium, color = Night.Text)
                        SmallButton("Got it", null, onClick = { model.welcomeRead() })
                    }
                }
            }
            item(key = "search") {
                Row(
                    Modifier.fillMaxWidth().glass(RoundedCornerShape(26.dp)).clickable(role = SemanticsRole.Button) { model.open(Page.Search) }
                        .heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Search, contentDescription = null, tint = Night.MintLight)
                    Spacer(Modifier.width(12.dp))
                    Text("Search items and boxes", style = MaterialTheme.typography.bodyLarge, color = Night.TextSoft)
                }
            }
            if (origin.isEmpty() && dest.isEmpty()) {
                item(key = "empty") {
                    Panel {
                        Text("No rooms yet", style = MaterialTheme.typography.titleMedium, color = Night.Text)
                        Hint("Add rooms on the Android phone, then get fresh data.")
                    }
                }
            }
            if (origin.isNotEmpty() || !homeless.let { it.items.isEmpty() && it.boxes.isEmpty() }) {
                item(key = "origin") { Heading(data.move.originName.ifBlank { "Old home" }, Modifier.padding(top = 6.dp)) }
                items(origin, key = { it.id }) { r -> RoomRow(r, data.thingsOf(r.id)) { model.open(Page.Room(r.id)) } }
                if (homeless.items.isNotEmpty() || homeless.boxes.isNotEmpty()) {
                    item(key = "homeless") { RoomRow(null, homeless) { model.open(Page.Room(Page.NO_ROOM)) } }
                }
            }
            if (dest.isNotEmpty()) {
                item(key = "dest") { Heading(data.move.destName.ifBlank { "New home" }, Modifier.padding(top = 6.dp)) }
                items(dest, key = { it.id }) { r -> RoomRow(r, data.thingsOf(r.id)) { model.open(Page.Room(r.id)) } }
            }
            item(key = "end") {
                Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Hint(if (device.lastSyncAt == 0L) "Not synced yet." else "Last synced ${Words.ago(device.lastSyncAt)}.")
                    Hint(Words.READ_ONLY)
                    BigButton(
                        "Leave this move on this iPhone", Icons.Filled.PhonelinkErase, onClick = { model.askToLeave(true) },
                        primary = false, tone = Tone.Red, enabled = !model.busy,
                    )
                    Hint(model.buildLabel, Modifier.fillMaxWidth(), center = true)
                }
            }
        }
    }
    if (model.askingToLeave) LeaveDialog(data.move.name.ifBlank { "this move" }, onLeave = { model.leave() }, onKeep = { model.askToLeave(false) })
}

/** One room: its colour, its name, the colour's name and how many things and boxes. [room] null: things with no room. */
@Composable
private fun RoomRow(room: Room?, things: RoomThings, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().glass().clickable(role = SemanticsRole.Button, onClick = onClick)
            .heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (room != null) Swatch(room.color) else Box(Modifier.size(28.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(room?.name ?: "Not in a room", style = MaterialTheme.typography.titleMedium, color = Night.Text)
            val colour = room?.let { roomColor(it.color).name }
            Text(listOfNotNull(colour, things.text).joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = Night.TextDim)
        }
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = Night.TextDim)
    }
}

@Composable
private fun LeaveDialog(move: String, onLeave: () -> Unit, onKeep: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeep,
        containerColor = Night.Panel,
        titleContentColor = Night.Text,
        textContentColor = Night.TextSoft,
        title = { Text("Leave $move on this iPhone?", style = MaterialTheme.typography.titleLarge) },
        text = {
            // Scrolls at the largest text sizes, so the buttons below stay on the screen.
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "This iPhone forgets the move: its rooms, items and boxes. The Android phone keeps all of it, and nothing is sent to it. You can join again later with a new invite.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onLeave, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Leave", color = Night.Red, style = MaterialTheme.typography.titleMedium)
            }
        },
        dismissButton = {
            TextButton(onClick = onKeep, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Keep it", color = Night.MintLight, style = MaterialTheme.typography.titleMedium)
            }
        },
    )
}

// ---- one room ----------------------------------------------------------------------------------------------------

@Composable
fun RoomScreen(model: AppModel, store: Store, roomId: String, listState: LazyListState = rememberLazyListState()) {
    val data by store.data.collectAsState()
    val room = data.room(roomId)
    val things = remember(data, roomId) { data.thingsOf(roomId) }
    val home = when {
        room == null -> data.move.originName.ifBlank { "Old home" }
        room.home == Home.ORIGIN -> data.move.originName.ifBlank { "Old home" }
        else -> data.move.destName.ifBlank { "New home" }
    }
    val title = room?.name ?: if (roomId == Page.NO_ROOM) "Not in a room" else "Room"
    Frame(title, onBack = { model.back() }, subtitle = home) {
        LazyColumn(
            Modifier.fillMaxSize().testTag("room"),
            state = listState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "head") {
                Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
                    if (room != null) {
                        Swatch(room.color, size = 36)
                        Spacer(Modifier.width(12.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        val colour = room?.let { "${roomColor(it.color).name}${if (it.code.isNotBlank()) " · door sign ${it.code}" else ""}" }
                        if (colour != null) Text(colour, style = MaterialTheme.typography.titleMedium, color = Night.Text)
                        Text(
                            when {
                                room == null -> "Things not in any room of the old home"
                                room.home == Home.DEST -> "What goes to this room: ${things.text.lowercase()}"
                                else -> "What is in this room: ${things.text.lowercase()}"
                            },
                            style = MaterialTheme.typography.bodyMedium, color = Night.TextDim,
                        )
                    }
                }
            }
            if (room == null && roomId != Page.NO_ROOM) {
                item(key = "gone") { Panel { Text("This room is no longer in the move.", color = Night.Text) } }
            }
            if (things.items.isEmpty() && things.boxes.isEmpty() && room != null) {
                item(key = "nothing") {
                    Panel {
                        Text("Nothing here yet", style = MaterialTheme.typography.titleMedium, color = Night.Text)
                        Hint("Things added on the Android phone show here after you get fresh data.")
                    }
                }
            }
            if (things.items.isNotEmpty()) {
                item(key = "items") { Heading("Items", Modifier.padding(top = 6.dp)) }
                items(things.items, key = { "i" + it.id }) { ItemCard(it, data) }
            }
            if (things.boxes.isNotEmpty()) {
                item(key = "boxes") { Heading("Boxes", Modifier.padding(top = 6.dp)) }
                items(things.boxes, key = { "b" + it.id }) { BoxCard(it, data) }
            }
        }
    }
}

/** An item: what it is, how it stands, where it is (or which box it is in), where it goes, and what to take care with. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ItemCard(x: Item, data: AppData, detail: String = "") {
    val container = data.containerOf(x)
    val moving = x.disposition == Disposition.MOVE || x.disposition == Disposition.STORE
    val dest = data.room(x.destRoomId)?.takeIf { moving && (container == null || x.destRoomId != container.destRoomId) }
    ThingCard(Icons.Filled.Chair) {
        Text(x.title(), style = MaterialTheme.typography.titleMedium, color = Night.Text)
        val where = data.whereIs(x.toMovable())
        val state = data.stateOf(x)
        // "On the van" is said once, by where it is.
        if (!where.startsWith(state)) {
            Text(state, style = MaterialTheme.typography.labelLarge, color = if (x.disposition == Disposition.UNDECIDED) Night.AmberPale else Night.MintLight)
        }
        Text(where, style = MaterialTheme.typography.bodyMedium, color = Night.TextSoft)
        if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodyMedium, color = Night.TextDim)
        val flags = listOfNotNull(
            if (x.fragile) "Fragile" else null, if (x.keepUpright) "Upright" else null, if (x.twoPerson) "Two people" else null,
            if (x.firstNight) "First night" else null,
        )
        if (dest != null || flags.isNotEmpty() || x.damaged) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (dest != null) GoesTo(dest)
                flags.forEach { Flag(it) }
                if (x.damaged) Flag("Damaged", error = true)
            }
        }
    }
}

/** A box: its label and contents, where it is, where it goes, what is packed in it, and what to take care with. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BoxCard(b: MoveBox, data: AppData, detail: String = "") {
    val dest = data.room(b.destRoomId)
    val inside = (data.itemsIn(b.id).map { it.title() } + data.childrenOf(b.id).map { it.title() })
    ThingCard(Icons.Filled.Inventory2) {
        Text(b.title(), style = MaterialTheme.typography.titleMedium, color = Night.Text)
        Text(data.whereIs(b.toMovable()), style = MaterialTheme.typography.bodyMedium, color = Night.TextSoft)
        if (inside.isNotEmpty()) Text("Holds: " + inside.joinToString(", "), style = MaterialTheme.typography.bodyMedium, color = Night.TextDim)
        if (detail.isNotBlank() && !b.title().contains(detail.take(40))) Text(detail, style = MaterialTheme.typography.bodyMedium, color = Night.TextDim)
        val flags = listOfNotNull(
            if (b.fragile) "Fragile" else null, if (b.thisWayUp) "Upright" else null, if (b.heavy) "Heavy" else null,
            if (b.priority == Priority.FIRST_NIGHT) "First night" else null,
        )
        if (dest != null || flags.isNotEmpty() || b.damaged) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (dest != null) GoesTo(dest)
                flags.forEach { Flag(it) }
                if (b.damaged) Flag("Damaged", error = true)
            }
        }
    }
}

@Composable
private fun GoesTo(room: Room) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Goes to ", style = MaterialTheme.typography.bodyMedium, color = Night.TextDim)
        RoomTag(room.name, room.color)
    }
}

/** A glass card for one thing, read by VoiceOver as one line. */
@Composable
private fun ThingCard(icon: androidx.compose.ui.graphics.vector.ImageVector, content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().glass().semantics(mergeDescendants = true) {}.padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, contentDescription = null, tint = Night.MintLight, modifier = Modifier.padding(top = 2.dp).size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
    }
}

// ---- search ------------------------------------------------------------------------------------------------------

@Composable
fun SearchScreen(model: AppModel, store: Store, focus: Boolean = true) {
    val data by store.data.collectAsState()
    val query = model.query
    val hits = remember(query, data) { data.search(query) }
    val focuser = remember { FocusRequester() }
    if (focus) {
        LaunchedEffect(Unit) {
            try {
                focuser.requestFocus()
            } catch (_: Exception) {
            }
        }
    }
    Frame("Search", onBack = { model.back() }) {
        WordsField(
            query, { model.query = it }, "What are you looking for?",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).focusRequester(focuser).testTag("search-field"),
            leading = Icons.Filled.Search,
            trailing = if (query.isEmpty()) null else {
                { IconButton(onClick = { model.query = "" }) { Icon(Icons.Filled.Close, contentDescription = "Clear") } }
            },
        )
        when {
            query.isBlank() -> Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Search, contentDescription = null, tint = Night.MintLight, modifier = Modifier.size(40.dp))
                Spacer(Modifier.size(12.dp))
                Text("Find an item or a box", style = MaterialTheme.typography.titleMedium, color = Night.Text, textAlign = TextAlign.Center)
                Spacer(Modifier.size(6.dp))
                Hint("Type its name, what is in a box, a label such as A1, or a room.", center = true)
            }
            hits.isEmpty() -> Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.SearchOff, contentDescription = null, tint = Night.TextDim, modifier = Modifier.size(40.dp))
                Spacer(Modifier.size(12.dp))
                Text("Nothing matches \"$query\"", style = MaterialTheme.typography.titleMedium, color = Night.Text, textAlign = TextAlign.Center)
                Spacer(Modifier.size(6.dp))
                Hint("Try one word. If it was added on the Android phone just now, get fresh data first.", center = true)
            }
            else -> LazyColumn(
                Modifier.fillMaxSize().testTag("hits"),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "count") { Hint(Words.count(hits.size, "match", "matches")) }
                items(hits, key = { it.movable.id }) { hit ->
                    val item = if (hit.movable.isBox) null else data.item(hit.movable.id)
                    val box = if (hit.movable.isBox) data.box(hit.movable.id) else null
                    // Where it is packed is said already; the rest of what matched (a make, a note, a book in a box) is shown.
                    val packed = when {
                        item != null -> data.containerOf(item)?.let { "In ${it.title()}" }
                        box != null -> data.parentOf(box)?.let { "Inside ${it.title()}" }
                        else -> null
                    }
                    val detail = if (packed != null) hit.detail.removePrefix(packed).removePrefix(" · ") else hit.detail
                    when {
                        item != null -> ItemCard(item, data, detail)
                        box != null -> BoxCard(box, data, detail)
                    }
                }
            }
        }
    }
}
