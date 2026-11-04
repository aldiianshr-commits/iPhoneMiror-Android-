package com.aldiianshr.iphonemiror.di

import com.aldiianshr.iphonemiror.connection.ReceiverController
import com.aldiianshr.iphonemiror.core.AppLog
import com.aldiianshr.iphonemiror.data.repository.RoomHistoryRepository
import com.aldiianshr.iphonemiror.domain.model.AppSettings
import com.aldiianshr.iphonemiror.domain.repository.SettingsRepository
import com.aldiianshr.iphonemiror.input.touch.TouchControlManager
import com.aldiianshr.iphonemiror.mirroring.MirrorEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Latest settings, readable synchronously from decoder / network threads. */
@Singleton
class SettingsHolder @Inject constructor() {
    private val _flow = MutableStateFlow(AppSettings())
    val flow: StateFlow<AppSettings> = _flow.asStateFlow()
    val current: AppSettings get() = _flow.value
    internal fun set(s: AppSettings) { _flow.value = s }
}

/** App-scope glue: propagates settings changes and closes stale history rows on start. */
@Singleton
class AppCoordinator @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val holder: SettingsHolder,
    private val receiver: ReceiverController,
    private val history: RoomHistoryRepository,
    private val touch: TouchControlManager,
    private val engine: MirrorEngine,
    private val hid: com.aldiianshr.iphonemiror.input.BluetoothHidController,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun init() {
        scope.launch { history.closeDangling(System.currentTimeMillis()) }
        scope.launch {
            settingsRepository.settings.distinctUntilChanged().collect { s ->
                holder.set(s)
                AppLog.debugEnabled = s.debugLogging
                receiver.applySettings(s)
                touch.configure(s)
            }
        }
        // Bluetooth keyboard/pointer: restore on start, remember the iPhone that connects.
        hid.onHostConnected = { address, name ->
            scope.launch {
                if (holder.current.hidHostAddress != address || holder.current.hidHostName != (name ?: "")) {
                    settingsRepository.update { it.copy(hidHostAddress = address, hidHostName = name ?: it.hidHostName) }
                }
            }
        }
        scope.launch {
            settingsRepository.settings.distinctUntilChanged { a, b -> a.hidEnabled == b.hidEnabled && a.hidHostAddress == b.hidHostAddress }
                .collect { s ->
                    hid.setPreferredHost(s.hidHostAddress.ifBlank { null })
                    if (s.hidEnabled && !hid.state.value.registered) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { hid.start() }
                    } else if (!s.hidEnabled && hid.state.value.registered) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { hid.stop() }
                    }
                }
        }
        // Feed the touch mapper the real stream geometry and the iPhone's address.
        scope.launch { engine.stats.collect { st -> if (st.hasVideo) touch.setVideoSize(st.width, st.height) } }
        scope.launch { receiver.state.collect { st -> touch.setPhoneAddress(st.device?.address?.takeUnless { it.startsWith("127.") }) } }
    }
}
