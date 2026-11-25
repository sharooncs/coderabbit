package app.housemove

import app.housemove.data.AppData
import app.housemove.data.AppJson
import app.housemove.data.MoveIdentity
import app.housemove.data.Person
import app.housemove.data.Store
import app.housemove.data.currentTimeMillis
import app.housemove.data.hexBytes
import app.housemove.data.hmacSha256
import app.housemove.data.sameBytes
import app.housemove.data.toHex
import app.housemove.data.toUtf8
import app.housemove.sync.AutoSlot
import app.housemove.sync.Strong
import app.housemove.sync.WifiSync
import app.housemove.sync.dataIn
import app.housemove.sync.dataOut
import app.housemove.sync.listsSynced
import app.housemove.sync.memberPhone
import app.housemove.sync.memberPhones
import app.housemove.sync.outsiderPhones
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.io.IOException
import java.net.Socket

/*
 * A phone on HouseMove 2.3, for the tests of 2.4 beside it (the seven-phone simulation's mixed versions, and the migration
 * from 2.3): 2.3's key ring (ring.json: Wi-Fi keys and pair keys), its meetings, and its handshake for syncing by itself
 * (HMAUTO1, design/auto-sync.md), as 2.3 shipped them (commit a0fa6ee), byte for byte. 2.4 itself speaks none of it: it
 * deletes ring.json when a move is opened and never answers HMAUTO1 (design/encryption.md, "Old phones"). Everything here
 * runs over WifiSync's own exchange, unencrypted, exactly as 2.3 phones sync with each other.
 */

/** A Wi-Fi key of a move, as 2.3 keeps it. */
@Serializable
internal data class WifiKey23(val id: String, val key: String, val out: List<String> = emptyList(), val at: Long = 0) {
    val bytes: ByteArray? get() = hexBytes(key)

    fun goodFor(outsiders: Set<String>): Boolean = outsiders.all { it in out }

    val wellFormed: Boolean
        get() = id.length == 16 && hexBytes(id) != null && key.length == 64 && hexBytes(key) != null && out.size <= 2000 && out.all { it.length in 1..64 }
}

@Serializable
private data class RingSaved23(val move: String = "", val keys: List<WifiKey23> = emptyList(), val pairs: Map<String, String> = emptyMap())

/** 2.3's key ring of one move on one phone, in [file] (the move's no-backup folder's `ring.json`, where 2.3 keeps it). */
internal class Ring23(private val file: File?, private val identity: () -> MoveIdentity) {
    private val lock = Any()
    private var saved: RingSaved23 = read()

    private fun read(): RingSaved23 = try {
        file?.takeIf { it.isFile }?.let { AppJson.decodeFromString<RingSaved23>(it.readText()) } ?: RingSaved23()
    } catch (_: Exception) {
        RingSaved23()
    }

    private fun current(): RingSaved23 {
        // The file may have been deleted under it (2.4 opened the move): then the ring is empty.
        if (file != null && !file.isFile && saved.keys.isNotEmpty()) saved = RingSaved23()
        return if (saved.move.isNotEmpty() && identity().matches(saved.move)) saved else RingSaved23()
    }

    private fun write(next: RingSaved23) {
        val stamped = next.copy(move = identity().id, keys = next.keys.sortedByDescending { it.id }.take(4))
        if (stamped == saved && file?.isFile != false) return
        saved = stamped
        file?.let {
            it.parentFile?.mkdirs()
            it.writeText(AppJson.encodeToString(stamped))
        }
    }

    fun keys(d: AppData): List<WifiKey23> = synchronized(lock) {
        val c = current()
        val outsiders = d.outsiderPhones()
        val good = c.keys.filter { it.goodFor(outsiders) }
        if (good.size != c.keys.size) write(c.copy(keys = good))
        good.sortedByDescending { it.id }
    }

    fun pairs(): Map<String, ByteArray> = synchronized(lock) {
        current().pairs.mapNotNull { (phone, hex) -> hexBytes(hex)?.takeIf { it.size == 32 }?.let { phone to it } }.toMap()
    }

    fun setPair(phoneId: String, key: ByteArray) = synchronized(lock) {
        val c = current()
        write(c.copy(pairs = c.pairs + (phoneId to key.toHex())))
    }

