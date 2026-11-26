package app.housemove.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import app.housemove.ui.icons.Icons
import app.housemove.ui.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
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
            // Every screen once, grouped by the phase of the move it belongs to. Three tiles to a row,
            // two on a narrow screen or with large text, so titles and statuses stay whole.
            val columns = if (tightScreen()) 2 else 3
            val seen = HashSet<String>()
            // Screens this phone cannot offer yet are left out (unavailableRoutes; none on Android).
            val unavailable = unavailableRoutes()
            shownPhases(data).forEach { (_, phase) ->
                val skip = phase.steps.map { it.route }.filter { !seen.add(it) }.toSet() + unavailable
                // A phase none of whose screens are for this person is left out, heading and all.
                if (phase.steps.none { it.route !in skip && it.shownFor(data, device) }) return@forEach
                Row(Modifier.semantics(mergeDescendants = true) { heading() }, verticalAlignment = Alignment.CenterVertically) {
                    Icon(phase.icon, contentDescription = null, tint = Night.Mint, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(phase.name, style = MaterialTheme.typography.titleSmall, color = Night.MintPale)
                    Spacer(Modifier.width(8.dp))
                    Text(phase.timing, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                PhaseTiles(phase, data, device, columns = columns, skipRoutes = skip)
            }
        }
    }
}

// ---- Settings --------------------------------------------------------------

/** Load spaces offered as one-tap choices, with what they are. Anything else is typed behind "Other". */
private val VAN_SIZES = listOf(6.0 to "Small van", 12.0 to "Long van", 15.0 to "Box van", 20.0 to "Large truck")

/** Currency signs offered as one-tap choices. A blank one shows amounts as bare numbers. */
private val CURRENCIES = listOf("£", "$", "€", "")

/**
 * Settings. What belongs to the move (its date, names, what it is for, which parts show)
 * is an Organiser's to change, goes through the Store's guarded functions, and is not
 * shown as something to change to anyone else. What belongs to this phone is always here.
 * Nothing needs the keyboard: the few text fields are pre-filled and inside closed folds.
 */
