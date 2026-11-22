package app.housemove.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role as SemanticsRole
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.housemove.data.*
import app.housemove.ui.*

/*
 * The parts of Home that differ by role (design 2.1, section 2). Everyone has the same house, key
 * numbers and dock above them, and Sync below. An Organiser then has what waits for them, the people,
 * the six phases and the changes refused at a sync; a Decider what waits for a decision, what became of
 * their suggestions and costs to suggest; a Helper what is physically next for the things, the jobs
 * given to them, their hand-overs and a way to ask.
 */

// ---- everyone ---------------------------------------------------------------------------------

/**
 * The banner about this phone's own role: "You are now a Decider" with what the role does, until it is
 * read; and for someone just promoted, that their costs and decisions arrive at the next sync with an
 * Organiser or a Decider, until this phone holds them ([awaitsHidden]).
 */
@Composable
internal fun RoleBanner(data: AppData, device: DeviceInfo, modifier: Modifier = Modifier) {
    val nav = LocalNav.current
    val store = LocalStore.current
    val seen = rememberSeenRoles(device.personId)
    val news = data.roleNews(device, seen.seenAt)
    // What the phone holds is read from the Store itself: the screens' copy never shows a Helper anything hidden.
    val raw by store.data.collectAsState()
    val waiting = raw.awaitsHidden(device)
    if (news == null && !waiting) return
    GlassCard(modifier.fillMaxWidth(), tone = GlassTone.Mint, shape = RoundedCornerShape(24.dp), sheen = true) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (news != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GlassOrb(news.role.icon(), null, kind = OrbKind.Primary, size = 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(news.title, style = MaterialTheme.typography.titleMedium)
                        Text(news.role.line(), style = MaterialTheme.typography.bodyMedium, color = Night.TextSoft)
                    }
                }
            }
            if (waiting) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Sync, contentDescription = null, tint = Night.MintLight, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Costs and decisions arrive at your next sync with an Organiser or Decider.",
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                    )
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (waiting) RowButton("Sync", onClick = { nav.open("sync") }, icon = Icons.Default.Sync, look = RowButtonLook.Primary)
                if (news != null) RowButton("Got it", onClick = { seen.mark(news.at) }, forward = false, icon = Icons.Default.Check)
            }
        }
    }
}

/** Sync, on everyone's Home of a move with more than one person: when this phone last synced, and the way to do it now. */
@Composable
internal fun SyncRow(device: DeviceInfo, modifier: Modifier = Modifier) {
    val nav = LocalNav.current
    GlassPill(modifier.fillMaxWidth().heightIn(min = 56.dp), onClick = { nav.open("sync") }, contentPadding = PaddingValues(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp)) {
        GlassOrb(Icons.Default.Sync, null, size = 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Sync", style = MaterialTheme.typography.titleSmall)
            Text(
                if (device.lastSyncAt == 0L) "Not synced yet" else "Last synced ${agoText(device.lastSyncAt)}",
                style = MaterialTheme.typography.bodySmall, color = Night.TextDim, maxLines = 1,
            )
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Night.TextDim)
    }
}

// ---- an Organiser -----------------------------------------------------------------------------

