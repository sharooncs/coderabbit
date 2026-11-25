package app.housemove

import app.housemove.data.AppJson
import app.housemove.data.File
import app.housemove.data.MailboxAddress
import app.housemove.data.Outcome
import app.housemove.data.Photo
import app.housemove.data.PhotoKind
import app.housemove.data.Role
import app.housemove.data.SCHEMA
import app.housemove.data.deleteRecursively
import app.housemove.data.isFile
import app.housemove.data.phoneEntry
import app.housemove.data.writeText
import app.housemove.sync.Envelope
import app.housemove.sync.LocalLan
import app.housemove.sync.MailboxCode
import app.housemove.sync.MailboxSync
import app.housemove.sync.WifiSync
import app.housemove.sync.memberPhone
import app.housemove.sync.noiseCrypto
import app.housemove.sync.noiseKey
import app.housemove.sync.pointOfKeyText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phones that are not on the same Wi-Fi keeping up to date through the internet mailbox (MailboxSync, design/encryption.md,
 * "Mailbox as built"), two and three phones in one process, each with its own folders, keys and engine, talking to an
 * in-process stand-in for the mailbox program ([MailboxStandIn], the same API as the Go program). The engines are driven
 * one round at a time ([MailboxSync.step]) with a clock of the test's own, so nothing waits. On the JVM in every test run,
 * and on the iPhone simulator in CI. MailboxGoServerTest plays the same over HTTP against the real Go program.
 */
class MailboxCommonTest {
    private val undoCrypto = useTestCryptoIfNone()
    private val root = newTempFolder()
    private val phones = ArrayList<BoxPhone>()
    private var now = 1_800_000_000_000L
    private val box = MailboxStandIn(clock = { now })

    /** A phone of the test, with its own Wi-Fi (so syncing by itself never meets the others) and its mailbox engine. */
    private inner class BoxPhone(name: String) {
        val p = AutoPhone(root, name, LocalLan(), engineOn = false)
        val engine = MailboxSync({ p.moves.current.value }, { p.moves.phone.settings.value.mailbox }, { now }).apply {
            http = box
            debounceMs = 0
            ackMs = 0
            resendMs = 60_000
        }
        val name get() = p.name
        val store get() = p.store
        val id get() = store.phone.id

        fun has(item: String) = store.data.value.liveItems.any { it.name == item }

        fun round() = engine.step()

        fun address(): MailboxAddress? = store.data.value.phoneEntry(id)?.second?.mailbox
    }

    private fun phone(name: String) = BoxPhone(name).also { phones += it }

    /** Every phone does a round, [times] times, the clock moving on a second each time. */
    private fun rounds(times: Int = 3) {
        repeat(times) {
            for (ph in phones) ph.round()
            now += 1_000
        }
    }

    @AfterTest
    fun cleanUp() {
        try {
            phones.forEach {
                it.engine.close()
                it.p.close()
            }
            root.deleteRecursively()
        } finally {
            WifiSync.phone24 = { false }
            undoCrypto()
        }
    }

    /**
     * Asha founds a move and sets up its mailbox (her phone registers its own at once, as the app does while it is open);
     * [others] join by invite and meet her once by code, as on the day they set up: the code sync carries the move's data
     * (with the mailbox's address and Asha's mailbox) and, inside the encrypted channel, the house token. Each then
     * registers its own mailbox, and the first message it sends Asha's phone carries its address there, and from there on
     * to everyone.
     */
    private fun move(vararg others: Pair<String, Role>): List<BoxPhone> {
        val asha = phone("Asha")
        asha.p.found("Maple Road")
        val (ok, said) = MailboxSync.setUp(asha.store, box.code)
        assertTrue(ok, said)
        asha.round()
        assertNotNull(asha.address(), "Asha's phone registered its mailbox: ${asha.engine.lastNote}")
        val rest = others.map { (name, role) ->
            phone(name).also {
                asha.p.invite(it.p, role)
                assertTrue(asha.p.codeSync(it.p).ok)
                it.round()
                assertNotNull(it.address(), "${it.name}'s phone registered its mailbox: ${it.engine.lastNote}")
            }
        }
        // Everyone meets everyone once, so every phone has every key pinned.
        for (a in rest) for (b in rest) if (a !== b) assertTrue(a.p.codeSync(b.p).ok)
        return listOf(asha) + rest
    }

