import SwiftUI

struct CreateGroupView: View {
    @ObservedObject var viewModel: GroupViewModel
    @Environment(\.dismiss) private var dismiss

    @State private var groupName = ""

    private var canCreate: Bool {
        !groupName.trimmingCharacters(in: .whitespaces).isEmpty
            && !viewModel.selectedMembers.isEmpty
    }

    var body: some View {
        Form {
            Section("Group Name") {
                TextField("Enter group name", text: $groupName)
                    .autocorrectionDisabled()
            }

            Section("Members (\(viewModel.selectedMembers.count) selected)") {
                if viewModel.availableContacts.isEmpty {
                    Text("No contacts available.\nAdd contacts first to create a group.")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                } else {
                    ForEach(viewModel.availableContacts) { contact in
                        Button {
                            viewModel.toggleMemberSelection(deviceId: contact.identity.deviceId)
                        } label: {
                            HStack(spacing: 12) {
                                contactAvatar(contact: contact)

                                VStack(alignment: .leading, spacing: 2) {
                                    Text(contact.displayName ?? String(contact.identity.deviceId.prefix(12)))
                                        .font(.body)
                                        .foregroundStyle(.primary)

                                    Text(String(contact.identity.deviceId.prefix(8)))
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }

                                Spacer()

                                if viewModel.selectedMembers.contains(contact.identity.deviceId) {
                                    Image(systemName: "checkmark.circle.fill")
                                        .foregroundStyle(.blue)
                                        .font(.title3)
                                } else {
                                    Image(systemName: "circle")
                                        .foregroundStyle(.secondary)
                                        .font(.title3)
                                }
                            }
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                    }
                }
            }

            if case .error(let message) = viewModel.createStatus {
                Section {
                    Label(message, systemImage: "exclamationmark.triangle")
                        .foregroundStyle(.red)
                        .font(.subheadline)
                }
            }
        }
        .navigationTitle("New Group")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("Create") {
                    viewModel.createGroup(groupName: groupName.trimmingCharacters(in: .whitespaces))
                }
                .fontWeight(.semibold)
                .disabled(!canCreate || viewModel.createStatus == .creating)
            }

            ToolbarItem(placement: .topBarLeading) {
                Button("Cancel") {
                    viewModel.clearSelection()
                    dismiss()
                }
            }
        }
        .onChange(of: viewModel.createStatus) { newStatus in
            if case .success = newStatus {
                viewModel.createStatus = .idle
                dismiss()
            }
        }
        .interactiveDismissDisabled(viewModel.createStatus == .creating)
    }

    @ViewBuilder
    private func contactAvatar(contact: Contact) -> some View {
        IdenticonAvatarView(
            deviceId: contact.identity.deviceId,
            displayName: contact.displayName,
            size: 40
        )
    }
}
