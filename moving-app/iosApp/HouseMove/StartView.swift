import SwiftUI

/// What the first test builds show: the HouseMove mark, a line on what this build is, and its version,
/// so the owner can tell which build is on the phone.
struct StartView: View {
    private let version: String = {
        let info = Bundle.main.infoDictionary ?? [:]
        let name = info["CFBundleShortVersionString"] as? String ?? "?"
        let build = info["CFBundleVersion"] as? String ?? "?"
        return "Test build \(name) (\(build))"
    }()

    var body: some View {
        ZStack {
            Color("Ground").ignoresSafeArea()
            ScrollView {
                VStack(spacing: 24) {
                    HouseMark()
                        .frame(width: 160, height: 160)
                        .accessibilityHidden(true)
                    Text("HouseMove")
                        .font(.custom("Outfit-SemiBold", size: 34, relativeTo: .largeTitle))
                        .foregroundStyle(Palette.text)
                        .accessibilityAddTraits(.isHeader)
                    Text("The iPhone version is being built. This test copy checks that it installs and opens on your iPhone.")
                        .font(.custom("Outfit-Regular", size: 18, relativeTo: .body))
                        .foregroundStyle(Palette.textDim)
                        .multilineTextAlignment(.center)
                        .fixedSize(horizontal: false, vertical: true)
                    Text(version)
                        .font(.custom("Outfit-Medium", size: 15, relativeTo: .footnote))
                        .foregroundStyle(Palette.mintLight)
                }
                .padding(.horizontal, 28)
                .padding(.vertical, 48)
                .frame(maxWidth: .infinity)
            }
        }
    }
}

/// Colours from the Android theme (ui/Theme.kt).
enum Palette {
    static let text = Color(red: 0xF2 / 255, green: 0xF6 / 255, blue: 0xFA / 255)
    static let textDim = Color(red: 0xC3 / 255, green: 0xCF / 255, blue: 0xDB / 255)
    static let mint = Color(red: 0x62 / 255, green: 0xE3 / 255, blue: 0xBB / 255)
    static let mintLight = Color(red: 0x8F / 255, green: 0xF0 / 255, blue: 0xD2 / 255)
    static let amber = Color(red: 0xFF / 255, green: 0xC8 / 255, blue: 0x61 / 255)
}

/// The app's mark, drawn from the Android launcher icon (res/drawable/ic_launcher_fg.xml, the visible 72 of its 108 units).
struct HouseMark: View {
    var body: some View {
        Canvas { ctx, size in
            let k = min(size.width, size.height) / 72
            func p(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: (x - 18) * k, y: (y - 18) * k) }
            ctx.fill(Path(ellipseIn: CGRect(x: 6 * k, y: 6 * k, width: 60 * k, height: 60 * k)),
                     with: .color(Palette.mint.opacity(0.2)))
            var house = Path()
            house.move(to: p(35.5, 52.5))
            house.addLine(to: p(54, 37))
            house.addLine(to: p(72.5, 52.5))
            house.addLine(to: p(72.5, 72))
            house.addLine(to: p(35.5, 72))
            house.closeSubpath()
            ctx.stroke(house, with: .color(Palette.mintLight),
                       style: StrokeStyle(lineWidth: 4.5 * k, lineCap: .round, lineJoin: .round))
            ctx.fill(Path(CGRect(origin: p(48.5, 57.5), size: CGSize(width: 11 * k, height: 11 * k))),
                     with: .color(Palette.amber))
        }
    }
}

#Preview {
    StartView()
}
