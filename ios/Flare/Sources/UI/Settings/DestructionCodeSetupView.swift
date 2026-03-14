import SwiftUI
import CryptoKit

struct DestructionCodeSetupView: View {
    @AppStorage(Constants.prefsKeyUnlockCodeHash) private var unlockCodeHash: String = ""
    @AppStorage(Constants.prefsKeyDestructionCodeHash) private var destructionCodeHash: String = ""

    @State private var unlockCode = ""
    @State private var destructionCode = ""
    @State private var confirmDestructionCode = ""
    @State private var showRemoveConfirmation = false
    @State private var errorMessage: String?
    @State private var showSaveSuccess = false

    private var isConfigured: Bool {
        !unlockCodeHash.isEmpty && !destructionCodeHash.isEmpty
    }

    var body: some View {
        List {
            explanationSection

            if isConfigured {
                activeSection
            } else {
                setupSection
            }
        }
        .navigationTitle("Destruction Code")
        .alert("Remove Destruction Code", isPresented: $showRemoveConfirmation) {
            Button("Remove", role: .destructive) {
                removeDestructionCode()
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This will remove the lock screen and destruction code. The app will no longer require a code on launch.")
        }
    }

    // MARK: - Explanation

    private var explanationSection: some View {
        Section {
            HStack(spacing: 16) {
                Image(systemName: "exclamationmark.triangle.fill")
                    .font(.system(size: 32))
                    .foregroundStyle(.red)

                VStack(alignment: .leading, spacing: 4) {
                    Text("Emergency Data Wipe")
                        .font(.subheadline.weight(.semibold))

                    Text("Set an unlock code and a separate destruction code. On every app launch, you must enter a code or use biometrics. Entering the destruction code permanently erases ALL data including messages, contacts, and your identity. This action is irreversible.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            .padding(.vertical, 4)
        }
    }

    // MARK: - Active State

    private var activeSection: some View {
        Section {
            HStack(spacing: 16) {
                Image(systemName: "checkmark.circle.fill")
                    .font(.system(size: 28))
                    .foregroundStyle(.green)

                VStack(alignment: .leading, spacing: 4) {
                    Text("Destruction Code Active")
                        .font(.subheadline.weight(.semibold))

                    Text("The app is protected with a lock screen. Biometric authentication is available as the primary unlock method.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            .padding(.vertical, 4)

            Button(role: .destructive) {
                showRemoveConfirmation = true
            } label: {
                HStack {
                    Spacer()
                    Text("Remove Destruction Code")
                    Spacer()
                }
            }
        }
    }

    // MARK: - Setup State

    private var setupSection: some View {
        Section {
            SecureField("Unlock Code (min \(Constants.minCodeLength) characters)", text: $unlockCode)
                .textContentType(.newPassword)

            SecureField("Destruction Code", text: $destructionCode)
                .textContentType(.newPassword)

            SecureField("Confirm Destruction Code", text: $confirmDestructionCode)
                .textContentType(.newPassword)

            if let errorMessage {
                HStack(spacing: 8) {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .foregroundStyle(.red)

                    Text(errorMessage)
                        .font(.caption)
                        .foregroundStyle(.red)
                }
            }

            if showSaveSuccess {
                HStack(spacing: 8) {
                    Image(systemName: "checkmark.circle.fill")
                        .foregroundStyle(.green)

                    Text("Destruction code configured successfully.")
                        .font(.caption)
                        .foregroundStyle(.green)
                }
            }

            HStack(spacing: 16) {
                Image(systemName: "exclamationmark.triangle")
                    .font(.system(size: 24))
                    .foregroundStyle(.yellow)

                VStack(alignment: .leading, spacing: 4) {
                    Text("Warning")
                        .font(.caption.weight(.semibold))

                    Text("The destruction code will permanently and irreversibly erase all messages, contacts, and your device identity. Choose codes you can remember under pressure. The unlock and destruction codes must be different.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            .padding(.vertical, 4)

            Button {
                saveDestructionCode()
            } label: {
                HStack {
                    Spacer()
                    Text("Save")
                        .fontWeight(.semibold)
                    Spacer()
                }
            }
            .disabled(!isInputValid)
        }
    }

    // MARK: - Validation

    private var isInputValid: Bool {
        unlockCode.count >= Constants.minCodeLength
            && destructionCode.count >= Constants.minCodeLength
            && destructionCode == confirmDestructionCode
            && unlockCode != destructionCode
    }

    // MARK: - Actions

    private func saveDestructionCode() {
        guard unlockCode.count >= Constants.minCodeLength else {
            errorMessage = "Unlock code must be at least \(Constants.minCodeLength) characters."
            return
        }

        guard destructionCode.count >= Constants.minCodeLength else {
            errorMessage = "Destruction code must be at least \(Constants.minCodeLength) characters."
            return
        }

        guard destructionCode == confirmDestructionCode else {
            errorMessage = "Destruction codes do not match."
            return
        }

        guard unlockCode != destructionCode else {
            errorMessage = "Unlock code and destruction code must be different."
            return
        }

        errorMessage = nil
        unlockCodeHash = sha256Hash(unlockCode)
        destructionCodeHash = sha256Hash(destructionCode)

        unlockCode = ""
        destructionCode = ""
        confirmDestructionCode = ""
        showSaveSuccess = true
    }

    private func removeDestructionCode() {
        let defaults = UserDefaults.standard
        defaults.removeObject(forKey: Constants.prefsKeyUnlockCodeHash)
        defaults.removeObject(forKey: Constants.prefsKeyDestructionCodeHash)
        unlockCodeHash = ""
        destructionCodeHash = ""
    }

    // MARK: - Hashing

    private func sha256Hash(_ input: String) -> String {
        let data = Data(input.utf8)
        let digest = SHA256.hash(data: data)
        return digest.map { String(format: "%02x", $0) }.joined()
    }
}
