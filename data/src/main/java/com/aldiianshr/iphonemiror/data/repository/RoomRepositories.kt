package com.aldiianshr.iphonemiror.data.repository

import com.aldiianshr.iphonemiror.data.db.HistoryDao
import com.aldiianshr.iphonemiror.data.db.HistoryEntity
import com.aldiianshr.iphonemiror.data.db.KnownDeviceDao
import com.aldiianshr.iphonemiror.data.db.KnownDeviceEntity
import com.aldiianshr.iphonemiror.domain.model.AppleDevice
import com.aldiianshr.iphonemiror.domain.model.ConnectionType
import com.aldiianshr.iphonemiror.domain.model.HistoryEntry
import com.aldiianshr.iphonemiror.domain.model.KnownDevice
import com.aldiianshr.iphonemiror.domain.repository.HistoryRepository
import com.aldiianshr.iphonemiror.domain.repository.KnownDeviceRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomHistoryRepository(private val dao: HistoryDao) : HistoryRepository {
    override fun observe(limit: Int): Flow<List<HistoryEntry>> = dao.observe(limit).map { list ->
        list.map {
            HistoryEntry(
                id = it.id,
                deviceId = it.deviceId,
                deviceName = it.deviceName,
                model = it.model,
                osVersion = it.osVersion,
                connectionType = runCatching { ConnectionType.valueOf(it.connectionType) }.getOrDefault(ConnectionType.WIFI),
                startedAt = it.startedAt,
                endedAt = it.endedAt,
                endReason = it.endReason,
                mirrored = it.mirrored,
            )
        }
    }

    override suspend fun start(device: AppleDevice, startedAt: Long): Long = dao.insert(
        HistoryEntity(
            deviceId = device.id,
            deviceName = device.name,
            model = device.model,
            osVersion = device.osVersion,
            connectionType = device.connectionType.name,
            startedAt = startedAt,
            endedAt = null,
            endReason = null,
            mirrored = false,
        ),
    )

    override suspend fun markMirrored(id: Long) = dao.markMirrored(id)
    override suspend fun finish(id: Long, endedAt: Long, reason: String) = dao.finish(id, endedAt, reason)
    override suspend fun clear() = dao.clear()
    suspend fun closeDangling(now: Long) = dao.closeDangling(now)
}

class RoomKnownDeviceRepository(private val dao: KnownDeviceDao) : KnownDeviceRepository {
    override fun observe(): Flow<List<KnownDevice>> = dao.observe().map { list -> list.map { it.toDomain() } }
    override suspend fun get(deviceId: String): KnownDevice? = dao.get(deviceId)?.toDomain()

    override suspend fun upsertSeen(device: AppleDevice, now: Long) {
        val existing = dao.get(device.id)
        dao.upsert(
            KnownDeviceEntity(
                deviceId = device.id,
                name = device.name ?: existing?.name,
                model = device.model ?: existing?.model,
                osVersion = device.osVersion ?: existing?.osVersion,
                allowed = existing?.allowed ?: false,
                blocked = existing?.blocked ?: false,
                firstSeen = existing?.firstSeen ?: now,
                lastSeen = now,
            ),
        )
    }

    override suspend fun setAllowed(deviceId: String, allowed: Boolean) = dao.setAllowed(deviceId, allowed)
    override suspend fun setBlocked(deviceId: String, blocked: Boolean) = dao.setBlocked(deviceId, blocked)
    override suspend fun forget(deviceId: String) = dao.delete(deviceId)
    override suspend fun forgetAll() = dao.clear()

    private fun KnownDeviceEntity.toDomain() =
        KnownDevice(deviceId, name, model, osVersion, allowed, blocked, firstSeen, lastSeen)
}