@Composable
fun SettingsScreen() {
    val store = LocalStore.current
    val nav = LocalNav.current
    val context = LocalPlatform.current
    val data = appData()
    val device = deviceInfo()
    val move = data.move
    val mine = myPerson()
    val shared = data.shared()
    val mayChangeMove = allowed(Action.MOVE_SETTINGS)
    val mayErase = allowed(Action.ERASE)
    var confirmErase by remember { mutableStateOf(false) }

    /** Change the move through the Store's guarded door; a refusal is shown, never swallowed. */
    fun change(f: (MoveInfo) -> MoveInfo) {
        store.changeMove(f).orSay(context)
    }

    Screen("Settings") {
        ScrollColumn {
            // On a shared move: who this phone is and what its role can do. An Organiser changes roles under People.
            if (shared && mine != null) {
                PersonPill(mine, "You are ${mine.shownName()}", mine.role.line(), sheen = true, onClick = { nav.open("people") })
            }

            if (mayChangeMove) {
                if (move.kind != MoveKind.INVENTORY) {
                    DateButton(move.moveDay, { d -> change { it.copy(moveDay = d) } }, "Move day", Modifier.fillMaxWidth().heightIn(min = 56.dp))
                }
                Fold(
                    "Names", Icons.Default.Edit,
                    summary = listOfNotNull(
                        move.name.ifBlank { null },
                        if (move.kind == MoveKind.INVENTORY) move.originName else move.originName + " → " + move.destName,
                    ).joinToString(" · "),
                ) {
                    TextInput(move.name, { v -> change { it.copy(name = v) } }, "Name of the move", key = "name", mic = true)
                    TextInput(move.originName, { v -> change { it.copy(originName = v) } }, "Moving from", key = "origin-${move.kind}")
                    if (move.kind != MoveKind.INVENTORY) {
                        TextInput(move.destName, { v -> change { it.copy(destName = v) } }, "Moving to", key = "dest-${move.kind}")
                    }
                }
                Fold("What you use it for", move.kind.icon(), summary = move.kind.label) {
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
                                change { m -> m.copy(off = if (on) m.off - part.name else (m.off + part.name).distinct()) }
                            }
                        },
                        columns = 2,
                    )
                }
            } else {
                // Not this person's to change, so shown as facts, not as controls that would refuse.
                Section("The move") {
                    Text(move.name.ifBlank { "HouseMove" }, style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Event, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (move.kind == MoveKind.INVENTORY) move.originName
                            else (move.moveDay?.let { fmtDay(it) } ?: "No move day yet") + " · " + move.originName + " → " + move.destName,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Hint("An Organiser looks after the date, the names and what the app shows.")
                }
            }

            Fold(
                "This phone", Icons.Default.PhoneAndroid,
                summary = listOfNotNull(device.name.ifBlank { "No name" }, "letter ${device.letter}", if (device.helper && !shared) "helper" else null).joinToString(" · "),
            ) {
                TextInput(device.name, { v -> store.setDevice { it.copy(name = v) } }, "Your name", key = "dname", mic = true)
                TextInput(
                    device.letter,
                    { v ->
                        val letter = v.trim().uppercase().firstOrNull { it.isLetter() }?.toString()
                        // The Store says so when someone else already has the letter.
                        if (letter != null && letter != device.letter) {
                            if (mine != null) store.setLetter(mine.id, letter).orSay(context) else store.setDevice { it.copy(letter = letter) }
                        }
                    },
                    "Letter for handwritten box codes", key = "dletter",
                )
                Hint("This phone writes ${device.letter}1, ${device.letter}2, … Everyone has their own letter.")
                // Alone, the old switch still works. On a shared move the role above takes its place.
                if (!shared) {
                    SwitchRow(
                        "Helper mode", device.helper, { v -> store.setDevice { it.copy(helper = v) } },
                        sub = "Home shows only packing, loading, unloading and search.",
                    )
                }
            }

            if (mayChangeMove) {
                Fold(
                    "Sizes, money and book titles", Icons.Default.Straighten,
                    summary = listOf(
                        "${fmtNum(move.vanCapacityM3)} m³ ${data.vehicle()}", if (move.useInches) "inches" else "cm",
                        move.currency.ifBlank { null }, if (device.bookLookup == true) "titles online" else null,
                    ).filterNotNull().joinToString(" · "),
                ) {
                    Text("Load space of the ${data.vehicle()}", style = MaterialTheme.typography.labelLarge)
                    var otherSize by rememberSaveable { mutableStateOf(VAN_SIZES.none { it.first == move.vanCapacityM3 }) }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        VAN_SIZES.forEach { (m3, what) ->
                            GlassChip("$what · ${fmtNum(m3)} m³", selected = !otherSize && move.vanCapacityM3 == m3, onClick = {
                                otherSize = false
                                change { it.copy(vanCapacityM3 = m3) }
                            })
                        }
                        GlassChip("Other", selected = otherSize, onClick = { otherSize = true })
                    }
                    if (otherSize) {
                        NumberInput(
                            move.vanCapacityM3, { v -> if (v != null && v > 0) change { it.copy(vanCapacityM3 = v) } },
                            "Load space (m³)", Modifier.fillMaxWidth(), key = "van", kind = NumberKind.VOLUME,
                        )
                    }
                    SwitchRow("Furniture sizes in inches", move.useInches, { v -> change { it.copy(useInches = v) } })
                    Text("Money", style = MaterialTheme.typography.labelLarge)
                    var otherMoney by rememberSaveable { mutableStateOf(move.currency !in CURRENCIES) }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CURRENCIES.forEach { c ->
                            GlassChip(c.ifBlank { "No sign" }, selected = !otherMoney && move.currency == c, onClick = {
                                otherMoney = false
                                change { it.copy(currency = c) }
                            })
                        }
                        GlassChip("Other sign", selected = otherMoney, onClick = { otherMoney = true })
                    }
                    if (otherMoney) TextInput(move.currency, { v -> change { it.copy(currency = v.take(4)) } }, "Currency sign", key = "cur")
                    BookLookupSwitch()
                }
            } else {
                Fold("Book titles", Icons.Default.MenuBook, summary = if (device.bookLookup == true) "Looked up online" else "Not looked up") {
                    BookLookupSwitch()
                }
            }

            // Erasing is an Organiser's to do; nobody else is shown it.
            if (mayErase) {
                Fold("Start again", Icons.Default.DeleteForever, summary = "Erase everything on this phone") {
                    Hint("Removes every room, item, box, task and photo from this phone only.")
                    PillButton("Erase all data on this phone", onClick = { confirmErase = true }, icon = Icons.Default.DeleteForever, primary = false, tone = GlassTone.Red)
                }
            }
        }
    }
    if (confirmErase) ConfirmDialog(
        title = "Erase everything on this phone?",
        text = "This cannot be undone. Save a backup first if you might want it back.",
        confirm = "Erase",
        onConfirm = {
            if (store.erase().orSay(context) != null) nav.navigate("setup") { popUpTo(0) { inclusive = true } }
        },
        onDismiss = { confirmErase = false },
    )
}

