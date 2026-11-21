package app.housemove

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import app.housemove.data.*
import app.housemove.pdf.Csv
import app.housemove.pdf.LabelSheet
import app.housemove.pdf.Pdf
import app.housemove.pdf.Share
import app.housemove.pdf.labelCode
import app.housemove.sync.WifiSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Runs the real app on real Android (an emulator in CI): the installed APK,
 * the real camera stack, real PDF generation, real sockets and file sharing.
 * Tests whose names start with "fresh" begin on an empty install; the others
 * begin with a small move already set up.
 */
@RunWith(AndroidJUnit4::class)
class DeviceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val store: Store get() = (context as App).store
    private val data get() = store.data.value

    private val prepare = object : TestWatcher() {
        override fun starting(description: Description) = reset(seed = !description.methodName.startsWith("fresh"))
    }

    @get:Rule(order = 0)
    val cameraPermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    @get:Rule(order = 1)
    val prepared = prepare

    @get:Rule(order = 2)
    val rule = createAndroidComposeRule<MainActivity>()

    private fun reset(seed: Boolean) {
        store.photoDir.listFiles()?.forEach { it.delete() }
        store.replaceAll(AppData())
        // Keep who this phone is: wiping it would leave the phone with no person and so no right to set the move up.
        store.setDevice { DeviceInfo(name = "Tester", letter = "T", nextSeq = 1, personId = it.personId) }
        if (seed) {
            store.setMove { it.copy(name = "Device test", moveDay = today() + 7, setupDone = true) }
            val newKitchen = store.createRoom(Home.DEST, "Kitchen")
            val newBedroom = store.createRoom(Home.DEST, "Bedroom")
            val oldKitchen = store.createRoom(Home.ORIGIN, "Kitchen")
            store.put(oldKitchen.copy(mapsTo = newKitchen.id))
            val oldBedroom = store.createRoom(Home.ORIGIN, "Bedroom")
            store.put(oldBedroom.copy(mapsTo = newBedroom.id))
            store.put(Box(id = "box1", label = "L001", originRoomId = oldKitchen.id, destRoomId = newKitchen.id, contents = "Kettle and mugs"))
            store.put(Item(id = "item1", name = "Oak wardrobe", originRoomId = oldBedroom.id, destRoomId = newBedroom.id, disposition = Disposition.MOVE))
        }
        store.flush()
    }

    // ---- helpers -------------------------------------------------------------

    private fun click(node: SemanticsNodeInteraction) {
        try {
            node.performScrollTo()
        } catch (_: AssertionError) {
            // not inside a scrolling container
        }
        node.performClick()
        rule.waitForIdle()
    }

    private fun tap(text: String, sub: Boolean = false) =
        click(rule.onAllNodes(hasText(text, substring = sub) and hasClickAction()).onFirst())

    private fun tapDesc(description: String) =
        click(rule.onAllNodes(hasContentDescription(description) and hasClickAction()).onFirst())

    /** Tap a row of a lazy list that may be below the visible part. */
    private fun tapInList(text: String) {
        try {
            rule.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText(text))
        } catch (_: Throwable) {
            // already visible, or the screen does not scroll
        }
        tap(text)
    }

    private fun type(label: String, value: String) {
        val node = rule.onAllNodes(hasSetTextAction() and hasText(label)).onFirst()
        try {
            node.performScrollTo()
        } catch (_: AssertionError) {
        }
        node.performTextReplacement(value)
        rule.waitForIdle()
    }

    private fun see(text: String) = rule.onAllNodes(hasText(text, substring = true)).onFirst().assertExists()

    private fun waitFor(what: String, timeoutMs: Long = 20_000, condition: () -> Boolean) {
        try {
            rule.waitUntil(timeoutMs) { condition() }
        } catch (e: Throwable) {
            throw AssertionError("Timed out waiting for: $what", e)
        }
    }

    /** Save a screenshot where the build collects it; never fails the test. */
    private fun shot(name: String) {
        val dir = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir") ?: return
        try {
            val roots = rule.onAllNodes(isRoot())
            val last = roots.fetchSemanticsNodes().size - 1
            val bmp = roots[last].captureToImage().asAndroidBitmap()
            File(dir).mkdirs()
            File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } catch (e: Throwable) {
            Log.w("DeviceTest", "screenshot $name failed: $e")
        }
    }

    private fun tempStore(): Store = Store(File(context.cacheDir, "test-" + UUID.randomUUID()).apply { mkdirs() })

    private fun jpeg(file: File, color: Int) {
        val bmp = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(color)
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
    }

    // ---- the whole journey on a fresh install --------------------------------

    @Test
    fun fresh_installThroughToUnpacked() {
        see("What is it for?")
        shot("01-setup")
        tap("Next") // keep "Moving it myself"
        tap("Next")
        tap("Kitchen")
        tap("Next")
        tap("Kitchen")
        tap("Finish")
        waitFor("setup to finish") { data.move.setupDone }
        assertEquals(data.roomsOf(Home.DEST).single().id, data.roomsOf(Home.ORIGIN).single().mapsTo)
        assertTrue(data.liveTasks.size > 40)
        shot("02-home")

        tap("Pack a box")
        tap("Kitchen")
        tap("No sticker? Use a handwritten code")
        waitFor("the new box") { data.liveBoxes.size == 1 }
        val box = data.liveBoxes.single()
        assertEquals("T1", box.label)
        type("Contents", "Mugs and plates")
        shot("03-box")
        tap("Seal box")
        assertTrue(data.box(box.id)!!.sealed)
        assertEquals("Mugs and plates", data.box(box.id)!!.contents)
        tapDesc("Back")
        tapDesc("Back")

        tap("Move day")
        shot("04-move-day")
        tap("Load the van")
        tap("Load all")
        assertEquals(Stage.LOADED, data.box(box.id)!!.stage)
        shot("05-load")
        tapDesc("Back")

        tap("Unload") // opens with the live camera scanner running
        shot("06-unload")
        tap("Here")
        assertEquals(Stage.DELIVERED, data.box(box.id)!!.stage)
        shot("07-arrived")
        tapDesc("Back")

        tap("Unpack")
        tap("Unpacked")
        assertEquals(Stage.DONE, data.box(box.id)!!.stage)
        shot("08-unpacked")
    }

    // ---- camera ---------------------------------------------------------------

    @Test
    fun cameraTakesAPhotoInTheWalkThrough() {
        assumeTrue("no camera on this device", context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY))
        val before = data.liveItems.size
        tap("Walk-through")
        tap("Kitchen")
        tap("Start shooting")
        waitFor("the camera screen") {
            rule.onAllNodes(hasContentDescription("Take photo")).fetchSemanticsNodes().isNotEmpty()
        }
        // Give the camera a moment to start streaming, then shoot; retry if it was not ready yet.
        var attempts = 0
        while (data.livePhotos.isEmpty() && attempts < 4) {
            Thread.sleep(3000)
            if (rule.onAllNodes(hasContentDescription("Take photo") and hasClickAction()).fetchSemanticsNodes().isNotEmpty()) {
                tapDesc("Take photo")
            }
            try {
                rule.waitUntil(8000) { data.livePhotos.isNotEmpty() }
            } catch (_: Throwable) {
            }
            attempts++
        }
        assertTrue("no photo was saved after $attempts attempts", data.livePhotos.isNotEmpty())
        shot("10-camera")
        val photo = data.livePhotos.first()
        val file = store.photoFile(photo)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        assertTrue("photo is not a readable image", bounds.outWidth > 100 && bounds.outHeight > 100)
        assertTrue("photo is unexpectedly large: ${file.length()}", file.length() < 3_000_000)
        tap("Done")
        assertEquals(before + 1, data.liveItems.size)
        val item = data.item(photo.ownerId)
        assertNotNull(item)
        assertEquals("Kitchen", data.room(item!!.originRoomId)!!.name)
        shot("11-walk-through")
    }

    @Test
    fun scannerRunsOnLiveCameraFramesAndTypedCodesOpenTheBox() {
        tapDesc("Scan a label")
        Thread.sleep(4000) // live frames are being analysed for codes during this time
        rule.waitForIdle()
        shot("12-scan")
        type("Or type the code on the label", "L001")
        tap("Open")
        see("Kettle and mugs")
        tapDesc("Back")
        type("Or type the code on the label", "L555")
        tap("Open")
        see("Label L555 is not in use")
        tap("New box")
        assertNotNull(data.findByLabel("L555"))
    }

    // ---- dialogs with typing at real phone width --------------------------------

    @Test
    fun dialogsWithTextFieldsWorkAtPhoneWidth() {
        tap("More")
        tapInList("Timeline")
        tapDesc("Add a task") // a job is normally spoken; tapping the line opens the keyboard's way, in the page
        type("What needs doing", "Return the ladder")
        // Tap into the field too, so it is focused with the keyboard up, as a person would.
        rule.onAllNodes(hasSetTextAction() and hasText("Return the ladder")).onFirst().performClick()
        rule.waitForIdle()
        shot("13-task-dialog")
        tap("Add")
        assertTrue(data.liveTasks.any { it.title == "Return the ladder" })
        tapDesc("Back")

        tapInList("Rooms")
        tap("Kitchen")
        see("Old-home room")
        type("Name", "Big kitchen")
        tap("Done")
        assertTrue(data.roomsOf(Home.ORIGIN).any { it.name == "Big kitchen" })
        tapDesc("Back")

        tapInList("Supplies and costs")
        tap("Costs")
        tap("Add a cost") // opens in the page; a cost is normally tapped in, and the keyboard sits behind these two
        tap("Other amount")
        type("Amount", "180")
        tap("Details")
        type("Name", "Van hire")
        tap("Add cost", sub = true)
        assertEquals(180.0, data.liveExpenses.single().amount, 0.001)
        assertEquals("Van hire", data.liveExpenses.single().title)
    }

    // ---- every screen -----------------------------------------------------------

    @Test
    fun everyScreenOpensFromTheMenus() {
        for (tab in listOf("Items", "Boxes", "Move day", "More", "Home")) tap(tab)
        tap("Items")
        tap("Oak wardrobe")
        see("What happens to it?")
        shot("20-item")
        tapDesc("Back")
        tap("Boxes")
        tap("L001", sub = true)
        see("Seal box")
        tapDesc("Back")

        tap("Move day")
        for (row in listOf("Old home record", "Load the van", "Final sweep", "New home record", "Unload", "Reconcile", "Unpack")) {
            tapInList(row)
            rule.waitForIdle()
            tapDesc("Back")
        }
        tap("More")
        val menu = listOf(
            "Rooms", "Triage", "Devices", "Plan rooms", "Sell, donate, bin", "Timeline", "Supplies and costs",
            "Labels and signs", "Packing order", "Rooms overview", "Special lists", "Unpack", "Reports", "Sync and backup", "Settings",
        )
        for (row in menu) {
            tapInList(row)
            rule.waitForIdle()
            if (row == "Labels and signs") shot("21-labels")
            if (row == "Sync and backup") {
                tap("Show sync code") // starts the real listener and draws the code
                shot("22-sync-code")
                tap("Stop showing the code")
            }
            tapDesc("Back")
        }
        tapDesc("Find anything")
        type("What are you looking for?", "kettle")
        see("Box L001")
    }

    // ---- barcodes already on things, and devices ------------------------------------

    @Test
    fun booksGoIntoABoxAndSerialNumbersOntoADevice() {
        tap("Boxes")
        tap("L001", sub = true)
        tap("Scan books and products into this box")
        Thread.sleep(3000) // the camera looks for book and product barcodes, upright and sideways
        rule.waitForIdle()
        shot("30-scan-into-box")
        type("Or type a barcode number or a name", "9780140328721")
        tap("Add")
        see("Look up book titles online?")
        tap("No, stay offline")
        assertEquals("9780140328721", data.entriesOf("box1").single().code)
        type("Or type a barcode number or a name", "Photo albums")
        tap("Add")
        assertEquals(2, data.entriesOf("box1").size)
        tap("Done")
        see("Scanned in (2)")
        tapDesc("Back")

        tap("Items")
        tap("Oak wardrobe")
        tap("It has a make, model and serial number")
        type("Make", "Acme")
        type("Serial number", "SN-TEST-1")
        assertEquals("SN-TEST-1", data.item("item1")!!.serial)
        tap("Scan the serial number barcode")
        Thread.sleep(2500) // the camera looks for serial-number barcodes
        rule.waitForIdle()
        shot("31-scan-serial")
        type("Or type the serial number", "SN-TEST-2")
        tap("OK")
        assertEquals("SN-TEST-2", data.item("item1")!!.serial)
        see("SN-TEST-2")
        shot("32-device-details")
        tapDesc("Back")

        tapDesc("Scan a label") // "what is this?" finds things by serial number and by book barcode
        type("Or type the code on the label", "sn-test-2")
        tap("Open")
        see("What happens to it?")
        tapDesc("Back")
        type("Or type the code on the label", "9780140328721")
        tap("Open")
        see("Kettle and mugs")
    }

    /** The one online feature, against the real public catalogue. Skipped, not failed, when there is no connection. */
    @Test
    fun bookTitleLookupWorksAgainstTheRealCatalogue() {
        val info = try {
            BookLookup.fetch("9780140328721", timeoutMs = 15_000)
        } catch (e: java.io.IOException) {
            Log.w("DeviceTest", "catalogue not reachable: $e")
            assumeTrue("catalogue not reachable from this device: $e", false)
            null
        }
        assertNotNull("the catalogue did not list a very well known book", info)
        assertTrue("unexpected title: ${info!!.title}", info.title.contains("Fox", ignoreCase = true))
        assertTrue("unexpected author: ${info.author}", info.author.contains("Dahl", ignoreCase = true))
        // And through the app's own flow: scanning it into a box with lookups switched on fills the title in.
        store.setDevice { it.copy(bookLookup = true) }
        tap("Boxes")
        tap("L001", sub = true)
        tap("Scan books and products into this box")
        type("Or type a barcode number or a name", "9780140328721")
        tap("Add")
        waitFor("the title to arrive", 25_000) { data.entriesOf("box1").firstOrNull()?.title?.contains("Fox", ignoreCase = true) == true }
        shot("33-book-title")
    }

    // ---- restart and recreation ---------------------------------------------------

    @Test
    fun editsSurviveTheScreenBeingRebuiltAndTheAppBeingStopped() {
        tap("Boxes")
        tap("L001", sub = true)
        type("Contents", "Changed on the device")
        rule.activityRule.scenario.recreate()
        rule.waitForIdle()
        assertEquals("Changed on the device", data.box("box1")!!.contents)
        see("Seal box") // still on the same box after being rebuilt

        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED) // app sent to the background
        val saved = File(context.filesDir, "data.json").readText()
        assertTrue(saved.contains("Changed on the device"))
        assertEquals("Changed on the device", AppJson.decodeFromString<AppData>(saved).box("box1")!!.contents)
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        rule.waitForIdle()
        see("Seal box")
    }

    // ---- printing ------------------------------------------------------------------

    /** Opens each generated PDF with Android's own renderer and scans every label on the page. */
    @Test
    fun everyLabelInTheRealPdfScansBackToItsCode() {
        val decoder = Qr.Decoder()
        for (sheet in LabelSheet.entries) {
            val codes = (1..sheet.perPage + 3).map { labelCode(it + 200) }
            val file = Pdf.labels(context, codes, sheet)
            PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).use { pdf ->
                assertEquals(sheet.name, 2, pdf.pageCount)
                pdf.openPage(0).use { page ->
                    assertEquals(sheet.pageW, page.width)
                    assertEquals(sheet.pageH, page.height)
                    val scale = 3
                    val bmp = Bitmap.createBitmap(page.width * scale, page.height * scale, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                    codes.take(sheet.perPage).forEachIndexed { i, code ->
                        val x = ((sheet.left + (i % sheet.cols) * (sheet.cellW + sheet.gapX)) * scale).toInt()
                        val y = ((sheet.top + (i / sheet.cols) * (sheet.cellH + sheet.gapY)) * scale).toInt()
                        val w = (sheet.cellW * scale).toInt().coerceAtMost(bmp.width - x)
                        val h = (sheet.cellH * scale).toInt().coerceAtMost(bmp.height - y)
                        val px = IntArray(w * h)
                        bmp.getPixels(px, 0, w, x, y, w, h)
                        val luma = ByteArray(w * h) { n -> val p = px[n]; ((Color.red(p) + Color.green(p) + Color.blue(p)) / 3).toByte() }
                        assertEquals("${sheet.name} label $i", QR_PREFIX + code, decoder.decode(luma, w, h))
                    }
                }
            }
        }
    }

    @Test
    fun reportsAndDoorSignsAreRealPdfs() {
        val s = tempStore()
        val kitchen = s.createRoom(Home.DEST, "Kitchen")
        s.createRoom(Home.DEST, "Children's bedroom at the back of the house")
        s.setMove { it.copy(name = "Report test", moveDay = today() + 3, currency = "£") }
        repeat(150) { n -> s.put(Box(id = "b$n", label = labelCode(n + 1), contents = "Books and things $n", destRoomId = kitchen.id)) }
        s.put(Item(id = "i1", name = "Table", disposition = Disposition.MOVE, destRoomId = kitchen.id, damaged = true, damageNote = "Leg cracked", value = 120.0))
        for (kind in listOf(PhotoKind.CONDITION, PhotoKind.DAMAGE)) {
            val f = s.newPhotoFile()
            jpeg(f, if (kind == PhotoKind.DAMAGE) Color.RED else Color.GREEN)
            s.addPhoto("i1", kind, f)
        }
        fun pages(file: File): Int =
            PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).use { it.pageCount }

        assertEquals(2, pages(Pdf.doorSigns(context, s.data.value, letter = false)))
        assertEquals(1, pages(Pdf.doorSigns(context, AppData(), letter = true)))
        assertTrue(pages(Pdf.inventory(context, s.data.value)) >= 3)
        val damageFile = Pdf.damageReport(context, s)
        assertEquals(1, pages(damageFile))
        val damage = File(context.cacheDir, "damage-copy.pdf").also { damageFile.copyTo(it, overwrite = true) }
        val withoutPhotos = Pdf.damageReport(context, tempStore()).length()
        assertTrue("damage report should embed the photos", damage.length() > withoutPhotos + 500)
        assertEquals(152, Csv.inventory(context, s.data.value).readText().trim().lines().size)
    }

    // ---- phones talking to each other, and files leaving the app ----------------------

    @Test
    fun syncBackupAndSharingWorkOnRealAndroid() {
        val a = tempStore()
        val b = tempStore()
        a.setDevice { it.copy(name = "Asha", letter = "A") }
        b.setDevice { it.copy(name = "Ben", letter = "B") }
        val boxA = a.createBox("L001", null)
        val photoA = a.newPhotoFile().also { jpeg(it, Color.BLUE) }
        a.addPhoto(boxA.id, PhotoKind.CONTENTS, photoA)
        val boxB = b.createBox("B1", null)
        val photoB = b.newPhotoFile().also { jpeg(it, Color.YELLOW) }
        b.addPhoto(boxB.id, PhotoKind.CONTENTS, photoB)

        WifiSync.localAddresses() // must not throw on a real network stack
        val done = CountDownLatch(1)
        val host = WifiSync.host(a) { done.countDown() }
        try {
            val r = WifiSync.join(b, WifiSync.Invite.parse(host.invite(listOf("127.0.0.1")).encode())!!)
            assertTrue(r.error, r.ok)
            assertEquals(1, r.photosSent)
            assertEquals(1, r.photosReceived)
            assertTrue(done.await(10, TimeUnit.SECONDS))
        } finally {
            host.stop()
        }
        for (s in listOf(a, b)) {
            assertEquals(setOf("L001", "B1"), s.data.value.liveBoxes.map { it.label }.toSet())
            assertTrue(File(s.photoDir, photoA.name).length() > 0)
            assertTrue(File(s.photoDir, photoB.name).length() > 0)
        }

        // A backup written to the share folder can be read back through the same route another app would use.
        val zip = Share.outFile(context, "device-test-backup.zip")
        zip.outputStream().use { Backup.export(a, it) }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", zip)
        val c = tempStore()
        context.contentResolver.openInputStream(uri)!!.use { Backup.import(c, it) }
        assertEquals(2, c.data.value.liveBoxes.size)
        assertTrue(File(c.photoDir, photoB.name).length() > 0)
        assertFalse(c.data.value.photosOf(boxA.id).isEmpty())
    }
}
