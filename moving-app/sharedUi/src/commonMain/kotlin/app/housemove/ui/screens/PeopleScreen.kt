package app.housemove.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.housemove.data.*
import app.housemove.sync.WifiSync
import app.housemove.ui.*
import kotlinx.coroutines.launch

/** Roles in the order they are offered: the one most people get first. */
private val ROLE_CHOICES = listOf(Role.HELPER, Role.DECIDER, Role.ORGANISER).map { Choice(it, it.label, it.icon()) }

/**
 * A wide glass pill for the way on from a screen: a glowing ball with a picture, two
 * lines, an arrow. The same shape as "Pack a box" on the rooms overview.
 */
@Composable
internal fun ActionPill(icon: ImageVector, title: String, sub: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    GlassPill(
        modifier.fillMaxWidth(), onClick = onClick, sheen = true,
        contentPadding = PaddingValues(start = 10.dp, top = 10.dp, bottom = 10.dp, end = 14.dp),
    ) {
        Box(Modifier.size(46.dp).drawBehind { drawOrb(OrbKind.Primary, ring = false) }, contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = Night.OnMint, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(sub, style = MaterialTheme.typography.bodySmall, fontSize = 13.sp, color = Night.TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null)
    }
}

// ---- everyone on the move ---------------------------------------------------------------

/**
 * Everyone on the move, with their role and whether their phone has joined. Organisers
 * add people from here and open one to change their role, invite a phone or remove them;
 * everyone else sees the list.
 */
@Composable
fun PeopleScreen() {
    val nav = LocalNav.current
    val data = appData()
    val device = deviceInfo()
    val manage = allowed(Action.MANAGE_PEOPLE)
    val shared = data.shared()
    // This phone's person first, then by role; people who were removed come last.
    val people = data.people.sortedWith(compareBy({ it.removed }, { it.id != device.personId }, { it.role.ordinal }, { it.name.lowercase() }, { it.id }))

    Screen("People", tab = "home", subtitle = data.move.name.ifBlank { "Your move" }) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (manage) {
                // Who's helping: say a name, tap a role, as many as needed; invites one at a time, and the Helper code.
                ActionPill(Icons.Default.PersonAdd, "Add a person", "Say a name, tap a role. They join by scanning a code here.") { nav.open("helping") }
            }
            people.forEach { p ->
                PersonRow(
                    p, isMe = p.id == device.personId, showRole = shared || p.removed,
                    onClick = if (manage) ({ nav.open("person/${p.id}") }) else null,
                )
            }
            if (!shared) {
                EmptyState(
                    Icons.Default.Groups, "Just you so far",
                    text = if (manage) "Add the people who are helping. Each one gets a role and joins with a code." else null,
                )
            } else {
                RoleLegend(myRole())
            }
            Lately(data)
        }
    }
}

@Composable
private fun PersonRow(p: Person, isMe: Boolean, showRole: Boolean, onClick: (() -> Unit)?) {
    GlassCard(Modifier.fillMaxWidth(), onClick = onClick, shape = RoundedCornerShape(26.dp), sheen = isMe) {
        Row(Modifier.padding(start = 12.dp, top = 11.dp, bottom = 11.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            PersonOrb(p, size = 48.dp, me = isMe)
            Spacer(Modifier.width(12.dp))
            // The role sits at the right where there is room, and under the name where there is not.
            val tight = tightScreen()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(if (isMe && p.name.isBlank()) "You" else p.shownName(), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (tight && showRole) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        RoleChip(p.role, Modifier.align(Alignment.CenterVertically))
                        Box(Modifier.align(Alignment.CenterVertically)) { PersonStatus(p, isMe) }
                    }
                } else PersonStatus(p, isMe)
            }
            if (showRole && !tight) RoleChip(p.role)
            if (onClick != null) Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Night.TextDim, modifier = Modifier.padding(start = 4.dp))
        }
    }
}

/** Where someone stands, with a picture as well as a colour: this phone, joined, not yet, removed. */
@Composable
private fun PersonStatus(p: Person, isMe: Boolean) {
    when {
        p.removed -> IconFlag(Icons.Default.PersonOff, "Removed", error = true)
        isMe -> QuietStatus(Icons.Default.PhoneAndroid, "This phone")
        p.joinedAt != 0L -> QuietStatus(Icons.Default.CheckCircle, "Joined")
        else -> IconFlag(Icons.Default.HourglassTop, "Not joined yet")
    }
}

@Composable
private fun QuietStatus(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = Night.MintLight, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(5.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = Night.TextDim, maxLines = 1)
    }
}

