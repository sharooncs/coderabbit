package app.housemove.sync

import app.housemove.data.EOFException
import app.housemove.data.IOException
import app.housemove.data.inputStreamOf
import app.housemove.data.outputStreamOf

/**
 * The encrypted channel two phones sync through once a handshake ([Handshake]) is done (design/encryption.md, Part 2):
 * today's sync ([WifiSync]'s exchange) runs inside it unchanged, reading and writing [dataIn] and [dataOut] as it reads
 * and writes a socket.
 *
 * On the wire, each frame is a two-byte length (big-endian) and that many bytes of AES-256-GCM ciphertext and tag: at
 * most [Noise.MAX_MESSAGE] bytes, so at most [MAX_PLAIN] bytes of the sync in each. A frame goes when [dataOut] is
 * flushed or when [MAX_PLAIN] bytes are waiting; the sync flushes at the end of every message, and photos go in many
 * frames. Each direction has its own key and counts its frames: the nonce is Noise's counter, so a frame that is
 * dropped, repeated, reordered, cut or changed on the way does not open, and the channel ends with an IOException (the
 * sync then says what it says when a connection fails). Every [rekeyEvery] frames in a direction both phones change
 * that direction's key (Noise's Rekey), so no key encrypts more than 2^20 frames (64 GB); after [maxFrames] frames the
 * channel refuses to go on rather than ever use a nonce twice. No sync comes near either.
 */
internal class SecureChannel(
    private val din: DataInputStream,
    private val out: DataOutputStream,
    private val send: CipherState,
    private val receive: CipherState,
    private val rekeyEvery: Long = REKEY_EVERY,
    private val maxFrames: Long = MAX_FRAMES,
) {
    private val outBuf = ByteArray(MAX_PLAIN)
    private var outUsed = 0
    private var sent = 0L

    private var inBuf = ByteArray(0)
    private var inAt = 0
    private var received = 0L

    /** Frames sent and received so far. */
    val framesSent: Long get() = sent
    val framesReceived: Long get() = received

    /** The sync's side of the channel: what the other phone sent, decrypted. */
    val dataIn: DataInputStream = dataInputOf(inputStreamOf(::read))

    /** The sync's side of the channel: what goes to the other phone, encrypted frame by frame. */
    val dataOut: DataOutputStream = dataOutputOf(outputStreamOf(::write, ::flush) {})

    private fun write(b: ByteArray, off: Int, len: Int) {
        var at = off
        var left = len
        while (left > 0) {
            val n = minOf(left, MAX_PLAIN - outUsed)
            b.copyInto(outBuf, outUsed, at, at + n)
            outUsed += n
            at += n
            left -= n
            if (outUsed == MAX_PLAIN) sendFrame()
        }
    }

    private fun flush() {
        if (outUsed > 0) sendFrame()
        out.flush()
    }

    private fun sendFrame() {
        if (sent >= maxFrames) throw IOException("the channel has sent all the frames it may")
        val c = send.encryptWithAd(EMPTY, outBuf.copyOf(outUsed))
        out.write(byteArrayOf((c.size ushr 8).toByte(), c.size.toByte()))
        out.write(c)
        outUsed = 0
        sent++
        if (sent % rekeyEvery == 0L) send.rekey()
    }

    /** Whether the other phone's first frame has come and opened (it then proved it holds the session's keys). */
    fun awaitFirst(): Boolean {
        if (received > 0) return true
        return nextFrame()
    }

    private fun nextFrame(): Boolean {
        val length = try {
            din.readUnsignedShort()
        } catch (_: EOFException) {
            // The other phone closed between frames: the end of what it sends.
            return false
        }
        if (length < Noise.TAG) throw IOException("not a frame")
        if (received >= maxFrames) throw IOException("the channel has received all the frames it may")
        val c = ByteArray(length)
        din.readFully(c)
        val p = receive.decryptWithAd(EMPTY, c) ?: throw IOException("a frame did not open: changed, repeated or out of order")
        received++
        if (received % rekeyEvery == 0L) receive.rekey()
        inBuf = p
        inAt = 0
        return true
    }

    private fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        while (inAt == inBuf.size) if (!nextFrame()) return -1
        val n = minOf(len, inBuf.size - inAt)
        inBuf.copyInto(b, off, inAt, inAt + n)
        inAt += n
        return n
    }

    companion object {
        /** The most bytes of the sync in one frame: the longest frame less the tag. */
        const val MAX_PLAIN = Noise.MAX_MESSAGE - Noise.TAG

        /** Both phones change a direction's key after every 2^20 frames in it. */
        const val REKEY_EVERY = 1L shl 20

        /** No direction carries more frames than this (2^40, about 64 PB): the counter never comes near reuse. */
        const val MAX_FRAMES = 1L shl 40

        private val EMPTY = ByteArray(0)
    }
}
