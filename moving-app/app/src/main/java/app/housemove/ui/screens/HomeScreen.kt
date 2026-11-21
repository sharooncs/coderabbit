package app.housemove.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.LocalShipping
import androidx.compose.material.icons.outlined.MeetingRoom
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material.icons.outlined.Unarchive
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
import androidx.compose.ui.platform.LocalDensity
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
import app.housemove.data.Role as MoveRole
import app.housemove.ui.*

private data class NextStep(val text: String, val button: String, val route: String, val icon: ImageVector)

/**
 * The single most useful thing to do now, from where the move has got to.
 * [suggests] is true for someone who may suggest decisions but not make them: the words
 * then say "suggest", as the screens they lead to do.
 */
private fun nextStep(data: AppData, suggests: Boolean = false): NextStep {
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
            NextStep((if (suggests) "Suggest" else "Decide") + " what happens to each thing. ${p.triage.total - p.triage.done} left.", "Triage", "triage", Icons.Default.Rule)
        data.uses(Part.PLAN) && p.destinations.done < p.destinations.total ->
            NextStep((if (suggests) "Suggest a room for every item in " else "Give every item a room in ") + data.move.destName.lowercase() + ".", "Plan destinations", "plan", Icons.Default.Map)
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

private fun keyNumbers(data: AppData, device: DeviceInfo): List<KeyNumber> {
    val move = data.move
    val p = data.progress()
    val out = ArrayList<KeyNumber>()
    // Suggestions come before the third number: an Organiser's to approve, a Decider's own that still wait.
    val toApprove = if (data.can(device, Action.APPROVE)) data.waitingCount() else 0
    val ownWaiting = if (toApprove == 0 && !data.can(device, Action.APPROVE)) data.proposalCounts(device.personId)[ProposalState.WAITING] ?: 0 else 0
    val undecided = data.liveItems.count { it.disposition == Disposition.UNDECIDED }
    if (move.kind == MoveKind.INVENTORY) {
        out += KeyNumber(Icons.Default.ListAlt, "${data.liveItems.size}", "things listed", "items")
    } else {
        val d = move.moveDay?.let { it - today() }
        // The date is set in Settings; someone who may not set it is taken to what counts down to it instead.
        val to = if (data.can(device, Action.MOVE_SETTINGS) || !data.uses(Part.TIMELINE)) "settings" else "tasks"
        out += when {
            d == null -> KeyNumber(Icons.Outlined.Event, "?", "no date yet", to)
            d > 1 -> KeyNumber(Icons.Outlined.Event, "$d", "days to go", to)
            d == 1L -> KeyNumber(Icons.Outlined.Event, "1", "day to go", to)
            d == 0L -> KeyNumber(Icons.Outlined.Event, "Today", "is the day", to)
            d == -1L -> KeyNumber(Icons.Outlined.Event, "+1", "day since", to)
            else -> KeyNumber(Icons.Outlined.Event, "+${-d}", "days since", to)
        }
    }
    if (data.uses(Part.BOXES)) {
        out += KeyNumber(Icons.Outlined.Inventory2, "${p.sealed.done}/${p.sealed.total}", "boxes sealed", "boxes")
    }
    // One more, chosen for where the move has got to today.
    val mv = data.movables()
    val moving = mv.any { it.stage != Stage.AT_ORIGIN }
    val overdue = data.liveTasks.count { !it.done && (data.dueDay(it) ?: Long.MAX_VALUE) < today() }
    out += when {
        toApprove > 0 -> KeyNumber(Icons.Outlined.CheckCircle, "$toApprove", if (toApprove == 1) "approval" else "approvals", "approvals")
        ownWaiting > 0 -> KeyNumber(Icons.Outlined.Lightbulb, "$ownWaiting", "waiting", "approvals")
        data.uses(Part.UNPACK) && mv.isNotEmpty() && p.delivered.done == p.delivered.total ->
            KeyNumber(Icons.Outlined.Unarchive, "${p.unpacked.done}/${p.unpacked.total}", "unpacked", "unpack")
        data.uses(Part.MOVE_DAY) && p.delivered.done > 0 ->
            KeyNumber(Icons.Default.Download, "${p.delivered.done}/${p.delivered.total}", "delivered", "unload")
        data.uses(Part.MOVE_DAY) && moving ->
            KeyNumber(Icons.Outlined.LocalShipping, "${p.loaded.done}/${p.loaded.total}", "on the ${data.vehicle()}", "load")
        data.uses(Part.TRIAGE) && undecided > 0 -> KeyNumber(Icons.Default.Rule, "$undecided", "to decide", "triage")
        data.uses(Part.TIMELINE) && overdue > 0 -> KeyNumber(Icons.Outlined.Checklist, "$overdue", "overdue", "tasks")
        data.uses(Part.BOXES) && p.roomsPacked.total > 0 ->
            KeyNumber(Icons.Outlined.MeetingRoom, "${p.roomsPacked.done}/${p.roomsPacked.total}", "rooms packed", "overview")
        data.uses(Part.TIMELINE) && p.tasks.total > 0 -> KeyNumber(Icons.Outlined.Checklist, "${p.tasks.done}/${p.tasks.total}", "tasks done", "tasks")
        else -> KeyNumber(Icons.Outlined.PhotoCamera, "${data.livePhotos.size}", if (data.livePhotos.size == 1) "photo" else "photos", "walk")
    }
    if (move.kind == MoveKind.INVENTORY) {
        val devices = data.liveItems.count { it.isElectronic() }
        if (out.size < 3 && data.uses(Part.DEVICES)) out += KeyNumber(Icons.Outlined.Tv, "$devices", if (devices == 1) "device" else "devices", "electronics")
    }
    return out.take(3)
}

@Composable
fun HomeScreen() {
    val nav = LocalNav.current
    val data = appData()
    val device = deviceInfo()

    // A Helper's phone shows only a Helper's jobs. (The switch follows the role; alone it is the old "helper mode".)
    if (device.helper && data.move.setupDone) {
        HelperHome()
        return
    }

    val packing = data.uses(Part.BOXES)
    val next = nextStep(data, suggests = !data.can(device, Action.DECIDE) && data.can(device, Action.SUGGEST))
    // The one big button: packing a box whenever boxes are in use, otherwise the next step.
    val action = if (packing) DockAction("Pack a box", Icons.Outlined.Inventory2) { nav.open("pack") }
    else DockAction(next.button, next.icon) { nav.open(next.route) }
    // On a short screen the tall dock would leave too little room, so the button moves into the page.
    val roomy = LocalConfiguration.current.screenHeightDp >= 700

    Frame(tab = "home", topBar = { HomeTopBar(device) }, dockAction = if (roomy) action else null) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // On opening, the house has the page to itself and the next step only peeks in below it.
            val firstPage = if (roomy) minOf(maxHeight - 34.dp, 620.dp) else 0.dp
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                HomeHero(data, device, packing, Modifier.fillMaxWidth().heightIn(min = firstPage))
                HomeDetails(data, device, next, inPageAction = if (roomy) null else action, quietNext = packing && roomy && next.route == "pack")
            }
        }
    }
}

