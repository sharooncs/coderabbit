package app.housemove

import androidx.compose.ui.window.ComposeUIViewController
import app.housemove.data.AppFolders
import app.housemove.ui.iphone.AppModel
import app.housemove.ui.iphone.HouseMoveApp
import kotlinx.coroutines.MainScope
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIViewController

/** The app's one model, once the screen has been made; a link that comes before it waits in [waitingLink]. */
private var model: AppModel? = null
private var waitingLink: String? = null

/**
 * What the iPhone app shows; its SwiftUI StartView hosts it (in Swift: `MainViewControllerKt.MainViewController`).
 * The first iPhone build: join a move that lives on an Android phone, look at it read-only, get fresh data, leave
 * it. [buildLabel] names the build, as in "Test build 0.1 (202611241030)".
 */
fun MainViewController(buildLabel: String): UIViewController {
    val m = model ?: run {
        val demo = demoRequested()
        val hooks = if (demo) {
            val (data, noBackup) = demoFolders()
            IosHooks(data, noBackup, demo = true)
        } else {
            // Photos and the phone's own codes are kept out of iCloud backups, as Android keeps them out of its backup.
            AppFolders.prepare()
            IosHooks(AppFolders.data, AppFolders.noBackup)
        }
        AppModel(hooks, buildLabel, MainScope(), demo = if (demo) startDemoHost() else null).also {
            model = it
            followTheApp(it)
        }
    }
    waitingLink?.let {
        waitingLink = null
        m.use(it)
    }
    return ComposeUIViewController { HouseMoveApp(m) }
}

/**
 * Syncing by itself runs only while the app is at the front: iOS lets an app in the background neither listen nor keep
 * connections. UIKit says when the app becomes active and when it goes to the background.
 */
private fun followTheApp(m: AppModel) {
    val center = NSNotificationCenter.defaultCenter
    center.addObserverForName(UIApplicationDidBecomeActiveNotification, null, NSOperationQueue.mainQueue) { _ -> m.foreground() }
    center.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, NSOperationQueue.mainQueue) { _ -> m.background() }
}

/**
 * A link opened with HouseMove: an invite or a sync code (`HMSYNC:…`, which iOS hands over as an `hmsync:` link when
 * the Camera reads an Android phone's QR code and the person taps Open in HouseMove). The SwiftUI app calls it from
 * onOpenURL (in Swift: `MainViewControllerKt.openLink(text:)`), on the main thread.
 */
fun openLink(text: String) {
    val m = model
    if (m == null) waitingLink = text else m.use(text)
}
