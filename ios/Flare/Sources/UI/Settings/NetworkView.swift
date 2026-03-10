import SwiftUI

struct NetworkView: View {
    @ObservedObject var viewModel: NetworkViewModel

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                meshStatusCard
                statsRow
                nearbyDevicesSection
            }
            .padding()
        }
        .navigationTitle("Network")
    }

    private var meshStatusCard: some View {
        HStack(spacing: 16) {
            Image(systemName: viewModel.isRunning ? "antenna.radiowaves.left.and.right" : "antenna.radiowaves.left.and.right.slash")
                .font(.system(size: 32))
                .foregroundStyle(statusColor)

            VStack(alignment: .leading, spacing: 4) {
                Text(viewModel.isRunning ? "Active" : "Inactive")
                    .font(.title3.weight(.semibold))

                Text(statusSubtitle)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }

            Spacer()
        }
        .padding()
        .background(statusBackground, in: RoundedRectangle(cornerRadius: 16))
    }

    private var statsRow: some View {
        HStack(spacing: 12) {
            StatCard(
                title: "Connected",
                value: "\(viewModel.meshStatus.connectedPeerCount)",
                icon: "link"
            )
            StatCard(
                title: "Queued",
                value: "\(viewModel.meshStatus.storedMessageCount)",
                icon: "tray"
            )
            StatCard(
                title: "Relayed",
                value: "\(viewModel.meshStatus.messagesRelayed)",
                icon: "arrow.triangle.swap"
            )
        }
    }

    private var nearbyDevicesSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Nearby Devices")
                .font(.headline)

            if viewModel.nearbyPeers.isEmpty {
                HStack {
                    ProgressView()
                    Text("Scanning for Flare devices…")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity, alignment: .center)
                .padding(.vertical, 24)
            } else {
                ForEach(Array(viewModel.nearbyPeers.values), id: \.deviceId) { peer in
                    PeerCard(peer: peer)
                }
            }
        }
    }

    private var statusColor: Color {
        if !viewModel.isRunning { return .red }
        if viewModel.meshStatus.connectedPeerCount > 0 { return .green }
        return .orange
    }

    private var statusSubtitle: String {
        if !viewModel.isRunning { return "Mesh network is not running" }
        let connected = viewModel.meshStatus.connectedPeerCount
        let discovered = viewModel.meshStatus.discoveredPeerCount
        if connected > 0 {
            return "\(connected) connected, \(discovered) discovered"
        }
        return "Scanning for nearby devices…"
    }

    private var statusBackground: Color {
        if !viewModel.isRunning { return .red.opacity(0.08) }
        if viewModel.meshStatus.connectedPeerCount > 0 { return .green.opacity(0.08) }
        return .orange.opacity(0.08)
    }
}

struct StatCard: View {
    let title: String
    let value: String
    let icon: String

    var body: some View {
        VStack(spacing: 8) {
            Image(systemName: icon)
                .font(.title3)
                .foregroundStyle(.secondary)

            Text(value)
                .font(.title2.weight(.bold))

            Text(title)
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 16)
        .background(Color(.systemGray6), in: RoundedRectangle(cornerRadius: 12))
    }
}

struct PeerCard: View {
    let peer: MeshPeer

    var body: some View {
        HStack(spacing: 12) {
            ZStack {
                Circle()
                    .fill(peer.isConnected ? Color.accentColor.opacity(0.2) : Color.secondary.opacity(0.1))
                    .frame(width: 44, height: 44)

                Image(systemName: "antenna.radiowaves.left.and.right")
                    .font(.body)
                    .foregroundStyle(peer.isConnected ? Color.accentColor : Color.secondary)
            }

            VStack(alignment: .leading, spacing: 2) {
                HStack {
                    Text(peer.shortId)
                        .font(.body)

                    if peer.isConnected {
                        Text("Connected")
                            .font(.caption2.weight(.medium))
                            .foregroundStyle(.green)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(.green.opacity(0.1), in: Capsule())
                    }
                }

                HStack(spacing: 8) {
                    if let rssi = peer.rssi {
                        Text("\(rssi) dBm")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }

                    if let distance = peer.estimatedDistance {
                        Text(String(format: "~%.1fm", distance))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            }

            Spacer()

            if let rssi = peer.rssi {
                SignalStrengthView(rssi: rssi)
            }
        }
        .padding()
        .background(Color(.systemGray6), in: RoundedRectangle(cornerRadius: 12))
    }
}

struct SignalStrengthView: View {
    let rssi: Int

    var body: some View {
        HStack(spacing: 2) {
            ForEach(0..<4) { bar in
                RoundedRectangle(cornerRadius: 1)
                    .fill(bar < signalBars ? Color.green : Color.secondary.opacity(0.2))
                    .frame(width: 4, height: CGFloat(6 + bar * 4))
            }
        }
    }

    private var signalBars: Int {
        if rssi >= -50 { return 4 }
        if rssi >= -65 { return 3 }
        if rssi >= -80 { return 2 }
        if rssi >= -90 { return 1 }
        return 0
    }
}
