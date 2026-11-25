package app.housemove.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role as SemanticsRole
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.housemove.data.*
import app.housemove.sync.WifiSync
import app.housemove.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---- first open ------------------------------------------------------------------------------

/**
 * What a phone with no move opens on: two large buttons, "Start a new move" and "Join a move",
 * and under Join a small way back for someone whose phone was lost.
 */
@Composable
fun WelcomeScreen() {
    val moves = LocalMoves.current
    val nav = LocalNav.current
    val roomy = LocalConfiguration.current.let { it.screenHeightDp / it.fontScale >= 700f }
    // No top bar: the app's mark and name lead, over the lit house.
    Frame(tab = null, topBar = { Spacer(Modifier.statusBarsPadding().height(12.dp)) }) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        ) {
            Row(Modifier.semantics(mergeDescendants = true) { heading() }, verticalAlignment = Alignment.CenterVertically) {
                AppMark(Modifier.size(40.dp), description = null)
                Spacer(Modifier.width(10.dp))
                Text("HouseMove", style = MaterialTheme.typography.headlineMedium)
            }
            if (roomy) IsoHouse(Modifier.widthIn(max = 320.dp).fillMaxWidth(), boxes = 2)
            Text(
                "Plan, pack and move together, phone to phone.", style = MaterialTheme.typography.titleMedium,
                color = Night.TextSoft, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
        }
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BigAction(
                Icons.Default.Add, "Start a new move",
                // A phone with moves starts one in the list of moves; this screen shown on its own goes to the wizard of the move it has.
                onClick = { if (moves != null) moves.create() else nav.open("setup") },
            )
            BigAction(Icons.Default.QrCodeScanner, "Join a move", onClick = { nav.open("join") }, primary = false)
            Text(
                "Lost your phone?", style = MaterialTheme.typography.labelLarge, color = Night.MintPale, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.CenterHorizontally).clip(RoundedCornerShape(14.dp))
                    .clickable(role = SemanticsRole.Button) { nav.open("lost") }.heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 14.dp),
            )
        }
    }
}

// ---- joining ---------------------------------------------------------------------------------

/** Where joining has got to. */
private sealed interface JoinStep {
    data object Scanning : JoinStep

    /** An invite was read: "Join Maple Road as Priya, Decider?" Nothing has been sent. */
    class Asking(val invite: WifiSync.Invite, val question: String, val check: InviteCheck?) : JoinStep

    /** Yes was said: connecting, or waiting for the other phone's person to say this is the right phone. */
    class Working(val move: String) : JoinStep {
        var waiting by mutableStateOf(false)
    }

    class Failed(val report: SyncReport) : JoinStep
}

/**
 * Join a move: the camera opens at once; the invite says which move and who ("Join Maple Road
 * as Priya, Decider?") and nothing is sent before Yes. A phone with a move of its own with
 * things in it is offered to bring them along (No unless chosen). Once in, Home says who the
 * phone now is, in a banner. Every way it can go wrong is said in plain words; when the other
 * phone has an older HouseMove, this one offers to share itself.
 *
 * [inMove] is false on a phone with no move yet (the first-open screens). Shown with a single
 * move on its own (tests), the invite is joined by that move.
 */
