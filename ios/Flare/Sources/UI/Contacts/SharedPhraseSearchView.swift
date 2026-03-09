import SwiftUI

struct SharedPhraseSearchView: View {
    @ObservedObject var viewModel: DiscoveryViewModel
    @State private var phrase = ""
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(spacing: 24) {
            VStack(spacing: 8) {
                Image(systemName: "text.quote")
                    .font(.system(size: 48))
                    .foregroundStyle(.green)

                Text("Shared Phrase")
                    .font(.title2.weight(.bold))

                Text("Enter a phrase that only you and your friend know — a shared memory, a place you visited together, an inside joke.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal)
            }
            .padding(.top, 24)

            switch viewModel.searchState {
            case .idle, .error:
                inputView

            case .searching:
                searchingView

            case .found(let identity, let method):
                foundView(identity: identity, method: method)
            }

            Spacer()

            Text("Your phrase never leaves this device. Only a mathematical fingerprint is broadcast.")
                .font(.caption)
                .foregroundStyle(.tertiary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)
                .padding(.bottom, 16)
        }
        .navigationTitle("Shared Phrase")
        .navigationBarTitleDisplayMode(.inline)
    }

    private var inputView: some View {
        VStack(spacing: 16) {
            TextField("Enter your shared phrase…", text: $phrase)
                .textFieldStyle(.roundedBorder)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
                .padding(.horizontal)

            if case .error(let message) = viewModel.searchState {
                Text(message)
                    .font(.caption)
                    .foregroundStyle(.red)
            }

            Button("Search the Mesh") {
                viewModel.startPhraseSearch(phrase)
            }
            .buttonStyle(.borderedProminent)
            .disabled(phrase.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        }
    }

    private var searchingView: some View {
        VStack(spacing: 16) {
            ProgressView()
                .controlSize(.large)

            Text("Searching the mesh network…")
                .font(.subheadline)
                .foregroundStyle(.secondary)

            Text("This may take a while. Keep Flare open and stay near other Flare users.")
                .font(.caption)
                .foregroundStyle(.tertiary)
                .multilineTextAlignment(.center)
                .padding(.horizontal)

            Button("Cancel", role: .destructive) {
                viewModel.cancelSearch()
            }
            .buttonStyle(.bordered)
        }
    }

    private func foundView(identity: DeviceIdentity, method: String) -> some View {
        VStack(spacing: 16) {
            Image(systemName: "checkmark.circle.fill")
                .font(.system(size: 48))
                .foregroundStyle(.green)

            Text("Contact Found!")
                .font(.title3.weight(.bold))

            Text("Device: \(identity.deviceId.prefix(16))")
                .font(.caption)
                .foregroundStyle(.secondary)

            Button("Add Contact") {
                try? FlareRepository.shared.addContact(
                    deviceId: identity.deviceId,
                    signingPublicKey: identity.signingPublicKey,
                    agreementPublicKey: identity.agreementPublicKey,
                    displayName: nil,
                    isVerified: false
                )
                dismiss()
            }
            .buttonStyle(.borderedProminent)
        }
    }
}
