package app.housemove.data

import kotlin.math.floor
import kotlin.math.log10

/**
 * Text as the JVM writes and reads it, in common Kotlin, for the iPhone (step 1 of design/ios-plan.md).
 *
 * An iPhone has to make the same bytes and text as an Android phone on 2.1.1: the same UTF-8 (a lone surrogate as
 * '?'), the same modified UTF-8 on the sync wire (DataOutputStream.writeUTF), the same Double text in records, which
 * decides merges, the same URL encoding in invites and the same ISO dates. Kotlin/Native's own functions differ in
 * places, so these are written out here, rule for rule.
 *
 * Android and the JVM keep calling the JVM's own functions (Platform.jvm.kt and sync/Net.jvm.kt); the iPhone calls
 * these (Platform.ios.kt). The golden fixtures check these on the JVM and on the iPhone simulator, and
 * JavaTextTest (jvmTest) checks them against the JVM's own functions on random input.
 */
internal object JavaText {
    // ---- UTF-8 ---------------------------------------------------------------------------------------------------------

    /** Standard UTF-8, as String.toByteArray() writes it on the JVM: each lone surrogate becomes '?'. */
    fun utf8(s: String): ByteArray {
        val out = ByteBuilder(s.length + 16)
        var i = 0
        while (i < s.length) {
            val c = s[i].code
            when {
                c < 0x80 -> out.add(c)
                c < 0x800 -> {
                    out.add(0xC0 or (c shr 6))
                    out.add(0x80 or (c and 0x3F))
                }
                c in 0xD800..0xDBFF && i + 1 < s.length && s[i + 1].code in 0xDC00..0xDFFF -> {
                    val cp = 0x10000 + ((c - 0xD800) shl 10) + (s[i + 1].code - 0xDC00)
                    out.add(0xF0 or (cp shr 18))
                    out.add(0x80 or ((cp shr 12) and 0x3F))
                    out.add(0x80 or ((cp shr 6) and 0x3F))
                    out.add(0x80 or (cp and 0x3F))
                    i++
                }
                c in 0xD800..0xDFFF -> out.add('?'.code)
                else -> {
                    out.add(0xE0 or (c shr 12))
                    out.add(0x80 or ((c shr 6) and 0x3F))
                    out.add(0x80 or (c and 0x3F))
                }
            }
            i++
        }
        return out.toByteArray()
    }

    /**
     * UTF-8 decoded as the JVM's String(bytes, UTF_8) and ByteArray.decodeToString() decode it: what is not UTF-8 becomes
     * U+FFFD, a piece at a time, in the JVM's pieces (an encoded surrogate is one piece of three bytes, an invalid first
     * byte one of one byte, a sequence cut short at the end one piece).
     */
    fun decodeUtf8(b: ByteArray, off: Int = 0, len: Int = b.size - off): String {
        val out = StringBuilder(len)
        var sp = off
        val sl = off + len
        fun cont(x: Int) = (x and 0xC0) == 0x80
        while (sp < sl) {
            val b1 = b[sp++].toInt()
            when {
                b1 >= 0 -> out.append(b1.toChar())
                (b1 shr 5) == -2 && (b1 and 0x1E) != 0 -> {
                    if (sp < sl) {
                        val b2 = b[sp++].toInt()
                        if (!cont(b2)) {
                            out.append(REPL)
                            sp--
                        } else {
                            out.append((((b1 and 0x1F) shl 6) or (b2 and 0x3F)).toChar())
                        }
                        continue
                    }
                    out.append(REPL)
                    break
                }
                (b1 shr 4) == -2 -> {
                    if (sp + 1 < sl) {
                        val b2 = b[sp++].toInt()
                        val b3 = b[sp++].toInt()
                        if ((b1 == 0xE0.toByte().toInt() && (b2 and 0xE0) == 0x80) || !cont(b2) || !cont(b3)) {
                            out.append(REPL)
                            sp -= 3
                            sp += malformed3(b, sp)
                        } else {
                            val c = (((b1 and 0x0F) shl 12) or ((b2 and 0x3F) shl 6) or (b3 and 0x3F)).toChar()
                            out.append(if (c.isSurrogate()) REPL else c)
                        }
                        continue
                    }
                    if (sp < sl && isMalformed3Of2(b1, b[sp].toInt())) {
                        out.append(REPL)
                        continue
                    }
                    out.append(REPL)
                    break
                }
                (b1 shr 3) == -2 -> {
                    if (sp + 2 < sl) {
                        val b2 = b[sp++].toInt()
                        val b3 = b[sp++].toInt()
                        val b4 = b[sp++].toInt()
                        val uc = ((b1 and 0x07) shl 18) or ((b2 and 0x3F) shl 12) or ((b3 and 0x3F) shl 6) or (b4 and 0x3F)
                        if (!cont(b2) || !cont(b3) || !cont(b4) || uc !in 0x10000..0x10FFFF) {
                            out.append(REPL)
                            sp -= 4
                            sp += malformed4(b, sp)
                        } else {
                            out.append((0xD800 + ((uc - 0x10000) shr 10)).toChar())
                            out.append((0xDC00 + ((uc - 0x10000) and 0x3FF)).toChar())
                        }
                        continue
                    }
                    val u1 = b1 and 0xFF
                    if (u1 > 0xF4 || (sp < sl && isMalformed4Of2(u1, b[sp].toInt() and 0xFF))) {
                        out.append(REPL)
                        continue
                    }
                    sp++
                    out.append(REPL)
                    if (sp < sl && !cont(b[sp].toInt())) continue
                    break
                }
                else -> out.append(REPL)
            }
        }
        return out.toString()
    }