    @Test
    fun theCodeAndTheAddress() {
        val access = MailboxCode.parse("HMBOX:1:${"a".repeat(64)}:https://example-laptop.example-tailnet.ts.net/", 5)
        assertEquals("https://example-laptop.example-tailnet.ts.net", access?.url)
        assertEquals("a".repeat(64), access?.token)
        assertNull(MailboxCode.parse("HMBOX:1:${"a".repeat(63)}:https://example-laptop.example-tailnet.ts.net", 5), "a short token")
        assertNull(MailboxCode.parse("HMBOX:1:${"a".repeat(64)}:http://example-laptop.example-tailnet.ts.net", 5), "not https")
        assertNull(MailboxCode.parse("HMBOX:1:${"a".repeat(64)}:https://user@example.ts.net", 5), "a user in the address")
        assertNull(MailboxCode.parse("HMSYNC:1.2.3.4:5:ABCDEFGH", 5))
        assertTrue(MailboxSync.isCode("hmbox:1:abc"))
        assertEquals("https://example-laptop.example-tailnet.ts.net", MailboxCode.cleanUrl("https://example-laptop.example-tailnet.ts.net/"))
        assertNull(MailboxCode.cleanUrl("https://example.ts.net/?x=1"))
        assertNull(MailboxCode.cleanUrl("ftp://example.ts.net"))
    }

    @Test
    fun twoPhonesKeepUpToDateThroughTheMailbox() {
        val (asha, priya) = move("Priya" to Role.DECIDER)
        // Both have the address in the data, and the token from the scan or from Asha, inside the encrypted sync.
        assertEquals(box.url, priya.store.data.value.mailbox.url)
        assertNotNull(priya.store.mailboxState.access(box.url), "Priya's phone got the house token from Asha's, encrypted")
        rounds(2)
        assertEquals(2, box.mailboxes().size, "each phone registered a mailbox of its own")
        assertEquals(box.url, asha.address()?.url)
        // Each knows the other's mailbox once the data says it: the next rounds bring it over.
        asha.store.createItem("Sofa", null)
        rounds(3)
        assertTrue(priya.has("Sofa"), "Asha's change reached Priya's phone over the internet")
        priya.store.createItem("Lamp", null)
        rounds(3)
        assertTrue(asha.has("Lamp"), "and the other way")
        for (ph in listOf(asha, priya)) assertEquals(MailboxSync.State.UP_TO_DATE, ph.engine.status.value.state, "${ph.name}: ${ph.engine.lastNote}")
        assertTrue(asha.engine.status.value.line(now).startsWith("Up to date over the internet · "))
        // Every envelope was read and deleted: nothing waits on the laptop.
        rounds(2)
        assertTrue(box.stored().values.all { it.isEmpty() }, "envelopes left: ${box.stored().mapValues { it.value.size }}")
        // The pins say each opens HPKE: update files between them are of format 2.
        assertTrue(asha.store.pins.opensHpke(priya.id) && priya.store.pins.opensHpke(asha.id))
    }

    @Test
    fun threePhonesAndOneRemoved() {
        val (asha, priya, omar) = move("Priya" to Role.DECIDER, "Omar" to Role.HELPER)
        rounds(4)
        asha.store.createItem("Desk", null)
        rounds(4)
        assertTrue(priya.has("Desk") && omar.has("Desk"))
        // A Helper is sent what a Helper may see, as at a sync: no costs.
        asha.store.saveCost(app.housemove.data.Expense(id = app.housemove.data.newId(), title = "Van hire", amount = 120.0))
        rounds(4)
        assertTrue(priya.store.data.value.liveExpenses.any { it.title == "Van hire" })
        assertFalse(omar.store.data.value.liveExpenses.any { it.title == "Van hire" }, "a Helper's phone is not sent costs")

        // Asha removes Omar: at once, her phone neither sends to his nor takes in what his sends.
        val omarPerson = asha.store.data.value.memberPhone(omar.id)!!
        assertTrue(asha.store.removePerson(omarPerson.id).ok)
        val before = box.stored()[omar.address()!!.id]?.size ?: 0
        asha.store.createItem("Secret plan", null)
        omar.store.createItem("From Omar after removal", null)
        // Only Asha and Omar take a round: Priya does not know yet.
        repeat(3) {
            asha.round()
            omar.round()
            now += 1_000
        }
        assertFalse(omar.has("Secret plan"), "nothing more went to a removed person's phone")
        assertFalse(asha.has("From Omar after removal"), "nothing from it is taken in")
        assertTrue((box.stored()[omar.address()!!.id]?.size ?: 0) <= before || !omar.has("Secret plan"))
        // Priya hears of it from Asha through the mailbox, then she too stops.
        rounds(4)
        assertTrue(priya.store.data.value.memberPhone(omar.id) == null, "Priya's phone knows Omar was removed")
        priya.store.createItem("After Omar left", null)
        rounds(4)
        assertTrue(asha.has("After Omar left"))
        assertFalse(omar.has("After Omar left"), "Priya's phone sends nothing to Omar's either")
        assertFalse(priya.has("From Omar after removal"))
    }

