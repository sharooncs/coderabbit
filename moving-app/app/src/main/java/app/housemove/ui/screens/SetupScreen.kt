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

/** The name a new move starts with, so it is never nameless in the list of moves. Said or typed over in one tap. */
private fun defaultMoveName(kind: MoveKind): String = when (kind) {
    MoveKind.STORAGE -> "Our storage"
    MoveKind.INVENTORY -> "Our home"
    else -> "Our move"
}

/** Kinds of use one person runs alone: no people screens until a second person is added. */
internal fun MoveKind.onePerson(): Boolean = this == MoveKind.INVENTORY || this == MoveKind.STORAGE

/**
 * Start a new move: what it is for (or "Join theirs instead"), the move (its name filled in,
 * the date and your name optional), the rooms of the old home and of the new one, each
 * skippable, the new home's in one tap from the old one's, and left out for a list of things.
 * Finishing saves the move and goes on to Who's helping? (not for kinds one person runs alone).
 * Every page can be passed with one tap and nothing typed.
 */
@Composable
fun SetupScreen() {
    val store = LocalStore.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val moves = LocalMoves.current
    val state = appState()
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
    val invites = allowed(Action.MANAGE_PEOPLE) && !data.move.kind.onePerson()
    val cards = moves?.cards?.collectAsState()?.value.orEmpty()
    val others = cards.any { !it.matches(store.moveId) }

    // Leaving is asked for both by the buttons and by the data changing; only the first counts.
    fun leave(route: String) {
        if (nav.currentDestination?.route == "setup") nav.navigate(route) { popUpTo("setup") { inclusive = true } }
    }

    // A move that is set up some other way meanwhile (its data came from another phone) goes Home.
    LaunchedEffect(data.move.setupDone) { if (store.data.value.move.setupDone) leave("home") }

    /** Give up this new move, which nobody else has seen: it is deleted, and the phone goes back to where it was. */
    fun giveUp(then: String?) {
        val m = moves ?: return
        state.pendingRoute = then
        val back = state.returnTo?.takeIf { id -> !store.identity.value.matches(id) }
        state.returnTo = null
        if (m.delete(store.moveId).ok && back != null) m.open(back)
    }

    fun joinInstead() {
        // On a phone with moves, the new one (nothing in it yet) makes way; the join makes a card of its own.
        if (moves != null && moves.whyNotDelete(store.moveId) == null) giveUp("join") else nav.open("join")
    }

    Screen(
        title = titles[at], back = false,
        actions = { if (moves != null && others) TopAction(Icons.Default.Close, "Give up this new move") { giveUp(null) } },
    ) {
        Meter((at + 1) / titles.size.toFloat(), Modifier.fillMaxWidth().padding(horizontal = 16.dp), height = 4.dp)
        ScrollColumn(Modifier.weight(1f)) {
            when (at) {
                0 -> {
                    // A tall phone opens on the lit house, as Home does, instead of on empty ground; a short one goes straight to the choice.
                    if (LocalConfiguration.current.let { it.screenHeightDp / it.fontScale >= 780f }) {
                        IsoHouse(Modifier.widthIn(max = 300.dp).align(Alignment.CenterHorizontally), boxes = 1)
                    }
                    KindPicker(large = true)
                    // Joining a move someone has started is one tap from here: straight to the scanner.
                    ActionPill(Icons.Default.QrCodeScanner, "Join theirs instead", "Has someone already started this move? Scan their invite.") { joinInstead() }
                }
                1 -> MoveDetails(inventoryOnly)
                2 -> RoomSetup(Home.ORIGIN)
                else -> RoomSetup(Home.DEST)
            }
        }
        // The rooms can wait: with none tapped, the button says Skip.
        val skipping = at >= 2 && data.roomsOf(if (at == 2) Home.ORIGIN else Home.DEST).isEmpty()
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (at > 0) BigAction(Icons.AutoMirrored.Filled.ArrowBack, "Back", onClick = { step = at - 1 }, modifier = Modifier.weight(1f), primary = false)
            BigAction(
                when {
                    skipping -> Icons.Default.SkipNext
                    at < last -> Icons.AutoMirrored.Filled.ArrowForward
                    else -> Icons.Default.Check
                },
                when {
                    skipping -> "Skip"
                    at < last -> "Next"
                    else -> "Finish"
                },
                onClick = {
                    if (at < last) step = at + 1
                    // Saved; then who is helping, for a move with people in it.
                    else if (finishSetup(store).orSay(context) != null) leave(if (invites) "helping" else "home")
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** The second page: the move's name (filled in), the date by a tap, your name by voice; the names of the two places folded away. */
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
    LaunchedEffect(Unit) {
        if (store.data.value.move.name.isBlank()) change { it.copy(name = defaultMoveName(it.kind)) }
    }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var said by rememberSaveable { mutableIntStateOf(0) }
    SayOrType(
        icon = { GlassOrb(Icons.Default.Home, null, size = 52.dp) },
        title = move.name.ifBlank { defaultMoveName(move.kind) }, sub = if (inventoryOnly) "What to call your home" else "Name of the move",
        prompt = "What is the move called?", sayDescription = "Say the name of the move", typeDescription = "Type the name of the move",
        onSaid = { name -> change { it.copy(name = name) }; said++ }, onType = { renaming = !renaming },
    )
    if (renaming) TextInput(move.name, { v -> change { it.copy(name = v) } }, "Name of the move", key = "move-name-$said")
    if (!inventoryOnly) {
        DateButton(move.moveDay, { d -> change { it.copy(moveDay = d) } }, "Move day", Modifier.fillMaxWidth().heightIn(min = 56.dp))
    }
    var naming by rememberSaveable { mutableStateOf(false) }
    var spoken by rememberSaveable { mutableIntStateOf(0) }
    SayOrType(
        icon = { PersonOrb(myPerson(), size = 52.dp, me = true) },
        title = device.name.ifBlank { "Your name" }, sub = "So the others know who did what",
        prompt = "What is your name?", sayDescription = "Say your name", typeDescription = "Type your name",
        onSaid = { name -> store.setDevice { it.copy(name = name) }; spoken++ }, onType = { naming = !naming },
    )
    if (naming) TextInput(device.name, { v -> store.setDevice { it.copy(name = v) } }, "Your name", key = "device-$spoken")
    Fold(
        "Names of the places", Icons.Default.Edit,
        summary = if (inventoryOnly) move.originName else move.originName + " → " + move.destName,
    ) {
        TextInput(move.originName, { v -> change { it.copy(originName = v) } }, if (inventoryOnly) "What to call your home" else "Where you are moving from", key = "origin")
        if (!inventoryOnly) {
            TextInput(move.destName, { v -> change { it.copy(destName = v) } }, "Where you are moving to", key = "dest")
        }
    }
}

/** A name to give: shown large, said with the microphone, or typed behind the pencil. */
@Composable
private fun SayOrType(
    icon: @Composable () -> Unit,
    title: String,
    sub: String,
    prompt: String,
    sayDescription: String,
    typeDescription: String,
    onSaid: (String) -> Unit,
    onType: () -> Unit,
) {
    GlassCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(26.dp), sheen = true) {
        Row(Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            icon()
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(sub, style = MaterialTheme.typography.bodySmall, color = Night.TextDim)
            }
            SpeakButton(prompt, contentDescription = sayDescription) { said -> onSaid(said.replaceFirstChar { c -> c.uppercase() }) }
            Spacer(Modifier.width(4.dp))
            GlassOrb(Icons.Default.Edit, typeDescription) { onType() }
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
            if (notYet.isNotEmpty()) {
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
