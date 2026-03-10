import Foundation
import MultipeerConnectivity
import Combine
import os.log

/// Manages Wi-Fi Direct transport via Apple's MultipeerConnectivity framework.
/// Operates alongside BLEManager — BLE handles discovery and small messages,
/// MultipeerConnectivity handles large payloads (voice, images, files).
///
/// MultipeerConnectivity uses a mix of Wi-Fi Direct and infrastructure Wi-Fi
/// to establish peer-to-peer connections with ~250m range and ~50 Mbps throughput.
final class MultipeerManager: NSObject, ObservableObject {
    static let shared = MultipeerManager()

    /// Incoming data received from a Multipeer peer.
    let incomingData = PassthroughSubject<(peerID: MCPeerID, data: Data), Never>()

    /// Connection state changes.
    let connectionEvents = PassthroughSubject<(peerID: MCPeerID, state: MCSessionState), Never>()

    @Published var connectedPeers: [MCPeerID] = []
    @Published var isActive = false

    private let serviceType = Constants.multipeerServiceType
    private let logger = Logger(subsystem: "com.flare.mesh", category: "MultipeerManager")

    private var localPeerID: MCPeerID!
    private var session: MCSession!
    private var advertiser: MCNearbyServiceAdvertiser!
    private var browser: MCNearbyServiceBrowser!

    /// Maps MCPeerID display names to Flare device IDs (hex strings).
    /// Set by the caller when a peer's Flare identity is known.
    private var peerDeviceIdMap: [String: String] = [:]
    private let mapLock = NSLock()

    private override init() {
        super.init()
    }

    /// Starts MultipeerConnectivity advertising and browsing.
    /// `localDeviceId`: This device's Flare device ID (hex string), used as the peer display name.
    func start(localDeviceId: String) {
        guard !isActive else { return }
        logger.info("Starting MultipeerConnectivity transport")

        localPeerID = MCPeerID(displayName: localDeviceId)
        session = MCSession(
            peer: localPeerID,
            securityIdentity: nil,
            encryptionPreference: .required
        )
        session.delegate = self

        advertiser = MCNearbyServiceAdvertiser(
            peer: localPeerID,
            discoveryInfo: ["v": String(Constants.protocolVersion)],
            serviceType: serviceType
        )
        advertiser.delegate = self
        advertiser.startAdvertisingPeer()

        browser = MCNearbyServiceBrowser(peer: localPeerID, serviceType: serviceType)
        browser.delegate = self
        browser.startBrowsingForPeers()

        isActive = true
    }

    func stop() {
        guard isActive else { return }
        logger.info("Stopping MultipeerConnectivity transport")

        advertiser?.stopAdvertisingPeer()
        browser?.stopBrowsingForPeers()
        session?.disconnect()

        DispatchQueue.main.async {
            self.connectedPeers = []
            self.isActive = false
        }
    }

    /// Sends data to a specific peer.
    /// Returns true if the send was queued successfully.
    func sendToPeer(_ peerID: MCPeerID, data: Data) -> Bool {
        guard session.connectedPeers.contains(peerID) else { return false }

        do {
            try session.send(data, toPeers: [peerID], with: .reliable)
            return true
        } catch {
            logger.warning("Send to peer \(peerID.displayName) failed: \(error)")
            return false
        }
    }

    /// Sends data to all connected peers.
    func sendToAllPeers(_ data: Data) -> Int {
        let peers = session.connectedPeers
        guard !peers.isEmpty else { return 0 }

        var sent = 0
        for peer in peers {
            if sendToPeer(peer, data: data) {
                sent += 1
            }
        }
        return sent
    }

    /// Maps a MultipeerConnectivity peer to a Flare device ID.
    func registerPeerDeviceId(_ peerID: MCPeerID, deviceId: String) {
        mapLock.lock()
        peerDeviceIdMap[peerID.displayName] = deviceId
        mapLock.unlock()
    }

