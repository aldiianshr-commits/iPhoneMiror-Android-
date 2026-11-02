package com.aldiianshr.iphonemiror.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aldiianshr.iphonemiror.connection.ReceiverController
import com.aldiianshr.iphonemiror.core.AppLog
import com.aldiianshr.iphonemiror.di.SettingsHolder
import com.aldiianshr.iphonemiror.discovery.AirPlayAdvertiser
import com.aldiianshr.iphonemiror.discovery.AppleNetworkBrowser
import com.aldiianshr.iphonemiror.discovery.BluetoothAppleScanner
import com.aldiianshr.iphonemiror.discovery.UsbAppleMonitor
import com.aldiianshr.iphonemiror.domain.model.AppSettings
import com.aldiianshr.iphonemiror.domain.model.HistoryEntry
import com.aldiianshr.iphonemiror.domain.model.KnownDevice
import com.aldiianshr.iphonemiror.domain.model.LinkQuality
import com.aldiianshr.iphonemiror.domain.model.LinkQualityCalculator
import com.aldiianshr.iphonemiror.domain.model.ReceiverState
import com.aldiianshr.iphonemiror.domain.model.StreamStats
import com.aldiianshr.iphonemiror.domain.repository.HistoryRepository
import com.aldiianshr.iphonemiror.domain.repository.IdentityStore
import com.aldiianshr.iphonemiror.domain.repository.KnownDeviceRepository
import com.aldiianshr.iphonemiror.domain.repository.SettingsRepository
import com.aldiianshr.iphonemiror.input.BluetoothHidController
import com.aldiianshr.iphonemiror.mirroring.MirrorEngine
import com.aldiianshr.iphonemiror.selftest.SelfTestRunner
import com.aldiianshr.iphonemiror.service.ReceiverService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Receiver lifecycle, live stream state and the mirroring view. */
@HiltViewModel
class ReceiverViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val receiver: ReceiverController,
    private val engine: MirrorEngine,
    private val settingsHolder: SettingsHolder,
    private val selfTest: SelfTestRunner,
    advertiser: AirPlayAdvertiser,
    history: HistoryRepository,
) : ViewModel() {

    val state: StateFlow<ReceiverState> = receiver.state
    val stats: StateFlow<StreamStats> = engine.stats
    val settings: StateFlow<AppSettings> = settingsHolder.flow
    val advertiserStatus: StateFlow<AirPlayAdvertiser.Status> = advertiser.status
    val events: SharedFlow<ReceiverController.Event> = receiver.events
    val selfTestState: StateFlow<SelfTestRunner.Result> = selfTest.state

    val quality: StateFlow<LinkQuality> = combine(engine.stats, settingsHolder.flow) { s, cfg ->
        LinkQualityCalculator.evaluate(s, cfg.maxFps)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LinkQuality.NONE)

    val recent: StateFlow<List<HistoryEntry>> = history.observe(6)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun startReceiver() = ReceiverService.start(context)
    fun stopReceiver() = ReceiverService.stop(context)
    fun disconnect() = receiver.disconnect()
    fun approve(allow: Boolean, remember: Boolean) = receiver.respondToApproval(allow, remember)

    fun attachSurface(surface: android.view.Surface) = engine.attachSurface(surface)
    fun detachSurface(surface: android.view.Surface) = engine.detachSurface(surface)

    fun runSelfTest() {
        viewModelScope.launch { selfTest.run() }
    }
}

/** Device discovery over Wi-Fi (Bonjour), USB OTG and Bluetooth, plus the HID link. */
@HiltViewModel
class ConnectViewModel @Inject constructor(
    private val browser: AppleNetworkBrowser,
    private val usb: UsbAppleMonitor,
    private val ble: BluetoothAppleScanner,
    private val hid: BluetoothHidController,
) : ViewModel() {
    val networkDevices = browser.devices
    val browsing = browser.browsing
    val usbStatus: StateFlow<UsbAppleMonitor.Status> = usb.status
    val bleStatus: StateFlow<BluetoothAppleScanner.Status> = ble.status
    val hidState: StateFlow<BluetoothHidController.State> = hid.state

    fun startDiscovery() {
        browser.start()
        usb.start()
        ble.refresh()
        hid.refresh()
    }

    fun stopDiscovery() = browser.stop()
    fun refreshUsb() = usb.refresh()
    fun requestUsbPermission(deviceName: String) = usb.requestPermission(deviceName)
    fun scanBluetooth() = ble.scan()
    fun refreshBluetooth() { ble.refresh(); hid.refresh() }

    fun startHid() = hid.start()
    fun stopHid() = hid.stop()
    fun connectHid(address: String) = hid.connect(address)
    fun disconnectHid() = hid.disconnect()

    override fun onCleared() {
        browser.stop()
        ble.stopScan()
    }
}