    private const val REPL = '�'

    private fun malformed3(b: ByteArray, at: Int): Int {
        val b1 = b[at].toInt()
        val b2 = b[at + 1].toInt()
        return if ((b1 == 0xE0.toByte().toInt() && (b2 and 0xE0) == 0x80) || (b2 and 0xC0) != 0x80) 1 else 2
    }

    private fun isMalformed3Of2(b1: Int, b2: Int): Boolean = (b1 == 0xE0.toByte().toInt() && (b2 and 0xE0) == 0x80) || (b2 and 0xC0) != 0x80

    private fun malformed4(b: ByteArray, at: Int): Int {
        val b1 = b[at].toInt() and 0xFF
        val b2 = b[at + 1].toInt() and 0xFF
        if (b1 > 0xF4 || (b1 == 0xF0 && (b2 < 0x90 || b2 > 0xBF)) || (b1 == 0xF4 && (b2 and 0xF0) != 0x80) || (b2 and 0xC0) != 0x80) return 1
        return if ((b[at + 2].toInt() and 0xC0) != 0x80) 2 else 3
    }

    private fun isMalformed4Of2(b1: Int, b2: Int): Boolean =
        (b1 == 0xF0 && (b2 < 0x90 || b2 > 0xBF)) || (b1 == 0xF4 && (b2 and 0xF0) != 0x80) || (b2 and 0xC0) != 0x80

    // ---- modified UTF-8 (DataOutputStream.writeUTF, DataInputStream.readUTF) ------------------------------------------

    /**
     * [s] as DataOutputStream.writeUTF writes it: the length in two big-endian bytes, then each UTF-16 code unit on its
     * own (NUL as C0 80, so a surrogate pair is two groups of three bytes and a lone surrogate is kept). Throws
     * UTFDataFormatException, as writeUTF does, when that is more than 65,535 bytes.
     */
    fun modifiedUtf8(s: String): ByteArray {
        var utflen = s.length.toLong()
        for (ch in s) {
            val c = ch.code
            if (c >= 0x80 || c == 0) utflen += if (c >= 0x800) 2 else 1
        }
        if (utflen > 65535) throw UTFDataFormatException("encoded string too long: $utflen bytes")
        val out = ByteArray(utflen.toInt() + 2)
        out[0] = (utflen shr 8).toByte()
        out[1] = utflen.toByte()
        var n = 2
        for (ch in s) {
            val c = ch.code
            when {
                c in 1..0x7F -> out[n++] = c.toByte()
                c >= 0x800 -> {
                    out[n++] = (0xE0 or ((c shr 12) and 0x0F)).toByte()
                    out[n++] = (0x80 or ((c shr 6) and 0x3F)).toByte()
                    out[n++] = (0x80 or (c and 0x3F)).toByte()
                }
                else -> {
                    out[n++] = (0xC0 or ((c shr 6) and 0x1F)).toByte()
                    out[n++] = (0x80 or (c and 0x3F)).toByte()
                }
            }
        }
        return out
    }

