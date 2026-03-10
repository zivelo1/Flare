import Foundation
import Combine

enum DuressSetupStatus {
    case idle
    case setting
    case success
    case error
}

final class SettingsViewModel: ObservableObject {

    // MARK: - Published Properties

    @Published var hasDuressPin: Bool = false
    @Published var duressStatus: DuressSetupStatus = .idle
    @Published var currentPowerTier: String = ""
    @Published var batterySaverEnabled: Bool = false
    @Published var storeStats: StoreStats? = nil

    // MARK: - Computed Properties

    var deviceId: String {
        FlareRepository.shared.getDeviceId()
    }

    var safetyNumber: String {
        FlareRepository.shared.getSafetyNumber()
    }

    // MARK: - Init

    init() {
        loadInitialState()
    }

    // MARK: - Methods

    func setDuressPassphrase(_ passphrase: String) {
        duressStatus = .setting
        do {
            try FlareRepository.shared.setDuressPassphrase(passphrase)
            hasDuressPin = true
            duressStatus = .success
        } catch {
            duressStatus = .error
        }
    }

    func clearDuressPassphrase() {
        do {
            try FlareRepository.shared.clearDuressPassphrase()
            hasDuressPin = false
            duressStatus = .idle
        } catch {
            duressStatus = .error
        }
    }

    func toggleBatterySaver() {
        batterySaverEnabled.toggle()
        FlareRepository.shared.powerSetBatterySaver(enabled: batterySaverEnabled)
    }

    func refreshStats() {
        storeStats = FlareRepository.shared.getStoreStats()
        currentPowerTier = FlareRepository.shared.powerCurrentTier()
    }

    // MARK: - Private

    private func loadInitialState() {
        do {
            hasDuressPin = try FlareRepository.shared.hasDuressPassphrase()
        } catch {
            hasDuressPin = false
        }

        currentPowerTier = FlareRepository.shared.powerCurrentTier()
        storeStats = FlareRepository.shared.getStoreStats()
    }
}
