import HouseMoveKit
import SwiftUI

/// The iPhone app's entry point. One window (UIScene life cycle, required by the iOS 27 SDK) showing the shared
/// Compose screens (StartView); they handle the safe area themselves, so they get the whole window.
///
/// An Android phone's invite or sync code is a QR code holding `HMSYNC:…`: the iPhone's Camera offers to open it in
/// HouseMove (the `hmsync` link type in Info.plist), and the link goes to the shared screens, which ask before
/// anything is sent. A file opened with HouseMove from another app (an update file or a backup, Info.plist's document
/// types) arrives the same way, as a file link, and the shared code takes it in (IosFiles.kt).
@main
struct HouseMoveApp: App {
    var body: some Scene {
        WindowGroup {
            StartView()
                .ignoresSafeArea()
                .onOpenURL { url in
                    MainViewControllerKt.openLink(text: url.absoluteString)
                }
        }
    }
}
