import Foundation
import Combine
import UIKit
import os.log

struct DeliveredMessage {
    let senderId: String
    let plaintext: String
}

final class MeshService: ObservableObject {
    static let shared = MeshService()

    @Published var meshStatus = MeshStatus()
    @Published var isRunning = false

    let incomingDelivered = PassthroughSubject<DeliveredMessage, Never>()

    private let bleManager = BLEManager.shared
    private let logger = Logger(subsystem: "com.flare.mesh", category: "MeshService")
    private var cancellables = Set<AnyCancellable>()
    private var rendezvousTimer: Timer?
    private var pruneTimer: Timer?
    private var powerTimer: Timer?

    /// Tracks timestamps for adaptive power tier evaluation.
    private var lastDataActivityMs: TimeInterval = 0
    private var lastPeerSeenMs: TimeInterval = 0
    private var highTierEnteredMs: TimeInterval = 0
    private var currentScanTier: BLEManager.ScanPowerTier = .balanced
    private var currentAdvertiseTier: BLEManager.AdvertisePowerTier = .balanced

    private init() {}

    func start() {
        guard !isRunning else { return }
        logger.info("Starting mesh service")

        bleManager.localPeerInfoBytes = FlareRepository.shared.getPeerInfoBytes()
        bleManager.start()

        subscribeToIncomingMessages()
        subscribeToConnectionEvents()
        startRendezvousBroadcast()
        startPruning()
        startPowerManagement()

        isRunning = true
        updateMeshStatus()
    }

    func stop() {
        logger.info("Stopping mesh service")
        bleManager.stop()
        cancellables.removeAll()
        rendezvousTimer?.invalidate()
        pruneTimer?.invalidate()
        powerTimer?.invalidate()
        isRunning = false
        meshStatus = MeshStatus()
    }

    func enqueueOutbound(recipientDeviceId: String, data: Data) {
        notifyDataActivity()
        let sent = bleManager.sendToAllPeers(data)
        logger.debug("Sent outbound to \(sent) peers")
    }

    // MARK: - Private

    private func subscribeToIncomingMessages() {
        bleManager.incomingMessages
            .receive(on: DispatchQueue.global(qos: .userInitiated))
            .sink { [weak self] message in
                self?.handleIncomingMessage(message)
            }
            .store(in: &cancellables)
    }

    private func subscribeToConnectionEvents() {
        bleManager.connectionEvents
            .receive(on: DispatchQueue.global(qos: .userInitiated))
            .sink { [weak self] event in
                guard let self = self else { return }
                if event.connected {
                    self.logger.info("Peer connected: \(event.identifier)")
                    self.lastPeerSeenMs = Date().timeIntervalSince1970
                    let repo = FlareRepository.shared
                    repo.notifyPeerConnected(event.identifier)

                    let localBitmap = repo.exportNeighborhoodBitmap()
                    _ = self.bleManager.sendToPeer(event.identifier, data: localBitmap)

                    let messages = repo.getMessagesForPeer(event.identifier)
                    for data in messages {
                        _ = self.bleManager.sendToPeer(event.identifier, data: data)
                    }
                } else {
                    self.logger.info("Peer disconnected: \(event.identifier)")
                }
                self.updateMeshStatus()
            }
            .store(in: &cancellables)
    }

    private func handleIncomingMessage(_ message: IncomingBLEMessage) {
        notifyDataActivity()

        do {
            let result = try FlareRepository.shared.processIncomingMessage(message.data)

            switch result.decision {
            case .deliverLocally:
                if let senderId = result.senderId, let plaintext = result.plaintext {
                    logger.info("Message delivered locally from \(senderId)")
                    incomingDelivered.send(DeliveredMessage(senderId: senderId, plaintext: plaintext))

                    if let messageId = result.messageId {
                        Task {
                            do {
                                let ackBytes = try FlareRepository.shared.createDeliveryAck(
                                    originalMessageId: messageId,
                                    senderDeviceId: senderId
                                )
                                _ = bleManager.sendToAllPeers(ackBytes)
                            } catch {
                                logger.warning("Failed to send delivery ACK: \(error)")
                            }
                        }
                    }
                }

            case .forward:
                do {
                    let relayData = try FlareRepository.shared.prepareForRelay(message.data)
                    let connectedAddresses = bleManager.connectedAddresses()
                    for address in connectedAddresses where address != message.fromIdentifier {
                        _ = bleManager.sendToPeer(address, data: relayData)
                    }
                    logger.debug("Forwarded to \(connectedAddresses.count - 1) peers")
                } catch {
                    logger.debug("Message reached hop limit")
                }

            case .store:
                logger.debug("Message stored for later forwarding")

            case .drop:
                logger.debug("Message dropped")
            }
        } catch {
            logger.error("Error processing incoming message: \(error)")
        }

        updateMeshStatus()
    }

    private func startRendezvousBroadcast() {
        DispatchQueue.main.async {
            self.rendezvousTimer = Timer.scheduledTimer(
                withTimeInterval: Constants.rendezvousBroadcastInterval,
                repeats: true
            ) { [weak self] _ in
                self?.broadcastRendezvous()
            }
        }
    }

