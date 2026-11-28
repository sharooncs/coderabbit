package app.housemove.ui.iphone

import app.housemove.data.SCHEMA
import app.housemove.sync.WifiSync
import app.housemove.ui.IPHONE_GETS_IT
import app.housemove.ui.PhoneWords
import app.housemove.ui.hasIphone
import app.housemove.ui.isIphoneName
import app.housemove.ui.screens.ANDROID_OLDER
import app.housemove.ui.screens.IF_IPHONE
import app.housemove.ui.screens.IPHONE_NEWER
import app.housemove.ui.screens.IPHONE_OLDER
import app.housemove.ui.screens.NEWER_THERE
import app.housemove.ui.screens.OLDER_THERE
import app.housemove.ui.screens.UPDATE_THIS_IPHONE
import app.housemove.ui.screens.syncReport
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The words that differ between the phones (step 5 of design/ios-plan.md): when two phones have different versions of
 * HouseMove, which one to update and how, on Android and on the iPhone; how a phone knows the other is an iPhone (the
 * name it gives itself); and Android's words where nothing about an iPhone is known, exactly as they were.
 */
class PhoneWordsTest {
    private val root = newRoot()
    private val android = AndroidPhone(root)

    @AfterTest
    fun cleanUp() {
        android.close()
        root.deleteRecursively()
    }

    private fun mismatch(older: Boolean, device: String = "") = WifiSync.Result(
        "Priya", "P", 0, 0, WifiSync.VERSION_MISMATCH, shareApp = older, peerSchema = if (older) SCHEMA - 1 else SCHEMA + 1, peerDevice = device,
    )

    @Test
    fun anIphoneIsKnownByTheNameItGivesItself() {
        assertTrue(isIphoneName("iPhone"))
        assertTrue(isIphoneName("iPhone 17 Pro"), "the simulator's name")
        assertTrue(isIphoneName("Priya's iPhone"))
        assertTrue(isIphoneName("iPad"))
        assertFalse(isIphoneName(""))
        assertFalse(isIphoneName("Pixel 8"))
        assertFalse(isIphoneName("samsung SM-S911B"))
        assertFalse(isIphoneName("Galaxy S23"))
    }

    @Test
    fun androidSaysWhatItAlwaysSaidWhereNoIphoneIsInvolved() {
        val s = android.store
        assertFalse(s.data.value.hasIphone())
        val older = syncReport(mismatch(older = true, device = "Pixel 8"), s)
        assertEquals(OLDER_THERE, older.text)
        assertTrue(older.shareApp, "Share the app and the download code are offered")
        assertEquals(NEWER_THERE, syncReport(mismatch(older = false), s).text)
        // Unknown which phone, and no iPhone in the move: as before.
        assertEquals(OLDER_THERE, syncReport(mismatch(older = true), s).text)
    }

    @Test
    fun androidTellsAnIphonesPersonToUpdateFromTestFlight() {
        val s = android.store
        val older = syncReport(mismatch(older = true, device = "iPhone"), s)
        assertEquals(IPHONE_OLDER, older.text)
        assertFalse(older.shareApp, "Android's app is no use to an iPhone: no Share the app, no download code")
        assertEquals(IPHONE_NEWER, syncReport(mismatch(older = false, device = "iPhone"), s).text)

        // Once an iPhone is in the move, a phone that could not be told apart hears both ways.
        val model = AppModel(DesktopHooks(File(root, "iphone")), "Test build 0.1 (1)", mainScope())
        model.use(android.invite())
        model.yes()
        waitFor("the join") { model.store != null }
        waitFor("the iPhone's phone in the move") { s.data.value.hasIphone() }
        val unknown = syncReport(mismatch(older = true), s)
        assertEquals(OLDER_THERE + IF_IPHONE, unknown.text)
        assertTrue(unknown.shareApp, "it may still be an Android phone")
    }

    @Test
    fun theIphoneTellsWhichPhoneToUpdate() {
        val iphone = PhoneWords.IPHONE
        val older = syncReport(mismatch(older = true), null, iphone)
        assertEquals(ANDROID_OLDER, older.text)
        assertFalse(older.shareApp, "an iPhone shares no app")
        assertEquals(IPHONE_OLDER, syncReport(mismatch(older = true, device = "iPhone"), null, iphone).text)
        assertEquals(UPDATE_THIS_IPHONE, syncReport(mismatch(older = false), null, iphone).text)
        // The iPhone's own screens (joining, Get fresh data) say the same.
        assertEquals(ANDROID_OLDER, Words.problem(mismatch(older = true)).text)
        assertEquals(UPDATE_THIS_IPHONE, Words.syncResult(mismatch(older = false)).text)
        assertEquals("Different versions", Words.syncResult(mismatch(older = false)).title)
    }

    @Test
    fun theWordsOfEachPhone() {
        assertEquals("from gallery", PhoneWords.ANDROID.fromGallery)
        assertEquals("Open Settings, then Apps, HouseMove, Permissions.", PhoneWords.ANDROID.cameraSettingsSteps)
        assertTrue(PhoneWords.ANDROID.downloadCode)
        assertFalse(PhoneWords.IPHONE.downloadCode, "the download code is Android's app")
        assertTrue(PhoneWords.IPHONE.getTheApp.contains("TestFlight"))
        assertTrue(IPHONE_GETS_IT.contains("TestFlight invite"))
        for (w in listOf(PhoneWords.IPHONE.fromGallery, PhoneWords.IPHONE.allowCameraHint, PhoneWords.IPHONE.cameraSettingsSteps)) {
            assertFalse(w.contains("Apps,") || w.contains("gallery") || w.contains("app settings"), w)
        }
    }
}
