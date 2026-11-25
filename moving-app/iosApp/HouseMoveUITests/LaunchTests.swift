import XCTest

/// The first iPhone build in the simulator: the join screen, joining a move, its rooms and a room's things, search,
/// getting fresh data, leaving, the largest text size and Apple's accessibility audit. Screenshots are kept (CI copies
/// them to the ci-shots branch): join, ask, rooms, room, search, fresh, leave and the largest-text ones.
///
/// There is no Android phone in CI. Launched with `-HMDemoHost YES`, the app hosts a small sample move inside itself
/// on 127.0.0.1 (demo mode, sharedUi iosMain Demo.kt), through the same sync code and real sockets as a real join, and
/// shows its invite on the join screen; the tests read it there and type it in, as a person would type or paste an
/// Android phone's invite. Demo mode keeps its moves in folders of its own, emptied at every launch, so each test
/// starts on the join screen. Without the argument there is no demo (testShowsTheJoinScreen checks).
///
/// Compose Multiplatform hands its texts and images to iOS accessibility itself: plain texts as static texts,
/// headings with the header trait, and a button or a card as one element read as one line. So the tests look
/// elements up by their label, whatever element type iOS gives them.
final class LaunchTests: XCTestCase {
    @MainActor
    func testShowsTheJoinScreen() throws {
        let app = launch(demo: false)
        XCTAssertTrue(labelled(app, "label == 'Join a move'").waitForExistence(timeout: 30))
        XCTAssertTrue(labelled(app, "label == 'HouseMove'").exists)
        XCTAssertTrue(labelled(app, "label == 'HouseMove house mark'").exists)
        XCTAssertTrue(labelled(app, "label CONTAINS 'On this iPhone, open the Camera'").exists)
        // Demo mode needs its launch argument: without it, no sample move and no invite on the screen.
        XCTAssertFalse(labelled(app, "label == 'Demo mode'").exists)
        XCTAssertFalse(labelled(app, "label BEGINSWITH 'HMSYNC:'").exists)
        keep("join", app)
    }

    @MainActor
    func testJoinsTheDemoMoveAndShowsItsRooms() throws {
        let app = launch()
        join(app, keepQuestion: true)
        XCTAssertTrue(labelled(app, "label BEGINSWITH 'You are Alex, Decider, in Sample move'").exists)
        XCTAssertTrue(labelled(app, "label == 'Old home'").exists)
        XCTAssertTrue(labelled(app, "label CONTAINS 'Kitchen'").exists)
        keep("rooms", app)

        let living = labelled(app, "label CONTAINS 'Living room'")
        XCTAssertTrue(reveal(app, living), "the Living room row comes into view")
        living.tap()
        XCTAssertTrue(labelled(app, "label CONTAINS 'Sofa'").waitForExistence(timeout: 10))
        XCTAssertTrue(labelled(app, "label CONTAINS 'Television'").exists)
        XCTAssertTrue(labelled(app, "label CONTAINS 'Armchair'").exists)
        keep("room", app)

        labelled(app, "label == 'Back'").tap()
        XCTAssertTrue(labelled(app, "label CONTAINS 'Living room'").waitForExistence(timeout: 10))
    }

    /// The invite as the iPhone's Camera hands it over: a link, opened with HouseMove.
    @MainActor
    func testOpensAnInviteLink() throws {
        let app = launch()
        let invite = labelled(app, "label BEGINSWITH 'HMSYNC:'")
        XCTAssertTrue(invite.waitForExistence(timeout: 30), "the demo invite shows")
        let link = try XCTUnwrap(URL(string: invite.label), "the invite is a link")
        app.open(link)
        XCTAssertTrue(labelled(app, "label BEGINSWITH 'Join Sample move as Alex'").waitForExistence(timeout: 30))
        XCTAssertTrue(labelled(app, "label == 'Yes, join'").exists)
    }