@Composable
private fun BookLookupSwitch() {
    val store = LocalStore.current
    SwitchRow(
        "Look up book titles online", deviceInfo().bookLookup == true, { v -> store.setDevice { it.copy(bookLookup = v) } },
        sub = "Sends only a book's barcode number to Open Library. Everything else stays offline.",
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
    // Rooms to look in by a tap, most things first. A room that is in both homes is offered once.
    val rooms = remember(data) {
        val used = data.movables().flatMap { listOfNotNull(it.originRoomId, it.destRoomId) }.groupingBy { it }.eachCount()
        data.liveRooms.filter { (used[it.id] ?: 0) > 0 }.groupBy { it.name }.map { (name, same) -> name to same.sumOf { used[it.id] ?: 0 } }
            .sortedByDescending { it.second }.map { it.first }.take(8)
    }
    Screen("Find anything") {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                colors = glassFieldColors(),
                value = query,
                onValueChange = { query = it },
                label = { Text("What are you looking for?") },
                placeholder = { Text("kettle, passport, L042…", maxLines = 1) },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, contentDescription = "Clear") }
                },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                modifier = Modifier.weight(1f).focusRequester(focus),
            )
            // Say it instead of typing it.
            SpeakButton("What are you looking for?", Modifier.padding(start = 8.dp), contentDescription = "Say what you are looking for") { query = it }
        }
        when {
            query.isBlank() -> ScrollColumn {
                if (rooms.isNotEmpty()) {
                    // No keyboard needed for the commonest question: what is in, or going to, this room?
                    Text("Or look in a room", style = MaterialTheme.typography.labelLarge, color = Night.TextDim)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        rooms.forEach { name -> GlassChip(name, icon = Pic.Room, onClick = { query = name }) }
                    }
                }
                EmptyState(Icons.Default.Search, "Find a box or an item", text = "Say or type what is in it, its name, its label or its room.")
            }
            hits.isEmpty() -> EmptyState(Icons.Default.SearchOff, "Nothing matches \"$query\"", text = "Try one word. If another phone packed it, sync first.")
            else -> LazyColumn(
                Modifier.weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(hits, key = { it.movable.id }) { hit -> SearchHit(hit, data) { nav.openMovable(hit.movable) } }
            }
        }
    }
}

