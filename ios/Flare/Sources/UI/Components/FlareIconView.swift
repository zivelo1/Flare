import SwiftUI

/// A programmatically drawn flame icon for Flare branding.
struct FlareIconView: View {
    var size: CGFloat = 64

    var body: some View {
        FlameShape()
            .fill(
                LinearGradient(
                    colors: [Constants.flareOrange, Constants.flareOrangeLight],
                    startPoint: .bottom,
                    endPoint: .top
                )
            )
            .frame(width: size, height: size)
    }
}

/// A SwiftUI Shape that draws a stylized flame.
struct FlameShape: Shape {
    func path(in rect: CGRect) -> Path {
        var path = Path()
        let w = rect.width
        let h = rect.height

        // Outer flame silhouette
        path.move(to: CGPoint(x: w * 0.50, y: h * 0.02))

        // Right side curve up
        path.addCurve(
            to: CGPoint(x: w * 0.82, y: h * 0.38),
            control1: CGPoint(x: w * 0.52, y: h * 0.12),
            control2: CGPoint(x: w * 0.80, y: h * 0.20)
        )

        // Right bulge outward
        path.addCurve(
            to: CGPoint(x: w * 0.72, y: h * 0.78),
            control1: CGPoint(x: w * 0.88, y: h * 0.54),
            control2: CGPoint(x: w * 0.85, y: h * 0.68)
        )

        // Bottom right to center bottom
        path.addCurve(
            to: CGPoint(x: w * 0.50, y: h * 0.98),
            control1: CGPoint(x: w * 0.64, y: h * 0.88),
            control2: CGPoint(x: w * 0.56, y: h * 0.96)
        )

        // Bottom left
        path.addCurve(
            to: CGPoint(x: w * 0.28, y: h * 0.78),
            control1: CGPoint(x: w * 0.44, y: h * 0.96),
            control2: CGPoint(x: w * 0.36, y: h * 0.88)
        )

        // Left bulge
        path.addCurve(
            to: CGPoint(x: w * 0.18, y: h * 0.38),
            control1: CGPoint(x: w * 0.15, y: h * 0.68),
            control2: CGPoint(x: w * 0.12, y: h * 0.54)
        )

        // Left side back up to top
        path.addCurve(
            to: CGPoint(x: w * 0.50, y: h * 0.02),
            control1: CGPoint(x: w * 0.20, y: h * 0.20),
            control2: CGPoint(x: w * 0.48, y: h * 0.12)
        )

        path.closeSubpath()

        return path
    }
}
