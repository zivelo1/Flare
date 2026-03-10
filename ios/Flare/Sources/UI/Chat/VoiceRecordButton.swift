import SwiftUI
import AVFoundation

/// Hold-to-record voice message button with live waveform visualization.
struct VoiceRecordButton: View {
    let onRecordingComplete: (URL) -> Void

    @StateObject private var recorder = VoiceRecorderModel()
    @State private var isHolding = false
    @State private var dragOffset: CGSize = .zero

    var body: some View {
        ZStack {
            if recorder.isRecording {
                recordingOverlay
                    .transition(.opacity)
            }

            recordButton
        }
    }

    @ViewBuilder
    private var recordButton: some View {
        Image(systemName: recorder.isRecording ? "mic.fill" : "mic")
            .font(.system(size: 22))
            .foregroundStyle(recorder.isRecording ? Color.red : Color(.secondaryLabel))
            .frame(width: 36, height: 36)
            .simultaneousGesture(
                LongPressGesture(minimumDuration: 0.3)
                    .onEnded { _ in
                        HapticManager.buttonTap()
                        recorder.startRecording()
                        withAnimation(.easeOut(duration: 0.2)) {
                            isHolding = true
                        }
                    }
                    .sequenced(before: DragGesture(minimumDistance: 0)
                        .onEnded { _ in
                            finishRecording()
                        }
                    )
            )
    }

    private var recordingOverlay: some View {
        HStack(spacing: 10) {
            Circle()
                .fill(Color.red)
                .frame(width: 10, height: 10)
                .opacity(recorder.redDotOpacity)

            Text(recorder.elapsedTimeString)
                .font(.system(.subheadline, design: .monospaced))
                .foregroundStyle(.primary)

            AudioWaveformView(
                levels: recorder.waveformLevels,
                barColor: .red
            )
            .frame(maxWidth: 120)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(Color(.secondarySystemBackground), in: Capsule())
    }

    private func finishRecording() {
        withAnimation(.easeOut(duration: 0.2)) {
            isHolding = false
        }
        if let url = recorder.stopRecording() {
            onRecordingComplete(url)
        }
    }
}

@MainActor
final class VoiceRecorderModel: ObservableObject {
    @Published var isRecording = false
    @Published var waveformLevels: [CGFloat] = []
    @Published var elapsedTimeString = "0:00"
    @Published var redDotOpacity: Double = 1.0

    private var audioRecorder: AVAudioRecorder?
    private var pollTimer: Timer?
    private var blinkTimer: Timer?
    private var startTime: Date?

    func startRecording() {
        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker])
            try session.setActive(true)
        } catch {
            return
        }

        let tempDir = FileManager.default.temporaryDirectory
        let fileName = "flare_voice_\(UUID().uuidString).\(Constants.voiceRecordingFormat)"
        let fileURL = tempDir.appendingPathComponent(fileName)

        let settings: [String: Any] = [
            AVFormatIDKey: Int(kAudioFormatMPEG4AAC),
            AVSampleRateKey: 44100,
            AVNumberOfChannelsKey: 1,
            AVEncoderAudioQualityKey: AVAudioQuality.high.rawValue
        ]

        do {
            audioRecorder = try AVAudioRecorder(url: fileURL, settings: settings)
            audioRecorder?.isMeteringEnabled = true
            audioRecorder?.record()
            isRecording = true
            startTime = Date()
            waveformLevels = []
            startPolling()
            startBlinking()
        } catch {
            return
        }
    }

    func stopRecording() -> URL? {
        guard let recorder = audioRecorder, recorder.isRecording else { return nil }
        let url = recorder.url
        recorder.stop()
        audioRecorder = nil
        isRecording = false
        stopPolling()
        stopBlinking()
        return url
    }

    private func startPolling() {
        pollTimer = Timer.scheduledTimer(
            withTimeInterval: Constants.voiceWaveformPollInterval,
            repeats: true
        ) { [weak self] _ in
            Task { @MainActor in
                self?.pollMeters()
            }
        }
    }

    private func stopPolling() {
        pollTimer?.invalidate()
        pollTimer = nil
    }

    private func startBlinking() {
        blinkTimer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self = self else { return }
                self.redDotOpacity = self.redDotOpacity < 0.5 ? 1.0 : 0.3
            }
        }
    }

    private func stopBlinking() {
        blinkTimer?.invalidate()
        blinkTimer = nil
        redDotOpacity = 1.0
    }

    private func pollMeters() {
        guard let recorder = audioRecorder, recorder.isRecording else { return }
        recorder.updateMeters()
        let power = recorder.averagePower(forChannel: 0)
        // Normalize from dB range (-60...0) to 0...1
        let normalized = CGFloat(max(0, (power + 60) / 60))
        waveformLevels.append(normalized)
        if waveformLevels.count > Constants.voiceWaveformBarCount {
            waveformLevels.removeFirst(waveformLevels.count - Constants.voiceWaveformBarCount)
        }

        if let start = startTime {
            let elapsed = Int(Date().timeIntervalSince(start))
            let minutes = elapsed / 60
            let seconds = elapsed % 60
            elapsedTimeString = "\(minutes):\(String(format: "%02d", seconds))"
        }
    }
}