    /**
     * The text of the [utflen] bytes that follow a writeUTF length, as DataInputStream.readUTF reads them: a raw zero
     * byte and an overlong two-byte form are taken; four-byte UTF-8, a stray continuation byte or a character cut short
     * throw UTFDataFormatException.
     */
    fun readModifiedUtf8(b: ByteArray, utflen: Int = b.size): String {
        val chars = CharArray(utflen)
        var count = 0
        var n = 0
        while (count < utflen) {
            val c = b[count].toInt() and 0xFF
            when (c shr 4) {
                0, 1, 2, 3, 4, 5, 6, 7 -> {
                    count++
                    chars[n++] = c.toChar()
                }
                12, 13 -> {
                    count += 2
                    if (count > utflen) throw UTFDataFormatException("malformed input: partial character at end")
                    val c2 = b[count - 1].toInt()
                    if ((c2 and 0xC0) != 0x80) throw UTFDataFormatException("malformed input around byte $count")
                    chars[n++] = (((c and 0x1F) shl 6) or (c2 and 0x3F)).toChar()
                }
                14 -> {
                    count += 3
                    if (count > utflen) throw UTFDataFormatException("malformed input: partial character at end")
                    val c2 = b[count - 2].toInt()
                    val c3 = b[count - 1].toInt()
                    if ((c2 and 0xC0) != 0x80 || (c3 and 0xC0) != 0x80) throw UTFDataFormatException("malformed input around byte ${count - 1}")
                    chars[n++] = (((c and 0x0F) shl 12) or ((c2 and 0x3F) shl 6) or (c3 and 0x3F)).toChar()
                }
                else -> throw UTFDataFormatException("malformed input around byte $count")
            }
        }
        return chars.concatToString(0, n)
    }

    // ---- Double.toString ---------------------------------------------------------------------------------------------

    /**
     * [d] as the JVM's Double.toString (Java 19 and later) writes it: the shortest decimal that reads back as [d], the
     * closest of those (when the shortest has one digit, the closest of one or two digits), plain from 10^-3 up to
     * 10^7 ("0.001", "12.5", "9999999.0") and otherwise as "1.0E7" or "1.0E-4". Merges compare record text with these in
     * it, so an iPhone must write exactly this (see golden/README.md, doubles.json and records.json).
     */
    fun doubleText(d: Double): String {
        if (d.isNaN()) return "NaN"
        if (d == Double.POSITIVE_INFINITY) return "Infinity"
        if (d == Double.NEGATIVE_INFINITY) return "-Infinity"
        val bits = d.toRawBits()
        val sign = if (bits < 0) "-" else ""
        if (d == 0.0) return sign + "0.0"
        val (digits, last) = shortestDecimal(bits and Long.MAX_VALUE)
        return sign + layOut(digits, last)
    }

    /** "1.25E-5" or "0.00125" from the digits of a decimal and the power of ten of its last digit. */
    private fun layOut(digits: String, last: Int): String {
        val n = digits.length
        val p = last + n - 1
        return when {
            p in 0..6 -> if (n <= p + 1) digits + "0".repeat(p + 1 - n) + ".0" else digits.substring(0, p + 1) + "." + digits.substring(p + 1)
            p in -3..-1 -> "0." + "0".repeat(-p - 1) + digits
            else -> digits.substring(0, 1) + "." + (if (n > 1) digits.substring(1) else "0") + "E" + p
        }
    }

