import SwiftUI

struct BroadcastView: View {
    @ObservedObject var viewModel: ChatViewModel
    @Environment(\.dismiss) private var dismiss

    @State private var messageText = ""
    @State private var showConfirmation = false
    @State private var showSuccess = false

    private var contactCount: Int {
        viewModel.conversations.count
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                // Security Warning
                HStack(spacing: 12) {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .font(.title2)
                        .foregroundStyle(.orange)

                    Text(String(localized: "broadcast_security_notice"))
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                .padding()
                .background(Color(.systemGray6), in: RoundedRectangle(cornerRadius: 12))

                // Message Input
                VStack(alignment: .leading, spacing: 8) {
                    Text(String(localized: "broadcast_hint"))
                        .font(.subheadline)
                        .foregroundStyle(.secondary)

                    TextEditor(text: $messageText)
                        .frame(minHeight: 120)
                        .padding(8)
                        .background(Color(.systemGray6), in: RoundedRectangle(cornerRadius: 12))
                        .overlay(
                            RoundedRectangle(cornerRadius: 12)
                                .stroke(Color(.systemGray4), lineWidth: 1)
                        )
                }

                // Send Button
                Button {
                    showConfirmation = true
                } label: {
                    HStack {
                        Image(systemName: "megaphone.fill")
                        Text(String(localized: "broadcast_send"))
                    }
                    .frame(maxWidth: .infinity)
                    .padding()
                    .background(messageText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || contactCount == 0
                        ? Color.gray : Constants.flareOrange,
                        in: RoundedRectangle(cornerRadius: 12))
                    .foregroundStyle(.white)
                    .font(.headline)
                }
                .disabled(messageText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || contactCount == 0)

                if contactCount == 0 {
                    Text(String(localized: "broadcast_no_contacts"))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                        .frame(maxWidth: .infinity)
                }
            }
            .padding()
        }
        .navigationTitle(String(localized: "broadcast_title"))
        .navigationBarTitleDisplayMode(.inline)
        .confirmationDialog(
            String(localized: "broadcast_confirm_title"),
            isPresented: $showConfirmation,
            titleVisibility: .visible
        ) {
            Button(String(localized: "broadcast_confirm_send")) {
                let text = messageText.trimmingCharacters(in: .whitespacesAndNewlines)
                viewModel.sendBroadcast(text: text)
                messageText = ""
                showSuccess = true
                DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
                    dismiss()
                }
            }
            Button(String(localized: "action_cancel"), role: .cancel) {}
        } message: {
            Text("broadcast_confirm_message \(contactCount)")
        }
        .overlay {
            if showSuccess {
                VStack {
                    Spacer()
                    Text("broadcast_sent \(contactCount)")
                        .font(.subheadline.weight(.medium))
                        .padding(.horizontal, 20)
                        .padding(.vertical, 12)
                        .background(.ultraThinMaterial, in: Capsule())
                        .padding(.bottom, 40)
                }
                .transition(.move(edge: .bottom).combined(with: .opacity))
                .animation(.spring(response: 0.3), value: showSuccess)
            }
        }
    }
}