    private func broadcastRendezvous() {
        let repo = FlareRepository.shared
        guard repo.activeSearchCount() > 0 else { return }

        do {
            let broadcasts = try repo.buildRendezvousBroadcasts()
            for data in broadcasts {
                _ = bleManager.sendToAllPeers(data)
            }
            if !broadcasts.isEmpty {
                logger.debug("Broadcast \(broadcasts.count) rendezvous tokens")
            }
        } catch {
            logger.warning("Rendezvous broadcast failed: \(error)")
        }
    }

    private func startPruning() {
        DispatchQueue.main.async {
            self.pruneTimer = Timer.scheduledTimer(
                withTimeInterval: Constants.pruneInterval,
                repeats: true
            ) { [weak self] _ in
                let pruned = FlareRepository.shared.pruneExpiredMessages()
                if pruned > 0 {
                    self?.logger.debug("Pruned \(pruned) expired messages")
                }
            }
        }
    }

    private func updateMeshStatus() {
        let discovered = bleManager.discoveredPeers.count
        let connected = bleManager.connectedCount()
        let stored = FlareRepository.shared.getStoreStats().totalMessages

        // Track peer discovery for power management
        if discovered > 0 {
            lastPeerSeenMs = Date().timeIntervalSince1970
        }

        DispatchQueue.main.async {
            self.meshStatus = MeshStatus(
                isActive: self.isRunning,
                connectedPeerCount: connected,
                discoveredPeerCount: discovered,
                storedMessageCount: stored,
                messagesRelayed: self.meshStatus.messagesRelayed
            )
        }
    }

    // MARK: - Adaptive Power Management

    /// Notifies that data was sent or received, promoting to High tier.
    private func notifyDataActivity() {
        lastDataActivityMs = Date().timeIntervalSince1970
    }

    /// Starts the periodic power management evaluation loop.
    private func startPowerManagement() {
        DispatchQueue.main.async {
            self.powerTimer = Timer.scheduledTimer(
                withTimeInterval: Constants.bleScanIntervalSeconds,
                repeats: true
            ) { [weak self] _ in
                self?.evaluateAndApplyPowerTier()
            }
        }
    }

    /// Evaluates network activity and battery state to determine optimal BLE power tier.
    /// Mirrors the logic in flare-core/src/power/mod.rs and Android MeshService.kt.
    private func evaluateAndApplyPowerTier() {
        let now = Date().timeIntervalSince1970
        let batteryPercent = getBatteryPercent()

        let connectedCount = bleManager.connectedCount()
        let hasPendingOutbound = FlareRepository.shared.getStoreStats().totalMessages > 0

        // Force UltraLow on critical battery
        if batteryPercent <= Constants.powerCriticalBatteryPercent {
            applyPowerTier(.ultraLow)
            return
        }

        let secsSinceData = now - lastDataActivityMs
        let secsSincePeer = now - lastPeerSeenMs
        let secsInHigh = now - highTierEnteredMs

        let highDurationOk: Bool
        if currentScanTier == .high {
            highDurationOk = secsInHigh < Constants.powerHighDurationLimitSeconds
        } else {
            highDurationOk = true
        }

        let candidate: PowerTierResult
        if hasPendingOutbound && connectedCount > 0 {
            candidate = .high
        } else if secsSinceData < Constants.powerHighInactivityThresholdSeconds && highDurationOk {
            candidate = .high
        } else if connectedCount > 0 || secsSincePeer < Constants.powerBalancedNoPeersThresholdSeconds {
            candidate = .balanced
        } else {
            candidate = .lowPower
        }

        // Cap at Balanced when battery is low
        let capped: PowerTierResult
        if batteryPercent <= Constants.powerLowBatteryPercent && candidate == .high {
            capped = .balanced
        } else {
            capped = candidate
        }

        applyPowerTier(capped)
    }

    /// Applies the evaluated power tier to BLE scanner and advertiser.
    private func applyPowerTier(_ tier: PowerTierResult) {
        let newScanTier = tier.toScanTier()
        let newAdvertiseTier = tier.toAdvertiseTier()

        // Track High tier entry
        if newScanTier == .high && currentScanTier != .high {
            highTierEnteredMs = Date().timeIntervalSince1970
        }

        // Only reconfigure if tier changed
        guard newScanTier != currentScanTier else { return }

        logger.info("Power tier changed: \(self.currentScanTier.rawValue) -> \(newScanTier.rawValue)")
        currentScanTier = newScanTier
        currentAdvertiseTier = newAdvertiseTier

        bleManager.startScanning(tier: newScanTier)
        bleManager.startAdvertising(tier: newAdvertiseTier)
    }

    /// Reads the current battery percentage.
    private func getBatteryPercent() -> Int {
        UIDevice.current.isBatteryMonitoringEnabled = true
        let level = UIDevice.current.batteryLevel
        if level < 0 { return 100 } // Unknown = assume full
        return Int(level * 100)
    }

    /// Internal power tier result enum that maps to both scan and advertise tiers.
    private enum PowerTierResult: Equatable {
        case high, balanced, lowPower, ultraLow

        func toScanTier() -> BLEManager.ScanPowerTier {
            switch self {
            case .high: return .high
            case .balanced: return .balanced
            case .lowPower: return .lowPower
            case .ultraLow: return .ultraLow
            }
        }

        func toAdvertiseTier() -> BLEManager.AdvertisePowerTier {
            switch self {
            case .high: return .high
            case .balanced: return .balanced
            case .lowPower, .ultraLow: return .lowPower
            }
        }
    }
}
