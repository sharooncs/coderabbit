package app.housemove.ui.iphone

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.housemove.ui.unavailableRoutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Files on the iPhone (step 3b of design/ios-plan.md), as the desktop tests can show them: the screens that now offer
 * printing and sharing (Labels and signs, Reports, Ask for decisions, and Get fresh data with Working apart and the
 * backup), drawn as an iPhone 17 Pro shows them (as ScreensRenderTest does, to build/screens).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FilesRenderTest {
    private val out = File(System.getProperty("screens.out") ?: "build/screens").apply { mkdirs() }
    private val root = newRoot()
    private val android = AndroidPhone(root)
    private val hooks = DesktopHooks(File(root, "iphone"))
    private val model = AppModel(hooks, "Test build 0.1 (1)", mainScope())

    /**
     * Some of the shared screens' work goes back to the phones' main thread (Dispatchers.Main), which a plain JVM has
     * not got: here it goes on where it is, and every thread counts as the main one (as the screens are drawn on this one).
     */
    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @AfterTest
    fun cleanUp() {
        model.stopCode()
        android.close()
        root.deleteRecursively()
        Dispatchers.resetMain()
    }

    private fun joined(m: AppModel = model, invite: String = android.invite()) {
        m.use(invite)
        m.yes()
        waitFor("the join") { m.store != null }
        m.welcomeRead()
    }

    @Test
    fun theScreensThatPrintAndShare() {
        joined()
        // The PDFs and the share sheet are on this iPhone now: nothing about them is left out.
        val platform = IphonePlatform(hooks, Toasts())
        assertTrue(platform.shareFiles && platform.papers != null)
        for (name in listOf("normal" to 1f, "large-text" to 2f)) {
            render("labels-${name.first}.png", name.second, route = "labels")
            render("reports-${name.first}.png", name.second, route = "reports")
            render("askdecisions-${name.first}.png", name.second, route = "askdecisions")
        }
        // Get fresh data, all of it (a tall picture): Working apart and the backup below the syncing.
        render("fresh-files-whole.png", 1f, route = "sync", height = 6200)
    }

    /** Everything the iPhone offers once files are there; only the camera's screens wait (step 3a), and other moves. */
    @Test
    fun onlyTheCamerasScreensAreLeftOut() {
        joined()
        var hidden: Set<String>? = null
        val scene = ImageComposeScene(width = 400, height = 400, density = Density(1f)) {
            androidx.compose.runtime.CompositionLocalProvider(app.housemove.ui.LocalPlatform provides IphonePlatform(hooks, Toasts())) {
                hidden = unavailableRoutes()
            }
        }
        try {
            scene.render()
        } finally {
            scene.close()
        }
        assertEquals(setOf("walk", "scan", "join", "lost"), hidden)
    }

    private fun render(name: String, fontScale: Float, which: AppModel = model, route: String? = null, height: Int = 2622, until: () -> Boolean = { true }) {
        val scene = ImageComposeScene(width = 1206, height = height, density = Density(3f, fontScale)) {
            Box(Modifier.fillMaxSize()) {
                HouseMoveApp(which, safeArea = WindowInsets(top = 62.dp, bottom = 34.dp), startRoute = route)
                // Where the phone covers the screen: the Dynamic Island and the home indicator.
                Box(Modifier.align(Alignment.TopCenter).offset(y = 11.dp).size(125.dp, 37.dp).background(Color.Black, RoundedCornerShape(50)))
                Box(
                    Modifier.align(Alignment.BottomCenter).offset(y = (-8).dp).size(134.dp, 5.dp)
                        .background(Color.White.copy(alpha = 0.7f), RoundedCornerShape(50)),
                )
            }
        }
        try {
            // Frames go on being drawn while what is waited for happens (effects run as frames are drawn), then until two
            // frames agree (the fonts load from the resources after the first).
            var t = 0L
            val until0 = System.currentTimeMillis() + 30_000
            while (!until()) {
                scene.render(t)
                t += 16_666_667L
                if (System.currentTimeMillis() > until0) throw AssertionError("timed out rendering $name")
                Thread.sleep(40)
            }
            var png = ByteArray(0)
            for (frame in 0 until 40) {
                val next = scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes
                t += 16_666_667L
                if (frame > 2 && next.contentEquals(png)) break
                png = next
                Thread.sleep(40)
            }
            File(out, name).writeBytes(png)
        } finally {
            scene.close()
        }
    }
}
