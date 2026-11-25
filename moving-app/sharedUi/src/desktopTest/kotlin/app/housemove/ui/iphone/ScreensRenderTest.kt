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
import app.housemove.data.Home
import app.housemove.sync.AutoSync
import app.housemove.sync.LocalLan
import app.housemove.sync.WifiSync
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * Renders every screen of the first iPhone build as an iPhone 17 Pro shows it (1206 x 2622 pixels at 3x: 402 x 874
 * points, with the Dynamic Island and the home indicator drawn on top where the phone has them) and saves PNGs to look
 * at: build/screens/<screen>-normal.png, and <screen>-large-text.png at twice the text size (iOS's largest setting,
 * Accessibility XXXL, is about 1.8 in Compose Multiplatform). The safe area given to the screens is the phone's: 62
 * points at the top, 34 at the bottom. The screens show the real thing: a sample move joined from a hosting "Android
 * phone" over 127.0.0.1, through the same model the iPhone uses.
 */
class ScreensRenderTest {
    private val out = File(System.getProperty("screens.out") ?: "build/screens").apply { mkdirs() }
    private val root = newRoot()
    private val android = AndroidPhone(root)
    private val model = AppModel(DesktopHooks(File(root, "iphone")), "Test build 0.1 (1)", mainScope())

    @AfterTest
    fun cleanUp() {
        model.stopCode()
        android.close()
        root.deleteRecursively()
    }

    private fun both(name: String) {
        render("$name-normal.png", 1f)
        render("$name-large-text.png", 2f)
    }

    private fun joined() {
        model.use(android.invite())
        model.yes()
        waitFor("the join") { model.store != null }
    }

    @Test
    fun theJoinScreens() {
        both("join")
        model.use(android.invite())
        both("ask")
        model.showForPicture(JoinStep.Working("Sample move", JoinStep.Stage.WAITING_FOR_RIGHT, "Alex"))
        both("joining")
        model.showForPicture(JoinStep.Failed(Words.DENIED, null))
        both("problem-local-network")
        model.showForPicture(JoinStep.Failed(Words.joinProblem(WifiSync.INVITE_EXPIRED), null))
        both("problem-expired")
    }

    @Test
    fun theJoinScreenInDemoMode() {
        val demo = AppModel(DesktopHooks(File(root, "demo")), "Test build 0.1 (1)", mainScope(), AppModel.Demo(android.invite(), android.syncCode()))
        render("join-demo-normal.png", 1f, demo)
    }

    @Test
    fun theMoveScreens() {
        joined()
        both("rooms-welcome")
        model.welcomeRead()
        both("rooms")
        val d = model.store!!.data.value
        model.open(Page.Room(d.roomsOf(Home.ORIGIN).first { it.name == "Kitchen" }.id))
        both("room")
        model.back()
        model.open(Page.Room(d.roomsOf(Home.ORIGIN).first { it.name == "Bedroom" }.id))
        both("room-bedroom")
        model.back()
        model.open(Page.Room(d.roomsOf(Home.DEST).first { it.name == "Lounge" }.id))
        both("room-new-home")
        model.back()
        model.open(Page.Search)
        model.query = "kitchen"
        both("search")
        model.query = "piano"
        both("search-nothing")
        model.back()
        model.askToLeave(true)
        both("leave")
        model.askToLeave(false)
    }

    @Test
    fun getFreshData() {
        joined()
        model.open(Page.Fresh)
        both("fresh")
        model.showForPicture(Fresh.Showing(android.syncCode().replace("127.0.0.1", "192.168.1.23")))
        both("fresh-code")
        model.showForPicture(Fresh.Idle(Words.syncResult(WifiSync.Result("Sam", "A", 0, 0))))
        both("fresh-done")
    }

    /** "Get fresh data" once the iPhone has synced by itself with an Android phone on 2.3 (design/auto-sync.md). */
    @Test
    fun getFreshDataByItself() {
        val lan = LocalLan()
        val phone = AndroidPhone(File(root, "auto").apply { mkdirs() }, lan)
        val auto = AppModel(DesktopHooks(File(root, "iphone-auto"), lan = lan), "Test build 0.1 (1)", mainScope())
        try {
            auto.use(phone.invite())
            auto.yes()
            waitFor("the join") { auto.store != null }
            auto.welcomeRead()
            auto.open(Page.Fresh)
            waitFor("up to date by itself", 30_000) { auto.autoSync?.status?.value?.state == AutoSync.State.UP_TO_DATE }
            render("fresh-by-itself-normal.png", 1f, auto)
            render("fresh-by-itself-large-text.png", 2f, auto)
        } finally {
            auto.autoSync?.close()
            phone.close()
        }
    }

    private fun render(name: String, fontScale: Float, which: AppModel = model) {
        val scene = ImageComposeScene(width = 1206, height = 2622, density = Density(3f, fontScale)) {
            Box(Modifier.fillMaxSize()) {
                HouseMoveApp(which, safeArea = WindowInsets(top = 62.dp, bottom = 34.dp))
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
