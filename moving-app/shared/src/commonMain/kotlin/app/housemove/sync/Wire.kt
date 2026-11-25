package app.housemove.sync

import app.housemove.data.EOFException
import app.housemove.data.JavaText

/**
 * The reading half of java.io.DataInputStream that the sync uses, in common Kotlin for the iPhone (its
 * DataInputStream, Net.ios.kt): big-endian numbers and modified UTF-8 strings, with the same errors (EOFException when
 * the bytes end early, UTFDataFormatException when a string is not modified UTF-8). [readSome] reads up to len bytes
 * into the array from off and says how many, or -1 at the end. Android and the JVM use DataInputStream itself; the
 * golden fixtures and WireTest check that the two read alike.
 */
internal class WireReader(private val readSome: (ByteArray, Int, Int) -> Int) {
    fun read(b: ByteArray, off: Int, len: Int): Int = if (len == 0) 0 else readSome(b, off, len)

    fun readFully(b: ByteArray, off: Int = 0, len: Int = b.size) {
        var n = 0
        while (n < len) {
            val count = readSome(b, off + n, len - n)
            if (count < 0) throw EOFException()
            n += count
        }
    }

    private fun readByte(): Int {
        val one = ByteArray(1)
        readFully(one)
        return one[0].toInt() and 0xFF
    }

    fun readUnsignedShort(): Int = (readByte() shl 8) or readByte()

    fun readInt(): Int {
        val b = ByteArray(4)
        readFully(b)
        return ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)
    }

    fun readLong(): Long {
        val b = ByteArray(8)
        readFully(b)
        var v = 0L
        for (x in b) v = (v shl 8) or (x.toLong() and 0xFF)
        return v
    }

    fun readUTF(): String {
        val utflen = readUnsignedShort()
        val bytes = ByteArray(utflen)
        readFully(bytes)
        return JavaText.readModifiedUtf8(bytes)
    }
}

/**
 * The writing half of java.io.DataOutputStream that the sync uses, in common Kotlin for the iPhone (its
 * DataOutputStream, Net.ios.kt): each call gives [writeAll] the bytes DataOutputStream writes for it.
 */
internal class WireWriter(private val writeAll: (ByteArray) -> Unit) {
    /** Throws UTFDataFormatException, writing nothing, when [s] takes more than 65,535 bytes, as writeUTF does. */
    fun writeUTF(s: String) = writeAll(JavaText.modifiedUtf8(s))

    fun writeInt(v: Int) = writeAll(byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()))

    fun writeLong(v: Long) = writeAll(ByteArray(8) { (v ushr (56 - 8 * it)).toByte() })

    fun write(b: ByteArray) = writeAll(b)
}
