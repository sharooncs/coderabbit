import XCTest

/// Opens the app in the simulator, checks the start screen, keeps screenshots (CI copies them to the
/// ci-shots branch) and runs Apple's accessibility audit.
final class LaunchTests: XCTestCase {
    @MainActor
    func testOpensAndShowsTheStartScreen() throws {
        let app = XCUIApplication()
        app.launch()
        XCTAssertTrue(app.staticTexts["HouseMove"].waitForExistence(timeout: 20))
        XCTAssertTrue(app.staticTexts.containing(NSPredicate(format: "label BEGINSWITH 'Test build'")).firstMatch.exists)
        keep("start", app)
    }

    @MainActor
    func testStartScreenAtTheLargestTextSize() throws {
        let app = XCUIApplication()
        app.launchArguments += ["-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"]
        app.launch()
        XCTAssertTrue(app.staticTexts["HouseMove"].waitForExistence(timeout: 20))
        keep("start-largest-text", app)
    }

    @MainActor
    func testPassesTheAccessibilityAudit() throws {
        let app = XCUIApplication()
        app.launch()
        XCTAssertTrue(app.staticTexts["HouseMove"].waitForExistence(timeout: 20))
        try app.performAccessibilityAudit()
    }

    @MainActor
    private func keep(_ name: String, _ app: XCUIApplication) {
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = name
        shot.lifetime = .keepAlways
        add(shot)
    }
}
