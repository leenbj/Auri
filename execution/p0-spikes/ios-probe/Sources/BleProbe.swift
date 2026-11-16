import CoreBluetooth

/// BLE 只读探索：回答“FreeClip 2 有没有向第三方 App 开放的 BLE 通道，手势触发时有没有数据到达”。
/// 只扫描、连接、发现服务与特征、订阅通知；不向任何特征写入。
/// 只记录目标外设（名称含 FreeClip 或 HUAWEI）的细节，其他外设只计数，避免记录旁人设备名。
final class BleProbe: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate {
    private var central: CBCentralManager?
    private var target: CBPeripheral?
    private var discoveredOthers = 0
    private var candidates = Set<String>()
    private var bulkFrames = 0
    private var bulkBytes = 0
    private var lastBulkLog: UInt64 = 0

    func start() {
        guard central == nil else { return }
        ProbeLogger.shared.log("ble_probe_start")
        central = CBCentralManager(delegate: self, queue: nil)
    }

    func stop() {
        central?.stopScan()
        if let target { central?.cancelPeripheralConnection(target) }
        target = nil
        central = nil
        ProbeLogger.shared.log("ble_probe_stop")
    }

    // MARK: CBCentralManagerDelegate

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        ProbeLogger.shared.log("ble_state", ["state": central.state.rawValue])
        guard central.state == .poweredOn else { return }
        discoveredOthers = 0
        candidates.removeAll()
        central.scanForPeripherals(withServices: nil)
        DispatchQueue.main.asyncAfter(deadline: .now() + 15) { [weak self] in
            guard let self else { return }
            self.central?.stopScan()
            ProbeLogger.shared.log("ble_scan_finished", [
                "freeclip_found": self.target != nil,
                "huawei_candidates": Array(self.candidates).sorted(),
                "other_peripherals": self.discoveredOthers,
            ])
        }
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                        advertisementData: [String: Any], rssi RSSI: NSNumber) {
        let name = (advertisementData[CBAdvertisementDataLocalNameKey] as? String) ?? peripheral.name ?? ""
        let lowered = name.lowercased()
        guard lowered.contains("freeclip") || lowered.contains("huawei") else {
            discoveredOthers += 1
            return
        }
        let isFreeClip = lowered.contains("freeclip")
        // 同名候选只记一次；只有名称含 freeclip 的才连接，不再被 Eyewear 等其他华为设备抢走。
        guard candidates.insert(name).inserted else { return }
        let services = (advertisementData[CBAdvertisementDataServiceUUIDsKey] as? [CBUUID])?.map(\.uuidString) ?? []
        let manufacturer = (advertisementData[CBAdvertisementDataManufacturerDataKey] as? Data)
            .map { $0.prefix(4).map { String(format: "%02x", $0) }.joined() } ?? ""
        ProbeLogger.shared.log("ble_target_discovered", [
            "name": name,
            "rssi": RSSI.intValue,
            "advertised_services": services,
            "manufacturer_prefix": manufacturer,
            "connectable": (advertisementData[CBAdvertisementDataIsConnectable] as? Bool) ?? false,
        ])
        guard isFreeClip, target == nil else { return }
        target = peripheral
        peripheral.delegate = self
        central.stopScan()
        central.connect(peripheral)
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        ProbeLogger.shared.log("ble_connected")
        peripheral.discoverServices(nil)
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        ProbeLogger.shared.log("ble_connect_failed", ["error": error?.localizedDescription ?? "unknown"])
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        ProbeLogger.shared.log("ble_disconnected", ["error": error?.localizedDescription ?? "none"])
    }

    // MARK: CBPeripheralDelegate

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        let uuids = (peripheral.services ?? []).map(\.uuid.uuidString)
        ProbeLogger.shared.log("ble_services", ["services": uuids, "error": error?.localizedDescription ?? "none"])
        for service in peripheral.services ?? [] {
            peripheral.discoverCharacteristics(nil, for: service)
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        let items = (service.characteristics ?? []).map { characteristic -> [String: Any] in
            var props: [String] = []
            if characteristic.properties.contains(.read) { props.append("read") }
            if characteristic.properties.contains(.write) { props.append("write") }
            if characteristic.properties.contains(.writeWithoutResponse) { props.append("writeNoResp") }
            if characteristic.properties.contains(.notify) { props.append("notify") }
            if characteristic.properties.contains(.indicate) { props.append("indicate") }
            return ["uuid": characteristic.uuid.uuidString, "properties": props]
        }
        ProbeLogger.shared.log("ble_characteristics", ["service": service.uuid.uuidString, "characteristics": items])
        for characteristic in service.characteristics ?? []
        where characteristic.properties.contains(.notify) || characteristic.properties.contains(.indicate) {
            peripheral.setNotifyValue(true, for: characteristic)
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        let bytes = characteristic.value ?? Data()
        // 大帧（>96 字节）是持续的批量数据流，只汇总计数，避免淹没短帧；短帧完整记录，便于对照手势时间点。
        if bytes.count > 96 {
            bulkFrames += 1
            bulkBytes += bytes.count
            let now = ProbeLogger.monoNanos()
            if now - lastBulkLog > 2_000_000_000 {
                lastBulkLog = now
                ProbeLogger.shared.log("ble_bulk_summary", ["frames_since_start": bulkFrames, "bytes_since_start": bulkBytes])
            }
            return
        }
        ProbeLogger.shared.log("ble_notification", [
            "characteristic": characteristic.uuid.uuidString,
            "length": bytes.count,
            "hex": bytes.map { String(format: "%02x", $0) }.joined(),
        ])
    }
}
