import SwiftUI

@main
struct FlareApp: App {
    @StateObject private var appState = AppState()
    @AppStorage("onboarding_complete") private var onboardingComplete = false

    var body: some Scene {
        WindowGroup {
            if !appState.isInitialized {
                ProgressView("Initializing Flare…")
                    .task {
                        await appState.initialize()
                    }
            } else if !onboardingComplete {
                OnboardingView()
            } else {
                MainTabView()
                    .environmentObject(appState)
            }
        }
    }
}

@MainActor
final class AppState: ObservableObject {
    @Published var isInitialized = false

    func initialize() async {
        do {
            try FlareRepository.shared.initialize()
            isInitialized = true
        } catch {
            print("Failed to initialize Flare: \(error)")
        }
    }
}
