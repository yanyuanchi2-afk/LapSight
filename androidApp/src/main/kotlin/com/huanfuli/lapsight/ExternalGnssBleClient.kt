package com.huanfuli.lapsight

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.huanfuli.lapsight.shared.HudRemoteCommand
import com.huanfuli.lapsight.shared.external.ExternalGnssConnectionPhase
import com.huanfuli.lapsight.shared.external.ExternalGnssProtocol
import com.huanfuli.lapsight.shared.external.ExternalGnssReceiverIdentity
import com.huanfuli.lapsight.shared.toWireCommand
import java.util.UUID

/**
 * Byte-stream source for [ExternalGnssLocationProvider].
 *
 * Real BLE hardware and fake byte-stream test doubles both implement this
 * seam, so the provider's parsing/queueing/telemetry logic is fully testable
 * with injected byte chunks (D-02) even though this phase never validates a
 * physical receiver.
 */
interface ExternalGnssByteStreamClient {
    /**
     * Begin (or resume) producing bytes. [onBytes] is invoked once per
     * received chunk, in arrival order. [onConnectionPhase] reports transport
     * lifecycle changes (scanning/connecting/connected/reconnecting/failed) so
     * the provider can mirror them without owning transport details.
     */
    fun start(
        onBytes: (ByteArray) -> Unit,
        onConnectionPhase: (ExternalGnssConnectionPhase) -> Unit,
    )

    /** Stop producing bytes and release any transport resources. */
    fun stop()
}

/**
 * Android BLE implementation of [ExternalGnssByteStreamClient].
 *
 * Scans for a UART-style GATT notify characteristic exposed by an external
 * GNSS receiver, connects, subscribes to notifications, and forwards raw
 * bytes to the caller unmodified — decoding stays entirely in the shared
 * NMEA/RaceBox parsers (D-04, D-07).
 *
 * [serviceUuid]/[notifyCharacteristicUuid] use Nordic UART Service, shared by
 * the LapSight HUD firmware, the LapSight Cardputer companion firmware and
 * many BLE-serial GNSS receivers. Discovery keeps RaceBox as a compatible
 * legacy name while preferring the LapSight devices.
 */
