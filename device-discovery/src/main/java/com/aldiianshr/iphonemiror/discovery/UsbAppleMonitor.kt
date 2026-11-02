package com.aldiianshr.iphonemiror.discovery

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import com.aldiianshr.iphonemiror.core.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Detects Apple devices attached through USB OTG using the Android USB Host API, and
 * handles the per-device USB permission prompt. Reads only what the USB descriptors
 * expose (product name, serial/UDID once permission is granted, interfaces).
 */
class UsbAppleMonitor(private val context: Context) {

    data class UsbAppleDevice(
        val deviceName: String,
        val vendorId: Int,
        val productId: Int,
        val manufacturer: String?,
        val product: String?,
        val serial: String?,
        val usbVersion: String?,
        val hasPermission: Boolean,
        val hasUsbmuxInterface: Boolean,
        val hasPtpInterface: Boolean,
        val configurationCount: Int,
    ) {
        val kind: String get() = when {
            product?.contains("iPhone", ignoreCase = true) == true -> "iPhone"
            product?.contains("iPad", ignoreCase = true) == true -> "iPad"
            productId in IPHONE_PIDS -> "iPhone"
            productId in IPAD_PIDS -> "iPad"
            else -> "Perangkat Apple"
        }
    }

    data class Status(
        val hostSupported: Boolean,
        val devices: List<UsbAppleDevice> = emptyList(),
        val lastError: String? = null,
    )

    private val usb: UsbManager? = context.getSystemService(UsbManager::class.java)
    private val hostSupported = context.packageManager.hasSystemFeature("android.hardware.usb.host")
    private val _status = MutableStateFlow(Status(hostSupported))
    val status: StateFlow<Status> = _status.asStateFlow()
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> AppLog.i(TAG, "USB device attached")
                UsbManager.ACTION_USB_DEVICE_DETACHED -> AppLog.i(TAG, "USB device detached")
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    AppLog.i(TAG, "USB permission ${if (granted) "granted" else "denied"}")
                    if (!granted) _status.value = _status.value.copy(lastError = "Izin USB ditolak oleh pengguna")
                }
            }
            refresh()
        }
    }

    fun start() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        registered = true
        refresh()
    }

    fun stop() {
        if (!registered) return
        runCatching { context.unregisterReceiver(receiver) }
        registered = false
    }

    fun refresh() {
        val manager = usb
        if (manager == null) {
            _status.value = Status(hostSupported = false, lastError = "USB Host API tidak tersedia")
            return
        }
        val list = manager.deviceList.values
            .filter { it.vendorId == APPLE_VENDOR_ID }
            .map { describe(manager, it) }
        _status.value = _status.value.copy(hostSupported = hostSupported, devices = list)
    }

    /** Shows the system USB permission dialog for the given device. */
    fun requestPermission(deviceName: String) {
        val manager = usb ?: return
        val device = manager.deviceList[deviceName] ?: return
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
        val pending = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        manager.requestPermission(device, pending)
    }

    fun findDevice(deviceName: String): UsbDevice? = usb?.deviceList?.get(deviceName)
    fun manager(): UsbManager? = usb

    private fun describe(manager: UsbManager, d: UsbDevice): UsbAppleDevice {
        val permitted = manager.hasPermission(d)
        var usbmux = false
        var ptp = false
        for (c in 0 until d.configurationCount) {
            val cfg = d.getConfiguration(c)
            for (i in 0 until cfg.interfaceCount) {
                val intf = cfg.getInterface(i)
                if (intf.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC &&
                    intf.interfaceSubclass == USBMUX_SUBCLASS && intf.interfaceProtocol == USBMUX_PROTOCOL
                ) usbmux = true
                if (intf.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE) ptp = true
            }
        }
        return UsbAppleDevice(
            deviceName = d.deviceName,
            vendorId = d.vendorId,
            productId = d.productId,
            manufacturer = d.manufacturerName,
            product = d.productName,
            serial = if (permitted) runCatching { d.serialNumber }.getOrNull() else null,
            usbVersion = d.version,
            hasPermission = permitted,
            hasUsbmuxInterface = usbmux,
            hasPtpInterface = ptp,
            configurationCount = d.configurationCount,
        )
    }

    companion object {
        private const val TAG = "UsbAppleMonitor"
        const val APPLE_VENDOR_ID = 0x05AC
        const val USBMUX_SUBCLASS = 0xFE
        const val USBMUX_PROTOCOL = 2
        private const val ACTION_USB_PERMISSION = "com.aldiianshr.iphonemiror.USB_PERMISSION"
        /** Product IDs Apple uses for iPhone / iPad in normal (non-recovery) mode. */
        val IPHONE_PIDS = setOf(0x1290, 0x1292, 0x1294, 0x1297, 0x129c, 0x12a0, 0x12a8)
        val IPAD_PIDS = setOf(0x129a, 0x129f, 0x12a2, 0x12a3, 0x12a9, 0x12aa, 0x12ab)
    }
}
