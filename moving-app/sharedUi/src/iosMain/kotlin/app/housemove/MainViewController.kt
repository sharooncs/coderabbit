package app.housemove

import androidx.compose.ui.uikit.OnFocusBehavior
import androidx.compose.ui.window.ComposeUIViewController
import app.housemove.data.AppFolders
import app.housemove.data.KeychainServices
import app.housemove.data.useAppleCrypto
import app.housemove.data.useAppleCryptoInMemory
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
 * What the iPhone app shows; its SwiftUI StartView hosts it (in Swift:
 * `MainViewControllerKt.MainViewController(buildLabel:crypto:)`). Join a move that lives on an Android phone, then the
 * shared app with it (step 2 of design/ios-plan.md), get fresh data, leave it. [buildLabel] names the build, as in "Test build 0.1
 * (202611241030)". [crypto] is the app's CryptoKit (CryptoKitCrypto.swift): the shared code's P-256 and AES-GCM for update
 * files, with this phone's private key in the Keychain (design/encryption.md). Launched with `-HMCryptoCheck YES` (the UI
 * tests only), the app checks that crypto against Android's instead (CryptoCheckScreen.kt).
 */
fun MainViewController(buildLabel: String, crypto: AppleCrypto): UIViewController {
    if (cryptoCheckRequested()) return ComposeUIViewController { CryptoCheckScreen(crypto) }
    if (pdfCheckRequested()) return ComposeUIViewController { PdfCheckScreen() }
    val m = model ?: run {
        // What the last run left to hand on or had opened (IosFiles.kt), before any file link of this run is taken in.
        IosFiles.clean()
        val demo = demoRequested()
        val hooks = if (demo) {
            val (data, noBackup) = demoFolders()
            // Demo mode's keys: CryptoKit's, kept in memory for as long as the app runs (its folders are emptied at every start),
            // so the demo's phones sync encrypted also when an unsigned build may not use the Keychain.
            useAppleCryptoInMemory(SwiftCrypto(crypto))
            IosHooks(data, noBackup, demo = true)
        } else {
            // Photos and the phone's own codes are kept out of iCloud backups, as Android keeps them out of its backup.
            AppFolders.prepare()
            // Before any move is opened: this phone's key for update files is CryptoKit's, its private half in the Keychain.
            useAppleCrypto(SwiftCrypto(crypto), KeychainServices.PHONE, AppFolders.noBackup)
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
    // The screens make room for the keyboard themselves (the safe area includes it, and the shared screens' imePadding),
    // and scroll the field being typed in into view; Compose's own lift of the whole screen would add to that and slide
    // the top bar under the clock.
    return ComposeUIViewController(configure = { onFocusBehavior = OnFocusBehavior.DoNothing }) { HouseMoveApp(m) }
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
