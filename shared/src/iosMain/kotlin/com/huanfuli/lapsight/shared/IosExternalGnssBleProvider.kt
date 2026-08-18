package com.huanfuli.lapsight.shared

import com.huanfuli.lapsight.shared.external.ExternalGnssConnectionPhase
import com.huanfuli.lapsight.shared.external.ExternalGnssConnectionState
import com.huanfuli.lapsight.shared.external.ExternalGnssHardwareValidationStatus
import com.huanfuli.lapsight.shared.external.ExternalGnssProtocol
import com.huanfuli.lapsight.shared.external.ExternalGnssReceiverIdentity
import com.huanfuli.lapsight.shared.external.ExternalGnssTransport
import com.huanfuli.lapsight.shared.external.Nmea0183ParseResult
import com.huanfuli.lapsight.shared.external.Nmea0183Parser
import com.huanfuli.lapsight.shared.external.toLocationSample
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.CoreBluetooth.CBAdvertisementDataLocalNameKey
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBManagerStatePoweredOff
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBManagerStateUnauthorized
import platform.CoreBluetooth.CBManagerStateUnsupported
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBPeripheralDelegateProtocol
import platform.CoreBluetooth.CBService
import platform.CoreBluetooth.CBUUID
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.Foundation.getBytes
import platform.darwin.NSObject

/**
 * iOS CoreBluetooth implementation of LapSight's external NMEA provider.
 *
 * The Cardputer advertises Nordic UART Service and sends the original NMEA
 * bytes through the notify characteristic. This class owns transport only;
 * decoding and normalization remain in the shared, replay-tested NMEA parser.
 */
@OptIn(ExperimentalForeignApi::class)
class IosExternalGnssBleProvider : LocationSampleProvider {

    private val serviceUuid = CBUUID.UUIDWithString(NUS_SERVICE_UUID)
    private val notifyUuid = CBUUID.UUIDWithString(NUS_TX_UUID)
    private val writeUuid = CBUUID.UUIDWithString(NUS_RX_UUID)
    private val bluetoothDelegate = IosExternalGnssBleDelegate(
        onStateChanged = ::handleCentralStateChanged,
        onDiscovered = ::handleDiscovered,
        onConnected = ::handleConnected,
        onConnectFailed = ::handleConnectFailed,
        onDisconnected = ::handleDisconnected,
        onServicesDiscovered = ::handleServicesDiscovered,
        onCharacteristicsDiscovered = ::handleCharacteristicsDiscovered,
        onNotificationStateChanged = ::handleNotificationStateChanged,
        onValueUpdated = ::handleValueUpdated,
    )
    private val centralManager = CBCentralManager(delegate = bluetoothDelegate, queue = null)

    private var parser = Nmea0183Parser()
    private val queue = ArrayDeque<LocationSample>()
    private var running = false
    private var selectedPeripheral: CBPeripheral? = null
    private var notifyCharacteristic: CBCharacteristic? = null
    private var writeCharacteristic: CBCharacteristic? = null
    private var selectedName: String? = null
    private var hasReceivedNmea = false

    private val mutableConnectionState = MutableStateFlow(disconnectedState())
    val connectionState: StateFlow<ExternalGnssConnectionState> =
        mutableConnectionState.asStateFlow()

    override val isRunning: Boolean
        get() = running

    override fun start() {
        if (running) return
        running = true
        parser = Nmea0183Parser()
        queue.clear()
        hasReceivedNmea = false
        beginScan(reconnecting = false)
    }

    override fun stop() {
        if (!running && mutableConnectionState.value.phase == ExternalGnssConnectionPhase.Disconnected) return
        running = false
        centralManager.stopScan()
        selectedPeripheral?.let(centralManager::cancelPeripheralConnection)
        clearPeripheral()
        mutableConnectionState.value = disconnectedState()
    }

    override fun reset() {
        stop()
        parser = Nmea0183Parser()
        queue.clear()
        hasReceivedNmea = false
    }

    fun rescan() {
        running = true
        centralManager.stopScan()
        selectedPeripheral?.let(centralManager::cancelPeripheralConnection)
        clearPeripheral()
        parser = Nmea0183Parser()
        queue.clear()
        hasReceivedNmea = false
        beginScan(reconnecting = false)
    }

    fun disconnect() = stop()

    override fun nextSample(): LocationSample? = queue.removeFirstOrNull()

    override fun drainPending(): List<LocationSample> {
        if (queue.isEmpty()) return emptyList()
        val drained = queue.toList()
        queue.clear()
        return drained
    }

