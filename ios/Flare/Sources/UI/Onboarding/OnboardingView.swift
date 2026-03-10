import SwiftUI

struct OnboardingView: View {
    @AppStorage("onboarding_complete") private var onboardingComplete = false
    @State private var currentPage = 0

    private let pages: [OnboardingPage] = [
        OnboardingPage(
            icon: "antenna.radiowaves.left.and.right",
            title: "No Internet Needed",
            description: "Flare uses Bluetooth to create a mesh network between nearby devices. Messages hop from phone to phone until they reach their destination."
        ),
        OnboardingPage(
            icon: "lock.fill",
            title: "End-to-End Encrypted",
            description: "Every message is encrypted before it leaves your device. Only the intended recipient can read it. Not even relay devices can see your messages."
        ),
        OnboardingPage(
            icon: "person.2.fill",
            title: "Find Your Friends",
            description: "Discover contacts nearby using QR codes, shared phrases, or phone numbers. No servers or accounts required."
        ),
        OnboardingPage(
            icon: "shield.fill",
            title: "Designed for Safety",
            description: "Built for situations where privacy matters most. Duress protection, message expiration, and zero metadata collection keep you safe."
        )
    ]

    private var isLastPage: Bool {
        currentPage == pages.count - 1
    }

    private var isFirstPage: Bool {
        currentPage == 0
    }

    var body: some View {
        VStack(spacing: 0) {
            TabView(selection: $currentPage) {
                ForEach(Array(pages.enumerated()), id: \.offset) { index, page in
                    pageContent(page: page)
                        .tag(index)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .never))
            .animation(.easeInOut(duration: 0.3), value: currentPage)

            bottomControls
                .padding(.horizontal, 24)
                .padding(.bottom, 48)
        }
        .background(Color(.systemBackground))
    }

    @ViewBuilder
    private func pageContent(page: OnboardingPage) -> some View {
        VStack(spacing: 32) {
            Spacer()

            ZStack {
                Circle()
                    .fill(Color.accentColor.opacity(0.12))
                    .frame(width: 120, height: 120)

                Image(systemName: page.icon)
                    .font(.system(size: 48))
                    .foregroundStyle(Color.accentColor)
            }

            VStack(spacing: 12) {
                Text(page.title)
                    .font(.title.weight(.bold))
                    .multilineTextAlignment(.center)

                Text(page.description)
                    .font(.body)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 32)
            }

            Spacer()
            Spacer()
        }
    }

    private var bottomControls: some View {
        VStack(spacing: 20) {
            pageIndicator

            HStack {
                if isLastPage {
                    Button {
                        onboardingComplete = true
                    } label: {
                        Text("Get Started")
                            .font(.headline)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 14)
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)
                } else {
                    Button("Skip") {
                        onboardingComplete = true
                    }
                    .foregroundStyle(.secondary)

                    Spacer()

                    HStack(spacing: 16) {
                        if !isFirstPage {
                            Button {
                                withAnimation {
                                    currentPage -= 1
                                }
                            } label: {
                                Image(systemName: "chevron.left")
                                    .font(.body.weight(.semibold))
                                    .frame(width: 44, height: 44)
                                    .background(Color(.tertiarySystemFill), in: Circle())
                            }
                        }

                        Button {
                            withAnimation {
                                currentPage += 1
                            }
                        } label: {
                            Image(systemName: "chevron.right")
                                .font(.body.weight(.semibold))
                                .frame(width: 44, height: 44)
                                .background(Color.accentColor, in: Circle())
                                .foregroundStyle(.white)
                        }
                    }
                }
            }
        }
    }

    private var pageIndicator: some View {
        HStack(spacing: 8) {
            ForEach(0..<pages.count, id: \.self) { index in
                Circle()
                    .fill(index == currentPage ? Color.accentColor : Color.secondary.opacity(0.3))
                    .frame(width: index == currentPage ? 10 : 8, height: index == currentPage ? 10 : 8)
                    .animation(.easeInOut(duration: 0.2), value: currentPage)
            }
        }
    }
}

private struct OnboardingPage {
    let icon: String
    let title: String
    let description: String
}
