import Foundation
import CoreBluetooth
import Combine
import os.log

struct IncomingBLEMessage {
    let fromIdentifier: String
    let data: Data
}

struct BLEConnectionEvent {
    let identifier: String
    let connected: Bool
}

final class BLEManager: NSObject, ObservableObject {
    static let shared = BLEManager()

    private var centralManager: CBCentralManager!
    private var peripheralManager: CBPeripheralManager!

    private var discoveredPeripherals: [UUID: CBPeripheral] = [:]
    private var connectedPeripherals: [UUID: CBPeripheral] = [:]
    private var writeCharacteristics: [UUID: CBCharacteristic] = [:]
    private var peerInfoCharacteristics: [UUID: CBCharacteristic] = [:]

    private var messageService: CBMutableService?
    private var messageWriteChar: CBMutableCharacteristic?
    private var messageNotifyChar: CBMutableCharacteristic?
    private var peerInfoChar: CBMutableCharacteristic?
    private var subscribedCentrals: [CBCentral] = []

    /// BLE scan power tiers for adaptive power management.
    /// Maps to CoreBluetooth scan strategies since iOS doesn't expose
    /// direct scan mode control like Android.
    enum ScanPowerTier: String {
        /// Near-continuous scanning for active data exchange (max 30s).
        case high
        /// Standard scanning with duplicate reporting.
        case balanced
        /// Reduced scanning: periodic bursts with sleep intervals.
        case lowPower
        /// Minimal scanning: short bursts with long sleep.
        case ultraLow
    }

    /// BLE advertise power tiers.
    /// iOS advertising is less configurable than Android, but we can
    /// start/stop advertising to achieve duty cycling.
    enum AdvertisePowerTier: String {
        case high
        case balanced
        case lowPower
    }

    @Published var discoveredPeers: [String: MeshPeer] = [:]
    @Published var isScanning = false
    @Published var isAdvertising = false

    let incomingMessages = PassthroughSubject<IncomingBLEMessage, Never>()
    let connectionEvents = PassthroughSubject<BLEConnectionEvent, Never>()

    var localPeerInfoBytes: Data = Data()

    private var scanTimer: Timer?
    private var pruneTimer: Timer?
    private var burstTimer: Timer?
    private var currentScanTier: ScanPowerTier = .balanced
    private var currentAdvertiseTier: AdvertisePowerTier = .balanced
    private var burstSleeping = false
    private let logger = Logger(subsystem: "com.flare.mesh", category: "BLE")

    override private init() {
        super.init()
        centralManager = CBCentralManager(delegate: self, queue: .global(qos: .userInitiated), options: [
            CBCentralManagerOptionRestoreIdentifierKey: "com.flare.mesh.central",
        ])
        peripheralManager = CBPeripheralManager(delegate: self, queue: .global(qos: .userInitiated), options: [
            CBPeripheralManagerOptionRestoreIdentifierKey: "com.flare.mesh.peripheral",
        ])
    }

    // MARK: - Public API

    func start() {
        if centralManager.state == .poweredOn {
            startScanning(tier: .balanced)
        }
        if peripheralManager.state == .poweredOn {
            startAdvertising(tier: .balanced)
        }
    }

    func stop() {
        stopScanning()
        stopAdvertising()
        disconnectAll()
    }

    func sendToPeer(_ identifier: String, data: Data) -> Bool {
        guard let uuid = UUID(uuidString: identifier),
              let peripheral = connectedPeripherals[uuid],
              let characteristic = writeCharacteristics[uuid] else {
            return false
        }

        peripheral.writeValue(data, for: characteristic, type: .withResponse)
        return true
    }

    func sendToAllPeers(_ data: Data) -> Int {
        var sent = 0

        // Send via peripheral manager (notify subscribed centrals)
        if let char = messageNotifyChar, !subscribedCentrals.isEmpty {
            let success = peripheralManager.updateValue(data, for: char, onSubscribedCentrals: nil)
            if success { sent += subscribedCentrals.count }
        }

        // Send via central manager (write to connected peripherals)
        for (uuid, peripheral) in connectedPeripherals {
            if let characteristic = writeCharacteristics[uuid] {
                peripheral.writeValue(data, for: characteristic, type: .withResponse)
                sent += 1
            }
        }

        return sent
    }

    func connectedAddresses() -> Set<String> {
        Set(connectedPeripherals.keys.map(\.uuidString))
    }

    func connectedCount() -> Int {
        connectedPeripherals.count + subscribedCentrals.count
    }

