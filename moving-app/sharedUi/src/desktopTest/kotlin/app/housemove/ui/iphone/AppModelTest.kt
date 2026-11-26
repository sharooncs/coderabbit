package app.housemove.ui.iphone

import app.housemove.data.AppData
import app.housemove.data.Home
import app.housemove.data.Moves
import app.housemove.data.Role
import app.housemove.data.search
import app.housemove.data.toMovable
import app.housemove.data.whereIs
import app.housemove.sync.WifiSync
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The first iPhone build's model, as its screens drive it, against a hosting "Android phone" over real sockets on
 * 127.0.0.1: joining with a typed, pasted or linked invite; what goes wrong (a wrong code, a used invite, the local
 * network switched off, no answer); the rooms and things it then shows; getting fresh data both ways; that it never
 * changes the move; and leaving.
 */
class AppModelTest {
    private val root = newRoot()
    private val android = AndroidPhone(root)
    private val hooks = DesktopHooks(File(root, "iphone"))
    private val model = AppModel(hooks, "Test build 0.1 (1)", mainScope(), localNetworkWaitMs = 2_000)
    private val addressesBefore = WifiSync.addresses

    @AfterTest
    fun cleanUp() {
        WifiSync.addresses = addressesBefore
        model.stopCode()
        android.close()
        root.deleteRecursively()
    }

    private fun joinAsAlex() {
        model.use(android.invite())
        model.yes()
        waitFor("the join") { model.store != null || model.join is JoinStep.Failed }
        assertNotNull(model.store, "joined: ${(model.join as? JoinStep.Failed)?.report}")
    }

    private val iphoneData: AppData get() = model.store!!.data.value

    @Test
    fun asksBeforeJoiningAndJoinsWithATypedInvite() {
        assertNull(model.store)
        assertTrue(model.join is JoinStep.Start)
        // As the Camera hands it over (hmsync:), and with a line break from copying.
        val invite = android.invite()
        model.use("hmsync:" + invite.removePrefix("HMSYNC:").chunked(40).joinToString("\n"))
        val asking = model.join as JoinStep.Asking
        assertEquals("Join Sample move as Alex, Decider?", asking.check.question)
        // Nothing has been sent yet: the Android phone knows nothing of this iPhone.
        assertEquals(0L, android.store.data.value.person(android.alexId)!!.joinedAt)

        model.yes()
        waitFor("the join") { model.store != null || model.join is JoinStep.Failed }
        val s = assertNotNull(model.store)
        assertEquals("Sample move", s.data.value.move.name)
        assertEquals(Role.DECIDER, s.myRole)
        assertEquals("You are Alex, Decider, in Sample move", model.welcome?.title)
        // Nothing asked for Get fresh data: the shared screens open on Home.
        assertEquals(0, model.freshAsked)
        assertTrue(s.device.value.lastSyncAt > 0)
        assertEquals(0, hooks.asked, "no local-network question when the other phone answered")

        // The move is kept on disk in the iPhone's own folders: opened again, it is there.
        val again = Moves(hooks.dataDir, hooks.noBackupDir, deviceName = { "iPhone" })
        assertEquals("Sample move", again.current.value?.data?.value?.move?.name)
    }

    @Test
    fun showsTheRoomsAndWhereEachThingIs() {
        joinAsAlex()
        val d = iphoneData
        val kitchen = d.roomsOf(Home.ORIGIN).first { it.name == "Kitchen" }
        val things = d.liveItems.filter { it.originRoomId == kitchen.id }
        assertEquals(listOf("Kettle", "Microwave", "Toaster"), things.map { it.name }.sorted())
        assertEquals(2, d.liveBoxes.count { it.originRoomId == kitchen.id })
        val kettle = things.first { it.name == "Kettle" }
        // Which box it is in, and where that box is, as Android says it (the shared screens show these words).
        assertEquals("In Box A1 · Plates, mugs, glasses · Old home: Kitchen", d.whereIs(kettle.toMovable()))
        val bedroom = d.roomsOf(Home.ORIGIN).first { it.name == "Bedroom" }
        val bed = d.liveItems.first { it.originRoomId == bedroom.id && it.name == "Bed frame" }
        assertEquals("On the van (Trip 1)", d.whereIs(bed.toMovable()))
        // A new-home room holds what goes there.
        val lounge = d.roomsOf(Home.DEST).first { it.name == "Lounge" }
        assertEquals(listOf("Sofa", "Television"), d.liveItems.filter { it.destRoomId == lounge.id && it.disposition.name == "MOVE" }.map { it.name }.sorted())
        assertEquals("Lounge", d.room(lounge.id)?.name)
        // Search, across items and boxes, as the shared search screen asks it.
        assertEquals(listOf("Suitcase B2 · Passports and chargers"), d.search("passports").map { it.movable.title })
        assertEquals(listOf("Kettle"), d.search("kettle").map { it.movable.title })
    }

