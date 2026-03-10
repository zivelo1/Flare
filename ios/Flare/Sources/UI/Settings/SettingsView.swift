import SwiftUI

struct SettingsView: View {
    @ObservedObject var viewModel: SettingsViewModel

    var body: some View {
        List {
            securitySection
            batterySection
            storageSection
            deviceSection
            aboutSection
        }
        .navigationTitle("Settings")
    }

    // MARK: - Security

    private var securitySection: some View {
        Section("Security") {
            NavigationLink {
                DuressSettingsView(viewModel: viewModel)
            } label: {
                Label("Duress PIN", systemImage: "lock.shield")
            }
        }
    }

    // MARK: - Battery & Performance

    private var batterySection: some View {
        Section("Battery & Performance") {
            NavigationLink {
                PowerSettingsView(viewModel: viewModel)
            } label: {
                Label("Power Management", systemImage: "battery.75percent")
            }
        }
    }

    // MARK: - Storage

    private var storageSection: some View {
        Section("Storage") {
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
        Section("Device") {
            HStack {
                Label("Device ID", systemImage: "cpu")
                Spacer()
                Text(String(viewModel.deviceId.prefix(12)) + "...")
                    .font(.caption.monospaced())
                    .foregroundStyle(.secondary)
            }

            HStack {
                Label("Safety Number", systemImage: "checkmark.shield")
                Spacer()
                Text(viewModel.safetyNumber)
                    .font(.caption.monospaced())
                    .foregroundStyle(.secondary)
            }
        }
    }

    // MARK: - About

    private var aboutSection: some View {
        Section("About") {
            HStack {
                Label("Flare", systemImage: "flame")
                Spacer()
                Text("Mesh Messenger")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }

            HStack {
                Label("License", systemImage: "doc.text")
                Spacer()
                Text("GPLv3")
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
