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
        val discovering: Boolean = false,
        val nearby: List<NearbyDevice> = emptyList(),
        /** Address the tablet is currently pairing with (bond in progress). */
        val pairingAddress: String? = null,
        val pairingMessage: String? = null,
        /** Remembered iPhone that is reconnected automatically. */
        val preferredHost: String? = null,
    ) {
        val canSend: Boolean get() = link == Link.CONNECTED
    }

    private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter
    private var hid: BluetoothHidDevice? = null
    private var host: BluetoothDevice? = null
    private val callbackExecutor = Executors.newSingleThreadExecutor()
    private val _state = MutableStateFlow(State(supported = adapter != null))
    val state: StateFlow<State> = _state.asStateFlow()

    data class NearbyDevice(
        val address: String,
        val name: String?,
        val isPhone: Boolean,
        val bonded: Boolean,
        val rssi: Int?,
    )

    /** Called when an iPhone becomes the connected HID host (to persist it for reconnects). */
    @Volatile var onHostConnected: ((address: String, name: String?) -> Unit)? = null

    private val nearbyMap = LinkedHashMap<String, NearbyDevice>()
    private var receiverRegistered = false
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

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
            else if (registered) scheduleReconnect(500)
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
            if (link == Link.CONNECTED) {
                mainHandler.removeCallbacks(reconnectRunnable)
                onHostConnected?.invoke(device.address, runCatching { device.name }.getOrNull())
                _state.update { it.copy(pairingAddress = null, pairingMessage = null, preferredHost = device.address) }
            } else if (link == Link.DISCONNECTED) {
                scheduleReconnect(RECONNECT_INTERVAL_MS)
            }
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

    // ---- Pairing from the tablet & automatic reconnect ----

    /** iPhone to reconnect to automatically (persisted by the app). */
    fun setPreferredHost(address: String?) {
        _state.update { it.copy(preferredHost = address?.takeIf { a -> a.isNotBlank() }) }
        if (_state.value.registered && _state.value.link != Link.CONNECTED) scheduleReconnect(300)
    }

    private val reconnectRunnable = Runnable { tryReconnect() }

    private fun scheduleReconnect(delayMs: Long) {
        mainHandler.removeCallbacks(reconnectRunnable)
        if (_state.value.preferredHost != null) mainHandler.postDelayed(reconnectRunnable, delayMs)
    }

    @SuppressLint("MissingPermission")
    private fun tryReconnect() {
        val st = _state.value
        val target = st.preferredHost ?: return
        if (!st.registered || st.link == Link.CONNECTED || st.link == Link.CONNECTING || st.pairingAddress != null) {
            if (st.link != Link.CONNECTED) scheduleReconnect(RECONNECT_INTERVAL_MS)
            return
        }
        val device = runCatching { adapter?.getRemoteDevice(target) }.getOrNull() ?: return
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            AppLog.d(TAG, "Auto-reconnect to $target")
            runCatching { hid?.connect(device) }
        }
        scheduleReconnect(RECONNECT_INTERVAL_MS)
    }

    private val btReceiver = object : android.content.BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(ctx: Context, intent: android.content.Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val d = intent.parcelableDevice() ?: return
                    val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).takeIf { it != Short.MIN_VALUE }?.toInt()
                    val name = runCatching { d.name }.getOrNull()
                    val major = d.bluetoothClass?.majorDeviceClass
                    val phone = major == android.bluetooth.BluetoothClass.Device.Major.PHONE ||
                        name?.contains("iPhone", true) == true
                    if (name == null && !phone) return
                    synchronized(nearbyMap) {
                        nearbyMap[d.address] = NearbyDevice(d.address, name, phone, d.bondState == BluetoothDevice.BOND_BONDED, rssi)
                        _state.update { it.copy(nearby = sortedNearby()) }
                    }
                }
                android.bluetooth.BluetoothAdapter.ACTION_DISCOVERY_FINISHED ->
                    _state.update { it.copy(discovering = false) }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val d = intent.parcelableDevice() ?: return
                    val bond = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                    if (d.address != _state.value.pairingAddress) return
                    when (bond) {
                        BluetoothDevice.BOND_BONDED -> {
                            AppLog.i(TAG, "Bonded with ${d.address}; connecting HID")
                            _state.update { it.copy(pairingMessage = "Terpasang. Menghubungkan kontrol…") }
                            mainHandler.postDelayed({ runCatching { hid?.connect(d) } }, 600)
                            mainHandler.postDelayed({
                                if (_state.value.link != Link.CONNECTED && _state.value.pairingAddress == d.address) {
                                    _state.update {
                                        it.copy(pairingAddress = null, pairingMessage = "Sudah terpasang, tetapi iPhone belum menerima kontrol. Di iPhone: Pengaturan → Bluetooth → ketuk nama tablet.")
                                    }
                                }
                            }, 10_000)
                        }
                        BluetoothDevice.BOND_NONE -> _state.update {
                            it.copy(pairingAddress = null, pairingMessage = "Pemasangan dibatalkan atau ditolak di iPhone. Coba lagi.")
                        }
                        BluetoothDevice.BOND_BONDING -> _state.update {
                            it.copy(pairingMessage = "Konfirmasi kode yang sama di iPhone dan tablet…")
                        }
                    }
                }
            }
        }
    }

    private fun sortedNearby() = nearbyMap.values.sortedWith(
        compareByDescending<NearbyDevice> { it.isPhone }.thenByDescending { it.rssi ?: -200 },
    )

    @Suppress("DEPRECATION")
    private fun android.content.Intent.parcelableDevice(): BluetoothDevice? =
        if (android.os.Build.VERSION.SDK_INT >= 33) getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

    private fun ensureReceiver() {
        if (receiverRegistered) return
        val f = android.content.IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(android.bluetooth.BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(context, btReceiver, f, ContextCompat.RECEIVER_EXPORTED)
        receiverRegistered = true
    }

    /**
     * Searches for nearby Bluetooth devices so the iPhone can be paired from the tablet.
     * The iPhone is only discoverable while its Settings → Bluetooth screen is open.
     */
    @SuppressLint("MissingPermission")
    fun startDiscovery() {
        val a = adapter ?: return
        if (!hasPermission() || ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            _state.update { it.copy(error = "Izin Bluetooth (Perangkat di sekitar) belum diberikan") }
            return
        }
        if (!a.isEnabled) { _state.update { it.copy(error = "Bluetooth mati") }; return }
        ensureReceiver()
        synchronized(nearbyMap) {
            nearbyMap.clear()
            // Already-paired phones are listed immediately.
            runCatching { a.bondedDevices }.getOrNull().orEmpty().forEach { d ->
                val name = runCatching { d.name }.getOrNull()
                val phone = d.bluetoothClass?.majorDeviceClass == android.bluetooth.BluetoothClass.Device.Major.PHONE ||
                    name?.contains("iPhone", true) == true
                if (phone) nearbyMap[d.address] = NearbyDevice(d.address, name, true, true, null)
            }
        }
        if (a.isDiscovering) a.cancelDiscovery()
        val ok = runCatching { a.startDiscovery() }.getOrDefault(false)
        _state.update { it.copy(discovering = ok, nearby = sortedNearby(), error = if (ok) null else "Pencarian Bluetooth gagal dimulai") }
    }

    @SuppressLint("MissingPermission")
    fun stopDiscovery() {
        runCatching { adapter?.cancelDiscovery() }
        _state.update { it.copy(discovering = false) }
    }

    /** Pairs with [address] (if needed) and connects the tablet's keyboard/pointer to it. */
    @SuppressLint("MissingPermission")
    fun pairAndConnect(address: String) {
        val a = adapter ?: return
        if (!_state.value.registered) { _state.update { it.copy(error = "Aktifkan kontrol Bluetooth terlebih dahulu") }; return }
        ensureReceiver()
        runCatching { a.cancelDiscovery() }
        val device = runCatching { a.getRemoteDevice(address) }.getOrNull() ?: return
        _state.update { it.copy(pairingAddress = address, preferredHost = address, discovering = false) }
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            _state.update { it.copy(pairingMessage = "Menghubungkan kontrol…") }
            runCatching { hid?.connect(device) }
            mainHandler.postDelayed({
                if (_state.value.link != Link.CONNECTED && _state.value.pairingAddress == address) {
                    _state.update {
                        it.copy(pairingAddress = null, pairingMessage = "iPhone belum menerima koneksi. Di iPhone: Pengaturan → Bluetooth → ketuk nama tablet.")
                    }
                }
            }, 8_000)
        } else {
            val ok = runCatching { device.createBond() }.getOrDefault(false)
            _state.update {
                it.copy(pairingMessage = if (ok) "Meminta pemasangan… konfirmasi di iPhone." else "Gagal memulai pemasangan", pairingAddress = if (ok) address else null)
            }
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
        mainHandler.removeCallbacks(reconnectRunnable)
        stopDiscovery()
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
        const val RECONNECT_INTERVAL_MS = 10_000L
    }
}
