package app.housemove.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.housemove.data.*
import app.housemove.ui.*

fun MoveKind.icon(): ImageVector = when (this) {
    MoveKind.SELF -> Icons.Default.LocalShipping
    MoveKind.MOVERS -> Icons.Default.Groups
    MoveKind.STORAGE -> Icons.Default.Warehouse
    MoveKind.INVENTORY -> Icons.Default.ListAlt
}

fun Part.icon(): ImageVector = when (this) {
    Part.TRIAGE -> Icons.Default.Rule
    Part.DEVICES -> Icons.Default.Tv
    Part.PLAN -> Icons.Default.Map
    Part.TIMELINE -> Icons.Default.Checklist
    Part.BUDGET -> Icons.Default.ShoppingCart
    Part.BOXES -> Icons.Default.Inventory2
    Part.LABELS -> Icons.Default.Print
    Part.MOVE_DAY -> Icons.Default.LocalShipping
    Part.RECORDS -> Icons.Default.PhotoCamera
    Part.UNPACK -> Icons.Default.Unarchive
}

/** Choose what the app is used for. Changing it resets which parts show and the place names. */
@Composable
fun KindPicker(modifier: Modifier = Modifier, large: Boolean = false) {
    val store = LocalStore.current
    val context = LocalContext.current
    val move = appData().move
    IconChoice(
        MoveKind.entries.map { Choice(it, it.label, it.icon()) },
        selected = move.kind,
        onPick = { kind ->
            store.changeMove { m ->
                val (oldDefault, newDefault) = placeNamesFor(m.kind)
                val (origin, dest) = placeNamesFor(kind)
                m.copy(
                    kind = kind,
                    off = partsOffFor(kind),
                    originName = if (m.originName == oldDefault || m.originName.isBlank()) origin else m.originName,
                    destName = if (m.destName == newDefault || m.destName.isBlank()) dest else m.destName,
                )
            }.orSay(context)
        },
        modifier = modifier,
        columns = 2,
        large = large,
    )
}

/**
 * First-run wizard: what it is for, the move, the rooms. Every page can be passed with
 * "Next" alone: names have defaults, the date can wait, rooms are tapped. A second phone
 * for a move that already exists joins it here instead: one tap opens the scanner for its invite.
 */