class AndroidExternalGnssBleClient(
    private val context: Context,
    private val hasBlePermission: () -> Boolean,
    private val deviceAddress: String? = null,
    private val deviceNamePrefixes: List<String> = listOf(
        "LapSight-HUD",
        "LapSight-Cardputer",
        "RaceBox",
    ),
    private val serviceUuid: UUID = NORDIC_UART_SERVICE_UUID,
    private val notifyCharacteristicUuid: UUID = NORDIC_UART_TX_CHARACTERISTIC_UUID,
    private val writeCharacteristicUuid: UUID = NORDIC_UART_RX_CHARACTERISTIC_UUID,
) : ExternalGnssByteStreamClient {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val bluetoothManager: BluetoothManager? =
        context.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    private var gatt: BluetoothGatt? = null
    private var running = false
    private var onBytes: ((ByteArray) -> Unit)? = null
    private var onPhase: ((ExternalGnssConnectionPhase) -> Unit)? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private val pendingHudCommands = ArrayDeque<String>()
    private val pendingRtcmPackets = ArrayDeque<ByteArray>()
    private var inFlightHudWrite: HudGattWrite? = null
    private var negotiatedMtu = DEFAULT_ATT_MTU
    private var onHudMessage: ((String) -> Unit)? = null
    private var onGgaSentence: ((String) -> Unit)? = null
    private val hudLineDecoder = HudControlLineDecoder()
    private val ggaLineDecoder = NmeaGgaLineDecoder()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!running) return
            val device = result.device
            val advertisedName = result.scanRecord?.deviceName
                ?: runCatching { device.name }.getOrNull()
            val matches = matchesExternalGnssDevice(
                deviceName = advertisedName,
                deviceAddress = device.address,
                requestedAddress = deviceAddress,
                acceptedNamePrefixes = deviceNamePrefixes,
            )
            if (!matches) return
            stopScanInternal()
            connect(device)
        }

        override fun onScanFailed(errorCode: Int) {
            reportPhase(ExternalGnssConnectionPhase.Failed)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (gatt !== g) {
                closeGattInstance(g)
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        scheduleReconnect(g)
                        return
                    }
                    reportPhase(ExternalGnssConnectionPhase.Connecting)
                    requestFastLink(g)
                    if (!discoverServices(g)) scheduleReconnect(g)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (running) {
                        // Unexpected drop while still selected: reflect a reconnect
                        // gap rather than a hard failure (D-06 reconnect-gap coverage).
                        closeGattInstance(g)
                        if (gatt === g) gatt = null
                        reportReceiver(null)
                        reportPhase(ExternalGnssConnectionPhase.Reconnecting)
                        startScanInternal()
                    } else {
                        closeGattInstance(g)
                        if (gatt === g) gatt = null
                        reportReceiver(null)
                        reportPhase(ExternalGnssConnectionPhase.Disconnected)
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (gatt !== g || !running) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                scheduleReconnect(g)
                return
            }
            val characteristic = g.getService(serviceUuid)
                ?.getCharacteristic(notifyCharacteristicUuid)
            writeCharacteristic = g.getService(serviceUuid)
                ?.getCharacteristic(writeCharacteristicUuid)
            if (characteristic == null) {
                scheduleReconnect(g)
                return
            }
            enableNotifications(g, characteristic)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (gatt !== g || !running || descriptor.uuid != CLIENT_CHARACTERISTIC_CONFIG_UUID) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                reportPhase(ExternalGnssConnectionPhase.Connected)
                reportReceiver(
                    ExternalGnssReceiverIdentity(
                        protocol = ExternalGnssProtocol.Nmea0183,
                        displayName = g.device.name ?: g.device.address,
                        deviceAddress = g.device.address,
                    ),
                )
                enqueueHudCommand(HudRemoteCommand.RequestState.toWireCommand(), atFront = true)
                writePendingHudWrite()
            } else {
                scheduleReconnect(g)
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (gatt !== g || characteristic.uuid != writeCharacteristicUuid) return
            val completed = inFlightHudWrite
            inFlightHudWrite = null
            if (status != BluetoothGatt.GATT_SUCCESS) {
                completed?.let(::requeueFailedWrite)
                scheduleReconnect(g)
                return
            }
            writePendingHudWrite()
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (gatt === g && status == BluetoothGatt.GATT_SUCCESS) {
                negotiatedMtu = mtu.coerceAtLeast(DEFAULT_ATT_MTU)
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (!running) return
            handleIncomingBytes(characteristic.value ?: return)
        }

        // API 33+ delivers the payload directly; the deprecated overload above
        // still fires on older platforms (minSdk 29, see build config).
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (!running) return
            handleIncomingBytes(value)
        }
    }

    override fun start(
        onBytes: (ByteArray) -> Unit,
        onConnectionPhase: (ExternalGnssConnectionPhase) -> Unit,
    ) {
        if (running) return
        this.onBytes = onBytes
        this.onPhase = onConnectionPhase
        if (!hasBlePermission() || adapter?.isEnabled != true) {
            onConnectionPhase(ExternalGnssConnectionPhase.Failed)
            return
        }
        running = true
        startScanInternal()
    }

    override fun stop() {
        if (!running) {
            onBytes = null
            onPhase = null
            return
        }
        running = false
        stopScanInternal()
        closeGatt()
        onBytes = null
        onPhase = null
    }

    /** Queues a discrete, idempotent user intent for the HUD; commands are never state mirrors. */
    fun sendHudCommand(command: HudRemoteCommand) {
        mainHandler.post {
            enqueueHudCommand(command.toWireCommand())
            writePendingHudWrite()
        }
    }

    /** Queues raw RTCM bytes for the HUD, framed separately from text commands. */
    fun sendRtcmCorrections(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        mainHandler.post {
            val maxWriteBytes = (negotiatedMtu - ATT_WRITE_OVERHEAD)
                .coerceIn(RTCM_BLE_HEADER_BYTES + 1, MAX_ATT_WRITE_BYTES)
            frameRtcmForBle(bytes, maxWriteBytes).forEach { packet ->
                while (pendingRtcmPackets.size >= MAX_PENDING_RTCM_PACKETS) {
                    pendingRtcmPackets.removeFirst()
                }
                pendingRtcmPackets.addLast(packet)
            }
            writePendingHudWrite()
        }
    }

    /** Receives complete HUD ACK/state/error frames independently from the NMEA stream. */
    fun setHudMessageListener(listener: ((String) -> Unit)?) {
        onHudMessage = listener
    }

    /** Supplies the receiver's latest GGA to an NTRIP/VRS client. */
    fun setGgaSentenceListener(listener: ((String) -> Unit)?) {
        onGgaSentence = listener
    }

    @SuppressLint("MissingPermission")
    private fun startScanInternal() {
        if (!hasBlePermission()) {
            reportPhase(ExternalGnssConnectionPhase.Failed)
            return
        }
        reportPhase(ExternalGnssConnectionPhase.Scanning)
        val scanner = adapter?.bluetoothLeScanner ?: run {
            reportPhase(ExternalGnssConnectionPhase.Failed)
            return
        }
        runCatching {
            scanner.startScan(
                emptyList(),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                scanCallback,
            )
        }.onFailure { reportPhase(ExternalGnssConnectionPhase.Failed) }
    }

    @SuppressLint("MissingPermission")
    private fun stopScanInternal() {
        if (!hasBlePermission()) return
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice) {
        if (!hasBlePermission()) {
            reportPhase(ExternalGnssConnectionPhase.Failed)
            return
        }
        reportPhase(ExternalGnssConnectionPhase.Connecting)
        closeGatt()
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }
    }

    @SuppressLint("MissingPermission")
    private fun discoverServices(g: BluetoothGatt): Boolean {
        if (!hasBlePermission()) return false
        return runCatching { g.discoverServices() }.getOrDefault(false)
    }

    @SuppressLint("MissingPermission")
    private fun requestFastLink(g: BluetoothGatt) {
        if (!hasBlePermission()) return
        runCatching { g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) }
        runCatching { g.requestMtu(PREFERRED_MTU) }
    }

    @SuppressLint("MissingPermission")
    private fun enableNotifications(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        if (!hasBlePermission()) {
            reportPhase(ExternalGnssConnectionPhase.Failed)
            return
        }
        val enabled = runCatching { g.setCharacteristicNotification(characteristic, true) }
            .getOrDefault(false)
        val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
        if (!enabled || descriptor == null) {
            scheduleReconnect(g)
            return
        }
        val descriptorWriteStarted = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == 0
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(descriptor)
                }
            }.getOrDefault(false)
        if (!descriptorWriteStarted) {
            scheduleReconnect(g)
        }
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        val activeGatt = gatt
        gatt = null
        writeCharacteristic = null
        inFlightHudWrite = null
        pendingRtcmPackets.clear()
        negotiatedMtu = DEFAULT_ATT_MTU
        hudLineDecoder.reset()
        ggaLineDecoder.reset()
        if (activeGatt != null && hasBlePermission()) {
            runCatching { activeGatt.disconnect() }
            runCatching { activeGatt.close() }
        }
    }

    @SuppressLint("MissingPermission")
    private fun closeGattInstance(instance: BluetoothGatt) {
        if (hasBlePermission()) runCatching { instance.close() }
    }

    private fun scheduleReconnect(failedGatt: BluetoothGatt) {
        closeGattInstance(failedGatt)
        if (gatt === failedGatt) gatt = null
        if (!running) return
        reportPhase(ExternalGnssConnectionPhase.Failed)
        mainHandler.postDelayed({
            if (running && gatt == null) {
                reportPhase(ExternalGnssConnectionPhase.Reconnecting)
                startScanInternal()
            }
        }, RECONNECT_DELAY_MILLIS)
    }

    private fun reportPhase(phase: ExternalGnssConnectionPhase) {
        mainHandler.post { onPhase?.invoke(phase) }
    }

    private fun reportReceiver(identity: ExternalGnssReceiverIdentity?) {
        mainHandler.post { onReceiver?.invoke(identity) }
    }

    private var onReceiver: ((ExternalGnssReceiverIdentity?) -> Unit)? = null

    /** Optional: report the connected receiver identity for the device UI. */
    fun setReceiverListener(listener: (ExternalGnssReceiverIdentity?) -> Unit) {
        onReceiver = listener
    }

    @SuppressLint("MissingPermission")
    private fun writePendingHudWrite() {
        if (!running || !hasBlePermission() || inFlightHudWrite != null) return
        val activeGatt = gatt ?: return
        val characteristic = writeCharacteristic ?: return
        val write = pendingHudCommands.removeFirstOrNull()?.let { command ->
            HudGattWrite(command.encodeToByteArray(), command)
        } ?: pendingRtcmPackets.removeFirstOrNull()?.let { packet ->
            HudGattWrite(packet, null)
        } ?: return
        inFlightHudWrite = write
        val started = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                activeGatt.writeCharacteristic(
                    characteristic,
                    write.bytes,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                ) == 0
            } else {
                @Suppress("DEPRECATION")
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                characteristic.value = write.bytes
                @Suppress("DEPRECATION")
                activeGatt.writeCharacteristic(characteristic)
            }
        }.getOrDefault(false)
        if (!started) {
            inFlightHudWrite = null
            requeueFailedWrite(write)
        }
    }

    private fun enqueueHudCommand(command: String, atFront: Boolean = false) {
        if (pendingHudCommands.contains(command) || inFlightHudWrite?.command == command) return
        while (pendingHudCommands.size >= MAX_PENDING_HUD_COMMANDS) pendingHudCommands.removeFirst()
        if (atFront) pendingHudCommands.addFirst(command) else pendingHudCommands.addLast(command)
    }

    private fun handleIncomingBytes(bytes: ByteArray) {
        onBytes?.invoke(bytes)
        ggaLineDecoder.accept(bytes).forEach { line -> onGgaSentence?.invoke(line) }
        hudLineDecoder.accept(bytes).forEach { line ->
            mainHandler.post { onHudMessage?.invoke(line) }
        }
    }

    private fun requeueFailedWrite(write: HudGattWrite) {
        if (write.command != null) {
            pendingHudCommands.addFirst(write.command)
        } else {
            while (pendingRtcmPackets.size >= MAX_PENDING_RTCM_PACKETS) pendingRtcmPackets.removeLast()
            pendingRtcmPackets.addFirst(write.bytes)
        }
    }

    companion object {
        val NORDIC_UART_SERVICE_UUID: UUID =
            UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val NORDIC_UART_TX_CHARACTERISTIC_UUID: UUID =
            UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
        val NORDIC_UART_RX_CHARACTERISTIC_UUID: UUID =
            UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
        private val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
        private const val PREFERRED_MTU = 247
        private const val DEFAULT_ATT_MTU = 23
        private const val ATT_WRITE_OVERHEAD = 3
        private const val MAX_ATT_WRITE_BYTES = PREFERRED_MTU - ATT_WRITE_OVERHEAD
        private const val RTCM_BLE_HEADER_BYTES = 4
        private const val RECONNECT_DELAY_MILLIS = 750L
        private const val MAX_PENDING_HUD_COMMANDS = 32
        private const val MAX_PENDING_RTCM_PACKETS = 64

        /** True when the runtime BLE permissions this client needs are granted. */
        fun hasBlePermission(context: Context): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
                    PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED
            } else {
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
            }
    }

    private data class HudGattWrite(val bytes: ByteArray, val command: String?)
}

