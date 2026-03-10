import SwiftUI

struct ChatView: View {
    let conversationId: String
    @ObservedObject var viewModel: ChatViewModel
    @State private var messageText = ""
    @State private var sendButtonScale: CGFloat = Constants.sendButtonScaleNormal
    @State private var showImageCapture = false
    @State private var capturedImage: UIImage?
    @State private var showImagePreview = false

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
                                .transition(
                                    .asymmetric(
                                        insertion: .move(edge: .bottom).combined(with: .opacity),
                                        removal: .opacity
                                    )
                                )
                        }
                    }
                    .padding()
                }
                .onChange(of: viewModel.currentMessages.count) { _ in
                    if let lastId = viewModel.currentMessages.last?.messageId {
                        withAnimation(.spring(response: Constants.bubbleSpringResponse, dampingFraction: Constants.bubbleDampingFraction)) {
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
        .fullScreenCover(isPresented: $showImageCapture) {
            ImageCaptureView(capturedImage: $capturedImage)
                .ignoresSafeArea()
        }
        .onChange(of: capturedImage) { _ in
            if capturedImage != nil {
                showImagePreview = true
            }
        }
        .sheet(isPresented: $showImagePreview) {
            if let image = capturedImage {
                ImagePreviewSheet(
                    image: image,
                    onSend: {
                        showImagePreview = false
                        capturedImage = nil
                        viewModel.sendMessage(conversationId: conversationId, text: "[Photo]")
                    },
                    onCancel: {
                        showImagePreview = false
                        capturedImage = nil
                    }
                )
                .presentationDetents([.medium, .large])
            }
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
            // Camera button
            Button {
                HapticManager.buttonTap()
                ImageCaptureView.checkCameraPermission { granted in
                    if granted {
                        showImageCapture = true
                    }
                }
            } label: {
                Image(systemName: "camera")
                    .font(.system(size: 20))
                    .foregroundStyle(Color(.secondaryLabel))
                    .frame(width: 32, height: 32)
            }

            TextField("Message", text: $messageText, axis: .vertical)
                .textFieldStyle(.plain)
                .lineLimit(1...4)
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(Color(.systemGray6), in: RoundedRectangle(cornerRadius: 20))

            if messageText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                VoiceRecordButton { fileURL in
                    viewModel.sendMessage(conversationId: conversationId, text: "[Voice Message]")
                }
            } else {
                Button {
                    HapticManager.messageSent()
                    withAnimation(.spring(response: 0.15, dampingFraction: 0.5)) {
                        sendButtonScale = Constants.sendButtonScalePressed
                    }
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) {
                        withAnimation(.spring(response: 0.15, dampingFraction: 0.5)) {
                            sendButtonScale = Constants.sendButtonScaleNormal
                        }
                    }
                    sendMessage()
                } label: {
                    Image(systemName: "arrow.up.circle.fill")
                        .font(.system(size: 32))
                        .foregroundStyle(Color.accentColor)
                        .scaleEffect(sendButtonScale)
                }
                .disabled(messageText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        }
        .padding(.horizontal)
        .padding(.vertical, 8)
    }

    private func sendMessage() {
        let text = messageText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        messageText = ""
        withAnimation(.spring(response: Constants.bubbleSpringResponse, dampingFraction: Constants.bubbleDampingFraction)) {
            viewModel.sendMessage(conversationId: conversationId, text: text)
        }
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
        case .delivered:
            Image(systemName: "checkmark.circle")
                .font(.caption2)
                .foregroundStyle(.secondary)
        case .read:
            Image(systemName: "checkmark.circle.fill")
                .font(.caption2)
                .foregroundStyle(Color(red: 0.31, green: 0.76, blue: 0.97))
        case .failed:
            Image(systemName: "exclamationmark.triangle")
                .font(.caption2)
                .foregroundStyle(.red)
        }
    }
}
