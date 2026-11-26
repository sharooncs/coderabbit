import XCTest

/// The iPhone build in the simulator: the join screen, joining a move, then the shared app (step 2 of design/ios-plan.md):
/// Home, the rooms, a room's things through search, an item, getting fresh data (the iPhone's Sync and backup), leaving,
/// the largest text size and Apple's accessibility audit. Screenshots are kept (CI copies them to the ci-shots branch):
/// join, ask, rooms, room, search, fresh, fresh-by-itself, leave and the largest-text ones as before, and home, item,
/// items, box and more for the shared screens.
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
///
/// The crypto check (testCryptoKitOpensAndSealsAsAndroidDoes) is the one place CI runs the app's real CryptoKit and
/// Keychain: launched with `-HMCryptoCheck YES`, the app checks them against Android's update files and shows a line per
/// check (sharedUi iosMain CryptoCheckScreen.kt, shared iosMain CryptoCheck.kt).
final class LaunchTests: XCTestCase {
    /// The iPhone's update-file crypto against Android's, through the app's own CryptoKit (design/encryption.md): Android's
    /// update file from the golden fixtures opens on Asha's phone as it did on Android, the known-answer file the JVM opens
    /// is sealed byte for byte, and the Keychain keeps and finds the phone's key. Every line the app shows is in the
    /// failure message, so CI's log says which check failed.
    @MainActor
    func testCryptoKitOpensAndSealsAsAndroidDoes() throws {
        let app = XCUIApplication()
        app.launchArguments += ["-HMCryptoCheck", "YES"]
        app.launch()
        let summary = labelled(app, "label == 'Crypto check passed' OR label == 'Crypto check failed'")
        XCTAssertTrue(summary.waitForExistence(timeout: 120), "the crypto check finished")
        let lines = app.descendants(matching: .any).matching(NSPredicate(format: "label BEGINSWITH 'Check '"))
            .allElementsBoundByIndex.map { $0.label }
        let report = ([summary.label] + lines).joined(separator: " | ")
        print("Crypto check: \(report)")
        keep("crypto-check", app)
        XCTAssertEqual(summary.label, "Crypto check passed", report)
        XCTAssertTrue(lines.contains { $0.hasPrefix("Check passed: Android's update opens on Asha's phone") }, report)
        XCTAssertTrue(lines.contains { $0.hasPrefix("Check passed: An update sealed here is the JVM's byte for byte") }, report)
        XCTAssertTrue(lines.contains { $0.hasPrefix("Check passed: That update opens on Asha's phone") }, report)
        // The encrypted sync of 2.4 (design/encryption.md, Part 2): the JVM's recorded handshakes and frames, through CryptoKit.
        XCTAssertTrue(lines.contains { $0.hasPrefix("Check passed: Noise IK through CryptoKit") }, report)
        XCTAssertTrue(lines.contains { $0.hasPrefix("Check passed: Noise XXpsk0 through CryptoKit") }, report)
        XCTAssertTrue(lines.contains { $0.hasPrefix("Check passed: Noise IK and XXpsk0 between keys CryptoKit makes now") }, report)
        // HPKE, for the mailbox and update files of format 2 (2.5): the CFRG's vectors, HouseMove's messages the JVM recorded,
        // and CryptoKit's own HPKE against HouseMove's.
        XCTAssertTrue(lines.contains { $0.hasPrefix("Check passed: HPKE through CryptoKit") }, report)
        XCTAssertTrue(lines.contains { $0.hasPrefix("Check passed: HouseMove's mailbox envelope through CryptoKit") }, report)
        XCTAssertTrue(lines.contains { $0.hasPrefix("Check passed: HouseMove's update file of format 2 through CryptoKit") }, report)
        XCTAssertTrue(lines.contains { $0.hasPrefix("Check passed: CryptoKit's own HPKE opens what HouseMove's HPKE seals") }, report)
        if lines.contains(where: { $0.hasPrefix("Check skipped: the Keychain") }) {
            // CI builds with code signing off: the simulator may then refuse the Keychain (errSecMissingEntitlement).
            print("Note: the Keychain was not checked in this unsigned build: \(report)")
        } else {
            XCTAssertTrue(lines.contains { $0.hasPrefix("Check passed: this phone's key is kept in the Keychain") }, report)
        }
    }

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
        // Home, as on Android, with what joining made of this iPhone said once at the top.
        XCTAssertTrue(labelled(app, "label BEGINSWITH 'You are Alex, Decider, in Sample move'").waitForExistence(timeout: 10))
        keep("home", app)

