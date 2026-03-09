import Foundation
import CoreBluetooth

enum Constants {
    static let protocolVersion: UInt8 = 1

    // BLE GATT UUIDs — must match Android
    static let serviceUUID = CBUUID(string: "7A8B0001-F5A0-4D6B-8C3E-1B2A3C4D5E6F")
    static let charMessageWriteUUID = CBUUID(string: "7A8B0002-F5A0-4D6B-8C3E-1B2A3C4D5E6F")
    static let charMessageNotifyUUID = CBUUID(string: "7A8B0003-F5A0-4D6B-8C3E-1B2A3C4D5E6F")
    static let charPeerInfoUUID = CBUUID(string: "7A8B0004-F5A0-4D6B-8C3E-1B2A3C4D5E6F")

    // BLE scanning
    static let bleScanIntervalSeconds: TimeInterval = 5.0
    static let peerStaleTimeoutSeconds: TimeInterval = 300.0

    // Messaging
    static let defaultMaxHops: Int = 10
    static let defaultTTLSeconds: Int = 86400
    static let sprayCopies: Int = 8
    static let requestedMTU: Int = 517

    // QR Code
    static let qrDataSeparator = "|"
    static let qrMinFields = 3
    static let hexPublicKeyLength = 64

    // Crypto
    static let deviceIdLength = 16
    static let publicKeyLength = 32

    // Notifications
    static let rendezvousBroadcastInterval: TimeInterval = 30.0
    static let pruneInterval: TimeInterval = 150.0
}
