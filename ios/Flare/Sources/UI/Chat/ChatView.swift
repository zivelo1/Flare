import SwiftUI
import AVFoundation

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
                        let imageToSend = image
                        showImagePreview = false
                        capturedImage = nil
                        viewModel.sendImageMessage(conversationId: conversationId, image: imageToSend)
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
                    viewModel.sendVoiceMessage(conversationId: conversationId, audioURL: fileURL)
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

// MARK: - Message Bubble with Media Support

struct MessageBubble: View {
    let message: ChatMessage

    var body: some View {
        HStack {
            if message.isOutgoing { Spacer(minLength: 60) }

            VStack(alignment: message.isOutgoing ? .trailing : .leading, spacing: 2) {
                bubbleContent
                    .background(
                        message.isOutgoing ? Color.accentColor : Color(.systemGray5),
                        in: RoundedRectangle(cornerRadius: 16)
                    )

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
    private var bubbleContent: some View {
        if message.content.hasPrefix(Constants.mediaPrefixImage) {
            imageBubble
        } else if message.content.hasPrefix(Constants.mediaPrefixVoice) {
            voiceBubble
        } else {
            Text(message.content)
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .foregroundStyle(message.isOutgoing ? .white : .primary)
        }
    }

    @ViewBuilder
    private var imageBubble: some View {
        let base64String = String(message.content.dropFirst(Constants.mediaPrefixImage.count))
        if let imageData = Data(base64Encoded: base64String),
           let uiImage = UIImage(data: imageData) {
            Image(uiImage: uiImage)
                .resizable()
                .scaledToFit()
                .frame(maxWidth: 220)
                .clipShape(RoundedRectangle(cornerRadius: 16))
        } else {
            // Sent image — only prefix stored locally, no Base64 data
            HStack(spacing: 6) {
                Image(systemName: "photo")
                Text("Photo")
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .foregroundStyle(message.isOutgoing ? .white : .primary)
        }
    }

    @ViewBuilder
    private var voiceBubble: some View {
        let base64String = String(message.content.dropFirst(Constants.mediaPrefixVoice.count))
        if !base64String.isEmpty, let audioData = Data(base64Encoded: base64String) {
            VoiceMessagePlayer(audioData: audioData, isOutgoing: message.isOutgoing)
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
        } else {
            // Sent voice — only prefix stored locally
            HStack(spacing: 6) {
                Image(systemName: "waveform")
                Text("Voice Message")
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .foregroundStyle(message.isOutgoing ? .white : .primary)
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

// MARK: - Voice Message Playback

struct VoiceMessagePlayer: View {
    let audioData: Data
    let isOutgoing: Bool

    @StateObject private var player = AudioPlayerModel()

    var body: some View {
        HStack(spacing: 8) {
            Button {
                if player.isPlaying {
                    player.stop()
                } else {
                    player.play(data: audioData)
                }
            } label: {
                Image(systemName: player.isPlaying ? "stop.fill" : "play.fill")
                    .font(.system(size: 18))
                    .foregroundStyle(isOutgoing ? .white : .primary)
                    .frame(width: 28, height: 28)
            }

            // Simple progress bar
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    RoundedRectangle(cornerRadius: 2)
                        .fill(isOutgoing ? Color.white.opacity(0.3) : Color(.systemGray3))
                        .frame(height: 4)

                    RoundedRectangle(cornerRadius: 2)
                        .fill(isOutgoing ? Color.white : Constants.flareOrange)
                        .frame(width: geo.size.width * player.progress, height: 4)
                }
                .frame(maxHeight: .infinity, alignment: .center)
            }
            .frame(maxWidth: 120, maxHeight: 20)

            Text(player.durationString)
                .font(.system(.caption2, design: .monospaced))
                .foregroundStyle(isOutgoing ? .white.opacity(0.7) : .secondary)
        }
    }
}

@MainActor
final class AudioPlayerModel: ObservableObject {
    @Published var isPlaying = false
    @Published var progress: CGFloat = 0
    @Published var durationString = "0:00"

    private var audioPlayer: AVAudioPlayer?
    private var progressTimer: Timer?

    func play(data: Data) {
        stop()

        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playback, mode: .default)
            try session.setActive(true)

            audioPlayer = try AVAudioPlayer(data: data)
            audioPlayer?.prepareToPlay()
            audioPlayer?.play()
            isPlaying = true

            updateDuration()
            progressTimer = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { [weak self] _ in
                Task { @MainActor in
                    self?.updateProgress()
                }
            }
        } catch {
            isPlaying = false
        }
    }

    func stop() {
        audioPlayer?.stop()
        audioPlayer = nil
        isPlaying = false
        progress = 0
        progressTimer?.invalidate()
        progressTimer = nil
    }

    private func updateProgress() {
        guard let player = audioPlayer else {
            stop()
            return
        }
        if !player.isPlaying {
            stop()
            return
        }
        let duration = player.duration
        guard duration > 0 else { return }
        progress = CGFloat(player.currentTime / duration)
    }

    private func updateDuration() {
        guard let player = audioPlayer else { return }
        let seconds = Int(player.duration)
        durationString = "\(seconds / 60):\(String(format: "%02d", seconds % 60))"
    }
}
