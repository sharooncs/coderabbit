package app.housemove

import app.housemove.data.CountDownLatch
import app.housemove.data.File
import app.housemove.data.Role
import app.housemove.data.Store
import app.housemove.data.awaitMillis
import app.housemove.data.countDown
import app.housemove.data.findByLabel
import app.housemove.data.startThread
import app.housemove.sync.ServerSocket
import app.housemove.sync.Socket
import app.housemove.sync.WifiSync
import app.housemove.sync.accept
import app.housemove.sync.close
import app.housemove.sync.connect
import app.housemove.sync.dataOut
import app.housemove.sync.flush
import app.housemove.sync.localPort
import app.housemove.sync.writeUTF
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * The sync over the platform's sockets when the other end misbehaves (ported from NetworkTest, which stays on the
 * JVM with the rest): a phone that goes quiet is given up on, a connection that drops at once is said to be lost, and
 * a sync after either goes through. On the iPhone this is SO_RCVTIMEO, EOF and resets through Net.ios.kt.
 */
class SyncNetworkCommonTest {
    private var now = 1_000_000L
    private val local = listOf("127.0.0.1")
    private val hosts = ArrayList<WifiSync.Host>()
    private val servers = ArrayList<ServerSocket>()
    private val dirs = ArrayList<File>()

    @AfterTest
    fun hangUp() {
        hosts.forEach { it.stop() }
        servers.forEach {
            try {
                it.close()
            } catch (_: Exception) {
            }
        }
        WifiSync.timeoutMs = 30_000
    }

    private fun phone(name: String): Store {
        val dir = newTempFolder().also { dirs += it }
        return Store(dir) { now++ }.also { s -> s.setDevice { it.copy(name = name) } }
    }

    private class Hosting(val host: WifiSync.Host, private val done: Array<CountDownLatch>, private val results: ArrayList<WifiSync.Result>) {
        fun result(i: Int): WifiSync.Result {
            assertTrue(done[i].awaitMillis(20_000), "the host reported nothing")
            return results[i]
        }
    }

    private fun hosting(store: Store): Hosting {
        val done = Array(8) { CountDownLatch(1) }
        val results = ArrayList<WifiSync.Result>()
        val lock = app.housemove.data.Lock()
        val h = WifiSync.Host(store, loopbackServer(), "HM4RT8WZ", { now }, true, false) { r ->
            val i = app.housemove.data.synchronized(lock) {
                results += r
                results.size - 1
            }
            if (i < done.size) done[i].countDown()
        }
        h.start()
        hosts += h
        return Hosting(h, done, results)
    }

    /** Something that takes each connection and does [then] with it, on a thread of its own. */
    private fun listener(then: (Socket) -> Unit): Int {
        val server = loopbackServer().also { servers += it }
        startThread("test-listener") {
            try {
                while (true) {
                    val s = server.accept()
                    startThread("test-connection") { then(s) }
                }
            } catch (_: Exception) {
            }
        }
        return server.localPort
    }

    @Test
    fun aPhoneThatStopsAnsweringIsGivenUpOnInsteadOfWaitedForForever() {
        WifiSync.timeoutMs = 1500
        val asha = phone("Asha")
        asha.createBox("L001", null)
        // Something that takes the connection and then says nothing at all.
        val release = CountDownLatch(1)
        val silent = listener { s ->
            release.awaitMillis(20_000)
            s.close()
        }
        val started = TimeSource.Monotonic.markNow()
        val r = WifiSync.join(asha, WifiSync.Invite(local, silent, "ABCDEFGH"))
        assertFalse(r.ok)
        assertEquals(WifiSync.CONNECTION_LOST, r.error)
        assertTrue(started.elapsedNow().inWholeMilliseconds < 8_000, "took ${started.elapsedNow()}")
        release.countDown()

        // The other way round: a phone connects to this one's code, says hello and goes quiet. The host gives up too.
        val h = hosting(asha)
        val s = Socket()
        s.connect("127.0.0.1", h.host.port, 4000)
        val out = s.dataOut()
        out.writeUTF("HMSYNC3")
        out.flush()
        assertEquals(WifiSync.CONNECTION_LOST, h.result(0).error)
        s.close()

        // And the next real sync goes through.
        val p = asha.addPerson("Hal", Role.HELPER).valueOrNull()!!
        val hal = phone("Hal")
        val joined = WifiSync.join(hal, h.host.inviteFor(p.id, local).valueOrNull()!!)
        assertTrue(joined.ok, joined.error)
        assertTrue(h.result(1).ok)
        assertNotNull(hal.data.value.findByLabel("L001"))
    }

    @Test
    fun aConnectionThatDropsAtOnceIsNotTakenForAnotherVersion() {
        val asha = phone("Asha")
        val dropper = listener { it.close() }
        assertEquals(WifiSync.CONNECTION_LOST, WifiSync.join(asha, WifiSync.Invite(local, dropper, "ABCDEFGH")).error)
    }

    @Test
    fun nobodyAtTheAddressIsUnreachable() {
        val asha = phone("Asha")
        val gone = loopbackServer()
        val port = gone.localPort
        gone.close()
        assertEquals(WifiSync.UNREACHABLE, WifiSync.join(asha, WifiSync.Invite(local, port, "ABCDEFGH")).error)
    }
}
