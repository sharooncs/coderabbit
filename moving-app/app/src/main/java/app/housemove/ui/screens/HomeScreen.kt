package app.housemove.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShortText
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.housemove.data.*
import app.housemove.ui.*

private data class NextStep(val text: String, val button: String, val route: String, val icon: ImageVector)

/** The single most useful thing to do now, from where the move has got to. */
private fun nextStep(data: AppData): NextStep {
    val p = data.progress()
    val mv = data.movables()
    val delivered = mv.count { it.stage == Stage.DELIVERED || it.stage == Stage.DONE }
    val loaded = mv.count { it.stage == Stage.LOADED }
    val inventoryOnly = data.move.kind == MoveKind.INVENTORY
    val van = data.vehicle()
    return when {
        data.roomsOf(Home.ORIGIN).isEmpty() || (!inventoryOnly && data.roomsOf(Home.DEST).isEmpty()) ->
            NextStep(
                if (inventoryOnly) "Add the rooms of your home so things can be given a place." else "Add the rooms of both homes so things can be given a place.",
                "Add rooms", "rooms", Icons.Default.MeetingRoom,
            )
        data.liveItems.isEmpty() && data.liveBoxes.isEmpty() ->
            NextStep("Photograph the big things, room by room.", "Start the walk-through", "walk", Icons.Default.PhotoCamera)
        data.uses(Part.TRIAGE) && p.triage.done < p.triage.total ->
            NextStep("Decide what happens to each thing. ${p.triage.total - p.triage.done} left.", "Triage", "triage", Icons.Default.Rule)
        data.uses(Part.PLAN) && p.destinations.done < p.destinations.total ->
            NextStep("Give every item a room in ${data.move.destName.lowercase()}.", "Plan destinations", "plan", Icons.Default.Map)
        data.uses(Part.MOVE_DAY) && loaded > 0 && delivered < mv.size ->
            NextStep("$loaded on the $van. Scan each thing as it comes off.", "Unload", "unload", Icons.Default.Download)
        data.uses(Part.UNPACK) && mv.isNotEmpty() && delivered == mv.size && p.unpacked.done < p.unpacked.total ->
            NextStep("Everything has arrived. First-night boxes first.", "Unpack", "unpack", Icons.Default.Unarchive)
        mv.isNotEmpty() && delivered == mv.size ->
            NextStep("Everything has arrived. Save the inventory.", "Reports and close-out", "reports", Icons.Default.Description)
        data.uses(Part.MOVE_DAY) && (data.move.moveDay ?: Long.MAX_VALUE) <= today() && mv.any { it.stage == Stage.AT_ORIGIN } ->
            NextStep("Move day: scan everything onto the $van.", "Load the $van", "load", Icons.Default.LocalShipping)
        data.uses(Part.BOXES) ->
            NextStep("Pack room by room: label, photo, contents, seal.", "Pack a box", "pack", Icons.Default.Inventory2)
        data.uses(Part.DEVICES) && data.liveItems.any { it.isElectronic() && it.serial.isBlank() } ->
            NextStep("Record the serial numbers of your devices.", "Devices", "electronics", Icons.Default.Tv)
        else ->
            NextStep("Your list is up to date. Save a copy of it.", "Reports and close-out", "reports", Icons.Default.Description)
    }
}

/** One of the three numbers under the top bar: what it is, and where tapping it goes. */
private data class KeyNumber(val icon: ImageVector, val value: String, val label: String, val route: String)

