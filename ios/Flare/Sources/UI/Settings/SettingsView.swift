import SwiftUI

struct SettingsView: View {
    @ObservedObject var viewModel: SettingsViewModel
    @AppStorage(Constants.prefsKeyDisplayName) private var displayName: String = ""

    var body: some View {
        List {
            profileSection
            securitySection
            batterySection
            storageSection
            deviceSection
            aboutSection
        }
        .navigationTitle(String(localized: "settings_title"))
    }

    // MARK: - Profile

    private var profileSection: some View {
        Section(String(localized: "settings_section_profile")) {
            HStack {
                Label(String(localized: "profile_name_label"), systemImage: "person")
                Spacer()
                TextField(String(localized: "profile_name_hint"), text: $displayName)
                    .multilineTextAlignment(.trailing)
                    .foregroundStyle(.primary)
            }
        }
    }

    // MARK: - Security

    private var securitySection: some View {
        Section(String(localized: "settings_section_security")) {
            NavigationLink {
                DestructionCodeSetupView()
            } label: {
                Label(String(localized: "destruction_title"), systemImage: "lock.shield")
            }
        }
    }

    // MARK: - Battery & Performance

    private var batterySection: some View {
        Section(String(localized: "settings_section_battery")) {
            NavigationLink {
                PowerSettingsView(viewModel: viewModel)
            } label: {
                Label(String(localized: "settings_power_title"), systemImage: "battery.75percent")
            }
        }
    }

    // MARK: - Storage

    private var storageSection: some View {
        Section(String(localized: "settings_section_storage")) {
            VStack(alignment: .leading, spacing: 12) {
                HStack(spacing: 16) {
                    Image(systemName: "internaldrive")
                        .font(.system(size: 28))
                        .foregroundStyle(.blue)

                    VStack(alignment: .leading, spacing: 4) {
                        Text("Message Store")
                            .font(.subheadline.weight(.semibold))

                        Text(formattedBytes(viewModel.storeStats?.totalBytes ?? 0) + " of " + formattedBytes(viewModel.storeStats?.budgetBytes ?? 0) + " used")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }

                    Spacer()
                }

                ProgressView(value: storageProgress)
                    .tint(storageProgressColor)

                HStack(spacing: 0) {
                    storageStatLabel("Own", value: viewModel.storeStats?.ownMessages ?? 0)
                    Spacer()
                    storageStatLabel("Relay", value: viewModel.storeStats?.activeRelayMessages ?? 0)
                    Spacer()
                    storageStatLabel("Waiting", value: waitingMessages)
                    Spacer()
                    storageStatLabel("Total", value: viewModel.storeStats?.totalMessages ?? 0)
                }
            }
            .padding(.vertical, 4)
        }
    }

    private func storageStatLabel(_ title: String, value: Int) -> some View {
        VStack(spacing: 2) {
            Text("\(value)")
                .font(.subheadline.weight(.bold))
            Text(title)
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
    }

    private var storageProgress: Double {
        guard let stats = viewModel.storeStats, stats.budgetBytes > 0 else { return 0 }
        return min(Double(stats.totalBytes) / Double(stats.budgetBytes), 1.0)
    }

    private var storageProgressColor: Color {
        if storageProgress > 0.9 { return .red }
        if storageProgress > 0.7 { return .orange }
        return .blue
    }

    private var waitingMessages: Int {
        guard let stats = viewModel.storeStats else { return 0 }
        return max(stats.totalMessages - stats.ownMessages - stats.activeRelayMessages, 0)
    }

    // MARK: - Device

    private var deviceSection: some View {
        Section(String(localized: "settings_section_device")) {
            HStack {
                Label(String(localized: "settings_device_id"), systemImage: "cpu")
                Spacer()
                Text(String(viewModel.deviceId.prefix(12)) + "...")
                    .font(.caption.monospaced())
                    .foregroundStyle(.secondary)
            }

            HStack {
                Label(String(localized: "settings_safety_number"), systemImage: "checkmark.shield")
                Spacer()
                Text(viewModel.safetyNumber)
                    .font(.caption.monospaced())
                    .foregroundStyle(.secondary)
            }
        }
    }

    // MARK: - About

    private var aboutSection: some View {
        Section(String(localized: "settings_section_about")) {
            HStack {
                Label("Flare", systemImage: "flame")
                Spacer()
                Text(String(localized: "settings_about_subtitle"))
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }

            HStack {
                Label(String(localized: "settings_version"), systemImage: "info.circle")
                Spacer()
                Text(Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }

            HStack {
                Label(String(localized: "settings_open_source"), systemImage: "doc.text")
                Spacer()
                Text(String(localized: "settings_license"))
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
        }
    }

    // MARK: - Helpers

    private func formattedBytes(_ bytes: Int) -> String {
        let formatter = ByteCountFormatter()
        formatter.countStyle = .binary
        return formatter.string(fromByteCount: Int64(bytes))
    }
}