/** One thing that was found: its picture, what it is, where it is right now, and what matched. */
@Composable
private fun SearchHit(hit: Hit, data: AppData, onClick: () -> Unit) {
    val m = hit.movable
    val dest = data.room(m.destRoomId)
    GlassCard(Modifier.fillMaxWidth(), onClick = onClick, shape = RoundedCornerShape(22.dp)) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OwnerThumb(m.id, Modifier.size(60.dp), isBox = m.isBox)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                // A box is named by what is in it, which is what was searched for: two lines, so it is read whole.
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(m.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (dest != null) RoomBadge(dest, Modifier.padding(top = 2.dp))
                }
                // Where it is at this moment is the answer most searches are after.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(m.stage.icon(), contentDescription = null, tint = Night.MintLight, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(data.whereIs(m), style = MaterialTheme.typography.bodySmall, color = Night.TextSoft, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                // A box's title already starts with what is in it; the detail is shown when it says more.
                if (hit.detail.isNotBlank() && !m.title.contains(hit.detail.take(40))) {
                    Text(hit.detail.take(120), style = MaterialTheme.typography.bodySmall, color = Night.TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (m.fragile || m.damaged || m.priority == Priority.FIRST_NIGHT) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (m.priority == Priority.FIRST_NIGHT) Flag("First night")
                        if (m.fragile) Flag("Fragile")
                        if (m.damaged) Flag("Damaged", error = true)
                    }
                }
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Night.TextDim)
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
    // Typing a code is the way round the camera, for a handwritten label: it stays out of sight until asked for.
    var typing by rememberSaveable { mutableStateOf(false) }
    val moves = LocalMoves.current
    val state = LocalAppState.current
    /** A label held by more than one move on this phone, or only by another: which move to open it in. */
    var which by remember { mutableStateOf<Pair<String, List<MoveCard>>?>(null) }

    fun handle(text: String) {
        if (WifiSync.Invite.parse(text) != null) {
            // A sync code or an invite: the sync screen uses it straight away, so it is not scanned twice.
            nav.open("sync?code=" + uriEncode(text.trim()))
            return
        }
        val code = labelCodeOf(text)
        if (code.isBlank()) return
        val found = data.findByAnyCode(code)
        if (moves != null && which == null) {
            // An old label (no move tag) can be in two moves on this phone; a new one says its move. Ask when it is not plainly this one.
            val holders = moves.movesHolding(text)
            if (holders.any { !it.matches(store.moveId) } && (holders.size > 1 || found == null)) {
                which = code to holders
                return
            }
        }
        when {
            found != null -> nav.openMovable(found)
            // A shop barcode that nothing was recorded against is not a label to start a box with.
            !text.startsWith(QR_PREFIX) && looksLikeBarcodeNumber(code) -> notRecorded = code
            else -> unknown = code
        }
    }

    Screen("Scan a label") {
        QrScanner(
            onText = { if (unknown == null && notRecorded == null && which == null) handle(it) },
            modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp).scannerFrame(),
            formats = ScanKind.ANY,
        )
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = if (typing) 4.dp else 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Hint("A HouseMove label, a book or product barcode, or a serial number.", Modifier.weight(1f))
            GlassChip("Type the code", icon = Icons.Default.Keyboard, selected = typing, onClick = { typing = !typing })
        }
        if (typing) {
            Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    colors = glassFieldColors(),
                    value = typed,
                    onValueChange = { typed = it },
                    label = { Text("Or type the code on the label") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                PillButton("Open", onClick = { handle(typed); typed = "" }, enabled = typed.isNotBlank())
            }
        }
    }

    notRecorded?.let { code ->
        GlassDialog(
            "Barcode $code is not recorded", onDismiss = { notRecorded = null }, icon = Icons.Default.QrCodeScanner,
            text = "Nothing with this barcode has been scanned into a box yet. To record it, open the box and tap \"Scan books and products into this box\".",
            buttons = { PillButton("OK", onClick = { notRecorded = null }) },
        )
    }

    which?.let { (code, holders) ->
        fun pick(card: MoveCard) {
            which = null
            if (card.matches(store.moveId)) {
                data.findByAnyCode(code)?.let { nav.openMovable(it) }
                return
            }
            // The thing is opened in the other move, once its screens are up.
            val there = moves?.store(card.moveId)?.data?.value?.findByAnyCode(code)
            state?.pendingRoute = there?.let { if (it.isBox) "box/${it.id}" else "item/${it.id}" }
            moves?.open(card.moveId)
        }
        GlassDialog(
            if (holders.size > 1) "Which move?" else "In another move", onDismiss = { which = null }, icon = Pic.Label,
            text = if (holders.size > 1) "Label $code is in ${holders.size} moves on this phone. Open it in:" else "Label $code is not in this move. Open it in:",
            buttons = { DialogCancel({ which = null }) },
        ) {
            holders.forEach { card ->
                PillButton(
                    card.name.ifBlank { "A move" } + if (card.matches(store.moveId)) " (open now)" else "",
                    onClick = { pick(card) }, icon = Icons.Default.Home, primary = false, modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    unknown?.let { code ->
        GlassDialog(
            "Label $code is not in use", onDismiss = { unknown = null }, icon = Pic.Label,
            text = "Start a new box with this label? To put it on a piece of furniture instead, open the item and choose \"Scan a printed label\".",
            buttons = {
                DialogCancel({ unknown = null })
                PillButton("New box", icon = Icons.Default.Add, onClick = {
                    val box = store.createBox(code)
                    unknown = null
                    nav.open("box/${box.id}")
                })
            },
        )
    }
}
