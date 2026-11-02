package com.aldiianshr.iphonemiror.di

import com.aldiianshr.iphonemiror.connection.ReceiverController
import com.aldiianshr.iphonemiror.core.AppLog
import com.aldiianshr.iphonemiror.data.repository.RoomHistoryRepository
import com.aldiianshr.iphonemiror.domain.model.AppSettings
import com.aldiianshr.iphonemiror.domain.repository.SettingsRepository
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
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun init() {
        scope.launch { history.closeDangling(System.currentTimeMillis()) }
        scope.launch {
            settingsRepository.settings.distinctUntilChanged().collect { s ->
                holder.set(s)
                AppLog.debugEnabled = s.debugLogging
                receiver.applySettings(s)
            }
        }
    }
}