/** Direct touch control: touch stream, keyboard, backend setup. */
@HiltViewModel
class TouchViewModel @Inject constructor(
    private val touch: com.aldiianshr.iphonemiror.input.touch.TouchControlManager,
    private val hid: BluetoothHidController,
    private val settingsRepo: SettingsRepository,
    settingsHolder: SettingsHolder,
) : ViewModel() {
    val state = touch.state
    val livePoints = touch.livePoints
    val hidState: StateFlow<BluetoothHidController.State> = hid.state
    val settings: StateFlow<AppSettings> = settingsHolder.flow
    private val _wdaMessage = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val wdaMessage: StateFlow<String?> = _wdaMessage
    private val _wdaBusy = kotlinx.coroutines.flow.MutableStateFlow(false)
    val wdaBusy: StateFlow<Boolean> = _wdaBusy

    fun onTouch(e: com.aldiianshr.iphonemiror.input.touch.TouchEvent) = touch.onTouch(e)
    fun type(text: String) = touch.typeText(text)
    fun key(usage: Int, modifiers: Int = 0) = touch.key(usage, modifiers)
    fun consumer(usage: Int) = touch.consumer(usage)
    fun home() = touch.home()
    fun mappingTest() = touch.runMappingTest()
    fun defaultWdaUrl(): String? = touch.defaultWdaUrl()
    fun startHid() = hid.start()
    fun update(transform: (AppSettings) -> AppSettings) = viewModelScope.launch { settingsRepo.update(transform) }

    fun connectWda(url: String) {
        if (url.isBlank()) { _wdaMessage.value = "Isi URL WebDriverAgent (mis. http://192.168.1.20:8100)"; return }
        viewModelScope.launch {
            _wdaBusy.value = true
            _wdaMessage.value = "Menghubungkan ke $url…"
            touch.connectWda(url.trim())
                .onSuccess { _wdaMessage.value = it }
                .onFailure { _wdaMessage.value = "Gagal: ${it.message}" }
            _wdaBusy.value = false
        }
    }

    fun disconnectWda() { touch.disconnectWda(); _wdaMessage.value = "Diputus" }
}

@HiltViewModel
class HistoryViewModel @Inject constructor(private val history: HistoryRepository) : ViewModel() {
    val entries: StateFlow<List<HistoryEntry>> = history.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun clear() = viewModelScope.launch { history.clear() }
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repo: SettingsRepository,
    holder: SettingsHolder,
    private val known: KnownDeviceRepository,
    private val receiver: ReceiverController,
    private val identityStore: IdentityStore,
) : ViewModel() {
    val settings: StateFlow<AppSettings> = holder.flow
    val knownDevices: StateFlow<List<KnownDevice>> = known.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val logs: StateFlow<List<AppLog.Entry>> = AppLog.entries

    fun update(transform: (AppSettings) -> AppSettings) = viewModelScope.launch { repo.update(transform) }
    fun setAllowed(id: String, allowed: Boolean) = viewModelScope.launch { known.setAllowed(id, allowed) }
    fun setBlocked(id: String, blocked: Boolean) = viewModelScope.launch { known.setBlocked(id, blocked) }
    fun forget(id: String) = viewModelScope.launch { known.forget(id) }
    fun forgetAll() = viewModelScope.launch { known.forgetAll() }
    fun clearLogs() = AppLog.clear()

    fun publicKey(): String? = receiver.publicKeyHex()
    fun receiverId(): String = receiver.receiverDeviceId()
    fun identityCreatedAt(): Long? = identityStore.createdAt()
    fun resetIdentity() = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        receiver.resetIdentity()
        known.forgetAll()
    }
}
