package app.housemove.ui.iphone

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.housemove.data.File
import app.housemove.data.InviteCheck
import app.housemove.data.Moves
import app.housemove.data.Outcome
import app.housemove.data.Role
import app.housemove.data.Store
import app.housemove.sync.AutoSync
import app.housemove.sync.MailboxSync
import app.housemove.sync.WifiSync
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Where joining has got to, on an iPhone that holds no move yet. */
sealed interface JoinStep {
    /** Waiting for an invite: from the Camera (a link), pasted or typed. [note] says why the last one was not taken. */
    data class Start(val note: String? = null) : JoinStep

    /** An invite was read: "Join Maple Road as Priya, Decider?" Nothing has been sent. [name] is asked for a Helper code. */
    data class Asking(val check: InviteCheck, val name: String = "") : JoinStep

    /** Yes was said. [who] is the person the invite is for, as the Android phone names them. */
    data class Working(val move: String, val stage: Stage, val who: String = "") : JoinStep

    /** It did not work. [retry] is the invite to try again with, when trying again may help. */
    data class Failed(val report: Report, val retry: InviteCheck? = null) : JoinStep

    enum class Stage {
        CONNECTING,

        /** The Android phone could not be reached: asking iOS whether HouseMove may use the local network. */
        CHECKING_WIFI,

        /** The Android phone's person is asked "Joined: Priya, on iPhone", Right or Undo. */
        WAITING_FOR_RIGHT,
    }
}

/** Getting fresh data, on the Get fresh data screen (FreshScreen). */
sealed interface Fresh {
    /** Nothing going on. [report] is how the last sync went, if one has. */
    data class Idle(val report: Report? = null) : Fresh

    /** This iPhone shows its sync code, for the Android phone to scan. [warning]: iOS keeps HouseMove off the local network. */
    data class Showing(val code: String, val report: Report? = null, val warning: Report? = null) : Fresh

    /** Syncing with the Android phone's code. */
    data object Syncing : Fresh
}

/**
 * What the iPhone does around the shared screens (step 2 of design/ios-plan.md): join a move that lives on an Android
 * phone ([Moves.join]), get fresh data from it ([WifiSync], syncing by itself, the internet mailbox), and leave it.
 * Once joined, the move is the shared app's (HouseMoveApp shows it), with the same rules as on Android for this
 * iPhone's person.
 *
 * [demo] is set only in demo mode (the launch argument `-HMDemoHost YES`): the invite of a sample move this app hosts
 * inside itself, shown on the join screen for the UI tests to type.
 */