    /// Looks up the Flare device ID for a MultipeerConnectivity peer.
    func deviceIdForPeer(_ peerID: MCPeerID) -> String? {
        mapLock.lock()
        defer { mapLock.unlock() }
        return peerDeviceIdMap[peerID.displayName]
    }

    /// Finds the MCPeerID for a given Flare device ID hex string.
    func peerForDeviceId(_ deviceId: String) -> MCPeerID? {
        // The display name IS the device ID hex string
        session.connectedPeers.first { $0.displayName == deviceId }
    }
}

// MARK: - MCSessionDelegate

extension MultipeerManager: MCSessionDelegate {
    func session(_ session: MCSession, peer peerID: MCPeerID, didChange state: MCSessionState) {
        logger.info("Peer \(peerID.displayName) state: \(state.rawValue)")

        DispatchQueue.main.async {
            self.connectedPeers = session.connectedPeers
        }

        connectionEvents.send((peerID: peerID, state: state))
    }

    func session(_ session: MCSession, didReceive data: Data, fromPeer peerID: MCPeerID) {
        logger.debug("Received \(data.count) bytes from \(peerID.displayName)")
        incomingData.send((peerID: peerID, data: data))
    }

    func session(
        _ session: MCSession,
        didReceive stream: InputStream,
        withName streamName: String,
        fromPeer peerID: MCPeerID
    ) {
        // Stream-based transfer not used; large payloads use reliable send.
        stream.close()
    }

    func session(
        _ session: MCSession,
        didStartReceivingResourceWithName resourceName: String,
        fromPeer peerID: MCPeerID,
        with progress: Progress
    ) {
        // Resource transfer not used.
    }

    func session(
        _ session: MCSession,
        didFinishReceivingResourceWithName resourceName: String,
        fromPeer peerID: MCPeerID,
        at localURL: URL?,
        withError error: Error?
    ) {
        // Resource transfer not used.
    }
}

// MARK: - MCNearbyServiceAdvertiserDelegate

extension MultipeerManager: MCNearbyServiceAdvertiserDelegate {
    func advertiser(
        _ advertiser: MCNearbyServiceAdvertiser,
        didReceiveInvitationFromPeer peerID: MCPeerID,
        withContext context: Data?,
        invitationHandler: @escaping (Bool, MCSession?) -> Void
    ) {
        // Auto-accept invitations from Flare peers.
        // Deterministic tie-breaking: lower display name accepts.
        // This prevents duplicate connections when both sides invite simultaneously.
        let shouldAccept = localPeerID.displayName < peerID.displayName
            || !session.connectedPeers.contains(peerID)

        logger.info("Invitation from \(peerID.displayName): \(shouldAccept ? "accept" : "decline")")
        invitationHandler(shouldAccept, shouldAccept ? session : nil)
    }

    func advertiser(_ advertiser: MCNearbyServiceAdvertiser, didNotStartAdvertisingPeer error: Error) {
        logger.error("Failed to start advertising: \(error)")
    }
}

// MARK: - MCNearbyServiceBrowserDelegate

extension MultipeerManager: MCNearbyServiceBrowserDelegate {
    func browser(_ browser: MCNearbyServiceBrowser, foundPeer peerID: MCPeerID, withDiscoveryInfo info: [String: String]?) {
        guard !session.connectedPeers.contains(peerID) else { return }

        // Deterministic invitation: higher display name invites.
        // Combined with advertiser tie-breaking, ensures exactly one connection attempt.
        guard localPeerID.displayName > peerID.displayName else { return }

        logger.info("Found peer \(peerID.displayName), inviting")
        browser.invitePeer(peerID, to: session, withContext: nil, timeout: 30)
    }

    func browser(_ browser: MCNearbyServiceBrowser, lostPeer peerID: MCPeerID) {
        logger.info("Lost peer \(peerID.displayName)")
    }

    func browser(_ browser: MCNearbyServiceBrowser, didNotStartBrowsingForPeers error: Error) {
        logger.error("Failed to start browsing: \(error)")
    }
}