private fun keyNumbers(data: AppData): List<KeyNumber> {
    val move = data.move
    val p = data.progress()
    val out = ArrayList<KeyNumber>()
    val undecided = data.liveItems.count { it.disposition == Disposition.UNDECIDED }
    if (move.kind == MoveKind.INVENTORY) {
        out += KeyNumber(Icons.Default.ListAlt, "${data.liveItems.size}", "things listed", "items")
    } else {
        val d = move.moveDay?.let { it - today() }
        out += when {
            d == null -> KeyNumber(Icons.Default.Event, "?", "no date yet", "settings")
            d > 1 -> KeyNumber(Icons.Default.Event, "$d", "days to go", "settings")
            d == 1L -> KeyNumber(Icons.Default.Event, "1", "day to go", "settings")
            d == 0L -> KeyNumber(Icons.Default.Event, "Today", "is the day", "settings")
            d == -1L -> KeyNumber(Icons.Default.Event, "+1", "day since", "settings")
            else -> KeyNumber(Icons.Default.Event, "+${-d}", "days since", "settings")
        }
    }
    if (data.uses(Part.BOXES)) {
        out += KeyNumber(Icons.Default.Inventory2, "${p.sealed.done}/${p.sealed.total}", "boxes sealed", "boxes")
    }
    // One more, chosen for where the move has got to today.
    val mv = data.movables()
    val moving = mv.any { it.stage != Stage.AT_ORIGIN }
    val overdue = data.liveTasks.count { !it.done && (data.dueDay(it) ?: Long.MAX_VALUE) < today() }
    out += when {
        data.uses(Part.UNPACK) && mv.isNotEmpty() && p.delivered.done == p.delivered.total ->
            KeyNumber(Icons.Default.Unarchive, "${p.unpacked.done}/${p.unpacked.total}", "unpacked", "unpack")
        data.uses(Part.MOVE_DAY) && p.delivered.done > 0 ->
            KeyNumber(Icons.Default.Download, "${p.delivered.done}/${p.delivered.total}", "delivered", "unload")
        data.uses(Part.MOVE_DAY) && moving ->
            KeyNumber(Icons.Default.LocalShipping, "${p.loaded.done}/${p.loaded.total}", "on the ${data.vehicle()}", "load")
        data.uses(Part.TRIAGE) && undecided > 0 -> KeyNumber(Icons.Default.Rule, "$undecided", "to decide", "triage")
        data.uses(Part.TIMELINE) && overdue > 0 -> KeyNumber(Icons.Default.Checklist, "$overdue", "overdue", "tasks")
        data.uses(Part.BOXES) && p.roomsPacked.total > 0 ->
            KeyNumber(Icons.Default.MeetingRoom, "${p.roomsPacked.done}/${p.roomsPacked.total}", "rooms packed", "overview")
        data.uses(Part.TIMELINE) && p.tasks.total > 0 -> KeyNumber(Icons.Default.Checklist, "${p.tasks.done}/${p.tasks.total}", "tasks done", "tasks")
        else -> KeyNumber(Icons.Default.PhotoCamera, "${data.livePhotos.size}", if (data.livePhotos.size == 1) "photo" else "photos", "walk")
    }
    if (move.kind == MoveKind.INVENTORY) {
        val devices = data.liveItems.count { it.isElectronic() }
        if (out.size < 3 && data.uses(Part.DEVICES)) out += KeyNumber(Icons.Default.Tv, "$devices", if (devices == 1) "device" else "devices", "electronics")
    }
    return out.take(3)
}

