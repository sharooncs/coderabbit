package app.housemove

import app.housemove.data.AppJson
import app.housemove.data.Lock
import app.housemove.data.decodeUtf8
import app.housemove.data.sha256
import app.housemove.data.strongRandomBytes
import app.housemove.data.synchronized
import app.housemove.data.toHex
import app.housemove.data.toUtf8
import app.housemove.sync.HttpAnswer
import app.housemove.sync.HttpRequest
import app.housemove.sync.HttpTransport
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The mailbox program (moving-app/mailbox, in Go) as an in-process stand-in with the same API and the same rules
 * (README.md there, "The API"): registering with the house token, posting by mailbox ID alone, listing, fetching and
 * deleting with the read secret, the largest envelope, the quotas and the number of mailboxes. The common tests talk to it
 * without sockets ([HttpTransport]), so they run the same on the iPhone simulator; MailboxGoServerTest runs the same phones
 * against the real Go program over HTTP. It keeps every request and answer ([wire]) and every envelope it holds
 * ([stored]), for the sniffing test; [down] stands in for a laptop that is off.
 */
internal class MailboxStandIn(
    val url: String = "https://example-laptop.example-tailnet.ts.net",
    val token: String = strongRandomBytes(32).toHex(),
    var maxEnvelope: Int = 8 * 1024 * 1024,
    var mailboxQuota: Long = 64L * 1024 * 1024,
    var totalQuota: Long = 2048L * 1024 * 1024,
    var maxMailboxes: Int = 200,
    private val clock: () -> Long = { app.housemove.data.currentTimeMillis() },
) : HttpTransport {
    private class Box(val secretHash: String, val envelopes: LinkedHashMap<String, ByteArray> = LinkedHashMap(), val at: HashMap<String, Long> = HashMap())

    private val lock = Lock()
    private val boxes = LinkedHashMap<String, Box>()
    private var counter = 0L

    /** The laptop is off, or not reachable: nothing answers. */
    @kotlin.concurrent.Volatile
    var down = false

    /** Every request and its answer, in order. */
    val wire = ArrayList<Pair<HttpRequest, HttpAnswer>>()

    val code: String get() = "HMBOX:1:$token:$url"

    /** Every envelope held now, by mailbox ID. */
    fun stored(): Map<String, List<ByteArray>> = synchronized(lock) { boxes.mapValues { (_, b) -> b.envelopes.values.toList() } }

    fun mailboxes(): Set<String> = synchronized(lock) { boxes.keys.toSet() }

    /** Changes the envelopes held in [id] (a laptop acting badly): each one through [f]; null drops it. */
    fun tamper(id: String, f: (ByteArray) -> ByteArray?) = synchronized(lock) {
        val b = boxes[id] ?: return@synchronized
        for (k in b.envelopes.keys.toList()) {
            val v = f(b.envelopes.getValue(k))
            if (v == null) b.envelopes.remove(k) else b.envelopes[k] = v
        }
    }

    /** Puts [bytes] in mailbox [id] as a post would (a replay by the laptop, or a stranger who knows the ID). */
    fun inject(id: String, bytes: ByteArray): Int = call(HttpRequest("POST", "$url/v1/mailboxes/$id/envelopes", body = bytes)).status

    /** Forgets every mailbox (the laptop's data folder was lost). */
    fun wipe() = synchronized(lock) { boxes.clear() }

    private fun hex(s: String, n: Int) = s.length == n && s.all { it in '0'..'9' || it in 'a'..'f' }

    private fun json(status: Int, text: String) = HttpAnswer(status, text.toUtf8())

    override fun call(r: HttpRequest): HttpAnswer {
        if (down) return HttpAnswer(0, problem = "ConnectException")
        val a = synchronized(lock) { answer(r) }
        synchronized(lock) { wire += r to a }
        return a
    }

    private fun answer(r: HttpRequest): HttpAnswer {
        if (!r.url.startsWith("$url/")) return json(404, "{}")
        val path = r.url.removePrefix(url).split('/').filter { it.isNotEmpty() }
        val bearer = r.headers["Authorization"]?.removePrefix("Bearer ")?.trim() ?: ""
        if (path == listOf("v1", "health") && r.method == "GET") return json(200, """{"ok":true,"name":"housemove-mailbox","version":"stand-in"}""")
        if (path.size < 3 || path[0] != "v1" || path[1] != "mailboxes" || !hex(path[2], 32)) return json(if (path.size >= 3) 400 else 404, "{}")
        val id = path[2]
        fun box(): Pair<Box?, HttpAnswer?> {
            val b = boxes[id] ?: return null to json(404, """{"error":"no such mailbox"}""")
            if (sha256(bearer.toUtf8()).toHex() != b.secretHash) return null to json(403, """{"error":"wrong secret"}""")
            return b to null
        }
        when {
            path.size == 3 && r.method == "PUT" -> {
                if (sha256(bearer.toUtf8()).toHex() != sha256(token.toUtf8()).toHex()) return json(401, """{"error":"token"}""")
                val secret = try {
                    (AppJson.parseToJsonElement(r.body!!.decodeUtf8()) as JsonObject)["secret"]!!.jsonPrimitive.content
                } catch (_: Exception) {
                    ""
                }
                if (!hex(secret, 64)) return json(400, "{}")
                val hash = sha256(secret.toUtf8()).toHex()
                val was = boxes[id]
                if (was != null) return if (was.secretHash == hash) json(200, """{"id":"$id"}""") else json(409, "{}")
                if (boxes.size >= maxMailboxes) return json(507, "{}")
                boxes[id] = Box(hash)
                return json(201, """{"id":"$id"}""")
            }
            path.size == 3 && r.method == "DELETE" -> {
                val (b, err) = box()
                if (b == null) return err!!
                boxes.remove(id)
                return HttpAnswer(204)
            }
            path.size == 4 && path[3] == "envelopes" && r.method == "POST" -> {
                val b = boxes[id] ?: return json(404, "{}")
                val body = r.body ?: ByteArray(0)
                if (body.isEmpty()) return json(400, "{}")
                if (body.size > maxEnvelope) return json(413, "{}")
                val used = b.envelopes.values.sumOf { it.size.toLong() }
                val total = boxes.values.sumOf { x -> x.envelopes.values.sumOf { it.size.toLong() } }
                if (used + body.size > mailboxQuota || total + body.size > totalQuota) return json(507, "{}")
                counter++
                val eid = (clock() * 1_000_000L + counter).toString(16).padStart(16, '0') + strongRandomBytes(4).toHex()
                b.envelopes[eid] = body.copyOf()
                b.at[eid] = clock()
                return json(201, """{"id":"$eid"}""")
            }
            path.size == 4 && path[3] == "envelopes" && r.method == "GET" -> {
                val (b, err) = box()
                if (b == null) return err!!
                val list = b.envelopes.entries.sortedBy { it.key }.joinToString(",") { (k, v) -> """{"id":"$k","size":${v.size},"at":${b.at[k] ?: 0}}""" }
                return json(200, """{"envelopes":[$list]}""")
            }
            path.size == 5 && path[3] == "envelopes" && hex(path[4], 24) -> {
                val (b, err) = box()
                if (b == null) return err!!
                val e = path[4]
                return when (r.method) {
                    "GET" -> b.envelopes[e]?.let { HttpAnswer(200, it.copyOf()) } ?: json(404, "{}")
                    "DELETE" -> if (b.envelopes.remove(e) != null) HttpAnswer(204) else json(404, "{}")
                    else -> json(405, "{}")
                }
            }
        }
        return json(404, "{}")
    }
}
