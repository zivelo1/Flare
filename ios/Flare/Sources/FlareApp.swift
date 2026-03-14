import SwiftUI

@main
struct FlareApp: App {
    @StateObject private var appState = AppState()
    @AppStorage("onboarding_complete") private var onboardingComplete = false
    @AppStorage(Constants.prefsKeyUnlockCodeHash) private var unlockCodeHash = ""
    @AppStorage(Constants.prefsKeyDestructionCodeHash) private var destructionCodeHash = ""
    @State private var splashFinished = false
    @State private var isUnlocked = false
    @State private var deepLinkMessage: String?

    /// Whether a destruction code is configured (lock screen required).
    private var isLockConfigured: Bool {
        !unlockCodeHash.isEmpty && !destructionCodeHash.isEmpty
    }

    var body: some Scene {
        WindowGroup {
            Group {
                if !splashFinished {
                    SplashView(isFinished: $splashFinished)
                } else if !appState.isInitialized {
                    ProgressView("Initializing Flare…")
                        .task {
                            await appState.initialize()
                        }
                } else if isLockConfigured && !isUnlocked {
                    LockScreenView(
                        unlockCodeHash: unlockCodeHash,
                        destructionCodeHash: destructionCodeHash,
                        onUnlocked: {
                            isUnlocked = true
                        },
                        onDestruction: {
                            do {
                                try FlareRepository.shared.wipeAndReinitialize()
                            } catch {
                                print("Wipe failed: \(error)")
                            }
                            isUnlocked = true
                        }
                    )
                } else if !onboardingComplete {
                    OnboardingView()
                } else {
                    MainTabView()
                        .environmentObject(appState)
                        .overlay(alignment: .bottom) {
                            if let message = deepLinkMessage {
                                DeepLinkToast(message: message)
                                    .onAppear {
                                        DispatchQueue.main.asyncAfter(deadline: .now() + 2.5) {
                                            deepLinkMessage = nil
                                        }
                                    }
                            }
                        }
                }
            }
            .onOpenURL { url in
                handleDeepLink(url)
            }
        }
    }

    private func handleDeepLink(_ url: URL) {
        guard appState.isInitialized else { return }

        let contactsVM = ContactsViewModel()
        let success = contactsVM.addContactFromLink(url)
        deepLinkMessage = success
            ? String(localized: "deep_link_contact_added")
            : String(localized: "deep_link_invalid")
    }
}

/// Transient toast notification for deep link results.
private struct DeepLinkToast: View {
    let message: String

    var body: some View {
        Text(message)
            .font(.subheadline.weight(.medium))
            .padding(.horizontal, 20)
            .padding(.vertical, 12)
            .background(.ultraThinMaterial, in: Capsule())
            .padding(.bottom, 80)
            .transition(.move(edge: .bottom).combined(with: .opacity))
            .animation(.spring(response: 0.3), value: message)
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
