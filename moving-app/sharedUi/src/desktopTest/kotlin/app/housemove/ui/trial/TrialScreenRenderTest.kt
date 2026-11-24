package app.housemove.ui.trial

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Renders the trial screen as an iPhone 17 Pro shows it (1206 x 2622 pixels at 3x: 402 x 874 points, with the
 * Dynamic Island and the home indicator drawn on top where the phone has them) and saves PNGs to look at:
 * build/trial/trial-*.png. The safe area given to the screen is the phone's: 62 points at the top, 34 at the bottom.
 */
class TrialScreenRenderTest {
    private val out = File(System.getProperty("trial.out") ?: "build/trial").apply { mkdirs() }

    @Test
    fun atTheNormalTextSize() = render("trial-normal.png", fontScale = 1f, firstRow = 0)

    /** Twice the normal text size. iOS's largest setting (Accessibility XXXL) is 1.8 in Compose Multiplatform. */
    @Test
    fun atTwiceTheTextSize() = render("trial-large-text.png", fontScale = 2f, firstRow = 0)

    /** Scrolled to the middle of the list (the dining room). */
    @Test
    fun scrolledToTheMiddle() = render("trial-scrolled.png", fontScale = 1f, firstRow = 2 + TrialRows.size / 2)

    @Test
    fun scrolledToTheMiddleAtTwiceTheTextSize() =
        render("trial-scrolled-large-text.png", fontScale = 2f, firstRow = 2 + TrialRows.size / 2)

    /** The icon grid at the end of the list. */
    @Test
    fun theIcons() = render("trial-icons.png", fontScale = 1f, firstRow = 2 + TrialRows.size)

    @Test
    fun everyIconOnceWithAName() {
        assertEquals(TrialIcons.size, TrialIcons.map { it.second }.toSet().size, "an icon is listed twice")
        assertEquals(TrialIcons.size, TrialIcons.map { it.first }.toSet().size, "two icons have the same name")
        assertTrue(TrialIcons.all { it.first.isNotBlank() })
        assertTrue(TrialRows.size in 190..230, "about 200 rows, not ${TrialRows.size}")
    }

    private fun render(name: String, fontScale: Float, firstRow: Int) {
        val scene = ImageComposeScene(width = 1206, height = 2622, density = Density(3f, fontScale)) {
            Box(Modifier.fillMaxSize()) {
                TrialScreen(
                    "Test build 0.1 (1)",
                    safeArea = WindowInsets(top = 62.dp, bottom = 34.dp),
                    listState = rememberLazyListState(initialFirstVisibleItemIndex = firstRow),
                )
                // Where the phone covers the screen: the Dynamic Island and the home indicator.
                Box(
                    Modifier.align(Alignment.TopCenter).offset(y = 11.dp).size(125.dp, 37.dp)
                        .background(Color.Black, RoundedCornerShape(50)),
                )
                Box(
                    Modifier.align(Alignment.BottomCenter).offset(y = (-8).dp).size(134.dp, 5.dp)
                        .background(Color.White.copy(alpha = 0.7f), RoundedCornerShape(50)),
                )
            }
        }
        try {
            // The fonts load from the resources after the first frame: render until two frames agree.
            var png = ByteArray(0)
            for (frame in 0 until 30) {
                val next = scene.render(frame * 16_666_667L).encodeToData(EncodedImageFormat.PNG)!!.bytes
                if (frame > 0 && next.contentEquals(png)) break
                png = next
                Thread.sleep(40)
            }
            File(out, name).writeBytes(png)
        } finally {
            scene.close()
        }
    }
}