@Composable
fun HomeScreen() {
    val nav = LocalNav.current
    val store = LocalStore.current
    val data = appData()
    val device = deviceInfo()
    val move = data.move
    val now = currentPhase(data)
    // -1 follows the phase the move is in; tapping the strip looks at another one.
    var picked by rememberSaveable { mutableIntStateOf(-1) }
    val shown = if (picked < 0) now else picked

    if (device.helper && move.setupDone) {
        HelperHome()
        return
    }

    val packing = data.uses(Part.BOXES)
    val next = nextStep(data)
    // The one big button: packing a box whenever boxes are in use, otherwise the next step.
    val action = if (packing) DockAction("Pack a box", Icons.Default.Inventory2) { nav.open("pack") }
    else DockAction(next.button, next.icon) { nav.open(next.route) }
    // On a short screen the tall dock would leave too little room, so the button moves into the page.
    val roomy = LocalConfiguration.current.screenHeightDp >= 700

    Frame(tab = "home", topBar = { HomeTopBar(device) }, dockAction = if (roomy) action else null) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            var warning by remember { mutableStateOf(store.loadWarning) }
            warning?.let { w ->
                GlassCard(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp), tone = GlassTone.Red) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(w, color = MaterialTheme.colorScheme.onErrorContainer)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { nav.open("sync") }) { Text("Sync and backup") }
                            TextButton(onClick = { store.clearLoadWarning(); warning = null }) { Text("Dismiss") }
                        }
                    }
                }
            }

            // Three numbers that say where the move stands.
            Row(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                keyNumbers(data).forEach { k -> KeyNumberView(k, Modifier.weight(1f, fill = false)) { nav.open(k.route) } }
            }

            val oldRooms = data.roomsOf(Home.ORIGIN)
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    move.name.ifBlank { "HouseMove" },
                    style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() },
                )
                Text(
                    move.originName + " · " + oldRooms.size + if (oldRooms.size == 1) " room" else " rooms",
                    style = MaterialTheme.typography.bodyMedium, color = Night.TextDim, textAlign = TextAlign.Center, maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // The house: the old home, with the next room to pack called out.
            val rooms = if (packing) data.roomPacking() else emptyList()
            val nextRoom = rooms.nextToPack(device.currentRoomId)
            val atOldHome = if (packing) data.liveBoxes.count { it.stage == Stage.AT_ORIGIN } else 0
            BoxWithConstraints(Modifier.widthIn(max = 460.dp).fillMaxWidth().align(Alignment.CenterHorizontally)) {
                val width = maxWidth
                IsoHouse(
                    Modifier.clickable(onClickLabel = if (packing) "Open the rooms overview" else "Open rooms", role = Role.Button) {
                        nav.open(if (packing) "overview" else "rooms")
                    },
                    boxes = when {
                        atOldHome == 0 -> 0
                        atOldHome <= 3 -> 1
                        atOldHome <= 9 -> 2
                        else -> 3
                    },
                    markers = listOfNotNull(
                        if (rooms.any { it.room.packed }) HouseMarker(HouseSpot.ROOF) else null,
                        if (nextRoom != null) HouseMarker(HouseSpot.EXTENSION, amber = true) else null,
                    ),
                )
                val tagAt = Modifier.align(Alignment.TopEnd).padding(end = 12.dp).offset(y = width * (206f / 390f)).widthIn(max = width * 0.6f)
                if (nextRoom != null) {
                    GlassTag("${nextRoom.room.name} is next", nextRoom.leftText(), Icons.Default.Inventory2, tagAt, onClick = { nav.open("overview") })
                } else if (packing && rooms.isNotEmpty()) {
                    GlassTag("Every room", "is packed", Icons.Default.Check, tagAt, kind = OrbKind.Primary, onClick = { nav.open("overview") })
                }
            }

            SuggestionsSlot(Modifier.fillMaxWidth().padding(horizontal = 16.dp))

            // Below the fold: the next step, the journey, and what needs attention.
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!roomy) BigAction(action.icon, action.label, action.onClick)

                Section {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Next step", style = MaterialTheme.typography.labelLarge, color = Night.MintPale, modifier = Modifier.weight(1f))
                        if (move.kind != MoveKind.INVENTORY) {
                            // The date of the move, and the way to set it.
                            GlassChip(
                                move.moveDay?.let { fmtDay(it) } ?: "Set the date",
                                icon = Icons.Default.Event, onClick = { nav.open("settings") },
                            )
                        }
                    }
                    Text(next.text, style = MaterialTheme.typography.bodyLarge)
                    BigAction(next.icon, next.button, onClick = { nav.open(next.route) }, primary = !(packing && roomy && next.route == "pack"))
                }

                // The things done over and over.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickAction(Icons.Default.PhotoCamera, "Walk-through", Modifier.weight(1f)) { nav.open("walk") }
                    QuickAction(Icons.Default.MeetingRoom, "Rooms", Modifier.weight(1f)) { nav.open(if (packing) "overview" else "rooms") }
                }

                // Where the move is, and what belongs to each phase.
                GlassCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(26.dp)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        JourneyStrip(data, selected = shown, current = now, onSelect = { picked = it })
                        val phase = JOURNEY[shown]
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(phase.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            if (shown == now) GlassChip("You are here", tone = GlassTone.Mint)
                            else Text(phase.timing, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        PhaseTiles(phase, data, device)
                    }
                }

                // Needs attention: the worst three, the rest on request.
                val issues = data.issues()
                if (issues.isNotEmpty()) {
                    var all by rememberSaveable { mutableStateOf(false) }
                    Section("Needs attention") {
                        (if (all) issues else issues.take(3)).forEach { issue ->
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { nav.open(issue.route) }.heightIn(min = 48.dp).padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    if (issue.severe) Icons.Default.ErrorOutline else Icons.Default.Info,
                                    contentDescription = if (issue.severe) "Urgent" else null,
                                    tint = if (issue.severe) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(issue.text, modifier = Modifier.weight(1f))
                                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (issues.size > 3) {
                            TextButton(onClick = { all = !all }) { Text(if (all) "Show fewer" else "Show ${issues.size - 3} more") }
                        }
                    }
                }
            }
        }
    }
}

/**
 * SLOT for the "suggestions waiting" strip (design/look-d: "2 suggestions waiting · Review").
 *
 * It sits between the house and the dock and is deliberately empty for now. Fill it with
 * one glass pill ([GlassPill] with `sheen = true`, about 56dp tall) and keep the modifier,
 * which already carries the side padding. Draw nothing when there are no suggestions.
 */
@Composable
internal fun SuggestionsSlot(modifier: Modifier = Modifier) {
    Box(modifier)
}

