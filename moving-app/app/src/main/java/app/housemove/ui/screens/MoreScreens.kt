package app.housemove.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.housemove.data.*
import app.housemove.sync.WifiSync
import app.housemove.ui.*

// ---- More ------------------------------------------------------------------

@Composable
fun MoreScreen() {
    val nav = LocalNav.current
    val data = appData()
    val p = data.progress()
    Screen("More", back = false, tab = "more", actions = { TopActions() }) {
        LazyColumn(Modifier.weight(1f)) {
            item { GroupTitle("Plan") }
            item { MenuRow(Icons.Default.MeetingRoom, "Rooms", "Both homes, colours, door widths") { nav.open("rooms") } }
            item { MenuRow(Icons.Default.Rule, "Triage", "Move, sell, donate, bin or store", p.triage.toString()) { nav.open("triage") } }
            item { MenuRow(Icons.Default.Straighten, "Destination plan and fit check", "Where each item goes and whether it fits", p.destinations.toString()) { nav.open("plan") } }
            item { MenuRow(Icons.Default.Sell, "Sell, donate, bin", "Track what still has to go") { nav.open("disposal") } }
            item { MenuRow(Icons.Default.Checklist, "Timeline", "Tasks counted back from move day", p.tasks.toString()) { nav.open("tasks") } }
            item { MenuRow(Icons.Default.ShoppingCart, "Supplies and costs", "Shopping list, quotes, receipts") { nav.open("budget") } }
            item { HorizontalDivider() }
            item { GroupTitle("Pack") }
            item { MenuRow(Icons.Default.QrCode2, "Labels and door signs", "Print QR labels and room signs") { nav.open("labels") } }
            item { MenuRow(Icons.Default.Star, "Special lists", "Travels with me, First night, Hazardous") { nav.open("lists") } }
            item { MenuRow(Icons.Default.FormatListNumbered, "Packing order", "What to pack when, room by room", p.roomsPacked.toString()) { nav.open("packorder") } }
            item { HorizontalDivider() }
            item { GroupTitle("After the move") }
            item { MenuRow(Icons.Default.Unarchive, "Unpack", "Priority boxes first", p.unpacked.toString()) { nav.open("unpack") } }
            item { MenuRow(Icons.Default.Description, "Reports and close-out", "Inventory, damage report, wrap-up") { nav.open("reports") } }
            item { HorizontalDivider() }
            item { GroupTitle("Phones") }
            item { MenuRow(Icons.Default.Sync, "Sync and backup", "Share data between phones; save a backup") { nav.open("sync") } }
            item { MenuRow(Icons.Default.Settings, "Settings", "Move date, home names, van size") { nav.open("settings") } }
        }
    }
}

@Composable
private fun GroupTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

// ---- Settings --------------------------------------------------------------

@Composable
fun SettingsScreen() {
    val store = LocalStore.current
    val data = appData()
    val device = deviceInfo()
    val move = data.move
    var confirmErase by remember { mutableStateOf(false) }
    Screen("Settings") {
        ScrollColumn {
            Section("The move") {
                TextInput(move.name, { v -> store.setMove { it.copy(name = v) } }, "Name", key = "name")
                DateButton(move.moveDay, { d -> store.setMove { it.copy(moveDay = d) } }, "Move day", Modifier.fillMaxWidth())
                TextInput(move.originName, { v -> store.setMove { it.copy(originName = v) } }, "Old home", key = "origin")
                TextInput(move.destName, { v -> store.setMove { it.copy(destName = v) } }, "New home", key = "dest")
            }
            Section("Van") {
                NumberInput(
                    move.vanCapacityM3, { v -> if (v != null && v > 0) store.setMove { it.copy(vanCapacityM3 = v) } },
                    "Van load space (m³)", Modifier.fillMaxWidth(), key = "van",
                )
                Hint("Typical: small van 6 m³, long-wheelbase van 11–13 m³, box van with tail lift 15–20 m³. Used to estimate the number of trips.")
            }
            Section("Units and money") {
                SwitchRow("Enter furniture sizes in inches", move.useInches, { v -> store.setMove { it.copy(useInches = v) } })
                TextInput(move.currency, { v -> store.setMove { it.copy(currency = v.take(4)) } }, "Currency symbol", key = "cur")
            }
            Section("This phone") {
                TextInput(device.name, { v -> store.setDevice { it.copy(name = v) } }, "Who uses this phone?", key = "dname")
                TextInput(
                    device.letter,
                    { v -> v.trim().uppercase().firstOrNull { it.isLetter() }?.let { c -> store.setDevice { it.copy(letter = c.toString()) } } },
                    "Letter for handwritten box codes", key = "dletter",
                )
                Hint("Each phone needs a different letter, so two people writing codes by hand never produce the same one. This phone writes ${device.letter}1, ${device.letter}2, …")
            }
            Section("Start again") {
                Hint("Removes every room, item, box, task and photo from this phone only. Other phones keep their copy.")
                OutlinedButton(onClick = { confirmErase = true }) { Text("Erase all data on this phone") }
            }
        }
    }
    if (confirmErase) ConfirmDialog(
        title = "Erase everything on this phone?",
        text = "This cannot be undone. Save a backup first if you might want it back.",
        confirm = "Erase",
        onConfirm = {
            store.photoDir.listFiles()?.forEach { it.delete() }
            store.replaceAll(AppData())
            store.setDevice { it.copy(nextSeq = 1, currentRoomId = null, currentTripId = null, lastSyncAt = 0) }
        },
        onDismiss = { confirmErase = false },
    )
}

