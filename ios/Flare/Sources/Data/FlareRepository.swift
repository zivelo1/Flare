import Foundation
import Combine
import UIKit

enum FlareMediaError: Error {
    case imageCompressionFailed
    case audioFileReadFailed
}

enum FlareRepositoryError: Error {
    case invalidInput
}

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
        try sendMessageInternal(
            recipientDeviceId: recipientDeviceId,
            recipientAgreementKey: recipientAgreementKey,
            plaintext: plaintext,
            contentType: Constants.contentTypeText
        )
    }

    /// Sends a voice message. The audio data is Base64-encoded and prefixed.
    func sendVoiceMessage(
        recipientDeviceId: String,
        recipientAgreementKey: Data,
        audioData: Data
    ) throws -> Data {
        let base64 = audioData.base64EncodedString()
        let plaintext = Constants.mediaPrefixVoice + base64
        return try sendMessageInternal(
            recipientDeviceId: recipientDeviceId,
            recipientAgreementKey: recipientAgreementKey,
            plaintext: plaintext,
            contentType: Constants.contentTypeVoiceMessage
        )
    }

    /// Sends an image message. The image is resized, compressed, Base64-encoded, and prefixed.
    func sendImageMessage(
        recipientDeviceId: String,
        recipientAgreementKey: Data,
        image: UIImage
    ) throws -> Data {
        let resized = Self.resizeImage(image, maxDimension: Constants.imageMaxDimension)
        guard let jpegData = resized.jpegData(compressionQuality: Constants.imageCompressQuality) else {
            throw FlareMediaError.imageCompressionFailed
        }
        let base64 = jpegData.base64EncodedString()
        let plaintext = Constants.mediaPrefixImage + base64
        return try sendMessageInternal(
            recipientDeviceId: recipientDeviceId,
            recipientAgreementKey: recipientAgreementKey,
            plaintext: plaintext,
            contentType: Constants.contentTypeImage
        )
    }

    private func sendMessageInternal(
        recipientDeviceId: String,
        recipientAgreementKey: Data,
        plaintext: String,
        contentType: UInt8
    ) throws -> Data {
        let encrypted = try safeNode.createEncryptedMessage(
            recipientDeviceId: recipientDeviceId,
            recipientAgreementKey: recipientAgreementKey,
            plaintext: plaintext
        )

        let meshMessage = try safeNode.buildMeshMessage(
            recipientDeviceId: recipientDeviceId,
            encryptedPayload: encrypted,
            contentType: contentType
        )

        let messageId = meshMessage.messageId
        try safeNode.queueOutboundMessage(
            messageId: messageId,
            recipientDeviceId: recipientDeviceId,
            encryptedPayload: encrypted
        )

        // Store a display-friendly version locally
        let displayContent: String
        switch contentType {
        case Constants.contentTypeVoiceMessage:
            displayContent = Constants.mediaPrefixVoice
        case Constants.contentTypeImage:
            displayContent = Constants.mediaPrefixImage
        default:
            displayContent = plaintext
        }

        let chatMsg = FfiChatMessage(
            messageId: messageId,
            conversationId: recipientDeviceId,
            senderDeviceId: getDeviceId(),
            content: displayContent,
            timestampMs: Int64(Date().timeIntervalSince1970 * 1000),
            isOutgoing: true,
            deliveryStatus: 1 // SENT
        )
        try safeNode.storeChatMessage(message: chatMsg)

        return meshMessage.serialized
    }

    /// Resizes an image so its longest side does not exceed maxDimension.
    private static func resizeImage(_ image: UIImage, maxDimension: CGFloat) -> UIImage {
        let size = image.size
        let longestSide = max(size.width, size.height)
        guard longestSide > maxDimension else { return image }

        let scale = maxDimension / longestSide
        let newSize = CGSize(width: size.width * scale, height: size.height * scale)

        let renderer = UIGraphicsImageRenderer(size: newSize)
        return renderer.image { _ in
            image.draw(in: CGRect(origin: .zero, size: newSize))
        }
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
                    timestampMs: Int64(Date().timeIntervalSince1970 * 1000),
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
             .dropHopCountDecrease, .dropSenderRateLimit,
             .dropParseError:
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

    func deleteContact(deviceId: String) throws {
        try safeNode.deleteContact(deviceId: deviceId)
        try refreshContacts()
    }

    func updateContactDisplayName(deviceId: String, displayName: String) throws {
        try safeNode.updateContactDisplayName(deviceId: deviceId, displayName: displayName)
        try refreshContacts()
    }

    func buildBroadcastMessage(plaintext: String) throws -> Data {
        guard let payloadData = plaintext.data(using: .utf8) else {
            throw FlareRepositoryError.invalidInput
        }
        let meshMsg = try safeNode.buildBroadcastMessage(
            plaintextPayload: payloadData,
            contentType: Constants.contentTypeText
        )
        return meshMsg.serialized
    }

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

    /// Processes a remote peer's neighborhood bitmap and tags the peer
    /// for neighborhood-aware routing. Bridge peers are prioritized in routing.
    func processRemoteNeighborhoodForPeer(_ peerDeviceId: String, remoteBitmap: Data) -> String {
        safeNode.processRemoteNeighborhoodForPeer(peerDeviceId: peerDeviceId, remoteBitmap: remoteBitmap)
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
            waitingRelayMessages: Int(ffi.waitingRelayMessages),
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

    // MARK: - Transfer Strategy

    struct TransferRecommendation {
        let strategy: String  // "mesh_relay", "direct_preferred", "direct_required"
        let sizeTier: String  // "small", "medium", "large"
        let estimatedBleChunks: Int
        let isOversized: Bool
    }

    func recommendTransferStrategy(contentType: UInt8, payloadBytes: UInt32) -> TransferRecommendation {
        let ffi = safeNode.recommendTransferStrategy(contentType: contentType, payloadBytes: payloadBytes)
        let strategyStr: String
        switch ffi.strategy {
        case .meshRelay: strategyStr = "mesh_relay"
        case .directPreferred: strategyStr = "direct_preferred"
        case .directRequired: strategyStr = "direct_required"
        }
        return TransferRecommendation(
            strategy: strategyStr,
            sizeTier: ffi.sizeTier,
            estimatedBleChunks: Int(ffi.estimatedBleChunks),
            isOversized: ffi.isOversized
        )
    }

    // MARK: - Wi-Fi Direct Transfer Queue

    func wifiDirectEnqueue(
        transferIdHex: String,
        recipientDeviceId: String,
        payload: Data,
        contentType: UInt8,
        nowSecs: UInt64
    ) -> Bool {
        (try? safeNode.wifiDirectEnqueue(
            transferIdHex: transferIdHex,
            recipientDeviceId: recipientDeviceId,
            payload: payload,
            contentType: contentType,
            nowSecs: nowSecs
        )) ?? false
    }

    func wifiDirectNextTransfer(peerDeviceId: String) -> Data? {
        try? safeNode.wifiDirectNextTransfer(peerDeviceId: peerDeviceId)
    }

    func wifiDirectCompleteTransfer(transferIdHex: String) -> Bool {
        (try? safeNode.wifiDirectCompleteTransfer(transferIdHex: transferIdHex)) ?? false
    }

    func wifiDirectFailTransfer(transferIdHex: String) -> Bool {
        (try? safeNode.wifiDirectFailTransfer(transferIdHex: transferIdHex)) ?? false
    }

    func wifiDirectConnectionChanged(state: String, peerDeviceId: String?) {
        try? safeNode.wifiDirectConnectionChanged(state: state, peerDeviceId: peerDeviceId)
    }

    func wifiDirectMostNeededPeer() -> String? {
        safeNode.wifiDirectMostNeededPeer()
    }

    func wifiDirectHasPending() -> Bool {
        safeNode.wifiDirectHasPending()
    }

    func wifiDirectPruneExpired(nowSecs: UInt64) -> Int {
        Int(safeNode.wifiDirectPruneExpired(nowSecs: nowSecs))
    }

    // MARK: - Groups

    func createGroup(groupId: String, groupName: String) throws {
        try safeNode.createGroup(groupId: groupId, groupName: groupName)
    }

    func addGroupMember(groupId: String, deviceId: String) throws {
        try safeNode.addGroupMember(groupId: groupId, deviceId: deviceId)
    }

    func removeGroupMember(groupId: String, deviceId: String) throws {
        try safeNode.removeGroupMember(groupId: groupId, deviceId: deviceId)
    }

    func listGroups() throws -> [FfiGroup] {
        try safeNode.listGroups()
    }

    func getGroupMembers(groupId: String) throws -> [String] {
        try safeNode.getGroupMembers(groupId: groupId)
    }

    // MARK: - Duress

    func setDuressPassphrase(_ passphrase: String) throws {
        try safeNode.setDuressPassphrase(passphrase: passphrase)
    }

    func hasDuressPassphrase() throws -> Bool {
        try safeNode.hasDuressPassphrase()
    }

    func clearDuressPassphrase() throws {
        try safeNode.clearDuressPassphrase()
    }

    func checkDuressPassphrase(_ passphrase: String) throws -> Bool {
        try safeNode.checkDuressPassphrase(passphrase: passphrase)
    }

    // MARK: - Power Management

    func powerCurrentTier() -> String {
        safeNode.powerCurrentTier()
    }

    func powerSetBatterySaver(enabled: Bool) {
        safeNode.powerSetBatterySaver(enabled: enabled)
    }

    func powerEvaluate(nowSecs: Int64) -> FfiPowerTierRecommendation {
        safeNode.powerEvaluate(nowSecs: nowSecs)
    }

    // MARK: - Data Wipe

    /// Permanently erases all data and reinitializes with a fresh identity.
    /// Used when the destruction code is entered.
    func wipeAndReinitialize() throws {
        // 1. Stop mesh service
        MeshService.shared.stop()

        // 2. Clear lock-related UserDefaults
        let defaults = UserDefaults.standard
        defaults.removeObject(forKey: Constants.prefsKeyUnlockCodeHash)
        defaults.removeObject(forKey: Constants.prefsKeyDestructionCodeHash)
        defaults.removeObject(forKey: Constants.prefsKeyDisplayName)

        // 3. Destroy current node
        lock.lock()
        node = nil
        lock.unlock()

        // 4. Delete database files
        let dbPath = Self.databasePath()
        let fm = FileManager.default
        for suffix in ["", "-wal", "-shm"] {
            try? fm.removeItem(atPath: dbPath + suffix)
        }

        // 5. Delete keychain passphrase so a new one is generated
        let keychainKey = "com.flare.mesh.device-passphrase"
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: keychainKey,
        ]
        SecItemDelete(query as CFDictionary)

        // 6. Reinitialize with fresh identity
        try initialize()

        // 7. Restart mesh service
        MeshService.shared.start()
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
