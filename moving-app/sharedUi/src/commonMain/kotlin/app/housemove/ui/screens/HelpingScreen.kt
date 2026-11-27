package app.housemove.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import app.housemove.ui.icons.Icons
import app.housemove.ui.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.housemove.data.*
import app.housemove.sync.WifiSync
import app.housemove.ui.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** What the code panel of Who's helping shows: one person's invite, or the Helper code. */
private const val HELPER_CODE_TAB = "*helpers"

/**
 * Who's helping? Offered once a new move is saved, and from People at any time. A second
 * Organiser is suggested first, so the move never depends on one phone. People are added by
 * saying or typing a name and tapping what they will do, as many as needed. Each gets an
 * invite of their own, shown one at a time; when an Organiser's or a Decider's phone comes
 * with it, this phone says "Joined: Priya, on Priya's phone" and nothing is sent until Right.
 * Helpers can all use one Helper code instead; those who joined with it are listed, each with
 * Remove. "Do this later" goes to Home.
 */
@Composable
fun HelpingScreen() {
    val store = LocalStore.current
    val nav = LocalNav.current
    val context = LocalPlatform.current
    val data = appData()
    val device = deviceInfo()
    val scope = rememberCoroutineScope()
    val addressesNow = LocalSyncAddresses.current
    // Opened straight after the move was saved, nothing is under it: the way on is "Do this later", not Back.
    val firstRun = remember { nav.previousBackStackEntry == null }

    fun leave() {
        if (nav.previousBackStackEntry == null) nav.navigate("home") { popUpTo(0) { inclusive = true } } else nav.popBackStack()
    }

    val refusal = data.refusal(myPerson(), Action.MANAGE_PEOPLE)
    if (refusal != null) {
        Screen("Who's helping?", subtitle = data.move.name.ifBlank { "Your move" }) { EmptyState(Icons.Default.Lock, refusal) }
        return
    }

    var host by remember { mutableStateOf<WifiSync.Host?>(null) }
    var looked by remember { mutableIntStateOf(0) }
    val addresses = remember(looked) { addressesNow() }
    /** Something to say about the last phone that came: who joined, or what went wrong. */
    var heard by remember { mutableStateOf<SyncReport?>(null) }
    DisposableEffect(Unit) { onDispose { host?.stop() } }
    LaunchedEffect(addresses) {
        if (host == null && addresses.isNotEmpty()) {
            host = try {
                // Nobody gets in through this screen except with a code shown on it, and an Organiser's or a Decider's phone waits for Right.
                WifiSync.host(store, letNewPhonesIn = false, confirmJoins = true) { r ->
                    scope.launch {
                        if (r.listsSynced()) store.setDevice { it.copy(lastSyncAt = nowMillis(), lastSyncWith = r.peerName) }
                        val who = r.joinedAs
                        heard = when {
                            who != null -> SyncReport(true, "${who.shownName()}'s phone has joined", "It is now ${who.shownName()}, ${who.role.label}." + plainNote(r))
                            // Undo on this phone needs no telling back.
                            r.error == WifiSync.UNDONE -> SyncReport(true, "Undone", "Nothing was sent to that phone. The invite still works for the right one.")
                            !r.ok -> syncReport(r, store, context.words)
                            else -> null
                        }
                    }
                }
            } catch (_: Exception) {
                heard = SyncReport(false, "Could not start", "This phone could not start showing codes. Try again, or restart HouseMove.")
                null
            }
        }
    }
    val h = host
    val waiting by remember(h) { h?.waiting ?: MutableStateFlow(emptyList()) }.collectAsState()
    val helpersJoined by remember(h) { h?.helpersJoined ?: MutableStateFlow(emptyList()) }.collectAsState()

    // Who still has to join, those who decide things first: each gets an invite of their own.
    val toJoin = data.people.filter { !it.removed && it.id != device.personId && it.joinedAt == 0L }
        .sortedWith(compareBy({ it.role != Role.ORGANISER }, { it.role != Role.DECIDER }, { it.grants.firstOrNull()?.at ?: 0L }, { it.id }))
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    val shown = chosen?.takeIf { c -> c == HELPER_CODE_TAB || toJoin.any { it.id == c } } ?: toJoin.firstOrNull()?.id
    val organisers = data.people.count { it.role == Role.ORGANISER && !it.removed }

    Screen(
        "Who's helping?", back = !firstRun, subtitle = data.move.name.ifBlank { "Your move" },
    ) {
        // Someone just added: their invite is brought into sight, below the ways to add people.
        val codes = remember { BringIntoViewRequester() }
        var added by remember { mutableIntStateOf(0) }
        LaunchedEffect(added) { if (added > 0) runCatching { codes.bringIntoView() } }
        fun add(name: String, role: Role) {
            store.addPerson(name, role).orSay(context)?.let {
                chosen = it.id
                added++
            }
        }
        ScrollColumn(Modifier.weight(1f)) {
            // A phone that came with an Organiser's or a Decider's invite comes first: nothing is sent until this phone says it is the right one.
            val request = waiting.firstOrNull()
            if (h != null && request != null) {
                JoinRequestCard(request, data.person(request.personId), onAnswer = { right -> h.confirm(request.id, right) })
            }
            heard?.let { report -> SyncReportCard(report) }

            if (organisers < 2) SecondOrganiser { name -> add(name, Role.ORGANISER) }
            AddSomeone { name, role -> add(name, role) }

            // The codes wait while the phone that came is answered.
            if (h != null && request != null) Unit
            else if (addresses.isEmpty()) {
                Section(tone = GlassTone.Red) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Default.WifiOff, contentDescription = null, tint = Night.RedPale)
                        Text(WifiSync.NO_NETWORK, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    }
                    GlassChip("Check again", icon = Icons.Default.Refresh, onClick = { looked++ })
                }
            } else if (h != null) {
                CodePanel(h, addresses, toJoin, shown, onShow = { chosen = it }, helpersJoined = helpersJoined, modifier = Modifier.bringIntoViewRequester(codes))
            }

            // Android sends its own app, or shows where to download it; an iPhone can do neither, and says how phones get it.
            Hint(context.words.getTheApp)
            if (context.shareTheApp != null || context.words.downloadCode) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (context.shareTheApp != null) GlassChip("Share the app", icon = Icons.Default.Share, onClick = { shareTheApp(context, scope) })
                    if (context.words.downloadCode) DownloadCodeButton(chip = true)
                }
            }
            if (!context.words.iphone) Hint(IPHONE_GETS_IT)
        }
        BigAction(
            if (toJoin.isEmpty()) Icons.Default.Check else Icons.Default.Schedule,
            if (toJoin.isEmpty() && !firstRun) "Done" else "Do this later",
            onClick = { leave() }, primary = toJoin.isEmpty(), modifier = Modifier.padding(16.dp),
        )
    }
}

