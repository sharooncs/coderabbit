package app.housemove.data

/**
 * Base64 as java.util.Base64's basic encoder and decoder (RFC 4648, the standard alphabet) write and read it, for the
 * iPhone (Android and the JVM use java.util.Base64 itself). The encoder pads and breaks no lines. The decoder takes what
 * Java's takes, with the same errors: the padding may be left out, but a wrong ending, a dangling character or anything
 * after the padding is refused, and so is any character outside the alphabet (a line break included); bits left over in
 * the last character are ignored. JavaBase64JvmTest checks both against java.util.Base64.
 */
internal object JavaBase64 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    private val VALUES = IntArray(256) { -1 }.also { v ->
        for (i in ALPHABET.indices) v[ALPHABET[i].code] = i
        v['='.code] = -2
    }

    fun encode(b: ByteArray): String {
        val out = StringBuilder((b.size + 2) / 3 * 4)
        var i = 0
        while (i + 3 <= b.size) {
            val n = ((b[i].toInt() and 0xff) shl 16) or ((b[i + 1].toInt() and 0xff) shl 8) or (b[i + 2].toInt() and 0xff)
            out.append(ALPHABET[n ushr 18]).append(ALPHABET[(n ushr 12) and 63]).append(ALPHABET[(n ushr 6) and 63]).append(ALPHABET[n and 63])
            i += 3
        }
        when (b.size - i) {
            1 -> {
                val n = (b[i].toInt() and 0xff) shl 16
                out.append(ALPHABET[n ushr 18]).append(ALPHABET[(n ushr 12) and 63]).append("==")
            }
            2 -> {
                val n = ((b[i].toInt() and 0xff) shl 16) or ((b[i + 1].toInt() and 0xff) shl 8)
                out.append(ALPHABET[n ushr 18]).append(ALPHABET[(n ushr 12) and 63]).append(ALPHABET[(n ushr 6) and 63]).append('=')
            }
        }
        return out.toString()
    }

    /** java.util.Base64.getDecoder().decode(text): the text as ISO-8859-1 bytes (anything beyond is '?'), then decode0. */
    fun decode(text: String): ByteArray {
        val src = ByteArray(text.length) { val c = text[it].code; (if (c > 0xff) '?'.code else c).toByte() }
        val sl = src.size
        if (sl == 0) return ByteArray(0)
        if (sl < 2) throw IllegalArgumentException("Input byte[] should at least have 2 bytes for base64 bytes")
        val out = ByteArray(sl / 4 * 3 + 3)
        var dp = 0
        var bits = 0
        var shiftto = 18
        var sp = 0
        while (sp < sl) {
            val c = src[sp++].toInt() and 0xff
            val b = VALUES[c]
            if (b < 0) {
                if (b == -2) {
                    // "=" where a group starts, or "xx=" not followed by a second "=", is a wrong ending; a dangling "x=" is refused below.
                    if (shiftto == 6 && (sp == sl || src[sp++] != '='.code.toByte()) || shiftto == 18) {
                        throw IllegalArgumentException("Input byte array has wrong 4-byte ending unit")
                    }
                    break
                }
                throw IllegalArgumentException("Illegal base64 character " + src[sp - 1].toInt().toString(16))
            }
            bits = bits or (b shl shiftto)
            shiftto -= 6
            if (shiftto < 0) {
                out[dp++] = (bits shr 16).toByte()
                out[dp++] = (bits shr 8).toByte()
                out[dp++] = bits.toByte()
                shiftto = 18
                bits = 0
            }
        }
        when (shiftto) {
            6 -> out[dp++] = (bits shr 16).toByte()
            0 -> {
                out[dp++] = (bits shr 16).toByte()
                out[dp++] = (bits shr 8).toByte()
            }
            12 -> throw IllegalArgumentException("Last unit does not have enough valid bits")
        }
        if (sp < sl) throw IllegalArgumentException("Input byte array has incorrect ending byte at $sp")
        return out.copyOf(dp)
    }
}
