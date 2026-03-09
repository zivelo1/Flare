import SwiftUI
import AVFoundation

struct QRScannerView: View {
    @ObservedObject var contactsVM: ContactsViewModel
    @Environment(\.dismiss) private var dismiss
    @State private var scannedCode: String?
    @State private var showPermissionAlert = false

    var body: some View {
        ZStack {
            QRCameraView { code in
                guard scannedCode == nil else { return }
                scannedCode = code
                contactsVM.addContactFromQr(code)
                dismiss()
            }
            .ignoresSafeArea()

            VStack {
                Spacer()

                RoundedRectangle(cornerRadius: 16)
                    .stroke(Color.white, lineWidth: 3)
                    .frame(width: 250, height: 250)

                Spacer()

                Text("Point your camera at a Flare QR code")
                    .font(.subheadline)
                    .foregroundStyle(.white)
                    .padding()
                    .background(.ultraThinMaterial, in: Capsule())
                    .padding(.bottom, 48)
            }
        }
        .navigationTitle("Scan QR Code")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            switch AVCaptureDevice.authorizationStatus(for: .video) {
            case .notDetermined:
                AVCaptureDevice.requestAccess(for: .video) { granted in
                    if !granted {
                        DispatchQueue.main.async { showPermissionAlert = true }
                    }
                }
            case .denied, .restricted:
                showPermissionAlert = true
            default:
                break
            }
        }
        .alert("Camera Access Required", isPresented: $showPermissionAlert) {
            Button("Open Settings") {
                if let url = URL(string: UIApplication.openSettingsURLString) {
                    UIApplication.shared.open(url)
                }
            }
            Button("Cancel", role: .cancel) { dismiss() }
        } message: {
            Text("Flare needs camera access to scan QR codes. Enable it in Settings.")
        }
    }
}

struct QRCameraView: UIViewControllerRepresentable {
    let onCodeScanned: (String) -> Void

    func makeUIViewController(context: Context) -> QRScannerController {
        let controller = QRScannerController()
        controller.onCodeScanned = onCodeScanned
        return controller
    }

    func updateUIViewController(_ uiViewController: QRScannerController, context: Context) {}
}

class QRScannerController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    var onCodeScanned: ((String) -> Void)?
    private var captureSession: AVCaptureSession?

    override func viewDidLoad() {
        super.viewDidLoad()

        let session = AVCaptureSession()
        guard let device = AVCaptureDevice.default(for: .video),
              let input = try? AVCaptureDeviceInput(device: device) else { return }

        if session.canAddInput(input) {
            session.addInput(input)
        }

        let output = AVCaptureMetadataOutput()
        if session.canAddOutput(output) {
            session.addOutput(output)
            output.setMetadataObjectsDelegate(self, queue: .main)
            output.metadataObjectTypes = [.qr]
        }

        let previewLayer = AVCaptureVideoPreviewLayer(session: session)
        previewLayer.frame = view.layer.bounds
        previewLayer.videoGravity = .resizeAspectFill
        view.layer.addSublayer(previewLayer)

        captureSession = session
        DispatchQueue.global(qos: .userInitiated).async {
            session.startRunning()
        }
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        captureSession?.stopRunning()
    }

    func metadataOutput(
        _ output: AVCaptureMetadataOutput,
        didOutput metadataObjects: [AVMetadataObject],
        from connection: AVCaptureConnection
    ) {
        guard let object = metadataObjects.first as? AVMetadataMachineReadableCodeObject,
              object.type == .qr,
              let value = object.stringValue else { return }

        // Validate QR format
        let parts = value.split(separator: Character(Constants.qrDataSeparator))
        guard parts.count >= Constants.qrMinFields,
              parts[1].count == Constants.hexPublicKeyLength,
              parts[2].count == Constants.hexPublicKeyLength else { return }

        captureSession?.stopRunning()
        onCodeScanned?(value)
    }
}
