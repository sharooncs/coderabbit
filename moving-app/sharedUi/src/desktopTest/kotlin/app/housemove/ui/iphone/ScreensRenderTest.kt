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
import app.housemove.sync.AutoSync
import app.housemove.sync.LocalLan
import app.housemove.sync.WifiSync
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * Renders the iPhone's screens (its join screens and Get fresh data, and the shared app once joined) as an iPhone 17 Pro shows it (1206 x 2622 pixels at 3x: 402 x 874
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

    private fun both(name: String, route: String? = null) {
        render("$name-normal.png", 1f, route = route)
        render("$name-large-text.png", 2f, route = route)
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

    /**
     * The shared app as the iPhone shows it once joined (step 2 of design/ios-plan.md): what Alex, a Decider, sees, with
     * what waits for step 3 (the camera, scanning, the PDFs) left out.
     */
    @Test
    fun theSharedScreens() {
        joined()
        // The first picture of Home has the welcome on it: who this iPhone is now.
        both("home-welcome", "home")
        val d = model.store!!.data.value
        both("home", "home")
        both("items", "items")
        both("rooms", "rooms")
        both("item", "item/" + d.liveItems.first { it.name == "Kettle" }.id)
        both("item-sofa", "item/" + d.liveItems.first { it.name == "Sofa" }.id)
        both("boxes", "boxes")
        both("box", "box/" + d.liveBoxes.first { it.label == "A1" }.id)
        both("search", "search")
        both("people", "people")
        both("more", "more")
        both("moves", "moves")
        both("moveday", "moveday")
    }

    /**
     * An iPhone that joined as the move's Organiser (step 5): Who's helping says how each kind of phone gets HouseMove,
     * with no "Share the app" and no download code (Android's app), and the dock at the largest text.
     */
    @Test
    fun theOrganisersScreens() {
        model.use(android.invite(android.store.me!!.id))
        model.yes()
        waitFor("the join") { model.store != null }
        model.welcomeRead()
        both("helping", "helping")
        both("organiser-home", "home")
    }

    @Test
    fun getFreshData() {
        joined()
        model.welcomeRead()
        both("fresh", "sync")
        model.showForPicture(Fresh.Showing(android.syncCode().replace("127.0.0.1", "192.168.1.23")))
        both("fresh-code", "sync")
        model.showForPicture(Fresh.Idle(Words.syncResult(WifiSync.Result("Sam", "A", 0, 0))))
        both("fresh-done", "sync")
        model.askToLeave(true)
        both("leave", "sync")
        model.askToLeave(false)
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
            waitFor("up to date by itself", 30_000) { auto.autoSync?.status?.value?.state == AutoSync.State.UP_TO_DATE }
            render("fresh-by-itself-normal.png", 1f, auto, "sync")
            render("fresh-by-itself-large-text.png", 2f, auto, "sync")
        } finally {
            auto.autoSync?.close()
            phone.close()
        }
    }

    private fun render(name: String, fontScale: Float, which: AppModel = model, route: String? = null) {
        val scene = ImageComposeScene(width = 1206, height = 2622, density = Density(3f, fontScale)) {
            Box(Modifier.fillMaxSize()) {
                HouseMoveApp(which, safeArea = WindowInsets(top = 62.dp, bottom = 34.dp), startRoute = route)
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
