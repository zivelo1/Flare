import Foundation
import Combine
import MultipeerConnectivity
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
    private let multipeerManager = MultipeerManager.shared
    private let logger = Logger(subsystem: "com.flare.mesh", category: "MeshService")
    private var cancellables = Set<AnyCancellable>()
    private var rendezvousTimer: Timer?
    private var pruneTimer: Timer?
    private var powerTimer: Timer?
    private var wifiDirectQueueTimer: Timer?

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

        let repo = FlareRepository.shared
        bleManager.localPeerInfoBytes = repo.getPeerInfoBytes()
        bleManager.start()

        // Start MultipeerConnectivity for Wi-Fi Direct transport
        multipeerManager.start(localDeviceId: repo.getDeviceId())

        subscribeToIncomingMessages()
        subscribeToConnectionEvents()
        subscribeToMultipeerEvents()
        startRendezvousBroadcast()
        startPruning()
        startPowerManagement()
        startWifiDirectQueueProcessing()

        isRunning = true
        updateMeshStatus()
    }

    func stop() {
        logger.info("Stopping mesh service")
        bleManager.stop()
        multipeerManager.stop()
        cancellables.removeAll()
        rendezvousTimer?.invalidate()
        pruneTimer?.invalidate()
        powerTimer?.invalidate()
        wifiDirectQueueTimer?.invalidate()
        isRunning = false
        meshStatus = MeshStatus()
    }

    func enqueueOutbound(recipientDeviceId: String, data: Data) {
        notifyDataActivity()

        // Send via BLE mesh (always — small messages and control traffic)
        let bleSent = bleManager.sendToAllPeers(data)

        // Also send via MultipeerConnectivity if peers are connected
        let wifiSent = multipeerManager.sendToAllPeers(data)

        logger.debug("Sent outbound to \(bleSent) BLE + \(wifiSent) Wi-Fi Direct peers")
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
                    self.logger.info("Peer connected")
                    self.lastPeerSeenMs = Date().timeIntervalSince1970
                    let repo = FlareRepository.shared
                    repo.notifyPeerConnected(event.identifier)

                    let localBitmap = repo.exportNeighborhoodBitmap()
                    _ = self.bleManager.sendToPeer(event.identifier, data: localBitmap)
                    // Note: Remote bitmap is processed in handleIncomingMessage
                    // when received from the peer, using processRemoteNeighborhoodForPeer
                    // to tag the peer for neighborhood-aware routing.

                    let messages = repo.getMessagesForPeer(event.identifier)
                    for data in messages {
                        _ = self.bleManager.sendToPeer(event.identifier, data: data)
                    }
                } else {
                    self.logger.info("Peer disconnected")
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
                    logger.info("Message delivered locally")
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
        let wifiDirectPeers = multipeerManager.connectedPeers.count
        let connected = bleManager.connectedCount() + wifiDirectPeers
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

    // MARK: - MultipeerConnectivity (Wi-Fi Direct)

    private func subscribeToMultipeerEvents() {
        // Handle incoming data from MultipeerConnectivity peers
        multipeerManager.incomingData
            .receive(on: DispatchQueue.global(qos: .userInitiated))
            .sink { [weak self] event in
                guard let self = self else { return }
                self.notifyDataActivity()
                // Incoming data from Wi-Fi Direct peers is treated identically
                // to BLE data — same message format, same routing logic
                self.handleIncomingMessage(IncomingBLEMessage(
                    fromIdentifier: event.peerID.displayName,
                    data: event.data
                ))
            }
            .store(in: &cancellables)

        // Handle Wi-Fi Direct connection state changes
        multipeerManager.connectionEvents
            .receive(on: DispatchQueue.global(qos: .userInitiated))
            .sink { [weak self] event in
                guard let self = self else { return }
                let repo = FlareRepository.shared
                let deviceId = event.peerID.displayName

                switch event.state {
                case .connected:
                    self.logger.info("Wi-Fi Direct peer connected: \(deviceId)")
                    repo.wifiDirectConnectionChanged(state: "connected", peerDeviceId: deviceId)
                    self.processWifiDirectQueue(forPeer: deviceId)

                case .notConnected:
                    self.logger.info("Wi-Fi Direct peer disconnected: \(deviceId)")
                    repo.wifiDirectConnectionChanged(state: "disconnected", peerDeviceId: nil)

                case .connecting:
                    repo.wifiDirectConnectionChanged(state: "connecting", peerDeviceId: deviceId)

                @unknown default:
                    break
                }

                self.updateMeshStatus()
            }
            .store(in: &cancellables)
    }

    /// Periodically checks for pending Wi-Fi Direct transfers and processes them.
    private func startWifiDirectQueueProcessing() {
        DispatchQueue.main.async {
            self.wifiDirectQueueTimer = Timer.scheduledTimer(
                withTimeInterval: Constants.wifiDirectQueueCheckInterval,
                repeats: true
            ) { [weak self] _ in
                self?.processWifiDirectQueueIfNeeded()
            }
        }
    }

    /// Checks if there are pending Wi-Fi Direct transfers and processes them.
    private func processWifiDirectQueueIfNeeded() {
        let repo = FlareRepository.shared
        guard repo.wifiDirectHasPending() else { return }

        // Prune expired transfers
        let now = UInt64(Date().timeIntervalSince1970)
        let pruned = repo.wifiDirectPruneExpired(nowSecs: now)
        if pruned > 0 {
            logger.debug("Pruned \(pruned) expired Wi-Fi Direct transfers")
        }

        // Find the peer we most need to connect to
        guard let neededPeer = repo.wifiDirectMostNeededPeer() else { return }

        // If that peer is already connected via MultipeerConnectivity, process queue
        if let mcPeer = multipeerManager.peerForDeviceId(neededPeer) {
            processWifiDirectQueue(forPeer: mcPeer.displayName)
        }
    }

    /// Sends all pending Wi-Fi Direct transfers for a specific peer.
    private func processWifiDirectQueue(forPeer deviceId: String) {
        let repo = FlareRepository.shared
        guard let mcPeer = multipeerManager.peerForDeviceId(deviceId) else { return }

        // Process transfers one at a time
        while let payload = repo.wifiDirectNextTransfer(peerDeviceId: deviceId) {
            if multipeerManager.sendToPeer(mcPeer, data: payload) {
                // The transfer ID would be extracted from the payload in production;
                // for now we rely on the Rust queue's ordering (FIFO)
                logger.debug("Sent Wi-Fi Direct transfer to \(deviceId)")
                notifyDataActivity()
            } else {
                logger.warning("Wi-Fi Direct send failed to \(deviceId)")
                break
            }
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
