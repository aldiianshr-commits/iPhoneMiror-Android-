package com.aldiianshr.iphonemiror.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "connection_history", indices = [Index("startedAt")])
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceId: String,
    val deviceName: String?,
    val model: String?,
    val osVersion: String?,
    val connectionType: String,
    val startedAt: Long,
    val endedAt: Long?,
    val endReason: String?,
    val mirrored: Boolean,
)

@Entity(tableName = "known_devices")
data class KnownDeviceEntity(
    @PrimaryKey val deviceId: String,
    val name: String?,
    val model: String?,
    val osVersion: String?,
    val allowed: Boolean,
    val blocked: Boolean,
    val firstSeen: Long,
    val lastSeen: Long,
)

@Dao
interface HistoryDao {
    @Query("SELECT * FROM connection_history ORDER BY startedAt DESC LIMIT :limit")
    fun observe(limit: Int): Flow<List<HistoryEntity>>

    @Insert
    suspend fun insert(entity: HistoryEntity): Long

    @Query("UPDATE connection_history SET mirrored = 1 WHERE id = :id")
    suspend fun markMirrored(id: Long)

    @Query("UPDATE connection_history SET endedAt = :endedAt, endReason = :reason WHERE id = :id")
    suspend fun finish(id: Long, endedAt: Long, reason: String)

    @Query("UPDATE connection_history SET endedAt = :now, endReason = 'Aplikasi ditutup' WHERE endedAt IS NULL")
    suspend fun closeDangling(now: Long)

    @Query("DELETE FROM connection_history")
    suspend fun clear()
}

@Dao
interface KnownDeviceDao {
    @Query("SELECT * FROM known_devices ORDER BY lastSeen DESC")
    fun observe(): Flow<List<KnownDeviceEntity>>

    @Query("SELECT * FROM known_devices WHERE deviceId = :id")
    suspend fun get(id: String): KnownDeviceEntity?

    @Upsert
    suspend fun upsert(entity: KnownDeviceEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(entity: KnownDeviceEntity): Long

    @Query("UPDATE known_devices SET allowed = :allowed, blocked = CASE WHEN :allowed THEN 0 ELSE blocked END WHERE deviceId = :id")
    suspend fun setAllowed(id: String, allowed: Boolean)

    @Query("UPDATE known_devices SET blocked = :blocked, allowed = CASE WHEN :blocked THEN 0 ELSE allowed END WHERE deviceId = :id")
    suspend fun setBlocked(id: String, blocked: Boolean)

    @Query("DELETE FROM known_devices WHERE deviceId = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM known_devices")
    suspend fun clear()
}

@Database(entities = [HistoryEntity::class, KnownDeviceEntity::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun history(): HistoryDao
    abstract fun knownDevices(): KnownDeviceDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "iphonemiror.db").build()

        fun inMemory(context: Context): AppDatabase =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    }
}