    /** A message sealed with a key that is not pinned for the phone it says it is from, or by a stranger, is not taken in. */
    @Test
    fun anUnpinnedKeyIsRefused() {
        val (asha, priya) = move("Priya" to Role.DECIDER)
        rounds(3)
        val crypto = noiseCrypto()!!
        val ashaBox = asha.address()!!.id
        val ashaPoint = pointOfKeyText(asha.store.phone.keys.publicText)!!
        // A stranger who knows Asha's mailbox ID and her public key (both are in the move's data) seals as "Priya".
        val stranger = crypto.generateKey().noiseKey()!!
        val forged = priya.store.data.value.copy(items = priya.store.data.value.items + app.housemove.data.Item(id = "forged", name = "Forged"))
        fun zip(d: app.housemove.data.AppData): ByteArray {
            val out = app.housemove.sync.MemoryOut()
            val w = app.housemove.data.ZipWriter(out.stream)
            w.put("data.json") { AppJson.encodeToString(app.housemove.data.AppData.serializer(), d).encodeToByteArray() }
            w.close()
            return out.bytes()
        }
        val head = Envelope.Head(priya.id, 99, now, 0, SCHEMA)
        val bad = Envelope.seal(crypto, stranger, ashaPoint, asha.store.moveId, asha.id, head, zip(forged))!!
        assertEquals(201, box.inject(ashaBox, bad))
        rounds(2)
        assertFalse(asha.has("Forged"), "a key that is not Priya's pin opens nothing")
        assertTrue(box.stored()[ashaBox].isNullOrEmpty(), "and it was deleted")

        // Priya's own key, but saying it is another phone: refused too.
        val lying = Envelope.seal(crypto, priya.store.phone.keys.noiseKey()!!, ashaPoint, asha.store.moveId, asha.id, head.copy(from = asha.id), zip(forged))!!
        box.inject(ashaBox, lying)
        rounds(2)
        assertFalse(asha.has("Forged"))

        // A phone of the move whose entry shows another key than its pin (a member acting badly put it there) is not sent to.
        val newKey = crypto.generateKey()
        val sentBefore = asha.store.mailboxState.peer(priya.id).out
        asha.store.update { d ->
            d.copy(people = d.people.map { p -> p.copy(phones = p.phones.map { if (it.id == priya.id) it.copy(key = app.housemove.data.base64Encode(newKey.publicDer), keyAt = it.keyAt + 1) else it }) })
        }
        asha.store.createItem("Only for the pinned key", null)
        rounds(3)
        // Asha still seals to Priya's pinned key, which is Priya's real one: it arrives, and nothing went to the forged key.
        assertTrue(priya.has("Only for the pinned key"))
        assertTrue(asha.store.mailboxState.peer(priya.id).out > sentBefore)
    }

    @Test
    fun tamperedRepeatedLateAndOutOfOrder() {
        val (asha, priya) = move("Priya" to Role.DECIDER)
        rounds(3)
        val priyaBox = priya.address()!!.id
        // A changed envelope: one bit anywhere and it does not open; it is deleted, and the next one brings the data.
        asha.store.createItem("Chair", null)
        asha.round()
        box.tamper(priyaBox) { b -> b.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() } }
        priya.round()
        assertFalse(priya.has("Chair"))
        asha.store.createItem("Table", null)
        rounds(3)
        assertTrue(priya.has("Chair") && priya.has("Table"), "the next message carried everything")