/** What Home opens on: the numbers, the name of the move, the house, and the slot for suggestions. */
@Composable
private fun HomeHero(data: AppData, device: DeviceInfo, packing: Boolean, modifier: Modifier = Modifier) {
    val nav = LocalNav.current
    val store = LocalStore.current
    val move = data.move
    Column(modifier) {
        var warning by remember { mutableStateOf(store.loadWarning) }
        warning?.let { w ->
            GlassCard(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp), tone = GlassTone.Red) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(w, color = MaterialTheme.colorScheme.onErrorContainer)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillButton("Sync and backup", onClick = { nav.open("sync") })
                        PillButton("Dismiss", onClick = { store.clearLoadWarning(); warning = null }, primary = false)
                    }
                }
            }
        }

        // Three numbers that say where the move stands.
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // On a narrow screen, or with large text, the little orbs go so the words fit.
            val tight = LocalConfiguration.current.let { it.screenWidthDp / it.fontScale < 340f }
            keyNumbers(data, device).forEach { k -> KeyNumberView(k, !tight, Modifier.weight(1f, fill = false)) { nav.open(k.route) } }
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

        // The house: the old home, with the next room to pack called out. It takes the middle of the page.
        val rooms = if (packing) data.roomPacking() else emptyList()
        val nextRoom = rooms.nextToPack(device.currentRoomId)
        val atOldHome = if (packing) data.liveBoxes.count { it.stage == Stage.AT_ORIGIN } else 0
        Spacer(Modifier.weight(1f))
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
            // The callout sits where the design has it: to the right of the marker on the extension.
            val tagAt = Modifier.align(Alignment.TopEnd).padding(end = 12.dp).offset(y = width * (206f / 390f)).widthIn(max = width * 0.6f)
            if (nextRoom != null) {
                GlassTag("${nextRoom.room.name} is next", nextRoom.leftText(), Icons.Outlined.Inventory2, tagAt, onClick = { nav.open("overview") })
            } else if (packing && rooms.isNotEmpty()) {
                GlassTag("Every room", "is packed", Icons.Default.Check, tagAt, kind = OrbKind.Primary, onClick = { nav.open("overview") })
            }
        }
        Spacer(Modifier.weight(1f))

        SuggestionsSlot(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        Spacer(Modifier.height(10.dp))
    }
}