    fun take(offered: List<WifiKey23>, d: AppData): Boolean = synchronized(lock) {
        val c = current()
        val outsiders = d.outsiderPhones()
        val known = c.keys.map { it.id }.toSet()
        val fresh = offered.filter { it.wellFormed && it.id !in known && it.goodFor(outsiders) }.distinctBy { it.id }
        if (fresh.isEmpty()) return false
        write(c.copy(keys = (c.keys.filter { it.goodFor(outsiders) } + fresh.map { it.copy(id = it.id.lowercase(), key = it.key.lowercase()) })))
        true
    }

    fun mintIfNone(d: AppData): Boolean = synchronized(lock) {
        if (keys(d).isNotEmpty()) return false
        val c = current()
        val key = WifiKey23(Strong.bytes(8).toHex(), Strong.bytes(32).toHex(), d.outsiderPhones().sorted(), currentTimeMillis())
        write(c.copy(keys = c.keys + key))
        true
    }

    companion object {
        /** The ring of [store]'s move, where 2.3 keeps it: `ring.json` beside the codes ([codesDir]). */
        fun of(store: Store, codesDir: File) = Ring23(File(codesDir, "ring.json")) { store.identity.value }
    }
}

/** 2.3's meetings: code syncs and joins that have just happened, by phone, for ten minutes (in memory only). */
internal object Meetings23 {
    class Meeting(val me: String, val moves: List<String>, val peer: String, val code: String, val at: Long)

    private val list = ArrayList<Meeting>()

    @Synchronized
    fun note(me: String, moves: List<String>, peer: String, code: String) {
        if (me.isEmpty() || peer.isEmpty() || code.isEmpty() || peer == me) return
        val now = currentTimeMillis()
        list.removeAll { it.at + 600_000L < now || (it.me == me && it.peer == peer) }
        list += Meeting(me, moves.filter { it.isNotEmpty() }, peer, code, now)
        while (list.size > 1000) list.removeAt(0)
    }

    @Synchronized
    fun recent(me: String, moves: List<String>): List<Meeting> {
        val now = currentTimeMillis()
        return list.filter { it.me == me && it.at + 600_000L >= now && it.moves.any { m -> m in moves } }
    }

    @Synchronized
    fun used(me: String, peer: String) {
        list.removeAll { it.me == me && it.peer == peer }
    }

    @Synchronized
    fun forget(mine: (String) -> Boolean) {
        list.removeAll { mine(it.me) }
    }

    @Synchronized
    fun clear() = list.clear()
}

/** 2.3's handshake for syncing by itself, HMAUTO1, with the ring a test gives each phone. */
internal object Auto23 {
    const val MAGIC = "HMAUTO1"
    const val NONCE = 32
    const val PROOF = 32
    private const val DIALLER = "HMAUTO1 dialler"
    private const val ANSWERER = "HMAUTO1 answerer"
    private const val CONFIRM = "HMAUTO1 confirm"
    private const val PAIR = "HMAUTO1 pair"

    fun hourOf(millis: Long): Long = millis.floorDiv(3_600_000L)

    fun tag(moveId: String, hour: Long): String = hmacSha256(("housemove-discovery:$moveId").toUtf8(), "tag:$hour".toUtf8()).toHex().take(16)

    fun advertisedTag(identity: MoveIdentity, now: Long): String = tag(identity.all.filter { it.isNotEmpty() }.min(), hourOf(now))

    fun tagsOf(identity: MoveIdentity, now: Long): Set<String> {
        val hour = hourOf(now)
        return identity.all.filter { it.isNotEmpty() }.flatMap { id -> (-1L..1L).map { tag(id, hour + it) } }.toSet()
    }

    private fun joined(parts: List<ByteArray>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val d = java.io.DataOutputStream(out)
        for (p in parts) {
            d.writeInt(p.size)
            d.write(p)
        }
        return out.toByteArray()
    }

    fun proof(key: ByteArray, label: String, tag: String, nD: ByteArray, nA: ByteArray, vararg ids: String): ByteArray =
        hmacSha256(key, joined(listOf(label.toUtf8(), tag.toUtf8(), nD, nA) + ids.map { it.toUtf8() }))

    enum class Kind { PAIR, MEETING, WIFI }

    class Cred(val kind: Kind, val key: ByteArray, val phones: List<String>, val id: String = "")

    fun creds(store: Store, ring: Ring23): List<Cred> {
        val d = store.data.value
        val me = store.phone.id
        val members = d.memberPhones() - me
        val out = ArrayList<Cred>()
        for ((phone, key) in ring.pairs()) if (phone in members) out += Cred(Kind.PAIR, key, listOf(phone))
        for (m in Meetings23.recent(me, store.identity.value.all)) if (m.peer in members) out += Cred(Kind.MEETING, "meeting:${m.code}".toUtf8(), listOf(m.peer))
        for (k in ring.keys(d)) k.bytes?.let { out += Cred(Kind.WIFI, it, members.toList(), k.id) }
        return out
    }