/** One line on what each role can do, with the role of this phone's person marked. Folded: it is read once. */
@Composable
private fun RoleLegend(mine: Role) {
    Fold("Who can do what", Icons.Default.HelpOutline, summary = "You are " + mine.withArticle()) {
        Role.entries.forEach { r ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlassOrb(size = 36.dp) { Icon(r.icon(), contentDescription = null, tint = Night.Mint, modifier = Modifier.size(18.dp)) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(r.label, style = MaterialTheme.typography.titleSmall)
                    Text(r.line(), style = MaterialTheme.typography.bodySmall, color = Night.TextDim)
                }
                if (r == mine) GlassChip("You", tone = GlassTone.Mint)
            }
        }
    }
}

/** The last few things anyone did, and the way to the whole list. */
@Composable
private fun Lately(data: AppData) {
    val nav = LocalNav.current
    val latest = data.latestActivity(3)
    if (latest.isEmpty()) return
    Section("Lately", action = { GlassChip("All activity", icon = Icons.Default.History, onClick = { nav.open("activity") }) }) {
        latest.forEach { a -> ActivityLine(a, data, time = agoText(a.at)) }
    }
}

/** One line of the log: who, what, when. With [onClick] it is a row to tap, a full 48dp tall, with an arrow. */
@Composable
private fun ActivityLine(a: Activity, data: AppData, time: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = if (onClick != null) 48.dp else 36.dp).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PersonOrb(data.person(a.by), size = 28.dp, me = a.by == deviceInfo().personId)
        Spacer(Modifier.width(10.dp))
        Text(data.line(a), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(8.dp))
        Text(time, style = MaterialTheme.typography.bodySmall, color = Night.TextDim, maxLines = 1)
        if (onClick != null) Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Night.TextDim, modifier = Modifier.padding(start = 2.dp).size(18.dp))
    }
}

// ---- adding someone ------------------------------------------------------------------------

/**
 * Add a person with nothing typed: their name can be spoken (or left as "Helper 2"),
 * the role is one of three pictures, the letter for their box codes is picked for them.
 * Adding leads straight to their invite.
 */
@Composable
fun AddPersonScreen() {
    val store = LocalStore.current
    val nav = LocalNav.current
    val context = LocalPlatform.current
    val data = appData()
    var name by rememberSaveable { mutableStateOf("") }
    var role by rememberSaveable { mutableStateOf(Role.HELPER) }
    var typing by rememberSaveable { mutableStateOf(false) }
    var spoken by rememberSaveable { mutableIntStateOf(0) }

    Screen("Add a person", subtitle = "People") {
        val refusal = data.refusal(myPerson(), Action.MANAGE_PEOPLE)
        if (refusal != null) {
            EmptyState(Icons.Default.Lock, refusal)
            return@Screen
        }
        // What the Store will call them if no name is given.
        val standIn = role.label + " " + (data.people.count { it.role == role } + 1)
        val letter = data.freeLetter()
        // On a tall phone the person is drawn large and the page sits in the middle; on a short one everything is smaller and scrolls.
        val roomy = screenDp().let { it.screenHeightDp / it.fontScale >= 700 }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(if (roomy) 16.dp else 12.dp, Alignment.CenterVertically),
        ) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                PersonOrb(Person(id = "", name = name.ifBlank { standIn }, letter = letter), size = if (roomy) 104.dp else 64.dp)
                Text(
                    name.ifBlank { standIn }, style = if (roomy) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when {
                        name.isNotBlank() -> "Letter $letter on their box codes"
                        // With no speech on this phone there is no microphone: only the pencil.
                        canSpeak() -> "Say their name, or keep this one"
                        else -> "Type their name, or keep this one"
                    },
                    style = MaterialTheme.typography.bodySmall, color = Night.TextDim, textAlign = TextAlign.Center,
                )
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    SpeakButton("What is their name?", contentDescription = "Say the name", label = "Say their name") { said ->
                        name = said.replaceFirstChar { it.uppercase() }
                        spoken++
                    }
                    GlassOrb(Icons.Default.Edit, "Type the name") { typing = !typing }
                }
            }
            if (typing) TextInput(name, { name = it }, "Name", key = "new-person-$spoken")

            Text("What will they do?", style = MaterialTheme.typography.titleMedium)
            IconChoice(ROLE_CHOICES, role, { role = it }, large = !tightScreen(), columns = 3)
            RoleAbilities(role)
        }
        BigAction(
            Icons.Default.QrCode2, "Add and show invite",
            onClick = {
                store.addPerson(name, role).orSay(context)?.let { p ->
                    nav.navigate("person/${p.id}?invite=true") { popUpTo("people") }
                }
            },
            modifier = Modifier.padding(16.dp),
        )
    }
}

