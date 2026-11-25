package app.housemove.sync

import app.housemove.data.Action
import app.housemove.data.AppData
import app.housemove.data.AppJson
import app.housemove.data.CountDownLatch
import app.housemove.data.File
import app.housemove.data.HIDDEN_FROM_HELPERS
import app.housemove.data.Lock
import app.housemove.data.MailboxAddress
import app.housemove.data.Photo
import app.housemove.data.Role
import app.housemove.data.SCHEMA
import app.housemove.data.Shrink
import app.housemove.data.Store
import app.housemove.data.Update
import app.housemove.data.ZipWriter
import app.housemove.data.awaitMillis
import app.housemove.data.countDown
import app.housemove.data.currentTimeMillis
import app.housemove.data.delete
import app.housemove.data.exists
import app.housemove.data.hasOrganiser
import app.housemove.data.isFile
import app.housemove.data.isSafeFileName
import app.housemove.data.merge
import app.housemove.data.phoneEntry
import app.housemove.data.refusal
import app.housemove.data.sentTo
import app.housemove.data.startThread
import app.housemove.data.synchronized
import app.housemove.data.toHex
import app.housemove.data.toUtf8
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.encodeToString
import kotlin.concurrent.Volatile

/**
 * Syncing through the internet mailbox (design/encryption.md, "Mailbox as built"), beside syncing by itself on the Wi-Fi
 * ([AutoSync]), not instead of it: while HouseMove is open ([resume] to [pause]) and the switch is on ([enabled]), for the
 * move in use ([currentStore]), once an Organiser has set up the move's mailbox ([setUp]):
 *
 * - **Registering.** This phone registers a mailbox of its own on the move's mailbox program (a random 128-bit ID, and a
 *   read secret only this phone and the program know), with the house token, and puts its address on its entry in the
 *   move, which reaches the other phones with the data.
 * - **Sending.** A little after a change ([debounceMs]), and whenever it has something another phone does not, it seals a
 *   message for each other phone of the move that has a mailbox and a pinned key ([Envelope], HPKE mode_auth): what an
 *   update file holds, as that phone's person may see it, with small copies of this phone's person's photos that phone
 *   has not acknowledged yet. Never to a phone that is not one of the move (removed, retired), and never to a key that is
 *   not pinned.
 * - **Receiving.** At the start and every [pollMs], it reads its mailbox: each envelope is opened with HPKE, only with the
 *   pinned key of a phone of the move, merged as [Update.open] merges, and deleted (acknowledged) on the program.
 *
 * Messages carry the whole of the data each time (merging is idempotent and does not care about order, so one late,
 * repeated or lost changes nothing wrong), and their numbers and acknowledgements make photos go again until they are
 * acknowledged. [status] says how it stands, in the words of [Status.line], for Sync and backup (Android) and "Get fresh
 * data" (iPhone).
 */
