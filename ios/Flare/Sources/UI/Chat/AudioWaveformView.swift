import SwiftUI

/// Reusable waveform visualization from an array of amplitude levels (0.0 to 1.0).
struct AudioWaveformView: View {
    let levels: [CGFloat]
    var barColor: Color = Constants.flareOrange
    var barCount: Int = Constants.voiceWaveformBarCount

    var body: some View {
        HStack(spacing: 2) {
            ForEach(0..<displayLevels.count, id: \.self) { index in
                RoundedRectangle(cornerRadius: 1.5)
                    .fill(barColor)
                    .frame(width: 3, height: barHeight(for: displayLevels[index]))
            }
        }
    }

    private var displayLevels: [CGFloat] {
        if levels.isEmpty {
            return Array(repeating: 0.05, count: barCount)
        }
        if levels.count >= barCount {
            return Array(levels.suffix(barCount))
        }
        let padding = Array(repeating: CGFloat(0.05), count: barCount - levels.count)
        return padding + levels
    }

    private func barHeight(for level: CGFloat) -> CGFloat {
        let clamped = max(0.05, min(1.0, level))
        return 4 + clamped * 28
    }
}