/**
 * What someone with [role] can do, in three lines with a tick, an hourglass or a bar.
 * Every line is asked of the Store's own table ([AppData.can]), so the screen cannot say
 * anything the rules do not.
 */
@Composable
private fun RoleAbilities(role: Role) {
    val data = appData()
    val work = data.can(role, Action.WORK)
    val manage = data.can(role, Action.MANAGE_PEOPLE)
    Section(role.withArticle().replaceFirstChar { it.uppercase() } + " can") {
        Ability(if (work) Icons.Default.CheckCircle else Icons.Default.Block, "Photograph, pack, scan on and off the van, unpack", work)
        when {
            data.can(role, Action.DECIDE) -> Ability(Icons.Default.CheckCircle, "Decide what to keep, sell or buy", true)
            data.can(role, Action.SUGGEST) -> Ability(Icons.Default.HourglassTop, "Suggest what to keep, sell or buy, for an Organiser to approve", true, amber = true)
            else -> Ability(Icons.Default.Block, "Decide what to keep, sell or buy", false)
        }
        Ability(if (manage) Icons.Default.CheckCircle else Icons.Default.Block, "Add people and change the move", manage)
    }
}

@Composable
private fun Ability(icon: ImageVector, text: String, can: Boolean, amber: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon, contentDescription = if (can) "Can" else "Cannot",
            tint = if (amber) Night.Amber else if (can) Night.MintLight else Night.Slate, modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = if (can) Night.Text else Night.TextDim)
    }
}

// ---- one person -----------------------------------------------------------------------------

internal class ShownInvite(val text: String, val until: String)

/**
 * One person, for an Organiser: their invite, their role, removing them and adding them
 * back. [showInvite] makes an invite straight away, as after adding someone.
 * Anyone else who ends up here sees who the person is and nothing to change.
 */