class AppModel(
    val hooks: PhoneHooks,
    val buildLabel: String,
    private val scope: CoroutineScope,
    val demo: Demo? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** How long to wait for iOS's answer about the local network (the person may be reading its prompt). */
    private val localNetworkWaitMs: Long = 30_000,
) {
    /** A move this app hosts inside itself, in demo mode: its invite, and its sync code for getting fresh data. */
    class Demo(val inviteCode: String, val syncCode: String)

    var moves: Moves by mutableStateOf(openMoves())
        private set

    /** The move on this iPhone, or null while it has none (the join screens). */
    var store: Store? by mutableStateOf(moves.current.value)
        private set

    var join: JoinStep by mutableStateOf(JoinStep.Start())
        private set

    /** Goes up each time something asks for Get fresh data to be shown (a sync code or the mailbox's code from outside). */
    var freshAsked: Int by mutableStateOf(0)
        private set

    /** What the shared screens say for a few seconds at the bottom of the screen (Android's toasts). */
    val toasts = Toasts()

    var fresh: Fresh by mutableStateOf(Fresh.Idle())
        private set

    /** Said once after joining: who this iPhone is now. */
    var welcome: Report? by mutableStateOf(null)
        private set

    /** "Leave this move on this iPhone?" is being asked. */
    var askingToLeave: Boolean by mutableStateOf(false)
        private set

    /** Something is being done that must not be done twice (leaving). */
    var busy: Boolean by mutableStateOf(false)
        private set

    private var host: WifiSync.Host? = null

    private fun openMoves() = Moves(hooks.dataDir, hooks.noBackupDir, deviceName = { hooks.deviceName() })

    /**
     * Syncing by itself on the same Wi-Fi (design/auto-sync.md): the phones of the move find each other and sync while the
     * app is at the front ([foreground], [background]), with no code. Null where the phone cannot look for others.
     */
    val autoSync: AutoSync? = hooks.discovery()?.let { d ->
        AutoSync(d, { moves.current.value }, { moves.phone.settings.value.autoSync }, loopbackOnly = hooks.loopbackOnly).also { engine ->
            // A sync by itself may have let go of a Helper's copy: the screens follow the move.
            engine.onSynced = { _, _ -> scope.launch { followMove() } }
            engine.resume()
        }
    }

    /** "Sync by itself on Wi-Fi", as the phone's settings say. */
    var autoSyncOn: Boolean by mutableStateOf(moves.phone.settings.value.autoSync)
        private set

    fun setAutoSync(on: Boolean) {
        moves.phone.updateSettings { it.copy(autoSync = on) }
        autoSyncOn = on
        autoSync?.poke()
    }

    /**
     * Syncing through the move's internet mailbox (design/encryption.md, "Mailbox as built"), beside syncing by itself: the
     * phones of the move that are not on the same Wi-Fi keep each other up to date through the family laptop, while the app
     * is at the front. Null where the phone does not (demo mode, the desktop tests).
     */
    val mailbox: MailboxSync? = if (!hooks.mailbox) null else MailboxSync({ moves.current.value }, { moves.phone.settings.value.mailbox }).also { engine ->
        engine.onTookIn = { _, _ -> scope.launch { followMove() } }
        engine.resume()
    }

    /** "Internet mailbox on this iPhone", as the phone's settings say. */
    var mailboxOn: Boolean by mutableStateOf(moves.phone.settings.value.mailbox)
        private set

    fun setMailbox(on: Boolean) {
        moves.phone.updateSettings { it.copy(mailbox = on) }
        mailboxOn = on
        mailbox?.poke()
    }

    /** The app came to the front: syncing by itself starts again, and the mailbox is read. */
    fun foreground() {
        autoSync?.resume()
        mailbox?.resume()
    }

    /** The app left the front: iOS lets it neither listen nor sync, so syncing by itself stops until it is back. */
    fun background() {
        autoSync?.pause()
        mailbox?.pause()
    }

    /**
     * The internet mailbox's code (HMBOX:…, from the Camera's "Open in HouseMove" or pasted), on this iPhone's move: an
     * Organiser sets the move's mailbox up; anyone keeps its code for the mailbox the move already has.
     */
    private fun setUpMailbox(s: Store, text: String) {
        freshAsked++
        val (ok, said) = MailboxSync.setUp(s, text)
        fresh = Fresh.Idle(Report(ok, if (ok) "Internet mailbox" else "Not set up", said))
        if (ok) mailbox?.poke()
    }

    /** What Get fresh data's mailbox scanner read (FreshScans.kt): set up with it, or said why not, as for a pasted code. */
    fun useMailboxCode(text: String) {
        store?.let { setUpMailbox(it, text) }
    }

    // ---- codes from outside: a link from the Camera, a paste, typing ---------------------------------------------

    /**
     * An invite or a sync code, as the person gave it. With no move yet, an invite is read and asked about ("Join
     * Maple Road as Priya, Decider?"); in a move, a sync code gets fresh data. Anything else is said in words.
     */
    fun use(text: String) {
        val s = store
        // The internet mailbox's code, for a phone in a move.
        if (s != null && MailboxSync.isCode(text)) {
            setUpMailbox(s, text)
            return
        }
        val code = Words.cleanCode(text)
        val invite = WifiSync.Invite.parse(code)
        if (s == null) {
            if (join is JoinStep.Working) return
            join = when {
                invite == null -> JoinStep.Start(if (code.isEmpty()) null else Words.NOT_AN_INVITE)
                invite.personId == null -> JoinStep.Start(Words.SYNC_CODE_NOT_INVITE)
                else -> moves.readInvite(code)?.let { JoinStep.Asking(it, moves.phone.settings.value.name) } ?: JoinStep.Start(Words.NOT_AN_INVITE)
            }
            return
        }
        if (fresh is Fresh.Syncing) return
        freshAsked++
        when {
            invite == null -> fresh = Fresh.Idle(Report(false, "That is not a sync code", "On the Android phone: More, Sync and backup, Show sync code. Use that code."))
            invite.personId != null -> fresh = Fresh.Idle(
                Report(
                    false, "That is an invite",
                    "This iPhone is already in ${s.data.value.move.name.ifBlank { "a move" }}. To get fresh data, use a sync code: on the Android phone, More, Sync and backup, Show sync code.",
                ),
            )
            else -> syncWith(invite)
        }
    }

    /** The text on the clipboard, or null when there is none. */
    fun paste(): String? = hooks.paste()?.takeIf { it.isNotBlank() }

    fun noteNothingToPaste() {
        if (store == null) join = JoinStep.Start(Words.NOTHING_TO_PASTE)
        else fresh = Fresh.Idle(Report(false, "Nothing to paste", Words.NOTHING_TO_PASTE))
    }

    // ---- joining -------------------------------------------------------------------------------------------------

    fun setName(name: String) {
        (join as? JoinStep.Asking)?.let { join = it.copy(name = name) }
    }

    /** "Not now" on the question: back to the start, nothing sent. */
    fun notNow() {
        join = JoinStep.Start("Nothing was sent. Use the right invite when you have it.")
    }

    /** "Use another invite" after a problem. */
    fun startAgain() {
        join = JoinStep.Start()
    }

    /** "Try again" after a problem: asked again, with the same invite. */
    fun retry() {
        val failed = join as? JoinStep.Failed ?: return
        val check = failed.retry ?: return startAgain()
        join = JoinStep.Asking(check, moves.phone.settings.value.name)
    }

    /** Yes: join the move of the invite being asked about. */
    fun yes() {
        val asking = join as? JoinStep.Asking ?: return
        val check = asking.check
        if (check.invite.helperCode && asking.name.isNotBlank()) moves.phone.updateSettings { it.copy(name = asking.name.trim()) }
        val move = check.invite.moveName.ifBlank { "the move" }
        val who = check.invite.personName
        join = JoinStep.Working(move, JoinStep.Stage.CONNECTING, who)
        scope.launch {
            val onWaiting = { scope.launch { if (join is JoinStep.Working) join = JoinStep.Working(move, JoinStep.Stage.WAITING_FOR_RIGHT, who) }; Unit }
            val attempt: suspend () -> WifiSync.Result = {
                try {
                    withContext(io) { moves.join(check, onWaiting = onWaiting) }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    WifiSync.Result("", "", 0, 0, WifiSync.CONNECTION_LOST)
                }
            }
            // The screen stays awake while the move and its photos come across, and may sleep again before anything is said.
            hooks.keepAwake(true)
            val r = try {
                withLocalNetworkCheck(check.invite, attempt) { stage -> join = JoinStep.Working(move, stage, who) }
            } finally {
                hooks.keepAwake(false)
            }
            val joined = r.result.joinedAs != null
            if (!joined) {
                val report = r.report ?: Words.joinProblem(r.result.error, r.result.shareApp)
                // A used or refused invite will not work again; anything else (the Wi-Fi, a time-out, Undo) may.
                val dead = r.result.error in setOf(WifiSync.INVITE_EXPIRED, WifiSync.CODE_NOT_ACCEPTED, WifiSync.REMOVED_SELF, WifiSync.WRONG_MOVE)
                join = JoinStep.Failed(report, if (dead) null else check)
                return@launch
            }
            val s = moves.current.value
            store = s
            fresh = Fresh.Idle()
            join = JoinStep.Start()
            if (s != null) {
                s.setDevice { it.copy(lastSyncAt = Words.nowMillis(), lastSyncWith = r.result.peerName) }
                val who = r.result.joinedAs ?: s.me
                val role = who?.role ?: s.myRole
                val name = s.data.value.move.name.ifBlank { "the move" }
                welcome = Report(
                    true,
                    if (who != null && who.name.isNotBlank()) "You are ${who.name}, ${role.label}, in $name" else "You are in $name",
                    Words.roleLine(role) + " " + Words.LATER + Words.plainNote(r.result),
                )
            }
        }
    }

    fun welcomeRead() {
        welcome = null
    }

    /** What came of a try, and the words to say when it was the local network. */
    private class Tried(val result: WifiSync.Result, val report: Report? = null)

    /**
     * Try [attempt]. When it could not reach the other phone over the Wi-Fi, ask iOS about the local network: switched
     * off, say so; just allowed (its prompt came up as the first try went out), try once more; otherwise say the
     * phones could not meet.
     */
    private suspend fun withLocalNetworkCheck(
        invite: WifiSync.Invite,
        attempt: suspend () -> WifiSync.Result,
        onStage: (JoinStep.Stage) -> Unit,
    ): Tried {
        val first = attempt()
        if (first.error != WifiSync.UNREACHABLE || !overTheWifi(invite)) return Tried(first)
        onStage(JoinStep.Stage.CHECKING_WIFI)
        return when (localNetwork()) {
            LocalNetwork.DENIED -> Tried(first, Words.DENIED)
            LocalNetwork.ALLOWED -> {
                onStage(JoinStep.Stage.CONNECTING)
                val again = attempt()
                Tried(again, if (again.error == WifiSync.UNREACHABLE) Words.unreachable(couldBeDenied = false) else null)
            }
            LocalNetwork.UNKNOWN -> Tried(first, Words.unreachable(couldBeDenied = true))
        }
    }

    private fun overTheWifi(invite: WifiSync.Invite) = invite.hosts.any { !it.startsWith("127.") && it != "localhost" }

    /** iOS's answer about the local network, or UNKNOWN when it does not come in time. */
    private suspend fun localNetwork(): LocalNetwork {
        val answer = CompletableDeferred<LocalNetwork>()
        try {
            hooks.checkLocalNetwork { answer.complete(it) }
        } catch (_: Exception) {
            return LocalNetwork.UNKNOWN
        }
        return withTimeoutOrNull(localNetworkWaitMs) { answer.await() } ?: LocalNetwork.UNKNOWN
    }

    fun openSettings() = hooks.openSettings()

    // ---- getting fresh data --------------------------------------------------------------------------------------

    /** Show this iPhone's sync code, for the Android phone to scan (More, Sync and backup, Scan sync code). */
    fun showCode() {
        val s = store ?: return
        if (host != null) return
        val addresses = try {
            WifiSync.addresses()
        } catch (_: Exception) {
            emptyList()
        }
        if (addresses.isEmpty()) {
            fresh = Fresh.Idle(Words.NO_WIFI)
            return
        }
        val h = try {
            // Only phones already in the move sync with it: the iPhone lets nobody new in.
            WifiSync.host(s, letNewPhonesIn = false) { r -> scope.launch { hosted(r) } }
        } catch (_: Exception) {
            fresh = Fresh.Idle(Report(false, "Could not show the code", "This iPhone could not start showing its code. Try again, or close HouseMove and open it again."))
            return
        }
        host = h
        hooks.keepAwake(true)
        fresh = Fresh.Showing(h.invite(addresses).encode())
        scope.launch {
            if (localNetwork() == LocalNetwork.DENIED) (fresh as? Fresh.Showing)?.let { fresh = it.copy(warning = Words.DENIED) }
        }
    }

    private fun hosted(r: WifiSync.Result) {
        val synced = r.ok || r.error == WifiSync.PHOTOS_CUT
        if (synced) afterSync(r.peerName)
        val report = if (synced) Words.syncResult(r) else Report(false, "Could not sync", r.error ?: "Something went wrong. Try again.")
        val showing = fresh as? Fresh.Showing
        fresh = if (showing != null && store != null) showing.copy(report = report) else Fresh.Idle(report)
    }

    fun stopCode() {
        host?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
        }
        if (host != null) hooks.keepAwake(false)
        host = null
        if (fresh is Fresh.Showing) fresh = Fresh.Idle((fresh as Fresh.Showing).report)
    }

    /** Sync with the Android phone whose sync code this is. */
    private fun syncWith(invite: WifiSync.Invite) {
        val s = store ?: return
        stopCode()
        fresh = Fresh.Syncing
        scope.launch {
            val attempt: suspend () -> WifiSync.Result = {
                try {
                    withContext(io) { WifiSync.join(s, invite, letNewPhonesIn = false) }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    WifiSync.Result("", "", 0, 0, WifiSync.CONNECTION_LOST)
                }
            }
            // The screen stays awake while the sync runs (many photos take a while).
            hooks.keepAwake(true)
            val r = try {
                withLocalNetworkCheck(invite, attempt) {}
            } finally {
                hooks.keepAwake(false)
            }
            val synced = r.result.ok || r.result.error == WifiSync.PHOTOS_CUT
            if (synced) afterSync(r.result.peerName)
            fresh = Fresh.Idle(r.report ?: Words.syncResult(r.result))
        }
    }

    /** A sync went both ways: note when, and follow the move (a Helper's copy may have been let go of). */
    private fun afterSync(peer: String) {
        store?.setDevice { it.copy(lastSyncAt = Words.nowMillis(), lastSyncWith = peer) }
        followMove()
    }

    /** The move in use may have changed (a Helper's copy let go of after a sync): the screens follow it. */
    private fun followMove() {
        val now = moves.current.value
        if (now !== store) {
            stopCode()
            store = now
            if (now == null) {
                join = JoinStep.Start(moves.farewells.value.lastOrNull())
                moves.farewellsSaid()
            }
        }
    }

    // ---- leaving -------------------------------------------------------------------------------------------------

    fun askToLeave(asking: Boolean) {
        askingToLeave = asking
    }

    /** Take the move off this iPhone. The other phones keep it; nothing is sent to them. */
    fun leave() {
        val s = store ?: return
        if (busy) return
        busy = true
        askingToLeave = false
        stopCode()
        val name = s.data.value.move.name.ifBlank { "The move" }
        scope.launch {
            withContext(io) { forget(s) }?.let { moves = it }
            store = moves.current.value
            fresh = Fresh.Idle()
            welcome = null
            join = JoinStep.Start(if (store == null) "$name was taken off this iPhone. The Android phone still has it." else null)
            busy = false
        }
    }

    /**
     * Moves.remove takes a move off the phone, unless this phone's person is its only Organiser while others are in
     * it (the move must not depend on one phone). This iPhone never changes the move, and that person has the Android
     * phone too, so then the iPhone lets go of its copy itself: the move's folders, as Moves keeps them (moves/,
     * photos/ and the codes in noBackupDir/moves/, each under the move's folder name), and the list of moves is read
     * again, which forgets a move whose folder is gone. Returns the list read again, or null when Moves.remove did it.
     */
    private fun forget(s: Store): Moves? {
        val id = s.moveId
        val folder = moves.cards.value.firstOrNull { it.matches(id) }?.folder
        if (moves.remove(id, evenIfUnshared = true) is Outcome.Ok || folder == null) return null
        try {
            s.close()
        } catch (_: Exception) {
        }
        hooks.deleteFolder(File(File(hooks.dataDir, "moves"), folder))
        hooks.deleteFolder(File(File(hooks.dataDir, "photos"), folder))
        hooks.deleteFolder(File(File(hooks.noBackupDir, "moves"), folder))
        return openMoves()
    }

    /** The role this iPhone has in its move, for the screens. */
    val role: Role? get() = store?.me?.role

    // ---- for the screens' pictures (desktopTest) ----------------------------------------------------------------

    internal fun showForPicture(step: JoinStep) {
        join = step
    }

    internal fun showForPicture(state: Fresh) {
        fresh = state
    }
}