    private fun search(creds: List<Cred>, matches: (Cred, String) -> Boolean): Pair<Cred, String>? {
        for (c in creds.sortedBy { it.kind.ordinal }) for (phone in c.phones) if (matches(c, phone)) return c to phone
        return null
    }

    @Serializable
    private class Offer(val keys: List<WifiKey23> = emptyList())

    private fun offerFrom(text: String): List<WifiKey23> = try {
        AppJson.decodeFromString<Offer>(text).keys.take(8)
    } catch (_: Exception) {
        emptyList()
    }

    private fun stillGood(ring: Ring23, d: AppData, cred: Cred, peer: String): Boolean =
        d.memberPhone(peer) != null && (cred.kind != Kind.WIFI || ring.keys(d).any { it.id == cred.id })

    private fun pairKey(cred: Cred, tag: String, nD: ByteArray, nA: ByteArray, a: String, b: String): ByteArray =
        proof(cred.key, PAIR, tag, nD, nA, minOf(a, b), maxOf(a, b))

    class Dialled(val word: String, val result: WifiSync.Result? = null, val peerPhone: String? = null, val via: Kind? = null)

    fun dial(store: Store, ring: Ring23, host: String, port: Int): Dialled {
        val creds = creds(store, ring).sortedBy { if (it.kind == Kind.PAIR) 1 else 0 }.take(64)
        if (creds.isEmpty()) return Dialled("NO")
        val socket = Socket()
        try {
            socket.connect(java.net.InetSocketAddress(host, port), 3_000)
        } catch (_: Exception) {
            socket.close()
            return Dialled("LOST")
        }
        try {
            socket.use { s ->
                s.soTimeout = 5_000
                s.tcpNoDelay = true
                val din = s.dataIn()
                val out = s.dataOut()
                val me = store.phone.id
                val tag = advertisedTag(store.identity.value, currentTimeMillis())
                val nD = Strong.bytes(NONCE)
                out.writeUTF(MAGIC); out.writeUTF(tag); out.writeUTF(""); out.write(nD); out.flush()
                val nA = ByteArray(NONCE).also { din.readFully(it) }
                val proofs = creds.map { proof(it.key, DIALLER, tag, nD, nA, me) }.sortedBy { it.toHex() }
                out.writeInt(proofs.size)
                for (p in proofs) out.write(p)
                out.flush()
                val word = din.readUTF()
                if (word != "OK") return Dialled(if (word == "BUSY") "BUSY" else "NO")
                val theirs = ByteArray(PROOF).also { din.readFully(it) }
                val found = search(creds) { c, phone -> sameBytes(proof(c.key, ANSWERER, tag, nD, nA, me, phone), theirs) }
                val person = found?.let { store.data.value.memberPhone(it.second) }
                if (found == null || person == null) {
                    out.writeUTF("NO"); out.flush()
                    return Dialled("NO")
                }
                val (cred, peer) = found
                out.writeUTF("GO"); out.write(proof(cred.key, CONFIRM, tag, nD, nA, me, peer)); out.flush()
                if (din.readUTF() != "SYNC") return Dialled("NO")
                s.soTimeout = WifiSync.timeoutMs
                val result = WifiSync.joinProven(store, s, din, out, WifiSync.Proven(peer, person.id))
                if (!result.ok) return Dialled("OK", result, peer, cred.kind)
                try {
                    val good = stillGood(ring, store.data.value, cred, peer)
                    out.writeUTF(AppJson.encodeToString(Offer(if (good) ring.keys(store.data.value) else emptyList()))); out.flush()
                    val offered = offerFrom(din.readUTF())
                    if (good) {
                        ring.take(offered, store.data.value)
                        if (cred.kind != Kind.PAIR) ring.setPair(peer, pairKey(cred, tag, nD, nA, me, peer))
                        if (cred.kind == Kind.MEETING) Meetings23.used(me, peer)
                    }
                } catch (_: IOException) {
                }
                return Dialled("OK", result, peer, cred.kind)
            }
        } catch (_: Exception) {
            return Dialled("LOST")
        }
    }

    class Answered(val result: WifiSync.Result, val peerPhone: String, val via: Kind)

