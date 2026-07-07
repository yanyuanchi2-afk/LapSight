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
import com.huanfuli.lapsight.shared.external.ExternalGnssConnectionPhase
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
 * RaceBox's official GATT service/characteristic UUIDs are only published on
 * request (06-RESEARCH.md); [serviceUuid]/[notifyCharacteristicUuid] default
 * to the widely used Nordic UART Service convention shared by many UBX/NMEA
 * BLE-serial bridges, but are constructor-overridable so a confirmed receiver
 * can supply its exact UUIDs. Real-device behavior for any of this remains
 * explicitly unvalidated (D-02) until hardware is available.
 */
class AndroidExternalGnssBleClient(
    private val context: Context,
    private val hasBlePermission: () -> Boolean,
    private val deviceAddress: String? = null,
    private val deviceNamePrefix: String = "RaceBox",
    private val serviceUuid: UUID = NORDIC_UART_SERVICE_UUID,
    private val notifyCharacteristicUuid: UUID = NORDIC_UART_TX_CHARACTERISTIC_UUID,
) : ExternalGnssByteStreamClient {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val bluetoothManager: BluetoothManager? =
        context.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    private var gatt: BluetoothGatt? = null
    private var running = false
    private var onBytes: ((ByteArray) -> Unit)? = null
    private var onPhase: ((ExternalGnssConnectionPhase) -> Unit)? = null

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!running) return
            val device = result.device
            val matches = deviceAddress?.let { device.address == it }
                ?: device.name?.startsWith(deviceNamePrefix, ignoreCase = true)
                ?: false
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
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    reportPhase(ExternalGnssConnectionPhase.Connecting)
                    discoverServices(g)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (running) {
                        // Unexpected drop while still selected: reflect a reconnect
                        // gap rather than a hard failure (D-06 reconnect-gap coverage).
                        reportPhase(ExternalGnssConnectionPhase.Reconnecting)
                        startScanInternal()
                    } else {
                        reportPhase(ExternalGnssConnectionPhase.Disconnected)
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                reportPhase(ExternalGnssConnectionPhase.Failed)
                return
            }
            val characteristic = g.getService(serviceUuid)
                ?.getCharacteristic(notifyCharacteristicUuid)
            if (characteristic == null) {
                reportPhase(ExternalGnssConnectionPhase.Failed)
                return
            }
            enableNotifications(g, characteristic)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (!running) return
            onBytes?.invoke(characteristic.value ?: return)
        }

        // API 33+ delivers the payload directly; the deprecated overload above
        // still fires on older platforms (minSdk 29, see build config).
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (!running) return
            onBytes?.invoke(value)
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
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }
    }

    @SuppressLint("MissingPermission")
    private fun discoverServices(g: BluetoothGatt) {
        if (!hasBlePermission()) return
        runCatching { g.discoverServices() }
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
        if (descriptor != null) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(descriptor)
                }
            }
        }
        reportPhase(if (enabled) ExternalGnssConnectionPhase.Connected else ExternalGnssConnectionPhase.Failed)
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        if (hasBlePermission()) {
            runCatching { gatt?.disconnect() }
            runCatching { gatt?.close() }
        }
        gatt = null
    }

    private fun reportPhase(phase: ExternalGnssConnectionPhase) {
        mainHandler.post { onPhase?.invoke(phase) }
    }

    companion object {
        val NORDIC_UART_SERVICE_UUID: UUID =
            UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val NORDIC_UART_TX_CHARACTERISTIC_UUID: UUID =
            UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
        private val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

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
}