    // MARK: - Scanning

    /// Starts BLE scanning at the specified power tier.
    /// Higher tiers scan more aggressively; lower tiers use burst mode.
    func startScanning(tier: ScanPowerTier = .balanced) {
        guard centralManager.state == .poweredOn else { return }

        // If already scanning at same tier, skip
        if isScanning && tier == currentScanTier && !burstSleeping { return }

        // Stop current scanning if changing tier
        if isScanning {
            stopScanning()
        }

        currentScanTier = tier

        // All tiers use the same CoreBluetooth scan call (iOS doesn't expose scan modes).
        // Power management is achieved through burst mode duty cycling.
        let allowDuplicates: Bool
        switch tier {
        case .high:
            allowDuplicates = true   // Report every advertisement
        case .balanced:
            allowDuplicates = true   // Report duplicates for RSSI updates
        case .lowPower, .ultraLow:
            allowDuplicates = false  // Reduce callback frequency
        }

        centralManager.scanForPeripherals(
            withServices: [Constants.serviceUUID],
            options: [CBCentralManagerScanOptionAllowDuplicatesKey: allowDuplicates]
        )
        DispatchQueue.main.async {
            self.isScanning = true
            self.burstSleeping = false
        }
        logger.info("BLE scanning started at tier \(tier.rawValue)")

        DispatchQueue.main.async {
            self.pruneTimer?.invalidate()
            self.pruneTimer = Timer.scheduledTimer(withTimeInterval: Constants.peerStaleTimeoutSeconds / 2, repeats: true) { [weak self] _ in
                self?.pruneStale()
            }
        }

        // Start burst mode for low-power tiers
        startBurstMode(tier: tier)
    }

    func stopScanning() {
        centralManager.stopScan()
        DispatchQueue.main.async {
            self.isScanning = false
            self.burstSleeping = false
            self.pruneTimer?.invalidate()
            self.pruneTimer = nil
            self.burstTimer?.invalidate()
            self.burstTimer = nil
        }
        logger.info("BLE scanning stopped")
    }

    /// Manages burst-mode scanning for LowPower and UltraLow tiers.
    /// Scans for a short window, then pauses to save battery.
    private func startBurstMode(tier: ScanPowerTier) {
        // Cancel existing burst timer
        DispatchQueue.main.async {
            self.burstTimer?.invalidate()
            self.burstTimer = nil
        }

        let burstScanSeconds: TimeInterval
        let burstSleepSeconds: TimeInterval

        switch tier {
        case .lowPower:
            burstScanSeconds = Constants.powerLowBurstScanSeconds
            burstSleepSeconds = Constants.powerLowBurstSleepSeconds
        case .ultraLow:
            burstScanSeconds = Constants.powerUltraLowBurstScanSeconds
            burstSleepSeconds = Constants.powerUltraLowBurstSleepSeconds
        case .high, .balanced:
            return // No burst mode for high/balanced — continuous scanning
        }

        // Schedule the first sleep after the scan window
        DispatchQueue.main.async { [weak self] in
            self?.scheduleBurstCycle(
                tier: tier,
                scanSeconds: burstScanSeconds,
                sleepSeconds: burstSleepSeconds,
                startWithSleep: false
            )
        }
    }

    private func scheduleBurstCycle(
        tier: ScanPowerTier,
        scanSeconds: TimeInterval,
        sleepSeconds: TimeInterval,
        startWithSleep: Bool
    ) {
        guard currentScanTier == tier else { return }

        if startWithSleep {
            // Sleep phase: stop scanning
            centralManager.stopScan()
            burstSleeping = true
            logger.debug("Burst sleep for \(sleepSeconds)s")

            burstTimer = Timer.scheduledTimer(withTimeInterval: sleepSeconds, repeats: false) { [weak self] _ in
                self?.scheduleBurstCycle(
                    tier: tier,
                    scanSeconds: scanSeconds,
                    sleepSeconds: sleepSeconds,
                    startWithSleep: false
                )
            }
        } else {
            // Scan phase: resume scanning
            guard centralManager.state == .poweredOn else { return }
            let allowDuplicates = (tier == .lowPower)
            centralManager.scanForPeripherals(
                withServices: [Constants.serviceUUID],
                options: [CBCentralManagerScanOptionAllowDuplicatesKey: allowDuplicates]
            )
            burstSleeping = false
            logger.debug("Burst scan for \(scanSeconds)s")

            burstTimer = Timer.scheduledTimer(withTimeInterval: scanSeconds, repeats: false) { [weak self] _ in
                self?.scheduleBurstCycle(
                    tier: tier,
                    scanSeconds: scanSeconds,
                    sleepSeconds: sleepSeconds,
                    startWithSleep: true
                )
            }
        }
    }

