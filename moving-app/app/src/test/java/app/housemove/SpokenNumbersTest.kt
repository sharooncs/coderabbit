package app.housemove

import app.housemove.ui.screens.spokenNumbers
import org.junit.Assert.assertEquals
import org.junit.Test

/** A size said aloud never gives NaN or an infinity either (see FiniteNumbersTest in the shared module). */
class SpokenNumbersTest {
    /** 1 followed by 309 zeros: 310 digits, more than a Double holds, so Kotlin's toDouble gives Infinity. */
    private val digits310 = "1" + "0".repeat(309)

    @Test
    fun aSpokenNumberTooLongForADoubleIsLeftOutInItsPlace() {
        assertEquals(listOf(200.0, null, 85.0), spokenNumbers("200 by $digits310 by 85"))
        assertEquals(listOf(200.0, 90.5, 85.0), spokenNumbers("200 by 90,5 by 85"))
    }
}
