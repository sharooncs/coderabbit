package app.housemove

import app.housemove.data.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Merges of whole datasets, with no phone, file or network: on the JVM and on the iPhone simulator. (From DataTest and EdgeCaseTest.) */
class MergeCommonTest {
    @Test
    fun newerRecordWinsAndMergeIsSymmetric() {
        val old = Box(id = "b1", label = "L001", contents = "old", updatedAt = 10)
        val new = old.copy(contents = "new", updatedAt = 20)
        val a = AppData(boxes = listOf(old), items = listOf(Item(id = "i1", name = "Sofa", updatedAt = 5)))
        val b = AppData(boxes = listOf(new), items = listOf(Item(id = "i2", name = "Bed", updatedAt = 7)))
        val ab = merge(a, b)
        val ba = merge(b, a)
        assertEquals("new", ab.box("b1")!!.contents)
        assertEquals(2, ab.liveItems.size)
        assertEquals(ab.boxes.toSet(), ba.boxes.toSet())
        assertEquals(ab.items.toSet(), ba.items.toSet())
    }

    @Test
    fun deletionTravelsAsTombstoneAndTiesAreDeterministic() {
        val live = Box(id = "b1", label = "L001", updatedAt = 10)
        val dead = live.copy(deleted = true, updatedAt = 11)
        assertTrue(merge(AppData(boxes = listOf(live)), AppData(boxes = listOf(dead))).liveBoxes.isEmpty())
        val x = Box(id = "b2", label = "L002", contents = "x", updatedAt = 10)
        val y = x.copy(contents = "y")
        assertEquals(
            merge(AppData(boxes = listOf(x)), AppData(boxes = listOf(y))).boxes,
            merge(AppData(boxes = listOf(y)), AppData(boxes = listOf(x))).boxes,
        )
    }

    @Test
    fun anyNumberOfPhonesConvergeWhateverTheSyncOrder() {
        val rnd = JavaRandom(42)
        fun randomData(seed: Int): AppData {
            val boxes = (0 until 40).filter { rnd.nextInt(3) > 0 }.map { n ->
                Box(id = "b$n", label = "L$n", contents = "phone$seed-${rnd.nextInt(5)}", updatedAt = rnd.nextInt(6).toLong(), deleted = rnd.nextInt(8) == 0)
            }
            val items = (0 until 25).filter { rnd.nextInt(3) > 0 }.map { n ->
                Item(id = "i$n", name = "phone$seed-${rnd.nextInt(5)}", updatedAt = rnd.nextInt(6).toLong(), deleted = rnd.nextInt(8) == 0)
            }
            return AppData(move = MoveInfo(name = "m$seed", updatedAt = rnd.nextInt(4).toLong()), boxes = boxes, items = items)
        }
        repeat(30) {
            val a = randomData(1)
            val b = randomData(2)
            val c = randomData(3)
            fun key(d: AppData) = Triple(d.move, d.boxes.toSet(), d.items.toSet())
            val expected = key(merge(merge(a, b), c))
            assertEquals(expected, key(merge(a, merge(b, c))))
            assertEquals(expected, key(merge(merge(c, a), b)))
            assertEquals(expected, key(merge(merge(b, c), merge(a, b))))
            assertEquals(key(a), key(merge(a, a)))
        }
    }
}

/** java.util.Random's numbers in common Kotlin, so a test moved here keeps the data it always had (JavaTextTest checks it). */
internal class JavaRandom(seed: Long) {
    private var s = (seed xor 0x5DEECE66DL) and MASK

    private fun next(bits: Int): Int {
        s = (s * 0x5DEECE66DL + 0xBL) and MASK
        return (s ushr (48 - bits)).toInt()
    }

    fun nextInt(bound: Int): Int {
        var r = next(31)
        val m = bound - 1
        if (bound and m == 0) return ((bound.toLong() * r.toLong()) shr 31).toInt()
        var u = r
        while (true) {
            r = u % bound
            if (u - r + m >= 0) return r
            u = next(31)
        }
    }

    private companion object {
        const val MASK = (1L shl 48) - 1
    }
}
