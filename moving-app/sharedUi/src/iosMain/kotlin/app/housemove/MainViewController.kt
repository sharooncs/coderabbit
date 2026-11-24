package app.housemove

import androidx.compose.ui.window.ComposeUIViewController
import app.housemove.ui.trial.TrialScreen
import platform.UIKit.UIViewController

/**
 * What the iPhone app shows; its SwiftUI StartView hosts it (in Swift: `MainViewControllerKt.MainViewController`).
 * For now the trial screen of step 0e; the app's screens come in step 2. [buildLabel] names the build, as in
 * "Test build 0.1 (202611241030)".
 */
fun MainViewController(buildLabel: String): UIViewController = ComposeUIViewController { TrialScreen(buildLabel) }
