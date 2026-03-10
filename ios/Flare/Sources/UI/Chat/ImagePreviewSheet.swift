import SwiftUI

/// Preview sheet for a captured image before sending.
struct ImagePreviewSheet: View {
    let image: UIImage
    let onSend: () -> Void
    let onCancel: () -> Void

    var body: some View {
        VStack(spacing: 16) {
            HStack {
                Button(action: onCancel) {
                    Text("Cancel")
                        .foregroundStyle(Color(.label))
                }

                Spacer()

                Text("Preview")
                    .font(.headline)

                Spacer()

                Button(action: onSend) {
                    Text("Send")
                        .fontWeight(.semibold)
                        .foregroundStyle(Constants.flareOrange)
                }
            }
            .padding(.horizontal)
            .padding(.top, 16)

            Image(uiImage: image)
                .resizable()
                .scaledToFit()
                .clipShape(RoundedRectangle(cornerRadius: 16))
                .padding(.horizontal)

            Spacer()
        }
        .background(Color(.systemBackground))
    }
}
