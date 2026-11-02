package com.aldiianshr.iphonemiror.connection

import com.aldiianshr.iphonemiror.connection.airplay.AirPlayReceiver
import com.aldiianshr.iphonemiror.connection.airplay.AirPlayReceiverConfig
import com.aldiianshr.iphonemiror.connection.airplay.SenderInfo
import com.aldiianshr.iphonemiror.connection.diagnostics.LoopbackSender
import com.aldiianshr.iphonemiror.core.AppLog
import com.aldiianshr.iphonemiror.domain.stream.AudioCodec
import com.aldiianshr.iphonemiror.domain.stream.AudioFormatInfo
import com.aldiianshr.iphonemiror.domain.stream.AudioSink
import com.aldiianshr.iphonemiror.domain.stream.VideoSink
import com.github.serezhka.airplay.protocol.pairing.PairingIdentity
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * End-to-end protocol test over localhost: a diagnostic sender performs the full legacy
 * pair-verify + FairPlay handshake against the real receiver, then streams encrypted
 * H.264 and AAC packets. Verifies the receiver decrypts them byte-for-byte.
 * (This exercises the receiver with test vectors; it is not a test against an iPhone.)
 */
class ReceiverLoopbackIntegrationTest {

    private val configs = CopyOnWriteArrayList<ByteArray>()
    private val frames = CopyOnWriteArrayList<ByteArray>()
    private val audio = CopyOnWriteArrayList<ByteArray>()
    private val senders = CopyOnWriteArrayList<SenderInfo>()
    private var pairVerified = false
    private val frameLatch = CountDownLatch(3)
    private val audioLatch = CountDownLatch(2)
    private val endedLatch = CountDownLatch(1)
    private lateinit var receiver: AirPlayReceiver
    private var audioFormat: AudioFormatInfo? = null

    @Before fun setUp() {
        AppLog.logcatEnabled = false
        val video = object : VideoSink {
            override fun onCodecConfig(spsPps: ByteArray) { configs += spsPps }
            override fun onVideoFrame(annexB: ByteArray, ptsUs: Long) { frames += annexB.copyOf(); frameLatch.countDown() }
            override fun onVideoStreamEnded() {}
        }
        val sound = object : AudioSink {
            override fun onAudioFormat(format: AudioFormatInfo) { audioFormat = format }
            override fun onAudioFrame(data: ByteArray, length: Int, rtpTimestamp: Long) {
                audio += data.copyOf(length); audioLatch.countDown()
            }
            override fun onVolume(linear: Float) {}
            override fun onAudioStreamEnded() {}
        }
        receiver = AirPlayReceiver(
            AirPlayReceiverConfig("TestPad", "AA:BB:CC:DD:EE:FF", 1920, 1080, 60, PairingIdentity.generate(), true),
            video, sound,
            object : AirPlayReceiver.Events {
                override fun onPairVerified(sessionId: String, verified: Boolean) { pairVerified = verified }
                override fun onSenderInfo(sessionId: String, info: SenderInfo, remote: String) { senders += info }
                override fun onSessionEnded(sessionId: String, reason: String) { endedLatch.countDown() }
            },
            { _, _, _ -> true },
        )
        receiver.start(0)
    }

    @After fun tearDown() = receiver.stop()

    @Test fun fullHandshakeAndEncryptedMirrorStream() {
        LoopbackSender("127.0.0.1", receiver.port).use { sender ->
            assertEquals("TestPad", sender.info())
            assertTrue(sender.handshake())
            assertTrue("pair-verify must succeed", pairVerified)
            assertEquals(200, sender.setupSession())
            assertEquals(LoopbackSender.SELF_TEST_NAME, senders.single().name)

            sender.setupMirror()
            val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1f, 0x11, 0x22)
            val pps = byteArrayOf(0x68, 0x33, 0x44)
            sender.sendCodecConfig(sps, pps, 1080, 1920)

            // Sizes chosen to exercise the cross-packet CTR residual handling.
            val sent = listOf(
                listOf(byteArrayOf(0x65) + ByteArray(1000) { (it * 7).toByte() }),
                listOf(byteArrayOf(0x41) + ByteArray(37) { it.toByte() }),
                listOf(byteArrayOf(0x41) + ByteArray(5000) { (it * 3).toByte() }, byteArrayOf(0x41, 0x01)),
            )
            sent.forEachIndexed { i, nals -> sender.sendFrame(nals, (i.toLong() + 1) shl 32) }
            assertTrue("frames not delivered", frameLatch.await(5, TimeUnit.SECONDS))

            assertArrayEquals(byteArrayOf(0, 0, 0, 1) + sps + byteArrayOf(0, 0, 0, 1) + pps, configs.single())
            sent.forEachIndexed { i, nals ->
                val expected = nals.fold(ByteArray(0)) { acc, nal -> acc + byteArrayOf(0, 0, 0, 1) + nal }
                assertArrayEquals("frame $i", expected, frames[i])
            }

            sender.setupAudio()
            assertEquals(AudioCodec.AAC_ELD, audioFormat?.codec)
            val a1 = ByteArray(200) { (it + 1).toByte() }
            val a2 = ByteArray(77) { (it * 5).toByte() }
            Thread.sleep(100)
            sender.sendAudio(a1, 480)
            sender.sendAudio(a2, 960)
            assertTrue("audio not delivered", audioLatch.await(5, TimeUnit.SECONDS))
            assertArrayEquals(a1, audio[0])
            assertArrayEquals(a2, audio[1])

            assertEquals(200, sender.feedback())
            assertEquals(200, sender.teardown())
        }
        assertTrue("session should end when sender disconnects", endedLatch.await(5, TimeUnit.SECONDS))
    }

    @Test fun rejectedSenderGets403() {
        receiver.stop()
        receiver = AirPlayReceiver(
            AirPlayReceiverConfig("TestPad", "AA:BB:CC:DD:EE:FF", 1920, 1080, 60, PairingIdentity.generate(), true),
            object : VideoSink {
                override fun onCodecConfig(spsPps: ByteArray) {}
                override fun onVideoFrame(annexB: ByteArray, ptsUs: Long) {}
                override fun onVideoStreamEnded() {}
            },
            object : AudioSink {
                override fun onAudioFormat(format: AudioFormatInfo) {}
                override fun onAudioFrame(data: ByteArray, length: Int, rtpTimestamp: Long) {}
                override fun onVolume(linear: Float) {}
                override fun onAudioStreamEnded() {}
            },
            object : AirPlayReceiver.Events {},
            { _, _, _ -> false },
        )
        receiver.start(0)
        LoopbackSender("127.0.0.1", receiver.port).use { sender ->
            sender.handshake()
            assertEquals(403, sender.setupSession())
        }
    }

    @Test fun persistedIdentityKeepsSamePublicKey() {
        val id = PairingIdentity.generate()
        val restored = PairingIdentity.fromSeed(id.seed())
        assertArrayEquals(id.publicKey(), restored.publicKey())
    }
}