/** Home's own top bar: menu and search on the left, the app's mark, scan and this phone on the right. */
@Composable
private fun HomeTopBar(device: DeviceInfo) {
    val nav = LocalNav.current
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TopAction(Icons.AutoMirrored.Filled.ShortText, "Menu") { nav.goTab("more") }
            TopAction(Icons.Default.Search, "Find anything") { nav.open("search") }
        }
        AppMark()
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TopAction(Icons.Default.QrCodeScanner, "Scan a label") { nav.open("scan") }
            val initial = device.name.trim().firstOrNull()?.uppercaseChar()
            GlassOrb(kind = OrbKind.Selected, onClick = { nav.open("settings") }, contentDescription = "Settings") {
                if (initial != null) Text(initial.toString(), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                else Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(22.dp))
            }
        }
    }
}

/** The app's mark: a small house drawn in glowing mint line. */
@Composable
private fun AppMark() {
    Canvas(Modifier.size(32.dp).semantics { contentDescription = "HouseMove" }) {
        val u = size.width / 24f
        val house = Path().apply {
            moveTo(3.5f * u, 11f * u); lineTo(12f * u, 4f * u); lineTo(20.5f * u, 11f * u); lineTo(20.5f * u, 20f * u); lineTo(3.5f * u, 20f * u); close()
            moveTo(9.5f * u, 13.5f * u); lineTo(14.5f * u, 13.5f * u); lineTo(14.5f * u, 18.5f * u); lineTo(9.5f * u, 18.5f * u); close()
        }
        drawCircle(
            androidx.compose.ui.graphics.Brush.radialGradient(listOf(Night.Mint.copy(alpha = 0.38f), Night.Mint.copy(alpha = 0f)), center = Offset(size.width / 2, size.height / 2), radius = size.width * 0.8f),
            size.width * 0.8f,
        )
        drawPath(house, Night.MintLight, style = Stroke(2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
private fun KeyNumberView(k: KeyNumber, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(24.dp)).clickable(role = Role.Button, onClick = onClick).heightIn(min = 48.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassOrb(k.icon, null, size = 36.dp)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(k.value, fontSize = 18.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(k.label, style = MaterialTheme.typography.bodySmall, color = Night.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A glass pill with a picture and a word or two: one of the things done over and over. */
@Composable
private fun QuickAction(icon: ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    GlassPill(modifier.heightIn(min = 52.dp), onClick = onClick, horizontalArrangement = Arrangement.Center) {
        Icon(icon, contentDescription = null, tint = Night.Mint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Home for a helper's phone: only the jobs a helper does, as large tiles. */
@Composable
private fun HelperHome() {
    val nav = LocalNav.current
    val store = LocalStore.current
    val data = appData()
    val p = data.progress()
    Screen(title = data.move.name.ifBlank { "HouseMove" }, back = false, tab = "home", actions = { TopActions() }, subtitle = "Helper") {
        ScrollColumn {
            data class Job(val icon: ImageVector, val title: String, val route: String, val status: String?, val part: Part?)
            val jobs = listOf(
                Job(Icons.Default.Inventory2, "Pack a box", "pack", "${p.sealed.done} of ${p.sealed.total} sealed", Part.BOXES),
                Job(Icons.Default.LocalShipping, "Load the ${data.vehicle()}", "load", "${p.loaded.done} of ${p.loaded.total} on", Part.MOVE_DAY),
                Job(Icons.Default.Download, "Unload", "unload", "${p.delivered.done} of ${p.delivered.total} in", Part.MOVE_DAY),
                Job(Icons.Default.Unarchive, "Unpack", "unpack", "${p.unpacked.done} of ${p.unpacked.total} done", Part.UNPACK),
                Job(Icons.Default.Search, "Find anything", "search", null, null),
                Job(Icons.Default.QrCodeScanner, "Scan a label", "scan", null, null),
            ).filter { it.part == null || data.uses(it.part) }
            TileGrid(jobs, columns = 2) { job, m ->
                Tile(job.icon, job.title, onClick = { nav.open(job.route) }, modifier = m.heightIn(min = 108.dp), status = job.status)
            }
            BigAction(Icons.Default.Sync, "Sync with the other phones", onClick = { nav.open("sync") }, primary = false)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Hint("Helper mode: only the essentials are shown.", Modifier.weight(1f))
                TextButton(onClick = { store.setDevice { it.copy(helper = false) } }) { Text("Show everything") }
            }
        }
    }
}

fun agoText(millis: Long, now: Long = System.currentTimeMillis()): String {
    val mins = ((now - millis) / 60_000).coerceAtLeast(0)
    return when {
        mins < 1 -> "just now"
        mins < 60 -> "$mins min ago"
        mins < 60 * 24 -> "${mins / 60} h ago"
        else -> "${mins / (60 * 24)} day${if (mins / (60 * 24) == 1L) "" else "s"} ago"
    }
}
