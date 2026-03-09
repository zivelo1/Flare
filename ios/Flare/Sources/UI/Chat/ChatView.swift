import SwiftUI

struct ChatView: View {
    let conversationId: String
    @ObservedObject var viewModel: ChatViewModel
    @State private var messageText = ""

    var body: some View {
        VStack(spacing: 0) {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 8) {
                        if viewModel.currentMessages.isEmpty {
                            emptyState
                        }

                        ForEach(viewModel.currentMessages) { message in
                            MessageBubble(message: message)
                                .id(message.messageId)
                        }
                    }
                    .padding()
                }
                .onChange(of: viewModel.currentMessages.count) {
                    if let lastId = viewModel.currentMessages.last?.messageId {
                        withAnimation {
                            proxy.scrollTo(lastId, anchor: .bottom)
                        }
                    }
                }
            }

            Divider()
            messageInputBar
        }
        .navigationTitle(viewModel.currentContact?.displayName ?? String(conversationId.prefix(12)))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) {
                VStack(spacing: 0) {
                    Text(viewModel.currentContact?.displayName ?? String(conversationId.prefix(12)))
                        .font(.headline)
                    Text("via mesh")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }
        }
        .onAppear {
            viewModel.loadConversation(conversationId)
        }
    }

    private var emptyState: some View {
        VStack(spacing: 8) {
            Spacer(minLength: 60)
            Image(systemName: "lock.shield")
                .font(.system(size: 40))
                .foregroundStyle(.secondary)
            Text("End-to-end encrypted")
                .font(.subheadline)
                .foregroundStyle(.secondary)
            Text("Send a message to start the conversation.")
                .font(.caption)
                .foregroundStyle(.tertiary)
            Spacer(minLength: 60)
        }
    }

    private var messageInputBar: some View {
        HStack(spacing: 8) {
            TextField("Message", text: $messageText, axis: .vertical)
                .textFieldStyle(.plain)
                .lineLimit(1...4)
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(Color(.systemGray6), in: RoundedRectangle(cornerRadius: 20))

            Button {
                sendMessage()
            } label: {
                Image(systemName: "arrow.up.circle.fill")
                    .font(.system(size: 32))
                    .foregroundStyle(.accent)
            }
            .disabled(messageText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        }
        .padding(.horizontal)
        .padding(.vertical, 8)
    }

    private func sendMessage() {
        let text = messageText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        messageText = ""
        viewModel.sendMessage(conversationId: conversationId, text: text)
    }
}

struct MessageBubble: View {
    let message: ChatMessage

    var body: some View {
        HStack {
            if message.isOutgoing { Spacer(minLength: 60) }

            VStack(alignment: message.isOutgoing ? .trailing : .leading, spacing: 2) {
                Text(message.content)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .background(
                        message.isOutgoing ? Color.accentColor : Color(.systemGray5),
                        in: RoundedRectangle(cornerRadius: 16)
                    )
                    .foregroundStyle(message.isOutgoing ? .white : .primary)

                HStack(spacing: 4) {
                    Text(message.timestamp, style: .time)
                        .font(.caption2)
                        .foregroundStyle(.tertiary)

                    if message.isOutgoing {
                        deliveryIcon
                    }
                }
            }

            if !message.isOutgoing { Spacer(minLength: 60) }
        }
    }

    @ViewBuilder
    private var deliveryIcon: some View {
        switch message.deliveryStatus {
        case .pending:
            Image(systemName: "clock")
                .font(.caption2)
                .foregroundStyle(.tertiary)
        case .sent:
            Image(systemName: "checkmark")
                .font(.caption2)
                .foregroundStyle(.secondary)
        case .delivered, .read:
            Image(systemName: "checkmark.circle")
                .font(.caption2)
                .foregroundStyle(.secondary)
        case .failed:
            Image(systemName: "exclamationmark.triangle")
                .font(.caption2)
                .foregroundStyle(.red)
        }
    }
}
