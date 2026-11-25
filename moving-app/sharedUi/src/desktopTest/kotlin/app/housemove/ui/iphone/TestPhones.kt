package app.housemove.ui.iphone

import app.housemove.data.Outcome
import app.housemove.data.Store
import app.housemove.sync.AutoSync
import app.housemove.sync.LocalDiscovery
import app.housemove.sync.LocalLan
import app.housemove.sync.WifiSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors

/*
 * The two phones of the desktop tests, in one JVM: "the Android phone", a Store holding the sample move (DemoMove)
 * that hosts with WifiSync, and "the iPhone", an AppModel with its own folders, driven as its screens drive it.
 * They meet over real sockets on 127.0.0.1.
 */

/**
 * The iPhone's side of the platform, on a plain JVM. [network] is what "iOS" says about the local network. With a [lan],
 * the iPhone syncs by itself with the phones on it (the tests' Bonjour); without, it does not.
 */
internal class DesktopHooks(
    root: File,
    var network: LocalNetwork = LocalNetwork.UNKNOWN,
    var clipboard: String? = null,
    private val lan: LocalLan? = null,
) : PhoneHooks {
    override val dataDir = File(root, "data").apply { mkdirs() }
    override val noBackupDir = File(root, "no-backup").apply { mkdirs() }
    var asked = 0
    var settingsOpened = 0
    var awake = false

    override fun deviceName() = "iPhone"
    override fun openSettings() {
        settingsOpened++
    }
    override fun checkLocalNetwork(done: (LocalNetwork) -> Unit) {
        asked++
        done(network)
    }
    override fun keepAwake(on: Boolean) {
        awake = on
    }
    override fun paste(): String? = clipboard
    override fun deleteFolder(folder: File) {
        folder.deleteRecursively()
    }
    override fun discovery() = lan?.let { LocalDiscovery(it) }
    override val loopbackOnly = true
}

/** The Android phone: the sample move, hosting. With a [lan], it syncs by itself too, as a phone on 2.3 does. */
internal class AndroidPhone(root: File, lan: LocalLan? = null) {
    val store = Store(File(root, "android").apply { mkdirs() })
    val alexId = DemoMove.fill(store)
    val results = java.util.concurrent.CopyOnWriteArrayList<WifiSync.Result>()
    val host: WifiSync.Host = WifiSync.host(store, letNewPhonesIn = false) { results.add(it) }
    val engine: AutoSync? = lan?.let { AutoSync(LocalDiscovery(it), { store }, { true }, loopbackOnly = true).also { e -> e.resume() } }

    /** Alex's invite (a Decider), as the QR code on the Android phone holds it. */
    fun invite(personId: String = alexId): String = (host.inviteFor(personId, listOf("127.0.0.1")) as Outcome.Ok).value.encode()

    /** The plain sync code ("Show sync code"). */
    fun syncCode(): String = host.invite(listOf("127.0.0.1")).encode()

    fun close() {
        engine?.close()
        host.stop()
        store.close()
    }
}

/** The main thread of the tests' iPhone: every coroutine of the model runs on it, as on the iPhone's main thread. */
internal fun mainScope(): CoroutineScope =
    CoroutineScope(SupervisorJob() + Executors.newSingleThreadExecutor { r -> Thread(r, "iphone-main").apply { isDaemon = true } }.asCoroutineDispatcher())

internal fun newRoot(): File = Files.createTempDirectory("hm-iphone").toFile()

/** Waits up to [ms] for [done], then fails with [what]. */
internal fun waitFor(what: String, ms: Long = 20_000, done: () -> Boolean) {
    val until = System.currentTimeMillis() + ms
    while (!done()) {
        if (System.currentTimeMillis() > until) throw AssertionError("timed out waiting for $what")
        Thread.sleep(20)
    }
}