/** The suggestion that comes first: a second Organiser, said or typed, so the move never depends on one phone. */
@Composable
private fun SecondOrganiser(onName: (String) -> Unit) {
    var typing by rememberSaveable { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    Section(tone = GlassTone.Mint) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlassOrb(Role.ORGANISER.icon(), null, size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Add a second Organiser", style = MaterialTheme.typography.titleMedium)
                Text("So the move never depends on one phone.", style = MaterialTheme.typography.bodyMedium, color = Night.TextSoft)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SpeakButton("Who else runs the move?", contentDescription = "Say the second Organiser's name", label = "Say their name") { said ->
                onName(said.replaceFirstChar { it.uppercase() })
            }
            GlassChip("Type it", icon = Icons.Default.Edit, selected = typing, onClick = { typing = !typing }, modifier = Modifier.align(Alignment.CenterVertically))
        }
        if (typing) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextInput(typed, { typed = it }, "Their name", Modifier.weight(1f), key = "second-organiser")
                Spacer(Modifier.width(8.dp))
                PillButton("Add", onClick = {
                    if (typed.isNotBlank()) {
                        onName(typed.trim())
                        typed = ""
                        typing = false
                    }
                }, enabled = typed.isNotBlank())
            }
        }
    }
}

/**
 * Add people with nothing typed: say a name (or type it, behind the pencil), then tap what they
 * will do. Each tap on a role adds one person; with no name they are "Helper 2" and so on.
 */
