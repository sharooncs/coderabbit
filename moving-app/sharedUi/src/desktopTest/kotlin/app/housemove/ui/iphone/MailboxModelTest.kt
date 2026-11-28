package app.housemove.ui.iphone

import app.housemove.sync.MailboxSync
import app.housemove.sync.WifiSync
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The iPhone and the internet mailbox (design/encryption.md, "Mailbox as built"): the mailbox's code, from the Camera's
 * "Open in HouseMove" or pasted, goes to "Get fresh data" and sets it up there; a Decider is told only an Organiser can,
 * until the move has one; the switch is kept. The engine itself is shared code, tested in shared.
 */
class MailboxModelTest {
    private val root = newRoot()
    private val android = AndroidPhone(root)
    private val hooks = DesktopHooks(File(root, "iphone"))
    private val model = AppModel(hooks, "Test build 0.1 (1)", mainScope(), localNetworkWaitMs = 2_000)
    private val code = "HMBOX:1:${"ab".repeat(32)}:https://example-laptop.example-tailnet.ts.net"

    @AfterTest
    fun cleanUp() {
        model.mailbox?.close()
        android.close()
        root.deleteRecursively()
    }

    @Test
    fun theCodeGoesToGetFreshData() {
        // Demo mode and the desktop have no engine; the iPhone's hooks say it has one.
        assertNull(model.mailbox)
        model.use(android.invite())
        model.yes()
        waitFor("the join") { model.store != null || model.join is JoinStep.Failed }
        val s = assertNotNull(model.store)
        // Alex is a Decider: with no mailbox in the move yet, only an Organiser may set one up.
        model.use(code)
        waitFor("the answer") { model.fresh is Fresh.Idle && (model.fresh as Fresh.Idle).report != null }
        // The code shows Get fresh data, where the answer is.
        assertTrue(model.freshAsked > 0)
        val no = (model.fresh as Fresh.Idle).report!!
        assertFalse(no.ok)
        assertEquals(MailboxSync.ONLY_ORGANISERS, no.text)
        assertEquals("", s.data.value.mailbox.url)

        // The Organiser's Android phone sets it up; a sync brings it to the iPhone; then the same code is taken here too.
        assertTrue(MailboxSync.setUp(android.store, code).first)
        val r = WifiSync.join(s, WifiSync.Invite.parse(android.syncCode())!!, letNewPhonesIn = false)
        assertTrue(r.ok, r.error)
        assertEquals("https://example-laptop.example-tailnet.ts.net", s.data.value.mailbox.url)
        model.use("  $code\n")
        waitFor("taken") { (model.fresh as? Fresh.Idle)?.report?.ok == true }
        assertEquals("This phone can use the internet mailbox now.", (model.fresh as Fresh.Idle).report!!.text)

        // The switch, kept in the phone's settings.
        assertTrue(model.mailboxOn)
        model.setMailbox(false)
        assertFalse(model.moves.phone.settings.value.mailbox)
        model.setMailbox(true)
        assertTrue(model.moves.phone.settings.value.mailbox)
    }
}
