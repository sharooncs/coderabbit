package app.housemove.ui.iphone

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import app.housemove.data.ScanFormats
import app.housemove.ui.LocalPlatform
import app.housemove.ui.ScanKind
import app.housemove.ui.screens.addByPhoto
import app.housemove.ui.unavailableRoutes
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The iPhone's camera and scanner as far as a plain JVM can check them (step 3a of design/ios-plan.md): which codes each
 * scanner reads, against Android's own lists; Android's rule for the same code read twice; the buzz-and-beep and the
 * screen kept awake around a live camera, through IphonePlatform as the shared screens call it; the beep's sound; the
 * scanner's zoom; and the screen kept awake while joining and syncing. AVFoundation itself runs only on an iPhone.
 */
class CameraTest {
    private val root = newRoot()

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun theSameCodeIsReadAgainOnlyAfterTwoAndAHalfSeconds() {
        val gate = ScanGate()
        assertTrue(gate.pass("HM:A1", 10_000))
        assertFalse(gate.pass("HM:A1", 10_100), "held still over the label")
        assertFalse(gate.pass("HM:A1", 12_500), "2.5 s is not yet more than 2.5 s")
        assertTrue(gate.pass("HM:A1", 12_501))
        assertTrue(gate.pass("HM:B2", 12_600), "another label at once")
        assertTrue(gate.pass("HM:A1", 12_700), "and back to the first at once")
        // A first code is never held back, whenever it comes (Android's lastAt starts at 0).
        assertTrue(ScanGate().pass("HM:A1", 0))
    }

    /** Android's lists, read from the shared module's ScanFormats (zxing), and the iPhone's, kind by kind. */
    @Test
    fun eachScannerReadsWhatAndroidsReads() {
        val field = ScanFormats::class.java.getDeclaredField("formats").apply { isAccessible = true }
        for (kind in ScanKind.entries) {
            val android = (field.get(ScanFormats.valueOf(kind.name)) as List<*>).map { it.toString() }.toSet()
            val iphone = Barcodes.of(kind).map { if (it == Symbology.QR) "QR_CODE" else it.name }.toSet()
            // UPC-A is read inside EAN-13 on the iPhone (AVFoundation has no UPC-A of its own).
            assertEquals(android - "UPC_A", iphone, "$kind")
            assertEquals("UPC_A" in android, "EAN_13" in iphone, "$kind: UPC-A goes with EAN-13")
        }
    }

    @Test
    fun aCodeReadsAsAndroidReadsIt() {
        // UPC-A: AVFoundation's EAN-13 with a 0 in front, zxing's 12 digits.
        assertEquals("036000291452", Barcodes.text(ScanKind.PRODUCTS, Symbology.EAN_13, "0036000291452"))
        assertEquals("036000291452", Barcodes.text(ScanKind.SERIALS, Symbology.EAN_13, "0036000291452"))
        assertEquals("036000291452", Barcodes.text(ScanKind.ANY, Symbology.EAN_13, "0036000291452"))
        // A book's EAN-13 (an ISBN) and an EAN-8 as they are.
        assertEquals("9780141036144", Barcodes.text(ScanKind.PRODUCTS, Symbology.EAN_13, "9780141036144"))
        assertEquals("96385074", Barcodes.text(ScanKind.PRODUCTS, Symbology.EAN_8, "96385074"))
        // HouseMove's own codes as they are.
        assertEquals("HM:L042", Barcodes.text(ScanKind.LABELS, Symbology.QR, "HM:L042"))
        assertEquals("HMSYNC:a:b", Barcodes.text(ScanKind.ANY, Symbology.QR, "HMSYNC:a:b"))
        // What a kind does not read, and nothing read.
        assertNull(Barcodes.text(ScanKind.LABELS, Symbology.EAN_13, "9780141036144"), "a book is not a label")
        assertNull(Barcodes.text(ScanKind.PRODUCTS, Symbology.QR, "HM:L042"), "a label is not a product")
        assertNull(Barcodes.text(ScanKind.PRODUCTS, Symbology.CODE_128, "SN123"))
        assertEquals("SN-0042-X", Barcodes.text(ScanKind.SERIALS, Symbology.CODE_128, "SN-0042-X"))
        assertNull(Barcodes.text(ScanKind.ANY, Symbology.CODE_39, "SN123"), "Android's ANY has no Code 39")
        assertNull(Barcodes.text(ScanKind.LABELS, Symbology.QR, null))
        assertNull(Barcodes.text(ScanKind.LABELS, Symbology.QR, ""))
    }