    private func pruneStale() {
        let cutoff = Date().addingTimeInterval(-Constants.peerStaleTimeoutSeconds)
        DispatchQueue.main.async {
            self.discoveredPeers = self.discoveredPeers.filter { $0.value.lastSeen > cutoff }
        }
    }

    // MARK: - Advertising

    /// Starts BLE advertising at the specified power tier.
    /// iOS doesn't allow direct advertising interval control, but we can
    /// start/stop advertising to achieve duty cycling in lower tiers.
    func startAdvertising(tier: AdvertisePowerTier = .balanced) {
        guard peripheralManager.state == .poweredOn else { return }

        // If already advertising at same tier, skip
        if isAdvertising && tier == currentAdvertiseTier { return }

        // Stop if changing tier
        if isAdvertising {
            stopAdvertising()
        }

        currentAdvertiseTier = tier

        let service = CBMutableService(type: Constants.serviceUUID, primary: true)

        let writeChar = CBMutableCharacteristic(
            type: Constants.charMessageWriteUUID,
            properties: [.write, .writeWithoutResponse],
            value: nil,
            permissions: [.writeable]
        )

        let notifyChar = CBMutableCharacteristic(
            type: Constants.charMessageNotifyUUID,
            properties: [.notify],
            value: nil,
            permissions: [.readable]
        )

        let infoChar = CBMutableCharacteristic(
            type: Constants.charPeerInfoUUID,
            properties: [.read],
            value: localPeerInfoBytes,
            permissions: [.readable]
        )

        service.characteristics = [writeChar, notifyChar, infoChar]
        messageWriteChar = writeChar
        messageNotifyChar = notifyChar
        peerInfoChar = infoChar
        messageService = service

        peripheralManager.add(service)
        peripheralManager.startAdvertising([
            CBAdvertisementDataServiceUUIDsKey: [Constants.serviceUUID],
            CBAdvertisementDataLocalNameKey: "Flare",
        ])

        DispatchQueue.main.async { self.isAdvertising = true }
        logger.info("BLE advertising started at tier \(tier.rawValue)")
    }

    func stopAdvertising() {
        peripheralManager.stopAdvertising()
        peripheralManager.removeAllServices()
        DispatchQueue.main.async { self.isAdvertising = false }
        logger.info("BLE advertising stopped")
    }

    // MARK: - Connection

    private func connectToPeripheral(_ peripheral: CBPeripheral) {
        guard connectedPeripherals[peripheral.identifier] == nil else { return }
        peripheral.delegate = self
        centralManager.connect(peripheral, options: nil)
        discoveredPeripherals[peripheral.identifier] = peripheral
    }

    private func disconnectAll() {
        for (_, peripheral) in connectedPeripherals {
            centralManager.cancelPeripheralConnection(peripheral)
        }
        connectedPeripherals.removeAll()
        writeCharacteristics.removeAll()
        subscribedCentrals.removeAll()
    }

    // MARK: - Distance Estimation

    static func estimateDistance(rssi: Int) -> Float {
        let txPower: Float = -59.0 // Assumed TX power at 1 meter
        let pathLossExponent: Float = 2.7
        let ratio = (txPower - Float(rssi)) / (10.0 * pathLossExponent)
        return powf(10.0, ratio)
    }
}

// MARK: - CBCentralManagerDelegate

