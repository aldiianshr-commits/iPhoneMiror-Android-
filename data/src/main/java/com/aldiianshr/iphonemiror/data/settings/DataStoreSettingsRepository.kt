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
import com.aldiianshr.iphonemiror.domain.model.HidOrientation
import com.aldiianshr.iphonemiror.domain.model.HidPointerMode
import com.aldiianshr.iphonemiror.domain.model.TouchBackendPref
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
        val TOUCH_BACKEND = stringPreferencesKey("touch_backend")
        val HID_POINTER = stringPreferencesKey("hid_pointer_mode")
        val HID_ORIENTATION = stringPreferencesKey("hid_orientation")
        val INVERT_SCROLL = booleanPreferencesKey("invert_scroll")
        val WDA_URL = stringPreferencesKey("wda_url")
        val TOUCH_INDICATORS = booleanPreferencesKey("touch_indicators")
        val TOUCH_STATUS_BAR = booleanPreferencesKey("touch_status_bar")
        val POINTER_SPEED = floatPreferencesKey("pointer_speed")
        val DEBUG = booleanPreferencesKey("debug_logging")
    }

    private val defaultName: String by lazy {
        val model = Build.MODEL.orEmpty().trim()
        if (model.isEmpty()) "iPhoneMiror" else "iPhoneMiror ($model)"
    }

    private fun read(p: Preferences): AppSettings {
        val d = AppSettings()
        return AppSettings(
            receiverName = p[Keys.NAME] ?: defaultName,
            resolution = p[Keys.RESOLUTION].enumOr(d.resolution),
            maxFps = p[Keys.FPS] ?: d.maxFps,
            audioEnabled = p[Keys.AUDIO] ?: d.audioEnabled,
            requireApproval = p[Keys.APPROVAL] ?: d.requireApproval,
            autoOpenMirroring = p[Keys.AUTO_OPEN] ?: d.autoOpenMirroring,
            keepScreenOn = p[Keys.SCREEN_ON] ?: d.keepScreenOn,
            lowLatencyDecoder = p[Keys.LOW_LATENCY] ?: d.lowLatencyDecoder,
            touchBackend = p[Keys.TOUCH_BACKEND].enumOr(d.touchBackend),
            hidPointerMode = p[Keys.HID_POINTER].enumOr(d.hidPointerMode),
            hidOrientation = p[Keys.HID_ORIENTATION].enumOr(d.hidOrientation),
            invertScroll = p[Keys.INVERT_SCROLL] ?: d.invertScroll,
            wdaUrl = p[Keys.WDA_URL] ?: d.wdaUrl,
            showTouchIndicators = p[Keys.TOUCH_INDICATORS] ?: d.showTouchIndicators,
            showTouchStatusBar = p[Keys.TOUCH_STATUS_BAR] ?: d.showTouchStatusBar,
            pointerSpeed = p[Keys.POINTER_SPEED] ?: d.pointerSpeed,
            debugLogging = p[Keys.DEBUG] ?: d.debugLogging,
        )
    }

    private inline fun <reified E : Enum<E>> String?.enumOr(default: E): E =
        this?.let { v -> enumValues<E>().firstOrNull { it.name == v } } ?: default

    override val settings: Flow<AppSettings> = context.settingsStore.data.map { read(it) }

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.settingsStore.edit { p ->
            val n = transform(read(p))
            p[Keys.NAME] = n.receiverName.take(40)
            p[Keys.RESOLUTION] = n.resolution.name
            p[Keys.FPS] = n.maxFps
            p[Keys.AUDIO] = n.audioEnabled
            p[Keys.APPROVAL] = n.requireApproval
            p[Keys.AUTO_OPEN] = n.autoOpenMirroring
            p[Keys.SCREEN_ON] = n.keepScreenOn
            p[Keys.LOW_LATENCY] = n.lowLatencyDecoder
            p[Keys.TOUCH_BACKEND] = n.touchBackend.name
            p[Keys.HID_POINTER] = n.hidPointerMode.name
            p[Keys.HID_ORIENTATION] = n.hidOrientation.name
            p[Keys.INVERT_SCROLL] = n.invertScroll
            p[Keys.WDA_URL] = n.wdaUrl.trim()
            p[Keys.TOUCH_INDICATORS] = n.showTouchIndicators
            p[Keys.TOUCH_STATUS_BAR] = n.showTouchStatusBar
            p[Keys.POINTER_SPEED] = n.pointerSpeed
            p[Keys.DEBUG] = n.debugLogging
        }
    }
}