        // The laptop keeps copies and plays them back later, twice, and out of order: nothing goes back.
        val kept = ArrayList<ByteArray>()
        asha.store.createItem("Rug", null)
        asha.round()
        kept += box.stored().getValue(priyaBox)
        val rug = asha.store.data.value.liveItems.first { it.name == "Rug" }
        assertTrue(asha.store.tryDelete(rug).ok)
        asha.store.createItem("Mirror", null)
        asha.round()
        kept += box.stored().getValue(priyaBox)
        // Delivered newest first, then each again.
        val newest = kept.last()
        box.tamper(priyaBox) { null }
        box.inject(priyaBox, newest)
        priya.round()
        for (k in kept.reversed()) box.inject(priyaBox, k)
        for (k in kept) box.inject(priyaBox, k)
        priya.round()
        assertTrue(priya.has("Mirror"))
        assertFalse(priya.has("Rug"), "an old message played back late does not bring back what was deleted")
        rounds(3)
        assertEquals(asha.store.data.value.liveItems.map { it.name }.sorted(), priya.store.data.value.liveItems.map { it.name }.sorted())
    }

    @Test
    fun aFullMailboxAndALaptopThatIsOff() {
        val (asha, priya) = move("Priya" to Role.DECIDER)
        rounds(3)
        box.down = true
        asha.store.createItem("While off", null)
        rounds(2)
        assertEquals(MailboxSync.State.PROBLEM, asha.engine.status.value.state)
        assertEquals(MailboxSync.Problem.UNREACHABLE, asha.engine.status.value.problem)
        assertEquals("Cannot reach the internet mailbox", asha.engine.status.value.line(now))
        assertTrue(asha.engine.status.value.hint(now, true)!!.startsWith("Check this phone is online."))
        box.down = false
        rounds(3)
        assertTrue(priya.has("While off"), "caught up once the laptop was back")
        assertEquals(MailboxSync.State.UP_TO_DATE, asha.engine.status.value.state)

        box.mailboxQuota = 10
        asha.store.createItem("Too much", null)
        rounds(2)
        assertEquals(MailboxSync.Problem.FULL, asha.engine.status.value.problem)
        assertEquals("The internet mailbox is full", asha.engine.status.value.line(now))
        box.mailboxQuota = 64L * 1024 * 1024
        rounds(3)
        assertTrue(priya.has("Too much"))

        // The laptop lost its data folder: each phone registers again (it has the token) and carries on.
        box.wipe()
        asha.store.createItem("After the wipe", null)
        rounds(4)
        assertTrue(priya.has("After the wipe"))
    }

    /** Photos go as small copies until the other phone says it has them; the switch off takes the phone's mailbox away. */
    @Test
    fun photosAreAcknowledgedAndTheSwitchTurnsItOff() {
        val (asha, priya) = move("Priya" to Role.DECIDER)
        rounds(3)
        val item = asha.store.createItem("Bike", null)
        val file = asha.store.newPhotoFile()
        file.writeText("not really a jpeg")
        asha.store.addPhoto(item.id, PhotoKind.GENERAL, file)
        rounds(3)
        val photo = priya.store.data.value.livePhotos.single { it.ownerId == item.id }
        assertTrue(File(priya.store.photoDir, photo.file).isFile, "the photo arrived")
        assertTrue(priya.store.isSmallCopy(photo.file))
        rounds(3)
        assertTrue(photo.file in asha.store.mailboxState.peer(priya.id).delivered, "Priya's phone said it has it")
        assertTrue(asha.store.mailboxState.peer(priya.id).pending.isEmpty())

        // Priya switches it off on her phone: her mailbox goes, and her entry says so.
        priya.p.moves.phone.updateSettings { it.copy(mailbox = false) }
        rounds(2)
        assertEquals(MailboxSync.State.OFF, priya.engine.status.value.state)
        assertEquals("", priya.address()?.id)
        assertEquals(1, box.mailboxes().size)
        rounds(3)
        assertEquals("", asha.store.data.value.phoneEntry(priya.id)?.second?.mailbox?.id, "Asha's phone stops sending to it")
        priya.p.moves.phone.updateSettings { it.copy(mailbox = true) }
        asha.store.createItem("Back on", null)
        rounds(5)
        assertTrue(priya.has("Back on"))
    }

    /**
     * What the laptop sees: every request and answer that went over the wire, and every envelope it held, searched for the
     * move (its ID and name, things' names, people's names, phone IDs, public keys) and for the read secrets and the house
     * token where they do not belong. None is there. (MailboxGoServerTest does the same with the real program's files.)
     */
    @Test
    fun theMailboxSeesNothingOfTheMove() {
        val (asha, priya) = move("Priya" to Role.DECIDER)
        rounds(2)
        val held = ArrayList<ByteArray>()
        asha.store.createItem("Grandmother's walnut piano", null)
        asha.round()
        held += box.stored().values.flatten()
        priya.store.createItem("Blue Delft vase", null)
        priya.round()
        held += box.stored().values.flatten()
        rounds(3)
        assertTrue(priya.has("Grandmother's walnut piano") && asha.has("Blue Delft vase"))
        val needles = LinkedHashMap<String, ByteArray>()
        for (s in listOf("Grandmother's walnut piano", "Blue Delft vase", "Maple Road", asha.store.moveId, "Asha", "Priya")) needles[s] = s.encodeToByteArray()
        for (p in listOf(asha, priya)) {
            needles["${p.name}'s phone ID"] = p.id.encodeToByteArray()
            needles["${p.name}'s public key"] = p.store.phone.keys.publicText.encodeToByteArray()
            needles["${p.name}'s point"] = p.store.phone.keys.noiseKey()!!.point
        }
        fun search(where: String, hay: ByteArray) {
            for ((what, n) in needles) assertFalse(contains(hay, n), "$where holds $what")
        }
        assertTrue(held.size >= 2)
        for (h in held) search("an envelope the laptop held", h)
        val secrets = listOf(asha, priya).map { it.store.mailboxState.registration()!!.third }
        assertTrue(box.wire.size > 10)
        for ((r, a) in box.wire) {
            search("a request", r.body ?: ByteArray(0))
            search("an answer", a.body)
            search("an address", r.url.encodeToByteArray())
            val auth = r.headers["Authorization"] ?: ""
            if (box.token in auth) assertEquals("PUT", r.method, "the house token goes only to register")
            for (s in secrets) {
                assertFalse(r.url.contains(s), "a read secret in an address")
                assertFalse(r.method == "POST" && s in auth, "a read secret sent with a post")
            }
        }
    }

    private fun contains(hay: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || hay.size < needle.size) return false
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }

    @Test
    fun theWordsAndWhoMaySetItUp() {
        val (asha, priya) = move("Priya" to Role.HELPER)
        // A Helper cannot point the move at another mailbox.
        val other = MailboxStandIn(url = "https://another-laptop.example-tailnet.ts.net")
        val (ok, said) = MailboxSync.setUp(priya.store, other.code)
        assertFalse(ok)
        assertEquals(MailboxSync.ONLY_ORGANISERS, said)
        assertEquals(box.url, priya.store.data.value.mailbox.url)
        // The same mailbox's code is fine for anyone: it only gives the token.
        assertTrue(MailboxSync.setUp(priya.store, box.code).first)
        assertEquals(MailboxSync.NOT_A_CODE, MailboxSync.setUp(asha.store, "hello").second)
        val s = MailboxSync.Status(MailboxSync.State.UP_TO_DATE, now - 120_000)
        assertEquals("Up to date over the internet · 2 min ago", s.line(now))
        assertEquals("No internet mailbox yet", MailboxSync.Status(MailboxSync.State.NOT_SET_UP).line(now))
        assertTrue(MailboxSync.Status(MailboxSync.State.NOT_SET_UP).hint(now, organiser = true)!!.startsWith("Scan the code"))
        assertTrue(MailboxSync.Status(MailboxSync.State.NOT_SET_UP).hint(now, organiser = false)!!.startsWith("An Organiser"))
        // A phone with HouseMove 2.4 has no mailbox.
        WifiSync.phone24 = { it == priya.id }
        priya.round()
        assertEquals(MailboxSync.State.OFF, priya.engine.status.value.state)
    }
}
