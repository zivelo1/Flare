import Foundation

struct DeviceIdentity: Identifiable, Equatable {
    var id: String { deviceId }
    let deviceId: String
    let signingPublicKey: Data
    let agreementPublicKey: Data
}

struct Contact: Identifiable, Equatable {
    var id: String { identity.deviceId }
    let identity: DeviceIdentity
    let displayName: String?
    let isVerified: Bool
    let lastSeen: Date

    var initials: String {
        (displayName?.first.map(String.init) ?? "?").uppercased()
    }
}

struct Conversation: Identifiable, Equatable {
    let id: String
    let contact: Contact
    let lastMessage: ChatMessage?
    let unreadCount: Int
}

struct ChatMessage: Identifiable, Equatable {
    let messageId: String
    let conversationId: String
    let senderDeviceId: String
    let content: String
    let timestamp: Date
    let isOutgoing: Bool
    let deliveryStatus: DeliveryStatus

    var id: String { messageId }
}

enum DeliveryStatus: UInt8, Equatable {
    case pending = 0
    case sent = 1
    case delivered = 2
    case read = 3
    case failed = 4
}

struct MeshPeer: Identifiable, Equatable {
    let deviceId: String
    let displayName: String?
    let rssi: Int?
    let estimatedDistance: Float?
    let isConnected: Bool
    let lastSeen: Date

    var id: String { deviceId }

    var shortId: String {
        displayName ?? "Device \(deviceId.prefix(4).uppercased())"
    }
}

struct MeshStatus: Equatable {
    var isActive: Bool = false
    var connectedPeerCount: Int = 0
    var discoveredPeerCount: Int = 0
    var storedMessageCount: Int = 0
    var messagesRelayed: Int = 0
}

enum TransportType: String {
    case ble = "BLE"
    case multipeer = "Multipeer"
}

struct ChatGroup: Identifiable, Equatable {
    let groupId: String
    let groupName: String
    let createdAt: String
    let creatorDeviceId: String
    var memberCount: Int = 0

    var id: String { groupId }
}

struct StoreStats: Equatable {
    let totalMessages: Int
    let ownMessages: Int
    let activeRelayMessages: Int
    let waitingRelayMessages: Int
    let totalBytes: Int
    let budgetBytes: Int
}