@Composable
fun PersonScreen(id: String, showInvite: Boolean = false) {
    val store = LocalStore.current
    val context = LocalPlatform.current
    val data = appData()
    val device = deviceInfo()
    val scope = rememberCoroutineScope()
    val addressesNow = LocalSyncAddresses.current
    val p = data.person(id)
    if (p == null) {
        Screen("Person", subtitle = "People") { EmptyState(Icons.Default.PersonOff, "This person is no longer here") }
        return
    }
    val manage = allowed(Action.MANAGE_PEOPLE)
    val isMe = p.id == device.personId
    val name = p.shownName()

    var host by remember { mutableStateOf<WifiSync.Host?>(null) }
    var invite by remember { mutableStateOf<ShownInvite?>(null) }
    var joined by remember { mutableStateOf(false) }

    /** Set when the phone joined without encryption, because it has an older HouseMove: said under "has joined". */
    var joinedPlain by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    var naming by rememberSaveable { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { host?.stop() } }

    fun makeInvite() {
        joined = false
        problem = null
        invite = null
        val addresses = addressesNow()
        if (addresses.isEmpty()) {
            problem = WifiSync.NO_NETWORK
            return
        }
        try {
            // Nobody gets in through this screen except with the invite made here.
            // An Organiser's or a Decider's phone waits for Right on this phone before anything is sent.
            val h = host ?: WifiSync.host(store, letNewPhonesIn = false, confirmJoins = true) { r ->
                scope.launch {
                    if (r.listsSynced()) store.setDevice { it.copy(lastSyncAt = nowMillis(), lastSyncWith = r.peerName) }
                    if (r.ok && r.joinedAs?.id == id) {
                        joined = true
                        joinedPlain = plainNote(r).trim()
                        invite = null
                    } else if (r.error == WifiSync.UNDONE) problem = "Undone: nothing was sent to that phone. The invite still works for the right one."
                    else if (!r.ok) problem = r.error
                }
            }.also { host = it }
            when (val made = h.inviteFor(id, addresses)) {
                is Outcome.Ok -> invite = ShownInvite(made.value.encode(), clockIn(WifiSync.INVITE_MINUTES.toLong()))
                is Outcome.Refused -> problem = made.reason
            }
        } catch (_: Exception) {
            problem = "This phone could not start showing the invite. Try again, or restart HouseMove."
        }
    }

    var invitedOnOpening by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(id) {
        if (showInvite && !invitedOnOpening && manage && !p.removed) {
            invitedOnOpening = true
            makeInvite()
        }
    }

    Screen(
        name, subtitle = "People",
        actions = { if (manage || isMe) TopAction(Icons.Default.Edit, "Edit name") { naming = !naming } },
    ) {
        ScrollColumn {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PersonOrb(p, size = 84.dp, me = isMe)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (data.shared() || p.removed) RoleChip(p.role, Modifier.align(Alignment.CenterVertically))
                    Box(Modifier.align(Alignment.CenterVertically)) { PersonStatus(p, isMe) }
                    if (p.letter.isNotBlank()) GlassChip("Letter ${p.letter}", Modifier.align(Alignment.CenterVertically))
                }
            }
            if (naming) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextInput(p.name, { v -> store.renamePerson(id, v).orSay(context) }, "Name", Modifier.weight(1f), key = id)
                    Spacer(Modifier.width(8.dp))
                    SpeakButton("What is their name?", contentDescription = "Say the name") { said ->
                        store.renamePerson(id, said.replaceFirstChar { it.uppercase() }).orSay(context)
                        naming = false
                    }
                }
            }

            if (joined) {
                Section(tone = GlassTone.Mint) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Night.MintPale, modifier = Modifier.size(32.dp))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("$name's phone has joined", style = MaterialTheme.typography.titleMedium)
                            Text("It is now $name, ${p.role.label}.", style = MaterialTheme.typography.bodyMedium)
                            if (joinedPlain.isNotEmpty()) Text(joinedPlain, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            problem?.let { Section(tone = GlassTone.Red) { Text(it) } }
            // A phone came with the invite: "Joined: Priya, on Priya's phone", Right or Undo, before anything is sent.
            val waiting by waitingOn(host)
            val request = waiting.firstOrNull()
            if (request != null) JoinRequestCard(request, p) { right -> host?.confirm(request.id, right) }
            else invite?.let { InviteCard(name, isMe, it) }

            if (!manage) {
                Text(p.role.line(), style = MaterialTheme.typography.bodyMedium, color = Night.TextSoft, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            } else if (p.removed) {
                Text(
                    "$name's phone can no longer sync with this move. What they did stays.",
                    style = MaterialTheme.typography.bodyMedium, color = Night.TextSoft, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                )
                BigAction(Icons.Default.PersonAdd, "Add $name back", onClick = { store.restorePerson(id).orSay(context) })
            } else {
                val waitingToJoin = p.joinedAt == 0L && !joined
                BigAction(
                    Icons.Default.QrCode2,
                    when {
                        invite != null -> "Make a new invite"
                        isMe -> "Move to a new phone"
                        waitingToJoin -> "Show $name's invite"
                        else -> "New phone for $name"
                    },
                    onClick = { makeInvite() }, primary = waitingToJoin && invite == null,
                )
                // With one person on the move there is no role to choose and nobody to remove.
                if (data.shared()) Section("Role") {
                    IconChoice(ROLE_CHOICES, p.role, { r -> store.setRole(id, r).orSay(context) }, columns = 3)
                    Text(p.role.line(), style = MaterialTheme.typography.bodySmall, color = Night.TextDim)
                }
                if (p.phones.isNotEmpty() && (data.shared() || p.phones.size > 1)) PhonesOf(p)
                // A Helper who has finished: "Done, thanks Sam".
                if (data.shared() && !isMe && p.role == app.housemove.data.Role.HELPER) ThanksHelper(p)
                // Nobody removes themselves by a slip of the finger: that is for another Organiser to do.
                if (data.shared() && !isMe) Fold("Remove from the move", Icons.Default.PersonRemove, summary = "Their phone can no longer sync", key = "remove-$id") {
                    Text("What $name did stays, and you can add them back.", style = MaterialTheme.typography.bodyMedium)
                    BigAction(
                        Icons.Default.PersonRemove, "Remove $name",
                        onClick = {
                            if (store.removePerson(id).orSay(context) != null) invite = null
                        },
                        primary = false,
                    )
                }
            }
        }
    }
}

/**
 * The phones someone uses in the move, each with its own name. Adding a phone never retires
 * another; one that is lost or replaced is retired here ("Retire Sam's old phone"): if it turns
 * up, its work comes in once, and nothing goes back to it. The phone in hand is not offered.
 */