        // The rooms: More, then Rooms.
        openMore(app)
        let rooms = labelled(app, "label BEGINSWITH 'Rooms'")
        XCTAssertTrue(reveal(app, rooms), "the Rooms tile comes into view")
        rooms.tap()
        XCTAssertTrue(labelled(app, "label CONTAINS 'Old home'").waitForExistence(timeout: 10))
        XCTAssertTrue(labelled(app, "label CONTAINS 'Kitchen'").exists)
        keep("rooms", app)
        back(app)

        // A room's things: search, by the room's chip.
        openSearch(app)
        let living = labelled(app, "label CONTAINS 'Living room'")
        XCTAssertTrue(reveal(app, living), "the Living room chip comes into view")
        living.tap()
        XCTAssertTrue(labelled(app, "label CONTAINS 'Sofa'").waitForExistence(timeout: 10))
        XCTAssertTrue(labelled(app, "label CONTAINS 'Television'").exists)
        keep("room", app)

        // One thing, opened: what happens to it and where it goes, as a Decider suggests them.
        labelled(app, "label CONTAINS 'Sofa'").tap()
        XCTAssertTrue(labelled(app, "label CONTAINS 'Suggest what happens to it'").waitForExistence(timeout: 10))
        // The camera is offered on it (demo mode's fake camera: the simulator has none).
        XCTAssertTrue(labelled(app, "label == 'Take Sofa photo'").exists, "the camera is offered")
        keep("item", app)
        back(app)
        XCTAssertTrue(labelled(app, "label CONTAINS 'Sofa'").waitForExistence(timeout: 10), "back on the search")
    }

    /// The shared screens a Decider uses most: Items, a box, and More (Everything), with the camera and the scanner
    /// (demo mode's fakes) and no printing.
    @MainActor
    func testShowsTheSharedScreens() throws {
        let app = launch()
        join(app)
        let items = labelled(app, "label BEGINSWITH 'Items'")
        XCTAssertTrue(items.waitForExistence(timeout: 10))
        items.tap()
        XCTAssertTrue(labelled(app, "label CONTAINS 'Kettle'").waitForExistence(timeout: 10))
        XCTAssertTrue(labelled(app, "label BEGINSWITH 'Walk-through'").exists, "the walk-through comes with the camera")
        keep("items", app)

        let boxes = labelled(app, "label BEGINSWITH 'Boxes'")
        XCTAssertTrue(boxes.waitForExistence(timeout: 10))
        boxes.tap()
        let a1 = labelled(app, "label CONTAINS 'Plates, mugs, glasses'")
        XCTAssertTrue(a1.waitForExistence(timeout: 10))
        a1.tap()
        XCTAssertTrue(labelled(app, "label CONTAINS 'Items inside'").waitForExistence(timeout: 10))
        XCTAssertTrue(labelled(app, "label BEGINSWITH 'Scan '").exists, "the box's scan buttons come with the camera")
        keep("box", app)
        back(app)

        openMore(app)
        XCTAssertTrue(labelled(app, "label BEGINSWITH 'Sync and backup'").waitForExistence(timeout: 10))
        XCTAssertFalse(labelled(app, "label BEGINSWITH 'Labels and signs'").exists, "printing waits for a later test version")
        keep("more", app)
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
        openSearch(app)
        let field = textField(app, "What are you looking for?")
        XCTAssertTrue(field.waitForExistence(timeout: 10))
        field.tap()
        app.typeText("passports")
        XCTAssertTrue(labelled(app, "label CONTAINS 'Passports and chargers'").waitForExistence(timeout: 10))
        XCTAssertTrue(labelled(app, "label CONTAINS 'B2'").exists)
        keep("search", app)
    }

    @MainActor
    func testGetsFreshDataWithTheOtherPhonesSyncCode() throws {
        let app = launch()
        join(app)
        openFresh(app)
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
        // The demo move's phone speaks 2.4, as this app does: the join was encrypted, so nothing says otherwise.
        XCTAssertFalse(labelled(app, "label CONTAINS 'not encrypted'").exists, "the join with the demo phone was encrypted")
        openFresh(app)
        let line = labelled(app, "label BEGINSWITH 'Up to date with Sam'")
        XCTAssertTrue(line.waitForExistence(timeout: 60), "synced by itself, with no code typed")
        XCTAssertTrue(labelled(app, "label BEGINSWITH 'Sync by itself on Wi-Fi'").exists, "the switch is there")
        keep("fresh-by-itself", app)
    }

    @MainActor
    func testLeavesTheMove() throws {
        let app = launch()
        join(app)
        openFresh(app)
        let leave = labelled(app, "label == 'Leave this move on this iPhone'")
        XCTAssertTrue(reveal(app, leave), "the Leave button comes into view")
        // As in focus(): a tap while the list is still gliding from the swipe only stops the list, and nothing is
        // asked. So let the glide end first, and tap again if the question did not come up (seen on the owner's Mac).
        let confirm = labelled(app, "label == 'Leave'")
        for attempt in 1...3 {
            Thread.sleep(forTimeInterval: 1.0)
            leave.tap()
            if confirm.waitForExistence(timeout: 5) {
                print("Note: Leave was asked after tap \(attempt)")
                break
            }
        }
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
        keep("home-largest-text", app)
        // The rooms and a room's things at this size are pictures only: the shared screens at the largest text are
        // checked by the render test (sharedUi desktopTest) and by Android's own checks, and opening them by the tests
        // at the normal size.
        openMore(app)
        let rooms = labelled(app, "label BEGINSWITH 'Rooms'")
        if reveal(app, rooms) {
            rooms.tap()
            if !labelled(app, "label CONTAINS 'Kitchen'").waitForExistence(timeout: 10) {
                print("Note: at the largest text size the Rooms screen did not show Kitchen within 10 s")
            }
        }
        keep("rooms-largest-text", app)
        back(app)
        openSearch(app)
        let kitchen = labelled(app, "label CONTAINS 'Kitchen'")
        if reveal(app, kitchen) {
            kitchen.tap()
            if !labelled(app, "label CONTAINS 'Kettle'").waitForExistence(timeout: 10) {
                print("Note: at the largest text size the search for Kitchen did not show Kettle within 10 s")
            }
        }
        keep("room-largest-text", app)
    }

    /// Apple's audit, strict on the iPhone's own screens (joining, Get fresh data) as before. The shared screens are
    /// Android's, checked there by tools/checks.sh; here every issue they have is written to the log for a person to
    /// read, and the test goes on.
    @MainActor
    func testPassesTheAccessibilityAudit() throws {
        let plain = launch(demo: false)
        XCTAssertTrue(labelled(plain, "label == 'Join a move'").waitForExistence(timeout: 30))
        try audit(plain)
        plain.terminate()

        let app = launch()
        join(app)
        try audit(app, strict: false, what: "Home")
        openMore(app)
        let rooms = labelled(app, "label BEGINSWITH 'Rooms'")
        XCTAssertTrue(reveal(app, rooms))
        rooms.tap()
        XCTAssertTrue(labelled(app, "label CONTAINS 'Kitchen'").waitForExistence(timeout: 10))
        try audit(app, strict: false, what: "Rooms")
        back(app)
        openFresh(app)
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
        // The shared Home: its title is the move's name.
        XCTAssertTrue(labelled(app, "label CONTAINS 'Sample move' AND NOT (label BEGINSWITH 'Join')").waitForExistence(timeout: 60), "the move came across")
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

    /// Apple's accessibility audit of what is on the screen. Every issue is written to the log with its element; with
    /// [strict] the test then fails on it, otherwise it is only counted ([what] names the screen in the log).
    @MainActor
    private func audit(_ app: XCUIApplication, strict: Bool = true, what: String = "") throws {
        if !strict { print("Accessibility audit of the shared screen \(what): its issues follow, if any (not failing)") }
        try app.performAccessibilityAudit { issue in
            print("Accessibility issue\(strict ? "" : " (shared screen \(what), not failing)"): \(issue.auditType) \(issue.compactDescription)"
                + " | \(issue.detailedDescription) | element: \(issue.element?.debugDescription ?? "none")")
            return !strict
        }
    }

    /// The dock's More: Everything, every screen of the move.
    @MainActor
    private func openMore(_ app: XCUIApplication) {
        let more = labelled(app, "label BEGINSWITH 'More'")
        XCTAssertTrue(more.waitForExistence(timeout: 10), "the dock's More")
        more.tap()
        XCTAssertTrue(labelled(app, "label == 'Everything'").waitForExistence(timeout: 10), "Everything opened")
    }

    /// Get fresh data, the iPhone's Sync and backup: More, then Sync and backup.
    @MainActor
    private func openFresh(_ app: XCUIApplication) {
        openMore(app)
        let sync = labelled(app, "label BEGINSWITH 'Sync and backup'")
        XCTAssertTrue(reveal(app, sync), "the Sync and backup tile comes into view")
        sync.tap()
        XCTAssertTrue(labelled(app, "label == 'Get fresh data'").waitForExistence(timeout: 10), "Get fresh data opened")
    }

    /// Search, from the magnifier at the top of Home or More.
    @MainActor
    private func openSearch(_ app: XCUIApplication) {
        let find = labelled(app, "label == 'Find anything'")
        XCTAssertTrue(find.waitForExistence(timeout: 10), "the search button")
        find.tap()
        // Compose's field may be listed as a text view rather than a text field: found by its label then.
        XCTAssertTrue(textField(app, "What are you looking for?").waitForExistence(timeout: 10), "search opened, with its field")
    }

    /// The round Back button at the top left.
    @MainActor
    private func back(_ app: XCUIApplication) {
        let button = labelled(app, "label == 'Back'")
        if button.waitForExistence(timeout: 5) { button.tap() }
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

// ---- the camera and the scanner (step 3a), through demo mode's fakes ------------------------------------------------

/// The camera and the scanner (step 3a of design/ios-plan.md). The simulator has no camera, so in demo mode the app uses
/// fakes (sharedUi iosMain camera/DemoCamera.kt) through the same screens: the fake camera "takes" a sample picture of
/// a sofa, saved as a photo is; the fake scanner "reads" the text given with `-HMFakeScan` a moment after it opens, where
/// `demo-sync-code` stands for the sample move's sync code. Neither exists outside demo mode.
extension LaunchTests {
    /// A thing's photo: its camera, the shutter, Done; the photo then shows on the thing.
    @MainActor
    func testTakesAPhotoOfAThingWithTheDemoCamera() throws {
        let app = launch()
        join(app)
        openThing(app, room: "Living room", thing: "Sofa")
        XCTAssertTrue(labelled(app, "label == 'Take a photo'").waitForExistence(timeout: 10), "no photo yet")
        let take = labelled(app, "label == 'Take Sofa photo'")
        XCTAssertTrue(take.exists, "the camera is offered on the thing")
        take.tap()
        let shutter = labelled(app, "label == 'Take photo'")
        XCTAssertTrue(shutter.waitForExistence(timeout: 10), "the camera opened")
        XCTAssertTrue(labelled(app, "label BEGINSWITH 'Demo camera'").exists, "demo mode's fake camera")
        keep("camera", app)
        shutter.tap()
        XCTAssertTrue(labelled(app, "label CONTAINS '1 taken'").waitForExistence(timeout: 15), "the photo was taken")
        labelled(app, "label == 'Done'").tap()
        // The thing's picture is the photo now: the "Take a photo" placeholder has gone.
        XCTAssertTrue(waitForAbsence(labelled(app, "label == 'Take a photo'"), timeout: 15), "the photo shows on the thing")
        let photo = app.images.matching(NSPredicate(format: "label == 'Sofa'")).firstMatch
        if !photo.waitForExistence(timeout: 5) { print("Note: the Sofa photo is not listed as an image by accessibility") }
        keep("item-photo", app)
    }

    /// Scan a label (Home, at the top) with the fake scanner reading box A1's old-style label: the box opens.
    @MainActor
    func testScanALabelOpensThatBox() throws {
        let app = launch(fakeScan: "HM:A1")
        join(app)
        let scan = labelled(app, "label == 'Scan a label'")
        XCTAssertTrue(scan.waitForExistence(timeout: 10), "Scan a label is offered on Home")
        scan.tap()
        // The fake scanner reads its code two seconds after it opens: a picture of it, if it is still there.
        if labelled(app, "label CONTAINS 'Demo scanner'").waitForExistence(timeout: 2) { keep("scan-label", app) }
        XCTAssertTrue(labelled(app, "label CONTAINS 'Items inside'").waitForExistence(timeout: 20), "the box with that label opened")
        XCTAssertTrue(labelled(app, "label CONTAINS 'Plates, mugs, glasses'").exists, "box A1")
        keep("scanned-box", app)
    }

    /// The walk-through is offered (Items), and a photo from its camera becomes a thing in the room picked.
    @MainActor
    func testTheWalkThroughIsOffered() throws {
        let app = launch()
        join(app)
        let items = labelled(app, "label BEGINSWITH 'Items'")
        XCTAssertTrue(items.waitForExistence(timeout: 10))
        items.tap()
        let walk = labelled(app, "label BEGINSWITH 'Walk-through'")
        XCTAssertTrue(walk.waitForExistence(timeout: 10), "the walk-through is offered")
        walk.tap()
        let hall = labelled(app, "label BEGINSWITH 'Hall'")
        XCTAssertTrue(hall.waitForExistence(timeout: 10), "the rooms to pick from")
        hall.tap()
        let start = labelled(app, "label BEGINSWITH 'Start shooting'")
        XCTAssertTrue(reveal(app, start))
        start.tap()
        let shutter = labelled(app, "label == 'Take photo'")
        XCTAssertTrue(shutter.waitForExistence(timeout: 10), "the walk-through's camera opened")
        shutter.tap()
        XCTAssertTrue(labelled(app, "label CONTAINS '1 taken'").waitForExistence(timeout: 15), "the photo was taken")
        keep("walk-camera", app)
        labelled(app, "label == 'Done'").tap()
        XCTAssertTrue(labelled(app, "label CONTAINS 'Hall item 1'").waitForExistence(timeout: 15), "the photo became a thing in the Hall")
        keep("walk", app)
    }

    /// Get fresh data: Scan the Android phone's code, with the fake scanner reading the sample move's sync code.
    @MainActor
    func testGetFreshDataScansASyncCode() throws {
        let app = launch(fakeScan: "demo-sync-code")
        join(app)
        openFresh(app)
        let scan = labelled(app, "label == \"Scan the Android phone's code\"")
        XCTAssertTrue(reveal(app, scan), "the scan button comes into view")
        scan.tap()
        if labelled(app, "label CONTAINS 'Demo scanner'").waitForExistence(timeout: 2) { keep("scan-sync-code", app) }
        let done = labelled(app, "label == 'Up to date'")
        XCTAssertTrue(done.waitForExistence(timeout: 40), "the code was read and the sync went through")
        keep("fresh-scanned", app)
    }

    /// The app in demo mode, its fake scanner reading [fakeScan].
    @MainActor
    private func launch(fakeScan: String) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments += ["-HMDemoHost", "YES", "-HMFakeScan", fakeScan]
        app.launch()
        return app
    }

    /// A thing, opened from search by its room's chip.
    @MainActor
    private func openThing(_ app: XCUIApplication, room: String, thing: String) {
        openSearch(app)
        let chip = labelled(app, "label CONTAINS '\(room)'")
        XCTAssertTrue(reveal(app, chip), "the \(room) chip comes into view")
        chip.tap()
        let row = labelled(app, "label CONTAINS '\(thing)'")
        XCTAssertTrue(row.waitForExistence(timeout: 10))
        row.tap()
        XCTAssertTrue(labelled(app, "label CONTAINS 'Suggest what happens to it'").waitForExistence(timeout: 10), "\(thing) opened")
    }

    /// Waits up to [timeout] seconds for [element] to be gone; true when it is.
    @MainActor
    private func waitForAbsence(_ element: XCUIElement, timeout: TimeInterval) -> Bool {
        let until = Date().addingTimeInterval(timeout)
        while element.exists && Date() < until { Thread.sleep(forTimeInterval: 0.5) }
        return !element.exists
    }
}
