package com.aldiianshr.iphonemiror.data

import androidx.test.core.app.ApplicationProvider
import com.aldiianshr.iphonemiror.data.db.AppDatabase
import com.aldiianshr.iphonemiror.data.repository.RoomHistoryRepository
import com.aldiianshr.iphonemiror.data.repository.RoomKnownDeviceRepository
import com.aldiianshr.iphonemiror.domain.model.AppleDevice
import com.aldiianshr.iphonemiror.domain.model.ConnectionType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RepositoriesTest {
    private lateinit var db: AppDatabase

    @Before fun setUp() { db = AppDatabase.inMemory(ApplicationProvider.getApplicationContext()) }
    @After fun tearDown() = db.close()

    private val device = AppleDevice(
        id = "11:22:33:44:55:66", name = "Aldi iPhone", model = "iPhone17,1",
        osVersion = "26.1", connectionType = ConnectionType.WIFI,
    )

    @Test fun historyLifecycle() = runTest {
        val repo = RoomHistoryRepository(db.history())
        val id = repo.start(device, 1_000)
        repo.markMirrored(id)
        repo.finish(id, 5_000, "Selesai")
        val entry = repo.observe().first().single()
        assertEquals("Aldi iPhone", entry.deviceName)
        assertTrue(entry.mirrored)
        assertEquals(5_000L, entry.endedAt)
        repo.clear()
        assertTrue(repo.observe().first().isEmpty())
    }

    @Test fun danglingSessionsAreClosed() = runTest {
        val repo = RoomHistoryRepository(db.history())
        repo.start(device, 1_000)
        repo.closeDangling(9_000)
        assertEquals("Aplikasi ditutup", repo.observe().first().single().endReason)
    }

    @Test fun knownDeviceAllowBlock() = runTest {
        val repo = RoomKnownDeviceRepository(db.knownDevices())
        repo.upsertSeen(device, 10)
        assertFalse(repo.get(device.id)!!.allowed)
        repo.setAllowed(device.id, true)
        assertTrue(repo.get(device.id)!!.allowed)
        repo.setBlocked(device.id, true)
        assertTrue(repo.get(device.id)!!.blocked)
        assertFalse(repo.get(device.id)!!.allowed)
        repo.upsertSeen(device.copy(name = null), 20)
        assertEquals("Aldi iPhone", repo.get(device.id)!!.name)
        assertEquals(10L, repo.get(device.id)!!.firstSeen)
        repo.forget(device.id)
        assertNull(repo.get(device.id))
    }
}