    private fun handleCentralStateChanged(central: CBCentralManager) {
        if (running) beginScan(reconnecting = selectedPeripheral != null)
    }

    private fun handleDiscovered(
        central: CBCentralManager,
        didDiscoverPeripheral: CBPeripheral,
        advertisementData: Map<Any?, *>,
        RSSI: NSNumber,
    ) {
        if (!running || selectedPeripheral != null) return
        val advertisedName = advertisementData[CBAdvertisementDataLocalNameKey] as? String
        val name = advertisedName ?: didDiscoverPeripheral.name
        if (!matchesSupportedName(name)) return

        central.stopScan()
        selectedPeripheral = didDiscoverPeripheral
        selectedName = name
        didDiscoverPeripheral.delegate = bluetoothDelegate
        mutableConnectionState.value = baseState(
            phase = ExternalGnssConnectionPhase.Connecting,
            receiver = receiverIdentity(verified = false),
        )
        central.connectPeripheral(didDiscoverPeripheral, options = null)
    }

    private fun handleConnected(
        central: CBCentralManager,
        didConnectPeripheral: CBPeripheral,
    ) {
        if (!running || didConnectPeripheral != selectedPeripheral) return
        didConnectPeripheral.delegate = bluetoothDelegate
        mutableConnectionState.value = baseState(
            phase = ExternalGnssConnectionPhase.Connecting,
            receiver = receiverIdentity(verified = false),
        )
        didConnectPeripheral.discoverServices(listOf(serviceUuid))
    }

    private fun handleConnectFailed(
        central: CBCentralManager,
        didFailToConnectPeripheral: CBPeripheral,
        error: NSError?,
    ) {
        if (didFailToConnectPeripheral == selectedPeripheral) {
            reconnect(error?.localizedDescription ?: "Unable to connect to external GNSS.")
        }
    }

    private fun handleDisconnected(
        central: CBCentralManager,
        didDisconnectPeripheral: CBPeripheral,
        error: NSError?,
    ) {
        if (didDisconnectPeripheral != selectedPeripheral) return
        clearPeripheral()
        if (running) {
            mutableConnectionState.value = baseState(
                phase = ExternalGnssConnectionPhase.Reconnecting,
                message = error?.localizedDescription,
            )
            beginScan(reconnecting = true)
        } else {
            mutableConnectionState.value = disconnectedState()
        }
    }

    private fun handleServicesDiscovered(
        peripheral: CBPeripheral,
        didDiscoverServices: NSError?,
    ) {
        if (peripheral != selectedPeripheral) return
        if (didDiscoverServices != null) {
            reconnect(didDiscoverServices.localizedDescription)
            return
        }
        val service = peripheral.services
            ?.filterIsInstance<CBService>()
            ?.firstOrNull { it.UUID.UUIDString.equals(NUS_SERVICE_UUID, ignoreCase = true) }
        if (service == null) {
            reconnect("LapSight Nordic UART service was not found.")
            return
        }
        peripheral.discoverCharacteristics(listOf(notifyUuid, writeUuid), forService = service)
    }

    private fun handleCharacteristicsDiscovered(
        peripheral: CBPeripheral,
        didDiscoverCharacteristicsForService: CBService,
        error: NSError?,
    ) {
        if (peripheral != selectedPeripheral) return
        if (error != null) {
            reconnect(error.localizedDescription)
            return
        }

        notifyCharacteristic = didDiscoverCharacteristicsForService.characteristics
            ?.filterIsInstance<CBCharacteristic>()
            ?.firstOrNull { it.UUID.UUIDString.equals(NUS_TX_UUID, ignoreCase = true) }
        writeCharacteristic = didDiscoverCharacteristicsForService.characteristics
            ?.filterIsInstance<CBCharacteristic>()
            ?.firstOrNull { it.UUID.UUIDString.equals(NUS_RX_UUID, ignoreCase = true) }

        val notify = notifyCharacteristic
        if (notify == null) {
            reconnect("LapSight NMEA notify characteristic was not found.")
            return
        }
        peripheral.setNotifyValue(true, forCharacteristic = notify)
    }

    private fun handleNotificationStateChanged(
        peripheral: CBPeripheral,
        didUpdateNotificationStateForCharacteristic: CBCharacteristic,
        error: NSError?,
    ) {
        if (peripheral != selectedPeripheral || didUpdateNotificationStateForCharacteristic != notifyCharacteristic) {
            return
        }
        if (error != null || !didUpdateNotificationStateForCharacteristic.isNotifying) {
            reconnect(error?.localizedDescription ?: "Unable to subscribe to external GNSS data.")
            return
        }
        mutableConnectionState.value = baseState(
            phase = ExternalGnssConnectionPhase.Connected,
            receiver = receiverIdentity(verified = false),
        )
    }

