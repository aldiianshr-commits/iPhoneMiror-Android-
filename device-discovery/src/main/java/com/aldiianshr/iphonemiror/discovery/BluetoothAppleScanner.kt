package com.aldiianshr.iphonemiror.discovery

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.Manifest
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.aldiianshr.iphonemiror.core.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Bluetooth discovery for nearby Apple devices. iPhones broadcast BLE advertisements with
 * Apple's company identifier (0x004C); that is the only signal used here. Bluetooth is
 * never used to carry video.
 */
class BluetoothAppleScanner(private val context: Context) {

    data class NearbyApple(
        val address: String,
        val name: String?,
        val rssi: Int,
        /** Continuity message type byte (first byte of Apple manufacturer data), if present. */
        val continuityType: Int?,
        val lastSeen: Long,
    )

    data class Status(
        val supported: Boolean,
        val enabled: Boolean,
        val scanning: Boolean = false,
        val permissionGranted: Boolean,
        val nearby: List<NearbyApple> = emptyList(),
        val bonded: List<Pair<String, String>> = emptyList(),
        val error: String? = null,
    )

    private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val handler = Handler(Looper.getMainLooper())
    private val found = LinkedHashMap<String, NearbyApple>()
    private val _status = MutableStateFlow(snapshot())
    val status: StateFlow<Status> = _status.asStateFlow()

    fun hasScanPermission() = granted(Manifest.permission.BLUETOOTH_SCAN)
    fun hasConnectPermission() = granted(Manifest.permission.BLUETOOTH_CONNECT)

    private fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    fun refresh() {
        _status.value = snapshot().copy(scanning = _status.value.scanning, nearby = found.values.toList())
    }

    @SuppressLint("MissingPermission")
    private fun snapshot(): Status {
        val bonded = if (adapter != null && hasConnectPermission()) {
            runCatching {
                adapter?.bondedDevices.orEmpty().map { (it.name ?: it.address) to describeClass(it) }
            }.getOrDefault(emptyList())
        } else emptyList()
        return Status(
            supported = adapter != null,
            enabled = adapter?.isEnabled == true,
            permissionGranted = hasScanPermission() && hasConnectPermission(),
            bonded = bonded,
        )
    }

    @SuppressLint("MissingPermission")
    fun scan(durationMs: Long = 12_000) {
        val scanner = adapter?.bluetoothLeScanner
        if (adapter == null || !adapter.isEnabled || scanner == null) {
            _status.value = snapshot().copy(error = "Bluetooth mati atau tidak tersedia")
            return
        }
        if (!hasScanPermission()) {
            _status.value = snapshot().copy(error = "Izin Bluetooth (Perangkat di sekitar) belum diberikan")
            return
        }
        synchronized(found) { found.clear() }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(null, settings, callback)
            _status.value = snapshot().copy(scanning = true)
            handler.postDelayed({ stopScan() }, durationMs)
        } catch (e: Exception) {
            AppLog.e(TAG, "BLE scan failed", e)
            _status.value = snapshot().copy(error = e.message)
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        handler.removeCallbacksAndMessages(null)
        runCatching { adapter?.bluetoothLeScanner?.stopScan(callback) }
        _status.value = snapshot().copy(scanning = false, nearby = found.values.toList())
    }

    private val callback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val data = result.scanRecord?.getManufacturerSpecificData(APPLE_COMPANY_ID) ?: return
            val entry = NearbyApple(
                address = result.device.address,
                name = runCatching { result.device.name }.getOrNull() ?: result.scanRecord?.deviceName,
                rssi = result.rssi,
                continuityType = data.firstOrNull()?.toInt()?.and(0xFF),
                lastSeen = System.currentTimeMillis(),
            )
            synchronized(found) { found[entry.address] = entry }
            _status.value = _status.value.copy(nearby = found.values.sortedByDescending { it.rssi })
        }

        override fun onScanFailed(errorCode: Int) {
            AppLog.w(TAG, "BLE scan failed: $errorCode")
            _status.value = _status.value.copy(scanning = false, error = "Scan BLE gagal (kode $errorCode)")
        }
    }

    private fun describeClass(device: BluetoothDevice): String =
        when (device.bluetoothClass?.majorDeviceClass) {
            android.bluetooth.BluetoothClass.Device.Major.PHONE -> "Telepon"
            android.bluetooth.BluetoothClass.Device.Major.COMPUTER -> "Komputer"
            android.bluetooth.BluetoothClass.Device.Major.AUDIO_VIDEO -> "Audio"
            android.bluetooth.BluetoothClass.Device.Major.PERIPHERAL -> "Periferal"
            else -> "Lainnya"
        }

    companion object {
        private const val TAG = "BtAppleScanner"
        const val APPLE_COMPANY_ID = 0x004C

        /** Human label for common Continuity advertisement types. */
        fun continuityLabel(type: Int?): String = when (type) {
            0x10 -> "Nearby Info"
            0x0F -> "Nearby Action"
            0x0C -> "Handoff"
            0x07 -> "AirPods/Proximity"
            0x12 -> "Find My"
            0x05 -> "AirDrop"
            0x09 -> "AirPlay Target"
            0x0A -> "AirPlay Source"
            0x0D -> "Hotspot"
            null -> "—"
            else -> "Tipe 0x%02X".format(type)
        }
    }
}
