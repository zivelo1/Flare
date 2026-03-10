import Foundation
import Combine

enum RouteDecisionType {
    case deliverLocally
    case forward
    case store
    case drop
}

struct IncomingMessageResult {
    let decision: RouteDecisionType
    let senderId: String?
    let plaintext: String?
    let messageId: String?
}

struct StoreStats {
    let totalMessages: Int
    let ownMessages: Int
    let activeRelayMessages: Int
    let totalBytes: Int
    let budgetBytes: Int
}

final class FlareRepository: @unchecked Sendable {
    static let shared = FlareRepository()

    private var node: FlareNode?
    private let lock = NSLock()

    @Published var contacts: [Contact] = []

    private init() {}

    func initialize() throws {
        let dbPath = Self.databasePath()
        let passphrase = Self.devicePassphrase()
        let flareNode = try FlareNode(dbPath: dbPath, passphrase: passphrase)
        lock.lock()
        node = flareNode
        lock.unlock()
        try refreshContacts()
    }

    private var safeNode: FlareNode {
        lock.lock()
        defer { lock.unlock() }
        guard let n = node else {
            fatalError("FlareRepository not initialized — call initialize() first")
        }
        return n
    }

    // MARK: - Identity

    func getDeviceId() -> String {
        safeNode.getDeviceId()
    }

    func getPublicIdentity() throws -> DeviceIdentity {
        let ffi = try safeNode.getPublicIdentity()
        return DeviceIdentity(
            deviceId: ffi.deviceId,
            signingPublicKey: ffi.signingPublicKey,
            agreementPublicKey: ffi.agreementPublicKey
        )
    }

    func getSafetyNumber() -> String {
        safeNode.getSafetyNumber()
    }

    func getPeerInfoBytes() -> Data {
        safeNode.getPeerInfoBytes()
    }

    // MARK: - Messaging

    func sendMessage(
        recipientDeviceId: String,
        recipientAgreementKey: Data,
        plaintext: String
    ) throws -> Data {
        let encrypted = try safeNode.createEncryptedMessage(
            recipientDeviceId: recipientDeviceId,
            recipientAgreementKey: recipientAgreementKey,
            plaintext: plaintext
        )

        let meshMessage = try safeNode.buildMeshMessage(
            recipientDeviceId: recipientDeviceId,
            encryptedPayload: encrypted,
            contentType: 1 // Text
        )

        let messageId = meshMessage.messageId
        try safeNode.queueOutboundMessage(
            messageId: messageId,
            recipientDeviceId: recipientDeviceId,
            encryptedPayload: encrypted
        )

        let chatMsg = FfiChatMessage(
            messageId: messageId,
            conversationId: recipientDeviceId,
            senderDeviceId: getDeviceId(),
            content: plaintext,
            timestampMs: UInt64(Date().timeIntervalSince1970 * 1000),
            isOutgoing: true,
            deliveryStatus: 1 // SENT
        )
        try safeNode.storeChatMessage(message: chatMsg)

        return meshMessage.serialized
    }