/** Below the fold: the next step, the journey, and what needs attention. */
@Composable
private fun HomeDetails(data: AppData, device: DeviceInfo, next: NextStep, inPageAction: DockAction?, quietNext: Boolean) {
    val nav = LocalNav.current
    val move = data.move
    val now = currentPhase(data)
    // -1 follows the phase the move is in; tapping the strip looks at another one.
    var picked by rememberSaveable { mutableIntStateOf(-1) }
    val shown = if (picked < 0) now else picked

    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (inPageAction != null) BigAction(inPageAction.icon, inPageAction.label, inPageAction.onClick)

        Section {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Next step", style = MaterialTheme.typography.labelLarge, color = Night.MintPale, modifier = Modifier.weight(1f))
                // The days to go are among the numbers at the top. Only a move with no date yet asks for one here,
                // and only of someone who may set it.
                if (move.kind != MoveKind.INVENTORY && move.moveDay == null && data.can(device, Action.MOVE_SETTINGS)) {
                    GlassChip("Set the date", icon = Icons.Outlined.Event, tone = GlassTone.Amber, onClick = { nav.open("settings") })
                }
            }
            Text(next.text, style = MaterialTheme.typography.bodyLarge)
            // When the dock's big button already says the same thing, this one steps back.
            BigAction(next.icon, next.button, onClick = { nav.open(next.route) }, primary = !quietNext)
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

        // Needs attention: the worst three, the rest on request. Overdue jobs are raised only with
        // someone who may tick one of them off; for anyone else they are an Organiser's to see to.
        val me = data.me(device)
        val jobsAreMine = data.liveTasks.any { !it.done && (data.dueDay(it) ?: Long.MAX_VALUE) < today() && data.mayTick(me, it) }
        val issues = data.issues().filter { it.route != "tasks" || jobsAreMine }
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
                    GlassChip(
                        if (all) "Show fewer" else "Show ${issues.size - 3} more",
                        icon = if (all) Icons.Default.ExpandLess else Icons.Default.ExpandMore, onClick = { all = !all },
                    )
                }
            }
        }
    }
}

/** What the strip between the house and the dock says, or null when there is nothing to say. */
internal class SuggestionStrip(val people: List<Person?>, val title: String, val sub: String, val button: String)

private fun count(n: Int, one: String) = "$n $one" + if (n == 1) "" else "s"

/**
 * For someone who may approve: what is waiting for them. For a Decider or a Helper: how
 * their own suggestions and questions stand, while any wait or were answered in the last
 * few days. Null on a move where nobody has suggested anything.
 */
internal fun suggestionStrip(data: AppData, device: DeviceInfo, now: Long = System.currentTimeMillis()): SuggestionStrip? {
    if (data.can(device, Action.APPROVE)) {
        val waiting = data.waitingProposals()
        if (waiting.isEmpty()) return null
        val questions = waiting.count { it.kind == ProposalKind.QUESTION }
        val suggestions = waiting.size - questions
        return SuggestionStrip(
            people = waiting.map { it.by }.distinct().take(3).map { data.person(it) },
            title = when {
                questions == 0 -> count(suggestions, "suggestion") + " waiting"
                suggestions == 0 -> count(questions, "question") + " waiting"
                else -> "${waiting.size} waiting for you"
            },
            // The one that has waited longest, by name; the rest as a number.
            sub = data.describe(waiting.first()) + if (waiting.size > 1) " · ${waiting.size - 1} more" else "",
            button = "Review",
        )
    }
    val counts = data.proposalCounts(device.personId)
    val waiting = counts[ProposalState.WAITING] ?: 0
    val recent = data.proposalsBy(device.personId).filter { it.state != ProposalState.WAITING && now - it.decidedAt < 3 * 86_400_000L }
    if (waiting == 0 && recent.isEmpty()) return null
    val approved = counts[ProposalState.APPROVED] ?: 0
    val declined = counts[ProposalState.DECLINED] ?: 0
    // A Helper asks; a Decider suggests.
    val asks = data.roleOf(device) == MoveRole.HELPER
    val waits = when {
        waiting == 0 -> null
        asks -> count(waiting, "question") + " waiting"
        else -> "$waiting waiting for approval"
    }
    // A no is news the person should not have to go looking for: it is said here, with the reason given.
    val no = recent.filter { it.state == ProposalState.DECLINED }.maxByOrNull { it.decidedAt }
    if (no != null) {
        return SuggestionStrip(
            people = listOf(data.person(device.personId)),
            title = "Declined: " + data.describe(no),
            sub = listOfNotNull(if (no.reason.isBlank()) "No reason given" else "“${no.reason.trim()}”", waits).joinToString(" · "),
            button = "View",
        )
    }
    val answered = listOfNotNull(
        if (approved > 0) "$approved " + (if (asks) "answered" else "approved") else null,
        if (declined > 0) "$declined declined" else null,
    ).joinToString(" · ")
    return SuggestionStrip(
        people = listOf(data.person(device.personId)),
        title = waits ?: answered.ifBlank { "Decided meanwhile" },
        sub = when {
            waits == null -> if (asks) "Your questions" else "Your suggestions"
            else -> answered.ifBlank { "An Organiser decides" }
        },
        button = "View",
    )
}

