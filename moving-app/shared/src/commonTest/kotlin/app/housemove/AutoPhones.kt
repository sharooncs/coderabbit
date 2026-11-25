package app.housemove

import app.housemove.data.File
import app.housemove.data.Moves
import app.housemove.data.Outcome
import app.housemove.data.Person
import app.housemove.data.Role
import app.housemove.data.Store
import app.housemove.data.currentTimeMillis
import app.housemove.sync.AutoSync
import app.housemove.sync.LocalDiscovery
import app.housemove.sync.LocalLan
import app.housemove.sync.WifiSync
import kotlin.test.assertTrue
import kotlin.test.fail

/*
 * Phones of the tests of syncing by itself: each keeps its moves as the app does ([Moves], in folders of its own), and
 * has its own engine ([AutoSync]) on one in-process network ([LocalLan], for Bonjour), with real sockets on 127.0.0.1
 * and the timings made short.
 */

/** One phone: [name] is its person's name, and its phone is "[name]'s phone". */
internal class AutoPhone(root: File, val name: String, lan: LocalLan, engineOn: Boolean = true) {
    val moves = Moves(File(root, "$name/files"), File(root, "$name/no-backup"), deviceName = { "$name's phone" })
    val store: Store get() = moves.current.value ?: fail("$name's phone has no move")
    val engine = AutoSync(LocalDiscovery(lan), { moves.current.value }, { moves.phone.settings.value.autoSync }, loopbackOnly = true).apply {
        tickMs = 20
        periodMs = 400
        fallbackMs = 1_000
        debounceMs = 100
        firstBackoffMs = 150
        maxBackoffMs = 600
        busyMinMs = 30
        busyMaxMs = 120
    }
    private var host: WifiSync.Host? = null

    init {
        if (engineOn) engine.resume()
    }

    /** Start a move as its founder and Organiser. */
    fun found(move: String) {
        moves.create(move, yourName = name)
    }

    /** Add [who] to the move as [role] and give them this phone's invite. */
    fun invite(who: AutoPhone, role: Role, confirm: Boolean = false): Person {
        val p = (store.addPerson(who.name, role) as Outcome.Ok).value
        val h = host ?: WifiSync.host(store, letNewPhonesIn = false, confirmJoins = confirm) {}.also { host = it }
        val invite = (h.inviteFor(p.id, listOf("127.0.0.1")) as Outcome.Ok).value
        val r = who.moves.join(who.moves.readInvite(invite.encode()) ?: fail("${who.name} could not read the invite"))
        assertTrue(r.ok && r.joinedAs != null, "${who.name} joined: ${r.error}")
        return p
    }

    /** Sync by code: this phone shows its sync code and [other] scans it. */
    fun codeSync(other: AutoPhone): WifiSync.Result {
        val h = host ?: WifiSync.host(store, letNewPhonesIn = false) {}.also { host = it }
        return WifiSync.join(other.store, h.invite(listOf("127.0.0.1")), letNewPhonesIn = false)
    }

    fun close() {
        engine.close()
        host?.stop()
        moves.flush()
        for (c in moves.cards.value) moves.store(c.moveId)?.close()
    }
}

/** Waits up to [ms] for [done], then fails saying [what]. */
internal fun waitUntil(what: String, ms: Long = 20_000, done: () -> Boolean) {
    val until = currentTimeMillis() + ms
    while (!done()) {
        if (currentTimeMillis() > until) fail("timed out waiting for $what")
        sleepMillis(20)
    }
}

/** Holds for [ms] (that something does not happen). */
internal fun holds(what: String, ms: Long, check: () -> Boolean) {
    val until = currentTimeMillis() + ms
    while (currentTimeMillis() < until) {
        if (!check()) fail(what)
        sleepMillis(20)
    }
}
