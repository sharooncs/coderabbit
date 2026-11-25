/*
 * Copyright 2007, 2008 ZXing authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// Changed for HouseMove (2026): the part of ZXing 3.5.3's QR encoder that the app uses (com.google.zxing.qrcode
// encoder.Encoder, MatrixUtil, MaskUtil and QRCodeWriter's rendering, with the Reed-Solomon encoder and the version
// table they use), ported to common Kotlin and cut down to it: one segment in numeric, alphanumeric or byte mode
// (ISO-8859-1, no ECI), error correction M, the smallest version that fits and the mask with the lowest penalty,
// chosen as ZXing chooses them. Licence: moving-app/licenses/ZXing-Apache-2.0.txt.

package app.housemove.data

/**
 * QR codes as the app's labels, invites and sync codes are drawn, in common Kotlin, so an iPhone prints the same
 * squares as an Android phone. Android and the JVM draw with ZXing itself ([Qr.matrix]); the golden fixtures
 * (qr.json) and QrEncoderTest check that this makes the same modules, module for module.
 */
internal object QrEncoder {
    /** One QR code: its [version], [mask] and [mode] ("NUMERIC", "ALPHANUMERIC", "BYTE"), and its modules, row by row. */
    class Code(val version: Int, val mask: Int, val mode: String, val size: Int, private val modules: ByteArray) {
        /** Is the module at column [x], row [y] dark? */
        operator fun get(x: Int, y: Int): Boolean = modules[y * size + x].toInt() == 1
    }

    private enum class Mode(val bits: Int, val countBits: IntArray) {
        NUMERIC(0x1, intArrayOf(10, 12, 14)),
        ALPHANUMERIC(0x2, intArrayOf(9, 11, 13)),
        BYTE(0x4, intArrayOf(8, 16, 16));

        fun characterCountBits(version: Int): Int = countBits[if (version <= 9) 0 else if (version <= 26) 1 else 2]
    }

    /** Error correction M (ZXing's ErrorCorrectionLevel.M): its two bits in the format information. */
    private const val EC_M_BITS = 0x00

    // For each version (1 to 40), at error correction M (ZXing's Version table): the error correction codewords per
    // block, the blocks of the first group and their data codewords, then the same for the second group (0 if none).
    private val M_BLOCKS = intArrayOf(
        10, 1, 16, 0, 0, 16, 1, 28, 0, 0, 26, 1, 44, 0, 0, 18, 2, 32, 0, 0, 24, 2, 43, 0, 0,
        16, 4, 27, 0, 0, 18, 4, 31, 0, 0, 22, 2, 38, 2, 39, 22, 3, 36, 2, 37, 26, 4, 43, 1, 44,
        30, 1, 50, 4, 51, 22, 6, 36, 2, 37, 22, 8, 37, 1, 38, 24, 4, 40, 5, 41, 24, 5, 41, 5, 42,
        28, 7, 45, 3, 46, 28, 10, 46, 1, 47, 26, 9, 43, 4, 44, 26, 3, 44, 11, 45, 26, 3, 41, 13, 42,
        26, 17, 42, 0, 0, 28, 17, 46, 0, 0, 28, 4, 47, 14, 48, 28, 6, 45, 14, 46, 28, 8, 47, 13, 48,
        28, 19, 46, 4, 47, 28, 22, 45, 3, 46, 28, 3, 45, 23, 46, 28, 21, 45, 7, 46, 28, 19, 47, 10, 48,
        28, 2, 46, 29, 47, 28, 10, 46, 23, 47, 28, 14, 46, 21, 47, 28, 14, 46, 23, 47, 28, 12, 47, 26, 48,
        28, 6, 47, 34, 48, 28, 29, 46, 14, 47, 28, 13, 46, 32, 47, 28, 40, 47, 7, 48, 28, 18, 47, 31, 48,
    )

