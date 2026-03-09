import SwiftUI

struct PhoneSearchView: View {
    @ObservedObject var viewModel: DiscoveryViewModel
    @State private var myPhone = ""
    @State private var theirPhone = ""
    @State private var acceptedRisk = false
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ScrollView {
            VStack(spacing: 24) {
                VStack(spacing: 8) {
                    Image(systemName: "phone")
                        .font(.system(size: 48))
                        .foregroundStyle(.orange)

                    Text("Phone Number")
                        .font(.title2.weight(.bold))

                    Text("Both you and your friend must enter each other's phone numbers. This is a bilateral search — it only works if both sides participate.")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal)
                }
                .padding(.top, 24)

                // Security warning
                VStack(alignment: .leading, spacing: 8) {
                    Label("Security Notice", systemImage: "exclamationmark.triangle.fill")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.red)

                    Text("A well-funded adversary who knows YOUR phone number could potentially determine who you are trying to reach by testing all possible phone numbers (~100 million combinations).")
                        .font(.caption)
                        .foregroundStyle(.secondary)

                    Text("For maximum security, use Shared Phrase instead.")
                        .font(.caption.weight(.medium))
                        .foregroundStyle(.orange)
                }
                .padding()
                .background(Color.red.opacity(0.08), in: RoundedRectangle(cornerRadius: 12))
                .padding(.horizontal)

                switch viewModel.searchState {
                case .idle, .error:
                    inputView
                case .searching:
                    searchingView
                case .found(let identity, let method):
                    foundView(identity: identity, method: method)
                }

                Spacer(minLength: 32)
            }
        }
        .navigationTitle("Phone Search")
        .navigationBarTitleDisplayMode(.inline)
    }

    private var inputView: some View {
        VStack(spacing: 16) {
            VStack(alignment: .leading, spacing: 4) {
                Text("Your phone number")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                TextField("+1234567890", text: $myPhone)
                    .textFieldStyle(.roundedBorder)
                    .keyboardType(.phonePad)
            }
            .padding(.horizontal)

            VStack(alignment: .leading, spacing: 4) {
                Text("Their phone number")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                TextField("+1234567890", text: $theirPhone)
                    .textFieldStyle(.roundedBorder)
                    .keyboardType(.phonePad)
            }
            .padding(.horizontal)

            Toggle(isOn: $acceptedRisk) {
                Text("I understand the security implications")
                    .font(.caption)
            }
            .padding(.horizontal)

            if case .error(let message) = viewModel.searchState {
                Text(message)
                    .font(.caption)
                    .foregroundStyle(.red)
            }

            Button("Search the Mesh") {
                viewModel.startPhoneSearch(myPhone: myPhone, theirPhone: theirPhone)
            }
            .buttonStyle(.borderedProminent)
            .tint(.orange)
            .disabled(!acceptedRisk || myPhone.isEmpty || theirPhone.isEmpty)
        }
    }

    private var searchingView: some View {
        VStack(spacing: 16) {
            ProgressView()
                .controlSize(.large)

            Text("Searching the mesh network…")
                .font(.subheadline)
                .foregroundStyle(.secondary)

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