    func processIncomingMessage(_ rawData: Data) throws -> IncomingMessageResult {
        let decision = safeNode.routeIncoming(rawMessage: rawData)

        switch decision {
        case .deliverLocally:
            guard let parsed = try safeNode.parseMeshMessage(rawData: rawData) else {
                return IncomingMessageResult(decision: .deliverLocally, senderId: nil, plaintext: nil, messageId: nil)
            }

            let senderId = parsed.senderId
            let messageId = parsed.messageId
            let contactList = try safeNode.listContacts()
            guard let sender = contactList.first(where: { $0.deviceId == senderId }) else {
                return IncomingMessageResult(decision: .deliverLocally, senderId: senderId, plaintext: nil, messageId: messageId)
            }

            let plaintext = try safeNode.decryptIncomingMessage(
                senderDeviceId: senderId,
                senderAgreementKey: sender.agreementPublicKey,
                encryptedData: parsed.payload
            )

            if let plaintext = plaintext {
                let chatMsg = FfiChatMessage(
                    messageId: messageId,
                    conversationId: senderId,
                    senderDeviceId: senderId,
                    content: plaintext,
                    timestampMs: UInt64(Date().timeIntervalSince1970 * 1000),
                    isOutgoing: false,
                    deliveryStatus: 2 // DELIVERED
                )
                try safeNode.storeChatMessage(message: chatMsg)
            }

            return IncomingMessageResult(
                decision: .deliverLocally,
                senderId: senderId,
                plaintext: plaintext,
                messageId: messageId
            )

        case .forward:
            return IncomingMessageResult(decision: .forward, senderId: nil, plaintext: nil, messageId: nil)
        case .store:
            return IncomingMessageResult(decision: .store, senderId: nil, plaintext: nil, messageId: nil)
        case .dropDuplicate, .dropExpired, .dropHopLimit,
             .dropInvalidSignature, .dropTtlInflation,
             .dropHopCountDecrease, .dropSenderRateLimit:
            return IncomingMessageResult(decision: .drop, senderId: nil, plaintext: nil, messageId: nil)
        }
    }

    func prepareForRelay(_ rawData: Data) throws -> Data {
        try safeNode.prepareForRelay(rawMessage: rawData)
    }

    func createDeliveryAck(originalMessageId: String, senderDeviceId: String) throws -> Data {
        try safeNode.createDeliveryAck(originalMessageId: originalMessageId, senderDeviceId: senderDeviceId)
    }

    func createReadReceipt(originalMessageId: String, senderDeviceId: String) throws -> Data {
        try safeNode.createReadReceipt(originalMessageId: originalMessageId, senderDeviceId: senderDeviceId)
    }

    func getMessagesForPeer(_ deviceId: String) -> [Data] {
        safeNode.getMessagesForPeer(deviceId: deviceId)
    }

    func notifyPeerConnected(_ deviceId: String) {
        safeNode.notifyPeerConnected(deviceId: deviceId)
    }

    func getMessagesForConversation(_ conversationId: String) throws -> [ChatMessage] {
        let ffiMessages = try safeNode.getMessagesForConversation(conversationId: conversationId)
        return ffiMessages.map { msg in
            ChatMessage(
                messageId: msg.messageId,
                conversationId: msg.conversationId,
                senderDeviceId: msg.senderDeviceId,
                content: msg.content,
                timestamp: Date(timeIntervalSince1970: Double(msg.timestampMs) / 1000),
                isOutgoing: msg.isOutgoing,
                deliveryStatus: DeliveryStatus(rawValue: msg.deliveryStatus) ?? .pending
            )
        }
    }

    // MARK: - Contacts

    func addContact(
        deviceId: String,
        signingPublicKey: Data,
        agreementPublicKey: Data,
        displayName: String?,
        isVerified: Bool
    ) throws {
        let contact = FfiContact(
            deviceId: deviceId,
            signingPublicKey: signingPublicKey,
            agreementPublicKey: agreementPublicKey,
            displayName: displayName,
            isVerified: isVerified
        )
        try safeNode.addContact(contact: contact)
        try refreshContacts()
    }

    @discardableResult
    func refreshContacts() throws -> [Contact] {
        let ffiContacts = try safeNode.listContacts()
        let mapped = ffiContacts.map { ffi in
            Contact(
                identity: DeviceIdentity(
                    deviceId: ffi.deviceId,
                    signingPublicKey: ffi.signingPublicKey,
                    agreementPublicKey: ffi.agreementPublicKey
                ),
                displayName: ffi.displayName,
                isVerified: ffi.isVerified,
                lastSeen: Date()
            )
        }
        DispatchQueue.main.async {
            self.contacts = mapped
        }
        return mapped
    }

    // MARK: - Neighborhood

    func recordNeighborhoodPeer(_ shortId: Data) {
        safeNode.recordNeighborhoodPeer(shortId: shortId)
    }

    func exportNeighborhoodBitmap() -> Data {
        safeNode.exportNeighborhoodBitmap()
    }