    @MainActor
    func testSearchFindsWhatIsInABox() throws {
        let app = launch()
        join(app)
        let search = labelled(app, "label BEGINSWITH 'Search items and boxes'")
        XCTAssertTrue(reveal(app, search))
        search.tap()
        let field = textField(app, "What are you looking for?")
        XCTAssertTrue(field.waitForExistence(timeout: 10))
        field.tap()
        app.typeText("passports")
        XCTAssertTrue(labelled(app, "label CONTAINS 'Passports and chargers'").waitForExistence(timeout: 10))
        XCTAssertTrue(labelled(app, "label CONTAINS 'Suitcase B2'").exists)
        keep("search", app)
    }

    @MainActor
    func testGetsFreshDataWithTheOtherPhonesSyncCode() throws {
        let app = launch()
        join(app)
        labelled(app, "label == 'Get fresh data'").tap()
        XCTAssertTrue(labelled(app, "label BEGINSWITH 'Show this iPhone'").waitForExistence(timeout: 10))
        // In demo mode the sample move's own sync code is on this screen, as the Android phone would show it.
        let code = labelled(app, "label BEGINSWITH 'HMSYNC:'")
        XCTAssertTrue(reveal(app, code), "the demo sync code comes into view")
        let text = code.label
        let field = textField(app, "Sync code")
        XCTAssertTrue(reveal(app, field))
        field.tap()
        app.typeText(text)
        app.typeText("\n")
        let done = labelled(app, "label == 'Up to date'")
        if !done.waitForExistence(timeout: 10) {
            let sync = labelled(app, "label == 'Sync'")
            if reveal(app, sync) { sync.tap() }
        }
        XCTAssertTrue(done.waitForExistence(timeout: 30), "the sync went through")
        keep("fresh", app)
    }

    /// Syncing by itself (design/auto-sync.md): demo mode's sample move syncs by itself, as an Android phone on 2.3 does,
    /// found inside the app as Bonjour would find it on the Wi-Fi. After joining, "Get fresh data" says it is up to date
    /// with Sam's phone, and no code was typed.
    @MainActor
    func testSyncsByItselfWithNoCode() throws {
        let app = launch()
        join(app)
        labelled(app, "label == 'Get fresh data'").tap()
        let line = labelled(app, "label BEGINSWITH 'Up to date with Sam'")
        XCTAssertTrue(line.waitForExistence(timeout: 60), "synced by itself, with no code typed")
        XCTAssertTrue(labelled(app, "label BEGINSWITH 'Sync by itself on Wi-Fi'").exists, "the switch is there")
        keep("fresh-by-itself", app)
    }

    @MainActor
    func testLeavesTheMove() throws {
        let app = launch()
        join(app)
        let leave = labelled(app, "label == 'Leave this move on this iPhone'")
        XCTAssertTrue(reveal(app, leave), "the Leave button comes into view")
        leave.tap()
        let confirm = labelled(app, "label == 'Leave'")
        XCTAssertTrue(confirm.waitForExistence(timeout: 10), "asked first")
        XCTAssertTrue(labelled(app, "label == 'Keep it'").exists)
        keep("leave", app)
        confirm.tap()
        XCTAssertTrue(labelled(app, "label CONTAINS 'was taken off this iPhone'").waitForExistence(timeout: 20))
        XCTAssertTrue(labelled(app, "label == 'Join a move'").exists)
    }

    @MainActor
    func testLargestTextSize() throws {
        let app = launch(largest: true)
        XCTAssertTrue(labelled(app, "label == 'HouseMove'").waitForExistence(timeout: 30))
        keep("join-largest-text", app)
        join(app)
        keep("rooms-largest-text", app)
        // Opening a room at this size is a picture only: the room screen at the largest text is checked by the
        // shared screens' render test, and opening rooms by the tests at the normal size.
        let kitchen = labelled(app, "label BEGINSWITH 'Kitchen'")
        if reveal(app, kitchen) {
            kitchen.tap()
            if !labelled(app, "label CONTAINS 'Kettle'").waitForExistence(timeout: 10) {
                print("Note: at the largest text size the Kitchen room did not show Kettle within 10 s")
            }
        }
        keep("room-largest-text", app)
    }

