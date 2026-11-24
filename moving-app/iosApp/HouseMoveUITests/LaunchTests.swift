import XCTest

/// Opens the app in the simulator, checks the start screen (the shared Compose screen from HouseMoveKit), keeps
/// screenshots (CI copies them to the ci-shots branch) and runs Apple's accessibility audit.
///
/// Compose Multiplatform hands its texts and images to iOS accessibility itself: plain texts as static texts,
/// headings with the header trait, images with their descriptions, and each row of the list as one element read as
/// one line. So the tests look elements up by their label, whatever element type iOS gives them.
final class LaunchTests: XCTestCase {
    @MainActor
    func testOpensAndShowsTheStartScreen() throws {
        let app = XCUIApplication()
        app.launch()
        XCTAssertTrue(labelled(app, "label == 'HouseMove'").waitForExistence(timeout: 30))
        XCTAssertTrue(labelled(app, "label BEGINSWITH 'Test build'").exists)
        XCTAssertTrue(labelled(app, "label == 'HouseMove house mark'").exists)
        keep("start", app)
    }

    @MainActor
    func testStartScreenAtTheLargestTextSize() throws {
        let app = XCUIApplication()
        app.launchArguments += ["-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"]
        app.launch()
        XCTAssertTrue(labelled(app, "label == 'HouseMove'").waitForExistence(timeout: 30))
        keep("start-largest-text", app)
    }

    /// Scrolls the list to its middle (the dining room), past about a hundred rows.
    @MainActor
    func testScrollsToTheMiddleOfTheList() throws {
        let app = XCUIApplication()
        app.launch()
        XCTAssertTrue(labelled(app, "label == 'HouseMove'").waitForExistence(timeout: 30))
        let row = labelled(app, "label BEGINSWITH 'Sideboard, Dining room'")
        // The hall's rows come after the dining room's: if they show, the list went past the row, so go back.
        let past = labelled(app, "label CONTAINS ', Hall. '")
        var swipes = 0
        while !(row.exists && row.isHittable) && swipes < 60 {
            if past.exists { app.swipeDown(velocity: .slow) } else { app.swipeUp(velocity: .slow) }
            swipes += 1
        }
        XCTAssertTrue(row.isHittable, "the Sideboard row did not come into view after \(swipes) swipes")
        keep("list-middle", app)
    }

    @MainActor
    func testPassesTheAccessibilityAudit() throws {
        let app = XCUIApplication()
        app.launch()
        XCTAssertTrue(labelled(app, "label == 'HouseMove'").waitForExistence(timeout: 30))
        // Every issue is written to the log with its element before the test fails on it.
        try app.performAccessibilityAudit { issue in
            print("Accessibility issue: \(issue.auditType) \(issue.compactDescription) | \(issue.detailedDescription)"
                + " | element: \(issue.element?.debugDescription ?? "none")")
            return false
        }
    }

    /// The first element, of any type, whose label matches [predicate].
    @MainActor
    private func labelled(_ app: XCUIApplication, _ predicate: String) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: predicate)).firstMatch
    }

    @MainActor
    private func keep(_ name: String, _ app: XCUIApplication) {
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = name
        shot.lifetime = .keepAlways
        add(shot)
    }
}