    /**
     * The decimal Double.toString writes for the positive finite double with these [bits]: its digits, without trailing
     * zeros, and the power of ten of the last one. Worked out exactly, with whole numbers of any size.
     */
    private fun shortestDecimal(bits: Long): Pair<String, Int> {
        val field = (bits ushr 52).toInt()
        val mantissa = bits and 0xFFFFFFFFFFFFFL
        val f = if (field == 0) mantissa else mantissa or (1L shl 52)
        val e = if (field == 0) -1074 else field - 1075
        // In units of 2^(e-2): the double, and the ends of the interval of values that round to it (IEEE nearest-even:
        // the ends belong to it when its significand is even). Below a power of two the next double down is closer.
        val lowerCloser = mantissa == 0L && field > 1
        val unitExp = e - 2
        val x = Big.of(4 * f)
        val lo = Big.of(4 * f - if (lowerCloser) 1 else 2)
        val hi = Big.of(4 * f + 2)
        val inclusive = f % 2 == 0L
        val scale = Scale(unitExp)

        // The coarsest power of ten Q that has a multiple inside the interval: its multiples there are the shortest decimals.
        val estimate = floor(log10(Double.fromBits(bits))).toInt()
        var q = estimate + 2
        var range: LongRange
        while (true) {
            range = multiplesInside(scale, lo, hi, inclusive, q)
            if (!range.isEmpty()) break
            q--
        }
        val shortest = range.first.toString().length
        val candidates = ArrayList<Pair<Long, Int>>()
        if (shortest >= 2) {
            for (c in closestTwo(scale, x, q, range)) candidates += c to q
        } else {
            // One digit: the closest of the decimals with one or two digits. Those at or above 10^Q are multiples of
            // 10^(Q-1); those below it (whose first digit is one place lower) multiples of 10^(Q-2).
            val above = multiplesInside(scale, lo, hi, inclusive, q - 1).let { r ->
                val from = maxOf(r.first, 10L)
                if (from > r.last) LongRange.EMPTY else from..r.last
            }
            for (c in closestTwo(scale, x, q - 1, above)) candidates += c to q - 1
            val below = multiplesInside(scale, lo, hi, inclusive, q - 2).let { r ->
                // Below 10^Q means c * 10^(Q-2) < 10^Q, c < 100.
                val to = minOf(r.last, 99L)
                if (r.first > to) LongRange.EMPTY else r.first..to
            }
            for (c in closestTwo(scale, x, q - 2, below)) candidates += c to q - 2
        }
        // The closest to the double; of two as close, the one whose last digit is even.
        val fine = candidates.minOf { it.second }
        fun atFine(c: Pair<Long, Int>): Big = Big.of(c.first).times(Big.TEN.pow(c.second - fine))
        val xAtFine = scale.of(x, fine)
        val best = candidates.minWithOrNull(
            Comparator { a, b ->
                val da = distance(atFine(a), xAtFine)
                val db = distance(atFine(b), xAtFine)
                val byDistance = da.compareTo(db)
                if (byDistance != 0) byDistance else lastDigitOdd(a).compareTo(lastDigitOdd(b))
            },
        )!!
        var c = best.first
        var last = best.second
        while (c % 10 == 0L) {
            c /= 10
            last++
        }
        return c.toString() to last
    }

    private fun lastDigitOdd(c: Pair<Long, Int>): Int {
        var v = c.first
        while (v % 10 == 0L) v /= 10
        return (v % 2).toInt()
    }

    /** |a - b|, with b given as a value over a denominator: compared as a * den against num. */
    private fun distance(a: Big, b: Fraction): Fraction {
        val scaled = a.times(b.den)
        return Fraction(if (scaled >= b.num) scaled - b.num else b.num - scaled, b.den)
    }

    /** A value num/den; [compareTo] only compares values with the same den, which is all this needs. */
    private class Fraction(val num: Big, val den: Big) : Comparable<Fraction> {
        override fun compareTo(other: Fraction): Int = num.compareTo(other.num)
    }

    /** Values in units of 2^[unitExp], set against powers of ten. */
    private class Scale(private val unitExp: Int) {
        /** [n] units divided by 10^[q], as a fraction. */
        fun of(n: Big, q: Int): Fraction {
            var num = n
            var den = Big.ONE
            if (unitExp >= 0) num = num.shl(unitExp) else den = den.shl(-unitExp)
            if (q >= 0) den = den.times(Big.TEN.pow(q)) else num = num.times(Big.TEN.pow(-q))
            return Fraction(num, den)
        }
    }

    /** The whole numbers c for which c * 10^[q] lies in the interval from [lo] to [hi] units. */
    private fun multiplesInside(scale: Scale, lo: Big, hi: Big, inclusive: Boolean, q: Int): LongRange {
        val l = scale.of(lo, q)
        val h = scale.of(hi, q)
        val (lq, lExact) = divide(l.num, l.den)
        val (hq, hExact) = divide(h.num, h.den)
        val first = if (lExact && inclusive) lq else lq + 1
        val last = if (hExact && !inclusive) hq - 1 else hq
        return if (first > last) LongRange.EMPTY else first..last
    }

    /** Of the whole numbers in [range], the one or two closest to the double [x] at 10^[q]. */
    private fun closestTwo(scale: Scale, x: Big, q: Int, range: LongRange): List<Long> {
        if (range.isEmpty()) return emptyList()
        val v = scale.of(x, q)
        val (t, _) = divide(v.num, v.den)
        return listOf(t, t + 1).map { it.coerceIn(range.first, range.last) }.distinct()
    }