    @MainActor
    func testPassesTheAccessibilityAudit() throws {
        let plain = launch(demo: false)
        XCTAssertTrue(labelled(plain, "label == 'Join a move'").waitForExistence(timeout: 30))
        try audit(plain)
        plain.terminate()

        let app = launch()
        join(app)
        try audit(app)
        let kitchen = labelled(app, "label CONTAINS 'Kitchen'")
        XCTAssertTrue(reveal(app, kitchen))
        kitchen.tap()
        XCTAssertTrue(labelled(app, "label CONTAINS 'Kettle'").waitForExistence(timeout: 10))
        try audit(app)
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    /// The app, in demo mode unless [demo] is false, at the largest text size with [largest].
    @MainActor
    private func launch(demo: Bool = true, largest: Bool = false) -> XCUIApplication {
        let app = XCUIApplication()
        if demo { app.launchArguments += ["-HMDemoHost", "YES"] }
        if largest { app.launchArguments += ["-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"] }
        app.launch()
        return app
    }

    /// Joins the demo move as a person does: reads its invite, types it into the invite field, says Yes.
    @MainActor
    private func join(_ app: XCUIApplication, keepQuestion: Bool = false) {
        let invite = labelled(app, "label BEGINSWITH 'HMSYNC:'")
        XCTAssertTrue(invite.waitForExistence(timeout: 30), "the demo invite shows")
        let code = invite.label
        let field = textField(app, "Invite")
        XCTAssertTrue(reveal(app, field), "the invite field comes into view")
        focus(app, field)
        app.typeText(code)
        app.typeText("\n")
        let question = labelled(app, "label BEGINSWITH 'Join Sample move as Alex'")
        if !question.waitForExistence(timeout: 10) {
            // Return did not send it: the Join button does.
            let button = labelled(app, "label == 'Join'")
            if reveal(app, button) { button.tap() }
        }
        XCTAssertTrue(question.waitForExistence(timeout: 10), "asked before anything is sent")
        if keepQuestion { keep("ask", app) }
        let yes = labelled(app, "label == 'Yes, join'")
        XCTAssertTrue(yes.waitForExistence(timeout: 10))
        yes.tap()
        XCTAssertTrue(labelled(app, "label == 'Sample move'").waitForExistence(timeout: 60), "the move came across")
    }

    /// The text field whose label is [label]: a text field if iOS lists it as one, else the element so labelled.
    @MainActor
    private func textField(_ app: XCUIApplication, _ label: String) -> XCUIElement {
        let field = app.textFields.firstMatch
        if field.waitForExistence(timeout: 5) { return field }
        return app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", label)).firstMatch
    }

    /// Taps [field] until it takes typing. A tap while the list is still gliding from a swipe only stops the list,
    /// so it waits for the glide to end first and taps again if the field did not get the focus. The simulator may
    /// take typing without showing an on-screen keyboard, so the field's own focus is what counts.
    @MainActor
    private func focus(_ app: XCUIApplication, _ field: XCUIElement) {
        for attempt in 0..<3 {
            Thread.sleep(forTimeInterval: attempt == 0 ? 1.0 : 1.5)
            if attempt < 2 { field.tap() } else { field.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap() }
            Thread.sleep(forTimeInterval: 0.5)
            let focused = (field.value(forKey: "hasKeyboardFocus") as? Bool) ?? false
            if focused || app.keyboards.firstMatch.exists { return }
            _ = reveal(app, field)
        }
        // Not fatal by itself: whether the invite went in is checked next, by the question it brings up.
        print("Note: the invite field did not report focus after three taps")
    }

    /// Swipes the screen up until [element] can be tapped; false when it never can.
    @MainActor
    private func reveal(_ app: XCUIApplication, _ element: XCUIElement) -> Bool {
        var swipes = 0
        while !(element.exists && element.isHittable) && swipes < 15 {
            app.swipeUp(velocity: .slow)
            swipes += 1
        }
        return element.exists && element.isHittable
    }

    /// Apple's accessibility audit of what is on the screen. Every issue is written to the log with its element
    /// before the test fails on it.
    @MainActor
    private func audit(_ app: XCUIApplication) throws {
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
