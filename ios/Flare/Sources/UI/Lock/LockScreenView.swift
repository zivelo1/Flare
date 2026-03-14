import SwiftUI
import CryptoKit
import LocalAuthentication

struct LockScreenView: View {
    let unlockCodeHash: String
    let destructionCodeHash: String
    let onUnlocked: () -> Void
    let onDestruction: () -> Void

    @State private var enteredCode = ""
    @State private var errorMessage: String?
    @State private var shakeOffset: CGFloat = 0
    @State private var isProcessing = false

    var body: some View {
        VStack(spacing: 32) {
            Spacer()

            lockIcon

            Text("Flare is Locked")
                .font(.title2.weight(.bold))

            Text("Enter your unlock code or use biometrics.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 40)

            codeEntry

            if let errorMessage {
                Text(errorMessage)
                    .font(.caption)
                    .foregroundStyle(.red)
                    .transition(.opacity)
            }

            unlockButton

            biometricButton

            Spacer()
            Spacer()
        }
        .padding(.horizontal, 32)
        .background(Color(.systemBackground))
        .onAppear {
            authenticateWithBiometric()
        }
    }

    // MARK: - Subviews

    private var lockIcon: some View {
        ZStack {
            Circle()
                .fill(Constants.flareOrange.opacity(0.12))
                .frame(width: 100, height: 100)

            Image(systemName: "lock.fill")
                .font(.system(size: 40))
                .foregroundStyle(Constants.flareOrange)
        }
    }

    private var codeEntry: some View {
        SecureField("Enter code", text: $enteredCode)
            .textContentType(.password)
            .multilineTextAlignment(.center)
            .padding()
            .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12))
            .offset(x: shakeOffset)
            .onSubmit {
                verifyCode()
            }
    }

    private var unlockButton: some View {
        Button {
            verifyCode()
        } label: {
            HStack {
                if isProcessing {
                    ProgressView()
                        .controlSize(.small)
                        .tint(.white)
                }
                Text("Unlock")
                    .fontWeight(.semibold)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 14)
        }
        .buttonStyle(.borderedProminent)
        .tint(Constants.flareOrange)
        .disabled(enteredCode.isEmpty || isProcessing)
    }

    private var biometricButton: some View {
        Button {
            authenticateWithBiometric()
        } label: {
            Label(biometricLabel, systemImage: biometricIcon)
                .font(.subheadline)
                .foregroundStyle(Constants.flareOrange)
        }
    }

    // MARK: - Biometric Helpers

    private var biometricLabel: String {
        let context = LAContext()
        var error: NSError?
        guard context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &error) else {
            return "Use Biometrics"
        }
        switch context.biometryType {
        case .faceID:
            return "Use Face ID"
        case .touchID:
            return "Use Touch ID"
        case .opticID:
            return "Use Optic ID"
        @unknown default:
            return "Use Biometrics"
        }
    }

    private var biometricIcon: String {
        let context = LAContext()
        var error: NSError?
        guard context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &error) else {
            return "faceid"
        }
        switch context.biometryType {
        case .faceID:
            return "faceid"
        case .touchID:
            return "touchid"
        case .opticID:
            return "opticid"
        @unknown default:
            return "faceid"
        }
    }

    // MARK: - Authentication

    private func authenticateWithBiometric() {
        let context = LAContext()
        var error: NSError?
        guard context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &error) else {
            return
        }
        context.evaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, localizedReason: "Unlock Flare") { success, _ in
            DispatchQueue.main.async {
                if success {
                    onUnlocked()
                }
            }
        }
    }

    private func verifyCode() {
        guard !enteredCode.isEmpty else { return }

        isProcessing = true
        let hash = sha256Hash(enteredCode)

        if hash == unlockCodeHash {
            isProcessing = false
            onUnlocked()
        } else if hash == destructionCodeHash {
            isProcessing = false
            onDestruction()
        } else {
            errorMessage = "Wrong code. Try again."
            enteredCode = ""
            isProcessing = false
            triggerShake()
        }
    }

    private func triggerShake() {
        withAnimation(Animation.linear(duration: 0.06).repeatCount(5, autoreverses: true)) {
            shakeOffset = 10
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) {
            shakeOffset = 0
        }
    }

    // MARK: - Hashing

    private func sha256Hash(_ input: String) -> String {
        let data = Data(input.utf8)
        let digest = SHA256.hash(data: data)
        return digest.map { String(format: "%02x", $0) }.joined()
    }
}
