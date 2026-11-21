package app.housemove.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
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
    val data = appData()
    val device = deviceInfo()
    Screen("Everything", back = false, tab = "more", actions = { TopActions() }) {
        ScrollColumn {
            // Every screen once, grouped by the phase of the move it belongs to.
            val seen = HashSet<String>()
            shownPhases(data).forEach { (_, phase) ->
                val skip = phase.steps.map { it.route }.filter { !seen.add(it) }.toSet()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(phase.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(phase.name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(phase.timing, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                PhaseTiles(phase, data, device, columns = 3, skipRoutes = skip)
            }
        }
    }
}

// ---- Settings --------------------------------------------------------------

@Composable
fun SettingsScreen() {
    val store = LocalStore.current
    val nav = LocalNav.current
    val data = appData()
    val device = deviceInfo()
    val move = data.move
    var confirmErase by remember { mutableStateOf(false) }
    Screen("Settings") {
        ScrollColumn {
            Section("The move") {
                TextInput(move.name, { v -> store.setMove { it.copy(name = v) } }, "Name", key = "name")
                if (move.kind != MoveKind.INVENTORY) {
                    DateButton(move.moveDay, { d -> store.setMove { it.copy(moveDay = d) } }, "Move day", Modifier.fillMaxWidth())
                }
                TextInput(move.originName, { v -> store.setMove { it.copy(originName = v) } }, "Moving from", key = "origin-${move.kind}")
                if (move.kind != MoveKind.INVENTORY) {
                    TextInput(move.destName, { v -> store.setMove { it.copy(destName = v) } }, "Moving to", key = "dest-${move.kind}")
                }
            }

            Section("What you use it for") {
                KindPicker()
            }

            Fold(
                "Parts of the app", Icons.Default.Tune,
                summary = Part.entries.count { data.uses(it) }.let { n -> if (n == Part.entries.size) "Everything is on" else "$n of ${Part.entries.size} on" },
            ) {
                Hint("Switch off what you don't need and it disappears from the app. Nothing is deleted.")
                ToggleTiles(
                    Part.entries.map { part ->
                        Toggle(part.label, part.icon(), data.uses(part)) { on ->
                            store.setMove { m -> m.copy(off = if (on) m.off - part.name else (m.off + part.name).distinct()) }
                        }
                    },
                    columns = 2,
                )
            }

            Fold(
                "This phone", Icons.Default.PhoneAndroid,
                summary = listOf(device.name.ifBlank { "No name" }, "letter ${device.letter}", if (device.helper) "helper" else null).filterNotNull().joinToString(" · "),
            ) {
                TextInput(device.name, { v -> store.setDevice { it.copy(name = v) } }, "Who uses this phone?", key = "dname")
                TextInput(
                    device.letter,
                    { v -> v.trim().uppercase().firstOrNull { it.isLetter() }?.let { c -> store.setDevice { it.copy(letter = c.toString()) } } },
                    "Letter for handwritten box codes", key = "dletter",
                )
                Hint("Each phone needs its own letter. This one writes ${device.letter}1, ${device.letter}2, …")
                SwitchRow(
                    "Helper mode", device.helper, { v -> store.setDevice { it.copy(helper = v) } },
                    sub = "Home shows only packing, loading, unloading and search.",
                )
            }

            Fold(
                "Sizes, money and book titles", Icons.Default.Straighten,
                summary = listOf(
                    "${fmtNum(move.vanCapacityM3)} m³ ${data.vehicle()}", if (move.useInches) "inches" else "cm",
                    move.currency.ifBlank { null }, if (device.bookLookup == true) "titles online" else null,
                ).filterNotNull().joinToString(" · "),
            ) {
                NumberInput(
                    move.vanCapacityM3, { v -> if (v != null && v > 0) store.setMove { it.copy(vanCapacityM3 = v) } },
                    "Load space of the ${data.vehicle()} (m³)", Modifier.fillMaxWidth(), key = "van",
                )
                Hint("Small van 6, long van 12, box van 15–20. Used to estimate trips.")
                SwitchRow("Furniture sizes in inches", move.useInches, { v -> store.setMove { it.copy(useInches = v) } })
                TextInput(move.currency, { v -> store.setMove { it.copy(currency = v.take(4)) } }, "Currency symbol", key = "cur")
                SwitchRow(
                    "Look up book titles online", device.bookLookup == true, { v -> store.setDevice { it.copy(bookLookup = v) } },
                    sub = "Sends only a book's barcode number to Open Library. Everything else stays offline.",
                )
            }

            Fold("Start again", Icons.Default.DeleteForever, summary = "Erase everything on this phone") {
                Hint("Removes every room, item, box, task and photo from this phone only.")
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
            store.setDevice { it.copy(nextSeq = 1, currentRoomId = null, currentTripId = null, lastSyncAt = 0, lastBackupAt = 0) }
            nav.navigate("setup") { popUpTo(0) { inclusive = true } }
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
            query.isBlank() -> EmptyState(Icons.Default.Search, "Find a box or an item", text = "Type what is in it, its name, its label or its room.")
            hits.isEmpty() -> EmptyState(Icons.Default.SearchOff, "Nothing matches \"$query\"", text = "Try one word. If another phone packed it, sync first.")
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
    var notRecorded by remember { mutableStateOf<String?>(null) }
    var typed by remember { mutableStateOf("") }

    fun handle(text: String) {
        if (WifiSync.Invite.parse(text) != null) {
            nav.open("sync")
            return
        }
        val code = labelFromQr(text)
        if (code.isBlank()) return
        val found = data.findByAnyCode(code)
        when {
            found != null -> nav.openMovable(found)
            // A shop barcode that nothing was recorded against is not a label to start a box with.
            !text.startsWith(QR_PREFIX) && looksLikeBarcodeNumber(code) -> notRecorded = code
            else -> unknown = code
        }
    }

    Screen("Scan a label") {
        QrScanner(
            onText = { if (unknown == null && notRecorded == null) handle(it) },
            modifier = Modifier.fillMaxWidth().weight(1f),
            formats = ScanFormats.ANY,
        )
        Hint(
            "Scan a HouseMove label, a book or product barcode you scanned into a box, or a device's serial number.",
            Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
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

    notRecorded?.let { code ->
        AlertDialog(
            onDismissRequest = { notRecorded = null },
            title = { Text("Barcode $code is not recorded") },
            text = { Text("Nothing with this barcode has been scanned into a box yet. To record it, open the box and tap \"Scan books and products into this box\".") },
            confirmButton = { TextButton(onClick = { notRecorded = null }) { Text("OK") } },
        )
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