extension BLEManager: CBCentralManagerDelegate {
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        logger.info("Central state: \(central.state.rawValue)")
        if central.state == .poweredOn {
            startScanning(tier: currentScanTier)
        }
    }

    func centralManager(_ central: CBCentralManager, willRestoreState dict: [String: Any]) {
        if let peripherals = dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral] {
            for peripheral in peripherals {
                peripheral.delegate = self
                connectedPeripherals[peripheral.identifier] = peripheral
            }
        }
    }

    func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber
    ) {
        let identifier = peripheral.identifier.uuidString
        let rssiValue = RSSI.intValue
        let distance = Self.estimateDistance(rssi: rssiValue)
        let name = advertisementData[CBAdvertisementDataLocalNameKey] as? String

        let peer = MeshPeer(
            deviceId: identifier,
            displayName: name,
            rssi: rssiValue,
            estimatedDistance: distance,
            isConnected: connectedPeripherals[peripheral.identifier] != nil,
            lastSeen: Date()
        )

        DispatchQueue.main.async {
            self.discoveredPeers[identifier] = peer
        }

        // Auto-connect to discovered Flare peers
        connectToPeripheral(peripheral)
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        let id = peripheral.identifier.uuidString
        connectedPeripherals[peripheral.identifier] = peripheral
        logger.info("Connected to \(id)")

        connectionEvents.send(BLEConnectionEvent(identifier: id, connected: true))
        peripheral.discoverServices([Constants.serviceUUID])
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        let id = peripheral.identifier.uuidString
        connectedPeripherals.removeValue(forKey: peripheral.identifier)
        writeCharacteristics.removeValue(forKey: peripheral.identifier)
        peerInfoCharacteristics.removeValue(forKey: peripheral.identifier)
        logger.info("Disconnected from \(id)")

        connectionEvents.send(BLEConnectionEvent(identifier: id, connected: false))

        // Auto-reconnect
        if discoveredPeripherals[peripheral.identifier] != nil {
            centralManager.connect(peripheral, options: nil)
        }
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        logger.error("Failed to connect to \(peripheral.identifier.uuidString): \(error?.localizedDescription ?? "unknown")")
    }
}

// MARK: - CBPeripheralDelegate

extension BLEManager: CBPeripheralDelegate {
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard let services = peripheral.services else { return }
        for service in services where service.uuid == Constants.serviceUUID {
            peripheral.discoverCharacteristics([
                Constants.charMessageWriteUUID,
                Constants.charMessageNotifyUUID,
                Constants.charPeerInfoUUID,
            ], for: service)
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard let characteristics = service.characteristics else { return }

        for char in characteristics {
            switch char.uuid {
            case Constants.charMessageWriteUUID:
                writeCharacteristics[peripheral.identifier] = char

            case Constants.charMessageNotifyUUID:
                peripheral.setNotifyValue(true, for: char)

            case Constants.charPeerInfoUUID:
                peripheral.readValue(for: char)
                peerInfoCharacteristics[peripheral.identifier] = char

            default:
                break
            }
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard let data = characteristic.value, !data.isEmpty else { return }
        let id = peripheral.identifier.uuidString

        switch characteristic.uuid {
        case Constants.charMessageNotifyUUID, Constants.charMessageWriteUUID:
            logger.debug("Received \(data.count) bytes from \(id)")
            incomingMessages.send(IncomingBLEMessage(fromIdentifier: id, data: data))

        case Constants.charPeerInfoUUID:
            logger.debug("Received peer info from \(id)")

        default:
            break
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        if let error = error {
            logger.error("Write failed to \(peripheral.identifier.uuidString): \(error.localizedDescription)")
        }
    }
}

// MARK: - CBPeripheralManagerDelegate

extension BLEManager: CBPeripheralManagerDelegate {
    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        logger.info("Peripheral state: \(peripheral.state.rawValue)")
        if peripheral.state == .poweredOn {
            startAdvertising(tier: currentAdvertiseTier)
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, willRestoreState dict: [String: Any]) {
        // CoreBluetooth state restoration
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        for request in requests {
            if request.characteristic.uuid == Constants.charMessageWriteUUID,
               let data = request.value {
                let id = request.central.identifier.uuidString
                logger.debug("Received write (\(data.count) bytes) from \(id)")
                incomingMessages.send(IncomingBLEMessage(fromIdentifier: id, data: data))
            }
            peripheral.respond(to: request, withResult: .success)
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveRead request: CBATTRequest) {
        if request.characteristic.uuid == Constants.charPeerInfoUUID {
            request.value = localPeerInfoBytes
            peripheral.respond(to: request, withResult: .success)
        } else {
            peripheral.respond(to: request, withResult: .attributeNotFound)
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didSubscribeTo characteristic: CBCharacteristic) {
        let id = central.identifier.uuidString
        subscribedCentrals.append(central)
        logger.info("Central subscribed: \(id)")
        connectionEvents.send(BLEConnectionEvent(identifier: id, connected: true))
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didUnsubscribeFrom characteristic: CBCharacteristic) {
        let id = central.identifier.uuidString
        subscribedCentrals.removeAll { $0.identifier == central.identifier }
        logger.info("Central unsubscribed: \(id)")
        connectionEvents.send(BLEConnectionEvent(identifier: id, connected: false))
    }
}