internal fun frameRtcmForBle(bytes: ByteArray, maxWriteBytes: Int): List<ByteArray> {
    require(maxWriteBytes > 4)
    val payloadBytes = maxWriteBytes - 4
    return bytes.asList().chunked(payloadBytes).map { chunk ->
        ByteArray(chunk.size + 4).also { packet ->
            packet[0] = 'L'.code.toByte()
            packet[1] = 'S'.code.toByte()
            packet[2] = 'R'.code.toByte()
            packet[3] = 1
            chunk.forEachIndexed { index, value -> packet[index + 4] = value }
        }
    }
}

/** Incrementally extracts only newline-delimited LapSight control frames from mixed NMEA traffic. */
internal class HudControlLineDecoder(
    private val maxLineBytes: Int = 256,
) {
    private val line = StringBuilder()
    private var overflow = false

    fun accept(bytes: ByteArray): List<String> {
        val messages = mutableListOf<String>()
        bytes.forEach { byte ->
            val value = byte.toInt().toChar()
            if (value == '\n') {
                if (!overflow) {
                    val complete = line.toString().trimEnd('\r')
                    if (complete.startsWith("LS1,")) messages += complete
                }
                reset()
            } else if (!overflow) {
                if (line.length < maxLineBytes) line.append(value) else overflow = true
            }
        }
        return messages
    }

    fun reset() {
        line.clear()
        overflow = false
    }
}

/** Extracts complete GGA sentences from the mixed HUD NMEA/control byte stream. */
internal class NmeaGgaLineDecoder(
    private val maxLineBytes: Int = 160,
) {
    private val line = StringBuilder()
    private var overflow = false

    fun accept(bytes: ByteArray): List<String> {
        val messages = mutableListOf<String>()
        bytes.forEach { byte ->
            val value = byte.toInt().toChar()
            if (value == '\n') {
                if (!overflow) {
                    val complete = line.toString().trimEnd('\r')
                    if (complete.firstOrNull() == '$' && complete.substringBefore(',').endsWith("GGA")) {
                        messages += complete
                    }
                }
                reset()
            } else if (!overflow) {
                if (line.length < maxLineBytes) line.append(value) else overflow = true
            }
        }
        return messages
    }

    fun reset() {
        line.clear()
        overflow = false
    }
}

internal fun matchesExternalGnssDevice(
    deviceName: String?,
    deviceAddress: String,
    requestedAddress: String?,
    acceptedNamePrefixes: List<String>,
): Boolean = requestedAddress?.equals(deviceAddress, ignoreCase = true)
    ?: acceptedNamePrefixes.any { prefix ->
        deviceName?.startsWith(prefix, ignoreCase = true) == true
    }