    @Test
    fun theBeepIsATenthOfASecondAt1200Hertz() {
        val wav = ScanBeep.wav()
        val b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals(wav.size - 8, b.getInt(4))
        assertEquals("WAVEfmt ", String(wav, 8, 8))
        assertEquals(1, b.getShort(20).toInt(), "PCM")
        assertEquals(1, b.getShort(22).toInt(), "mono")
        assertEquals(44_100, b.getInt(24))
        assertEquals(16, b.getShort(34).toInt())
        assertEquals("data", String(wav, 36, 4))
        val samples = b.getInt(40) / 2
        assertEquals(4_410, samples, "100 ms")
        val pcm = (0 until samples).map { b.getShort(44 + 2 * it).toInt() }
        // Silent at both ends (no click), at most half loudness, and 120 cycles (1200 Hz for 0.1 s).
        assertEquals(0, pcm.first())
        assertTrue(abs(pcm.last()) < 200)
        assertTrue(pcm.maxOf { abs(it) } in 16_000..16_384)
        val crossings = pcm.zipWithNext().count { (a, c) -> a < 0 && c >= 0 }
        assertTrue(crossings in 118..121, "$crossings")
    }

    @Test
    fun theScannerZoomsInOnlyWhereTheLensCannotFocusClose() {
        // An iPhone Pro's main camera (closest focus about 20 cm): zoomed in, at most 2x.
        assertEquals(2.0, ScanZoom.factor(200.0, 70.0))
        // A camera that focuses at 4.5 cm: a 2 cm code fills the aim marks further away than that (about 4.6 cm), so no zoom.
        assertEquals(1.0, ScanZoom.factor(45.0, 70.0))
        // In between: the closest distance over how near a code may come (about 47 mm at 70 degrees).
        assertEquals(1.7, ScanZoom.factor(80.0, 70.0), 0.05)
        // Not known: no zoom.
        assertEquals(1.0, ScanZoom.factor(-1.0, 70.0))
        assertEquals(1.0, ScanZoom.factor(200.0, 0.0))
    }

    /** IphonePlatform offers the camera only where the phone has one; the walk-through and Scan a label come with it. */
    @Test
    fun theCameraIsOfferedOnlyWhereThereIsOne() {
        val hooks = DesktopHooks(File(root, "a"))
        val platform = IphonePlatform(hooks, Toasts())
        assertFalse(platform.camera, "no camera given: none, as in step 2")
        hooks.camera = TestCamera(hasCamera = false)
        assertFalse(platform.camera, "the simulator: no camera")
        hooks.camera = TestCamera()
        assertTrue(platform.camera)

        val routes = mutableListOf<Set<String>>()
        compose(platform) { routes += unavailableRoutes() }
        assertFalse("walk" in routes.last() || "scan" in routes.last(), "${routes.last()}")
        hooks.camera = TestCamera(hasCamera = false)
        compose(IphonePlatform(hooks, Toasts())) { routes += unavailableRoutes() }
        assertTrue("walk" in routes.last() && "scan" in routes.last(), "${routes.last()}")
    }

    /**
     * The scanner as the shared screens use it (IphonePlatform.ScannerView): what the camera reads goes through Android's
     * rule, with a beep for each code passed on, and the screen is kept awake while it is open, and let go after.
     */
    @Test
    fun theScannerBeepsForEachCodeAndKeepsTheScreenAwake() {
        val hooks = DesktopHooks(File(root, "b"))
        val camera = TestCamera(codes = listOf("HM:A1", "HM:A1", "HM:A1", "HM:B2", "HM:A1"))
        hooks.camera = camera
        val platform = IphonePlatform(hooks, Toasts())
        val read = mutableListOf<String>()
        var ready = false
        var awakeWhileOpen = false
        compose(platform, after = { awakeWhileOpen = hooks.awake }) {
            platform.ScannerView(ScanKind.LABELS, { read += it }, Modifier.fillMaxSize()) { ready = it != null }
        }
        assertEquals(listOf("HM:A1", "HM:B2", "HM:A1"), read)
        assertEquals(3, camera.beeps)
        assertTrue(ready, "the scanner was handed its camera")
        assertTrue(awakeWhileOpen, "the screen is kept awake while the scanner is open")
        assertFalse(hooks.awake, "and may sleep again once it is closed")
    }

