@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package app.housemove

import app.housemove.data.AppFolders
import app.housemove.data.File
import app.housemove.data.Home
import app.housemove.data.Outcome
import app.housemove.data.Store
import app.housemove.data.Update
import app.housemove.sync.AutoSync
import app.housemove.sync.LocalDiscovery
import app.housemove.sync.LocalLan
import app.housemove.sync.WifiSync
import app.housemove.sync.loopbackHost
import app.housemove.ui.iphone.AppModel
import app.housemove.ui.iphone.DemoMove
import app.housemove.ui.outputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSURL

/*
 * Demo mode, for the UI tests in CI: the app hosts a small sample move inside itself, on 127.0.0.1 only, through the
 * same WifiSync code and real sockets a real Android phone's invite uses, and the join screen shows its invite to be
 * typed in. The app then keeps its moves in folders of its own (HouseMove-demo), emptied at every start, so a test
 * never sees what the one before it joined, and nothing of the person's real moves is read or changed.
 *
 * It starts only when the app is launched with the arguments `-HMDemoHost YES`, which only Xcode, xcodebuild and
 * the UI tests can pass: an app opened from the Home Screen, TestFlight or a link is never given launch arguments.
 * The arguments are read as they were given (ProcessInfo), not through UserDefaults, which the app could keep.
 */

/** Was the app launched with `-HMDemoHost YES`? */
internal fun demoRequested(): Boolean {
    val args = NSProcessInfo.processInfo.arguments.map { it.toString() }
    val i = args.indexOf("-HMDemoHost")
    return i >= 0 && args.getOrNull(i + 1) == "YES"
}

/** Demo mode's folders for the app's own moves, beside the real ones: Application Support/HouseMove-demo(-no-backup). */
internal fun demoFolders(): Pair<File, File> {
    val data = File(AppFolders.data.path + "-demo")
    val noBackup = File(AppFolders.data.path + "-demo-no-backup")
    for (f in listOf(data, noBackup)) {
        NSFileManager.defaultManager.removeItemAtPath(f.path, error = null)
        NSFileManager.defaultManager.createDirectoryAtPath(f.path, withIntermediateDirectories = true, attributes = null, error = null)
    }
    return data to noBackup
}

/** Hosts the sample move ([DemoMove]) on 127.0.0.1 and returns its invite (for Alex, a Decider) and its sync code. */
internal fun startDemoHost(): AppModel.Demo {
    val dir = File(AppFolders.caches.path + "/demo-host")
    NSFileManager.defaultManager.removeItemAtPath(dir.path, error = null)
    NSFileManager.defaultManager.createDirectoryAtPath(dir.path, withIntermediateDirectories = true, attributes = null, error = null)
    val store = Store(dir)
    val alex = DemoMove.fill(store)
    val host = WifiSync.loopbackHost(store) {}
    demoHost = host
    demoStore = store
    // The sample move syncs by itself too, as an Android phone on 2.3 does: the app finds it inside itself (LocalLan), on
    // 127.0.0.1 only, so a test sees "Up to date" with no code typed.
    demoEngine = AutoSync(LocalDiscovery(LocalLan.shared), { store }, { true }, loopbackOnly = true).also { it.resume() }
    val loopback = listOf("127.0.0.1")
    val invite = when (val made = host.inviteFor(alex, loopback)) {
        is Outcome.Ok -> made.value.encode()
        is Outcome.Refused -> error("demo invite: ${made.reason}")
    }
    return AppModel.Demo(invite, host.invite(loopback).encode()).also { demoCodes = it }
}

/** The sample move's invite and sync code, for demo mode's fake scanner (camera/DemoCamera.kt, `-HMFakeScan`). */
internal var demoCodes: AppModel.Demo? = null
    private set

/** Kept for as long as the app runs. */
private var demoHost: WifiSync.Host? = null
private var demoEngine: AutoSync? = null
private var demoStore: Store? = null

/** Was the app launched with `-HMDemoUpdate YES`, beside `-HMDemoHost YES`? Only the UI tests pass it. */
internal fun demoUpdateRequested(): Boolean {
    val args = NSProcessInfo.processInfo.arguments.map { it.toString() }
    val i = args.indexOf("-HMDemoUpdate")
    return i >= 0 && args.getOrNull(i + 1) == "YES"
}

/**
 * Demo mode's update file (`-HMDemoUpdate YES`, the UI tests; step 3b): once this iPhone ([m]) has joined the sample move
 * and synced by itself with its phone, so that phone knows this one has HouseMove 2.5, the sample phone (Sam's) stops
 * syncing by itself, adds a thing, and sends this iPhone an update file sealed for it. The file reaches the app as a file
 * link, by the path "Open in HouseMove" brings one (openLink), and the screens show what Android shows: "Update from Sam's
 * phone".
 */
internal fun sendDemoUpdate(m: AppModel) {
    val sample = demoStore ?: return
    val folder = File(AppFolders.caches, "demo-update")
    NSFileManager.defaultManager.createDirectoryAtPath(folder.path, withIntermediateDirectories = true, attributes = null, error = null)
    val file = File(folder, "sample-move-update.housemove")
    fun write(): Boolean = try {
        file.outputStream().use { Update.write(sample, it) }.sealed
    } catch (_: Exception) {
        false
    }
    MainScope().launch {
        // Until the sample phone can seal an update for this iPhone (it has synced with it by itself). Writing one is
        // work (the move and its photos), so not too often beside the syncing it waits for.
        while (m.store == null || !withContext(Dispatchers.Default) { write() }) delay(4_000)
        // Nothing more by itself from now, so what the update brings is new to this iPhone.
        demoEngine?.pause()
        sample.createItem("Garden bench", sample.data.value.roomsOf(Home.ORIGIN).firstOrNull()?.id)
        if (withContext(Dispatchers.Default) { write() }) NSURL.fileURLWithPath(file.path).absoluteString?.let { openLink(it) }
    }
}
