import SwiftUI

struct ConversationListView: View {
    @ObservedObject var viewModel: ChatViewModel
    @ObservedObject var settingsVM: SettingsViewModel
    @ObservedObject var groupVM: GroupViewModel

    var body: some View {
        Group {
            if viewModel.conversations.isEmpty {
                emptyState
            } else {
                List(viewModel.conversations) { conversation in
                    NavigationLink(value: conversation.id) {
                        ConversationRow(conversation: conversation)
                    }
                }
                .listStyle(.plain)
            }
        }
        .navigationTitle("Flare")
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                NavigationLink(value: "broadcast") {
                    Image(systemName: "megaphone")
                }
                NavigationLink(value: "groups") {
                    Image(systemName: "person.3")
                }
                NavigationLink(value: "settings") {
                    Image(systemName: "gearshape")
                }
            }
            ToolbarItem(placement: .topBarLeading) {
                MeshStatusIndicator(status: viewModel.meshStatus, isRunning: viewModel.isServiceRunning)
            }
        }
        .navigationDestination(for: String.self) { route in
            switch route {
            case "settings":
                SettingsView(viewModel: settingsVM)
            case "settings-duress":
                DuressSettingsView(viewModel: settingsVM)
            case "settings-power":
                PowerSettingsView(viewModel: settingsVM)
            case "broadcast":
                BroadcastView(viewModel: viewModel)
            case "groups":
                GroupListView(viewModel: groupVM)
            case "create-group":
                CreateGroupView(viewModel: groupVM)
            default:
                ChatView(conversationId: route, viewModel: viewModel)
            }
        }
    }

    private var emptyState: some View {
        VStack(spacing: 16) {
            Image(systemName: "bubble.left.and.bubble.right")
                .font(.system(size: 64))
                .foregroundStyle(.secondary)

            Text("No conversations yet")
                .font(.title3)
                .foregroundStyle(.secondary)

            Text("Add a contact to start messaging over the mesh network.")
                .font(.body)
                .foregroundStyle(.tertiary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 48)

            if viewModel.meshStatus.discoveredPeerCount > 0 {
                Label(
                    "\(viewModel.meshStatus.discoveredPeerCount) Flare devices nearby",
                    systemImage: "antenna.radiowaves.left.and.right"
                )
                .font(.subheadline.weight(.medium))
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
                .background(.ultraThinMaterial, in: Capsule())
            }
        }
    }
}

struct ConversationRow: View {
    let conversation: Conversation

    var body: some View {
        HStack(spacing: 12) {
            IdenticonAvatarView(
                deviceId: conversation.contact.identity.deviceId,
                displayName: conversation.contact.displayName,
                size: 48
            )

            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Text(conversation.contact.displayName ?? String(conversation.contact.identity.deviceId.prefix(12)))
                        .font(.headline)
                        .lineLimit(1)

                    Spacer()

                    if let lastMsg = conversation.lastMessage {
                        Text(lastMsg.timestamp, style: .time)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }

                HStack {
                    if let lastMsg = conversation.lastMessage {
                        Text(lastMsg.content)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    } else {
                        Text("No messages yet")
                            .font(.subheadline)
                            .foregroundStyle(.tertiary)
                    }

                    Spacer()

                    if conversation.unreadCount > 0 {
                        Text("\(conversation.unreadCount)")
                            .font(.caption2.weight(.bold))
                            .foregroundStyle(.white)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(.blue, in: Capsule())
                    }
                }
            }
        }
        .padding(.vertical, 4)
    }
}

struct MeshStatusIndicator: View {
    let status: MeshStatus
    let isRunning: Bool

    var body: some View {
        HStack(spacing: 4) {
            Circle()
                .fill(statusColor)
                .frame(width: 8, height: 8)

            Text(statusText)
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }

    private var statusColor: Color {
        if !isRunning { return .red }
        if status.connectedPeerCount > 0 { return .green }
        return .orange
    }

    private var statusText: String {
        if !isRunning { return "Offline" }
        if status.connectedPeerCount > 0 { return "\(status.connectedPeerCount) connected" }
        return "Scanning"
    }
}

/// Identicon-based avatar view using deterministic colors from device ID.
struct IdenticonAvatarView: View {
    let deviceId: String
    let displayName: String?
    var isVerified: Bool = false
    var size: CGFloat = 48

    var body: some View {
        let colors = IdenticonGenerator.getColors(deviceId: deviceId)
        let initials = IdenticonGenerator.getInitials(displayName: displayName, deviceId: deviceId)

        ZStack {
            Circle()
                .fill(colors.background.opacity(0.25))
                .frame(width: size, height: size)

            Text(initials)
                .font(.system(size: size * 0.4, weight: .medium))
                .foregroundStyle(colors.background)
        }
    }
}

/// Legacy avatar view kept for backwards compatibility — delegates to IdenticonAvatarView.
struct AvatarView: View {
    let initials: String
    var isVerified: Bool = false
    var size: CGFloat = 48

    var body: some View {
        ZStack {
            Circle()
                .fill(isVerified ? Color.accentColor.opacity(0.2) : Color.secondary.opacity(0.15))
                .frame(width: size, height: size)

            Text(initials)
                .font(.system(size: size * 0.4, weight: .medium))
                .foregroundStyle(isVerified ? Color.accentColor : Color.secondary)
        }
    }
}
