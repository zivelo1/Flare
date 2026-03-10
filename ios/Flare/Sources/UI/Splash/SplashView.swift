import SwiftUI

struct SplashView: View {
    @Binding var isFinished: Bool
    @State private var iconOpacity: Double = 0
    @State private var iconScale: CGFloat = 0.8
    @State private var textOpacity: Double = 0

    var body: some View {
        ZStack {
            LinearGradient(
                colors: [Constants.flareOrange, Constants.flareOrangeLight],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
            .ignoresSafeArea()

            VStack(spacing: 20) {
                FlareIconView(size: 96)
                    .shadow(color: Color(.sRGBLinear, white: 0, opacity: 0.25), radius: 12, y: 4)
                    .scaleEffect(iconScale)
                    .opacity(iconOpacity)

                Text("Flare")
                    .font(.system(size: 42, weight: .bold, design: .rounded))
                    .foregroundStyle(.white)
                    .opacity(textOpacity)
            }
        }
        .onAppear {
            withAnimation(.easeOut(duration: 0.6)) {
                iconOpacity = 1.0
                iconScale = 1.0
            }
            withAnimation(.easeOut(duration: 0.6).delay(0.3)) {
                textOpacity = 1.0
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + Constants.splashDurationSeconds) {
                withAnimation(.easeInOut(duration: 0.3)) {
                    isFinished = true
                }
            }
        }
    }
}
