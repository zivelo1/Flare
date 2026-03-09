import Foundation
import Combine

@MainActor
final class NetworkViewModel: ObservableObject {
    @Published var meshStatus = MeshStatus()
    @Published var isRunning = false
    @Published var nearbyPeers: [String: MeshPeer] = [:]

    private var cancellables = Set<AnyCancellable>()

    init() {
        MeshService.shared.$meshStatus
            .receive(on: DispatchQueue.main)
            .assign(to: &$meshStatus)

        MeshService.shared.$isRunning
            .receive(on: DispatchQueue.main)
            .assign(to: &$isRunning)

        BLEManager.shared.$discoveredPeers
            .receive(on: DispatchQueue.main)
            .assign(to: &$nearbyPeers)
    }
}
