import SwiftUI

struct ContactsView: View {
    @ObservedObject var viewModel: ContactsViewModel
    @ObservedObject var discoveryVM: DiscoveryViewModel

    var body: some View {
        Group {
            if viewModel.contacts.isEmpty {
                emptyState
            } else {
                List(viewModel.contacts) { contact in
                    NavigationLink(value: contact.identity.deviceId) {
                        ContactRow(contact: contact)
                    }
                }
                .listStyle(.plain)
            }
        }
        .navigationTitle("Contacts")
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                NavigationLink(value: "find-contact") {
                    Image(systemName: "person.badge.plus")
                }
                NavigationLink(value: "qr-scanner") {
                    Image(systemName: "qrcode.viewfinder")
                }
                NavigationLink(value: "qr-display") {
                    Image(systemName: "qrcode")
                }
            }
        }
        .navigationDestination(for: String.self) { route in
            switch route {
            case "find-contact":
                FindContactView(discoveryVM: discoveryVM)
            case "qr-scanner":
                QRScannerView(contactsVM: viewModel)
            case "qr-display":
                QRDisplayView(
                    qrData: viewModel.generateQrData(),
                    safetyNumber: viewModel.getSafetyNumber()
                )
            case "phrase-search":
                SharedPhraseSearchView(viewModel: discoveryVM)
            case "phone-search":
                PhoneSearchView(viewModel: discoveryVM)
            default:
                // Device ID → navigate to chat
                ChatView(
                    conversationId: route,
                    viewModel: ChatViewModel()
                )
            }
        }
        .onAppear {
            viewModel.refreshContacts()
        }
    }

    private var emptyState: some View {
        VStack(spacing: 16) {
            Image(systemName: "person.2.slash")
                .font(.system(size: 64))
                .foregroundStyle(.secondary.opacity(0.6))

            Text("No contacts yet.\nScan a QR code or use a shared phrase to find someone.")
                .font(.body)
                .foregroundStyle(.secondary)
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

struct ContactRow: View {
    let contact: Contact

    var body: some View {
        HStack(spacing: 12) {
            IdenticonAvatarView(
                deviceId: contact.identity.deviceId,
                displayName: contact.displayName,
                isVerified: contact.isVerified,
                size: 48
            )

            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 4) {
                    Text(contact.displayName ?? String(contact.identity.deviceId.prefix(12)))
                        .font(.body)
                        .lineLimit(1)

                    if contact.isVerified {
                        Image(systemName: "checkmark.seal.fill")
                            .font(.caption)
                            .foregroundStyle(Color.accentColor)
                    }
                }

                Text(formatLastSeen(contact.lastSeen))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 2)
    }

    private func formatLastSeen(_ date: Date) -> String {
        let interval = Date().timeIntervalSince(date)
        let minutes = Int(interval / 60)
        let hours = Int(interval / 3600)
        let days = Int(interval / 86400)

        if minutes < 1 { return "Just now" }
        if minutes < 60 { return "\(minutes)m ago" }
        if hours < 24 { return "\(hours)h ago" }
        return "\(days)d ago"
    }
}
