package app.housemove.ui.iphone

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.housemove.ui.HouseMoveTheme
import app.housemove.ui.LocalPlatform
import app.housemove.ui.ScanDialog
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the shared screens say for a few seconds (ToastHost), on the iPhone (step 5 of design/ios-plan.md): above a
 * full-screen dialog, such as a scanner that has just read a wrong code, and never in the way of a touch. Rendered as
 * ScreensRenderTest renders the screens (an iPhone 17 Pro, normal and twice the text size) to
 * build/screens/toast-over-scanner-*.png.
 */
class ToastRenderTest {
    private val out = File(System.getProperty("screens.out") ?: "build/screens").apply { mkdirs() }
    private val root = newRoot()

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    /** A box's label scanner reads a sync code by mistake: its words show over the scanner, as on Android. */
    @Test
    fun aWrongCodeInAScannerIsSaidAboveIt() {
        val android = AndroidPhone(root)
        val camera = TestCamera(codes = listOf("HMSYNC:192.168.1.23:41234:E2ABCDEFGH"))
        val model = AppModel(DesktopHooks(File(root, "iphone")).also { it.camera = camera }, "Test build 0.1 (1)", mainScope())
        try {
            model.use(android.invite())
            model.yes()
            waitFor("the join") { model.store != null }
            model.welcomeRead()
            // Rendering until two frames agree may take longer than the toast's 3.5 seconds here.
            model.toasts.showMs = 120_000
            val box = "box/" + model.store!!.data.value.liveBoxes.first { it.label == "A1" }.id
            for ((name, scale) in listOf("normal" to 1f, "large-text" to 2f)) {
                val said = "That is a code for linking phones, not a box label."
                val with = render(model, box, scale) { platform ->
                    ScanDialog("Scan the label on the box", onCode = { platform.toast(said) }, onClose = {})
                }
                assertEquals(said, model.toasts.text, "the scanner's words were said")
                File(out, "toast-over-scanner-$name.png").writeBytes(with)
                // The same, once the words have gone: where the pill was, the picture differs, so it was drawn above the
                // dialog (under it, the dialog's opaque ground would hide it).
                model.toasts.done(model.toasts.count)
                camera.codes = emptyList()
                val without = render(model, box, scale) { ScanDialog("Scan the label on the box", onCode = {}, onClose = {}) }
                camera.codes = listOf("HMSYNC:192.168.1.23:41234:E2ABCDEFGH")
                val y = 2622 - ((34 + 96 + 20) * 3)
                assertTrue(differs(with, without, 603, y), "the toast is drawn above the scanner ($name)")
            }
        } finally {
            model.stopCode()
            android.close()
        }
    }

    /** Taps land on whatever is under the toast, on a dialog and on the screen alike, the pill itself included. */
    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun aToastNeverTakesATouch() {
        val toasts = Toasts().apply { showMs = 120_000 }
        val onScreen = AtomicInteger()
        val onDialog = AtomicInteger()
        var dialog by mutableStateOf(true)
        val scene = ImageComposeScene(width = 1206, height = 2622, density = Density(3f, 1f)) {
            HouseMoveTheme {
                Box(Modifier.fillMaxSize().background(Color(0xFF0B1826)).clickable { onScreen.incrementAndGet() })
                if (dialog) {
                    Dialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                        Box(Modifier.fillMaxSize().background(Color(0xFF15253A)).clickable { onDialog.incrementAndGet() })
                    }
                }
                ToastHost(toasts)
            }
        }
        try {
            var frame = 0L
            fun settle(): ByteArray {
                var png = ByteArray(0)
                repeat(20) {
                    png = scene.render(frame++ * 16_666_667L).encodeToData(EncodedImageFormat.PNG)!!.bytes
                    Thread.sleep(10)
                }
                return png
            }
            fun tap(x: Float, y: Float) {
                scene.sendPointerEvent(PointerEventType.Press, Offset(x, y), type = PointerType.Touch)
                scene.sendPointerEvent(PointerEventType.Release, Offset(x, y), type = PointerType.Touch)
                settle()
            }
            val plain = settle()
            // The pill's middle: 96 points above the bottom, half a pill (about 24 points) higher.
            val pillX = 603f
            val pillY = 2622f - (96 + 24) * 3
            tap(pillX, pillY)
            assertEquals(1, onDialog.get(), "with no toast, the dialog takes the tap")
            toasts.say("That is not a sync code")
            val said = settle()
            assertTrue(differs(plain, said, pillX.toInt(), pillY.toInt()), "the toast is drawn above the dialog")
            tap(pillX, pillY)
            assertEquals(2, onDialog.get(), "the tap on the toast went to the dialog under it")
            tap(pillX, 600f)
            assertEquals(3, onDialog.get(), "and a tap elsewhere too")
            assertEquals(0, onScreen.get(), "nothing reached the screen under the dialog")
            assertEquals("That is not a sync code", toasts.text, "the toast is still there")

            // With no dialog, the screen under the toast takes the tap.
            dialog = false
            settle()
            toasts.say("Withdrawn")
            settle()
            tap(pillX, pillY)
            assertEquals(1, onScreen.get(), "the tap on the toast went to the screen under it")
        } finally {
            scene.close()
        }
    }

    /** Is any pixel in the 60 x 30 pixel box around ([x], [y]) different between the two PNGs? */
    private fun differs(a: ByteArray, b: ByteArray, x: Int, y: Int): Boolean {
        val ia = javax.imageio.ImageIO.read(a.inputStream())
        val ib = javax.imageio.ImageIO.read(b.inputStream())
        for (dx in -30..30) for (dy in -15..15) if (ia.getRGB(x + dx, y + dy) != ib.getRGB(x + dx, y + dy)) return true
        return false
    }

    /** The app on [route] with [over] (a dialog) on top, as the iPhone shows it; the PNG once two frames agree. */
    private fun render(model: AppModel, route: String, fontScale: Float, over: @Composable (IphonePlatform) -> Unit): ByteArray {
        val scene = ImageComposeScene(width = 1206, height = 2622, density = Density(3f, fontScale)) {
            Box(Modifier.fillMaxSize()) {
                HouseMoveApp(model, safeArea = WindowInsets(top = 62.dp, bottom = 34.dp), startRoute = route)
                val platform = remember { IphonePlatform(model.hooks, model.toasts) }
                HouseMoveTheme { CompositionLocalProvider(LocalPlatform provides platform) { over(platform) } }
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
            var png = ByteArray(0)
            for (frame in 0 until 40) {
                val next = scene.render(frame * 16_666_667L).encodeToData(EncodedImageFormat.PNG)!!.bytes
                if (frame > 2 && next.contentEquals(png)) break
                png = next
                Thread.sleep(40)
            }
            return png
        } finally {
            scene.close()
        }
    }
}