    /** Answers on [s] as a 2.3 phone does. */
    fun answer(store: Store, ring: Ring23, s: Socket, slot: AutoSlot): Answered? {
        s.soTimeout = 5_000
        s.tcpNoDelay = true
        val din = s.dataIn()
        val out = s.dataOut()
        val length = din.readUnsignedShort()
        if (length != MAGIC.length) return null
        if (ByteArray(length).also { din.readFully(it) }.decodeToString() != MAGIC) return null
        val tag = din.readUTF()
        din.readUTF()
        val nD = ByteArray(NONCE).also { din.readFully(it) }
        if (tag !in tagsOf(store.identity.value, currentTimeMillis())) return null
        val nA = Strong.bytes(NONCE)
        out.write(nA); out.flush()
        val n = din.readInt()
        if (n !in 0..64) return null
        val proofs = HashSet<String>()
        repeat(n) { proofs += ByteArray(PROOF).also { din.readFully(it) }.toHex() }
        val me = store.phone.id
        val creds = creds(store, ring)
        val found = search(creds) { c, phone -> proof(c.key, DIALLER, tag, nD, nA, phone).toHex() in proofs }
        val person = found?.let { store.data.value.memberPhone(it.second) }
        if (found == null || person == null) {
            out.writeUTF("NO"); out.flush()
            return null
        }
        if (!slot.take()) {
            out.writeUTF("BUSY"); out.flush()
            return null
        }
        try {
            val (cred, peer) = found
            out.writeUTF("OK"); out.write(proof(cred.key, ANSWERER, tag, nD, nA, peer, me)); out.flush()
            if (din.readUTF() != "GO") return null
            val confirm = ByteArray(PROOF).also { din.readFully(it) }
            if (!sameBytes(confirm, proof(cred.key, CONFIRM, tag, nD, nA, peer, me))) {
                out.writeUTF("NO"); out.flush()
                return null
            }
            out.writeUTF("SYNC"); out.flush()
            s.soTimeout = WifiSync.timeoutMs
            val host = WifiSync.Host(store, null, "", ::currentTimeMillis, letNewPhonesIn = false, confirmJoins = false, onResult = {})
            val result = host.serveProven(din, out, WifiSync.Proven(peer, person.id))
            if (!result.ok) return Answered(result, peer, cred.kind)
            try {
                val offered = offerFrom(din.readUTF())
                val d = store.data.value
                val good = stillGood(ring, d, cred, peer)
                if (good) {
                    ring.take(offered, d)
                    ring.mintIfNone(d)
                }
                out.writeUTF(AppJson.encodeToString(Offer(if (good) ring.keys(d) else emptyList()))); out.flush()
                if (good) {
                    if (cred.kind != Kind.PAIR) ring.setPair(peer, pairKey(cred, tag, nD, nA, peer, me))
                    if (cred.kind == Kind.MEETING) Meetings23.used(me, peer)
                }
            } catch (_: IOException) {
            }
            return Answered(result, peer, cred.kind)
        } finally {
            slot.release()
        }
    }

    /** Is [r] a sync both phones took in? */
    fun synced(r: WifiSync.Result?): Boolean = r != null && r.listsSynced()

    /** Who a phone of the move proved to be, for the tests' words. */
    fun person(store: Store, phone: String): Person? = store.data.value.memberPhone(phone)
}

/**
 * A 2.3 phone's listening socket for syncing by itself, on 127.0.0.1: answers HMAUTO1 for the move [store] gives, with
 * the ring [ring] gives, one connection at a time on threads of its own. [onDone] hears what came of each connection.
 */
internal class Listener23(private val store: () -> Store?, private val ring: (Store) -> Ring23, private val onDone: (Auto23.Answered?) -> Unit = {}) {
    private val server = java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
    private val slot = AutoSlot()
    val port: Int get() = server.localPort

    init {
        kotlin.concurrent.thread(name = "listen-23", isDaemon = true) {
            while (true) {
                val s = try {
                    server.accept()
                } catch (_: IOException) {
                    break
                }
                kotlin.concurrent.thread(name = "answer-23", isDaemon = true) {
                    var a: Auto23.Answered? = null
                    try {
                        s.use { sock -> store()?.let { st -> a = Auto23.answer(st, ring(st), sock, slot) } }
                    } catch (_: Exception) {
                    } finally {
                        onDone(a)
                    }
                }
            }
        }
    }

    fun stop() = server.close()
}
