package app.housemove.sync

import app.housemove.data.AppData
import app.housemove.data.AtomicInteger
import app.housemove.data.CountDownLatch
import app.housemove.data.IOException
import app.housemove.data.Lock
import app.housemove.data.Store
import app.housemove.data.awaitMillis
import app.housemove.data.countDown
import app.housemove.data.currentTimeMillis
import app.housemove.data.decrementAndGet
import app.housemove.data.incrementAndGet
import app.housemove.data.startThread
import app.housemove.data.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.concurrent.Volatile
import kotlin.random.Random

/**
 * Syncing by itself on the same Wi-Fi (design/auto-sync.md): while HouseMove is open ([resume] to [pause]) and the
 * setting is on ([enabled]), this phone advertises the move in use ([currentStore]) through [discovery], listens for
 * phones of the move, and syncs with each one it finds ([AutoSession]): when it comes to the front, every two minutes,
 * shortly after a change made here, when the network changes, and at once after a code was used (to swap keys).
 * One sync by itself at a time, dialling or answering; a phone that fails is tried again later and later.
 *
 * [status] says, in the words of [Status.line], what it is doing, for the Sync and backup screen (Android) and "Get
 * fresh data" (iPhone). [loopbackOnly] listens on 127.0.0.1 alone (tests and the iPhone's demo mode).
 */