@Composable
fun SetupScreen() {
    val store = LocalStore.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val data = appData()
    var step by rememberSaveable { mutableIntStateOf(0) }
    val inventoryOnly = data.move.kind == MoveKind.INVENTORY
    val titles = listOfNotNull(
        "What is it for?",
        if (inventoryOnly) "Your home" else "Your move",
        if (inventoryOnly) "Rooms" else "Rooms in ${data.move.originName.lowercase()}",
        if (inventoryOnly) null else "Rooms in ${data.move.destName.lowercase()}",
    )
    val last = titles.size - 1
    val at = step.coerceAtMost(last)

    val joiner = rememberJoiner()
    var scanning by remember { mutableStateOf(false) }
    val report = joiner.report

    // Leaving is asked for both by the button and by the data changing; only the first counts.
    fun goHome() {
        if (nav.currentDestination?.route == "setup") nav.navigate("home") { popUpTo("setup") { inclusive = true } }
    }

    // A phone that took in a move that is already set up skips the wizard, once it has been told who it now is.
    LaunchedEffect(data.move.setupDone, joiner.busy, report) {
        // Read as they are now, not as they were when this was composed: the end of a join sets both at once.
        if (store.data.value.move.setupDone && !joiner.busy && joiner.report == null) goHome()
    }

    // This phone has just met another one: say what came of it before anything else.
    if (report != null && report.ok) {
        Screen(title = if (report.joinedAs != null) "You are in" else "Synced", back = false) {
            ScrollColumn(Modifier.weight(1f)) {
                val who = report.joinedAs
                if (who == null) SyncReportCard(report) else {
                    // Who this phone now is, large, so there is no doubt about it.
                    Column(
                        Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        PersonOrb(who, size = 104.dp, me = true)
                        Text(report.title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                        Text(report.text, style = MaterialTheme.typography.bodyLarge, color = Night.TextSoft, textAlign = TextAlign.Center)
                    }
                    Section {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            GlassOrb(Icons.Default.Home, null, size = 40.dp)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(data.move.name.ifBlank { "The move" }, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    listOfNotNull(
                                        "${data.livePeople.size} people", data.move.moveDay?.let { "move day " + fmtDay(it) },
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall, color = Night.TextDim,
                                )
                            }
                        }
                    }
                }
                if (!data.move.setupDone) Hint("That move is not set up yet. You can finish setting it up here.")
            }
            BigAction(
                Icons.AutoMirrored.Filled.ArrowForward, "Start",
                onClick = { if (data.move.setupDone) goHome() else joiner.report = null },
                modifier = Modifier.padding(16.dp),
            )
        }
        return
    }

    Screen(title = titles[at], back = false) {
        Meter((at + 1) / titles.size.toFloat(), Modifier.fillMaxWidth().padding(horizontal = 16.dp), height = 4.dp)
        if (joiner.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
        ScrollColumn(Modifier.weight(1f)) {
            when (at) {
                0 -> {
                    if (report != null) SyncReportCard(report)
                    // A tall phone opens on the lit house, as Home does, instead of on empty ground; a short one goes straight to the choice.
                    else if (LocalConfiguration.current.let { it.screenHeightDp / it.fontScale >= 780f }) {
                        IsoHouse(Modifier.widthIn(max = 300.dp).align(Alignment.CenterHorizontally), boxes = 1)
                    }
                    KindPicker(large = true)
                    // Joining a move that exists is one tap from here: straight to the scanner.
                    ActionPill(
                        Icons.Default.QrCodeScanner, "Second phone for the same move?", "Scan your invite: on the first phone, People, then you",
                        Modifier.enabledLook(!joiner.busy),
                    ) { if (!joiner.busy) scanning = true }
                }
                1 -> MoveDetails(inventoryOnly)
                2 -> RoomSetup(Home.ORIGIN)
                else -> RoomSetup(Home.DEST)
            }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (at > 0) BigAction(Icons.AutoMirrored.Filled.ArrowBack, "Back", onClick = { step = at - 1 }, modifier = Modifier.weight(1f), primary = false)
            BigAction(
                if (at < last) Icons.AutoMirrored.Filled.ArrowForward else Icons.Default.Check, if (at < last) "Next" else "Finish",
                onClick = {
                    if (at < last) step = at + 1 else if (finishSetup(store).orSay(context) != null) goHome()
                },
                modifier = Modifier.weight(1f),
            )
        }
    }

    if (scanning) {
        ScanDialog(
            title = "Scan the invite on the other phone",
            raw = true,
            // An invite is far too long to type unless there is no other way: the field waits behind a chip.
            typingFolded = true,
            onClose = { scanning = false },
            onCode = { text ->
                if (joiner.join(text)) scanning = false
                else toast(context, "That is not an invite. On the other phone open People and show your invite.")
            },
        )
    }
}

/** The second page: the date by a tap, your name by voice, and the names of things with their defaults folded away. */
@Composable
private fun MoveDetails(inventoryOnly: Boolean) {
    val store = LocalStore.current
    val context = LocalContext.current
    val data = appData()
    val device = deviceInfo()
    val move = data.move
    fun change(f: (MoveInfo) -> MoveInfo) {
        store.changeMove(f).orSay(context)
    }
    if (!inventoryOnly) {
        DateButton(move.moveDay, { d -> change { it.copy(moveDay = d) } }, "Move day", Modifier.fillMaxWidth().heightIn(min = 56.dp))
    }
    var naming by rememberSaveable { mutableStateOf(false) }
    var spoken by rememberSaveable { mutableIntStateOf(0) }
    GlassCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(26.dp), sheen = true) {
        Row(Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            PersonOrb(myPerson(), size = 52.dp, me = true)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(device.name.ifBlank { "Your name" }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("So the others know who did what", style = MaterialTheme.typography.bodySmall, color = Night.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            SpeakButton("What is your name?", contentDescription = "Say your name") { said ->
                store.setDevice { it.copy(name = said.replaceFirstChar { c -> c.uppercase() }) }
                spoken++
            }
            Spacer(Modifier.width(4.dp))
            GlassOrb(Icons.Default.Edit, "Type your name") { naming = !naming }
        }
    }
    if (naming) TextInput(device.name, { v -> store.setDevice { it.copy(name = v) } }, "Your name", key = "device-$spoken")
    Fold(
        "Names", Icons.Default.Edit,
        summary = listOfNotNull(move.name.ifBlank { null }, if (inventoryOnly) move.originName else move.originName + " → " + move.destName).joinToString(" · "),
    ) {
        TextInput(move.name, { v -> change { it.copy(name = v) } }, "Name of the move (optional)", key = "name", placeholder = "e.g. Maple Road move", mic = true)
        TextInput(move.originName, { v -> change { it.copy(originName = v) } }, if (inventoryOnly) "What to call your home" else "Where you are moving from", key = "origin")
        if (!inventoryOnly) {
            TextInput(move.destName, { v -> change { it.copy(destName = v) } }, "Where you are moving to", key = "dest")
        }
    }
}

/** Link old rooms to new rooms with the same name, add the checklist, mark setup done. */
private fun finishSetup(store: Store): Outcome<MoveInfo> {
    val data = store.data.value
    val dest = data.roomsOf(Home.DEST)
    for (r in data.roomsOf(Home.ORIGIN)) {
        if (r.mapsTo != null) continue
        val match = dest.firstOrNull { it.name.equals(r.name, ignoreCase = true) } ?: continue
        store.put(r.copy(mapsTo = match.id))
    }
    if (data.liveTasks.isEmpty() && data.uses(Part.TIMELINE)) seedTasks(data.move.kind).forEach { store.put(it) }
    return store.changeMove { it.copy(setupDone = true) }
}

/**
 * Add rooms by tapping the common ones or saying a name; typing one is behind "Other".
 * Used by the wizard and by the Rooms screen. In the new home, the rooms of the old one
 * can be added all at once, each linked to the room of the same name it takes things from.
 */
@Composable
internal fun RoomAdder(home: Home, modifier: Modifier = Modifier) {
    val store = LocalStore.current
    val data = appData()
    val rooms = data.roomsOf(home)
    var typing by rememberSaveable(home) { mutableStateOf(false) }
    var custom by remember { mutableStateOf("") }
    // The old home's rooms that the new home does not have yet, once each.
    val notYet = if (home == Home.DEST) {
        data.roomsOf(Home.ORIGIN).map { it.name.trim() }.filter { n -> n.isNotEmpty() && rooms.none { it.name.equals(n, ignoreCase = true) } }
            .distinctBy { it.lowercase() }
    } else emptyList()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (notYet.size > 1) {
                GlassChip(
                    "Same as ${data.move.originName.lowercase()} (${notYet.size})", icon = Icons.Default.DoneAll, tone = GlassTone.Mint,
                    onClick = {
                        for (name in notYet) {
                            val made = store.createRoom(Home.DEST, name)
                            store.data.value.roomsOf(Home.ORIGIN).filter { it.mapsTo == null && it.name.trim().equals(name, ignoreCase = true) }
                                .forEach { store.put(it.copy(mapsTo = made.id)) }
                        }
                    },
                )
            }
            COMMON_ROOMS.filter { name -> rooms.none { it.name.equals(name, ignoreCase = true) } }.forEach { name ->
                GlassChip(name, icon = Icons.Default.Add, onClick = { store.createRoom(home, name) })
            }
            GlassChip("Other", icon = Icons.Default.Edit, selected = typing, onClick = { typing = !typing })
            SpeakButton("What is the room called?", contentDescription = "Say a room name") { said ->
                store.createRoom(home, said.replaceFirstChar { it.uppercase() })
            }
        }
        if (typing) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    colors = glassFieldColors(),
                    value = custom,
                    onValueChange = { custom = it },
                    label = { Text("Room name") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                GlassOrb(
                    Icons.Default.Add, "Add room", Modifier.alpha(if (custom.isNotBlank()) 1f else 0.45f), kind = OrbKind.Primary,
                    onClick = { if (custom.isNotBlank()) { store.createRoom(home, custom); custom = "" } },
                )
            }
        }
    }
}

@Composable
private fun RoomSetup(home: Home) {
    val store = LocalStore.current
    val context = LocalContext.current
    val data = appData()
    val me = myPerson()
    val rooms = data.roomsOf(home)
    Text(
        if (home == Home.ORIGIN) "Tap every room and storage space with things in it."
        else "Tap the rooms things will go into. Each gets a colour for its boxes and door sign.",
    )
    RoomAdder(home)
    if (rooms.isNotEmpty()) {
        Text("${rooms.size} added", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        rooms.forEach { r ->
            GlassCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (home == Home.DEST) ColorDot(r.color) else Icon(Pic.Room, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(r.name, modifier = Modifier.weight(1f))
                    if (home == Home.DEST) Hint(roomColor(r.color).name)
                    // By the Store's rule, like every delete: hidden from whoever may not take it off.
                    if (data.canDelete(me, r)) GlassOrb(Icons.Default.Close, "Remove ${r.name}", Modifier.padding(4.dp), size = 36.dp, onClick = { store.tryDelete(r).okOrSay(context) })
                    else Spacer(Modifier.width(12.dp))
                }
            }
        }
    }
}
