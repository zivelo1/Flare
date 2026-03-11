import SwiftUI
import CoreImage.CIFilterBuiltins

struct QRDisplayView: View {
    let qrData: String
    let safetyNumber: String
    let shareLink: String

    @State private var showShareSheet = false

    var body: some View {
        VStack(spacing: 24) {
            Text("Show this QR code to your contact so they can scan it.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal)

            if let image = generateQRCode(from: qrData) {
                Image(uiImage: image)
                    .interpolation(.none)
                    .resizable()
                    .scaledToFit()
                    .frame(width: 250, height: 250)
                    .padding()
                    .background(Color.white, in: RoundedRectangle(cornerRadius: 16))
                    .shadow(radius: 4)
            }

            VStack(spacing: 4) {
                Text("Safety Number")
                    .font(.caption)
                    .foregroundStyle(.secondary)

                Text(safetyNumber)
                    .font(.system(.body, design: .monospaced))
                    .foregroundStyle(.primary)
            }

            Text("Verify this number matches on both devices to confirm a secure connection.")
                .font(.caption)
                .foregroundStyle(.tertiary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)

            // Share identity link button
            Button {
                showShareSheet = true
            } label: {
                Label("Share My Identity Link", systemImage: "square.and.arrow.up")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .tint(.accentColor)
            .padding(.horizontal, 32)

            Text("Send this link via SMS, WhatsApp, or any app.\nYour friend taps it to add you as a contact.")
                .font(.caption)
                .foregroundStyle(.tertiary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)

            Spacer()
        }
        .padding(.top, 24)
        .navigationTitle("My QR Code")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    showShareSheet = true
                } label: {
                    Image(systemName: "square.and.arrow.up")
                }
            }
        }
        .sheet(isPresented: $showShareSheet) {
            let shareText = "Add me on Flare (encrypted mesh messaging):\n\(shareLink)\n\nDon't have Flare? Get it at https://github.com/zivelo1/Flare"
            ShareSheet(activityItems: [shareText])
        }
    }

    private func generateQRCode(from string: String) -> UIImage? {
        let context = CIContext()
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(string.utf8)
        filter.correctionLevel = "M"

        guard let outputImage = filter.outputImage else { return nil }
        let scale = 250.0 / outputImage.extent.width
        let scaledImage = outputImage.transformed(by: CGAffineTransform(scaleX: scale, y: scale))

        guard let cgImage = context.createCGImage(scaledImage, from: scaledImage.extent) else { return nil }
        return UIImage(cgImage: cgImage)
    }
}

/// UIKit share sheet wrapper for SwiftUI.
struct ShareSheet: UIViewControllerRepresentable {
    let activityItems: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: activityItems, applicationActivities: nil)
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}
