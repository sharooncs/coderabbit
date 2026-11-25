// HouseMove's App Store Connect helper: TestFlight groups, testers, builds and Beta App Review from the
// command line, so the owner doesn't have to use the website for them.
//
// Runs on the owner's Mac with an App Store Connect API key that never leaves the Mac:
//   ~/.appstoreconnect/private_keys/AuthKey_<KEY ID>.p8   (downloaded once from App Store Connect)
//   ~/.appstoreconnect/housemove.conf                     two lines: key_id=<KEY ID> and issuer_id=<ISSUER ID>
// (or the environment variables ASC_KEY_ID and ASC_ISSUER_ID). Nothing here prints or copies the key.
//
//   tools/ios.sh asc status                     the app, its latest builds and their review state, the tester groups
//   tools/ios.sh asc test-info                  the beta description, feedback email and review contact as saved
//   tools/ios.sh asc create-group NAME          a new external tester group
//   tools/ios.sh asc add-tester GROUP EMAIL [FIRST LAST]
//   tools/ios.sh asc add-build GROUP BUILD      BUILD is a build number (202611240946) or "latest"
//   tools/ios.sh asc what-to-test BUILD TEXT    the "What to Test" note testers see for that build
//   tools/ios.sh asc submit BUILD               send the build to Beta App Review (needed once per version for external groups)
//   tools/ios.sh asc public-link GROUP on|off   a link anyone can use to join the group
import CryptoKit
import Foundation

struct Fail: Error, CustomStringConvertible { let description: String }
typealias JSON = [String: Any]

let bundleId = "app.housemove.ios"
let api = "https://api.appstoreconnect.apple.com/v1/"

// MARK: The key and the token

func credentials() throws -> (keyId: String, issuer: String, keyPath: String) {
    let env = ProcessInfo.processInfo.environment
    var keyId = env["ASC_KEY_ID"]
    var issuer = env["ASC_ISSUER_ID"]
    let home = FileManager.default.homeDirectoryForCurrentUser.path
    let confPath = home + "/.appstoreconnect/housemove.conf"
    if keyId == nil || issuer == nil, let text = try? String(contentsOfFile: confPath, encoding: .utf8) {
        for line in text.split(whereSeparator: \.isNewline) {
            let parts = line.split(separator: "=", maxSplits: 1).map { $0.trimmingCharacters(in: .whitespaces) }
            guard parts.count == 2 else { continue }
            if parts[0] == "key_id", keyId == nil { keyId = parts[1] }
            if parts[0] == "issuer_id", issuer == nil { issuer = parts[1] }
        }
    }
    guard let k = keyId, !k.isEmpty, let i = issuer, !i.isEmpty else {
        throw Fail(description: "no key ID or issuer ID: put key_id=… and issuer_id=… in \(confPath)")
    }
    let keyPath = home + "/.appstoreconnect/private_keys/AuthKey_\(k).p8"
    guard FileManager.default.fileExists(atPath: keyPath) else {
        throw Fail(description: "the key file is not at \(keyPath)")
    }
    return (k, i, keyPath)
}

func base64url(_ data: Data) -> String {
    data.base64EncodedString()
        .replacingOccurrences(of: "+", with: "-")
        .replacingOccurrences(of: "/", with: "_")
        .replacingOccurrences(of: "=", with: "")
}

/// A JSON Web Token signed with the key (ES256), valid for 15 minutes, as App Store Connect asks for.
func makeToken() throws -> String {
    let c = try credentials()
    let pem = try String(contentsOfFile: c.keyPath, encoding: .utf8)
    let key = try P256.Signing.PrivateKey(pemRepresentation: pem)
    let now = Int(Date().timeIntervalSince1970)
    let header = try JSONSerialization.data(withJSONObject: ["alg": "ES256", "kid": c.keyId, "typ": "JWT"])
    let claims = try JSONSerialization.data(withJSONObject: [
        "iss": c.issuer, "iat": now, "exp": now + 15 * 60, "aud": "appstoreconnect-v1",
    ] as [String: Any])
    let signingInput = base64url(header) + "." + base64url(claims)
    let signature = try key.signature(for: Data(signingInput.utf8))
    return signingInput + "." + base64url(signature.rawRepresentation)
}

// MARK: Calling the API

/// Square brackets in query names (filter[app]) must be percent-encoded.
func path(_ p: String) -> String { p.replacingOccurrences(of: "[", with: "%5B").replacingOccurrences(of: "]", with: "%5D") }

func call(_ token: String, _ method: String, _ p: String, _ body: JSON? = nil) async throws -> JSON {
    guard let url = URL(string: api + path(p)) else { throw Fail(description: "bad address \(p)") }
    var request = URLRequest(url: url)
    request.httpMethod = method
    request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
    if let body {
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
    }
    let (data, response) = try await URLSession.shared.data(for: request)
    let status = (response as? HTTPURLResponse)?.statusCode ?? 0
    let json = (try? JSONSerialization.jsonObject(with: data)) as? JSON ?? [:]
    guard (200..<300).contains(status) else {
        let errors = (json["errors"] as? [JSON] ?? [])
            .map { "\($0["title"] as? String ?? ""): \($0["detail"] as? String ?? "")" }
            .joined(separator: "; ")
        throw Fail(description: "\(method) \(p) answered HTTP \(status). \(errors)")
    }
    return json
}