    /** floor(a / b), which must be below 2^62, and whether it divides exactly. */
    private fun divide(a: Big, b: Big): Pair<Long, Boolean> {
        var rest = a
        var quotient = 0L
        var bit = 0
        while (bit < 62 && b.shl(bit + 1) <= rest) bit++
        while (bit >= 0) {
            val part = b.shl(bit)
            if (part <= rest) {
                rest -= part
                quotient = quotient or (1L shl bit)
            }
            bit--
        }
        return quotient to rest.isZero()
    }

    /** A whole number of any size, zero or more, just big enough for [shortestDecimal]: 32-bit limbs, lowest first. */
    private class Big private constructor(private val mag: IntArray) : Comparable<Big> {
        fun isZero() = mag.isEmpty()

        fun shl(bits: Int): Big {
            if (mag.isEmpty() || bits == 0) return this
            val words = bits / 32
            val rem = bits % 32
            val out = IntArray(mag.size + words + 1)
            for (i in mag.indices) {
                val v = (mag[i].toLong() and MASK) shl rem
                out[i + words] = out[i + words] or v.toInt()
                out[i + words + 1] = (v ushr 32).toInt()
            }
            return make(out)
        }

        fun times(o: Big): Big {
            if (mag.isEmpty() || o.mag.isEmpty()) return ZERO
            val out = IntArray(mag.size + o.mag.size)
            for (i in mag.indices) {
                var carry = 0L
                val a = mag[i].toLong() and MASK
                for (j in o.mag.indices) {
                    val t = a * (o.mag[j].toLong() and MASK) + (out[i + j].toLong() and MASK) + carry
                    out[i + j] = t.toInt()
                    carry = t ushr 32
                }
                out[i + o.mag.size] = carry.toInt()
            }
            return make(out)
        }

        operator fun minus(o: Big): Big {
            val out = IntArray(mag.size)
            var borrow = 0L
            for (i in mag.indices) {
                var t = (mag[i].toLong() and MASK) - (if (i < o.mag.size) o.mag[i].toLong() and MASK else 0L) - borrow
                borrow = if (t < 0) 1 else 0
                if (t < 0) t += 1L shl 32
                out[i] = t.toInt()
            }
            check(borrow == 0L)
            return make(out)
        }

        fun pow(n: Int): Big {
            require(this === TEN)
            return powersOfTen(n)
        }

        override fun compareTo(other: Big): Int {
            if (mag.size != other.mag.size) return mag.size.compareTo(other.mag.size)
            for (i in mag.indices.reversed()) {
                val a = mag[i].toLong() and MASK
                val b = other.mag[i].toLong() and MASK
                if (a != b) return a.compareTo(b)
            }
            return 0
        }

        companion object {
            private const val MASK = 0xFFFFFFFFL
            val ZERO = Big(IntArray(0))
            val ONE = of(1)
            val TEN = of(10)

            fun of(v: Long): Big {
                require(v >= 0)
                return make(intArrayOf(v.toInt(), (v ushr 32).toInt()))
            }

            private fun make(words: IntArray): Big {
                var n = words.size
                while (n > 0 && words[n - 1] == 0) n--
                return Big(if (n == words.size) words else words.copyOf(n))
            }

            private val powers: List<Big> by lazy {
                val out = ArrayList<Big>(400)
                var p = ONE
                for (i in 0 until 400) {
                    out += p
                    p = p.times(TEN)
                }
                out
            }

            private fun powersOfTen(n: Int): Big = powers[n]
        }
    }

    // ---- URLEncoder / URLDecoder -------------------------------------------------------------------------------------

    private fun keptByUrlEncoder(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == ' ' || c == '-' || c == '_' || c == '.' || c == '*'

    /** java.net.URLEncoder.encode(text, "UTF-8"): a space as '+', a-z A-Z 0-9 - _ . * as they are, the rest as %XX of UTF-8 ('?' for a lone surrogate). */
    fun urlEncode(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (keptByUrlEncoder(c)) {
                out.append(if (c == ' ') '+' else c)
                i++
                continue
            }
            val start = i
            while (i < text.length && !keptByUrlEncoder(text[i])) i++
            for (byte in utf8(text.substring(start, i))) {
                val v = byte.toInt() and 0xFF
                out.append('%').append(HEX_UPPER[v shr 4]).append(HEX_UPPER[v and 0xF])
            }
        }
        return out.toString()
    }

    private const val HEX_UPPER = "0123456789ABCDEF"