    @Test
    fun thePhotoCameraKeepsTheScreenAwakeAndTakesPhotos() {
        val hooks = DesktopHooks(File(root, "c"))
        val camera = TestCamera()
        hooks.camera = camera
        val platform = IphonePlatform(hooks, Toasts())
        var live: app.housemove.ui.LiveCamera? = null
        var awakeWhileOpen = false
        compose(platform, after = { awakeWhileOpen = hooks.awake }) {
            platform.PhotoCameraView(Modifier.fillMaxSize()) { live = it }
        }
        assertTrue(awakeWhileOpen)
        assertFalse(hooks.awake)
        val file = File(root, "photo.jpg")
        var said: Throwable? = IllegalStateException("not called")
        assertNotNull(live).shoot(file) { said = it }
        assertNull(said)
        assertTrue(file.length() > 0)
        // Without a camera, the screens are told there is none.
        hooks.camera = TestCamera(hasCamera = false)
        var none: Any? = "not called"
        compose(IphonePlatform(hooks, Toasts())) { IphonePlatform(hooks, Toasts()).PhotoCameraView(Modifier.fillMaxSize()) { none = it } }
        assertNull(none)
        assertFalse(hooks.awake)
    }

    /** Joining and getting fresh data by code keep the screen awake while they run, and let it sleep after. */
    @Test
    fun theScreenStaysAwakeWhileJoiningAndSyncing() {
        val android = AndroidPhone(File(root, "phones").apply { mkdirs() })
        val hooks = DesktopHooks(File(root, "iphone"))
        val calls = java.util.concurrent.CopyOnWriteArrayList<Boolean>()
        val recording = object : PhoneHooks by hooks {
            override fun keepAwake(on: Boolean) {
                calls += on
                hooks.keepAwake(on)
            }
        }
        val model = AppModel(recording, "Test build 0.1 (1)", mainScope(), localNetworkWaitMs = 2_000)
        try {
            model.use(android.invite())
            model.yes()
            waitFor("the join") { model.store != null }
            assertEquals(listOf(true, false), calls.toList(), "joining")
            model.use(android.syncCode())
            waitFor("the sync") { (model.fresh as? Fresh.Idle)?.report?.title == "Up to date" }
            assertEquals(listOf(true, false, true, false), calls.toList(), "syncing by code")
            assertFalse(hooks.awake)

            // The walk-through on this iPhone, as its UI test does it: Alex, a Decider, picks the Hall and takes one photo,
            // which becomes a thing there with its photo.
            val store = model.store!!
            val hall = store.data.value.roomsOf(app.housemove.data.Home.ORIGIN).first { it.name == "Hall" }
            val photo = store.newPhotoFile().apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val thing = store.addByPhoto(hall.id, photo)
            assertEquals("Hall item 1", thing.name)
            val data = store.data.value
            assertEquals(hall.id, data.item(thing.id)?.originRoomId)
            assertEquals(1, data.photosOf(thing.id).size)
        } finally {
            model.stopCode()
            android.close()
        }
    }

    /** Composes [content] with [platform] for a few frames, runs [after] while it is still on screen, then closes it. */
    private fun compose(platform: IphonePlatform, after: () -> Unit = {}, content: @androidx.compose.runtime.Composable () -> Unit) {
        val scene = ImageComposeScene(width = 402, height = 874, density = Density(1f)) {
            CompositionLocalProvider(LocalPlatform provides platform) { content() }
        }
        try {
            for (frame in 0 until 5) scene.render(frame * 16_666_667L)
            after()
        } finally {
            scene.close()
        }
    }
}
