import SwiftUI

struct FindContactView: View {
    @ObservedObject var discoveryVM: DiscoveryViewModel

    var body: some View {
        List {
            Section {
                Text("Find someone you know on the Flare mesh network without needing internet or servers.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }

            Section("Recommended") {
                NavigationLink(value: "phrase-search") {
                    DiscoveryOptionRow(
                        icon: "text.quote",
                        title: "Shared Phrase",
                        subtitle: "Both enter a phrase only you two know — a shared memory, a place, an inside joke.",
                        iconColor: .green
                    )
                }
            }

            Section("Remote Contact") {
                NavigationLink(value: "qr-display") {
                    DiscoveryOptionRow(
                        icon: "square.and.arrow.up",
                        title: "Share Identity Link",
                        subtitle: "Send your Flare identity via SMS, WhatsApp, or any app. Works at any distance.",
                        iconColor: .purple
                    )
                }
            }

            Section("Other Methods") {
                NavigationLink(value: "qr-scanner") {
                    DiscoveryOptionRow(
                        icon: "qrcode.viewfinder",
                        title: "QR Code",
                        subtitle: "Scan each other's QR code when meeting in person.",
                        iconColor: .blue
                    )
                }

                NavigationLink(value: "phone-search") {
                    DiscoveryOptionRow(
                        icon: "phone",
                        title: "Phone Number",
                        subtitle: "Enter each other's phone numbers. Both must participate.",
                        iconColor: .orange
                    )
                }
            }
        }
        .navigationTitle("Find Contact")
    }
}

struct DiscoveryOptionRow: View {
    let icon: String
    let title: String
    let subtitle: String
    let iconColor: Color

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
                .font(.title2)
                .foregroundStyle(iconColor)
                .frame(width: 40)

            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.body.weight(.medium))

                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(3)
            }
        }
        .padding(.vertical, 4)
    }
}
