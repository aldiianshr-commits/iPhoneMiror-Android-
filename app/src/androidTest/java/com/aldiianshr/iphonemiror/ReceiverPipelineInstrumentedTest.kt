package com.aldiianshr.iphonemiror

import android.graphics.SurfaceTexture
import android.os.Debug
import android.util.Log
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aldiianshr.iphonemiror.connection.ReceiverController
import com.aldiianshr.iphonemiror.domain.model.AppSettings
import com.aldiianshr.iphonemiror.domain.model.ReceiverStatus
import com.aldiianshr.iphonemiror.mirroring.MirrorEngine
import com.aldiianshr.iphonemiror.selftest.SelfTestRunner
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * Runs the complete receive pipeline on a real Android runtime: AirPlay control server,
 * legacy pairing, FairPlay decryption, MediaCodec hardware/software decode to a Surface.
 * The stream comes from the on-device self-test sender (not an iPhone).
 * Also records memory before/after to catch leaks in the decode path.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ReceiverPipelineInstrumentedTest {

    @get:Rule val hilt = HiltAndroidRule(this)

    @Inject lateinit var receiver: ReceiverController
    @Inject lateinit var engine: MirrorEngine
    @Inject lateinit var selfTest: SelfTestRunner

    private lateinit var texture: SurfaceTexture
    private lateinit var surface: Surface

    @Before fun setUp() {
        hilt.inject()
        texture = SurfaceTexture(false)
        texture.setDefaultBufferSize(1280, 720)
        surface = Surface(texture)
        engine.attachSurface(surface)
        receiver.start(AppSettings(receiverName = "PipelineTest", requireApproval = true, audioEnabled = false))
    }

    @After fun tearDown() {
        receiver.stop()
        engine.detachSurface(surface)
        surface.release()
        texture.release()
    }

    @Test fun selfTestStreamIsDecryptedAndDecoded() {
        assertTrue(receiver.isRunning)
        assertTrue(receiver.state.value.port > 0)
        val before = memorySnapshot()

        runBlocking { selfTest.run(seconds = 6, width = 1280, height = 720, fps = 30) }

        val result = selfTest.state.value
        Log.i(TAG, "Self-test result: $result")
        assertEquals("self-test should succeed: ${result.message}", true, result.success)

        Thread.sleep(800)
        val stats = engine.stats.value
        Log.i(TAG, "Stats after stream: $stats")
        assertTrue("frames must be received (got ${stats.framesReceived})", stats.framesReceived >= 60)
        assertTrue("frames must be decoded (got ${stats.framesDecoded})", stats.framesDecoded >= 30)
        assertEquals("no decoder errors", 0L, stats.decodeErrors)

        // Run a second session to check resources are released and reused.
        runBlocking { selfTest.run(seconds = 4, width = 1280, height = 720, fps = 30) }
        assertEquals(true, selfTest.state.value.success)
        Thread.sleep(1_500)
        Runtime.getRuntime().gc()
        Thread.sleep(500)
        val after = memorySnapshot()
        val growthKb = after.javaHeapKb - before.javaHeapKb
        Log.i(TAG, "MEMORY before=$before after=$after javaHeapGrowthKb=$growthKb")
        println("MEMORY_REPORT before=$before after=$after javaHeapGrowthKb=$growthKb")
        assertTrue("Java heap grew by $growthKb KB over two sessions", growthKb < 24 * 1024)
        assertTrue(receiver.state.value.status != ReceiverStatus.MIRRORING)
    }

    data class Mem(val javaHeapKb: Long, val nativeHeapKb: Long, val totalPssKb: Int)

    private fun memorySnapshot(): Mem {
        val rt = Runtime.getRuntime()
        val info = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        return Mem((rt.totalMemory() - rt.freeMemory()) / 1024, Debug.getNativeHeapAllocatedSize() / 1024, info.totalPss)
    }

    companion object { private const val TAG = "PipelineTest" }
}