func items(_ json: JSON) -> [JSON] { json["data"] as? [JSON] ?? [] }
func attrs(_ item: JSON) -> JSON { item["attributes"] as? JSON ?? [:] }
func id(_ item: JSON) -> String { item["id"] as? String ?? "" }
func text(_ value: Any?) -> String { value.map { "\($0)" } ?? "-" }

func appId(_ t: String) async throws -> String {
    guard let app = items(try await call(t, "GET", "apps?filter[bundleId]=\(bundleId)")).first else {
        throw Fail(description: "no app with bundle ID \(bundleId) in App Store Connect")
    }
    return id(app)
}

func group(_ t: String, _ app: String, named name: String) async throws -> JSON {
    let groups = items(try await call(t, "GET", "apps/\(app)/betaGroups?limit=50"))
    guard let g = groups.first(where: { (attrs($0)["name"] as? String)?.lowercased() == name.lowercased() }) else {
        let names = groups.compactMap { attrs($0)["name"] as? String }.joined(separator: ", ")
        throw Fail(description: "no tester group called \"\(name)\" (there are: \(names))")
    }
    return g
}

func build(_ t: String, _ app: String, _ number: String) async throws -> JSON {
    let query = number == "latest"
        ? "builds?filter[app]=\(app)&sort=-uploadedDate&limit=1"
        : "builds?filter[app]=\(app)&filter[version]=\(number)&limit=1"
    guard let b = items(try await call(t, "GET", query)).first else {
        throw Fail(description: "no build \(number) for \(bundleId)")
    }
    return b
}

// MARK: Commands

func status(_ t: String) async throws {
    let app = try await appId(t)
    print("App \(bundleId) (Apple ID \(app))")
    let builds = try await call(t, "GET",
        "builds?filter[app]=\(app)&sort=-uploadedDate&limit=5&include=preReleaseVersion,buildBetaDetail")
    let included = builds["included"] as? [JSON] ?? []
    func related(_ b: JSON, _ name: String) -> JSON {
        let rel = (b["relationships"] as? JSON)?[name] as? JSON
        let relId = (rel?["data"] as? JSON)?["id"] as? String
        return included.first { id($0) == relId }.map(attrs) ?? [:]
    }
    print("\nLatest builds:")
    for b in items(builds) {
        let a = attrs(b)
        let detail = related(b, "buildBetaDetail")
        let review = items(try await call(t, "GET", "betaAppReviewSubmissions?filter[build]=\(id(b))")).first.map(attrs)
        print("  \(text(related(b, "preReleaseVersion")["version"])) (\(text(a["version"]))): " +
              "processing \(text(a["processingState"])), internal \(text(detail["internalBuildState"])), " +
              "external \(text(detail["externalBuildState"])), Beta App Review \(text(review?["betaReviewState"])), " +
              "uploaded \(text(a["uploadedDate"]))")
    }
    print("\nTester groups:")
    for g in items(try await call(t, "GET", "apps/\(app)/betaGroups?limit=50")) {
        let a = attrs(g)
        let kind = (a["isInternalGroup"] as? Bool ?? false) ? "internal" : "external"
        let testers = items(try await call(t, "GET", "betaGroups/\(id(g))/betaTesters?limit=200")).count
        let link = (a["publicLinkEnabled"] as? Bool ?? false) ? ", public link \(text(a["publicLink"]))" : ""
        print("  \(text(a["name"])) (\(kind)): \(testers) tester(s)\(link)")
    }
}

func testInfo(_ t: String) async throws {
    let app = try await appId(t)
    for l in items(try await call(t, "GET", "betaAppLocalizations?filter[app]=\(app)")) {
        let a = attrs(l)
        print("Beta description (\(text(a["locale"]))): \(text(a["description"]))")
        print("Feedback email: \(text(a["feedbackEmail"]))")
    }
    let detail = attrs(try await call(t, "GET", "apps/\(app)/betaAppReviewDetail")["data"] as? JSON ?? [:])
    print("Review contact: \(text(detail["contactFirstName"])) \(text(detail["contactLastName"])), " +
          "\(text(detail["contactEmail"])), phone \((detail["contactPhone"] as? String).map { _ in "set" } ?? "missing")")
    print("Sign-in required: \(text(detail["demoAccountRequired"]))")
    print("Review notes: \(text(detail["notes"]))")
}