class AutoSync(
    private val discovery: Discovery,
    private val currentStore: () -> Store?,
    private val enabled: () -> Boolean,
    private val loopbackOnly: Boolean = false,
    private val clock: () -> Long = ::currentTimeMillis,
) {
    /** What syncing by itself is doing, for the status line. */
    enum class State { OFF, NO_WIFI, DENIED, LOOKING, SYNCING, UP_TO_DATE }

    /**
     * The status line's facts: the [state], the phone being synced with ([syncingWith], its person's name), the last
     * phone synced with and when ([lastWith], [lastAt], by code or by itself), whether this phone still needs one sync
     * by code before it can sync by itself ([needsCode]), and how many phones of the move it sees ([phones]).
     */
    data class Status(
        val state: State,
        val syncingWith: String = "",
        val lastWith: String = "",
        val lastAt: Long = 0,
        val needsCode: Boolean = false,
        val phones: Int = 0,
    ) {
        /** The status line: "Up to date with Asha's phone · 2 min ago", "Looking for the family's phones on this Wi-Fi"… */
        fun line(now: Long): String = when (state) {
            State.OFF -> "Sync by itself is off"
            State.NO_WIFI -> "Not on Wi-Fi"
            State.DENIED -> "HouseMove may not use your Wi-Fi"
            State.SYNCING -> "Syncing with ${phoneOf(syncingWith)}…"
            State.UP_TO_DATE -> "Up to date with ${phoneOf(lastWith)} · ${ago(lastAt, now)}"
            State.LOOKING -> "Looking for the family's phones on this Wi-Fi"
        }

        /** One hint under the line, when there is something to say. */
        fun hint(now: Long): String? = when {
            state == State.OFF -> "Phones sync when one shows its code and the other scans it."
            state == State.DENIED -> "Switch on Local Network for HouseMove in Settings to sync by itself."
            needsCode -> "Sync once with a code to start syncing by itself."
            state != State.UP_TO_DATE && lastAt > 0 -> "Last synced with ${phoneOf(lastWith)} ${ago(lastAt, now)}."
            else -> null
        }

        companion object {
            /** "Asha's phone", from its person's name; "another phone" when there is none. */
            fun phoneOf(name: String): String = name.trim().let { if (it.isEmpty()) "another phone" else "$it's phone" }

            /** How long ago [at] was, in words: "just now", "2 min ago", "3 hours ago", "2 days ago". */
            fun ago(at: Long, now: Long): String {
                val s = (now - at) / 1000
                return when {
                    s < 60 -> "just now"
                    s < 3600 -> "${s / 60} min ago"
                    s < 86_400 -> (s / 3600).let { if (it == 1L) "1 hour ago" else "$it hours ago" }
                    else -> (s / 86_400).let { if (it == 1L) "1 day ago" else "$it days ago" }
                }
            }
        }
    }

    private val _status = MutableStateFlow(Status(State.LOOKING))
    val status: StateFlow<Status> = _status

    /** Called after each sync by itself that went through, on the engine's thread or a listener's. */
    @Volatile
    var onSynced: (Store, WifiSync.Result) -> Unit = { _, _ -> }

    // How often things happen, in milliseconds (design/auto-sync.md, "When syncs happen"). Tests make them short.
    @Volatile internal var tickMs = 1_000L
    @Volatile internal var periodMs = 120_000L
    @Volatile internal var fallbackMs = 300_000L
    @Volatile internal var debounceMs = 5_000L
    @Volatile internal var firstBackoffMs = 15_000L
    @Volatile internal var maxBackoffMs = 600_000L
    @Volatile internal var busyMinMs = 3_000L
    @Volatile internal var busyMaxMs = 8_000L

    /** "Up to date" for this long after the last sync. */
    @Volatile internal var upToDateMs = 5 * 60_000L

    /** One sync by itself at a time, either way. */
    internal val slot = AutoSlot()

    private val lock = Lock()

    // Under [lock]: whether HouseMove is at the front, whether this engine is done for good, and the listening socket.
    private var running = false
    private var closed = false
    private var started = false
    private var listener: AutoListener? = null

    @Volatile
    private var wake = CountDownLatch(1)

    /** A phone found on the network, by its advertised name. Read and changed on the engine's thread only. */
    private class Peer(val name: String, val firstSeen: Long) {
        var host = ""
        var port = 0
        var lastTry = 0L
        var lastOk = 0L
        var failures = 0
        var busy = 0
        var nextTry = 0L
    }

    // The engine's own state, read and changed on its one thread only.
    private val peers = LinkedHashMap<String, Peer>()
    private var advertised: String? = null
    private var advertisedTag = ""
    private var browsing = false
    private var lastAddresses: List<String>? = null
    private var addressesAt = 0L
    private var seenMeetings = -1
    private var seenRing = -1
    private var lastData: AppData? = null
    private var meetingAt = 0L
    private var pushAt = 0L

    /** The move the engine works on; the listener answers for it too. */
    @Volatile
    private var forStore: Store? = null

    @Volatile
    private var peerCount = 0

    // Whom this phone synced with lately, by phone ID, and which phone each advertised name is. Under [lock]: the
    // listener's threads write them too.
    private val contact = HashMap<String, Long>()
    private val nameToPhone = HashMap<String, String>()

    @Volatile
    private var dialling: String? = null

    @Volatile
    private var answering: String? = null

    /** HouseMove came to the front: advertise, look and sync. */
    fun resume() {
        synchronized(lock) {
            if (closed) return
            running = true
            if (!started) {
                started = true
                startThread("auto-sync") { loop() }
            }
        }
        poke()
    }

    /** HouseMove left the front: stop advertising and listening at once. A sync under way finishes if the platform lets it. */
    fun pause() {
        synchronized(lock) {
            running = false
            stopNetworkLocked()
        }
        poke()
    }

    /** Stop for good (tests, and a model that is let go of). */
    fun close() {
        synchronized(lock) {
            closed = true
            running = false
            stopNetworkLocked()
        }
        poke()
    }

    /** Something changed (the setting, the move in use): look again now rather than at the next tick. */
    fun poke() {
        wake.countDown()
    }

    /** Stop advertising, looking and listening. Call holding [lock]. */
    private fun stopNetworkLocked() {
        try {
            discovery.stop()
        } catch (_: Exception) {
        }
        listener?.stop()
        listener = null
    }

    private fun loop() {
        while (true) {
            val (run, end) = synchronized(lock) { running to closed }
            if (end) {
                quiet()
                return
            }
            try {
                if (run) tick() else quiet()
            } catch (_: Exception) {
                // Whatever went wrong, the next tick tries again.
            }
            val latch = CountDownLatch(1)
            wake = latch
            latch.awaitMillis(if (run) tickMs else 60_000L)
        }
    }

    /** Stop the network side for now (no move, the setting off, no Wi-Fi, in the background); a later tick starts it again. */
    private fun quiet() {
        synchronized(lock) { stopNetworkLocked() }
        advertised = null
        browsing = false
        peers.clear()
        peerCount = 0
    }

    private fun tick() {
        val now = clock()
        val store = currentStore()
        if (store == null || !enabled()) {
            quiet()
            publish(if (store == null) State.LOOKING else State.OFF, store)
            return
        }
        if (store !== forStore) {
            // Another move is in use: advertise it, and start afresh.
            quiet()
            forStore = store
            lastData = null
            pushAt = 0
        }

        // The network, looked at every few seconds: no Wi-Fi, nothing to do; a change of address, advertise and look again.
        if (lastAddresses == null || now - addressesAt >= ADDRESSES_MS || now < addressesAt) {
            val addresses = if (discovery.needsWifi) WifiSync.addresses() else listOf("in-process")
            if (lastAddresses != null && addresses != lastAddresses) quiet()
            lastAddresses = addresses
            addressesAt = now
        }
        if (lastAddresses.isNullOrEmpty()) {
            quiet()
            publish(State.NO_WIFI, store)
            return
        }

        val identity = store.identity.value
        val tag = AutoSession.advertisedTag(identity, now)
        synchronized(lock) {
            if (!running) return
            val l = listener ?: AutoListener(loopbackOnly, { forStore }, slot, ::answerProven, ::answerEnded).also {
                listener = it
                it.start()
            }
            if (advertised == null || tag != advertisedTag) {
                val name = AutoSession.serviceName(tag, suffix())
                discovery.advertise(name, l.port)
                advertised = name
                advertisedTag = tag
            }
            if (!browsing) {
                discovery.browse()
                browsing = true
            }
        }

        // The phones of this move on the network now. One newly found (or found again) is synced with at once.
        val tags = AutoSession.tagsOf(identity, now)
        val seen = discovery.found().filter { it.name != advertised && AutoSession.tagOfService(it.name) in tags }
        peers.keys.retainAll(seen.map { it.name }.toSet())
        for (f in seen) {
            val p = peers.getOrPut(f.name) { Peer(f.name, now) }
            p.host = f.host
            p.port = f.port
        }
        peerCount = peers.size

        // A code was just used, or the keys changed: try everyone afresh.
        if (Meetings.version != seenMeetings) {
            if (seenMeetings >= 0) meetingAt = now
            seenMeetings = Meetings.version
            freshStart()
        }
        if (store.ring.version != seenRing) {
            seenRing = store.ring.version
            freshStart()
        }
        // A change made on this phone that no other phone has yet: pushed to everyone a little after the last one.
        val data = store.data.value
        if (data !== lastData) {
            if (lastData != null && store.stampOf(data) > store.device.value.sharedUpTo) pushAt = now
            lastData = data
        }

        val next = peers.values.filter { now >= it.nextTry && due(it, now) }.minByOrNull { it.lastTry }
        if (next != null && slot.take()) {
            try {
                dial(store, next)
            } finally {
                slot.release()
            }
        }
        publish(null, store)
    }

    private fun suffix(): String {
        val chars = "abcdefghjkmnpqrstuvwxyz23456789"
        return (1..4).map { chars[Random.nextInt(chars.length)] }.joinToString("")
    }

    private fun freshStart() {
        for (p in peers.values) {
            p.failures = 0
            p.nextTry = 0
        }
    }

    /** Is it time to sync with [p]? (design/auto-sync.md, "When syncs happen" and "Who dials") */
    private fun due(p: Peer, now: Long): Boolean {
        val mine = advertised ?: return false
        val last = maxOf(p.lastOk, synchronized(lock) { nameToPhone[p.name]?.let { contact[it] } } ?: 0L)
        return when {
            // Found since HouseMove came to the front or the network changed: at once.
            p.lastTry == 0L -> true
            // A code was just used: swap keys while the meeting lasts.
            p.lastTry < meetingAt -> true
            // A change made here: a little after the last one.
            pushAt > 0 && p.lastTry < pushAt -> now >= pushAt + debounceMs
            // The timed syncs: the phone whose name sorts first dials; the other only if it has not been called for long.
            mine < p.name -> now - last >= periodMs
            else -> now - maxOf(last, p.firstSeen) >= fallbackMs
        }
    }

    private fun dial(store: Store, p: Peer) {
        val d = AutoSession.dial(store, p.host, p.port, advertised ?: "") { person ->
            dialling = person.name
            publish(null, store)
        }
        dialling = null
        val t = clock()
        val r = d.result
        when {
            d.word == "BUSY" -> {
                // Already syncing with another phone: again soon, at a random moment, without counting it against this one.
                p.busy++
                p.nextTry = t + busyMinMs + Random.nextLong(maxOf(1L, busyMaxMs - busyMinMs))
                if (p.busy > 6) failed(p, t)
            }
            d.word == "OK" && r != null && r.listsSynced() -> {
                p.lastTry = t
                p.lastOk = t
                p.failures = 0
                p.busy = 0
                p.nextTry = 0
                val phone = d.peerPhone ?: ""
                synchronized(lock) {
                    contact[phone] = t
                    nameToPhone[p.name] = phone
                }
                synced(store, phone, r, t)
            }
            else -> failed(p, t)
        }
    }

    /** [p] could not be reached, or refused: tried again after 15 s, 30 s, 1 min… at most every 10 min, give or take a fifth. */
    private fun failed(p: Peer, t: Long) {
        p.lastTry = t
        p.busy = 0
        p.failures++
        val wait = minOf(maxBackoffMs, firstBackoffMs shl minOf(p.failures - 1, 20))
        p.nextTry = t + (wait * (0.8 + 0.4 * Random.nextDouble())).toLong()
    }

    /** A phone that dialled this one proved itself, and they are syncing. On a listener's thread. */
    private fun answerProven(person: String) {
        answering = person
        publish(null, forStore)
    }

    /** That sync ended ([a] null when it did not go through). On a listener's thread. */
    private fun answerEnded(store: Store, a: AutoSession.Answered?) {
        answering = null
        if (a != null && a.result.listsSynced()) {
            val t = clock()
            synchronized(lock) {
                contact[a.peerPhone] = t
                if (a.peerName.isNotEmpty()) nameToPhone[a.peerName] = a.peerPhone
            }
            synced(store, a.peerPhone, a.result, t)
        }
        publish(null, store)
        poke()
    }

    private fun synced(store: Store, phone: String, r: WifiSync.Result, t: Long) {
        val name = store.data.value.memberPhone(phone)?.name?.takeIf { it.isNotBlank() } ?: r.peerName
        store.setDevice { it.copy(lastSyncAt = t, lastSyncWith = name) }
        try {
            onSynced(store, r)
        } catch (_: Exception) {
        }
    }

    /** Puts out the status: [state] when given, else worked out from what is going on. */
    private fun publish(state: State?, store: Store?) {
        val now = clock()
        val dev = store?.device?.value
        val d = store?.data?.value
        val with = dialling ?: answering
        val s = state ?: when {
            discovery.denied -> State.DENIED
            with != null -> State.SYNCING
            dev != null && dev.lastSyncAt > 0 && now - dev.lastSyncAt < upToDateMs -> State.UP_TO_DATE
            else -> State.LOOKING
        }
        val needsCode = store != null && d != null && s != State.OFF && (d.memberPhones() - store.phone.id).isNotEmpty() &&
            store.ring.empty(d) && Meetings.recent(store.phone.id, store.identity.value.all).isEmpty()
        _status.value = Status(s, with ?: "", dev?.lastSyncWith ?: "", dev?.lastSyncAt ?: 0, needsCode, if (s == State.OFF) 0 else peerCount)
    }

    private companion object {
        /** How often the phone's addresses are looked at, to see the network change. */
        const val ADDRESSES_MS = 3_000L
    }
}