    @Test
    fun wrongCodesAreSaidInWords() {
        model.use("hello there")
        assertEquals(Words.NOT_AN_INVITE, (model.join as JoinStep.Start).note)
        model.use(android.syncCode())
        assertEquals(Words.SYNC_CODE_NOT_INVITE, (model.join as JoinStep.Start).note)
        model.paste().let { assertNull(it) }
        model.noteNothingToPaste()
        assertEquals(Words.NOTHING_TO_PASTE, (model.join as JoinStep.Start).note)

        // A code the Android phone did not make.
        val wrong = WifiSync.Invite.parse(android.invite())!!.copy(token = "WRONG234").encode()
        model.use(wrong)
        model.yes()
        waitFor("the refusal") { model.join is JoinStep.Failed }
        assertEquals("The code was not accepted", (model.join as JoinStep.Failed).report.title)
        assertNull((model.join as JoinStep.Failed).retry, "a refused code is not tried again")

        // An invite another phone has used already.
        val invite = android.invite()
        val other = AppModel(DesktopHooks(File(root, "other")), "", mainScope())
        other.use(invite)
        other.yes()
        waitFor("the other phone's join") { other.store != null }
        model.startAgain()
        model.use(invite)
        model.yes()
        waitFor("the refusal") { model.join is JoinStep.Failed }
        val used = (model.join as JoinStep.Failed).report
        assertEquals("This invite no longer works", used.title)
        assertNull(model.store)
    }

    @Test
    fun theLocalNetworkSwitchedOffIsSaidWithTheWayToSettings() {
        // A phone on the Wi-Fi (as far as WifiSync can tell) whose invite's address answers nobody.
        WifiSync.addresses = { listOf("10.0.0.2") }
        val nowhere = WifiSync.Invite.parse(android.invite())!!.copy(hosts = listOf("0.0.0.0"), port = 9).encode()
        hooks.network = LocalNetwork.DENIED
        model.use(nowhere)
        model.yes()
        waitFor("the answer") { model.join is JoinStep.Failed }
        val report = (model.join as JoinStep.Failed).report
        assertEquals(Words.DENIED, report)
        assertTrue(report.settings)
        assertEquals(1, hooks.asked)
        assertNotNull((model.join as JoinStep.Failed).retry, "trying again may help once it is switched on")

        // Allowed, and still nobody there: a different Wi-Fi. It was tried once more.
        hooks.network = LocalNetwork.ALLOWED
        model.retry()
        model.yes()
        waitFor("the answer") { model.join is JoinStep.Failed }
        assertEquals(Words.unreachable(couldBeDenied = false), (model.join as JoinStep.Failed).report)

        // iOS did not say: both reasons are given.
        hooks.network = LocalNetwork.UNKNOWN
        model.retry()
        model.yes()
        waitFor("the answer") { model.join is JoinStep.Failed }
        assertEquals(Words.unreachable(couldBeDenied = true), (model.join as JoinStep.Failed).report)

        // No Wi-Fi at all.
        WifiSync.addresses = { emptyList() }
        model.retry()
        model.yes()
        waitFor("the answer") { model.join is JoinStep.Failed }
        assertEquals(Words.NO_WIFI, (model.join as JoinStep.Failed).report)
    }