func run(_ args: [String]) async throws {
    guard let command = args.first else {
        throw Fail(description: "say what to do: status, test-info, create-group, add-tester, add-build, what-to-test, submit, public-link")
    }
    let t = try makeToken()
    let rest = Array(args.dropFirst())
    func need(_ n: Int, _ usage: String) throws { if rest.count < n { throw Fail(description: "usage: \(command) \(usage)") } }
    switch command {
    case "status":
        try await status(t)
    case "test-info":
        try await testInfo(t)
    case "create-group":
        try need(1, "NAME")
        let app = try await appId(t)
        let made = try await call(t, "POST", "betaGroups", ["data": [
            "type": "betaGroups", "attributes": ["name": rest[0]],
            "relationships": ["app": ["data": ["type": "apps", "id": app]]],
        ]])
        print("Made the group \"\(rest[0])\" (\(text((made["data"] as? JSON)?["id"])))")
    case "add-tester":
        try need(2, "GROUP EMAIL [FIRST LAST]")
        let app = try await appId(t)
        let g = try await group(t, app, named: rest[0])
        let email = rest[1]
        var attributes: JSON = ["email": email]
        if rest.count >= 3 { attributes["firstName"] = rest[2] }
        if rest.count >= 4 { attributes["lastName"] = rest[3] }
        if let existing = items(try await call(t, "GET", "betaTesters?filter[email]=\(email)")).first {
            _ = try await call(t, "POST", "betaGroups/\(id(g))/relationships/betaTesters",
                               ["data": [["type": "betaTesters", "id": id(existing)]]])
        } else {
            _ = try await call(t, "POST", "betaTesters", ["data": [
                "type": "betaTesters", "attributes": attributes,
                "relationships": ["betaGroups": ["data": [["type": "betaGroups", "id": id(g)]]]],
            ]])
        }
        print("Added \(email) to \"\(rest[0])\". They get the invitation once the group has a build they may install.")
    case "add-build":
        try need(2, "GROUP BUILD")
        let app = try await appId(t)
        let g = try await group(t, app, named: rest[0])
        let b = try await build(t, app, rest[1])
        _ = try await call(t, "POST", "betaGroups/\(id(g))/relationships/builds",
                           ["data": [["type": "builds", "id": id(b)]]])
        print("Build \(text(attrs(b)["version"])) is in \"\(rest[0])\".")
    case "what-to-test":
        try need(2, "BUILD TEXT")
        let app = try await appId(t)
        let b = try await build(t, app, rest[0])
        let note = rest.dropFirst().joined(separator: " ")
        if let existing = items(try await call(t, "GET", "builds/\(id(b))/betaBuildLocalizations")).first {
            _ = try await call(t, "PATCH", "betaBuildLocalizations/\(id(existing))", ["data": [
                "type": "betaBuildLocalizations", "id": id(existing), "attributes": ["whatsNew": note],
            ]])
        } else {
            _ = try await call(t, "POST", "betaBuildLocalizations", ["data": [
                "type": "betaBuildLocalizations", "attributes": ["whatsNew": note, "locale": "en-US"],
                "relationships": ["build": ["data": ["type": "builds", "id": id(b)]]],
            ]])
        }
        print("Saved What to Test for build \(text(attrs(b)["version"])).")
    case "submit":
        try need(1, "BUILD")
        let app = try await appId(t)
        let b = try await build(t, app, rest[0])
        let made = try await call(t, "POST", "betaAppReviewSubmissions", ["data": [
            "type": "betaAppReviewSubmissions",
            "relationships": ["build": ["data": ["type": "builds", "id": id(b)]]],
        ]])
        // Apple answers at once for a new build of a version it has already approved; otherwise it takes a while.
        let state = attrs(made["data"] as? JSON ?? [:])["betaReviewState"] as? String
        switch state {
        case "APPROVED":
            print("Build \(text(attrs(b)["version"])) is approved for external testers already.")
        case "REJECTED":
            print("Build \(text(attrs(b)["version"])) was turned down by Beta App Review; see App Store Connect for why.")
        default:
            print("Build \(text(attrs(b)["version"])) is waiting for Beta App Review (\(state ?? "submitted")); " +
                  "a first build of a version usually takes about a day. `asc status` shows when it changes.")
        }
    case "public-link":
        try need(2, "GROUP on|off")
        let app = try await appId(t)
        let g = try await group(t, app, named: rest[0])
        let on = rest[1] == "on"
        let changed = try await call(t, "PATCH", "betaGroups/\(id(g))", ["data": [
            "type": "betaGroups", "id": id(g), "attributes": ["publicLinkEnabled": on],
        ]])
        let a = attrs(changed["data"] as? JSON ?? [:])
        print(on ? "Public link for \"\(rest[0])\": \(text(a["publicLink"]))" : "Public link for \"\(rest[0])\" is off.")
    default:
        throw Fail(description: "unknown command \(command)")
    }
}

do {
    try await run(Array(CommandLine.arguments.dropFirst()))
} catch {
    FileHandle.standardError.write(Data("asc: \(error)\n".utf8))
    exit(1)
}