// ---- Search ----------------------------------------------------------------

@Composable
fun SearchScreen() {
    val data = appData()
    val nav = LocalNav.current
    var query by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        try {
            focus.requestFocus()
        } catch (_: Exception) {
        }
    }
    val hits = remember(query, data) { data.search(query) }
    Screen("Find anything") {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("What are you looking for?") },
            placeholder = { Text("kettle, passport, L042, bookcase…") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, contentDescription = "Clear") }
            },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
            modifier = Modifier.fillMaxWidth().padding(16.dp).focusRequester(focus),
        )
        when {
            query.isBlank() -> EmptyHint("Searches box contents, item names, label codes, notes and room names on this phone.")
            hits.isEmpty() -> EmptyHint("Nothing matches \"$query\". Try a single word, or sync with the other phones in case it was packed on one of them.")
            else -> LazyColumn(Modifier.weight(1f)) {
                items(hits, key = { it.movable.id }) { hit ->
                    Row(Modifier.fillMaxWidth()) {
                        OwnerThumb(hit.movable.id, Modifier.padding(start = 16.dp, top = 10.dp).size(48.dp), isBox = hit.movable.isBox)
                        Column(Modifier.weight(1f)) {
                            MovableRow(hit.movable, onClick = { nav.openMovable(hit.movable) })
                            Hint(
                                "Now: " + data.whereIs(hit.movable) + if (hit.detail.isBlank()) "" else "\n" + hit.detail.take(120),
                                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

// ---- Scan ------------------------------------------------------------------

/** Scan any label: opens the box or item, or offers to start a new box with an unused label. */
@Composable
fun ScanScreen() {
    val store = LocalStore.current
    val nav = LocalNav.current
    val data = appData()
    var unknown by remember { mutableStateOf<String?>(null) }
    var typed by remember { mutableStateOf("") }

    fun handle(text: String) {
        if (WifiSync.Invite.parse(text) != null) {
            nav.open("sync")
            return
        }
        val code = labelFromQr(text)
        if (code.isBlank()) return
        val found = data.findByLabel(code)
        if (found != null) nav.openMovable(found) else unknown = code
    }

    Screen("Scan a label") {
        QrScanner(onText = { if (unknown == null) handle(it) }, modifier = Modifier.fillMaxWidth().weight(1f))
        Row(Modifier.padding(12.dp)) {
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                label = { Text("Or type the code on the label") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(enabled = typed.isNotBlank(), onClick = { handle(typed); typed = "" }) { Text("Open") }
        }
    }

    unknown?.let { code ->
        AlertDialog(
            onDismissRequest = { unknown = null },
            title = { Text("Label $code is not in use") },
            text = { Text("Start a new box with this label? To put it on a piece of furniture instead, open the item and choose \"Scan a printed label\".") },
            confirmButton = {
                TextButton(onClick = {
                    val box = store.createBox(code)
                    unknown = null
                    nav.open("box/${box.id}")
                }) { Text("New box") }
            },
            dismissButton = { TextButton(onClick = { unknown = null }) { Text("Cancel") } },
        )
    }
}