@Composable
private fun PhonesOf(p: Person) {
    val store = LocalStore.current
    val context = LocalPlatform.current
    var retiring by remember { mutableStateOf<PhoneEntry?>(null) }
    val name = p.shownName()
    Section(if (p.phones.size == 1) "Phone" else "Phones") {
        p.phones.forEach { ph ->
            Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (ph.isRetired) Icons.Default.PhonelinkErase else Icons.Default.PhoneAndroid, contentDescription = null,
                    tint = if (ph.isRetired) Night.TextDim else Night.MintLight, modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(ph.name.ifBlank { "$name's phone" }, style = MaterialTheme.typography.bodyLarge, color = if (ph.isRetired) Night.TextDim else Night.Text)
                    Hint(
                        when {
                            ph.id == store.phone.id -> "This phone"
                            ph.isRetired -> "Retired" + if (ph.collected) ", its last work taken in" else ""
                            ph.addedAt > 0 -> "Joined " + fmtDay(ph.addedAt.floorDiv(86_400_000L))
                            else -> "In use"
                        },
                    )
                }
                if (!ph.isRetired && ph.id != store.phone.id) {
                    PillButton("Retire", onClick = { retiring = ph }, icon = Icons.Default.PhonelinkErase, primary = false, tone = GlassTone.Red)
                }
            }
        }
    }
    retiring?.let { ph ->
        ConfirmDialog(
            title = "Retire ${ph.name.ifBlank { "$name's phone" }}?",
            text = "For a phone that is lost or replaced. If it turns up, its work comes in once, and nothing goes back to it. $name's other phones carry on.",
            confirm = "Retire", danger = true,
            onConfirm = { store.retirePhone(p.id, ph.id).orSay(context) },
            onDismiss = { retiring = null },
        )
    }
}

/** The invite as a code to scan, saying plainly that it works once and for how long. */
@Composable
internal fun InviteCard(name: String, isMe: Boolean, invite: ShownInvite, inner: Boolean = false) {
    val qr = LocalPlatform.current.let { phone -> remember(invite.text) { phone.qrImage(invite.text, 640) } }
    val body: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (isMe) "Invite for your new phone" else "$name's invite", style = MaterialTheme.typography.titleMedium)
            Image(
                qr, contentDescription = "Invite code for $name",
                modifier = Modifier.size(232.dp).clip(RoundedCornerShape(20.dp)).background(Color.White).padding(10.dp)
                    .semantics { this[InviteText] = invite.text },
            )
            // The two things to know about it, each whole on any screen.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                GlassChip("Works once", icon = Icons.Default.LooksOne, tone = GlassTone.Amber)
                GlassChip("For ${WifiSync.INVITE_MINUTES} minutes, until ${invite.until}", icon = Icons.Default.Timer, tone = GlassTone.Amber)
            }
            Text(
                "On " + (if (isMe) "the new phone" else "$name's phone") + ": open HouseMove, tap Join a move and point it here.",
                style = MaterialTheme.typography.bodyMedium, color = Night.TextSoft, textAlign = TextAlign.Center,
            )
            Text(IPHONE_JOINS, style = MaterialTheme.typography.bodyMedium, color = Night.TextSoft, textAlign = TextAlign.Center)
        }
    }
    if (inner) body() else Section { body() }
}

// ---- who did what ---------------------------------------------------------------------------

private const val ACTIVITY_SHOWN = 100

/** The shared log, newest first, a line each. Tapping a line opens what it was about. */
@Composable
fun ActivityScreen() {
    val nav = LocalNav.current
    val data = appData()
    val manage = allowed(Action.MANAGE_PEOPLE)
    val log = remember(data.activity) { data.latestActivity(ACTIVITY_SHOWN) }
    Screen("Activity", subtitle = data.move.name.ifBlank { "Your move" }) {
        if (log.isEmpty()) {
            EmptyState(Icons.Default.History, "Nothing yet", text = "Sealing, loading, decisions and changes to people show up here.")
            return@Screen
        }
        val now = today()
        // One glass panel for each day. The log is short (the newest 100 lines), so a day is built whole.
        val days = remember(log) { log.groupBy { localDayOf(it.at) } }
        LazyColumn(
            Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(days.entries.toList(), key = { it.key }) { (day, lines) ->
                Section(
                    when (day) {
                        now -> "Today"
                        now - 1 -> "Yesterday"
                        else -> fmtDay(day)
                    },
                ) {
                    Column {
                        lines.forEach { a ->
                            val route = when {
                                a.refId == null -> null
                                data.item(a.refId) != null -> "item/${a.refId}"
                                data.box(a.refId) != null -> "box/${a.refId}"
                                data.proposal(a.refId) != null -> "approvals"
                                manage && data.person(a.refId) != null -> "person/${a.refId}"
                                else -> null
                            }
                            ActivityLine(
                                a, data, time = clockAt(a.at),
                                onClick = route?.let { r -> { nav.open(r) } },
                            )
                        }
                    }
                }
            }
        }
    }
}
