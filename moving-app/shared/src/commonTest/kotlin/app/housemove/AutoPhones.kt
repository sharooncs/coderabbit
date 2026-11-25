package app.housemove

import app.housemove.data.AppJson
import app.housemove.data.File
import app.housemove.data.Moves
import app.housemove.data.Outcome
import app.housemove.data.Person
import app.housemove.data.Role
import app.housemove.data.Store
import app.housemove.data.base64Encode
import app.housemove.data.currentTimeMillis
import app.housemove.data.delete
import app.housemove.data.mkdirs
import app.housemove.data.writeText
import app.housemove.data.platformCrypto
import app.housemove.sync.AutoSession
import app.housemove.sync.AutoSync
import app.housemove.sync.LocalDiscovery
import app.housemove.sync.LocalLan
import app.housemove.sync.WifiSync
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.assertTrue
import kotlin.test.fail

/*
 * Phones of the tests of syncing by itself: each keeps its moves as the app does ([Moves], in folders of its own), and
 * has its own engine ([AutoSync]) on one in-process network ([LocalLan], for Bonjour), with real sockets on 127.0.0.1
 * and the timings made short. On the iPhone simulator their keys are the reference crypto's (useTestCryptoIfNone).
 */

/**
 * One phone: [name] is its person's name, and its phone is "[name]'s phone". With [keyScalar], its key pair is the one of
 * that private scalar (written where the phone keeps it before it first starts), so a test can know it.
 */
internal class AutoPhone(private val root: File, val name: String, lan: LocalLan, engineOn: Boolean = true, keyScalar: ByteArray? = null) {
    private val files = File(root, "$name/files")
    private val noBackup = File(root, "$name/no-backup")

    init {
        if (keyScalar != null) writeKey(keyScalar)
    }

    var moves = Moves(files, noBackup, deviceName = { "$name's phone" })
        private set
    val store: Store get() = moves.current.value ?: fail("$name's phone has no move")
    // The timings made short, but each well above a handshake (TestSpeed): two phones that dial each other at once are both
    // told BUSY, and with a pause shorter than a handshake they would meet again every time.
    val engine = AutoSync(LocalDiscovery(lan), { moves.current.value }, { moves.phone.settings.value.autoSync }, loopbackOnly = true).apply {
        val hs = TestSpeed.handshakeMs()
        tickMs = 20
        periodMs = 400 + 2 * hs
        fallbackMs = 1_000 + 4 * hs
        debounceMs = 100
        firstBackoffMs = 150 + hs
        maxBackoffMs = 600 + 4 * hs
        busyMinMs = 30 + 2 * hs
        busyMaxMs = 120 + 6 * hs
        // A handshake step may take as long as the slowest key agreements, with every phone of the test busy at once.
        AutoSession.stepMs = maxOf(5_000L, 8 * hs).toInt()
    }
    private var host: WifiSync.Host? = null

    init {
        if (engineOn) engine.resume()
    }

    private fun writeKey(d: ByteArray) {
        noBackup.mkdirs()
        val key = RefKey(d)
        val json = buildJsonObject {
            put("public", base64Encode(key.publicDer))
            put("private", base64Encode(RefPkcs8.of(key)))
            put("at", 1L)
        }
        File(noBackup, "phone-key.json").writeText(AppJson.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), json))
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

    /** This phone's code to show. */
    fun codeHost(): WifiSync.Host = host ?: WifiSync.host(store, letNewPhonesIn = false) {}.also { host = it }

    /** Sync by code: this phone shows its sync code and [other] scans it. */
    fun codeSync(other: AutoPhone): WifiSync.Result = WifiSync.join(other.store, codeHost().invite(listOf("127.0.0.1")), letNewPhonesIn = false)

    /**
     * HouseMove starts again on this phone ([newKey]: after its key file was lost, so it makes a new key, as an Android
     * phone does): its moves are opened afresh, as at every start.
     */
    fun restart(newKey: Boolean = false) {
        engine.pause()
        host?.stop()
        host = null
        moves.flush()
        for (c in moves.cards.value) moves.store(c.moveId)?.close()
        if (newKey) File(noBackup, "phone-key.json").delete()
        moves = Moves(files, noBackup, deviceName = { "$name's phone" })
        engine.resume()
    }

    fun close() {
        engine.close()
        host?.stop()
        moves.flush()
        for (c in moves.cards.value) moves.store(c.moveId)?.close()
    }
}

/**
 * How fast the crypto of the test phones is: a key agreement takes well under a millisecond with the JCA (the JVM), and far
 * longer with the reference crypto (pure Kotlin, not optimised) that the iPhone simulator's tests use, since they cannot
 * call CryptoKit. Waits and pauses grow with it, so the tests ask the same of the code on both. Measured once a crypto is there.
 */
internal object TestSpeed {
    private var measured = -1L

    /** One key agreement, in milliseconds (0 while the tests have no crypto). */
    fun agreeMs(): Long {
        if (measured >= 0) return measured
        val crypto = platformCrypto ?: return 0
        val a = crypto.generateKey()
        val b = crypto.generateKey()
        a.agree(b.publicDer)
        val start = currentTimeMillis()
        repeat(4) { a.agree(b.publicDer) }
        measured = (currentTimeMillis() - start) / 4
        return measured
    }

    /** About how long a handshake of syncing by itself takes: both phones' key agreements, one after the other. */
    fun handshakeMs(): Long = 12 * agreeMs()
}

/** What a test that timed out adds to its failure: say, how each phone's syncing by itself stands. */
internal var explainTimeout: (() -> String)? = null

/** Waits up to [ms] (longer where the crypto is slow: [TestSpeed]) for [done], then fails saying [what]. */
internal fun waitUntil(what: String, ms: Long = 20_000 + 20 * TestSpeed.handshakeMs(), done: () -> Boolean) {
    val until = currentTimeMillis() + ms
    while (!done()) {
        if (currentTimeMillis() > until) {
            val more = explainTimeout?.let { "; " + it() } ?: ""
            fail("timed out waiting for $what after $ms ms (a key agreement takes ${TestSpeed.agreeMs()} ms here)$more")
        }
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
