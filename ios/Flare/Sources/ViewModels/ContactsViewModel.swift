import Foundation

@MainActor
final class ContactsViewModel: ObservableObject {
    @Published var contacts: [Contact] = []
    @Published var meshStatus = MeshStatus()

    private let repo = FlareRepository.shared

    init() {
        repo.$contacts
            .receive(on: DispatchQueue.main)
            .assign(to: &$contacts)

        MeshService.shared.$meshStatus
            .receive(on: DispatchQueue.main)
            .assign(to: &$meshStatus)
    }

    func refreshContacts() {
        _ = try? repo.refreshContacts()
    }

    func getMyPublicIdentity() -> DeviceIdentity? {
        try? repo.getPublicIdentity()
    }

    func getSafetyNumber() -> String {
        repo.getSafetyNumber()
    }

    func generateQrData() -> String {
        guard let identity = getMyPublicIdentity() else { return "" }
        let signingHex = identity.signingPublicKey.map { String(format: "%02x", $0) }.joined()
        let agreementHex = identity.agreementPublicKey.map { String(format: "%02x", $0) }.joined()
        return [identity.deviceId, signingHex, agreementHex].joined(separator: Constants.qrDataSeparator)
    }

    func addContactFromQr(_ qrData: String) {
        let parts = qrData.split(separator: Character(Constants.qrDataSeparator)).map(String.init)
        guard parts.count >= Constants.qrMinFields,
              parts[1].count == Constants.hexPublicKeyLength,
              parts[2].count == Constants.hexPublicKeyLength else { return }

        let deviceId = parts[0]
        let signingKey = Data(hexString: parts[1])
        let agreementKey = Data(hexString: parts[2])
        let displayName = parts.count > 3 ? parts[3] : nil

        guard let signingKey = signingKey, let agreementKey = agreementKey else { return }

        try? repo.addContact(
            deviceId: deviceId,
            signingPublicKey: signingKey,
            agreementPublicKey: agreementKey,
            displayName: displayName,
            isVerified: true
        )
    }
}

extension Data {
    init?(hexString: String) {
        let len = hexString.count / 2
        var data = Data(capacity: len)
        var index = hexString.startIndex
        for _ in 0..<len {
            let nextIndex = hexString.index(index, offsetBy: 2)
            guard let byte = UInt8(hexString[index..<nextIndex], radix: 16) else { return nil }
            data.append(byte)
            index = nextIndex
        }
        self = data
    }
}
