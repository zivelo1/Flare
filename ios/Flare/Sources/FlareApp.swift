import SwiftUI

@main
struct FlareApp: App {
    @StateObject private var appState = AppState()

    var body: some Scene {
        WindowGroup {
            if appState.isInitialized {
                MainTabView()
                    .environmentObject(appState)
            } else {
                ProgressView("Initializing Flare…")
                    .task {
                        await appState.initialize()
                    }
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
