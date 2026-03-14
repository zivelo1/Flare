import SwiftUI

struct ContactsView: View {
    @ObservedObject var viewModel: ContactsViewModel
    @ObservedObject var discoveryVM: DiscoveryViewModel

    @State private var contactToDelete: Contact?
    @State private var showDeleteConfirmation = false
    @State private var contactToRename: Contact?
    @State private var showRenameDialog = false
    @State private var newContactName = ""

    var body: some View {
        Group {
            if viewModel.contacts.isEmpty {
                emptyState
            } else {
                List(viewModel.contacts) { contact in
                    NavigationLink(value: contact.identity.deviceId) {
                        ContactRow(contact: contact)
                    }
                    .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                        Button(role: .destructive) {
                            contactToDelete = contact
                            showDeleteConfirmation = true
                        } label: {
                            Label(String(localized: "delete_contact_confirm"), systemImage: "trash")
                        }
                    }
                    .contextMenu {
                        Button {
                            contactToRename = contact
                            newContactName = contact.displayName ?? ""
                            showRenameDialog = true
                        } label: {
                            Label(String(localized: "rename_contact_title"), systemImage: "pencil")
                        }

                        Button(role: .destructive) {
                            contactToDelete = contact
                            showDeleteConfirmation = true
                        } label: {
                            Label(String(localized: "delete_contact_confirm"), systemImage: "trash")
                        }
                    }
                }
                .listStyle(.plain)
            }
        }
        .navigationTitle(String(localized: "tab_contacts"))
        .alert(String(localized: "delete_contact_title"), isPresented: $showDeleteConfirmation) {
            Button(String(localized: "delete_contact_confirm"), role: .destructive) {
                if let contact = contactToDelete {
                    viewModel.deleteContact(deviceId: contact.identity.deviceId)
                }
                contactToDelete = nil
            }
            Button(String(localized: "action_cancel"), role: .cancel) {
                contactToDelete = nil
            }
        } message: {
            Text(String(localized: "delete_contact_message \(contactToDelete?.displayName ?? contactToDelete?.identity.deviceId.prefix(12).description ?? "")"))
        }
        .alert(String(localized: "rename_contact_title"), isPresented: $showRenameDialog) {
            TextField(String(localized: "rename_contact_hint"), text: $newContactName)
            Button(String(localized: "action_save")) {
                if let contact = contactToRename, !newContactName.isEmpty {
                    viewModel.renameContact(deviceId: contact.identity.deviceId, newName: newContactName)
                }
                contactToRename = nil
                newContactName = ""
            }
            Button(String(localized: "action_cancel"), role: .cancel) {
                contactToRename = nil
                newContactName = ""
            }
        }
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
                    safetyNumber: viewModel.getSafetyNumber(),
                    shareLink: viewModel.generateShareLink()
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