@Composable
private fun AddSomeone(onAdd: (name: String, role: Role) -> Unit) {
    val data = appData()
    var name by rememberSaveable { mutableStateOf("") }
    var typing by rememberSaveable { mutableStateOf(false) }
    var spoken by rememberSaveable { mutableIntStateOf(0) }
    Section("Add people") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PersonOrb(Person(id = "", name = name, letter = data.freeLetter()), size = 48.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name.ifBlank { "Say or type a name" }, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("Then tap what they will do", style = MaterialTheme.typography.bodySmall, color = Night.TextDim)
            }
            SpeakButton("What is their name?", contentDescription = "Say a name") { said ->
                name = said.replaceFirstChar { it.uppercase() }
                spoken++
            }
            Spacer(Modifier.width(4.dp))
            GlassOrb(Icons.Default.Edit, "Type a name") { typing = !typing }
        }
        if (typing) TextInput(name, { name = it }, "Name", key = "helping-name-$spoken")
        // The three roles as three buttons: one tap adds the person.
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (role in listOf(Role.HELPER, Role.DECIDER, Role.ORGANISER)) {
                val who = name.trim()
                GlassSurface(
                    Modifier.weight(1f).fillMaxHeight().heightIn(min = 72.dp)
                        .semantics(mergeDescendants = true) { contentDescription = if (who.isEmpty()) "Add a ${role.label}" else "Add $who as ${role.withArticle()}" },
                    shape = RoundedCornerShape(18.dp), shadow = 4.dp,
                    onClick = {
                        onAdd(who, role)
                        name = ""
                        typing = false
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(Modifier.padding(vertical = 10.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(role.icon(), contentDescription = null, tint = Night.Mint, modifier = Modifier.size(24.dp))
                        Text(role.label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, maxLines = 1)
                    }
                }
            }
        }
        Hint("Organisers run the move; Deciders say what goes; Helpers pack, carry and scan.")
    }
}

/**
 * One code at a time: the invite of each person still to join (a chip each), or the Helper
 * code, which any number of Helpers can scan for ten minutes. Under the Helper code, who
 * joined with it, each with Remove.
 */
