import HouseMoveKit
import SwiftUI
import UIKit

/// What the app shows: the shared HouseMove screens, written in Kotlin with Compose Multiplatform and linked in from
/// the HouseMoveKit framework (sharedUi; Xcode builds it with Gradle before compiling this). In this first test
/// build: joining a move that lives on an Android phone, looking at it read-only, getting fresh data and leaving it
/// (sharedUi/src/commonMain/.../ui/iphone). It is told the build's version, so the owner can tell which build is on
/// the phone. The screens keep their content out of the Dynamic Island, the home indicator and the keyboard; the app
/// gives them the whole window (HouseMoveApp ignores the safe area) so the background reaches every edge.
struct StartView: UIViewControllerRepresentable {
    /// "Test build 0.1 (202611241030)": the version and the build number from Info.plist.
    static let buildLabel: String = {
        let info = Bundle.main.infoDictionary ?? [:]
        let name = info["CFBundleShortVersionString"] as? String ?? "?"
        let build = info["CFBundleVersion"] as? String ?? "?"
        return "Test build \(name) (\(build))"
    }()

    func makeUIViewController(context: Context) -> UIViewController {
        let screen = MainViewControllerKt.MainViewController(buildLabel: Self.buildLabel)
        // The launch screen's colour, until Compose draws its first frame.
        screen.view.backgroundColor = UIColor(named: "Ground")
        return screen
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
