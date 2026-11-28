package app.housemove

import app.housemove.data.PhotoKind
import app.housemove.data.Role
import app.housemove.data.base64Decode
import app.housemove.data.toUtf8
import app.housemove.data.writeText
import app.housemove.sync.Http
import app.housemove.sync.HttpAnswer
import app.housemove.sync.HttpRequest
import app.housemove.sync.HttpTransport
import app.housemove.sync.LocalLan
import app.housemove.sync.MailboxSync
import app.housemove.sync.memberPhone
import app.housemove.sync.noiseKey
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Three phones keeping up to date through the real mailbox program (moving-app/mailbox, Go), over HTTP on 127.0.0.1, with
 * the app's own HTTP client (HttpURLConnection, Http.jvm.kt): the program is built with `go build` for the test, started
 * with a settings file of the test's own, and stopped at the end. Where Go is not installed, the same phones run against
 * the in-process stand-in ([MailboxStandIn]) served over real HTTP by the JDK's own server, and the test says so.
 *
 * Then the sniffing: every file the program keeps, every request and answer body, and the program's log are searched for
 * the move (its ID and name, things' names, a serial number, people's names, phone IDs, public keys, a photo's bytes) and
 * for each phone's read secret and the house token where they do not belong: none is there. The same search over the move's
 * own data finds them all, so the search would have.
 */
class MailboxGoServerTest {
    private val root = Files.createTempDirectory("hm-mailbox-go").toFile()
    private val phones = ArrayList<AutoPhone>()
    private val engines = ArrayList<MailboxSync>()
    private var process: Process? = null
    private var jdkServer: HttpServer? = null

    @After
    fun cleanUp() {
        engines.forEach { it.close() }
        phones.forEach { it.close() }
        process?.destroy()
        process?.waitFor(10, TimeUnit.SECONDS)
        jdkServer?.stop(0)
        Http.loopbackForTests = false
        root.deleteRecursively()
    }

    private fun freePort(): Int = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }

    /** The Go program built for this test, or null when Go is not there (or it does not build). */
    private fun buildGo(): File? {
        val src = File("../mailbox").canonicalFile
        if (!File(src, "main.go").isFile) return null
        val out = File(root, "housemove-mailbox")
        return try {
            val p = ProcessBuilder("go", "build", "-o", out.path, ".").directory(src).redirectErrorStream(true)
                .apply { environment()["GOTOOLCHAIN"] = "local"; environment()["CGO_ENABLED"] = "0" }.start()
            val said = p.inputStream.bufferedReader().readText()
            if (p.waitFor(240, TimeUnit.SECONDS) && p.exitValue() == 0 && out.isFile) out else {
                println("MailboxGoServerTest: the Go program did not build: $said")
                null
            }
        } catch (e: java.io.IOException) {
            println("MailboxGoServerTest: no Go here (${e.message}); the stand-in is used")
            null
        }
    }

    /** Starts the program (or the stand-in) on 127.0.0.1: its address and house token, and where it keeps its files. */
    private fun start(): Triple<String, String, File?> {
        val port = freePort()
        val url = "http://127.0.0.1:$port"
        val go = buildGo()
        if (go != null) {
            val data = File(root, "laptop/data")
            val settings = File(root, "laptop/mailbox.json")
            settings.parentFile.mkdirs()
            settings.writeText("""{"listen": "127.0.0.1:$port", "data": "data", "public_url": "https://example-laptop.example-tailnet.ts.net", "rate_per_minute": 100000}""")
            val p = ProcessBuilder(go.path, "--config", settings.path).redirectOutput(File(root, "laptop/out.txt")).redirectError(File(root, "laptop/log.txt")).start()
            process = p
            val until = System.currentTimeMillis() + 20_000
            while (true) {
                val a = Http.platform.call(HttpRequest("GET", "$url/v1/health"))
                if (a.status == 200) break
                assertTrue("the mailbox program started: ${File(root, "laptop/log.txt").readText()}", System.currentTimeMillis() < until && p.isAlive)
                Thread.sleep(50)
            }
            val token = File(data, "house-token").readText().trim()
            return Triple(url, token, data)
        }
        val standIn = MailboxStandIn(url = url)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
        server.createContext("/") { ex ->
            val body = ex.requestBody.readBytes()
            val headers = ex.requestHeaders.entries.associate { (k, v) -> k to v.first() }.mapKeys { (k, _) -> if (k.equals("Authorization", true)) "Authorization" else k }
            val a = standIn.call(HttpRequest(ex.requestMethod, url + ex.requestURI.toString(), headers, body.takeIf { it.isNotEmpty() }))
            ex.sendResponseHeaders(a.status, if (a.body.isEmpty()) -1 else a.body.size.toLong())
            if (a.body.isNotEmpty()) ex.responseBody.use { it.write(a.body) } else ex.close()
        }
        server.start()
        jdkServer = server
        return Triple(url, standIn.token, null)
    }

    /** What went over HTTP, kept for the search: every request and answer, with headers. */
    private class Recorder : HttpTransport {
        val wire = java.util.Collections.synchronizedList(ArrayList<Pair<HttpRequest, HttpAnswer>>())

        override fun call(r: HttpRequest): HttpAnswer = Http.platform.call(r).also { wire += r to it }
    }

    @Test
    fun threePhonesThroughTheRealProgramAndNothingOfTheMoveOnTheLaptop() {
        Http.loopbackForTests = true
        val (url, token, data) = start()
        val recorder = Recorder()
        fun phone(name: String): Pair<AutoPhone, MailboxSync> {
            val p = AutoPhone(root, name, LocalLan(), engineOn = false).also { phones += it }
            val e = MailboxSync({ p.moves.current.value }, { true }).apply {
                http = recorder
                debounceMs = 0
                ackMs = 0
                engines += this
            }
            return p to e
        }
        val (asha, ashaBox) = phone("Asha")
        asha.found("Maple Road")
        assertTrue(MailboxSync.setUp(asha.store, "HMBOX:1:$token:$url").first)
        ashaBox.step()
        val (priya, priyaBox) = phone("Priya")
        val (omar, omarBox) = phone("Omar")
        for ((p, e) in listOf(priya to priyaBox, omar to omarBox)) {
            asha.invite(p, if (p === priya) Role.DECIDER else Role.HELPER)
            assertTrue(asha.codeSync(p).ok)
            e.step()
        }
        fun rounds(n: Int) = repeat(n) { for (e in listOf(ashaBox, priyaBox, omarBox)) e.step() }
        fun has(p: AutoPhone, name: String) = p.store.data.value.liveItems.any { it.name == name }
        rounds(3)

        // The move, with things that must not be readable on the laptop.
        val piano = asha.store.createItem("Grandmother's walnut piano", null)
        asha.store.setDetails(piano.id) { it.copy(serial = "SN-9X2Q-77810") }
        val photo = asha.store.newPhotoFile()
        photo.writeText("PHOTO-MARKER-6a1f9c")
        asha.store.addPhoto(piano.id, PhotoKind.GENERAL, photo)
        priya.store.createItem("Blue Delft vase", null)
        omar.store.createItem("Box of cables", null)
        rounds(4)
        for (p in listOf(asha, priya, omar)) {
            for (thing in listOf("Grandmother's walnut piano", "Blue Delft vase", "Box of cables")) assertTrue("${p.name} has $thing", has(p, thing))
        }
        assertTrue("the photo reached Priya's phone", priya.store.data.value.livePhotos.any { it.ownerId == piano.id && File(priya.store.photoDir, it.file).isFile })
        for (e in listOf(ashaBox, priyaBox, omarBox)) assertEquals(MailboxSync.State.UP_TO_DATE, e.status.value.state)

        // Removing Omar: Asha's phone stops at once.
        assertTrue(asha.store.removePerson(asha.store.data.value.memberPhone(omar.store.phone.id)!!.id).ok)
        asha.store.createItem("After Omar", null)
        rounds(3)
        assertFalse(has(omar, "After Omar"))
        assertTrue(has(priya, "After Omar"))

        // The search: what must not be there.
        val secrets = LinkedHashMap<String, ByteArray>()
        for (s in listOf("Grandmother's walnut piano", "Blue Delft vase", "Box of cables", "SN-9X2Q-77810", "Maple Road", asha.store.moveId, "Asha", "Priya", "Omar", "PHOTO-MARKER-6a1f9c")) secrets[s] = s.toUtf8()
        for (p in listOf(asha, priya, omar)) {
            secrets["${p.name}'s phone ID"] = p.store.phone.id.toUtf8()
            secrets["${p.name}'s person ID"] = p.store.me!!.id.toUtf8()
            secrets["${p.name}'s public key (Base64)"] = p.store.phone.keys.publicText.toUtf8()
            secrets["${p.name}'s public key (X.509)"] = base64Decode(p.store.phone.keys.publicText)
            secrets["${p.name}'s public key (point)"] = p.store.phone.keys.noiseKey()!!.point
        }
        // The search would find them: they are all in the move's own data, as a sync carries it.
        val plain = app.housemove.data.AppJson.encodeToString(app.housemove.data.AppData.serializer(), asha.store.data.value).toUtf8() + photo.readBytes()
        // (The move's ID is not in its data: it is part of what each envelope is sealed with, and never travels. The keys'
        // bytes are in the data as Base64.)
        for ((what, bytes) in secrets) {
            if (what != asha.store.moveId && "(X.509)" !in what && "(point)" !in what) assertTrue("the move itself holds $what", contains(plain, bytes))
        }

        fun search(where: String, haystack: ByteArray) {
            for ((what, needle) in secrets) assertFalse("$where holds $what", contains(haystack, needle))
        }
        val readSecrets = listOf(asha, priya, omar).map { it.store.mailboxState.registration()!!.third }
        synchronized(recorder.wire) {
            assertTrue(recorder.wire.size > 20)
            for ((r, a) in recorder.wire) {
                search("a request body (${r.method})", r.body ?: ByteArray(0))
                search("an answer body", a.body)
                search("a request's address", r.url.toUtf8())
                // The house token goes only to register, the read secret only to read, each in the Authorization header.
                val auth = r.headers["Authorization"] ?: ""
                if (token in auth) assertEquals("PUT", r.method)
                for (s in readSecrets) {
                    assertFalse("a read secret in an address", r.url.contains(s))
                    if (s in auth) assertTrue(r.method == "GET" || r.method == "DELETE")
                }
                assertFalse("the house token in a body", contains(r.body ?: ByteArray(0), token.toUtf8()) && r.method != "PUT")
            }
        }
        if (data != null) {
            var files = 0
            data.walkTopDown().filter { it.isFile && it.name != "house-token" && it.name != "setup-code.png" }.forEach { f ->
                files++
                val bytes = f.readBytes()
                search("the laptop's file ${f.relativeTo(data)}", bytes)
                for (s in readSecrets) assertFalse("${f.name} holds a read secret", contains(bytes, s.toUtf8()))
                assertFalse("${f.name} holds the house token", contains(bytes, token.toUtf8()))
            }
            assertTrue("the laptop kept files ($files)", files >= 3)
            val log = File(root, "laptop/log.txt").readText()
            assertTrue("the log says what happened: $log", log.contains("POST /v1/mailboxes/{id}/envelopes 201"))
            for (s in readSecrets + token + listOf(asha, priya, omar).map { it.store.mailboxState.registration()!!.second }) assertFalse("the log holds a secret or an ID", log.contains(s))
            for ((what, needle) in secrets) assertFalse("the log holds $what", contains(log.toByteArray(), needle))
            assertFalse("the token was printed", File(root, "laptop/out.txt").readText().contains(token))
        } else {
            println("MailboxGoServerTest: Go is not installed here, so the stand-in played the mailbox program over HTTP")
        }
    }

    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || haystack.size < needle.size) return false
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }
}