class MailboxSync(
    private val currentStore: () -> Store?,
    private val enabled: () -> Boolean,
    private val clock: () -> Long = ::currentTimeMillis,
) {
    /** How the phone reaches the mailbox: the platform's HTTPS, or in tests a stand-in. */
    @Volatile
    internal var http: HttpTransport = Http.platform

    enum class State {
        /** The switch is off on this phone. */
        OFF,

        /** The move has no mailbox. */
        NOT_SET_UP,

        /** The move has one, but this phone has not got its code (the house token) yet. */
        NEEDS_CODE,

        /** Reading or sending now. */
        WORKING,

        /** The last round went through. */
        UP_TO_DATE,

        /** This phone uses the mailbox, and no other phone of the move does yet. */
        ALONE,

        /** The last round did not go through: [Status.problem] says why. */
        PROBLEM,
    }

    enum class Problem {
        UNREACHABLE, CODE_REFUSED, FULL, WAIT, BROKEN, TOO_BIG, NO_KEY, NOT_HTTPS,
    }

    /** How the mailbox stands: [state], [at] (the last round that went through), and [problem] with when it was ([tried]). */
    data class Status(val state: State, val at: Long = 0, val problem: Problem? = null, val tried: Long = 0) {
        /** The status line: "Up to date over the internet · 2 min ago", "Cannot reach the internet mailbox"… */
        fun line(now: Long): String = when (state) {
            State.OFF -> "Internet mailbox is off on this phone"
            State.NOT_SET_UP -> "No internet mailbox yet"
            State.NEEDS_CODE -> "The internet mailbox needs its code"
            State.WORKING -> "Syncing over the internet…"
            State.UP_TO_DATE -> "Up to date over the internet · ${AutoSync.Status.ago(at, now)}"
            State.ALONE -> "Internet mailbox ready"
            State.PROBLEM -> when (problem) {
                Problem.UNREACHABLE, null -> "Cannot reach the internet mailbox"
                Problem.CODE_REFUSED -> "The internet mailbox did not take its code"
                Problem.FULL -> "The internet mailbox is full"
                Problem.WAIT -> "The internet mailbox asked to wait"
                Problem.BROKEN -> "The internet mailbox had a problem"
                Problem.TOO_BIG -> "This move is too big for the internet mailbox"
                Problem.NO_KEY -> "This phone cannot seal messages just now"
                Problem.NOT_HTTPS -> "The internet mailbox's address is not right"
            }
        }

        /** One hint under the line, when there is something to say. [organiser]: this phone's person may set the mailbox up. */
        fun hint(now: Long, organiser: Boolean): String? = when (state) {
            State.OFF -> "Phones away from this Wi-Fi keep up to date through it. Switch it on to use it here."
            State.NOT_SET_UP -> if (organiser) "Scan the code the mailbox laptop showed, so phones away from this Wi-Fi keep up to date."
            else "An Organiser can set one up, so phones away from this Wi-Fi keep up to date."
            State.NEEDS_CODE -> "Sync once on the same Wi-Fi with a phone that uses it, or scan the mailbox's code."
            State.WORKING, State.UP_TO_DATE -> null
            State.ALONE -> "No other phone of this move uses it yet."
            State.PROBLEM -> when (problem) {
                Problem.UNREACHABLE, null -> "Check this phone is online. If it keeps happening, the laptop with the mailbox may be off or asleep."
                Problem.CODE_REFUSED -> "Scan the code the mailbox laptop shows again."
                Problem.FULL -> "It makes room as phones read their messages. If it stays full, ask whoever looks after the laptop."
                Problem.WAIT -> "This phone tries again in a few minutes."
                Problem.BROKEN -> "This phone tries again in a few minutes."
                Problem.TOO_BIG -> "Sync on the same Wi-Fi instead."
                Problem.NO_KEY -> "Close HouseMove, open it again, and it tries once more."
                Problem.NOT_HTTPS -> "An Organiser scans the mailbox's code again."
            }?.let { if (at > 0 && problem != Problem.NOT_HTTPS) "$it Last up to date ${AutoSync.Status.ago(at, now)}." else it }
        }
    }

    private val _status = MutableStateFlow(Status(State.NOT_SET_UP))
    val status: StateFlow<Status> = _status

    /** Called after a message was taken in, on the engine's thread. */
    @Volatile
    var onTookIn: (Store, Update.Opened) -> Unit = { _, _ -> }

    // How often things happen, in milliseconds. Tests make them short.
    @Volatile internal var tickMs = 1_000L
    @Volatile internal var pollMs = 60_000L
    @Volatile internal var debounceMs = 8_000L
    @Volatile internal var ackMs = 15_000L
    @Volatile internal var firstBackoffMs = 30_000L
    @Volatile internal var maxBackoffMs = 600_000L
    @Volatile internal var resendMs = 30 * 60_000L
    @Volatile internal var missingMs = 10 * 60_000L

    /** What the last round did, for a test that timed out to say. */
    @Volatile internal var lastNote = ""

    private val lock = Lock()
    private var running = false
    private var closed = false
    private var started = false

    @Volatile
    private var wake = CountDownLatch(1)

    @Volatile
    private var pollNow = true

    // The engine's own state, on its one thread.
    private var forStore: Store? = null
    private var nextPoll = 0L
    private var waitUntil = 0L
    private var failures = 0
    private var lastData: AppData? = null
    private var lastChange = 0L
    private var lastOk = 0L
    private val peerWait = HashMap<String, Long>()

    /** HouseMove came to the front: read the mailbox now, and send what is due. */
    fun resume() {
        synchronized(lock) {
            if (closed) return
            running = true
            if (!started) {
                started = true
                startThread("mailbox") { loop() }
            }
        }
        pollNow = true
        poke()
    }

    /** HouseMove left the front: nothing more until it is back. A request under way finishes. */
    fun pause() {
        synchronized(lock) { running = false }
        poke()
    }

    fun close() {
        synchronized(lock) {
            closed = true
            running = false
        }
        poke()
    }

    /** Something changed (the switch, the code scanned, the move in use): look again now. */
    fun poke() {
        waitUntil = 0
        wake.countDown()
    }

    /** Tests: one round now, on the calling thread: read the mailbox and send what is due, as if any wait were over. */
    internal fun step() {
        pollNow = true
        waitUntil = 0
        tick()
    }

    private fun loop() {
        while (true) {
            val (run, end) = synchronized(lock) { running to closed }
            if (end) return
            try {
                if (run) tick()
            } catch (e: Exception) {
                lastNote = "error: ${e::class.simpleName}"
            }
            val latch = CountDownLatch(1)
            wake = latch
            latch.awaitMillis(if (run) tickMs else 60_000L)
        }
    }

    private fun publish(s: Status) {
        _status.value = s
    }

    private fun problem(p: Problem, now: Long) {
        failures++
        val wait = minOf(maxBackoffMs, firstBackoffMs shl minOf(failures - 1, 20))
        waitUntil = now + wait
        publish(Status(State.PROBLEM, lastOk, p, now))
    }

    private fun problemOf(a: HttpAnswer): Problem = when (a.status) {
        0 -> Problem.UNREACHABLE
        401 -> Problem.CODE_REFUSED
        413 -> Problem.TOO_BIG
        429 -> Problem.WAIT
        507 -> Problem.FULL
        else -> Problem.BROKEN
    }

    private fun tick() {
        val now = clock()
        val store = currentStore()
        if (store == null) {
            publish(Status(State.NOT_SET_UP))
            return
        }
        if (store !== forStore) {
            forStore = store
            nextPoll = 0
            waitUntil = 0
            failures = 0
            lastData = null
            lastOk = 0
            peerWait.clear()
        }
        val me = store.phone.id
        val d = store.data.value
        if (!enabled() || WifiSync.phone24(me)) {
            stopUsing(store, now)
            publish(Status(State.OFF))
            return
        }
        if (d.mailbox.url.isBlank()) {
            stopUsing(store, now)
            publish(Status(State.NOT_SET_UP))
            return
        }
        val url = MailboxCode.cleanUrl(d.mailbox.url) ?: return publish(Status(State.PROBLEM, problem = Problem.NOT_HTTPS))
        val crypto = noiseCrypto()
        val mine = store.phone.keys.noiseKey()
        if (crypto == null || mine == null) return publish(Status(State.PROBLEM, problem = Problem.NO_KEY))
        if (now < waitUntil) return
        val client = MailboxClient(url, http)

        // Registered on this mailbox, with this phone's address on its entry.
        var reg = store.mailboxState.registration()?.takeIf { MailboxCode.cleanUrl(it.first) == url }
        if (reg == null) {
            reg = register(store, client, url, null, now) ?: return
        }
        val (_, id, secret) = reg
        val entry = d.phoneEntry(me)?.second?.mailbox
        if (entry == null || entry.url != url || entry.id != id) store.noteMyMailbox(MailboxAddress(url, id, now))

        val working = _status.value.state != State.UP_TO_DATE && _status.value.state != State.ALONE
        if (working && lastOk == 0L) publish(Status(State.WORKING, lastOk))

        // Read this phone's mailbox: at the start, every [pollMs], and when asked.
        if (pollNow || now >= nextPoll) {
            pollNow = false
            if (!poll(store, client, url, id, secret, now)) return
            nextPoll = now + pollMs
        }
        // Send what is due.
        val peers = sendDue(store, client, url, now, crypto, mine) ?: return
        failures = 0
        lastOk = now
        publish(Status(if (peers == 0) State.ALONE else State.UP_TO_DATE, lastOk))
    }

    /**
     * Registers this phone's mailbox on [url]: [again] (url, id, secret) when the program no longer has it, else a new one.
     * Needs the house token. Returns the registration, or null when it could not (the status then says why).
     */
    private fun register(store: Store, client: MailboxClient, url: String, again: Triple<String, String, String>?, now: Long): Triple<String, String, String>? {
        val access = store.mailboxState.access(url)
        if (access == null) {
            publish(Status(State.NEEDS_CODE))
            return null
        }
        val id = again?.second ?: Strong.bytes(16).toHex()
        val secret = again?.third ?: Strong.bytes(32).toHex()
        publish(Status(State.WORKING, lastOk))
        val a = client.register(id, secret, access.token)
        lastNote = "register ${a.status} ${a.problem}"
        return when (a.status) {
            200, 201 -> {
                store.mailboxState.registered(url, id, secret, now)
                store.noteMyMailbox(MailboxAddress(url, id, now))
                Triple(url, id, secret)
            }
            409 -> {
                // That ID is someone else's with another secret: a new one next time.
                store.mailboxState.forgetRegistration()
                problem(Problem.BROKEN, now)
                null
            }
            else -> {
                problem(problemOf(a), now)
                null
            }
        }
    }

    /**
     * This phone stops using the mailbox (the switch is off, or the move has none): its address is taken off its entry, the
     * other phones are told so in one last message each (so they stop sending here at once), and its mailbox is deleted.
     */
    private fun stopUsing(store: Store, now: Long) {
        val entry = store.data.value.phoneEntry(store.phone.id)?.second?.mailbox
        if (entry != null && entry.id.isNotEmpty()) store.noteMyMailbox(MailboxAddress("", "", now))
        val reg = store.mailboxState.registration() ?: return
        val client = MailboxClient(reg.first, http)
        val crypto = noiseCrypto()
        val mine = store.phone.keys.noiseKey()
        val url = MailboxCode.cleanUrl(reg.first)
        if (crypto != null && mine != null && url != null) {
            for (p in peersOf(store, store.data.value, url)) {
                try {
                    send(store, client, url, p, store.mailboxState.peer(p.phone), emptyList(), store.stampOf(store.data.value), now, crypto, mine)
                } catch (_: Exception) {
                }
            }
        }
        try {
            client.unregister(reg.second, reg.third)
        } catch (_: Exception) {
        }
        store.mailboxState.forgetRegistration()
        waitUntil = 0
        failures = 0
    }

    /** Reads every envelope waiting, takes in what opens, and deletes each. False when the program could not be read. */
    private fun poll(store: Store, client: MailboxClient, url: String, id: String, secret: String, now: Long): Boolean {
        val (a, list) = client.list(id, secret)
        lastNote = "list ${a.status} ${list?.size ?: "-"} ${a.problem}"
        when {
            a.status == 200 && list != null -> Unit
            a.status == 404 -> {
                // The program no longer has this mailbox (it was cleared, or not read for a long time): registered again.
                register(store, client, url, Triple(url, id, secret), now)
                return false
            }
            a.status == 403 -> {
                store.mailboxState.forgetRegistration()
                problem(Problem.BROKEN, now)
                return false
            }
            else -> {
                problem(problemOf(a), now)
                return false
            }
        }
        for (e in list!!) {
            if (e.size <= Envelope.MAX) {
                val f = client.fetch(id, secret, e.id)
                if (f.status == 200) take(store, f.body, now)
                else if (f.status != 404) {
                    problem(problemOf(f), now)
                    return false
                }
            }
            // Read (or not one HouseMove could open, or too large): deleted, which tells nobody anything.
            val gone = client.delete(id, secret, e.id)
            if (gone.status != 204 && gone.status != 200 && gone.status != 404) {
                problem(problemOf(gone), now)
                return false
            }
        }
        return true
    }

    /**
     * Opens [bytes] with the pinned key of a phone of the move and takes in what it holds as an update of format 2 is taken
     * in. An envelope that does not open (not sealed by a pinned phone of the move, changed, or not HouseMove's) changes
     * nothing. Returns whether it opened.
     */
    internal fun take(store: Store, bytes: ByteArray, now: Long): Boolean {
        val crypto = noiseCrypto() ?: return false
        val mine = store.phone.keys.noiseKey() ?: return false
        val before = store.data.value
        val me = store.phone.id
        val senders = store.pins.learn(before, me).mapNotNull { (phone, pin) -> pointOfKeyText(pin.key)?.let { phone to it } }.toMap()
        val opened = Envelope.open(crypto, mine, me, store.identity.value.all, senders, bytes) ?: return false
        val head = opened.head
        val from = head.from
        // Of another data layout: not taken (as a sync between phones of different versions is not).
        if (head.schema != SCHEMA || before.memberPhone(from) == null) return false
        val staged = LinkedHashMap<String, File>()
        try {
            val json = try {
                Update.readContents(store, memoryIn(opened.body), staged)
            } catch (_: Exception) {
                null
            } ?: return false
            val remote = try {
                AppJson.decodeFromString<AppData>(json)
            } catch (_: Exception) {
                return false
            }
            val result = Update.takeInMessage(store, from, opened.moveId, remote, staged)
            store.pins.markHpke(from)
            head.access?.let { store.mailboxState.offer(it, store.data.value.mailbox.url) }
            val peer = before.memberPhone(from)
            // Does the sender have everything of this phone's it may see? Then nothing needs to go back to it.
            val theyHaveIt = peer != null && merge(remote, before.sentTo(peer.role, peer.id)) == remote
            val after = store.stampOf(store.data.value)
            val gotNew = result.ok && (result.changes > 0 || result.photos > 0)
            store.mailboxState.updatePeer(from) { pb ->
                val delivered = pb.pending.filterValues { it.seq <= head.ack }.keys
                pb.copy(
                    heard = maxOf(pb.heard, head.seq), heardAt = now, got = maxOf(pb.got, head.ack),
                    owes = pb.owes || (gotNew && head.seq > pb.acked),
                    pending = pb.pending - delivered, delivered = (pb.delivered + delivered).distinct(),
                    sentStamp = if (theyHaveIt) maxOf(pb.sentStamp, after) else pb.sentStamp,
                )
            }
            try {
                onTookIn(store, result)
            } catch (_: Exception) {
            }
            return true
        } finally {
            staged.values.forEach { if (it.exists()) it.delete() }
        }
    }

    /** Another phone of the move this one sends to: its phone ID, its person, its mailbox ID and its pinned key. */
    private class Peer(val phone: String, val personId: String, val role: Role, val mailbox: String, val point: ByteArray)

    /** The phones of the move to send to: each with a mailbox on [url] and a pinned key. */
    private fun peersOf(store: Store, d: AppData, url: String): List<Peer> {
        val me = store.phone.id
        val pins = store.pins.learn(d, me)
        val out = ArrayList<Peer>()
        for (phone in d.memberPhones() - me) {
            val person = d.memberPhone(phone) ?: continue
            val box = d.phoneEntry(phone)?.second?.mailbox ?: continue
            if (box.id.isEmpty() || MailboxCode.cleanUrl(box.url) != url || !MailboxCode.isMailboxId(box.id)) continue
            val point = pins[phone]?.let { pointOfKeyText(it.key) } ?: continue
            out += Peer(phone, person.id, person.role, box.id, point)
        }
        return out
    }

    /** Sends each phone what is due to it. The number of phones there are to send to, or null when a send failed. */
    private fun sendDue(store: Store, client: MailboxClient, url: String, now: Long, crypto: app.housemove.data.PlatformCrypto, mine: NoiseKey): Int? {
        val d = store.data.value
        if (d !== lastData) {
            lastData = d
            lastChange = now
        }
        store.mailboxState.keepOnly(d.memberPhones())
        val peers = peersOf(store, d, url)
        val quiet = now - lastChange >= debounceMs
        val stamp = store.stampOf(d)
        for (p in peers) {
            if (now < (peerWait[p.phone] ?: 0L)) continue
            val pb = store.mailboxState.peer(p.phone)
            val photos = if (quiet) photosFor(store, d, p, pb, now) else emptyList()
            val due = (quiet && (stamp > pb.sentStamp || photos.isNotEmpty())) || (pb.owes && now - pb.heardAt >= ackMs)
            if (!due) continue
            if (!send(store, client, url, p, pb, photos, stamp, now, crypto, mine)) return null
        }
        return peers.size
    }

    /** The photos to send [p] small copies of: this phone's person's, whose files are here in full, that it has not acknowledged. */
    private fun photosFor(store: Store, d: AppData, p: Peer, pb: PeerBox, now: Long): List<Photo> {
        val me = store.me?.id ?: return emptyList()
        return d.livePhotos.filter { ph ->
            ph.by == me && isSafeFileName(ph.file) && ph.file !in pb.delivered && !store.isSmallCopy(ph.file) &&
                (p.role != Role.HELPER || ph.kind !in HIDDEN_FROM_HELPERS) &&
                (pb.pending[ph.file]?.let { now - it.at >= resendMs } ?: true) &&
                File(store.photoDir, ph.file).isFile
        }.sortedWith(compareBy({ it.createdAt }, { it.id }))
    }

    private fun send(
        store: Store, client: MailboxClient, url: String, p: Peer, pb: PeerBox, photos: List<Photo>, stamp: Long, now: Long,
        crypto: app.housemove.data.PlatformCrypto, mine: NoiseKey,
    ): Boolean {
        val d = store.data.value
        val seq = pb.out + 1
        // What that phone's person may see, as a sync sends it.
        val data = d.sentTo(p.role, p.personId)
        val zip = MemoryOut()
        val included = ArrayList<String>()
        val w = ZipWriter(zip.stream)
        w.put("data.json") { AppJson.encodeToString(data).toUtf8() }
        for (ph in photos) {
            val bytes = Shrink.copyOf(File(store.photoDir, ph.file)) ?: continue
            if (zip.size + bytes.size > PHOTO_BUDGET) break
            w.put("photos/" + ph.file) { bytes }
            included += ph.file
        }
        w.close()
        val body = zip.bytes()
        val head = Envelope.Head(store.phone.id, seq, now, pb.heard, SCHEMA, store.mailboxState.access(url))
        val sealed = Envelope.seal(crypto, mine, p.point, AutoSession.advertisedMove(store.identity.value), p.phone, head, body)
        if (sealed == null || sealed.size > Envelope.MAX) {
            problem(Problem.TOO_BIG, now)
            return false
        }
        publish(Status(State.WORKING, lastOk))
        val a = client.post(p.mailbox, sealed)
        lastNote = "post ${a.status} ${a.problem}"
        return when (a.status) {
            200, 201 -> {
                store.mailboxState.updatePeer(p.phone) {
                    it.copy(
                        out = seq, sentStamp = maxOf(it.sentStamp, stamp), sentAt = now, acked = maxOf(it.acked, pb.heard), owes = false,
                        pending = it.pending + included.associateWith { PhotoSent(seq, now) },
                    )
                }
                true
            }
            404 -> {
                // That phone's mailbox is not there (it is registering a new one): tried again later.
                peerWait[p.phone] = now + missingMs
                true
            }
            else -> {
                problem(problemOf(a), now)
                false
            }
        }
    }

    companion object {
        /** A message's photos stop at about this much, with the data: well inside the largest envelope. */
        internal const val PHOTO_BUDGET = 6 * 1024 * 1024

        /** Said when a scanned or pasted code is not the mailbox's. */
        const val NOT_A_CODE = "That is not an internet mailbox's code. Its code begins with HMBOX and comes from the mailbox laptop."

        /** Said to someone who may not set up the mailbox for the move. */
        const val ONLY_ORGANISERS = "Only an Organiser can set up the internet mailbox for this move."

        /** Is [text] an internet mailbox's code (`HMBOX:…`)? */
        fun isCode(text: String): Boolean = text.trim().startsWith(MailboxCode.PREFIX, ignoreCase = true) || text.trim().startsWith("hmbox:", ignoreCase = true)

        /**
         * The mailbox's code, scanned or pasted, on this phone's move [store]: an Organiser sets the move's mailbox to its
         * address (which every phone then gets with the data), and this phone keeps its house token, in the no-backup folder,
         * to register its own mailbox and to pass on inside encrypted syncs. Anyone else keeps the token when the code is for
         * the mailbox the move already has. Returns what to say, and whether it worked.
         */
        fun setUp(store: Store, code: String, now: Long = currentTimeMillis()): Pair<Boolean, String> {
            val access = MailboxCode.parse(code, now) ?: return false to NOT_A_CODE
            val d = store.data.value
            val current = MailboxCode.cleanUrl(d.mailbox.url)
            if (current != access.url) {
                if (store.data.value.refusal(store.me, Action.MOVE_SETTINGS) != null && d.hasOrganiser()) return false to ONLY_ORGANISERS
                val set = store.setMailbox(access.url)
                if (!set.ok) return false to (set.reason ?: ONLY_ORGANISERS)
            }
            store.mailboxState.offer(access, access.url)
            return true to if (current == access.url) "This phone can use the internet mailbox now." else
                "Internet mailbox set up. The other phones get it at their next sync, and keep up to date through it from then on."
        }
    }
}
