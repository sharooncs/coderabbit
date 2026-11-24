package app.housemove

import app.housemove.data.*
import app.housemove.sync.WifiSync
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Joining a move in 2.1: what an invite says before anything is sent, the hosting phone's person
 * confirming an Organiser's or a Decider's phone, the Helper code, the move checked before any data
 * moves, a phone that already holds the move, bringing your things, phones added and retired, and
 * phones on different versions.
 */
class JoiningTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var now = 1_000_000L
    private var count = 0
    private val local = listOf("127.0.0.1")
    private val open = ArrayList<WifiSync.Host>()

    private class Hosting(val host: WifiSync.Host, val results: LinkedBlockingQueue<WifiSync.Result>) {
        fun result(): WifiSync.Result = results.poll(15, TimeUnit.SECONDS) ?: throw AssertionError("the host reported nothing")
    }

    @Before
    fun quick() {
        WifiSync.confirmMs = 3_000
    }

    @After
    fun hangUp() {
        open.forEach { it.stop() }
        WifiSync.confirmMs = 90_000
    }

    private fun phoneDirs(): Pair<File, File> {
        val root = tmp.newFolder("phone" + count++)
        return File(root, "files") to File(root, "no_backup")
    }

    private fun moves(device: String = "Phone ${count + 1}", yourName: String = ""): Moves {
        val (files, noBackup) = phoneDirs()
        return Moves(files, noBackup, { device }) { now++ }.also { m -> if (yourName.isNotEmpty()) m.phone.updateSettings { it.copy(name = yourName) } }
    }

    private fun hosting(store: Store, clock: () -> Long = { now }, letNewPhonesIn: Boolean = true): Hosting {
        val results = LinkedBlockingQueue<WifiSync.Result>()
        return Hosting(WifiSync.host(store, clock, letNewPhonesIn, confirmJoins = true) { results.add(it) }.also { open += it }, results)
    }

    private fun sync(host: Store, joiner: Store): Pair<WifiSync.Result, WifiSync.Result> {
        val h = hosting(host)
        val r = WifiSync.join(joiner, h.host.invite(local))
        return h.result() to r
    }

    /** Wait for the hosting phone to show a phone waiting to be confirmed, then answer [right]. Returns what it showed. */
    private fun answer(h: WifiSync.Host, right: Boolean): LinkedBlockingQueue<WifiSync.JoinRequest> {
        val seen = LinkedBlockingQueue<WifiSync.JoinRequest>()
        thread(isDaemon = true) {
            val until = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < until) {
                val r = h.waiting.value.firstOrNull()
                if (r != null) {
                    seen.add(r)
                    h.confirm(r.id, right)
                    return@thread
                }
                Thread.sleep(5)
            }
        }
        return seen
    }

    /** [phone] joins [host]'s move as [name] by an invite, confirmed on the host when it needs to be. */
    private fun joined(phone: Moves, host: Store, name: String, role: Role): Store {
        val p = host.addPerson(name, role).valueOrNull()!!
        val h = hosting(host)
        val check = phone.readInvite(h.host.inviteFor(p.id, local).valueOrNull()!!.encode())!!
        if (role != Role.HELPER) answer(h.host, true)
        val r = phone.join(check)
        assertTrue(r.error, r.ok)
        assertTrue(h.result().ok)
        return phone.current.value!!
    }

    // ---- the invite says what it is before anything is sent -----------------------------------

    @Test
    fun anInviteSaysWhichMoveAndWhoAndNothingIsSentBeforeYes() {
        val asha = moves("Asha's phone").create("Maple Road", yourName = "Asha")
        val priya = asha.addPerson("Priya", Role.DECIDER).valueOrNull()!!
        // A stand-in for the hosting phone that counts who connects.
        val server = ServerSocket(0)
        val connections = AtomicInteger()
        thread(isDaemon = true) {
            try {
                while (true) server.accept().use { connections.incrementAndGet() }
            } catch (_: Exception) {
            }
        }
        val h = hosting(asha)
        val text = h.host.inviteFor(priya.id, local).valueOrNull()!!.copy(port = server.localPort).encode()
        val phone = moves("Priya's phone")
        val check = phone.readInvite(text)!!
        assertEquals("Join Maple Road as Priya, Decider?", check.question)
        assertEquals(asha.moveId, check.invite.moveId)
        assertEquals("Maple Road", check.invite.moveName)
        assertEquals(Role.DECIDER, check.invite.role)
        assertNull(check.card)
        Thread.sleep(200)
        assertEquals(0, connections.get())
        assertTrue(phone.cards.value.isEmpty())
        // The QR text reads back whole, and 2.0's shapes still read.
        assertEquals(check.invite, WifiSync.Invite.parse(check.invite.encode()))
        val plain = h.host.invite(local)
        assertEquals("Sync with Maple Road?", WifiSync.Invite.parse(plain.encode())!!.question())
        assertEquals(asha.moveId, WifiSync.Invite.parse(plain.encode())!!.moveId)
        assertNull(WifiSync.Invite.parse(plain.encode())!!.personId)
        assertEquals("Join Maple Road as a Helper?", h.host.helperCode(local).valueOrNull()!!.question())
        server.close()
    }

    // ---- confirming the phone that came -------------------------------------------------------

    @Test
    fun aDecidersPhoneIsConfirmedOnTheOrganisersPhoneBeforeAnyDataIsSent() {
        val asha = moves("Asha's phone").create("Maple Road", yourName = "Asha")
        asha.createBox("L001", null)
        val priya = asha.addPerson("Priya", Role.DECIDER).valueOrNull()!!
        val h = hosting(asha)
        val invite = h.host.inviteFor(priya.id, local).valueOrNull()!!
        val before = asha.data.value

        // Undo: nothing moves either way, and the invite can still be used.
        val wrong = moves("Someone else's phone")
        val seen = answer(h.host, right = false)
        var waited = false
        val r = wrong.join(wrong.readInvite(invite.encode())!!, onWaiting = { waited = true })
        assertTrue(waited)
        assertEquals("Joined: Priya, on Someone else's phone", seen.poll(5, TimeUnit.SECONDS)!!.text)
        assertEquals(WifiSync.UNDONE, r.error)
        assertEquals(WifiSync.UNDONE, h.result().error)
        assertTrue(wrong.cards.value.isEmpty())
        assertEquals(before, asha.data.value)
        assertEquals(0L, asha.data.value.person(priya.id)!!.joinedAt)

        // Nobody answers in time: the same.
        val late = moves("Late phone")
        assertEquals(WifiSync.NOT_CONFIRMED, late.join(late.readInvite(invite.encode())!!).error)
        assertEquals(WifiSync.NOT_CONFIRMED, h.result().error)
        assertEquals(before, asha.data.value)

        // Right: Priya's phone joins with the same invite.
        val phone = moves("Priya's phone")
        answer(h.host, right = true)
        val ok = phone.join(phone.readInvite(invite.encode())!!)
        assertTrue(ok.error, ok.ok)
        assertTrue(h.result().ok)
        val s = phone.current.value!!
        assertEquals(priya.id, s.me!!.id)
        assertEquals(asha.moveId, s.moveId)
        assertNotNull(s.data.value.findByLabel("L001"))
        assertEquals(listOf("Priya's phone"), asha.data.value.person(priya.id)!!.phones.map { it.name })
        // Used: nobody comes in on it again.
        val again = moves("Another phone")
        assertEquals(WifiSync.INVITE_EXPIRED, again.join(again.readInvite(invite.encode())!!).error)
        h.result()
    }

    @Test
    fun aHelpersInviteNeedsNoConfirming() {
        val asha = moves().create("Maple Road", yourName = "Asha")
        val hal = asha.addPerson("Hal", Role.HELPER).valueOrNull()!!
        val h = hosting(asha)
        val phone = moves("Hal's phone")
        var waited = false
        val r = phone.join(phone.readInvite(h.host.inviteFor(hal.id, local).valueOrNull()!!.encode())!!, onWaiting = { waited = true })
        assertTrue(r.error, r.ok)
        assertFalse(waited)
        assertTrue(h.host.waiting.value.isEmpty())
        h.result()
    }

    // ---- the Helper code ---------------------------------------------------------------------------

    @Test
    fun aHelperCodeLetsSeveralPhonesInUnderTheirOwnUniqueNamesForTenMinutes() {
        var time = 5_000_000L
        val asha = moves().create("Maple Road", yourName = "Asha")
        asha.addPerson("Sam", Role.DECIDER)
        val h = hosting(asha, clock = { time })
        val code = h.host.helperCode(local).valueOrNull()!!.encode()
        val sam = moves("Sam's phone", yourName = "Sam")
        val jo = moves("Jo's phone", yourName = "Jo")
        for (m in listOf(sam, jo)) {
            val r = m.join(m.readInvite(code)!!)
            assertTrue(r.error, r.ok)
            assertTrue(h.result().ok)
        }
        val samHere = sam.current.value!!
        assertEquals("Sam 2", samHere.me!!.name)
        assertEquals(Role.HELPER, samHere.myRole)
        assertEquals("Jo", jo.current.value!!.me!!.name)
        assertEquals(setOf("Sam 2", "Jo"), h.host.helpersJoined.value.map { it.name }.toSet())
        val names = asha.data.value.livePeople.map { it.name }
        assertEquals(names.size, names.toSet().size)
        assertTrue(h.host.waiting.value.isEmpty())

        // Removed, Sam's phone is refused with the code; so is a retired phone.
        assertTrue(asha.removePerson(samHere.me!!.id).ok)
        assertEquals(WifiSync.REMOVED_SELF, sam.join(sam.readInvite(code)!!).error)
        assertNotNull(h.result().error)
        val joPhone = jo.current.value!!
        assertTrue(asha.retirePhone(joPhone.me!!.id, jo.phone.id).ok)
        val r = WifiSync.join(joPhone, WifiSync.Invite.parse(code)!!)
        assertEquals(WifiSync.RETIRED_PHONE, r.error)
        h.result()
        // Ten minutes on, it no longer works.
        time += WifiSync.HELPER_CODE_MINUTES * 60_000L + 1
        val late = moves("Late phone", yourName = "Lee")
        assertEquals(WifiSync.INVITE_EXPIRED, late.join(late.readInvite(code)!!).error)
        h.result()
        assertTrue(late.cards.value.isEmpty())
    }

    // ---- the move is checked before any data moves ------------------------------------------------

    @Test
    fun twoPhonesOfDifferentMovesExchangeNothing() {
        val asha = moves().create("Maple Road", yourName = "Asha")
        asha.createBox("M1", null)
        val zed = moves().create("Elm Street", yourName = "Zed")
        zed.createBox("E1", null)
        val a = asha.data.value
        val z = zed.data.value
        for ((host, joiner) in listOf(asha to zed, zed to asha)) {
            val (hosted, joined) = sync(host, joiner)
            assertEquals(WifiSync.WRONG_MOVE, hosted.error)
            assertEquals(WifiSync.WRONG_MOVE, joined.error)
        }
        assertEquals(a, asha.data.value)
        assertEquals(z, zed.data.value)
        // An invite that names one move, shown by a phone of another (an old QR code on the screen, say), is refused too.
        val p = asha.addPerson("Hal", Role.HELPER).valueOrNull()!!
        val h = hosting(asha)
        val wrongMove = h.host.inviteFor(p.id, local).valueOrNull()!!.copy(moveId = zed.moveId)
        val phone = moves()
        assertEquals(WifiSync.WRONG_MOVE, phone.join(phone.readInvite(wrongMove.encode())!!).error)
        assertEquals(WifiSync.WRONG_MOVE, h.result().error)
        assertTrue(phone.cards.value.isEmpty())
    }

    @Test
    fun aPhoneThatHoldsTheMoveAlreadyJoinsItOnlyThroughAnOrganiserItKnows() {
        val asha = moves().create("Maple Road", yourName = "Asha")
        val halMoves = moves("Hal's phone")
        val hal = joined(halMoves, asha, "Hal", Role.HELPER)
        val dee = joined(moves(), asha, "Dee", Role.DECIDER)
        // Dee shows an invite to Maple Road (made while she was an Organiser, say): Hal's phone, which holds Maple Road, is not taken in.
        assertTrue(asha.setRole(dee.me!!.id, Role.ORGANISER).ok)
        assertTrue(sync(asha, dee).second.ok)
        val pat = dee.addPerson("Pat", Role.HELPER).valueOrNull()!!
        assertTrue(asha.setRole(dee.me!!.id, Role.DECIDER).ok)
        assertTrue(sync(asha, hal).second.ok)
        val h = hosting(dee)
        val check = halMoves.readInvite(h.host.inviteFor(pat.id, local).valueOrNull()!!.encode())!!
        assertEquals(hal.moveId, check.card!!.moveId)
        assertNull(check.bringOffer)
        val before = hal.data.value
        val r = halMoves.join(check)
        assertEquals(WifiSync.alreadyInAMove("Maple Road"), r.error)
        assertEquals(WifiSync.NOT_ADDED, h.result().error)
        assertEquals(before, hal.data.value)
        assertEquals(1, halMoves.cards.value.size)
        // Through Asha, an Organiser it knows, the same phone is handed on to Pat.
        val pat2 = asha.addPerson("Pat", Role.HELPER).valueOrNull()!!
        val byAsha = hosting(asha)
        val ok = halMoves.join(halMoves.readInvite(byAsha.host.inviteFor(pat2.id, local).valueOrNull()!!.encode())!!)
        assertTrue(ok.error, ok.ok)
        byAsha.result()
        assertEquals(pat2.id, halMoves.current.value!!.me!!.id)
        assertEquals(1, halMoves.cards.value.size)
    }

    // ---- bringing your things -----------------------------------------------------------------------

    @Test
    fun aPhoneAloneInItsOwnMoveIsOfferedToBringItsThingsAndKeepsTheOldMoveArchived() {
        val asha = moves().create("Maple Road", yourName = "Asha")
        val box = asha.createBox("L001", null).id
        // A phone tried out on its own first: three things in a move of its own.
        val samMoves = moves("Sam's phone")
        val own = samMoves.create("My things", yourName = "Sam")
        val mine = (1..3).map { own.createItem("Thing $it", null).id }
        val p1 = asha.addPerson("Sam", Role.HELPER).valueOrNull()!!
        val h = hosting(asha)
        val check = samMoves.readInvite(h.host.inviteFor(p1.id, local).valueOrNull()!!.encode())!!
        assertEquals(3, check.bringOffer)
        val r = samMoves.join(check, bringThings = true)
        assertTrue(r.error, r.ok)
        h.result()
        val sam = samMoves.current.value!!
        assertEquals(asha.moveId, sam.moveId)
        for (id in mine) {
            assertNotNull(sam.data.value.item(id))
            assertNotNull(asha.data.value.item(id))
        }
        assertNotNull(sam.data.value.box(box))
        // The old move is archived, not deleted, and still has its things.
        val old = samMoves.cards.value.first { it.moveId == own.moveId }
        assertTrue(old.archived)
        assertEquals(3, samMoves.store(own.moveId)!!.data.value.liveItems.size)

        // Without saying yes, nothing comes along and the old move stays as it is.
        val joMoves = moves("Jo's phone")
        val jos = joMoves.create("Jo's things", yourName = "Jo")
        val lamp = jos.createItem("Lamp", null).id
        val p2 = asha.addPerson("Jo", Role.HELPER).valueOrNull()!!
        val r2 = joMoves.join(joMoves.readInvite(h.host.inviteFor(p2.id, local).valueOrNull()!!.encode())!!)
        assertTrue(r2.error, r2.ok)
        h.result()
        assertNull(asha.data.value.item(lamp))
        assertNull(joMoves.current.value!!.data.value.item(lamp))
        assertFalse(joMoves.cards.value.first { it.moveId == jos.moveId }.archived)
        // The letters Jo's phone uses in its own move are not given to it again in Maple Road.
        assertNotEquals(jos.device.value.letter, joMoves.current.value!!.device.value.letter)

        // A move for just listing what you own is never offered.
        val liMoves = moves()
        val list = liMoves.create("What I own")
        list.setMove { it.copy(kind = MoveKind.INVENTORY) }
        list.createItem("Piano", null)
        val p3 = asha.addPerson("Li", Role.HELPER).valueOrNull()!!
        assertNull(liMoves.readInvite(h.host.inviteFor(p3.id, local).valueOrNull()!!.encode())!!.bringOffer)
    }

    // ---- phones added and retired -------------------------------------------------------------------

    @Test
    fun aSecondPhoneForSomeoneRetiresNothingAndARetiredPhoneIsHeardOnceOneWay() {
        val asha = moves("Asha's phone").create("Maple Road", yourName = "Asha")
        asha.createBox("L001", null)
        val oldMoves = moves("Sam's old phone")
        val old = joined(oldMoves, asha, "Sam", Role.HELPER)
        val sam = old.me!!
        // Sam gets a new phone: Asha lets it in as Sam too. Both are listed; neither is retired; both sync.
        val h = hosting(asha)
        val newMoves = moves("Sam's new phone")
        val r = newMoves.join(newMoves.readInvite(h.host.inviteFor(sam.id, local).valueOrNull()!!.encode())!!)
        assertTrue(r.error, r.ok)
        h.result()
        val new = newMoves.current.value!!
        assertEquals(sam.id, new.me!!.id)
        assertEquals(setOf("Sam's old phone", "Sam's new phone"), asha.data.value.person(sam.id)!!.phones.map { it.name }.toSet())
        assertTrue(asha.data.value.person(sam.id)!!.phones.none { it.isRetired })
        assertNotEquals(old.device.value.letter, new.device.value.letter)
        assertTrue(sync(asha, old).second.ok)
        assertTrue(sync(asha, new).second.ok)

        // The old phone is lost in a box. Asha retires it; meanwhile it packed a box.
        assertTrue(asha.retirePhone(sam.id, oldMoves.phone.id).ok)
        assertNotNull(asha.retirePhone(asha.me!!.id, asha.phone.id).reason)
        assertTrue(sync(asha, new).second.ok)
        old.createBox("OLD1", null)
        val oldBefore = old.data.value
        asha.createBox("L002", null)
        // Found again, it syncs once: its box comes in, nothing goes back to it.
        val (hosted, joinedR) = sync(asha, old)
        assertEquals(WifiSync.RETIRED_PHONE, joinedR.error)
        assertTrue(hosted.oneWay)
        assertNotNull(asha.data.value.findByLabel("OLD1"))
        assertEquals(oldBefore, old.data.value)
        assertNull(old.data.value.findByLabel("L002"))
        // A second time it is refused, and nothing comes in.
        old.createBox("OLD2", null)
        assertEquals(WifiSync.RETIRED_PHONE, sync(asha, old).second.error)
        assertNull(asha.data.value.findByLabel("OLD2"))
        // The other way round too: the new phone, which has heard, does not sync with it.
        assertTrue(sync(asha, new).second.ok)
        assertNotNull(sync(new, old).second.error)
        assertNull(new.data.value.findByLabel("OLD2"))
        // A retirement only counts from an Organiser.
        assertNotNull(new.retirePhone(asha.me!!.id, asha.phone.id).reason)
    }

    // ---- phones on different versions -------------------------------------------------------------

    @Test
    fun aPhoneOn20IsToldTheVersionsDifferAndThisPhoneOffersToShareTheApp() {
        // A stand-in for a phone on 2.0 showing its code: it reads the whole hello, which begins as 2.0's does, and answers VERSION.
        val server = ServerSocket(0)
        val said = LinkedBlockingQueue<String>()
        val old = thread(isDaemon = true) {
            try {
                while (true) server.accept().use { s ->
                    val din = DataInputStream(s.getInputStream().buffered())
                    val out = DataOutputStream(s.getOutputStream().buffered())
                    val magic = din.readUTF()
                    repeat(3) { din.readUTF() }
                    val schema = din.readInt()
                    repeat(2) { din.readUTF() }
                    said.add("$magic $schema")
                    out.writeUTF("VERSION"); out.flush()
                }
            } catch (_: Exception) {
            }
        }
        val mine = moves().create("Maple Road")
        val before = mine.data.value
        val r = WifiSync.join(mine, WifiSync.Invite(local, server.localPort, "TOKEN123"))
        assertEquals(WifiSync.VERSION_MISMATCH, r.error)
        assertTrue(r.shareApp)
        assertEquals("HMSYNC3 $SCHEMA", said.poll(5, TimeUnit.SECONDS))
        assertEquals(before, mine.data.value)
        server.close()
        old.join(2000)

        // A phone on 2.0 scanning this phone's code: 2.0's hello, with its data layout.
        val h = hosting(mine)
        Socket("127.0.0.1", h.host.port).use { s ->
            val out = DataOutputStream(s.getOutputStream().buffered())
            out.writeUTF("HMSYNC3"); out.writeUTF(h.host.token); out.writeUTF("Old phone"); out.writeUTF("B"); out.writeInt(4)
            out.writeUTF(""); out.writeUTF(""); out.flush()
            val din = DataInputStream(s.getInputStream().buffered())
            assertEquals("VERSION", din.readUTF())
        }
        val hosted = h.result()
        assertEquals(WifiSync.VERSION_MISMATCH, hosted.error)
        assertTrue(hosted.shareApp)
        assertEquals(4, hosted.peerSchema)
        assertEquals(before, mine.data.value)
    }
}