/**
 * The strip between the house and the dock (design/look-d: "2 suggestions waiting · Review"):
 * who suggested, how many wait, what they are, and the way to Approvals. Draws nothing
 * when there is nothing to say, so a move with one person never sees it.
 */
@Composable
internal fun SuggestionsSlot(modifier: Modifier = Modifier) {
    val nav = LocalNav.current
    val device = deviceInfo()
    val strip = suggestionStrip(appData(), device)
    if (strip == null) {
        Box(modifier)
        return
    }
    GlassPill(
        modifier.heightIn(min = 58.dp), onClick = { nav.open("approvals") }, sheen = true,
        contentPadding = PaddingValues(start = 8.dp, top = 7.dp, bottom = 7.dp, end = 8.dp),
    ) {
        // The people who suggested, overlapping like coins.
        Box {
            strip.people.forEachIndexed { i, p -> PersonOrb(p, Modifier.padding(start = 26.dp * i), size = 42.dp, me = p != null && p.id == device.personId) }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(strip.title, style = MaterialTheme.typography.titleSmall, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (strip.sub.isNotBlank()) {
                Text(strip.sub, style = MaterialTheme.typography.bodySmall, color = Night.TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.glass(CircleShape, GlassTone.White, shadow = 0.dp).padding(horizontal = 16.dp, vertical = 9.dp)) {
            Text(strip.button, style = MaterialTheme.typography.labelLarge, color = Night.OnWhite, maxLines = 1)
        }
    }
}

/** Home's own top bar: search on the left, the app's mark, scan and this phone's person on the right. */
@Composable
private fun HomeTopBar(device: DeviceInfo) {
    val nav = LocalNav.current
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TopAction(Icons.Default.Search, "Find anything") { nav.open("search") }
        }
        AppMark()
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TopAction(Icons.Default.QrCodeScanner, "Scan a label") { nav.open("scan") }
            val initial = device.name.trim().firstOrNull()?.uppercaseChar()
            // Sized to the orb, which does not grow with the phone's text size.
            val letter = with(LocalDensity.current) { 19.dp.toSp() }
            GlassOrb(kind = OrbKind.Selected, onClick = { nav.open("people") }, contentDescription = "People") {
                if (initial != null) Text(initial.toString(), fontSize = letter, lineHeight = letter * 1.25f, fontWeight = FontWeight.SemiBold)
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
private fun KeyNumberView(k: KeyNumber, withOrb: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(24.dp)).clickable(role = Role.Button, onClick = onClick).heightIn(min = 48.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (withOrb) {
            GlassOrb(k.icon, null, size = 34.dp)
            Spacer(Modifier.width(6.dp))
        }
        Column(horizontalAlignment = if (withOrb) Alignment.Start else Alignment.CenterHorizontally) {
            Text(k.value, fontSize = 18.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(k.label, style = MaterialTheme.typography.bodySmall, fontSize = 11.sp, color = Night.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Home for a helper's phone: only the jobs a helper does, as large tiles. */
@Composable
private fun HelperHome() {
    val nav = LocalNav.current
    val store = LocalStore.current
    val data = appData()
    val device = deviceInfo()
    val p = data.progress()
    val mine = myPerson()
    // On a shared move the phone is a Helper because of its person's role; alone it is the old "helper mode".
    val shared = data.shared()
    // No search and scan buttons in the top bar here: both are among the tiles, with their names.
    Screen(
        title = data.move.name.ifBlank { "HouseMove" }, back = false, tab = "home",
        subtitle = if (shared && mine != null && mine.name.isNotBlank()) "${mine.name} · Helper" else "Helper",
    ) {
        ScrollColumn {
            // How the questions this Helper asked stand, when there are any.
            if (suggestionStrip(data, device) != null) SuggestionsSlot(Modifier.fillMaxWidth())
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
            if (shared) {
                // Who else is on the move, and what a Helper does. The role is an Organiser's to change.
                PersonPill(mine, "People", MoveRole.HELPER.line(), role = null, onClick = { nav.open("people") })
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Hint("Helper mode: only the essentials are shown.", Modifier.weight(1f))
                    GlassChip("Show everything", onClick = { store.setDevice { it.copy(helper = false) } })
                }
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
