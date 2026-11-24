import SwiftUI

/// The iPhone app's entry point. One window (UIScene life cycle, required by the iOS 27 SDK) showing the shared
/// Compose screens (StartView); they handle the safe area themselves, so they get the whole window.
@main
struct HouseMoveApp: App {
    var body: some Scene {
        WindowGroup {
            StartView()
                .ignoresSafeArea()
        }
    }
}