/** Did the two phones take in each other's lists? Also when only some photos were still on their way ([WifiSync.PHOTOS_CUT]). */
internal fun WifiSync.Result.listsSynced(): Boolean = ok || error == WifiSync.PHOTOS_CUT

/**
 * The listening socket of syncing by itself: answers phones that dial this one, for the move in use ([store]), each on a
 * thread of its own, at most [MAX_AT_ONCE] at once ([AutoSession.answer] lets one of them sync at a time).
 */
internal class AutoListener(
    loopbackOnly: Boolean,
    private val store: () -> Store?,
    private val slot: AutoSlot,
    private val onProven: (String) -> Unit,
    private val onEnded: (Store, AutoSession.Answered?) -> Unit,
) {
    private val server = listeningSocket(loopbackOnly)
    val port: Int get() = server.localPort
    private val handling = AtomicInteger(0)

    @Volatile
    private var closed = false

    fun start() {
        startThread("auto-listen") {
            while (!closed) {
                val socket = try {
                    server.accept()
                } catch (_: IOException) {
                    break
                }
                if (handling.incrementAndGet() > MAX_AT_ONCE) {
                    handling.decrementAndGet()
                    try {
                        socket.close()
                    } catch (_: Exception) {
                    }
                    continue
                }
                startThread("auto-answer") {
                    val st = store()
                    var proven = false
                    var answered: AutoSession.Answered? = null
                    try {
                        socket.use { s ->
                            if (st != null) answered = AutoSession.answer(st, s, slot) { proven = true; onProven(it.name) }
                        }
                    } catch (_: Exception) {
                        // A stranger, a dropped connection: nothing was synced.
                    } finally {
                        handling.decrementAndGet()
                        if (proven && st != null) onEnded(st, answered)
                    }
                }
            }
        }
    }

    fun stop() {
        closed = true
        try {
            server.close()
        } catch (_: Exception) {
        }
    }

    companion object {
        const val MAX_AT_ONCE = 4
    }
}
