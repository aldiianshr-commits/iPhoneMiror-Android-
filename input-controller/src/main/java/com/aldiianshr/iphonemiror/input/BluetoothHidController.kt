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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
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
        class Report(val id: Int, val data: ByteArray) : Out
        class Delay(val ms: Long) : Out
    }

    private val outQueue = LinkedBlockingQueue<Out>(4096)
    private val moveLock = Any()
    private var pendingDx = 0f
    private var pendingDy = 0f
    private var pendingWheel = 0f
    @Volatile private var buttons = 0

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
            HidReports.DESCRIPTOR,
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
                HidReports.ID_MOUSE -> HidReports.mouse(0, 0, 0)
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

    // ---- Pointer ----

    /** Accumulates relative pointer motion; the sender thread flushes it every few ms. */
    fun move(dx: Float, dy: Float) = synchronized(moveLock) { pendingDx += dx; pendingDy += dy }

    fun scroll(amount: Float) = synchronized(moveLock) { pendingWheel += amount }

    fun press(button: Int) { buttons = buttons or button; enqueue(Out.Report(HidReports.ID_MOUSE, HidReports.mouse(buttons, 0, 0))) }

    fun release(button: Int) { buttons = buttons and button.inv(); enqueue(Out.Report(HidReports.ID_MOUSE, HidReports.mouse(buttons, 0, 0))) }

    fun click(button: Int = HidReports.BUTTON_LEFT) {
        press(button); enqueue(Out.Delay(30)); release(button)
    }

    /**
     * Experimental absolute tap: pins the pointer to the top-left corner, then moves by a
     * calibrated amount. iOS applies pointer acceleration, so accuracy depends on the
     * AssistiveTouch tracking speed and the calibration value.
     */
    fun tapAt(u: Float, v: Float, countsAcross: Int, countsDown: Int) {
        repeat(40) { enqueue(Out.Report(HidReports.ID_MOUSE, HidReports.mouse(0, -127, -127))); enqueue(Out.Delay(4)) }
        enqueue(Out.Delay(60))
        val tx = (u.coerceIn(0f, 1f) * countsAcross).toInt()
        val ty = (v.coerceIn(0f, 1f) * countsDown).toInt()
        for ((sx, sy) in HidReports.splitMovement(tx, ty, maxStep = 6)) {
            enqueue(Out.Report(HidReports.ID_MOUSE, HidReports.mouse(0, sx, sy)))
            enqueue(Out.Delay(6))
        }
        enqueue(Out.Delay(40))
        click()
    }

    // ---- Keyboard ----

    fun typeText(text: String) {
        for (c in text) {
            val stroke = KeyMap.forChar(c) ?: continue
            key(stroke.usage, stroke.modifiers)
        }
    }

    fun key(usage: Int, modifiers: Int = 0) {
        enqueue(Out.Report(HidReports.ID_KEYBOARD, HidReports.keyboard(modifiers, listOf(usage))))
        enqueue(Out.Delay(12))
        enqueue(Out.Report(HidReports.ID_KEYBOARD, HidReports.keyboard(0, emptyList())))
        enqueue(Out.Delay(12))
    }

    fun consumer(bit: Int) {
        enqueue(Out.Report(HidReports.ID_CONSUMER, HidReports.consumer(bit)))
        enqueue(Out.Delay(40))
        enqueue(Out.Report(HidReports.ID_CONSUMER, HidReports.consumer(0)))
    }

    private fun enqueue(o: Out) {
        if (!_state.value.canSend) return
        outQueue.offer(o)
    }

    @SuppressLint("MissingPermission")
    private fun senderLoop() {
        while (true) {
            try {
                val item = outQueue.poll(8, TimeUnit.MILLISECONDS)
                when (item) {
                    is Out.Report -> send(item.id, item.data)
                    is Out.Delay -> Thread.sleep(item.ms)
                    null -> flushMotion()
                }
            } catch (_: InterruptedException) {
                return
            } catch (e: Exception) {
                AppLog.w(TAG, "HID send failed", e)
            }
        }
    }

    private fun flushMotion() {
        val (dx, dy, wheel) = synchronized(moveLock) {
            val x = pendingDx.toInt(); val y = pendingDy.toInt(); val w = pendingWheel.toInt()
            pendingDx -= x; pendingDy -= y; pendingWheel -= w
            Triple(x, y, w)
        }
        if (dx == 0 && dy == 0 && wheel == 0) return
        if (!_state.value.canSend) return
        val steps = HidReports.splitMovement(dx, dy)
        if (steps.isEmpty()) send(HidReports.ID_MOUSE, HidReports.mouse(buttons, 0, 0, wheel))
        steps.forEachIndexed { i, (sx, sy) ->
            send(HidReports.ID_MOUSE, HidReports.mouse(buttons, sx, sy, if (i == 0) wheel else 0))
        }
    }

    @SuppressLint("MissingPermission")
    private fun send(id: Int, data: ByteArray) {
        val h = hid ?: return
        val target = host ?: return
        h.sendReport(target, id, data)
    }

    companion object {
        private const val TAG = "BtHid"
    }
}
