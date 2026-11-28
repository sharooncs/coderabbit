package app.housemove

import app.housemove.data.CountDownLatch
import app.housemove.data.EOFException
import app.housemove.data.IOException
import app.housemove.data.awaitMillis
import app.housemove.data.countDown
import app.housemove.data.startThread
import app.housemove.sync.Socket
import app.housemove.sync.SocketException
import app.housemove.sync.SocketTimeoutException
import app.housemove.sync.accept
import app.housemove.sync.close
import app.housemove.sync.connect
import app.housemove.sync.dataIn
import app.housemove.sync.dataInUnbuffered
import app.housemove.sync.dataOut
import app.housemove.sync.flush
import app.housemove.sync.localPort
import app.housemove.sync.read
import app.housemove.sync.readFully
import app.housemove.sync.readInt
import app.housemove.sync.readLong
import app.housemove.sync.readUTF
import app.housemove.sync.soTimeout
import app.housemove.sync.tcpNoDelay
import app.housemove.sync.write
import app.housemove.sync.writeInt
import app.housemove.sync.writeLong
import app.housemove.sync.writeUTF
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * The sync's sockets (sync/Net.kt) on 127.0.0.1, on every platform: java.net on the JVM, POSIX on the iPhone. They
 * must carry the sync wire both ways, end and time out the way WifiSync expects, and be closed cleanly by either end.
 */
class SocketsCommonTest {
    /** Runs [serve] on the next connection to a new listening socket, on a thread of its own; gives the port. */
    private class OneServer(serve: (Socket) -> Unit) {
        val server = loopbackServer()
        val port = server.localPort
        val done = CountDownLatch(1)

        /** Read only once [done] is open, which the thread counts down after it is written. */
        private var failure: Throwable? = null

        init {
            startThread("test-server") {
                try {
                    server.accept().use { serve(it) }
                } catch (e: Throwable) {
                    failure = e
                } finally {
                    done.countDown()
                }
            }
        }

        fun finish() {
            assertTrue(done.awaitMillis(20_000), "the server did not finish")
            failure?.let { throw AssertionError("the server failed: $it", it) }
            server.close()
        }
    }

    private fun connected(port: Int): Socket = Socket().also { it.connect("127.0.0.1", port, 4000) }

    @Test
    fun theSyncWireGoesBothWays() {
        val photo = Random(5).nextBytes(200_000)
        val s = OneServer { c ->
            c.soTimeout = 10_000
            c.tcpNoDelay = true
            assertEquals(10_000, c.soTimeout)
            assertTrue(c.tcpNoDelay)
            val din = c.dataIn()
            val out = c.dataOut()
            assertEquals("HMSYNC3", din.readUTF())
            assertEquals("Priya 🏠", din.readUTF())
            assertEquals(5, din.readInt())
            assertEquals(Long.MIN_VALUE, din.readLong())
            val got = ByteArray(photo.size).also { din.readFully(it) }
            assertContentEquals(photo, got)
            out.writeUTF("OK")
            out.write(got)
            out.flush()
        }
        connected(s.port).use { c ->
            c.soTimeout = 10_000
            c.tcpNoDelay = true
            val out = c.dataOut()
            out.writeUTF("HMSYNC3")
            out.writeUTF("Priya 🏠")
            out.writeInt(5)
            out.writeLong(Long.MIN_VALUE)
            out.write(photo)
            out.flush()
            val din = c.dataIn()
            assertEquals("OK", din.readUTF())
            val back = ByteArray(photo.size).also { din.readFully(it) }
            assertContentEquals(photo, back)
            // The other end has closed: the end of the stream.
            assertEquals(-1, din.read(ByteArray(10), 0, 10))
            assertFailsWith<EOFException> { din.readInt() }
        }
        s.finish()
    }

    @Test
    fun nothingGoesUntilFlushed() {
        val arrived = CountDownLatch(1)
        val s = OneServer { c ->
            c.soTimeout = 300
            val din = c.dataInUnbuffered()
            // Written but not flushed: nothing comes.
            assertFailsWith<SocketTimeoutException> { din.readUTF() }
            c.soTimeout = 10_000
            assertEquals("now", din.readUTF())
            arrived.countDown()
        }
        connected(s.port).use { c ->
            val out = c.dataOut()
            out.writeUTF("now")
            sleepMillis(1_000)
            out.flush()
            assertTrue(arrived.awaitMillis(10_000))
        }
        s.finish()
    }

    @Test
    fun aReadThatWaitsTooLongTimesOut() {
        val release = CountDownLatch(1)
        val s = OneServer { release.awaitMillis(10_000) }
        connected(s.port).use { c ->
            c.soTimeout = 500
            val start = TimeSource.Monotonic.markNow()
            assertFailsWith<SocketTimeoutException> { c.dataIn().readUTF() }
            val waited = start.elapsedNow().inWholeMilliseconds
            assertTrue(waited in 400..5_000, "waited $waited ms")
        }
        release.countDown()
        s.finish()
    }

    @Test
    fun nobodyListeningIsAnIOExceptionAtOnce() {
        val server = loopbackServer()
        val port = server.localPort
        server.close()
        val start = TimeSource.Monotonic.markNow()
        val c = Socket()
        assertFailsWith<IOException> { c.connect("127.0.0.1", port, 4000) }
        c.close()
        c.close()
        assertTrue(start.elapsedNow().inWholeMilliseconds < 3_000)
    }

    @Test
    fun closingTheListeningSocketEndsTheWaitInAccept() {
        val server = loopbackServer()
        val ended = CountDownLatch(1)
        var thrown: Throwable? = null
        startThread("accepting") {
            try {
                server.accept().close()
            } catch (e: Throwable) {
                thrown = e
            }
            ended.countDown()
        }
        sleepMillis(300)
        server.close()
        assertTrue(ended.awaitMillis(5_000), "accept still waits after the socket was closed")
        assertTrue(thrown is IOException, "accept ended with $thrown")
        assertFailsWith<IOException> { server.accept() }
        server.close()
    }

    @Test
    fun closingASocketWhileAnotherThreadReadsEndsTheRead() {
        val release = CountDownLatch(1)
        val s = OneServer { release.awaitMillis(10_000) }
        val c = connected(s.port)
        val ended = CountDownLatch(1)
        var thrown: Throwable? = null
        startThread("reading") {
            try {
                c.dataIn().readUTF()
            } catch (e: Throwable) {
                thrown = e
            }
            ended.countDown()
        }
        sleepMillis(300)
        c.close()
        assertTrue(ended.awaitMillis(5_000), "the read still waits after the socket was closed")
        assertTrue(thrown is SocketException || thrown is EOFException, "the read ended with $thrown")
        assertFailsWith<IOException> { c.dataOut().apply { writeUTF("x") }.flush() }
        release.countDown()
        s.finish()
    }

    @Test
    fun writingToAPhoneThatHasGoneIsASocketExceptionNotACrash() {
        val s = OneServer { }
        val c = connected(s.port)
        s.finish()
        val out = c.dataOut()
        val big = ByteArray(64 * 1024)
        assertFailsWith<IOException> {
            // The first writes may still be taken in; the connection's end shows within a few.
            repeat(200) {
                out.write(big)
                out.flush()
                sleepMillis(5)
            }
        }
        c.close()
    }
}
