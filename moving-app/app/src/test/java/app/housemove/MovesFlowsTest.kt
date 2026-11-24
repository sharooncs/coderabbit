package app.housemove

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.core.app.ActivityOptionsCompat
import androidx.navigation.NavHostController
import app.housemove.data.*
import app.housemove.sync.WifiSync
import app.housemove.ui.AppRoot
import app.housemove.ui.InviteText
import app.housemove.ui.LocalSpeech
import app.housemove.ui.LocalSyncAddresses
import app.housemove.ui.Speech
import app.housemove.ui.installedApk
import app.housemove.ui.shareSheet
import app.housemove.ui.line
import app.housemove.ui.screens.APP_DOWNLOAD_NOTE
import app.housemove.ui.screens.APP_DOWNLOAD_URL
import app.housemove.ui.screens.SHOW_DOWNLOAD_CODE
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Several moves on one phone, driven through the real screens of the app as a phone opens it
 * ([AppRoot] for the phone's moves): the first open, starting a move and inviting its people,
 * joining one (Yes, Not me, and the Organiser's Right or Undo), the Helper code, switching
 * between moves, removing and deleting one, a label held by two moves, a lost phone, and a
 * phone on another version. Phones meet over a real connection (localhost); a code is typed
 * where a person would scan it, and words are "spoken" through a stand-in for speech.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class MovesFlowsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @get:Rule
    val rule = createComposeRule()

    private val local = listOf("127.0.0.1")
    private var count = 0

    /** The phone whose screen is showing. */
    private var shown by mutableStateOf<Moves?>(null)
    private var started = false
    private var nav: NavHostController? = null

    /** What the stand-in for speech hears next. */
    private var said = ""

    /** The files the stand-in for the phone's file picker hands back: where a backup is saved, and which one is opened. */
    private var savedTo: File? = null
    private var opened: File? = null

    private val hosts = ArrayList<WifiSync.Host>()

    @Before
    fun quick() {
        WifiSync.confirmMs = 20_000
    }

    @After
    fun hangUp() {
        hosts.forEach { it.stop() }
        WifiSync.confirmMs = 90_000
        installedApk = { File(it.applicationInfo.sourceDir) }
        shareSheet = { context, file, type -> app.housemove.pdf.Share.send(context, file, type) }
    }

    /** A phone: its own folders and the name it has in its own settings. */
    private fun phone(device: String, yourName: String = ""): Moves {
        val root = tmp.newFolder("phone" + count++)
        return Moves(File(root, "files"), File(root, "no_backup"), { device }).also { m ->
            if (yourName.isNotEmpty()) m.phone.updateSettings { it.copy(name = yourName) }
        }
    }

    /** A move on [m] that is set up, with a room in each home. */
    private fun move(m: Moves, name: String, yourName: String = "Asha"): Store {
        val s = m.create(name, yourName = yourName)
        s.setMove { it.copy(moveDay = today() + 10, setupDone = true) }
        val dest = s.createRoom(Home.DEST, "Kitchen")
        s.put(s.createRoom(Home.ORIGIN, "Kitchen").copy(mapsTo = dest.id))
        return s
    }

    private fun hosting(store: Store): WifiSync.Host = WifiSync.host(store, letNewPhonesIn = false, confirmJoins = true) {}.also { hosts += it }

    /** [phone] joins with [text] on a thread of its own (it waits there for Right); the result arrives in the queue. */
    private fun joinBehind(phone: Moves, text: String): LinkedBlockingQueue<WifiSync.Result> {
        val q = LinkedBlockingQueue<WifiSync.Result>()
        val check = phone.readInvite(text)!!
        thread(isDaemon = true) { q.add(phone.join(check)) }
        return q
    }

    /** [joiner] joins [host]'s move as a new [role] by an invite, confirmed on the host as an Organiser would. */
    private fun joined(joiner: Moves, host: Store, name: String, role: Role): Store {
        val p = host.addPerson(name, role).valueOrNull()!!
        val h = hosting(host)
        val q = joinBehind(joiner, h.inviteFor(p.id, local).valueOrNull()!!.encode())
        if (role != Role.HELPER) {
            val until = System.currentTimeMillis() + 10_000
            while (h.waiting.value.isEmpty() && System.currentTimeMillis() < until) Thread.sleep(5)
            h.confirm(h.waiting.value.first().id, true)
        }
        val r = q.poll(20, TimeUnit.SECONDS)!!
        assertTrue(r.error, r.ok)
        return joiner.current.value!!
    }

    /** A stand-in for the phone's file picker: saving and opening go to [savedTo] and [opened]; the camera is not allowed. */
    private val files = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                val result: Any? = when (contract) {
                    is ActivityResultContracts.CreateDocument -> savedTo?.let { Uri.fromFile(it) }
                    is ActivityResultContracts.OpenDocument -> opened?.let { Uri.fromFile(it) }
                    is ActivityResultContracts.RequestPermission -> false
                    else -> null
                }
                dispatchResult(requestCode, result)
            }
        }
    }

    /** Put [m] on the screen, as the app opens on that phone. */
    private fun show(m: Moves) {
        if (!started) {
            started = true
            rule.setContent {
                CompositionLocalProvider(
                    LocalSpeech provides Speech { _, onText -> onText(said) }, LocalSyncAddresses provides { local },
                    LocalActivityResultRegistryOwner provides files,
                ) {
                    shown?.let { phone -> key(phone) { AppRoot(phone, onNav = { nav = it }) } }
                }
            }
        }
        rule.runOnUiThread { shown = m }
        settle()
    }

    private fun settle() = rule.waitForIdle()

    private fun go(route: String) {
        rule.runOnUiThread { nav!!.navigate(route) }
        settle()
    }

    private fun route(): String? {
        var r: String? = null
        rule.runOnUiThread { r = nav!!.currentBackStackEntry?.destination?.route }
        return r
    }

    private fun click(node: SemanticsNodeInteraction) {
        try {
            node.performScrollTo()
        } catch (_: AssertionError) {
            // not inside a scrolling container
        }
        node.performClick()
        settle()
    }

    private fun tap(text: String, index: Int = 0) = click(rule.onAllNodes(hasText(text) and hasClickAction())[index])
    private fun tapDesc(description: String) = click(rule.onAllNodes(hasContentDescription(description) and hasClickAction()).onFirst())

    /** Type a code where a person would point the camera. */
    private fun scan(code: String) {
        if (rule.onAllNodes(hasSetTextAction() and hasText("Or type the code")).fetchSemanticsNodes().isEmpty()) tap("Type the code")
        rule.onAllNodes(hasSetTextAction() and hasText("Or type the code")).onFirst().performTextReplacement(code)
        settle()
        tap("OK")
    }

    private fun see(text: String) = rule.onAllNodes(hasText(text, substring = true)).onFirst().assertExists()
    private fun dontSee(text: String) = rule.onAllNodes(hasText(text, substring = true)).assertCountEquals(0)
    private fun canTap(text: String) = rule.onAllNodes(hasText(text) and hasClickAction()).fetchSemanticsNodes().isNotEmpty()
    private fun noTextFields() = rule.onAllNodes(hasSetTextAction()).assertCountEquals(0)

    private fun waitFor(text: String) = try {
        rule.waitUntil(20_000) { rule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }
    } catch (e: Throwable) {
        val onScreen = try {
            rule.onAllNodes(hasText("", substring = true)).fetchSemanticsNodes()
                .flatMap { n -> n.config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { it.text } }.joinToString(" | ")
        } catch (_: Throwable) {
            "?"
        }
        throw AssertionError("Timed out waiting to see: $text. On the screen: $onScreen", e)
    }

    /** What the code on the screen says, as the other phone's camera would read it. */
    private fun codeOnScreen(): String = rule.onNode(SemanticsMatcher.keyIsDefined(InviteText)).fetchSemanticsNode().config[InviteText]

    private fun shot(name: String) = rule.onRoot().captureRoboImage("build/screens/moves-$name.png")

    // ---- first open, a new move, its people ----------------------------------------------------------

    @Test
    fun firstOpenStartsAMoveSavesItAndInvitesItsPeopleOneAtATime() {
        val asha = phone("Asha's phone")
        show(asha)
        assertEquals("welcome", route())
        see("Start a new move")
        see("Join a move")
        assertTrue(canTap("Lost your phone?"))
        noTextFields()
        shot("welcome")

        tap("Start a new move")
        assertEquals(1, asha.cards.value.size)
        assertEquals("setup", route())
        see("What is it for?")
        assertTrue(canTap("Join theirs instead"))
        tap("Next")
        // The move: its name is filled in, and can be said over; your name is said too.
        see("Our move")
        noTextFields()
        said = "Maple Road"
        tapDesc("Say the name of the move")
        said = "asha"
        tapDesc("Say your name")
        tap("Next")
        // The rooms can be skipped; tapped, the button says Next.
        see("Skip")
        tap("Kitchen")
        tap("Living room")
        // A room's usual name can be changed right there: Bedroom 2 becomes Ava's room, said rather than typed.
        tap("Bedroom 2")
        tapDesc("Rename Bedroom 2")
        see("Guest room") // usual other names, a tap each
        shot("rename-room")
        said = "ava's room"
        tapDesc("Say a new name for Bedroom 2")
        tap("Done")
        assertEquals(
            listOf("Ava's room", "Kitchen", "Living room"),
            asha.current.value!!.data.value.roomsOf(Home.ORIGIN).map { it.name }.sorted(),
        )
        tap("Next")
        tap("Same as old home (3)")
        tap("Finish")
        val store = asha.current.value!!
        assertTrue(store.data.value.move.setupDone)
        assertEquals("Maple Road", store.data.value.move.name)
        assertEquals("Asha", store.me!!.name)
        asha.refresh()
        assertEquals(listOf("Maple Road"), asha.cards.value.map { it.name })

        // Saved; then who is helping. A second Organiser is suggested first.
        assertEquals("helping", route())
        see("Add a second Organiser")
        noTextFields()
        shot("helping")
        said = "omar"
        tapDesc("Say the second Organiser's name")
        val omar = store.data.value.people.single { it.name == "Omar" }
        assertEquals(Role.ORGANISER, omar.role)
        dontSee("Add a second Organiser")
        // More people: say a name, tap a role, again.
        said = "priya"
        tapDesc("Say a name")
        see("Priya")
        tap("Decider")
        said = "sam"
        tapDesc("Say a name")
        tap("Helper")
        assertEquals(Role.DECIDER, store.data.value.people.single { it.name == "Priya" }.role)
        assertEquals(Role.HELPER, store.data.value.people.single { it.name == "Sam" }.role)
        noTextFields()

        // One invite at a time, those who decide first: Omar's is the one showing.
        tap("Omar")
        see("Omar's invite")
        dontSee("Priya's invite")
        val omarsPhone = phone("Omar's phone")
        val result = joinBehind(omarsPhone, codeOnScreen())
        // Before anything is sent, this phone says which phone came: Right or Undo.
        waitFor("Joined: Omar, on Omar's phone")
        shot("helping-right-undo")
        tap("Right")
        assertTrue(result.poll(20, TimeUnit.SECONDS)!!.ok)
        waitFor("Omar's phone has joined")
        assertEquals(omar.id, omarsPhone.current.value!!.me!!.id)
        // The next one comes up by itself.
        see("Priya's invite")

        // Later: Home, with the move's name at the top.
        tap("Do this later")
        assertEquals("home", route())
        see("Maple Road")
        see("Organiser")
    }

    @Test
    fun aMoveForJustListingShowsNoPeopleScreens() {
        val solo = phone("Lee's phone")
        show(solo)
        tap("Start a new move")
        tap("Just listing what I own")
        tap("Next")
        see("Our home")
        tap("Next")
        tap("Skip")
        // Saved and straight Home: nobody to invite until a second person is added.
        assertEquals("home", route())
        dontSee("Who's helping?")
        tapDesc("You and this phone")
        assertEquals("settings", route())
    }

    // ---- joining ---------------------------------------------------------------------------------

    @Test
    fun joiningAsksYesOrNotMeAndTheOrganiserConfirmsTheRightPhone() {
        val ashaPhone = phone("Asha's phone")
        val maple = move(ashaPhone, "Maple Road")
        maple.createBox("L001", null)
        val priya = maple.addPerson("Priya", Role.DECIDER).valueOrNull()!!
        val host = hosting(maple)
        val invite = host.inviteFor(priya.id, local).valueOrNull()!!.encode()

        val phone = phone("Priya's phone")
        show(phone)
        tap("Join a move")
        assertEquals("join", route())
        noTextFields() // the camera first
        scan(invite)
        see("Join Maple Road as Priya, Decider?")
        see("Nothing is sent until you say Yes")
        shot("join-ask")

        // Not me: nothing is sent, and nothing is on the phone.
        tap("Not me")
        see("Nothing was sent")
        assertTrue(phone.cards.value.isEmpty())
        assertTrue(host.waiting.value.isEmpty())
        assertEquals(0L, maple.data.value.person(priya.id)!!.joinedAt)

        // Yes, then Undo on the Organiser's phone: told so, and still nothing.
        scan(invite)
        tap("Yes")
        waitFor("Waiting for the other phone")
        rule.waitUntil(10_000) { host.waiting.value.isNotEmpty() }
        assertEquals("Joined: Priya, on Priya's phone", host.waiting.value.first().text)
        host.confirm(host.waiting.value.first().id, false)
        waitFor("The other phone said no")
        assertTrue(phone.cards.value.isEmpty())

        // Again, and Right: Priya's phone is in, and Home says who it is now.
        tap("Scan again")
        scan(invite)
        tap("Yes")
        rule.waitUntil(10_000) { host.waiting.value.isNotEmpty() }
        host.confirm(host.waiting.value.first().id, true)
        waitFor("You are Priya, Decider, in Maple Road")
        assertEquals("home", route())
        see(Role.DECIDER.line().take(30))
        val s = phone.current.value!!
        assertEquals(priya.id, s.me!!.id)
        assertEquals(maple.moveId, s.moveId)
        assertNotNull(s.data.value.findByLabel("L001"))
        shot("joined-home")
        tapDesc("Dismiss")
        dontSee("You are Priya")

        // The same invite again: it has been used.
        go("join")
        scan(invite)
        tap("Yes")
        waitFor("This invite no longer works")
        see("has been used or has run out")
    }

    @Test
    fun aRemovedPersonAndAnotherMoveAreSaidPlainly() {
        val ashaPhone = phone("Asha's phone")
        val maple = move(ashaPhone, "Maple Road")
        val hal = maple.addPerson("Hal", Role.HELPER).valueOrNull()!!
        val host = hosting(maple)
        val invite = host.inviteFor(hal.id, local).valueOrNull()!!.encode()
        assertTrue(maple.removePerson(hal.id).ok)
        val phone = phone("Hal's phone")
        show(phone)
        tap("Join a move")
        scan(invite)
        tap("Yes")
        waitFor("Removed from this move")
        assertTrue(phone.cards.value.isEmpty())

        // An invite that names one move, shown by a phone of another.
        val elm = move(phone("Zed's phone"), "Elm Street")
        val pat = maple.addPerson("Pat", Role.HELPER).valueOrNull()!!
        val wrong = host.inviteFor(pat.id, local).valueOrNull()!!.copy(moveId = elm.moveId).encode()
        tap("Scan again")
        scan(wrong)
        tap("Yes")
        waitFor("A different move")

        // Something that is not an invite at all.
        tap("Scan again")
        scan("L001")
        assertEquals("That is not an invite. On the other phone open People and show your invite.", ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun theHelperCodeLetsHelpersInUnderTheirOwnNamesAndEachCanBeRemoved() {
        val ashaPhone = phone("Asha's phone")
        val maple = move(ashaPhone, "Maple Road")
        show(ashaPhone)
        go("helping")
        tap("Show the Helper code")
        see("Any number of Helpers")
        val code = codeOnScreen()
        assertTrue(WifiSync.Invite.parse(code)!!.helperCode)

        // Helpers' phones that know their person's name join without being asked anything more; each is listed.
        val sam = phone("Sam's phone", yourName = "Sam")
        val lee = phone("Lee's phone", yourName = "Lee")
        for (p in listOf(sam, lee)) assertTrue(joinBehind(p, code).poll(20, TimeUnit.SECONDS)!!.ok)
        waitFor("Joined with this code")
        waitFor("Lee")
        see("on Sam's phone")
        assertTrue(maple.data.value.livePeople.any { it.name == "Sam" && it.role == Role.HELPER })
        shot("helper-code")
        // Remove, one by one: the move takes them off, and the phone is turned away after.
        tap("Remove", index = 1)
        val removed = maple.data.value.people.filter { it.removed }.map { it.name }
        assertEquals(1, removed.size)
        val gone = if (removed.single() == "Lee") lee else sam
        dontSee("on ${removed.single()}'s phone")
        assertEquals(WifiSync.REMOVED_SELF, gone.join(gone.readInvite(code)!!).error)

        // A Helper's phone that does not know its person's name yet: it is said on the way in, and Home says who it is.
        val code2 = hosting(maple).helperCode(local).valueOrNull()!!.encode()
        val jo = phone("Jo's phone")
        show(jo)
        tap("Join a move")
        scan(code2)
        see("Join Maple Road as a Helper?")
        said = "jo"
        tapDesc("Say your name")
        tap("Yes")
        waitFor("You are Jo, Helper, in Maple Road")
        assertEquals("Jo", jo.phone.settings.value.name)
        assertTrue(maple.data.value.livePeople.any { it.name == "Jo" && it.role == Role.HELPER })
    }

    // ---- your moves ------------------------------------------------------------------------------

    @Test
    fun theMovesNameOpensYourMovesWhichSwitchesMovesAndTheLastUsedOpensNextTime() {
        val phone = phone("Asha's phone")
        val maple = move(phone, "Maple Road")
        // Something urgent in Maple Road: an overdue job.
        maple.put(Task(id = "late", title = "Book the van", dueDay = today() - 2))
        val elm = move(phone, "Elm Street")
        assertSame(elm, phone.current.value)
        show(phone)
        assertEquals("home", route())
        see("Elm Street")
        // Another move needs its person: a dot by the name.
        rule.onAllNodes(hasContentDescription("Another move needs you")).onFirst().assertExists()
        tapDesc("Your moves")
        assertEquals("moves", route())
        see("Open now")
        see("Needs you")
        see("10 days to go")
        shot("your-moves")
        tap("Maple Road")
        assertSame(maple, phone.current.value)
        assertEquals("home", route())
        see("Maple Road")
        rule.onAllNodes(hasContentDescription("Another move needs you")).assertCountEquals(0)

        // Archived, a move folds away under Earlier moves, and comes back.
        tapDesc("Your moves")
        tapDesc("Back up, archive or remove Elm Street")
        tap("Archive")
        see("Earlier moves")
        assertTrue(phone.cards.value.single { it.moveId == elm.moveId }.archived)

        // The phone opens the move used last.
        phone.flush()
        val again = Moves(File(tmp.root, "phone0/files"), File(tmp.root, "phone0/no_backup"))
        assertEquals(maple.moveId, again.current.value!!.moveId)

        // Start another from the list, and give it up before it is set up: back where it was.
        tap("Start a new move")
        assertEquals("setup", route())
        tapDesc("Give up this new move")
        assertSame(maple, phone.current.value)
        assertEquals(2, phone.cards.value.size)
    }

    @Test
    fun removingSaysWhatNoOtherPhoneHasAndTheLastOrganiserCannotRemoveASharedMove() {
        val ashaPhone = phone("Asha's phone")
        val maple = move(ashaPhone, "Maple Road")
        val priyaPhone = phone("Priya's phone")
        val priya = joined(priyaPhone, maple, "Priya", Role.DECIDER)
        priya.createBox("P1", null)
        priya.createItem("Lamp", null)

        show(priyaPhone)
        tapDesc("Your moves")
        tapDesc("Back up, archive or remove Maple Road")
        tap("Remove from this phone")
        see("Not on any other phone yet")
        see("that no other phone has yet")
        assertTrue(canTap("Sync first"))
        assertTrue(canTap("Back up first"))
        shot("remove-unshared")
        // Back up first: the whole move goes to a file.
        savedTo = File(tmp.root, "priya-backup.zip")
        tap("Back up first")
        waitFor("Backup saved")
        assertTrue(savedTo!!.length() > 0)
        // Sync first: it opens there.
        tapDesc("Back up, archive or remove Maple Road")
        tap("Remove from this phone")
        tap("Sync first")
        assertEquals("sync?code={code}", route())
        // Removed anyway: the other phones keep it.
        tapDesc("Back")
        assertEquals("moves", route())
        tapDesc("Back up, archive or remove Maple Road")
        tap("Remove from this phone")
        tap("Remove anyway")
        assertTrue(priyaPhone.cards.value.isEmpty())
        assertEquals("welcome", route())
        assertNotNull(maple.data.value.person(priya.me?.id ?: ""))

        // The move's only Organiser, with others in it: it stays.
        show(ashaPhone)
        tapDesc("Your moves")
        tapDesc("Back up, archive or remove Maple Road")
        tap("Remove from this phone")
        see("Maple Road stays on this phone")
        see("only Organiser")
        tap("OK")
        assertEquals(1, ashaPhone.cards.value.size)
    }

    @Test
    fun deletingIsOnlyForAMoveNobodyElseEverJoined() {
        val phone = phone("Asha's phone")
        val maple = move(phone, "Maple Road")
        joined(phone("Priya's phone"), maple, "Priya", Role.DECIDER)
        val trial = move(phone, "Trying it out")
        trial.createItem("Piano", null)
        show(phone)
        tapDesc("Your moves")
        tapDesc("Back up, archive or remove Maple Road")
        assertFalse(canTap("Delete"))
        tap("Close")
        tapDesc("Back up, archive or remove Trying it out")
        tap("Delete")
        see("This cannot be undone")
        shot("delete")
        tap("Delete")
        assertEquals(listOf(maple.moveId), phone.cards.value.map { it.moveId })
        assertSame(maple, phone.current.value)
        assertEquals("home", route())
        see("Maple Road")
    }

    // ---- labels, a lost phone, other versions -----------------------------------------------------

    @Test
    fun aLabelHeldByTwoMovesAsksWhichMove() {
        val phone = phone("Asha's phone")
        val maple = move(phone, "Maple Road")
        maple.createBox("L007", null)
        val elm = move(phone, "Elm Street")
        val box = elm.createBox("L007", null)
        phone.open(maple.moveId)
        show(phone)
        go("scan")
        tap("Type the code")
        rule.onAllNodes(hasSetTextAction() and hasText("Or type the code on the label")).onFirst().performTextReplacement("L007")
        settle()
        tap("Open")
        see("Which move?")
        see("in 2 moves")
        shot("which-move")
        tap("Elm Street")
        assertSame(elm, phone.current.value)
        rule.waitUntil(5_000) { route() == "box/{id}" }
        see("L007")
        assertNotNull(elm.data.value.box(box.id))
    }

    @Test
    fun aLostPhoneRestoresItsMoveFromABackupFile() {
        val ashaPhone = phone("Asha's phone")
        val maple = move(ashaPhone, "Maple Road")
        maple.createBox("L001", null)
        val file = File(tmp.root, "maple.zip")
        file.outputStream().use { Backup.export(maple, it) }

        val newPhone = phone("Asha's new phone")
        show(newPhone)
        tap("Lost your phone?")
        assertEquals("lost", route())
        see("No backup?")
        opened = file
        tap("Choose a backup file")
        waitFor("Restored Maple Road")
        assertEquals("home", route())
        val s = newPhone.current.value!!
        assertEquals(maple.moveId, s.moveId)
        assertNotNull(s.data.value.findByLabel("L001"))
        assertEquals("Asha", s.me!!.name)
    }

    @Test
    fun aPhoneOnAnOlderVersionIsOfferedTheAppThroughTheShareSheet() {
        // A stand-in for a phone on 2.0 showing its code: it reads the hello and answers that the versions differ.
        val server = ServerSocket(0)
        thread(isDaemon = true) {
            try {
                while (true) server.accept().use { s ->
                    val din = DataInputStream(s.getInputStream().buffered())
                    val out = DataOutputStream(s.getOutputStream().buffered())
                    din.readUTF(); repeat(3) { din.readUTF() }; din.readInt(); repeat(2) { din.readUTF() }
                    out.writeUTF("VERSION"); out.flush()
                }
            } catch (_: Exception) {
            }
        }
        val apk = File(tmp.root, "app.apk").apply { writeBytes(ByteArray(2048) { it.toByte() }) }
        installedApk = { apk }
        val shared = LinkedBlockingQueue<Pair<File, String>>()
        shareSheet = { _, file, type -> shared.add(file to type) }
        try {
            val invite = WifiSync.Invite(local, server.localPort, "TOKEN123", "p1", "Priya", Role.DECIDER, "mmaple", "Maple Road").encode()
            val phone = phone("Priya's phone")
            show(phone)
            tap("Join a move")
            scan(invite)
            tap("Yes")
            waitFor("Different versions")
            see("older HouseMove")
            shot("different-versions")
            tap("Share the app")
            // The installed app goes to the share sheet (and so to Quick Share) from the shared cache folder.
            // The copy is made off the main thread and handed over on it: let the main thread run while waiting.
            val until = System.currentTimeMillis() + 10_000
            while (shared.isEmpty() && System.currentTimeMillis() < until) {
                settle()
                Thread.sleep(50)
            }
            val (file, type) = shared.poll()!!
            assertEquals("application/vnd.android.package-archive", type)
            assertEquals("out", file.parentFile!!.name)
            assertArrayEquals(apk.readBytes(), file.readBytes())
            assertTrue(phone.cards.value.isEmpty())
            // Beside it, for a phone with internet: a code to download the app, and what it needs.
            tap(SHOW_DOWNLOAD_CODE)
            see(APP_DOWNLOAD_NOTE)
            assertEquals(APP_DOWNLOAD_URL, codeOnScreen())
            tap("Done")
            dontSee(APP_DOWNLOAD_NOTE)
        } finally {
            server.close()
        }
    }
}
