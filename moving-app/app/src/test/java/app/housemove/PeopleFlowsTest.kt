package app.housemove

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
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.navigation.NavHostController
import app.housemove.data.*
import app.housemove.sync.WifiSync
import app.housemove.ui.AppRoot
import app.housemove.ui.InviteText
import app.housemove.ui.LocalSpeech
import app.housemove.ui.LocalSyncAddresses
import app.housemove.ui.Speech
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast

/**
 * People, joining, approvals and what each role is shown, driven through the real
 * screens. Phones meet over a real connection (localhost); a code is typed where a
 * person would scan, and words are "spoken" through a stand-in for the phone's speech.
 * Nothing here opens the keyboard except where a test says it is checking the typed way.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class PeopleFlowsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @get:Rule
    val rule = createComposeRule()

    private val local = listOf("127.0.0.1")

    /** The phone whose screen is showing. */
    private var shown by mutableStateOf<Store?>(null)
    private var started = false
    private val store get() = shown!!
    private val data get() = store.data.value
    private var nav: NavHostController? = null

    /** What the stand-in for speech hears next. */
    private var said = ""

    private val hosts = ArrayList<WifiSync.Host>()

    @After
    fun stopHosts() = hosts.forEach { it.stop() }

    private fun newStore(name: String = ""): Store = Store(tmp.newFolder()).also { s -> if (name.isNotEmpty()) s.setDevice { it.copy(name = name) } }

    /** Asha's phone: a move that is set up, with a few things in it. */
    private fun organiser(): Store {
        val s = newStore("Asha")
        s.setDevice { it.copy(letter = "A") }
        s.setMove { it.copy(name = "Maple Road", moveDay = today() + 10, setupDone = true) }
        val lounge = s.createRoom(Home.DEST, "Lounge")
        val old = s.createRoom(Home.ORIGIN, "Lounge").copy(mapsTo = lounge.id).also { s.put(it) }
        for (name in listOf("Sofa", "Lamp", "Bike", "Chair", "Table")) s.put(Item(id = name.lowercase(), name = name, originRoomId = old.id))
        return s
    }

    /** Someone added to [host]'s move whose phone has joined. Returns that phone. */
    private fun joined(host: Store, name: String, role: Role): Store {
        val p = host.addPerson(name, role).valueOrNull()!!
        val phone = newStore()
        assertTrue(phone.joinAs(p.id, host.data.value.visibleTo(role)).ok)
        host.mergeIn(phone.data.value)
        return phone
    }

    /** What a sync does to the data, both ways. */
    private fun sync(a: Store, b: Store) {
        rule.runOnUiThread { b.mergeIn(a.mergeIn(b.data.value).visibleTo(b.myRole)) }
        settle()
    }

    private fun hosting(store: Store, letNewPhonesIn: Boolean = false): WifiSync.Host =
        WifiSync.host(store, letNewPhonesIn = letNewPhonesIn) {}.also { hosts += it }

    /** Put [s] on the screen, as the app would open on that phone. */
    private fun show(s: Store, route: String? = null) {
        if (!started) {
            started = true
            rule.setContent {
                CompositionLocalProvider(LocalSpeech provides Speech { _, onText -> onText(said) }, LocalSyncAddresses provides { local }) {
                    shown?.let { phone -> key(phone) { AppRoot(phone, onNav = { nav = it }) } }
                }
            }
        }
        rule.runOnUiThread { shown = s }
        settle()
        if (route != null) go(route)
    }

    private fun settle() = rule.waitForIdle()

    private fun go(route: String) {
        rule.runOnUiThread { nav!!.navigate(route) }
        settle()
    }

    private fun back() {
        rule.runOnUiThread { nav!!.popBackStack() }
        settle()
    }

    private fun route(): String? {
        var r: String? = null
        rule.runOnUiThread { r = nav!!.currentBackStackEntry?.destination?.route }
        return r
    }

    /** How many times a finger went down since this was last set to 0. */
    private var taps = 0

    private fun click(node: SemanticsNodeInteraction) {
        try {
            node.performScrollTo()
        } catch (_: AssertionError) {
            // not inside a scrolling container
        }
        node.performClick()
        taps++
        settle()
    }

    private fun tap(text: String, index: Int = 0) = click(rule.onAllNodes(hasText(text) and hasClickAction())[index])

    private fun tapDesc(description: String, sub: Boolean = false) =
        click(rule.onAllNodes(hasContentDescription(description, substring = sub) and hasClickAction()).onFirst())

    /** Type a code where a person would scan it. It stands for pointing the camera, so it counts as neither a tap nor typing. */
    private fun scan(code: String) {
        val before = taps
        rule.onAllNodes(hasSetTextAction() and hasText("Or type the code")).onFirst().performTextReplacement(code)
        settle()
        tap("OK")
        taps = before
    }

    private fun see(text: String) = rule.onAllNodes(hasText(text, substring = true)).onFirst().assertExists()
    private fun dontSee(text: String) = rule.onAllNodes(hasText(text, substring = true)).assertCountEquals(0)
    private fun canTap(text: String) = rule.onAllNodes(hasText(text) and hasClickAction()).fetchSemanticsNodes().isNotEmpty()
    private fun noTextFields() = rule.onAllNodes(hasSetTextAction()).assertCountEquals(0)

    /** Wait for something that arrives from another thread: the end of a sync. */
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
    private fun codeOnScreen(): WifiSync.Invite =
        WifiSync.Invite.parse(rule.onNode(SemanticsMatcher.keyIsDefined(InviteText)).fetchSemanticsNode().config[InviteText])!!

    private fun toast(): String? = ShadowToast.getTextOfLatestToast()

    // ---- people ---------------------------------------------------------------------

    @Test
    fun organiserAddsSomeoneWithoutTypingAndTheirPhoneJoinsWithTheInvite() {
        val asha = organiser()
        show(asha)
        tapDesc("People") // the round button with this phone's initial
        assertEquals("people", route())
        see("Just you so far")
        dontSee("Organiser") // one person: nothing about roles

        tap("Add a person")
        assertEquals("people/add", route())
        noTextFields()
        see("Helper 1") // the name they get if none is given
        said = "sam"
        tapDesc("Say the name")
        see("Sam")
        see("A Helper can") // Helper is chosen to start with
        tap("Decider")
        see("A Decider can")
        see("for an Organiser to approve")
        noTextFields()
        tap("Add and show invite")

        val sam = data.people.single { it.name == "Sam" }
        assertEquals(Role.DECIDER, sam.role)
        assertEquals("B", sam.letter) // picked for them
        assertEquals(0L, sam.joinedAt)
        assertEquals("person/{id}?invite={invite}", route())
        see("Sam's invite")
        see("Works once")
        see("10 minutes")
        val invite = codeOnScreen()
        assertEquals(sam.id, invite.personId)
        assertEquals(Role.DECIDER, invite.role)

        // Sam's phone scans it and becomes Sam.
        val phone = newStore()
        val r = WifiSync.join(phone, invite)
        assertTrue(r.error, r.ok)
        assertEquals(sam.id, phone.me!!.id)
        assertEquals(Role.DECIDER, phone.myRole)
        assertEquals("Maple Road", phone.data.value.move.name)
        waitFor("Sam's phone has joined")
        dontSee("Works once")

        // The invite worked once: a second phone is turned away, and the Organiser is told.
        assertEquals(WifiSync.INVITE_EXPIRED, WifiSync.join(newStore(), invite).error)
        waitFor("has been used or has run out")

        back()
        assertEquals("people", route())
        see("Joined")
        see("Decider")
        see("Who can do what")
    }

    @Test
    fun organiserRenamesChangesRoleRemovesRestoresAndInvitesANewPhone() {
        val asha = organiser()
        val hal = asha.addPerson("Hal", Role.HELPER).valueOrNull()!!
        show(asha, "people")
        see("Not joined yet")
        tap("Hal")
        assertEquals("person/{id}?invite={invite}", route())
        dontSee("Works once") // the invite is only shown when asked for
        noTextFields()

        tap("Decider")
        assertEquals(Role.DECIDER, data.person(hal.id)!!.role)

        // The name: spoken, or typed once behind the pencil.
        tapDesc("Edit name")
        said = "harold"
        tapDesc("Say the name")
        assertEquals("Harold", data.person(hal.id)!!.name)
        tapDesc("Edit name")
        rule.onAllNodes(hasSetTextAction() and hasText("Name")).onFirst().performTextReplacement("Hal")
        settle()
        assertEquals("Hal", data.person(hal.id)!!.name)
        tapDesc("Edit name")

        tap("Show Hal's invite")
        see("Hal's invite")
        val first = codeOnScreen()
        tap("Make a new invite")
        assertNotEquals(first.token, codeOnScreen().token)

        tap("Remove from the move")
        tap("Remove Hal")
        assertTrue(data.person(hal.id)!!.removed)
        dontSee("Works once")
        see("can no longer sync")
        // The invite made before they were removed is no use now.
        assertNotNull(WifiSync.join(newStore(), first).error)

        tap("Add Hal back")
        assertFalse(data.person(hal.id)!!.removed)
        tap("Show Hal's invite")
        val phone = newStore()
        assertTrue(WifiSync.join(phone, codeOnScreen()).ok)
        waitFor("Hal's phone has joined")
        // A replacement phone for the same person gets a fresh invite.
        tap("New phone for this person")
        val second = newStore()
        assertTrue(WifiSync.join(second, codeOnScreen()).ok)
        assertEquals(hal.id, second.me!!.id)

        // The last Organiser cannot be made anything else: the Store's reason is shown.
        back()
        tap("Asha")
        tap("Helper")
        assertEquals("A move needs at least one Organiser. Make someone else an Organiser first.", toast())
        assertEquals(Role.ORGANISER, store.myRole)
    }

    // ---- joining --------------------------------------------------------------------

    @Test
    fun aNewPhoneJoinsFromTheFirstScreenAndIsToldWhoItIs() {
        val asha = organiser()
        val sam = asha.addPerson("Sam", Role.HELPER).valueOrNull()!!
        val host = hosting(asha)
        val invite = host.inviteFor(sam.id, local).valueOrNull()!!

        show(newStore())
        assertEquals("setup", route())
        tap("Second phone for the same move?") // straight to the scanner
        scan(invite.encode())
        waitFor("You are Sam, Helper")
        see("Photographs, packs")
        assertEquals(sam.id, store.me!!.id)
        assertEquals(Role.HELPER, store.myRole)
        rule.waitUntil(5_000) { asha.data.value.person(sam.id)!!.joinedAt > 0 } // the other phone knows too
        tap("Start")
        assertEquals("home", route())
        see("Maple Road")
        see("Sam · Helper")
        dontSee("Show everything") // the role is an Organiser's to change, not a switch on this phone

        // The same invite again: it has been used.
        go("sync")
        see("This phone is Sam")
        tap("Scan sync code")
        scan(invite.encode())
        waitFor("This invite no longer works")
        see("has been used or has run out")

        // A plain sync between phones on the move works as it always did.
        asha.createBox("L001", null)
        tap("Scan sync code")
        scan(host.invite(local).encode())
        waitFor("Synced with Asha")
        assertEquals(1, data.liveBoxes.size)
        assertTrue(store.device.value.lastSyncAt > 0)

        // Once removed, the phone is told so in plain words.
        asha.removePerson(sam.id)
        tap("Scan sync code")
        scan(host.invite(local).encode())
        waitFor("Removed from this move")
        see("You have been removed from this move")
    }

    @Test
    fun aPhoneThatWasNeverAddedIsToldToAskForAnInvite() {
        val asha = organiser()
        joined(asha, "Dee", Role.DECIDER)
        val host = hosting(asha) // as the sync screen does once the move has more than one person
        show(newStore())
        tap("Second phone for the same move?")
        scan(host.invite(local).encode())
        waitFor("Not added to this move yet")
        see("Ask an Organiser to add you and show you your invite")
        assertEquals("setup", route())
        assertFalse(data.move.setupDone)
        assertTrue(data.people.none { it.name == "Asha" })

        // Something that is not a code at all.
        tap("Second phone for the same move?")
        scan("L001")
        assertEquals("That is not an invite. On the other phone open People and show your invite.", toast())
    }

    @Test
    fun theSyncCodeLetsANewPhoneInOnlyWhileTheMoveHasOnePerson() {
        val asha = organiser()
        show(asha, "sync")
        dontSee("This phone is") // one person: nothing about who is who
        tap("Show sync code")
        // As before 2.0: a second phone joins as itself by scanning the plain code.
        val partner = newStore("Ben")
        val r = WifiSync.join(partner, codeOnScreen(), letNewPhonesIn = partner.isAlone())
        assertTrue(r.error, r.ok)
        waitFor("Synced with Ben")
        assertEquals(2, data.livePeople.size)
        tap("Stop showing the code")

        // Now there are two people, so the next phone needs an invite.
        see("This phone is Asha")
        tap("Show sync code")
        val stranger = newStore("Mallory")
        assertEquals(WifiSync.NOT_ADDED, WifiSync.join(stranger, codeOnScreen(), letNewPhonesIn = stranger.isAlone()).error)
        waitFor("That phone is not on this move")
        assertEquals(2, data.livePeople.size)
        // The two that are on the move still sync.
        partner.createBox("B1", null)
        assertTrue(WifiSync.join(partner, codeOnScreen(), letNewPhonesIn = partner.isAlone()).ok)
        waitFor("Synced with Ben")
        assertEquals(1, data.liveBoxes.size)
    }

    // ---- approvals ------------------------------------------------------------------

    @Test
    fun organiserApprovesDeclinesWithAReasonAndAnswersAQuestion() {
        val asha = organiser()
        val dee = joined(asha, "Dee", Role.DECIDER)
        val hal = joined(asha, "Hal", Role.HELPER)
        val sell = dee.suggestDecision("sofa", Disposition.SELL, 80.0, note = "Too big").valueOrNull()!!
        val cost = dee.suggestCost(Expense(id = "boxes", title = "More boxes", amount = 35.0, day = today())).valueOrNull()!!
        val question = hal.ask("lamp", note = "Is this coming?").valueOrNull()!!
        show(asha)
        dontSee("Review") // nothing has arrived yet
        sync(asha, dee)
        sync(asha, hal)

        // Home: the strip, and the count among the key numbers.
        see("3 waiting for you")
        see("approvals")
        see("Sell Sofa for 80")
        tap("Review")
        assertEquals("approvals", route())
        see("3 waiting")
        see("Dee suggests")
        see("“Too big”")
        see("Hal asks")
        see("What to do with Lamp?")
        noTextFields()

        // Approve: the decision is made, in the Organiser's name.
        tap("Approve")
        assertEquals(Disposition.SELL, data.item("sofa")!!.disposition)
        assertEquals(80.0, data.item("sofa")!!.price!!, 0.0)
        assertEquals(ProposalState.APPROVED, data.proposal(sell.id)!!.state)
        assertEquals("Approved: Sell Sofa for 80", toast())

        // Decline with a reason by one tap.
        tap("Decline")
        tap("Too expensive")
        assertEquals(ProposalState.DECLINED, data.proposal(cost.id)!!.state)
        assertEquals("Too expensive", data.proposal(cost.id)!!.reason)
        assertNull(data.expense("boxes"))

        // Answer the Helper's question with a decision tile.
        tap("Donate")
        assertEquals(Disposition.DONATE, data.item("lamp")!!.disposition)
        assertEquals(ProposalState.APPROVED, data.proposal(question.id)!!.state)
        see("Nothing is waiting")
        tap("Decided lately")
        see("Declined by Asha")
        see("“Too expensive”")

        // Three more: one declined in spoken words, two approved together.
        val keep = dee.suggestDecision("chair", Disposition.BIN).valueOrNull()!!
        val a = dee.suggestDecision("table", Disposition.SELL, 20.0).valueOrNull()!!
        val b = dee.suggestTask(Task(id = "skip", title = "Book a skip")).valueOrNull()!!
        sync(asha, dee)
        see("3 waiting")
        tap("Decline")
        said = "we still need it"
        tapDesc("Say why")
        tap("Decline: “we still need it”")
        assertEquals("we still need it", data.proposal(keep.id)!!.reason)
        assertEquals(Disposition.UNDECIDED, data.item("chair")!!.disposition)
        tap("Select all")
        see("2 selected")
        tap("Approve 2")
        assertEquals(ProposalState.APPROVED, data.proposal(a.id)!!.state)
        assertEquals(ProposalState.APPROVED, data.proposal(b.id)!!.state)
        assertEquals("Book a skip", data.task("skip")!!.title)
        assertEquals("Approved 2 suggestions", toast())
        see("Nothing is waiting")

        // The reason can also be typed, behind "Type a reason".
        val last = dee.suggestRoom("bike", null).valueOrNull()!!
        sync(asha, dee)
        tap("Decline")
        tap("Type a reason")
        rule.onAllNodes(hasSetTextAction() and hasText("Your reason")).onFirst().performTextReplacement("It goes in the shed")
        settle()
        tap("Decline: “It goes in the shed”")
        assertEquals("It goes in the shed", data.proposal(last.id)!!.reason)
    }

    // ---- who is shown what -------------------------------------------------------------

    @Test
    fun eachRoleIsShownOnlyWhatItMayDo() {
        val asha = organiser()
        val dee = joined(asha, "Dee", Role.DECIDER)
        val hal = joined(asha, "Hal", Role.HELPER)
        dee.suggestDecision("sofa", Disposition.SELL, 80.0)
        val yes = dee.suggestDecision("lamp", Disposition.DONATE).valueOrNull()!!
        val no = dee.suggestDecision("bike", Disposition.BIN).valueOrNull()!!
        hal.ask("chair")
        asha.mergeIn(dee.data.value)
        asha.mergeIn(hal.data.value)
        assertTrue(asha.approve(yes.id).ok)
        assertTrue(asha.decline(no.id, "We're keeping it").ok)
        dee.mergeIn(asha.data.value)
        hal.mergeIn(asha.data.value.visibleTo(Role.HELPER))

        // The Organiser: everything.
        show(asha, "people")
        assertTrue(canTap("Add a person"))
        see("Who can do what")
        assertTrue(canTap("Dee"))
        go("settings")
        see("You are Asha")
        see("Parts of the app")
        see("Start again")
        dontSee("Helper mode") // the role has taken its place
        tap("This phone")
        dontSee("Helper mode")
        go("sync")
        see("Restore or merge a backup file")
        go("more")
        see("Approvals")
        see("2 waiting")

        // The Decider: suggests, and sees what became of it.
        show(dee)
        // A no is said on Home, with the reason, without having to go and look for it.
        see("Declined: Bin Bike")
        see("“We're keeping it”")
        see("1 waiting for approval")
        tap("View")
        assertEquals("approvals", route())
        see("My suggestions")
        see("Waiting for approval")
        see("Approved by Asha")
        see("Declined by Asha")
        see("“We're keeping it”")
        see("You can carry it out now")
        assertFalse(canTap("Approve"))
        assertFalse(canTap("Decline"))
        go("people")
        see("Asha")
        see("Who can do what")
        assertFalse(canTap("Add a person"))
        assertFalse(canTap("Hal")) // the list only
        go("settings")
        see("You are Dee")
        see("An Organiser looks after")
        dontSee("Parts of the app")
        dontSee("Start again")
        dontSee("Helper mode")
        go("sync")
        see("Save backup")
        dontSee("Restore or merge")
        go("more")
        see("My suggestions")
        see("Triage") // a Decider suggests there
        see("Supplies and costs")
        go("people/add") // even by a stale link, the reason is given
        see("Only an Organiser can add people")

        // The Helper: the simple Home, their questions, the list of people.
        show(hal)
        see("Hal · Helper")
        see("Sync with the other phones")
        dontSee("Show everything")
        see("1 question waiting")
        tap("View")
        see("My questions")
        see("Waiting for an answer")
        see("Asked just now")
        go("people")
        assertFalse(canTap("Add a person"))
        go("settings")
        see("You are Hal")
        dontSee("Start again")
        dontSee("Parts of the app")
        go("sync")
        dontSee("Restore or merge")
        // The list of every screen leaves out what a Helper has no part in.
        go("more")
        see("Walk-through")
        see("Supplies")
        dontSee("costs")
        dontSee("Triage")
        dontSee("Plan rooms")
        dontSee("Sell, donate, bin")
    }

    @Test
    fun aDeclineWithNoReasonSaysSoAndTheMoveDateIsOnlyAskedOfThoseWhoMaySetIt() {
        val asha = organiser()
        asha.changeMove { it.copy(moveDay = null) }
        val dee = joined(asha, "Dee", Role.DECIDER)
        val no = dee.suggestDecision("sofa", Disposition.BIN).valueOrNull()!!
        asha.mergeIn(dee.data.value)
        assertTrue(asha.decline(no.id).ok)
        dee.mergeIn(asha.data.value)

        // The Organiser is asked for the date, on Home and on the Settings tile.
        show(asha)
        see("no date yet")
        assertTrue(canTap("Set the date"))
        go("more")
        rule.onAllNodes(hasText("Settings") and hasContentDescription("Needs attention")).assertCountEquals(1)

        // The Decider is told there is none, and is not asked to do anything about it.
        show(dee)
        see("no date yet")
        dontSee("Set the date")
        see("Declined: Bin Sofa")
        see("No reason given")
        go("more")
        see("No move day yet")
        rule.onAllNodes(hasText("Settings") and hasContentDescription("Needs attention")).assertCountEquals(0)
        go("approvals")
        see("Declined by Asha")
        see("No reason given")
    }

    @Test
    fun aMoveWithOnePersonShowsNothingAboutRoles() {
        show(organiser())
        dontSee("waiting")
        dontSee("Review")
        go("more")
        see("People")
        see("Just you")
        dontSee("Approvals")
        go("settings")
        dontSee("You are")
        dontSee("Organiser")
        tap("This phone")
        see("Helper mode") // alone, the old switch is still there
        go("people")
        dontSee("Who can do what")
        dontSee("Organiser")
        go("approvals")
        see("Nothing is waiting")
        go("sync")
        dontSee("This phone is")
        see("Restore or merge a backup file")
    }

    // ---- activity -------------------------------------------------------------------

    @Test
    fun theActivityListIsReachedFromPeople() {
        val asha = organiser()
        val box = asha.createBox("L012", null)
        asha.put(box.copy(sealed = true))
        asha.addPerson("Hal", Role.HELPER)
        show(asha, "people")
        see("Lately")
        see("Asha added Hal as a Helper")
        tap("All activity")
        assertEquals("activity", route())
        see("Today")
        see("Asha sealed L012")
        tap("Asha sealed L012")
        assertEquals("box/{id}", route())
    }

    // ---- small screens -----------------------------------------------------------------

    /** A small phone with large text: the new screens open, and their main actions can still be reached. */
    @Test
    @Config(sdk = [34], qualifiers = "w320dp-h568dp-night-xhdpi")
    fun theNewScreensWorkOnASmallPhoneWithLargeText() {
        RuntimeEnvironment.setFontScale(1.3f)
        val asha = organiser()
        val dee = joined(asha, "Dee", Role.DECIDER)
        val hal = joined(asha, "Hal", Role.HELPER)
        val sell = dee.suggestDecision("sofa", Disposition.SELL, 80.0, note = "Too big for the new lounge").valueOrNull()!!
        hal.ask("lamp")
        asha.mergeIn(dee.data.value)
        asha.mergeIn(hal.data.value)
        fun shot(name: String) = rule.onRoot().captureRoboImage("build/screens/small-$name.png")

        show(asha)
        tap("Review")
        assertEquals("approvals", route())
        shot("approvals")
        tap("Decline")
        shot("approvals-decline")
        tap("Not yet")
        assertEquals("Not yet", data.proposal(sell.id)!!.reason)
        tap("Donate")
        assertEquals(Disposition.DONATE, data.item("lamp")!!.disposition)

        go("people")
        shot("people")
        tap("Add a person")
        shot("people-add")
        tap("Add and show invite")
        see("Works once")
        shot("person-invite")
        go("activity")
        shot("activity")

        dee.mergeIn(asha.data.value)
        hal.mergeIn(asha.data.value.visibleTo(Role.HELPER))
        show(dee)
        shot("home-decider")
        tap("View")
        see("Declined by Asha")
        shot("my-suggestions")
        show(hal)
        shot("home-helper")
    }

    // ---- as little typing as possible ----------------------------------------------------

    @Test
    fun theFirstRunAndTheSettingsNeedNoKeyboard() {
        show(newStore())
        assertEquals("setup", route())
        noTextFields()
        tap("Next")
        noTextFields()
        said = "asha"
        tapDesc("Say your name")
        assertEquals("Asha", store.device.value.name)
        tap("Next")
        noTextFields()
        tap("Kitchen")
        said = "wine cellar"
        tapDesc("Say a room name")
        assertEquals(setOf("Kitchen", "Wine cellar"), data.roomsOf(Home.ORIGIN).map { it.name }.toSet())
        tap("Next")
        tap("Kitchen")
        noTextFields()
        tap("Finish")
        assertEquals("home", route())
        assertTrue(data.move.setupDone)
        assertEquals("Old home", data.move.originName) // the defaults stand until someone edits them

        go("settings")
        noTextFields()
        tap("Sizes, money and book titles")
        tap("Long van · 12 m³")
        assertEquals(12.0, data.move.vanCapacityM3, 0.0)
        tap("€")
        assertEquals("€", data.move.currency)
        noTextFields()
        // The names are there to edit, pre-filled, inside a closed fold.
        tap("Names")
        rule.onAllNodes(hasSetTextAction() and hasText("Old home")).onFirst().performTextReplacement("Maple Road")
        settle()
        assertEquals("Maple Road", data.move.originName)

        go("rooms")
        noTextFields()
        go("search")
        store.put(Box(id = "b1", label = "L001", contents = "Kettle and toaster"))
        said = "kettle"
        tapDesc("Say what you are looking for")
        see("Box L001")
    }

    // ---- how much work the main flows are ---------------------------------------------------

    /**
     * The main flows of the core screens, each walked the short way a person would. Taps are
     * counted; none of the flows opens the keyboard (names are spoken or left as they are,
     * codes are scanned, reasons are tapped). The counts are written to build/audit/flows.md,
     * and each flow has a ceiling, so one that grows by a step fails here.
     */
    @Test
    fun theMainFlowsTakeFewTapsAndNoKeyboard() {
        val lines = ArrayList<String>()
        fun flow(name: String, how: String, most: Int, steps: () -> Unit) {
            taps = 0
            steps()
            lines += "| $name | $taps | 0 | $how |"
            assertTrue("$name took $taps taps; it used to take $most", taps <= most)
        }

        // First run: what it is for, the move, one room in each home.
        show(newStore())
        flow("First run, to Home", "Next, Next, a room, Next, a room, Finish", most = 6) {
            tap("Next"); tap("Next"); tap("Kitchen"); tap("Next"); tap("Kitchen"); tap("Finish")
            assertEquals("home", route())
        }
        show(newStore())
        flow("First run, saying your name on the way", "as above, and the microphone on the second page", most = 7) {
            tap("Next")
            said = "asha"
            tapDesc("Say your name")
            tap("Next"); tap("Kitchen"); tap("Next"); tap("Kitchen"); tap("Finish")
            assertEquals("Asha", store.device.value.name)
            assertEquals("home", route())
        }

        // Add a person: from Home to their invite on the screen.
        val asha = organiser()
        show(asha)
        flow("Add a person, to their invite", "People, Add a person, Add and show invite (a Helper called \"Helper 1\")", most = 3) {
            tapDesc("People"); tap("Add a person"); tap("Add and show invite")
            see("Works once")
        }
        back()
        flow("Add a person with a spoken name and another role", "from People: Add a person, microphone, Decider, Add and show invite", most = 4) {
            tap("Add a person")
            said = "dee"
            tapDesc("Say the name")
            tap("Decider")
            tap("Add and show invite")
            see("Dee's invite")
        }
        val dee = data.people.single { it.name == "Dee" }

        // Join: a new phone, from its first screen to Home.
        val invite = hosting(asha).inviteFor(dee.id, local).valueOrNull()!!
        show(newStore())
        flow("Join with an invite, to Home", "Second phone for the same move?, point the camera, Start", most = 2) {
            tap("Second phone for the same move?")
            scan(invite.encode())
            waitFor("You are Dee, Decider")
            tap("Start")
            assertEquals("home", route())
        }
        val deePhone = store
        assertEquals(dee.id, deePhone.me!!.id)
        rule.waitUntil(5_000) { asha.data.value.person(dee.id)!!.joinedAt > 0 }

        // Approve and decline: from Home, with two suggestions waiting.
        deePhone.suggestDecision("sofa", Disposition.SELL, 80.0)
        deePhone.suggestDecision("bike", Disposition.BIN)
        show(asha)
        sync(asha, deePhone)
        go("home")
        flow("Approve a suggestion", "Review, Approve", most = 2) {
            tap("Review"); tap("Approve")
            assertEquals(Disposition.SELL, data.item("sofa")!!.disposition)
        }
        flow("Decline the next one with a reason", "the inbox is open: Decline, a reason", most = 2) {
            tap("Decline"); tap("We're keeping it")
            assertEquals("We're keeping it", data.liveProposals.single { it.targetId == "bike" }.reason)
        }

        // Find something: by saying it, and by tapping its room.
        store.put(Box(id = "b1", label = "L001", contents = "Kettle and toaster", originRoomId = data.roomsOf(Home.ORIGIN).first().id))
        go("home")
        flow("Find something by saying it", "Find anything, microphone, the thing", most = 3) {
            tapDesc("Find anything")
            said = "kettle"
            tapDesc("Say what you are looking for")
            tap("Box L001 · Kettle and toaster")
            assertEquals("box/{id}", route())
        }
        go("home")
        flow("Find something by its room", "Find anything, the room, the thing", most = 3) {
            tapDesc("Find anything")
            tap("Lounge")
            tap("Box L001 · Kettle and toaster")
            assertEquals("box/{id}", route())
        }

        val out = java.io.File("build/audit/flows.md")
        out.parentFile?.mkdirs()
        out.writeText(
            "# Taps and keyboard uses of the main flows\n\n" +
                "Counted by PeopleFlowsTest.theMainFlowsTakeFewTapsAndNoKeyboard, which walks each flow the short way.\n" +
                "Pointing the camera at a code and saying a word are not taps; the tap on the microphone is.\n\n" +
                "| Flow | Taps | Keyboard | The taps |\n|---|---:|---:|---|\n" + lines.joinToString("\n") + "\n",
        )
    }
}
