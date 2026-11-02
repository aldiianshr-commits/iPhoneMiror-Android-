package com.aldiianshr.iphonemiror.input

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.aldiianshr.iphonemiror.core.AppLog
import com.aldiianshr.iphonemiror.input.touch.HidCommand
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit

/**
 * Makes the tablet act as a Bluetooth keyboard + mouse for the iPhone, using Android's
 * official BluetoothHidDevice API. This is the legitimate way to control an iPhone from
 * another device: AirPlay itself carries no input back to the phone.
 *
 * Requirements on the iPhone: pair "this tablet" in Settings → Bluetooth, and enable
 * AssistiveTouch for pointer (mouse) control. Keyboard input works without it.
 */
class BluetoothHidController(private val context: Context) {

    enum class Link { DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING }

    data class State(
        val supported: Boolean = true,
        val bluetoothOn: Boolean = false,
        val permissionGranted: Boolean = false,
        val profileReady: Boolean = false,
        val registered: Boolean = false,
        val host: String? = null,
        val hostAddress: String? = null,
        val link: Link = Link.DISCONNECTED,
        val bondedHosts: List<Pair<String, String>> = emptyList(),
        val error: String? = null,
        val absolutePointer: Boolean = true,
    ) {
        val canSend: Boolean get() = link == Link.CONNECTED
    }

    private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter
    private var hid: BluetoothHidDevice? = null
    private var host: BluetoothDevice? = null
    private val callbackExecutor = Executors.newSingleThreadExecutor()
    private val _state = MutableStateFlow(State(supported = adapter != null))
    val state: StateFlow<State> = _state.asStateFlow()

    private sealed interface Out {
        class Report(val id: Int, val data: ByteArray, val key: String? = null) : Out
        class Delay(val ms: Long) : Out
    }

    private val outQueue = LinkedBlockingDeque<Out>(4096)
    @Volatile var absolutePointer: Boolean = true
        private set
    @Volatile var lastSendNanos: Long = 0
        private set
    @Volatile var lastSendOk: Boolean = true
        private set
    /** Counters for diagnostics (not in [state] to avoid recomposition per report). */
    val reportsSent = java.util.concurrent.atomic.AtomicLong()
    val reportsFailed = java.util.concurrent.atomic.AtomicLong()

    init {
        Thread(::senderLoop, "hid-sender").apply { isDaemon = true }.start()
    }

    fun hasPermission() = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
        PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun refresh() {
        val granted = hasPermission()
        val bonded = if (granted) runCatching {
            adapter?.bondedDevices.orEmpty().map { (it.name ?: it.address) to it.address }
        }.getOrDefault(emptyList()) else emptyList()
        _state.update {
            it.copy(
                supported = adapter != null,
                bluetoothOn = adapter?.isEnabled == true,
                permissionGranted = granted,
                bondedHosts = bonded,
            )
        }
    }

