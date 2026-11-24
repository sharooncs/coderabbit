import SwiftUI

/// The iPhone app's entry point. One window (UIScene life cycle, required by the iOS 27 SDK).
/// The shared HouseMove screens (Kotlin, Compose Multiplatform) replace StartView in step 0e of design/ios-plan.md.
@main
struct HouseMoveApp: App {
    var body: some Scene {
        WindowGroup {
            StartView()
        }
    }
}