    private fun handleValueUpdated(
        peripheral: CBPeripheral,
        didUpdateValueForCharacteristic: CBCharacteristic,
        error: NSError?,
    ) {
        if (peripheral != selectedPeripheral || didUpdateValueForCharacteristic != notifyCharacteristic) return
        if (error != null) {
            mutableConnectionState.value = mutableConnectionState.value.copy(message = error.localizedDescription)
            return
        }
        val bytes = didUpdateValueForCharacteristic.value?.toByteArray() ?: return
        if (bytes.isEmpty()) return
        acceptNmea(bytes)
    }

    private fun beginScan(reconnecting: Boolean) {
        if (!running) return
        when (centralManager.state) {
            CBManagerStatePoweredOn -> {
                if (selectedPeripheral != null) return
                mutableConnectionState.value = baseState(
                    phase = if (reconnecting) {
                        ExternalGnssConnectionPhase.Reconnecting
                    } else {
                        ExternalGnssConnectionPhase.Scanning
                    },
                )
                centralManager.stopScan()
                centralManager.scanForPeripheralsWithServices(
                    serviceUUIDs = listOf(serviceUuid),
                    options = null,
                )
            }
            CBManagerStateUnauthorized -> fail("Bluetooth permission is required for Cardputer GNSS.")
            CBManagerStatePoweredOff -> fail("Turn on Bluetooth to connect Cardputer GNSS.")
            CBManagerStateUnsupported -> fail("Bluetooth LE is not supported on this device.")
            else -> mutableConnectionState.value = baseState(ExternalGnssConnectionPhase.Scanning)
        }
    }

    private fun reconnect(message: String) {
        centralManager.stopScan()
        val peripheral = selectedPeripheral
        clearPeripheral()
        peripheral?.let(centralManager::cancelPeripheralConnection)
        if (!running) {
            mutableConnectionState.value = disconnectedState()
            return
        }
        mutableConnectionState.value = baseState(
            phase = ExternalGnssConnectionPhase.Reconnecting,
            message = message,
        )
        beginScan(reconnecting = true)
    }

    private fun fail(message: String) {
        mutableConnectionState.value = baseState(
            phase = ExternalGnssConnectionPhase.Failed,
            message = message,
        )
    }

    private fun acceptNmea(bytes: ByteArray) {
        parser.accept(bytes).forEach { result ->
            val sample = (result as? Nmea0183ParseResult.Snapshot)
                ?.snapshot
                ?.toLocationSample()
                ?: return@forEach
            enqueue(sample)
            if (!hasReceivedNmea) {
                hasReceivedNmea = true
                mutableConnectionState.value = baseState(
                    phase = ExternalGnssConnectionPhase.Connected,
                    receiver = receiverIdentity(verified = true),
                )
            }
        }
    }

    private fun enqueue(sample: LocationSample) {
        if (queue.lastOrNull()?.elapsedMillis == sample.elapsedMillis) {
            queue.removeLast()
            queue.addLast(sample)
            return
        }
        while (queue.size >= MAX_QUEUE_SIZE) queue.removeFirst()
        queue.addLast(sample)
    }

    private fun clearPeripheral() {
        selectedPeripheral?.delegate = null
        selectedPeripheral = null
        notifyCharacteristic = null
        writeCharacteristic = null
        selectedName = null
    }

    private fun receiverIdentity(verified: Boolean): ExternalGnssReceiverIdentity? {
        val peripheral = selectedPeripheral ?: return null
        return ExternalGnssReceiverIdentity(
            protocol = ExternalGnssProtocol.Nmea0183,
            displayName = selectedName ?: peripheral.name ?: "LapSight Cardputer",
            modelName = "Cardputer ADV + ATGM336H",
            deviceAddress = peripheral.identifier.UUIDString,
            firmwareVersion = CARDPUTER_FIRMWARE_VERSION,
            hardwareValidationStatus = if (verified) {
                ExternalGnssHardwareValidationStatus.Verified
            } else {
                ExternalGnssHardwareValidationStatus.Unverified
            },
        )
    }

    private fun baseState(
        phase: ExternalGnssConnectionPhase,
        receiver: ExternalGnssReceiverIdentity? = null,
        message: String? = null,
    ): ExternalGnssConnectionState = ExternalGnssConnectionState(
        phase = phase,
        protocol = ExternalGnssProtocol.Nmea0183,
        transport = ExternalGnssTransport.Ble,
        receiver = receiver,
        message = message,
    )

