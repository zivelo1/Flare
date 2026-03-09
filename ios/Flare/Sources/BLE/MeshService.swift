import Foundation
import Combine
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

        isRunning = true
        updateMeshStatus()
    }

    func stop() {
        logger.info("Stopping mesh service")
        bleManager.stop()
        cancellables.removeAll()
        rendezvousTimer?.invalidate()
        pruneTimer?.invalidate()
        isRunning = false
        meshStatus = MeshStatus()
    }

    func enqueueOutbound(recipientDeviceId: String, data: Data) {
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
}
