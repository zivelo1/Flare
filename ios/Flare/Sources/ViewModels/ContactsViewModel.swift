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

    /// Generates a shareable deep link URL for the local device identity.
    /// Format: flare://add?id=<deviceId>&sk=<signingKey>&ak=<agreementKey>
    func generateShareLink() -> String {
        guard let identity = getMyPublicIdentity() else { return "" }
        let signingHex = identity.signingPublicKey.map { String(format: "%02x", $0) }.joined()
        let agreementHex = identity.agreementPublicKey.map { String(format: "%02x", $0) }.joined()

        var components = URLComponents()
        components.scheme = Constants.deepLinkScheme
        components.host = Constants.deepLinkHostAdd
        components.queryItems = [
            URLQueryItem(name: Constants.deepLinkParamId, value: identity.deviceId),
            URLQueryItem(name: Constants.deepLinkParamSigningKey, value: signingHex),
            URLQueryItem(name: Constants.deepLinkParamAgreementKey, value: agreementHex),
        ]
        return components.string ?? ""
    }

    /// Adds a contact from an incoming deep link URL.
    /// Returns true if the contact was successfully added.
    func addContactFromLink(_ url: URL) -> Bool {
        guard let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
              components.scheme == Constants.deepLinkScheme,
              components.host == Constants.deepLinkHostAdd else {
            return false
        }

        let params = Dictionary(
            uniqueKeysWithValues: (components.queryItems ?? []).compactMap { item in
                item.value.map { (item.name, $0) }
            }
        )

        guard let deviceId = params[Constants.deepLinkParamId],
              let signingKeyHex = params[Constants.deepLinkParamSigningKey],
              let agreementKeyHex = params[Constants.deepLinkParamAgreementKey],
              signingKeyHex.count == Constants.hexPublicKeyLength,
              agreementKeyHex.count == Constants.hexPublicKeyLength,
              let signingKey = Data(hexString: signingKeyHex),
              let agreementKey = Data(hexString: agreementKeyHex) else {
            return false
        }

        let displayName = params[Constants.deepLinkParamName]

        do {
            try repo.addContact(
                deviceId: deviceId,
                signingPublicKey: signingKey,
                agreementPublicKey: agreementKey,
                displayName: displayName,
                isVerified: false  // Link-based = not verified (no in-person confirmation)
            )
            return true
        } catch {
            return false
        }
    }

    func deleteContact(deviceId: String) {
        try? repo.deleteContact(deviceId: deviceId)
    }

    func renameContact(deviceId: String, newName: String) {
        try? repo.updateContactDisplayName(deviceId: deviceId, displayName: newName)
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