    /**
     * java.net.URLDecoder.decode(text, "UTF-8"): '+' as a space, each run of %XX as UTF-8 (what is not UTF-8 as U+FFFD).
     * A '%' without two hex digits after it throws IllegalArgumentException. As Integer.parseInt reads them, the two
     * may be any digits Character.digit knows (full-width ones too), or a sign and one digit.
     */
    fun urlDecode(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        val n = text.length
        while (i < n) {
            when (val c = text[i]) {
                '+' -> {
                    out.append(' ')
                    i++
                }
                '%' -> {
                    val bytes = ByteBuilder((n - i) / 3)
                    var at = c
                    while (i + 2 < n && at == '%') {
                        val v = hexByte(text[i + 1], text[i + 2])
                        if (v < 0) throw IllegalArgumentException("URLDecoder: Illegal hex characters in escape (%) pattern - negative value")
                        bytes.add(v)
                        i += 3
                        if (i < n) at = text[i]
                    }
                    if (i < n && at == '%') throw IllegalArgumentException("URLDecoder: Incomplete trailing escape (%) pattern")
                    out.append(decodeUtf8(bytes.toByteArray()))
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    /** Integer.parseInt of two characters in base 16, as URLDecoder calls it. */
    private fun hexByte(a: Char, b: Char): Int {
        val bad = { throw IllegalArgumentException("URLDecoder: Illegal hex characters in escape (%) pattern - for input string: \"$a$b\"") }
        if (a == '-' || a == '+') {
            val d = b.digitToIntOrNull(16) ?: bad()
            return if (a == '-') -d else d
        }
        val hi = a.digitToIntOrNull(16) ?: bad()
        val lo = b.digitToIntOrNull(16) ?: bad()
        return hi * 16 + lo
    }

    // ---- ISO dates (LocalDate) ---------------------------------------------------------------------------------------

    /** LocalDate.ofEpochDay([day]).toString(): "2026-06-01"; a year past 9999 with '+', one before year 0 with '-'. */
    fun isoDate(day: Long): String {
        // Days to the proleptic Gregorian calendar (H. Hinnant's civil_from_days).
        val z = day + 719468
        val era = (if (z >= 0) z else z - 146096) / 146097
        val doe = z - era * 146097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        val y = yoe + era * 400 + if (m <= 2) 1 else 0
        val year = when {
            y > 9999 -> "+$y"
            y < 0 -> "-" + (-y).toString().padStart(4, '0')
            else -> y.toString().padStart(4, '0')
        }
        return year + "-" + m.toString().padStart(2, '0') + "-" + d.toString().padStart(2, '0')
    }

    /**
     * LocalDate.parse([text]).toEpochDay() for text of the form "2026-06-01" (four-digit year), which is all
     * DailyBackup reads: a day that does not exist, or any other text, throws IllegalArgumentException.
     */
    fun parseIsoDate(text: String): Long {
        val ok = text.length == 10 && text[4] == '-' && text[7] == '-' &&
            text.withIndex().all { (i, ch) -> i == 4 || i == 7 || ch in '0'..'9' }
        if (!ok) throw IllegalArgumentException("Text '$text' could not be parsed")
        val y = text.substring(0, 4).toLong()
        val m = text.substring(5, 7).toInt()
        val d = text.substring(8, 10).toInt()
        val leap = y % 4 == 0L && (y % 100 != 0L || y % 400 == 0L)
        val days = when (m) {
            1, 3, 5, 7, 8, 10, 12 -> 31
            4, 6, 9, 11 -> 30
            2 -> if (leap) 29 else 28
            else -> throw IllegalArgumentException("Text '$text' could not be parsed: invalid month")
        }
        if (d !in 1..days) throw IllegalArgumentException("Text '$text' could not be parsed: invalid day")
        // H. Hinnant's days_from_civil.
        val yy = if (m <= 2) y - 1 else y
        val era = (if (yy >= 0) yy else yy - 399) / 400
        val yoe = yy - era * 400
        val mp = if (m > 2) m - 3 else m + 9
        val doy = (153 * mp + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097 + doe - 719468
    }

    /** A growing array of bytes. */
    private class ByteBuilder(capacity: Int) {
        private var bytes = ByteArray(maxOf(capacity, 16))
        private var size = 0

        fun add(v: Int) {
            if (size == bytes.size) bytes = bytes.copyOf(bytes.size * 2)
            bytes[size++] = v.toByte()
        }

        fun toByteArray(): ByteArray = bytes.copyOf(size)
    }
}