@Composable
fun JoinScreen(inMove: Boolean) {
    val moves = LocalMoves.current
    val single = if (moves == null) LocalStore.current else null
    val here = if (inMove) LocalStore.current else null
    val nav = LocalNav.current
    val context = LocalContext.current
    val state = appState()
    var step by remember { mutableStateOf<JoinStep>(JoinStep.Scanning) }
    var note by remember { mutableStateOf<String?>(null) }
    val showing = remember { booleanArrayOf(true) }
    DisposableEffect(Unit) { onDispose { showing[0] = false } }

    fun goHome() {
        if (showing[0] && inMove && (moves == null || moves.current.value === here)) {
            nav.navigate("home") { popUpTo(0) { inclusive = true } }
        }
    }

    /** Read a scanned code: false when it is not an invite. */
    fun read(text: String): Boolean {
        val invite = WifiSync.Invite.parse(text) ?: return false
        val check = moves?.readInvite(text)
        note = null
        step = JoinStep.Asking(invite, check?.question ?: invite.question(), check)
        return true
    }
    LaunchedEffect(Unit) { state.pendingInvite?.let { state.pendingInvite = null; read(it) } }

    // A move shown on its own that is set up meanwhile (its data came from another phone) has been joined: Home.
    if (single != null) {
        val setUp = single.data.collectAsState().value.move.setupDone
        val wasSetUp = remember { setUp }
        LaunchedEffect(setUp) { if (setUp && !wasSetUp && step is JoinStep.Scanning) goHome() }
    }

    fun yes(a: JoinStep.Asking, bring: Boolean) {
        val working = JoinStep.Working(a.invite.moveName.ifBlank { "the move" })
        step = working
        // On the main thread whatever the scope resumes on: the end of it opens Home.
        state.scope.launch(Dispatchers.Main) {
            val r = try {
                withContext(Dispatchers.IO) {
                    val waited = { working.waiting = true }
                    if (moves != null && a.check != null) moves.join(a.check, bringThings = bring, onWaiting = waited)
                    else WifiSync.join(single!!, a.invite, letNewPhonesIn = single.isAlone(), onWaiting = waited)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // Whatever went wrong, the screen says so and can scan again, rather than wait for ever.
                WifiSync.Result("", "", 0, 0, WifiSync.CONNECTION_LOST)
            }
            val joined = r.joinedAs != null || r.ok
            if (!joined) {
                step = JoinStep.Failed(syncReport(r, single ?: here))
                return@launch
            }
            // The move this phone is now in: the one it joined (Moves opened it), or the one shown on its own.
            val s = moves?.current?.value ?: single!!
            s.setDevice { it.copy(lastSyncAt = System.currentTimeMillis(), lastSyncWith = r.peerName) }
            val d = s.data.value
            val who = r.joinedAs ?: s.me
            val role = who?.role ?: s.myRole
            val move = d.move.name.ifBlank { "the move" }
            state.banner = HomeBanner(
                s.moveId,
                title = if (who != null && who.name.isNotBlank()) "You are ${who.name}, ${role.label}, in $move" else "You are in $move",
                text = role.line(),
                personId = who?.id.orEmpty(),
            )
            goHome()
        }
    }

    when (val st = step) {
        JoinStep.Scanning -> Screen("Join a move", back = nav.previousBackStackEntry != null) {
            QrScanner(
                onText = { if (step is JoinStep.Scanning && !read(it)) note = NOT_AN_INVITE },
                modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp).scannerFrame(),
            )
            note?.let {
                Text(
                    it, style = MaterialTheme.typography.bodyMedium, color = Night.AmberPale,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            TypedCode(
                hint = "Scan the invite on the other phone: People, then your name.",
                onCode = { text ->
                    if (!read(text)) {
                        note = NOT_AN_INVITE
                        toast(context, NOT_AN_INVITE)
                    }
                },
            )
        }
        is JoinStep.Asking -> AskToJoin(st, onNo = {
            note = "Nothing was sent. Scan the right invite when you have it."
            step = JoinStep.Scanning
        }, onYes = { bring -> yes(st, bring) })
        is JoinStep.Working -> Screen("Join a move", back = false) {
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            ) {
                CircularProgressIndicator(color = Night.Mint)
                Text(
                    if (st.waiting) "Waiting for the other phone" else "Joining ${st.move}…",
                    style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                if (st.waiting) {
                    Text(
                        "Their screen shows this phone's name. Once they tap Right, the move comes across.",
                        style = MaterialTheme.typography.bodyMedium, color = Night.TextSoft, textAlign = TextAlign.Center,
                    )
                }
            }
        }
        is JoinStep.Failed -> Screen("Join a move", back = nav.previousBackStackEntry != null) {
            ScrollColumn(Modifier.weight(1f)) { SyncReportCard(st.report) }
            BigAction(Icons.Default.QrCodeScanner, "Scan again", onClick = { note = null; step = JoinStep.Scanning }, modifier = Modifier.padding(16.dp))
        }
    }
}

private const val NOT_AN_INVITE = "That is not an invite. On the other phone open People and show your invite."

/**
 * The typed way round the camera, for when there is no other: behind a "Type the code" chip,
 * so the camera has the screen to itself. [hint] says what to point the camera at.
 */
@Composable
private fun TypedCode(hint: String, onCode: (String) -> Unit) {
    var typing by rememberSaveable { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = if (typing) 4.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Hint(hint, Modifier.weight(1f))
        GlassChip("Type the code", icon = Icons.Default.Keyboard, selected = typing, onClick = { typing = !typing })
    }
    if (typing) {
        Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                colors = glassFieldColors(), value = typed, onValueChange = { typed = it },
                label = { Text("Or type the code") }, singleLine = true, modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            PillButton("OK", onClick = { onCode(typed.trim()); typed = "" }, enabled = typed.isNotBlank())
        }
    }
}

/**
 * "Join Maple Road as Priya, Decider?" with Yes and Not me, before anything is sent. A Helper
 * code asks for nobody in particular: the phone gives the name it knows its person by, which
 * can be said here when it has none yet.
 */
@Composable
private fun AskToJoin(a: JoinStep.Asking, onNo: () -> Unit, onYes: (bring: Boolean) -> Unit) {
    val moves = LocalMoves.current
    val single = if (moves == null) LocalStore.current else null
    val invite = a.invite
    val forSomeone = invite.personId != null && !invite.helperCode
    var bring by rememberSaveable { mutableStateOf(false) }
    val move = invite.moveName.ifBlank { "the move" }
    Screen("Join a move", back = false) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
        ) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    forSomeone -> PersonOrb(Person(id = "", name = invite.personName), size = 96.dp)
                    else -> GlassOrb(if (invite.helperCode) Role.HELPER.icon() else Icons.Default.Sync, null, size = 96.dp)
                }
                Text(
                    a.question, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { heading() },
                )
                invite.role?.takeIf { invite.personId != null }?.let { role ->
                    Text(role.line(), style = MaterialTheme.typography.bodyMedium, color = Night.TextSoft, textAlign = TextAlign.Center)
                }
            }
            // A Helper code: the phone joins under the name it knows its person by.
            if (invite.helperCode) {
                val known = moves?.phone?.settings?.collectAsState()?.value?.name ?: single?.device?.collectAsState()?.value?.name.orEmpty()
                YourName(known) { said ->
                    if (moves != null) moves.phone.updateSettings { it.copy(name = said) } else single?.setDevice { it.copy(name = said) }
                }
            }
            a.check?.bringOffer?.let { n ->
                GlassCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
                        SwitchRow(
                            "Bring your ${countOf(n, "thing")} into $move", bring, { bring = it },
                            sub = "Your own move stays on this phone, under Earlier moves.",
                        )
                    }
                }
            }
            Hint("Nothing is sent until you say Yes.", Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally))
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigAction(
                if (forSomeone) Icons.Default.PersonOff else Icons.AutoMirrored.Filled.ArrowBack, if (forSomeone) "Not me" else "Cancel",
                onClick = onNo, modifier = Modifier.weight(1f), primary = false,
            )
            BigAction(Icons.Default.Check, "Yes", onClick = { onYes(bring) }, modifier = Modifier.weight(1f))
        }
    }
}

