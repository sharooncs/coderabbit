package app.housemove.ui.iphone

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.housemove.ui.CameraDialog
import app.housemove.ui.HouseMoveTheme
import app.housemove.ui.LocalPlatform
import app.housemove.ui.ScanDialog
import app.housemove.ui.ScanKind
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * The screens whose look changes on the iPhone once it has a camera (step 3a of design/ios-plan.md), rendered as
 * ScreensRenderTest renders the others (an iPhone 17 Pro, normal and twice the text size), with the desktop tests'
 * camera (TestCamera: a still of a sofa for the live picture): Home with Scan a label and the walk-through, Items, a
 * thing and a box with their camera and scan buttons, the walk-through, Scan a label, Get fresh data with its scanner,
 * Load, the camera and the scanner themselves, and the camera when it is not allowed. To build/screens/camera-*.png.
 *
 * The camera and the scanners are full-screen dialogs. On the iPhone a dialog keeps clear of the Dynamic Island and the
 * home indicator by itself (Compose Multiplatform's platform insets); a plain JVM has none, so here their top row sits
 * under the island drawn on top.
 */
class CameraRenderTest {
    private val out = File(System.getProperty("screens.out") ?: "build/screens").apply { mkdirs() }
    private val root = newRoot()
    private val android = AndroidPhone(root)
    private val camera = TestCamera()
    private val hooks = DesktopHooks(File(root, "iphone")).also { it.camera = camera }
    private val model = AppModel(hooks, "Test build 0.1 (1)", mainScope())

    @AfterTest
    fun cleanUp() {
        model.stopCode()
        android.close()
        root.deleteRecursively()
    }

    private fun joined() {
        model.use(android.invite())
        model.yes()
        waitFor("the join") { model.store != null }
        model.welcomeRead()
    }

    private fun both(name: String, route: String? = null, over: (@Composable () -> Unit)? = null) {
        render("camera-$name-normal.png", 1f, route, over)
        render("camera-$name-large-text.png", 2f, route, over)
    }

    @Test
    fun theScreensWithTheCamera() {
        joined()
        val d = model.store!!.data.value
        both("home", "home")
        both("items", "items")
        both("item-sofa", "item/" + d.liveItems.first { it.name == "Sofa" }.id)
        both("item-kettle", "item/" + d.liveItems.first { it.name == "Kettle" }.id)
        both("box", "box/" + d.liveBoxes.first { it.label == "A1" }.id)
        both("walk", "walk")
        both("scan", "scan")
        both("fresh", "sync")
        both("load", "load")
        both("more", "more")
    }

    @Test
    fun theCameraAndTheScanners() {
        joined()
        val sofa = "item/" + model.store!!.data.value.liveItems.first { it.name == "Sofa" }.id
        val newFile = { File(root, "shot.jpg") }
        both("photo-camera", sofa) { CameraDialog(hint = "Sofa", newFile = newFile, onPhoto = {}, onClose = {}) }
        both("walk-camera", "walk") { CameraDialog(hint = "Kitchen: one photo per big thing", newFile = newFile, onPhoto = {}, onClose = {}) }
        both("scan-sync-code", "sync") {
            ScanDialog("Scan the sync code on the Android phone", onCode = {}, onClose = {}, raw = true, typeLabel = "Or paste the code", typingFolded = true)
        }
        both("scan-serial", sofa) { ScanDialog("Scan the serial number", onCode = {}, onClose = {}, formats = ScanKind.SERIALS) }
        camera.allowed = false
        both("scan-not-allowed", "box/" + model.store!!.data.value.liveBoxes.first { it.label == "A1" }.id) {
            ScanDialog("Scan the label on the item", onCode = {}, onClose = {})
        }
        camera.allowed = true
    }

    private fun render(name: String, fontScale: Float, route: String?, over: (@Composable () -> Unit)?) {
        val scene = ImageComposeScene(width = 1206, height = 2622, density = Density(3f, fontScale)) {
            Box(Modifier.fillMaxSize()) {
                HouseMoveApp(model, safeArea = WindowInsets(top = 62.dp, bottom = 34.dp), startRoute = route)
                if (over != null) {
                    val platform = remember { IphonePlatform(model.hooks, model.toasts) }
                    HouseMoveTheme { CompositionLocalProvider(LocalPlatform provides platform) { over() } }
                }
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
