package app.housemove

import app.housemove.data.AppJson
import app.housemove.data.PhotoKind
import app.housemove.data.Role
import app.housemove.data.base64Decode
import app.housemove.data.toUtf8
import app.housemove.sync.AutoListener
import app.housemove.sync.AutoSession
import app.housemove.sync.AutoSlot
import app.housemove.sync.LocalLan
import app.housemove.sync.WifiSync
import app.housemove.sync.noiseKey
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * What someone recording the Wi-Fi sees when two phones with 2.4 sync (design/encryption.md, Part 2): every byte that went
 * between them, by invite, by code and by themselves, is recorded by a relay in between, and searched for the move (its
 * ID, its name, the names of things, a serial number, a photo's bytes, people's names, phone IDs, the hello's words) and
 * for keys (each phone's public key as a point, in X.509 and in Base64; each phone's private key; the code and the key
 * made from it; every session's keys). None is there. The same search over a sync with a phone on 2.3, which is plain,
 * finds them: so the search would have found them.
 */
class SniffingTest {
    private val root = Files.createTempDirectory("hm-sniffing").toFile()
    private val lan = LocalLan()
    private val phones = ArrayList<AutoPhone>()
    private val older = java.util.Collections.synchronizedSet(HashSet<String>())

    @Before
    fun keys() {
        SessionKeys.install()
        WifiSync.olderPhone = { it in older }
    }

    @After
    fun cleanUp() {
        phones.forEach { it.close() }
        SessionKeys.uninstall()
        WifiSync.olderPhone = { false }
        root.deleteRecursively()
    }

    private fun phone(name: String) = AutoPhone(root, name, lan, engineOn = false).also { phones += it }

    /** The private scalar of [p]'s phone key, from where Android keeps it (PKCS#8 in no-backup/phone-key.json). */
    private fun privateScalar(p: AutoPhone): ByteArray {
        val saved = AppJson.parseToJsonElement(File(root, "${p.name}/no-backup/phone-key.json").readText()).jsonObject
        val pkcs8 = base64Decode(saved.getValue("private").jsonPrimitive.content)
        return RefPkcs8.scalar(pkcs8) ?: pkcs8
    }

    /** Everything that must not be readable on the wire between [a] and [b], by name. */
    private fun secrets(a: AutoPhone, b: AutoPhone, code: String): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        val s = a.store
        for (name in listOf("Grandmother's walnut piano", "Blue Delft vase", "SN-9X2Q-77810", "Maple Road", s.moveId, "Asha", "Priya", "PHOTO-MARKER-6a1f9c")) out[name] = name.toUtf8()
        for (p in listOf(a, b)) {
            out["${p.name}'s phone ID"] = p.store.phone.id.toUtf8()
            val k = p.store.phone.keys
            out["${p.name}'s public key (Base64)"] = k.publicText.toUtf8()
            out["${p.name}'s public key (X.509)"] = base64Decode(k.publicText)
            out["${p.name}'s public key (point)"] = k.noiseKey()!!.point
            out["${p.name}'s private key"] = privateScalar(p)
        }
        out["the code"] = code.toUtf8()
        out["the key made from the code"] = WifiSync.codeKey(code, s.moveId)
        out["the hello's words"] = "\"phoneId\"".toUtf8()
        out["the plain hello"] = "HMSYNC3".toUtf8()
        return out
    }

    private fun ByteArray.holds(x: ByteArray): Boolean {
        if (x.isEmpty() || x.size > size) return false
        outer@ for (i in 0..size - x.size) {
            for (j in x.indices) if (this[i + j] != x[j]) continue@outer
            return true
        }
        return false
    }

    private fun seen(wire: Wire): ByteArray = wire.raw(0) + wire.raw(1)

    /** Asha's move, with things worth hiding: names, a serial number and a photo. */
    private fun move(): Pair<AutoPhone, AutoPhone> {
        val asha = phone("Asha")
        val priya = phone("Priya")
        asha.found("Maple Road")
        asha.store.createItem("Grandmother's walnut piano", null)
        val vase = asha.store.createItem("Blue Delft vase", null)
        asha.store.updateItems(listOf(vase.id)) { it.copy(notes = "Serial SN-9X2Q-77810, in the attic") }
        val photo = File(asha.store.photoDir, "vase.jpg").apply { writeBytes("PHOTO-MARKER-6a1f9c and the rest of a photo".toUtf8()) }
        asha.store.addPhoto(vase.id, PhotoKind.GENERAL, photo)
        return asha to priya
    }

    @Test
    fun nothingOfTheMoveOrOfAnyKeyCrossesInTheClearBetween24Phones() {
        val (asha, priya) = move()
        val host = WifiSync.host(asha.store, letNewPhonesIn = false) {}
        val captured = ArrayList<Pair<String, ByteArray>>()
        val sessionsBefore = SessionKeys.all().size
        try {
            // The invite join, through the relay.
            val person = asha.store.addPerson("Priya", Role.DECIDER).valueOrNull()!!
            val invite = host.inviteFor(person.id, listOf("127.0.0.1")).valueOrNull()!!
            val joinWire = Wire(host.port)
            val joined = priya.moves.join(priya.moves.readInvite(invite.copy(port = joinWire.port).encode())!!)
            joinWire.close()
            assertTrue(joined.error, joined.ok && !joined.plain)
            assertTrue(priya.store.data.value.liveItems.any { it.name == "Grandmother's walnut piano" })
            assertTrue("the photo came, inside the channel", File(priya.store.photoDir, "vase.jpg").readText().startsWith("PHOTO-MARKER-6a1f9c"))
            captured += "the invite join" to seen(joinWire)

            // A code sync, through the relay.
            priya.store.createItem("Priya's lamp", null)
            val codeWire = Wire(host.port)
            val r = WifiSync.join(priya.store, host.invite(listOf("127.0.0.1")).copy(port = codeWire.port), letNewPhonesIn = false)
            codeWire.close()
            assertTrue(r.error, r.ok && !r.plain)
            captured += "a code sync" to seen(codeWire)

            // A sync by itself, through the relay.
            asha.store.createItem("Asha's rug", null)
            val listener = AutoListener(true, { asha.store }, AutoSlot(), {}, { _, _ -> }).also { it.start() }
            val autoWire = Wire(listener.port)
            val d = AutoSession.dial(priya.store, "127.0.0.1", autoWire.port, asha.store.phone.id)
            autoWire.close()
            listener.stop()
            assertEquals("OK", d.word)
            assertTrue(priya.store.data.value.liveItems.any { it.name == "Asha's rug" })
            captured += "a sync by itself" to seen(autoWire)

            // The relay read each of them with the session keys: it is the data, encrypted, that it saw.
            for (w in listOf(joinWire, codeWire, autoWire)) assertTrue(w.encrypted && w.data(3) != null && w.data(4) != null)
            val sessions = SessionKeys.all().drop(sessionsBefore)
            assertEquals("three sessions, two keys each", 6, sessions.size)
            val search = secrets(asha, priya, invite.token) + ("the plain code" to host.token.toUtf8()) +
                sessions.mapIndexed { n, k -> "session key ${n + 1}" to k }
            val found = ArrayList<String>()
            for ((what, bytes) in captured) for ((name, secret) in search) if (bytes.holds(secret)) found += "$name in $what"
            assertTrue("in the clear: $found", found.isEmpty())
            assertTrue(captured.all { it.second.size > 1_000 })
        } finally {
            host.stop()
        }
    }

    /** The same search, over a code sync with a phone on 2.3: plain, and so it finds the move and the keys. */
    @Test
    fun theSameSearchFindsThemInASyncWithAnOlderPhone() {
        val (asha, priya) = move()
        older += priya.moves.phone.id
        val host = WifiSync.host(asha.store, letNewPhonesIn = false) {}
        try {
            val person = asha.store.addPerson("Priya", Role.DECIDER).valueOrNull()!!
            val invite = host.inviteFor(person.id, listOf("127.0.0.1")).valueOrNull()!!
            val wire = Wire(host.port)
            val joined = priya.moves.join(priya.moves.readInvite(invite.copy(port = wire.port).encode())!!)
            wire.close()
            assertTrue(joined.error, joined.ok && joined.plain)
            val seen = seen(wire)
            val search = secrets(asha, priya, invite.token)
            val found = search.filter { (_, secret) -> seen.holds(secret) }.keys
            for (name in listOf("Grandmother's walnut piano", "SN-9X2Q-77810", "Maple Road", asha.store.moveId, "the code", "Asha's public key (Base64)", "Priya's public key (Base64)", "PHOTO-MARKER-6a1f9c", "the plain hello")) {
                assertTrue("$name is in a plain sync: $found", name in found)
            }
            // Private keys never travel, even in a plain sync.
            assertTrue(found.none { it.endsWith("private key") })
        } finally {
            host.stop()
        }
    }
}