    @Test
    fun freshDataComesFromTheAndroidPhonesCodeAndNothingGoesBackButTheJoin() {
        joinAsAlex()
        // What joining wrote on the iPhone (the person's phone entry, when it joined) reaches the Android phone at the
        // first sync after it; nothing else ever does.
        model.use(android.syncCode())
        waitFor("the first sync") { (model.fresh as? Fresh.Idle)?.report != null }
        assertEquals("Up to date", (model.fresh as Fresh.Idle).report!!.title)
        val before = android.store.data.value
        val alex = before.person(android.alexId)!!
        assertTrue(alex.joinedAt > 0)
        assertEquals(listOf("iPhone"), alex.phones.map { it.name })

        // Something changes on the Android phone, and comes across.
        val hall = before.roomsOf(Home.ORIGIN).first { it.name == "Hall" }
        android.store.createItem("Umbrella stand", hall.id)
        val changed = android.store.data.value
        model.use(android.syncCode())
        waitFor("the second sync") { (model.fresh as? Fresh.Idle)?.report?.title == "Up to date" && iphoneData.liveItems.any { it.name == "Umbrella stand" } }
        assertEquals(listOf("Umbrella stand"), iphoneData.liveItems.filter { it.originRoomId == hall.id }.map { it.name })
        // The Android phone's move is as it was: the iPhone changed nothing in it.
        assertEquals(changed, android.store.data.value)
        assertTrue(android.store.data.value.refused.isEmpty())
        assertTrue(android.results.none { !it.ok }, "the Android phone saw no failure: ${android.results}")
    }

    @Test
    fun theAndroidPhoneScansTheIphonesCode() {
        joinAsAlex()
        WifiSync.addresses = { listOf("127.0.0.1") }
        model.showCode()
        val showing = model.fresh as Fresh.Showing
        assertTrue(hooks.awake, "the screen stays on while the code is shown")
        val code = WifiSync.Invite.parse(showing.code)!!
        assertNull(code.personId)
        // The Android phone scans it (Sync and backup, Scan sync code) and syncs as a phone of the move.
        val hall = android.store.data.value.roomsOf(Home.ORIGIN).first { it.name == "Hall" }
        android.store.createItem("Coat hooks", hall.id)
        val r = WifiSync.join(android.store, code, letNewPhonesIn = android.store.isAlone())
        assertTrue(r.ok, "Android's sync: ${r.error}")
        waitFor("the iPhone's report") { (model.fresh as? Fresh.Showing)?.report != null }
        assertEquals("Up to date", (model.fresh as Fresh.Showing).report!!.title)
        assertTrue(iphoneData.liveItems.any { it.name == "Coat hooks" })
        // Leaving Get fresh data stops showing the code (FreshScreen does this as it goes).
        model.stopCode()
        assertTrue(model.fresh is Fresh.Idle)
        assertTrue(!hooks.awake)
    }

    @Test
    fun leavingTakesTheMoveOffTheIphone() {
        joinAsAlex()
        model.askToLeave(true)
        assertTrue(model.askingToLeave)
        model.leave()
        waitFor("leaving") { model.store == null && !model.busy }
        assertEquals("Sample move was taken off this iPhone. The Android phone still has it.", (model.join as JoinStep.Start).note)
        assertTrue(model.moves.cards.value.isEmpty())
        assertTrue(File(hooks.dataDir, "moves").listFiles().orEmpty().isEmpty(), "no move folder is left")
        // The Android phone still has everything.
        assertEquals("Sample move", android.store.data.value.move.name)
    }

    @Test
    fun theOnlyOrganisersOtherPhoneLeavesToo() {
        // "Move to a new phone" for Sam, the move's only Organiser: Moves.remove keeps such a move on a phone, but
        // this iPhone never changes the move, and Sam has the Android phone.
        val sam = android.store.me!!.id
        model.use(android.invite(sam))
        model.yes()
        waitFor("the join") { model.store != null || model.join is JoinStep.Failed }
        assertEquals(Role.ORGANISER, model.store?.myRole)
        assertTrue(model.store!!.lastOrganiserOfShared())
        model.leave()
        waitFor("leaving") { model.store == null && !model.busy }
        assertTrue(model.moves.cards.value.isEmpty())
        assertTrue(File(hooks.dataDir, "moves").listFiles().orEmpty().isEmpty())
        assertTrue(File(hooks.noBackupDir, "moves").listFiles().orEmpty().isEmpty())
        // Opened again, the iPhone holds no move.
        assertNull(Moves(hooks.dataDir, hooks.noBackupDir).current.value)
    }
}