/** The name this phone's person goes by, said or typed; offered when a Helper code is about to give it. */
@Composable
private fun YourName(name: String, onName: (String) -> Unit) {
    var typing by rememberSaveable { mutableStateOf(false) }
    var spoken by remember { mutableIntStateOf(0) }
    GlassCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(26.dp), sheen = true) {
        Row(Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            PersonOrb(Person(id = "", name = name), size = 48.dp, me = true)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(name.ifBlank { "Your name" }, style = MaterialTheme.typography.titleMedium)
                Text("So the others know who did what", style = MaterialTheme.typography.bodySmall, color = Night.TextDim)
            }
            SpeakButton("What is your name?", contentDescription = "Say your name") { said ->
                onName(said.replaceFirstChar { it.uppercase() })
                spoken++
            }
            Spacer(Modifier.width(4.dp))
            GlassOrb(Icons.Default.Edit, "Type your name") { typing = !typing }
        }
    }
    if (typing) TextInput(name, { onName(it) }, "Your name", key = "joining-name-$spoken")
}

// ---- a lost phone ------------------------------------------------------------------------------

/**
 * Lost your phone? Restore the move from a backup file. The phone becomes the person whose
 * phone made it (a Helper if that person is gone). A move another Organiser runs can let the
 * new phone in instead, with an invite.
 */
