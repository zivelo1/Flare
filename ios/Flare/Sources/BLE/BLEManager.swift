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

    @Published var discoveredPeers: [String: MeshPeer] = [:]
    @Published var isScanning = false
    @Published var isAdvertising = false

    let incomingMessages = PassthroughSubject<IncomingBLEMessage, Never>()
    let connectionEvents = PassthroughSubject<BLEConnectionEvent, Never>()

    var localPeerInfoBytes: Data = Data()

    private var scanTimer: Timer?
    private var pruneTimer: Timer?
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
            startScanning()
        }
        if peripheralManager.state == .poweredOn {
            startAdvertising()
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

    private func startScanning() {
        guard centralManager.state == .poweredOn else { return }
        centralManager.scanForPeripherals(
            withServices: [Constants.serviceUUID],
            options: [CBCentralManagerScanOptionAllowDuplicatesKey: true]
        )
        DispatchQueue.main.async { self.isScanning = true }
        logger.info("BLE scanning started")

        DispatchQueue.main.async {
            self.pruneTimer = Timer.scheduledTimer(withTimeInterval: Constants.peerStaleTimeoutSeconds / 2, repeats: true) { [weak self] _ in
                self?.pruneStale()
            }
        }
    }

    private func stopScanning() {
        centralManager.stopScan()
        DispatchQueue.main.async {
            self.isScanning = false
            self.pruneTimer?.invalidate()
            self.pruneTimer = nil
        }
        logger.info("BLE scanning stopped")
    }

    private func pruneStale() {
        let cutoff = Date().addingTimeInterval(-Constants.peerStaleTimeoutSeconds)
        DispatchQueue.main.async {
            self.discoveredPeers = self.discoveredPeers.filter { $0.value.lastSeen > cutoff }
        }
    }

    // MARK: - Advertising

    private func startAdvertising() {
        guard peripheralManager.state == .poweredOn else { return }

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
        logger.info("BLE advertising started")
    }

    private func stopAdvertising() {
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
            startScanning()
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
            startAdvertising()
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