    /** The centres of the alignment patterns of each version (ZXing's Version table; none for version 1). */
    private val ALIGNMENT = arrayOf(
        intArrayOf(), intArrayOf(6, 18), intArrayOf(6, 22), intArrayOf(6, 26), intArrayOf(6, 30), intArrayOf(6, 34),
        intArrayOf(6, 22, 38), intArrayOf(6, 24, 42), intArrayOf(6, 26, 46), intArrayOf(6, 28, 50), intArrayOf(6, 30, 54),
        intArrayOf(6, 32, 58), intArrayOf(6, 34, 62), intArrayOf(6, 26, 46, 66), intArrayOf(6, 26, 48, 70),
        intArrayOf(6, 26, 50, 74), intArrayOf(6, 30, 54, 78), intArrayOf(6, 30, 56, 82), intArrayOf(6, 30, 58, 86),
        intArrayOf(6, 34, 62, 90), intArrayOf(6, 28, 50, 72, 94), intArrayOf(6, 26, 50, 74, 98),
        intArrayOf(6, 30, 54, 78, 102), intArrayOf(6, 28, 54, 80, 106), intArrayOf(6, 32, 58, 84, 110),
        intArrayOf(6, 30, 58, 86, 114), intArrayOf(6, 34, 62, 90, 118), intArrayOf(6, 26, 50, 74, 98, 122),
        intArrayOf(6, 30, 54, 78, 102, 126), intArrayOf(6, 26, 52, 78, 104, 130), intArrayOf(6, 30, 56, 82, 108, 134),
        intArrayOf(6, 34, 60, 86, 112, 138), intArrayOf(6, 30, 58, 86, 114, 142), intArrayOf(6, 34, 62, 90, 118, 146),
        intArrayOf(6, 30, 54, 78, 102, 126, 150), intArrayOf(6, 24, 50, 76, 102, 128, 154),
        intArrayOf(6, 28, 54, 80, 106, 132, 158), intArrayOf(6, 32, 58, 84, 110, 136, 162),
        intArrayOf(6, 26, 54, 82, 110, 138, 166), intArrayOf(6, 30, 58, 86, 114, 142, 170),
    )

    private fun ecPerBlock(version: Int) = M_BLOCKS[(version - 1) * 5]

    private fun numBlocks(version: Int) = M_BLOCKS[(version - 1) * 5 + 1] + M_BLOCKS[(version - 1) * 5 + 3]

    private fun totalCodewords(version: Int): Int {
        val i = (version - 1) * 5
        val ec = M_BLOCKS[i]
        return M_BLOCKS[i + 1] * (M_BLOCKS[i + 2] + ec) + M_BLOCKS[i + 3] * (M_BLOCKS[i + 4] + ec)
    }

    private fun dimension(version: Int) = 17 + 4 * version

    // ---- bits ------------------------------------------------------------------------------------------------------

    /** A growing list of bits (ZXing's BitArray, as the encoder uses it). */
    private class Bits {
        private var words = IntArray(4)
        var size = 0
            private set

        val sizeInBytes: Int get() = (size + 7) / 8

        operator fun get(i: Int): Boolean = (words[i / 32] and (1 shl (i and 0x1F))) != 0

        fun appendBit(bit: Boolean) {
            if (size == words.size * 32) words = words.copyOf(words.size * 2)
            if (bit) words[size / 32] = words[size / 32] or (1 shl (size and 0x1F))
            size++
        }

        /** The lowest [numBits] bits of [value], most significant first. */
        fun appendBits(value: Int, numBits: Int) {
            for (left in numBits - 1 downTo 0) appendBit(((value shr left) and 1) == 1)
        }

        fun appendBits(other: Bits) {
            for (i in 0 until other.size) appendBit(other[i])
        }

        /** [count] bytes from bit [bitOffset], each from its most significant bit. */
        fun toBytes(bitOffset: Int, count: Int): IntArray {
            var at = bitOffset
            return IntArray(count) {
                var v = 0
                for (j in 0 until 8) {
                    if (this[at]) v = v or (1 shl (7 - j))
                    at++
                }
                v
            }
        }
    }

    // ---- the data --------------------------------------------------------------------------------------------------

