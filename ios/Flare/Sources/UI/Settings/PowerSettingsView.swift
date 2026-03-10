import SwiftUI

struct PowerSettingsView: View {
    @ObservedObject var viewModel: SettingsViewModel

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                currentTierCard
                batterySaverToggle
                tierExplanationsSection
            }
            .padding()
        }
        .navigationTitle("Power Management")
    }

    // MARK: - Current Tier

    private var currentTierCard: some View {
        let tier = viewModel.currentPowerTier
        let color = tierColor(tier)

        return HStack(spacing: 16) {
            Image(systemName: tierIcon(tier))
                .font(.system(size: 32))
                .foregroundStyle(color)

            VStack(alignment: .leading, spacing: 4) {
                Text("Current Tier")
                    .font(.caption)
                    .foregroundStyle(.secondary)

                Text(tierDisplayName(tier))
                    .font(.title3.weight(.semibold))

                Text(tierDescription(tier))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Spacer()
        }
        .padding()
        .background(color.opacity(0.08), in: RoundedRectangle(cornerRadius: 16))
    }

    // MARK: - Battery Saver Toggle

    private var batterySaverToggle: some View {
        HStack {
            Label("Battery Saver", systemImage: "leaf.fill")
                .foregroundStyle(.primary)

            Spacer()

            Toggle("", isOn: Binding(
                get: { viewModel.batterySaverEnabled },
                set: { _ in viewModel.toggleBatterySaver() }
            ))
            .labelsHidden()
        }
        .padding()
        .background(Color(.systemGray6), in: RoundedRectangle(cornerRadius: 12))
    }

    // MARK: - Tier Explanations

    private var tierExplanationsSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Power Tiers")
                .font(.headline)
                .padding(.top, 4)

            tierCard(
                name: "High",
                icon: "bolt.fill",
                color: .red,
                description: "Continuous scanning for up to \(Int(Constants.powerHighDurationLimitSeconds))s. Used when peers are actively exchanging messages. Drops to Balanced after \(Int(Constants.powerHighInactivityThresholdSeconds))s of inactivity."
            )

            tierCard(
                name: "Balanced",
                icon: "bolt.badge.clock",
                color: .blue,
                description: "Standard scanning with \(String(format: "%.1f", Constants.powerBalancedBurstScanSeconds))s bursts. Falls to Low Power after \(Int(Constants.powerBalancedNoPeersThresholdSeconds))s with no peers discovered."
            )

            tierCard(
                name: "Low Power",
                icon: "battery.50percent",
                color: .green,
                description: "Scans for \(String(format: "%.0f", Constants.powerLowBurstScanSeconds))s then sleeps for \(String(format: "%.0f", Constants.powerLowBurstSleepSeconds))s. Activates below \(Constants.powerLowBatteryPercent)% battery."
            )

            tierCard(
                name: "Ultra Low",
                icon: "battery.25percent",
                color: .gray,
                description: "Scans for \(String(format: "%.0f", Constants.powerUltraLowBurstScanSeconds))s then sleeps for \(String(format: "%.0f", Constants.powerUltraLowBurstSleepSeconds))s. Activates below \(Constants.powerCriticalBatteryPercent)% battery."
            )

            Text("Flare automatically adjusts the power tier based on network activity, peer presence, and battery level. High tier is disabled below \(Constants.powerLowBatteryPercent)% battery.")
                .font(.caption)
                .foregroundStyle(.secondary)
                .padding(.top, 4)
        }
    }

    private func tierCard(name: String, icon: String, color: Color, description: String) -> some View {
        let isActive = tierDisplayName(viewModel.currentPowerTier) == name

        return HStack(alignment: .top, spacing: 12) {
            Image(systemName: icon)
                .font(.title3)
                .foregroundStyle(color)
                .frame(width: 28)

            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Text(name)
                        .font(.subheadline.weight(.semibold))

                    if isActive {
                        Text("ACTIVE")
                            .font(.caption2.weight(.bold))
                            .foregroundStyle(color)
                    }
                }

                Text(description)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            isActive ? color.opacity(0.08) : Color(.systemGray6),
            in: RoundedRectangle(cornerRadius: 12)
        )
    }

    // MARK: - Tier Helpers

    private func tierColor(_ tier: String) -> Color {
        switch tier {
        case "high": return .red
        case "balanced": return .blue
        case "low_power": return .green
        case "ultra_low": return .gray
        default: return .secondary
        }
    }

    private func tierIcon(_ tier: String) -> String {
        switch tier {
        case "high": return "bolt.fill"
        case "balanced": return "bolt.badge.clock"
        case "low_power": return "battery.50percent"
        case "ultra_low": return "battery.25percent"
        default: return "battery.100percent"
        }
    }

    private func tierDisplayName(_ tier: String) -> String {
        switch tier {
        case "high": return "High"
        case "balanced": return "Balanced"
        case "low_power": return "Low Power"
        case "ultra_low": return "Ultra Low"
        default: return tier
        }
    }

    private func tierDescription(_ tier: String) -> String {
        switch tier {
        case "high": return "Maximum scan rate, continuous discovery"
        case "balanced": return "Standard scanning, good battery life"
        case "low_power": return "Intermittent scanning, extended battery"
        case "ultra_low": return "Minimal scanning, maximum battery savings"
        default: return ""
        }
    }
}
