package com.aldiianshr.iphonemiror.domain.repository

import com.aldiianshr.iphonemiror.domain.model.AppSettings
import com.aldiianshr.iphonemiror.domain.model.AppleDevice
import com.aldiianshr.iphonemiror.domain.model.HistoryEntry
import com.aldiianshr.iphonemiror.domain.model.KnownDevice
import kotlinx.coroutines.flow.Flow

interface HistoryRepository {
    fun observe(limit: Int = 200): Flow<List<HistoryEntry>>
    suspend fun start(device: AppleDevice, startedAt: Long): Long
    suspend fun markMirrored(id: Long)
    suspend fun finish(id: Long, endedAt: Long, reason: String)
    suspend fun clear()
}

interface KnownDeviceRepository {
    fun observe(): Flow<List<KnownDevice>>
    suspend fun get(deviceId: String): KnownDevice?
    suspend fun upsertSeen(device: AppleDevice, now: Long)
    suspend fun setAllowed(deviceId: String, allowed: Boolean)
    suspend fun setBlocked(deviceId: String, blocked: Boolean)
    suspend fun forget(deviceId: String)
    suspend fun forgetAll()
}

interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun update(transform: (AppSettings) -> AppSettings)
}

/**
 * Persistent receiver identity: the Ed25519 seed used for AirPlay pairing and the
 * MAC-style receiver id advertised over mDNS. Both stay on the tablet.
 */
interface IdentityStore {
    fun loadOrCreateSeed(): ByteArray
    fun loadOrCreateDeviceId(): ByteArray
    fun createdAt(): Long?
    fun reset()
}