    private val ALPHANUMERIC = intArrayOf(
        36, -1, -1, -1, 37, 38, -1, -1, -1, -1, 39, 40, -1, 41, 42, 43,
        0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 44, -1, -1, -1, -1, -1,
        -1, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24,
        25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, -1, -1, -1, -1, -1,
    )

    /** The value of [c] in alphanumeric mode, or -1 when it has none. */
    private fun alphanumericCode(c: Char): Int = if (c.code in 0x20..0x5F) ALPHANUMERIC[c.code - 0x20] else -1

    /** As Encoder.chooseMode with no character set given: numeric, alphanumeric or byte, one mode for the whole text. */
    private fun chooseMode(content: String): Mode {
        var numeric = false
        var alphanumeric = false
        for (c in content) {
            when {
                c in '0'..'9' -> numeric = true
                alphanumericCode(c) != -1 -> alphanumeric = true
                else -> return Mode.BYTE
            }
        }
        return if (alphanumeric) Mode.ALPHANUMERIC else if (numeric) Mode.NUMERIC else Mode.BYTE
    }

    /** [s] in ISO-8859-1, as String.getBytes(ISO_8859_1): anything beyond it, a surrogate pair included, is one '?'. */
    private fun latin1(s: String): IntArray {
        val out = ArrayList<Int>(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i++]
            if (c.code <= 0xFF) {
                out += c.code
            } else {
                if (c.isHighSurrogate() && i < s.length && s[i].isLowSurrogate()) i++
                out += '?'.code
            }
        }
        return out.toIntArray()
    }

    private fun appendBytes(content: String, mode: Mode, bits: Bits) {
        when (mode) {
            Mode.NUMERIC -> {
                var i = 0
                while (i < content.length) {
                    val n1 = content[i] - '0'
                    if (i + 2 < content.length) {
                        bits.appendBits(n1 * 100 + (content[i + 1] - '0') * 10 + (content[i + 2] - '0'), 10)
                        i += 3
                    } else if (i + 1 < content.length) {
                        bits.appendBits(n1 * 10 + (content[i + 1] - '0'), 7)
                        i += 2
                    } else {
                        bits.appendBits(n1, 4)
                        i++
                    }
                }
            }
            Mode.ALPHANUMERIC -> {
                var i = 0
                while (i < content.length) {
                    val c1 = alphanumericCode(content[i])
                    if (i + 1 < content.length) {
                        bits.appendBits(c1 * 45 + alphanumericCode(content[i + 1]), 11)
                        i += 2
                    } else {
                        bits.appendBits(c1, 6)
                        i++
                    }
                }
            }
            Mode.BYTE -> for (b in latin1(content)) bits.appendBits(b, 8)
        }
    }

    private fun willFit(numInputBits: Int, version: Int): Boolean {
        val numDataBytes = totalCodewords(version) - ecPerBlock(version) * numBlocks(version)
        return numDataBytes >= (numInputBits + 7) / 8
    }

    private fun chooseVersion(numInputBits: Int): Int {
        for (v in 1..40) if (willFit(numInputBits, v)) return v
        throw IllegalArgumentException("Data too big")
    }

    /** As Encoder.recommendVersion: a guess at version 1's count field, then the version that fits with the right one. */
    private fun recommendVersion(mode: Mode, headerBits: Bits, dataBits: Bits): Int {
        val provisional = chooseVersion(headerBits.size + mode.characterCountBits(1) + dataBits.size)
        return chooseVersion(headerBits.size + mode.characterCountBits(provisional) + dataBits.size)
    }

    private fun terminateBits(numDataBytes: Int, bits: Bits) {
        val capacity = numDataBytes * 8
        require(bits.size <= capacity) { "data bits cannot fit in the QR Code ${bits.size} > $capacity" }
        var i = 0
        while (i < 4 && bits.size < capacity) {
            bits.appendBit(false)
            i++
        }
        val inLastByte = bits.size and 7
        if (inLastByte > 0) for (j in inLastByte until 8) bits.appendBit(false)
        val padding = numDataBytes - bits.sizeInBytes
        for (j in 0 until padding) bits.appendBits(if (j and 1 == 0) 0xEC else 0x11, 8)
        check(bits.size == capacity) { "Bits size does not equal capacity" }
    }

    // ---- Reed-Solomon over GF(256), x^8 + x^4 + x^3 + x^2 + 1 (ZXing's QR_CODE_FIELD_256) -------------------------

    private val EXP = IntArray(256)
    private val LOG = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 256) {
            EXP[i] = x
            x = x shl 1
            if (x >= 0x100) x = x xor 0x011D
        }
        for (i in 0 until 255) LOG[EXP[i]] = i
    }

    private fun multiply(a: Int, b: Int): Int = if (a == 0 || b == 0) 0 else EXP[(LOG[a] + LOG[b]) % 255]

    /** The generator polynomial of [degree] error correction codewords, highest power first: (x - a^0)(x - a^1)... */
    private fun generator(degree: Int): IntArray {
        var g = intArrayOf(1)
        for (d in 0 until degree) {
            val next = IntArray(g.size + 1)
            val root = EXP[d]
            for (i in g.indices) {
                next[i] = next[i] xor g[i]
                next[i + 1] = next[i + 1] xor multiply(g[i], root)
            }
            g = next
        }
        return g
    }

    /** The error correction codewords of [data]: the remainder of data times x^ecCount divided by the generator. */
    private fun errorCorrection(data: IntArray, ecCount: Int): IntArray {
        val g = generator(ecCount)
        val rem = IntArray(ecCount)
        for (d in data) {
            val factor = d xor rem[0]
            for (i in 0 until ecCount - 1) rem[i] = rem[i + 1] xor multiply(g[i + 1], factor)
            rem[ecCount - 1] = multiply(g[ecCount], factor)
        }
        return rem
    }

    /** As Encoder.interleaveWithECBytes: the data split into blocks, each with its error correction, interleaved. */
    private fun interleave(bits: Bits, numTotalBytes: Int, numDataBytes: Int, numBlocks: Int): Bits {
        check(bits.sizeInBytes == numDataBytes) { "Number of bits and data bytes does not match" }
        val group2 = numTotalBytes % numBlocks
        val group1 = numBlocks - group2
        val totalIn1 = numTotalBytes / numBlocks
        val dataIn1 = numDataBytes / numBlocks
        val ecIn1 = totalIn1 - dataIn1
        val ecIn2 = totalIn1 + 1 - (dataIn1 + 1)
        val blocks = ArrayList<Pair<IntArray, IntArray>>(numBlocks)
        var offset = 0
        var maxData = 0
        var maxEc = 0
        for (i in 0 until numBlocks) {
            val size = if (i < group1) dataIn1 else dataIn1 + 1
            val ecCount = if (i < group1) ecIn1 else ecIn2
            val data = bits.toBytes(8 * offset, size)
            val ec = errorCorrection(data, ecCount)
            blocks += data to ec
            maxData = maxOf(maxData, size)
            maxEc = maxOf(maxEc, ec.size)
            offset += size
        }
        check(offset == numDataBytes) { "Data bytes does not match offset" }
        val out = Bits()
        for (i in 0 until maxData) for ((data, _) in blocks) if (i < data.size) out.appendBits(data[i], 8)
        for (i in 0 until maxEc) for ((_, ec) in blocks) if (i < ec.size) out.appendBits(ec[i], 8)
        check(out.sizeInBytes == numTotalBytes) { "Interleaving error" }
        return out
    }

    // ---- the matrix (ZXing's MatrixUtil) ---------------------------------------------------------------------------

    private val FINDER = arrayOf(
        intArrayOf(1, 1, 1, 1, 1, 1, 1), intArrayOf(1, 0, 0, 0, 0, 0, 1), intArrayOf(1, 0, 1, 1, 1, 0, 1),
        intArrayOf(1, 0, 1, 1, 1, 0, 1), intArrayOf(1, 0, 1, 1, 1, 0, 1), intArrayOf(1, 0, 0, 0, 0, 0, 1),
        intArrayOf(1, 1, 1, 1, 1, 1, 1),
    )

    private val ALIGNMENT_PATTERN = arrayOf(
        intArrayOf(1, 1, 1, 1, 1), intArrayOf(1, 0, 0, 0, 1), intArrayOf(1, 0, 1, 0, 1), intArrayOf(1, 0, 0, 0, 1),
        intArrayOf(1, 1, 1, 1, 1),
    )

    private val TYPE_INFO_COORDINATES = arrayOf(
        intArrayOf(8, 0), intArrayOf(8, 1), intArrayOf(8, 2), intArrayOf(8, 3), intArrayOf(8, 4), intArrayOf(8, 5),
        intArrayOf(8, 7), intArrayOf(8, 8), intArrayOf(7, 8), intArrayOf(5, 8), intArrayOf(4, 8), intArrayOf(3, 8),
        intArrayOf(2, 8), intArrayOf(1, 8), intArrayOf(0, 8),
    )

    private const val VERSION_INFO_POLY = 0x1f25
    private const val TYPE_INFO_POLY = 0x537
    private const val TYPE_INFO_MASK_PATTERN = 0x5412

    /** A square of modules, each -1 (not set yet), 0 or 1 (ZXing's ByteMatrix). */
    private class Matrix(val size: Int) {
        val cells = ByteArray(size * size)

        operator fun get(x: Int, y: Int): Int = cells[y * size + x].toInt()

        operator fun set(x: Int, y: Int, v: Int) {
            cells[y * size + x] = v.toByte()
        }

        fun clear() = cells.fill(-1)
    }

    private fun findMsbSet(value: Int) = 32 - value.countLeadingZeroBits()

    private fun bchCode(value: Int, poly: Int): Int {
        val msbInPoly = findMsbSet(poly)
        var v = value shl (msbInPoly - 1)
        while (findMsbSet(v) >= msbInPoly) v = v xor (poly shl (findMsbSet(v) - msbInPoly))
        return v
    }

    /** The 15 bits of format information: the level and [mask] (5 bits), their BCH code (10), masked. */
    private fun typeInfoBits(mask: Int): Bits {
        val typeInfo = (EC_M_BITS shl 3) or mask
        val bits = Bits()
        bits.appendBits(((typeInfo shl 10) or bchCode(typeInfo, TYPE_INFO_POLY)) xor TYPE_INFO_MASK_PATTERN, 15)
        return bits
    }

    private fun versionInfoBits(version: Int): Bits {
        val bits = Bits()
        bits.appendBits(version, 6)
        bits.appendBits(bchCode(version, VERSION_INFO_POLY), 12)
        return bits
    }

    private fun maskBit(mask: Int, x: Int, y: Int): Boolean {
        val intermediate = when (mask) {
            0 -> (y + x) and 1
            1 -> y and 1
            2 -> x % 3
            3 -> (y + x) % 3
            4 -> ((y / 2) + (x / 3)) and 1
            5 -> (y * x).let { (it and 1) + (it % 3) }
            6 -> (y * x).let { ((it and 1) + (it % 3)) and 1 }
            7 -> (y * x).let { ((it % 3) + ((y + x) and 1)) and 1 }
            else -> throw IllegalArgumentException("Invalid mask pattern: $mask")
        }
        return intermediate == 0
    }

    private fun buildMatrix(dataBits: Bits, version: Int, mask: Int, m: Matrix) {
        m.clear()
        // The finder patterns in three corners, and their separators.
        val n = m.size
        for ((x0, y0) in listOf(0 to 0, n - 7 to 0, 0 to n - 7)) {
            for (y in 0 until 7) for (x in 0 until 7) m[x0 + x, y0 + y] = FINDER[y][x]
        }
        for (x in 0 until 8) {
            m[x, 7] = 0
            m[n - 8 + x, 7] = 0
            m[x, n - 8] = 0
        }
        for (y in 0 until 7) {
            m[7, y] = 0
            m[n - 8, y] = 0
            m[7, n - 7 + y] = 0
        }
        // The dark module beside the bottom-left finder.
        m[8, n - 8] = 1
        // Alignment patterns, where nothing is yet.
        if (version >= 2) {
            val centres = ALIGNMENT[version - 1]
            for (y in centres) for (x in centres) {
                if (m[x, y] == -1) {
                    for (dy in 0 until 5) for (dx in 0 until 5) m[x - 2 + dx, y - 2 + dy] = ALIGNMENT_PATTERN[dy][dx]
                }
            }
        }
        // Timing patterns.
        for (i in 8 until n - 8) {
            val bit = (i + 1) % 2
            if (m[i, 6] == -1) m[i, 6] = bit
            if (m[6, i] == -1) m[6, i] = bit
        }
        // Format information: error correction level and mask.
        val type = typeInfoBits(mask)
        for (i in 0 until type.size) {
            val bit = if (type[type.size - 1 - i]) 1 else 0
            val (x1, y1) = TYPE_INFO_COORDINATES[i].let { it[0] to it[1] }
            m[x1, y1] = bit
            if (i < 8) m[n - i - 1, 8] = bit else m[8, n - 7 + (i - 8)] = bit
        }
        // Version information, from version 7.
        if (version >= 7) {
            val v = versionInfoBits(version)
            var bitIndex = 6 * 3 - 1
            for (i in 0 until 6) for (j in 0 until 3) {
                val bit = if (v[bitIndex--]) 1 else 0
                m[i, n - 11 + j] = bit
                m[n - 11 + j, i] = bit
            }
        }
        // The data, two columns at a time from the bottom right, up and down in turn, masked.
        var bitIndex = 0
        var direction = -1
        var x = n - 1
        var y = n - 1
        while (x > 0) {
            if (x == 6) x -= 1
            while (y in 0 until n) {
                for (i in 0 until 2) {
                    val xx = x - i
                    if (m[xx, y] != -1) continue
                    var bit = if (bitIndex < dataBits.size) dataBits[bitIndex++] else false
                    if (maskBit(mask, xx, y)) bit = !bit
                    m[xx, y] = if (bit) 1 else 0
                }
                y += direction
            }
            direction = -direction
            y += direction
            x -= 2
        }
        check(bitIndex == dataBits.size) { "Not all bits consumed: $bitIndex/${dataBits.size}" }
    }

    // ---- choosing the mask (ZXing's MaskUtil) ----------------------------------------------------------------------

    private fun penaltyRule1(m: Matrix, horizontal: Boolean): Int {
        var penalty = 0
        for (i in 0 until m.size) {
            var same = 0
            var prev = -1
            for (j in 0 until m.size) {
                val bit = if (horizontal) m[j, i] else m[i, j]
                if (bit == prev) {
                    same++
                } else {
                    if (same >= 5) penalty += 3 + (same - 5)
                    same = 1
                    prev = bit
                }
            }
            if (same >= 5) penalty += 3 + (same - 5)
        }
        return penalty
    }

    private fun penaltyRule2(m: Matrix): Int {
        var penalty = 0
        for (y in 0 until m.size - 1) for (x in 0 until m.size - 1) {
            val v = m[x, y]
            if (v == m[x + 1, y] && v == m[x, y + 1] && v == m[x + 1, y + 1]) penalty++
        }
        return 3 * penalty
    }

    private fun whiteHorizontal(m: Matrix, y: Int, from: Int, to: Int): Boolean {
        if (from < 0 || m.size < to) return false
        for (x in from until to) if (m[x, y] == 1) return false
        return true
    }

    private fun whiteVertical(m: Matrix, x: Int, from: Int, to: Int): Boolean {
        if (from < 0 || m.size < to) return false
        for (y in from until to) if (m[x, y] == 1) return false
        return true
    }

    private fun penaltyRule3(m: Matrix): Int {
        var count = 0
        val n = m.size
        for (y in 0 until n) for (x in 0 until n) {
            if (x + 6 < n && m[x, y] == 1 && m[x + 1, y] == 0 && m[x + 2, y] == 1 && m[x + 3, y] == 1 && m[x + 4, y] == 1 &&
                m[x + 5, y] == 0 && m[x + 6, y] == 1 && (whiteHorizontal(m, y, x - 4, x) || whiteHorizontal(m, y, x + 7, x + 11))
            ) count++
            if (y + 6 < n && m[x, y] == 1 && m[x, y + 1] == 0 && m[x, y + 2] == 1 && m[x, y + 3] == 1 && m[x, y + 4] == 1 &&
                m[x, y + 5] == 0 && m[x, y + 6] == 1 && (whiteVertical(m, x, y - 4, y) || whiteVertical(m, x, y + 7, y + 11))
            ) count++
        }
        return count * 40
    }

    private fun penaltyRule4(m: Matrix): Int {
        var dark = 0
        for (c in m.cells) if (c.toInt() == 1) dark++
        val total = m.size * m.size
        return kotlin.math.abs(dark * 2 - total) * 10 / total * 10
    }

    private fun penalty(m: Matrix) = penaltyRule1(m, true) + penaltyRule1(m, false) + penaltyRule2(m) + penaltyRule3(m) + penaltyRule4(m)

    // ---- the whole ---------------------------------------------------------------------------------------------------

    /** The QR code for [content] at error correction M, as ZXing's Encoder.encode makes it with no character set given. */
    fun encode(content: String): Code {
        val mode = chooseMode(content)
        val header = Bits()
        header.appendBits(mode.bits, 4)
        val data = Bits()
        appendBytes(content, mode, data)
        val version = recommendVersion(mode, header, data)
        val all = Bits()
        all.appendBits(header)
        val letters = if (mode == Mode.BYTE) data.sizeInBytes else content.length
        val countBits = mode.characterCountBits(version)
        require(letters < (1 shl countBits)) { "$letters is bigger than ${(1 shl countBits) - 1}" }
        all.appendBits(letters, countBits)
        all.appendBits(data)
        val total = totalCodewords(version)
        val numDataBytes = total - ecPerBlock(version) * numBlocks(version)
        terminateBits(numDataBytes, all)
        val final = interleave(all, total, numDataBytes, numBlocks(version))
        val m = Matrix(dimension(version))
        var best = -1
        var lowest = Int.MAX_VALUE
        for (mask in 0 until 8) {
            buildMatrix(final, version, mask, m)
            val p = penalty(m)
            if (p < lowest) {
                lowest = p
                best = mask
            }
        }
        buildMatrix(final, version, best, m)
        return Code(version, best, mode.name, m.size, m.cells.copyOf())
    }

    /**
     * [code] drawn [width] by [height] pixels with a quiet zone of [quietZone] modules, as QRCodeWriter.renderResult
     * draws it: each module a square of a whole number of pixels, centred; never smaller than the code and its quiet
     * zone. Row by row, true for a dark pixel.
     */
    fun render(code: Code, width: Int, height: Int, quietZone: Int): Triple<Int, Int, BooleanArray> {
        val qrWidth = code.size + quietZone * 2
        val outW = maxOf(width, qrWidth)
        val outH = maxOf(height, qrWidth)
        val multiple = minOf(outW / qrWidth, outH / qrWidth)
        val left = (outW - code.size * multiple) / 2
        val top = (outH - code.size * multiple) / 2
        val out = BooleanArray(outW * outH)
        for (iy in 0 until code.size) {
            val oy = top + iy * multiple
            for (ix in 0 until code.size) {
                if (!code[ix, iy]) continue
                val ox = left + ix * multiple
                for (y in oy until oy + multiple) for (x in ox until ox + multiple) out[y * outW + x] = true
            }
        }
        return Triple(outW, outH, out)
    }
}

/**
 * The QR code for [text], [sizePx] pixels square, as label sheets and screens draw it: error correction M, a quiet zone
 * of one module, the modules scaled by a whole number and centred ([QrEncoder], the same as Android's ZXing 3.5.3,
 * golden/README.md, qr.json).
 */
fun qrPixels(text: String, sizePx: Int): QrPixels {
    require(text.isNotEmpty()) { "Found empty contents" }
    require(sizePx >= 0) { "Requested dimensions are too small: ${sizePx}x$sizePx" }
    val (w, _, dark) = QrEncoder.render(QrEncoder.encode(text), sizePx, sizePx, 1)
    return QrPixels(w, dark)
}