    func processRemoteNeighborhood(_ remoteBitmap: Data) -> String {
        safeNode.processRemoteNeighborhood(remoteBitmap: remoteBitmap)
    }

    // MARK: - Rendezvous

    func startPassphraseSearch(_ passphrase: String) throws -> Data {
        try safeNode.startPassphraseSearch(passphrase: passphrase)
    }

    func startPhoneSearch(myPhone: String, theirPhone: String) throws -> Data {
        try safeNode.startPhoneSearch(myPhone: myPhone, theirPhone: theirPhone)
    }

    func registerMyPhone(_ phoneNumber: String) throws {
        try safeNode.registerMyPhone(phoneNumber: phoneNumber)
    }

    func importPhoneContacts(myPhone: String, contacts: [String]) throws -> UInt32 {
        try safeNode.importPhoneContacts(myPhone: myPhone, contacts: contacts)
    }

    func cancelSearch(_ tokenHex: String) throws {
        try safeNode.cancelSearch(tokenHex: tokenHex)
    }

    func buildRendezvousBroadcasts() throws -> [Data] {
        try safeNode.buildRendezvousBroadcasts()
    }

    func processRendezvousMessage(_ rawPayload: Data, contentType: UInt8) throws -> FfiDiscoveredContact? {
        try safeNode.processRendezvousMessage(rawPayload: rawPayload, contentType: contentType)
    }

    func processRendezvousRequest(_ rawPayload: Data, senderDeviceId: String) throws -> Data? {
        try safeNode.processRendezvousRequest(rawPayload: rawPayload, senderDeviceId: senderDeviceId)
    }

    func activeSearchCount() -> UInt32 {
        safeNode.activeSearchCount()
    }

    // MARK: - Store Stats

    func getStoreStats() -> StoreStats {
        let ffi = safeNode.getStoreStats()
        return StoreStats(
            totalMessages: Int(ffi.totalMessages),
            ownMessages: Int(ffi.ownMessages),
            activeRelayMessages: Int(ffi.activeRelayMessages),
            totalBytes: Int(ffi.totalBytes),
            budgetBytes: Int(ffi.budgetBytes)
        )
    }

    func getMeshStatus() -> MeshStatus {
        let ffi = safeNode.getMeshStatus()
        return MeshStatus(
            isActive: true,
            connectedPeerCount: Int(ffi.connectedPeers),
            discoveredPeerCount: Int(ffi.discoveredPeers),
            storedMessageCount: Int(ffi.storedMessages),
            messagesRelayed: Int(ffi.messagesRelayed)
        )
    }

    func pruneExpiredMessages() -> Int {
        Int(safeNode.pruneExpiredMessages())
    }

    // MARK: - Duress

    func setDuressPassphrase(_ passphrase: String) throws {
        try safeNode.setDuressPassphrase(passphrase: passphrase)
    }

    func hasDuressPassphrase() throws -> Bool {
        try safeNode.hasDuressPassphrase()
    }

    func checkDuressPassphrase(_ passphrase: String) throws -> Bool {
        try safeNode.checkDuressPassphrase(passphrase: passphrase)
    }

    // MARK: - Private Helpers

    private static func databasePath() -> String {
        let documentsDir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
        return documentsDir.appendingPathComponent("flare.db").path
    }

    private static func devicePassphrase() -> String {
        let keychainKey = "com.flare.mesh.device-passphrase"

        // Try to load existing passphrase from Keychain
        if let existing = loadFromKeychain(key: keychainKey) {
            return existing
        }

        // Generate and store a new passphrase
        var bytes = [UInt8](repeating: 0, count: 32)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        let passphrase = Data(bytes).base64EncodedString()

        saveToKeychain(key: keychainKey, value: passphrase)
        return passphrase
    }

    private static func loadFromKeychain(key: String) -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: key,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]

        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        guard status == errSecSuccess, let data = result as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    private static func saveToKeychain(key: String, value: String) {
        guard let data = value.data(using: .utf8) else { return }
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: key,
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]

        SecItemDelete(query as CFDictionary)
        SecItemAdd(query as CFDictionary, nil)
    }
}
