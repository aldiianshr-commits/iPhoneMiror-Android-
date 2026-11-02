package com.aldiianshr.iphonemiror.data.settings

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aldiianshr.iphonemiror.domain.model.AppSettings
import com.aldiianshr.iphonemiror.domain.model.InputMode
import com.aldiianshr.iphonemiror.domain.model.StreamResolution
import com.aldiianshr.iphonemiror.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class DataStoreSettingsRepository(private val context: Context) : SettingsRepository {

    private object Keys {
        val NAME = stringPreferencesKey("receiver_name")
        val RESOLUTION = stringPreferencesKey("resolution")
        val FPS = intPreferencesKey("max_fps")
        val AUDIO = booleanPreferencesKey("audio")
        val APPROVAL = booleanPreferencesKey("require_approval")
        val AUTO_OPEN = booleanPreferencesKey("auto_open")
        val SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val LOW_LATENCY = booleanPreferencesKey("low_latency")
        val INPUT_MODE = stringPreferencesKey("input_mode")
        val POINTER_SPEED = floatPreferencesKey("pointer_speed")
        val DEBUG = booleanPreferencesKey("debug_logging")
    }

    private val defaultName: String by lazy {
        val model = Build.MODEL.orEmpty().trim()
        if (model.isEmpty()) "iPhoneMiror" else "iPhoneMiror ($model)"
    }

    override val settings: Flow<AppSettings> = context.settingsStore.data.map { p ->
        val d = AppSettings()
        AppSettings(
            receiverName = p[Keys.NAME] ?: defaultName,
            resolution = p[Keys.RESOLUTION]?.let { runCatching { StreamResolution.valueOf(it) }.getOrNull() } ?: d.resolution,
            maxFps = p[Keys.FPS] ?: d.maxFps,
            audioEnabled = p[Keys.AUDIO] ?: d.audioEnabled,
            requireApproval = p[Keys.APPROVAL] ?: d.requireApproval,
            autoOpenMirroring = p[Keys.AUTO_OPEN] ?: d.autoOpenMirroring,
            keepScreenOn = p[Keys.SCREEN_ON] ?: d.keepScreenOn,
            lowLatencyDecoder = p[Keys.LOW_LATENCY] ?: d.lowLatencyDecoder,
            inputMode = p[Keys.INPUT_MODE]?.let { runCatching { InputMode.valueOf(it) }.getOrNull() } ?: d.inputMode,
            pointerSpeed = p[Keys.POINTER_SPEED] ?: d.pointerSpeed,
            debugLogging = p[Keys.DEBUG] ?: d.debugLogging,
        )
    }

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.settingsStore.edit { p ->
            val current = AppSettings(
                receiverName = p[Keys.NAME] ?: defaultName,
                resolution = p[Keys.RESOLUTION]?.let { runCatching { StreamResolution.valueOf(it) }.getOrNull() } ?: AppSettings().resolution,
                maxFps = p[Keys.FPS] ?: AppSettings().maxFps,
                audioEnabled = p[Keys.AUDIO] ?: AppSettings().audioEnabled,
                requireApproval = p[Keys.APPROVAL] ?: AppSettings().requireApproval,
                autoOpenMirroring = p[Keys.AUTO_OPEN] ?: AppSettings().autoOpenMirroring,
                keepScreenOn = p[Keys.SCREEN_ON] ?: AppSettings().keepScreenOn,
                lowLatencyDecoder = p[Keys.LOW_LATENCY] ?: AppSettings().lowLatencyDecoder,
                inputMode = p[Keys.INPUT_MODE]?.let { runCatching { InputMode.valueOf(it) }.getOrNull() } ?: AppSettings().inputMode,
                pointerSpeed = p[Keys.POINTER_SPEED] ?: AppSettings().pointerSpeed,
                debugLogging = p[Keys.DEBUG] ?: AppSettings().debugLogging,
            )
            val n = transform(current)
            p[Keys.NAME] = n.receiverName.take(40)
            p[Keys.RESOLUTION] = n.resolution.name
            p[Keys.FPS] = n.maxFps
            p[Keys.AUDIO] = n.audioEnabled
            p[Keys.APPROVAL] = n.requireApproval
            p[Keys.AUTO_OPEN] = n.autoOpenMirroring
            p[Keys.SCREEN_ON] = n.keepScreenOn
            p[Keys.LOW_LATENCY] = n.lowLatencyDecoder
            p[Keys.INPUT_MODE] = n.inputMode.name
            p[Keys.POINTER_SPEED] = n.pointerSpeed
            p[Keys.DEBUG] = n.debugLogging
        }
    }
}