    /** Opens the HID Device profile and registers the keyboard/mouse SDP record. */
    @SuppressLint("MissingPermission")
    fun start() {
        refresh()
        val a = adapter ?: return
        if (!hasPermission()) { _state.update { it.copy(error = "Izin Bluetooth belum diberikan") }; return }
        if (!a.isEnabled) { _state.update { it.copy(error = "Bluetooth mati") }; return }
        if (hid != null) { register(); return }
        val ok = a.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile != BluetoothProfile.HID_DEVICE) return
                hid = proxy as BluetoothHidDevice
                _state.update { it.copy(profileReady = true, error = null) }
                register()
            }

            override fun onServiceDisconnected(profile: Int) {
                hid = null
                host = null
                _state.update { it.copy(profileReady = false, registered = false, link = Link.DISCONNECTED, host = null) }
            }
        }, BluetoothProfile.HID_DEVICE)
        if (!ok) {
            _state.update {
                it.copy(supported = false, error = "Profil Bluetooth HID Device tidak didukung oleh sistem perangkat ini")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun register() {
        val h = hid ?: return
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "iPhoneMiror Input",
            "Keyboard & Mouse dari tablet",
            "iPhoneMiror",
            BluetoothHidDevice.SUBCLASS1_COMBO,
            HidReports.descriptor(absolutePointer),
        )
        val ok = runCatching { h.registerApp(sdp, null, null, callbackExecutor, hidCallback) }.getOrDefault(false)
        if (!ok) _state.update { it.copy(error = "Registrasi HID gagal (mungkin dipakai aplikasi lain)") }
    }

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        @SuppressLint("MissingPermission")
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            AppLog.i(TAG, "HID app registered=$registered plugged=${pluggedDevice?.address}")
            _state.update { it.copy(registered = registered, error = if (registered) null else it.error) }
            if (registered && pluggedDevice != null) runCatching { hid?.connect(pluggedDevice) }
        }

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            val link = when (state) {
                BluetoothProfile.STATE_CONNECTED -> Link.CONNECTED
                BluetoothProfile.STATE_CONNECTING -> Link.CONNECTING
                BluetoothProfile.STATE_DISCONNECTING -> Link.DISCONNECTING
                else -> Link.DISCONNECTED
            }
            AppLog.i(TAG, "HID host ${device.address} → $link")
            host = if (link == Link.CONNECTED) device else if (host?.address == device.address) null else host
            val name = runCatching { device.name }.getOrNull()
            _state.update {
                it.copy(
                    link = link,
                    host = if (link == Link.DISCONNECTED) null else (name ?: device.address),
                    hostAddress = if (link == Link.DISCONNECTED) null else device.address,
                )
            }
        }

        @SuppressLint("MissingPermission")
        override fun onGetReport(device: BluetoothDevice?, type: Byte, id: Byte, bufferSize: Int) {
            val data = when (id.toInt()) {
                HidReports.ID_MOUSE -> if (absolutePointer) HidReports.absMouse(0, 0, 0) else HidReports.mouse(0, 0, 0)
                HidReports.ID_KEYBOARD -> HidReports.keyboard(0, emptyList())
                else -> HidReports.consumer(0)
            }
            runCatching { hid?.replyReport(device, type, id, data) }
        }
    }

    /** Connects to an already-paired host (the iPhone) by address. */
    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        val h = hid ?: return
        val device = runCatching { adapter?.getRemoteDevice(address) }.getOrNull() ?: return
        runCatching { h.connect(device) }.onFailure { AppLog.w(TAG, "connect failed", it) }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        val h = hid ?: return
        host?.let { runCatching { h.disconnect(it) } }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        disconnect()
        runCatching { hid?.unregisterApp() }
        hid?.let { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, it) }
        hid = null
        _state.update { it.copy(profileReady = false, registered = false, link = Link.DISCONNECTED, host = null) }
    }

    /**
     * Switches between the absolute (iOS 18+) and relative pointer descriptor. The SDP record
     * is re-registered; iOS caches the descriptor, so the iPhone must forget and re-pair the
     * tablet after a change.
     */
    @SuppressLint("MissingPermission")
    fun setPointerMode(absolute: Boolean) {
        if (absolute == absolutePointer) return
        absolutePointer = absolute
        _state.update { it.copy(absolutePointer = absolute) }
        val h = hid ?: return
        if (_state.value.registered) {
            runCatching { h.unregisterApp() }
            register()
        }
    }

    // ---- Command stream (from the touch translator) ----

    /** Queues HID commands; a pending report with the same coalesce key is replaced. */
    fun submit(commands: List<HidCommand>) {
        if (!_state.value.canSend || commands.isEmpty()) return
        synchronized(outQueue) {
            for (c in commands) when (c) {
                is HidCommand.Delay -> outQueue.offer(Out.Delay(c.ms))
                is HidCommand.Report -> {
                    val tail = outQueue.peekLast()
                    if (c.coalesceKey != null && tail is Out.Report && tail.key == c.coalesceKey) outQueue.pollLast()
                    outQueue.offer(Out.Report(c.id, c.data, c.coalesceKey))
                }
            }
        }
    }

    // ---- Keyboard / consumer ----

    fun typeText(text: String) {
        for (c in text) {
            val stroke = KeyMap.forChar(c) ?: continue
            key(stroke.usage, stroke.modifiers)
        }
    }

    fun key(usage: Int, modifiers: Int = 0) {
        submit(
            listOf(
                HidCommand.Report(HidReports.ID_KEYBOARD, HidReports.keyboard(modifiers, listOf(usage))),
                HidCommand.Delay(12),
                HidCommand.Report(HidReports.ID_KEYBOARD, HidReports.keyboard(0, emptyList())),
                HidCommand.Delay(12),
            ),
        )
    }

    /** Presses and releases one consumer-page usage (e.g. [HidReports.CONSUMER_HOME]). */
    fun consumer(usage: Int) {
        submit(
            listOf(
                HidCommand.Report(HidReports.ID_CONSUMER, HidReports.consumer(usage)),
                HidCommand.Delay(60),
                HidCommand.Report(HidReports.ID_CONSUMER, HidReports.consumer(0)),
            ),
        )
    }

    private fun senderLoop() {
        while (true) {
            try {
                when (val item = outQueue.poll(50, TimeUnit.MILLISECONDS)) {
                    is Out.Report -> send(item.id, item.data)
                    is Out.Delay -> Thread.sleep(item.ms)
                    null -> Unit
                }
            } catch (_: InterruptedException) {
                return
            } catch (e: Exception) {
                AppLog.w(TAG, "HID send failed", e)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun send(id: Int, data: ByteArray) {
        val h = hid ?: return
        val target = host ?: return
        val ok = runCatching { h.sendReport(target, id, data) }.getOrDefault(false)
        lastSendOk = ok
        lastSendNanos = System.nanoTime()
        if (ok) reportsSent.incrementAndGet() else reportsFailed.incrementAndGet()
        // Pace reports so iOS sees continuous motion rather than bursts.
        if (id == HidReports.ID_MOUSE) Thread.sleep(REPORT_INTERVAL_MS)
    }

    companion object {
        private const val TAG = "BtHid"
        const val REPORT_INTERVAL_MS = 8L
    }
}
