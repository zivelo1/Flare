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

    // Adaptive Power Management (mirrors Rust PowerConfig defaults)
    // See flare-core/src/power/mod.rs for the full tier-based power manager.
    static let powerHighBurstScanSeconds: TimeInterval = 4.096
    static let powerBalancedBurstScanSeconds: TimeInterval = 1.024
    static let powerLowBurstScanSeconds: TimeInterval = 5.0
    static let powerLowBurstSleepSeconds: TimeInterval = 25.0
    static let powerUltraLowBurstScanSeconds: TimeInterval = 3.0
    static let powerUltraLowBurstSleepSeconds: TimeInterval = 57.0
    static let powerHighInactivityThresholdSeconds: TimeInterval = 10.0
    static let powerBalancedNoPeersThresholdSeconds: TimeInterval = 60.0
    static let powerHighDurationLimitSeconds: TimeInterval = 30.0
    static let powerCriticalBatteryPercent: Int = 15
    static let powerLowBatteryPercent: Int = 30

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

    // Wi-Fi Direct / MultipeerConnectivity
    /// Service type for MultipeerConnectivity discovery.
    /// Must be 1-15 characters, lowercase ASCII letters, numbers, and hyphens.
    static let multipeerServiceType = "flare-mesh"
    /// How long a Wi-Fi Direct transfer can wait before being dropped (seconds).
    static let wifiDirectTransferTimeoutSeconds: TimeInterval = 300.0
    /// Interval for checking Wi-Fi Direct transfer queue (seconds).
    static let wifiDirectQueueCheckInterval: TimeInterval = 5.0

    // Message Size Tiers (mirrors Rust SizeTierConfig defaults)
    /// Maximum payload size for BLE mesh relay (bytes).
    static let meshRelayMaxBytes: Int = 15 * 1024
    /// Maximum payload size for direct-preferred tier (bytes).
    static let directPreferredMaxBytes: Int = 64 * 1024
    /// Absolute maximum payload size (bytes).
    static let absoluteMaxPayloadBytes: Int = 10 * 1024 * 1024
}
