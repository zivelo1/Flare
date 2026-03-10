import SwiftUI

struct DuressSettingsView: View {
    @ObservedObject var viewModel: SettingsViewModel

    @State private var passphrase = ""
    @State private var confirmPassphrase = ""
    @State private var showRemoveConfirmation = false
    @State private var errorMessage: String?

    var body: some View {
        List {
            explanationSection

            if viewModel.hasDuressPin {
                activeSection
            } else {
                setupSection
            }
        }
        .navigationTitle("Duress PIN")
        .alert("Remove Duress PIN", isPresented: $showRemoveConfirmation) {
            Button("Remove", role: .destructive) {
                viewModel.clearDuressPassphrase()
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This will disable plausible deniability. Anyone who forces you to unlock Flare will see your real conversations.")
        }
    }

    // MARK: - Explanation

    private var explanationSection: some View {
        Section {
            HStack(spacing: 16) {
                Image(systemName: "shield.lefthalf.filled")
                    .font(.system(size: 32))
                    .foregroundStyle(.orange)

                VStack(alignment: .leading, spacing: 4) {
                    Text("Plausible Deniability")
                        .font(.subheadline.weight(.semibold))

                    Text("If you are forced to unlock Flare, entering the duress PIN opens a decoy database with innocent messages. Your real conversations remain hidden and encrypted.")
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
                    Text("Duress PIN Active")
                        .font(.subheadline.weight(.semibold))

                    Text("A decoy database will be shown when the duress PIN is entered.")
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
                    Text("Remove Duress PIN")
                    Spacer()
                }
            }
        }
    }

    // MARK: - Setup State

    private var setupSection: some View {
        Section {
            SecureField("Passphrase", text: $passphrase)
                .textContentType(.newPassword)

            SecureField("Confirm Passphrase", text: $confirmPassphrase)
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

            HStack(spacing: 16) {
                Image(systemName: "exclamationmark.triangle")
                    .font(.system(size: 24))
                    .foregroundStyle(.yellow)

                VStack(alignment: .leading, spacing: 4) {
                    Text("Important")
                        .font(.caption.weight(.semibold))

                    Text("Choose a passphrase you can remember under stress. It must be different from your main passphrase. If you forget it, there is no way to recover it.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            .padding(.vertical, 4)

            Button {
                setDuressPin()
            } label: {
                HStack {
                    Spacer()
                    if viewModel.duressStatus == .setting {
                        ProgressView()
                            .controlSize(.small)
                    }
                    Text("Set Duress PIN")
                        .fontWeight(.semibold)
                    Spacer()
                }
            }
            .disabled(!isValid || viewModel.duressStatus == .setting)
        }
    }

    // MARK: - Validation

    private var isValid: Bool {
        passphrase.count >= 4 && passphrase == confirmPassphrase
    }

    private func setDuressPin() {
        guard passphrase.count >= 4 else {
            errorMessage = "Passphrase must be at least 4 characters."
            return
        }

        guard passphrase == confirmPassphrase else {
            errorMessage = "Passphrases do not match."
            return
        }

        errorMessage = nil
        viewModel.setDuressPassphrase(passphrase)
        passphrase = ""
        confirmPassphrase = ""
    }
}