    private fun disconnectedState(): ExternalGnssConnectionState =
        baseState(ExternalGnssConnectionPhase.Disconnected)

    private fun matchesSupportedName(name: String?): Boolean =
        SUPPORTED_NAME_PREFIXES.any { prefix ->
            name?.startsWith(prefix, ignoreCase = true) == true
        }

    private fun NSData.toByteArray(): ByteArray {
        val byteCount = length.toInt()
        if (byteCount == 0) return ByteArray(0)
        return ByteArray(byteCount).also { output ->
            output.usePinned { pinned ->
                getBytes(pinned.addressOf(0), length)
            }
        }
    }

}

/**
 * Objective-C delegate kept separate from [LocationSampleProvider]. Kotlin/Native
 * does not allow a single type to mix Kotlin and Objective-C supertypes.
 * CoreBluetooth uses the main queue because the central is created with a null
 * dispatch queue.
 */
@OptIn(ExperimentalForeignApi::class)
private class IosExternalGnssBleDelegate(
    private val onStateChanged: (CBCentralManager) -> Unit,
    private val onDiscovered: (CBCentralManager, CBPeripheral, Map<Any?, *>, NSNumber) -> Unit,
    private val onConnected: (CBCentralManager, CBPeripheral) -> Unit,
    private val onConnectFailed: (CBCentralManager, CBPeripheral, NSError?) -> Unit,
    private val onDisconnected: (CBCentralManager, CBPeripheral, NSError?) -> Unit,
    private val onServicesDiscovered: (CBPeripheral, NSError?) -> Unit,
    private val onCharacteristicsDiscovered: (CBPeripheral, CBService, NSError?) -> Unit,
    private val onNotificationStateChanged: (CBPeripheral, CBCharacteristic, NSError?) -> Unit,
    private val onValueUpdated: (CBPeripheral, CBCharacteristic, NSError?) -> Unit,
) : NSObject(), CBCentralManagerDelegateProtocol, CBPeripheralDelegateProtocol {

    override fun centralManagerDidUpdateState(central: CBCentralManager) {
        onStateChanged(central)
    }

    override fun centralManager(
        central: CBCentralManager,
        didDiscoverPeripheral: CBPeripheral,
        advertisementData: Map<Any?, *>,
        RSSI: NSNumber,
    ) {
        onDiscovered(central, didDiscoverPeripheral, advertisementData, RSSI)
    }

    override fun centralManager(
        central: CBCentralManager,
        didConnectPeripheral: CBPeripheral,
    ) {
        onConnected(central, didConnectPeripheral)
    }

    @ObjCSignatureOverride
    override fun centralManager(
        central: CBCentralManager,
        didFailToConnectPeripheral: CBPeripheral,
        error: NSError?,
    ) {
        onConnectFailed(central, didFailToConnectPeripheral, error)
    }

    @ObjCSignatureOverride
    override fun centralManager(
        central: CBCentralManager,
        didDisconnectPeripheral: CBPeripheral,
        error: NSError?,
    ) {
        onDisconnected(central, didDisconnectPeripheral, error)
    }

    override fun peripheral(
        peripheral: CBPeripheral,
        didDiscoverServices: NSError?,
    ) {
        onServicesDiscovered(peripheral, didDiscoverServices)
    }

    override fun peripheral(
        peripheral: CBPeripheral,
        didDiscoverCharacteristicsForService: CBService,
        error: NSError?,
    ) {
        onCharacteristicsDiscovered(peripheral, didDiscoverCharacteristicsForService, error)
    }

    @ObjCSignatureOverride
    override fun peripheral(
        peripheral: CBPeripheral,
        didUpdateNotificationStateForCharacteristic: CBCharacteristic,
        error: NSError?,
    ) {
        onNotificationStateChanged(peripheral, didUpdateNotificationStateForCharacteristic, error)
    }

    @ObjCSignatureOverride
    override fun peripheral(
        peripheral: CBPeripheral,
        didUpdateValueForCharacteristic: CBCharacteristic,
        error: NSError?,
    ) {
        onValueUpdated(peripheral, didUpdateValueForCharacteristic, error)
    }
}

private const val NUS_SERVICE_UUID = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
private const val NUS_RX_UUID = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"
private const val NUS_TX_UUID = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"
private const val CARDPUTER_FIRMWARE_VERSION = "0.2.0-userdemo"
private const val MAX_QUEUE_SIZE = 1_000
private val SUPPORTED_NAME_PREFIXES = listOf(
    "LapSight-Cardputer",
    "LapSight-HUD",
    "RaceBox",
)