@Composable
fun LostPhoneScreen(inMove: Boolean) {
    val moves = LocalMoves.current
    val single = if (moves == null) LocalStore.current else null
    val here = if (inMove) LocalStore.current else null
    val nav = LocalNav.current
    val context = LocalContext.current
    val state = appState()
    var working by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val showing = remember { booleanArrayOf(true) }
    DisposableEffect(Unit) { onDispose { showing[0] = false } }

    // A backup file chosen on its own, or the daily backup from the folder it is saved to: [read] opens it (twice).
    fun restore(read: () -> java.io.InputStream) {
        working = true
        problem = null
        state.scope.launch(Dispatchers.Main) {
            val outcome = withContext(Dispatchers.IO) {
                if (moves != null) moves.restore(read)
                else try {
                    Outcome.Ok(read().use { Backup.import(single!!, it) })
                } catch (e: Exception) {
                    Outcome.Refused((e as? IllegalArgumentException)?.message ?: Backup.NOT_A_BACKUP)
                }
            }
            working = false
            when (outcome) {
                is Outcome.Refused -> problem = outcome.reason
                is Outcome.Ok -> {
                    val s = moves?.current?.value ?: single!!
                    val who = s.me
                    val move = s.data.value.move.name.ifBlank { "your move" }
                    state.banner = HomeBanner(
                        s.moveId, "Restored $move",
                        if (who != null && who.name.isNotBlank()) "You are ${who.name}, ${who.role.label}. " + who.role.line() else s.myRole.line(),
                        who?.id.orEmpty(),
                    )
                    if (showing[0] && inMove && (moves == null || moves.current.value === here)) nav.navigate("home") { popUpTo(0) { inclusive = true } }
                }
            }
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        restore { context.contentResolver.openInputStream(uri) ?: throw IllegalArgumentException(Backup.NOT_A_BACKUP) }
    }

    Screen("Lost your phone?") {
        if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
        ScrollColumn {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.Center) {
                GlassOrb(Icons.Default.Restore, null, size = 88.dp)
            }
            Text(
                "Bring your move back from its daily backup, or from a backup file you saved or shared from HouseMove.",
                style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
            // The folder the daily small backup went to (ApartParts): its newest backup of each move.
            DailyBackupChooser(working, onRestore = { read -> restore(read) })
            BigAction(
                Icons.Default.FolderOpen, "Choose a backup file", enabled = !working, primary = false,
                onClick = { open.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
            )
            problem?.let { SyncReportCard(SyncReport(false, "Could not restore that file", it)) }
            Section {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GlassOrb(Icons.Default.Groups, null, size = 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("No backup?", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "If someone else runs the move with you, they can let this phone in: on their phone, People, your name, New phone. Then tap Join a move here.",
                            style = MaterialTheme.typography.bodyMedium, color = Night.TextSoft,
                        )
                    }
                }
                PillButton("Join a move", onClick = { nav.open("join") }, icon = Icons.Default.QrCodeScanner, primary = false, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