/** The people on the move as a row of orbs: how many, and who still has to join. Opens People. */
@Composable
internal fun PeopleRow(data: AppData, device: DeviceInfo, modifier: Modifier = Modifier) {
    val nav = LocalNav.current
    val people = data.livePeople.filter { !it.removed }
    val toJoin = people.count { it.joinedAt == 0L && it.id != device.personId }
    GlassPill(modifier.fillMaxWidth().heightIn(min = 58.dp), onClick = { nav.open("people") }, contentPadding = PaddingValues(start = 8.dp, end = 12.dp, top = 7.dp, bottom = 7.dp)) {
        Box {
            people.take(if (tightScreen()) 2 else 4).forEachIndexed { i, p ->
                PersonOrb(p, Modifier.padding(start = 26.dp * i), size = 42.dp, me = p.id == device.personId)
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("People", style = MaterialTheme.typography.titleSmall)
            Text(
                listOfNotNull(countOf(people.size, "person", "people"), if (toJoin > 0) "$toJoin still to join" else null).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = Night.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Night.TextDim)
    }
}

/**
 * Changes another phone made that were not accepted at a sync, for an Organiser: "Sam's phone tried to
 * mark Sofa sold; Sam is a Helper.", each with Dismiss. Nothing of costs is said ([describe]).
 */
@Composable
internal fun RefusedSection(data: AppData) {
    val store = LocalStore.current
    val context = LocalContext.current
    val refused = data.refusedChanges()
    if (refused.isEmpty()) return
    var all by rememberSaveable { mutableStateOf(false) }
    Section("Not accepted at a sync") {
        (if (all) refused else refused.take(3)).forEachIndexed { i, r ->
            if (i > 0) HorizontalDivider(color = Night.Hairline)
            FlowRow(
                Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Row(Modifier.weight(1f).widthIn(min = 200.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Block, contentDescription = null, tint = Night.AmberPale, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(data.describe(r), style = MaterialTheme.typography.bodyMedium)
                        Text(fmtDayShort(r.at / 86_400_000L), style = MaterialTheme.typography.bodySmall, color = Night.TextDim)
                    }
                }
                RowButton("Dismiss", onClick = { store.dismissRefused(r.id).okOrSay(context) }, forward = false)
            }
        }
        if (refused.size > 3) {
            GlassChip(if (all) "Show fewer" else "Show ${refused.size - 3} more", icon = if (all) Icons.Default.ExpandLess else Icons.Default.ExpandMore, onClick = { all = !all })
        }
    }
}

// ---- a Decider --------------------------------------------------------------------------------

/** What waits for a decision, what became of this Decider's suggestions, and costs to suggest. */
@Composable
internal fun DeciderSections(data: AppData, device: DeviceInfo) {
    val nav = LocalNav.current
    val undecided = data.liveItems.filter { it.disposition == Disposition.UNDECIDED }
    if (data.uses(Part.TRIAGE) && undecided.isNotEmpty()) {
        Section("Waiting for a decision", action = { RowButton("Suggest", onClick = { nav.open("triage") }) }) {
            Text(countOf(undecided.size, "thing") + " nobody has decided yet", style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                undecided.take(6).forEach { i ->
                    Column(
                        Modifier.width(84.dp).clip(RoundedCornerShape(14.dp)).clickable { nav.open("item/${i.id}") },
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        ItemPhoto(i.id, Modifier.size(84.dp))
                        Text(i.title(), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }

    // What became of their suggestions: the latest few, each as it stands now.
    val mine = data.proposalsBy(device.personId).take(4)
    if (mine.isNotEmpty()) {
        Section("Your suggestions", action = { RowButton("All", onClick = { nav.open("approvals") }, forward = false) }) {
            mine.forEachIndexed { i, p ->
                if (i > 0) HorizontalDivider(color = Night.Hairline)
                val (word, tone) = when (p.state) {
                    ProposalState.WAITING -> "Waiting" to GlassTone.Amber
                    ProposalState.APPROVED -> "Approved" to GlassTone.Mint
                    ProposalState.DECLINED -> "Declined" to GlassTone.Red
                    else -> "Decided otherwise" to GlassTone.Plain
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(data.describe(p), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (p.state == ProposalState.DECLINED) {
                            Text(
                                if (p.reason.isBlank()) "No reason given" else "“${p.reason.trim()}”",
                                style = MaterialTheme.typography.bodySmall, color = Night.TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    GlassChip(word, tone = tone)
                }
            }
        }
    }

    if (data.uses(Part.BUDGET)) {
        val costs = data.liveExpenses
        val waiting = data.proposalsBy(device.personId).count { it.kind == ProposalKind.COST && it.state == ProposalState.WAITING }
        GlassPill(Modifier.fillMaxWidth().heightIn(min = 58.dp), onClick = { nav.open("budget") }, contentPadding = PaddingValues(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp)) {
            GlassOrb(Icons.Default.Payments, null, size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Suggest a cost", style = MaterialTheme.typography.titleSmall)
                Text(
                    listOfNotNull(
                        if (costs.isEmpty()) "No costs yet" else money(data.move.currency, costs.sumOf { it.amount }) + " so far",
                        if (waiting > 0) "$waiting waiting" else null,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = Night.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Night.TextDim)
        }
    }
}

// ---- a Helper ---------------------------------------------------------------------------------

/** One thing a Helper can do now with their hands, from where the things are. */
internal data class PhysicalStep(val icon: ImageVector, val title: String, val detail: String, val route: String, val bringBack: Boolean = false)

/**
 * What is physically next for the things, in the order they travel: packing, loading, the old home's meters,
 * the sweep, unloading, the new home's meters, bringing things back from storage, unpacking. Worked out from
 * where the things are ([placeOf], their stages), never from the date.
 */
internal fun AppData.physicalNext(currentRoomId: String?): List<PhysicalStep> {
    val out = ArrayList<PhysicalStep>()
    val vehicle = vehicle()
    val mv = movables()
    val atOld = mv.filter { placeOf(it) == Place.OLD_HOME && !it.carriedByHand() }
    val left = mv.any { placeOf(it) != Place.OLD_HOME }
    val onTheWay = mv.filter { placeOf(it) == Place.VAN }
    val arrived = mv.any { placeOf(it) == Place.NEW_HOME }
    val stored = inStorage()
    val toUnpack = toUnpack()
    if (uses(Part.BOXES)) {
        val rooms = roomPacking()
        val open = topBoxes().count { !it.sealed && it.stage == Stage.AT_ORIGIN }
        val next = rooms.nextToPack(currentRoomId)
        when {
            open > 0 -> out += PhysicalStep(Pic.Box, "Pack and seal", countOf(open, "box", "boxes") + " still open" + (next?.let { " · ${it.room.name} is next" } ?: ""), "pack")
            next != null && !left -> out += PhysicalStep(Pic.Box, "Pack ${next.room.name}", next.leftText(), "pack")
        }
    }
    if (uses(Part.MOVE_DAY) && atOld.isNotEmpty()) {
        val packed = !uses(Part.BOXES) || (topBoxes().none { !it.sealed && it.stage == Stage.AT_ORIGIN } && roomPacking().all { it.room.packed })
        if (left || packed) out += PhysicalStep(Icons.Default.LocalShipping, "Load the $vehicle", countOf(atOld.size, "thing") + " at the old home", "load")
    }
    val readings = liveReadings
    if (uses(Part.RECORDS) && left && readings.none { it.home == Home.ORIGIN }) {
        out += PhysicalStep(Icons.Default.Speed, "Meters at the old home", "Photograph each meter before you leave", "records/ORIGIN")
    }
    val oldRooms = roomsOf(Home.ORIGIN)
    if (uses(Part.MOVE_DAY) && left && atOld.isEmpty() && oldRooms.any { !it.swept }) {
        out += PhysicalStep(Icons.Default.CleaningServices, "Final sweep", "${oldRooms.count { !it.swept }} of ${oldRooms.size} rooms to check", "sweep")
    }
    if (uses(Part.MOVE_DAY) && onTheWay.isNotEmpty()) {
        val van = onTheWay.count { it.onTheVan() }
        out += PhysicalStep(
            Icons.Default.Download, "Unload",
            listOfNotNull(if (van > 0) "$van on the $vehicle" else null, (onTheWay.size - van).takeIf { it > 0 }?.let { "$it carried by hand" }).joinToString(" · "),
            "unload",
        )
    }
    if (uses(Part.RECORDS) && (arrived || onTheWay.isNotEmpty()) && readings.none { it.home == Home.DEST }) {
        out += PhysicalStep(Icons.Default.Speed, "Meters at the new home", "Photograph each meter as you arrive", "records/DEST")
    }
    if (uses(Part.MOVE_DAY) && stored.isNotEmpty()) {
        out += PhysicalStep(Icons.Default.Warehouse, BRING_BACK_TRIP_NAME, countOf(stored.size, "thing") + " in storage", "load", bringBack = true)
    }
    if (uses(Part.UNPACK) && toUnpack.isNotEmpty()) {
        out += PhysicalStep(Icons.Default.Unarchive, "Unpack", countOf(toUnpack.size, "thing") + " to unpack", "unpack")
    }
    return out
}

/** A Helper's part of Home: what is next for the things, today's jobs, their hand-overs, Ask a question. */
@Composable
internal fun HelperSections(data: AppData, device: DeviceInfo) {
    val nav = LocalNav.current
    val store = LocalStore.current
    val context = LocalContext.current
    val me = data.me(device)
    var asking by remember { mutableStateOf(false) }

    fun go(step: PhysicalStep) {
        if (step.bringBack) mdStorageTrip(store, fromStorage = true)
        if (step.route == "pack") {
            val next = data.roomPacking().nextToPack(device.currentRoomId)
            if (next != null) store.setDevice { it.copy(currentRoomId = next.room.id) }
        }
        nav.open(step.route)
    }

    val steps = data.physicalNext(device.currentRoomId)
    Section("Next for the things") {
        if (steps.isEmpty()) {
            Text("Nothing to carry right now.", style = MaterialTheme.typography.bodyLarge)
        } else {
            val first = steps.first()
            MdMainAction(first.icon, first.title, onClick = { go(first) }, sub = first.detail)
            steps.drop(1).take(3).forEach { s -> StepRow(s.icon, s.title, onClick = { go(s) }, status = s.detail) }
        }
    }

    // Jobs given to this person that are due by today, or have no date.
    val jobs = data.liveTasks.filter { t -> t.assignedTo == me?.id && me != null && !t.done && (data.dueDay(t) ?: Long.MIN_VALUE) <= today() }
    if (jobs.isNotEmpty()) {
        Section("Your jobs today") {
            jobs.forEach { t ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .toggleable(t.done, role = SemanticsRole.Checkbox) { v -> store.tick(t.id, v).okOrSay(context) }
                        .heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = t.done, onCheckedChange = null)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t.title, style = MaterialTheme.typography.bodyLarge)
                        val due = data.dueDay(t)
                        if (due != null && due < today()) Text("Was due ${fmtDayShort(due)}", style = MaterialTheme.typography.bodySmall, color = Night.AmberPale)
                    }
                }
            }
        }
    }

    // Hand-overs given to this person.
    val handovers = data.handoversFor(me?.id).mapNotNull { data.item(it.itemId) }
    if (handovers.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ListHeading("Your hand-overs", Modifier.padding(start = 6.dp), count = handovers.size)
            handovers.forEach { HandoverRow(it) }
        }
    }

    BigAction(Icons.Default.HelpOutline, "Ask a question", onClick = { asking = true }, primary = false)
    if (asking) AskDialog(onClose = { asking = false })
}

/**
 * Ask the Organisers about one thing, without the keyboard: what to ask is said (or left out), and the
 * thing is picked by its photo or by scanning its label.
 */
@Composable
internal fun AskDialog(onClose: () -> Unit) {
    val store = LocalStore.current
    val context = LocalContext.current
    val data = appData()
    var words by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }

    fun ask(itemId: String) {
        val name = data.item(itemId)?.title() ?: "it"
        if (store.ask(itemId, words).okOrSay(context)) {
            toast(context, "Asked the Organisers about $name.")
            onClose()
        }
    }

    if (scanning) {
        ScanDialog(
            title = "Scan the label of the thing",
            onCode = { code ->
                val hit = store.data.value.findByLabel(code)
                when {
                    code.isBlank() -> Unit
                    hit == null -> toast(context, "Nothing here has the label $code.")
                    hit.isBox -> toast(context, "${hit.title} is a container. Ask about a thing in it from its own page.")
                    else -> { scanning = false; ask(hit.id) }
                }
            },
            onClose = { scanning = false },
        )
        return
    }

    // Things nobody has decided yet come first: those are what questions are usually about.
    val things = data.liveItems.filter { !it.gone }.sortedWith(compareBy({ it.disposition != Disposition.UNDECIDED }, { it.title() }))
    GlassDialog(
        "Ask a question", onDismiss = onClose, icon = Icons.Default.HelpOutline,
        text = "Pick the thing. The Organisers answer it.",
        buttons = { DialogCancel(onClose) },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SpeakButton("What do you want to ask?", contentDescription = "Say what you want to ask", size = 44.dp) { said -> words = said.trim() }
            Text(
                words.ifBlank { "Say what you want to know, or just pick the thing" },
                style = MaterialTheme.typography.bodyMedium, color = if (words.isBlank()) Night.TextDim else Night.Text,
                modifier = Modifier.weight(1f),
            )
        }
        BigAction(Icons.Default.QrCodeScanner, "Scan its label", onClick = { scanning = true }, primary = false)
        Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            things.forEach { i ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClickLabel = "Ask about ${i.title()}") { ask(i.id) }
                        .heightIn(min = 52.dp).padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ItemPhoto(i.id, Modifier.size(44.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(i.title(), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (i.disposition == Disposition.UNDECIDED) GlassChip("Undecided", tone = GlassTone.Amber)
                }
            }
        }
    }
}