@Composable
private fun CodePanel(
    host: WifiSync.Host,
    addresses: List<String>,
    toJoin: List<Person>,
    shown: String?,
    onShow: (String) -> Unit,
    helpersJoined: List<Person>,
    modifier: Modifier = Modifier,
) {
    val store = LocalStore.current
    val context = LocalPlatform.current
    val data = appData()
    Section(if (toJoin.isEmpty()) "Helper code" else "Invites", modifier) {
        // Each person's invite is made when it is shown, and lasts ten minutes; the Helper code likewise.
        val code: ShownInvite? = remember(shown, host) {
            val made = when (shown) {
                null -> null
                HELPER_CODE_TAB -> host.helperCode(addresses)
                else -> host.inviteFor(shown, addresses)
            }
            made?.valueOrNull()?.let { ShownInvite(it.encode(), clockIn(WifiSync.INVITE_MINUTES.toLong())) }
        }
        // A chip for each code there is to choose between; with nobody waiting to join there is only the Helper code.
        if (toJoin.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            toJoin.forEach { p ->
                GlassChip(p.shownName(), selected = shown == p.id, onClick = { onShow(p.id) }, leading = { PersonOrb(p, size = 22.dp) })
            }
            GlassChip("Helper code", icon = Role.HELPER.icon(), selected = shown == HELPER_CODE_TAB, onClick = { onShow(HELPER_CODE_TAB) })
        }
        if (shown == null || code == null) {
            // Nobody waiting to join: the Helper code is one tap away.
            Text(
                "Helpers can all join with one code: no names to add first. Each phone gives the name it knows.",
                style = MaterialTheme.typography.bodyMedium, color = Night.TextSoft,
            )
            PillButton("Show the Helper code", onClick = { onShow(HELPER_CODE_TAB) }, icon = Icons.Default.QrCode2, modifier = Modifier.fillMaxWidth())
        } else if (shown == HELPER_CODE_TAB) {
            CodeImage(code.text, "Helper code")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                GlassChip("Any number of Helpers", icon = Icons.Default.Groups, tone = GlassTone.Amber)
                GlassChip("Until ${code.until}", icon = Icons.Default.Timer, tone = GlassTone.Amber)
            }
            Text(
                "On each Helper's phone: open HouseMove, tap Join a move and point it here. Each phone joins under the name it already knows.",
                style = MaterialTheme.typography.bodyMedium, color = Night.TextSoft, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
            Text(IPHONE_JOINS, style = MaterialTheme.typography.bodyMedium, color = Night.TextSoft, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            val joined = helpersJoined.mapNotNull { data.person(it.id) }.filter { !it.removed }
            if (joined.isNotEmpty()) {
                Text("Joined with this code", style = MaterialTheme.typography.labelLarge, color = Night.MintPale)
                joined.forEach { p ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PersonOrb(p, size = 36.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.shownName(), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            p.phones.firstOrNull()?.name?.takeIf { it.isNotBlank() }?.let { Hint("on $it") }
                        }
                        PillButton("Remove", onClick = { store.removePerson(p.id).orSay(context) }, icon = Icons.Default.PersonRemove, primary = false, tone = GlassTone.Red)
                    }
                }
            }
        } else {
            val p = data.person(shown)
            if (p != null) InviteCard(p.shownName(), isMe = false, invite = code, inner = true)
            if (toJoin.size > 1) {
                val next = toJoin[(toJoin.indexOfFirst { it.id == shown } + 1) % toJoin.size]
                PillButton("Next: ${next.shownName()}", onClick = { onShow(next.id) }, icon = Icons.Default.SkipNext, primary = false, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/** A code to scan, large on white, with what it says kept for tests and the audit. */
@Composable
internal fun CodeImage(text: String, description: String) {
    val qr = LocalPlatform.current.let { phone -> remember(text) { phone.qrImage(text, 640) } }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Image(
            qr, contentDescription = description,
            modifier = Modifier.size(232.dp).clip(RoundedCornerShape(20.dp)).background(Color.White).padding(10.dp)
                .semantics { this[InviteText] = text },
        )
    }
}

/**
 * A phone has come with an Organiser's or a Decider's invite: "Joined: Priya, on Priya's
 * phone". Right lets it in; Undo sends nothing and leaves the invite for the right phone.
 */
@Composable
internal fun JoinRequestCard(request: WifiSync.JoinRequest, person: Person?, onAnswer: (right: Boolean) -> Unit) {
    Section(tone = GlassTone.Amber, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PersonOrb(person, size = 52.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(request.text, style = MaterialTheme.typography.titleMedium)
                Text("Is that the right phone? Nothing is sent to it until you say.", style = MaterialTheme.typography.bodyMedium, color = Night.TextSoft)
            }
        }
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("Undo", onClick = { onAnswer(false) }, icon = Icons.Default.Undo, primary = false, modifier = Modifier.weight(1f).fillMaxHeight())
            PillButton("Right", onClick = { onAnswer(true) }, icon = Icons.Default.Check, modifier = Modifier.weight(1f).fillMaxHeight())
        }
    }
}

/** For screens that host: the phones waiting for Right or Undo, as state. */
@Composable
internal fun waitingOn(host: WifiSync.Host?): State<List<WifiSync.JoinRequest>> =
    remember(host) { host?.waiting ?: MutableStateFlow<List<WifiSync.JoinRequest>>(emptyList()) as StateFlow<List<WifiSync.JoinRequest>> }.collectAsState()
