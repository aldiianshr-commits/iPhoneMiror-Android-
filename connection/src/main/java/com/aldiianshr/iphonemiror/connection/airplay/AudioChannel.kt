package com.aldiianshr.iphonemiror.connection.airplay

import com.aldiianshr.iphonemiror.core.AppLog
import com.aldiianshr.iphonemiror.core.readUIntBE
import com.aldiianshr.iphonemiror.core.readUShortBE
import com.aldiianshr.iphonemiror.domain.stream.AudioSink
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException

/**
 * Realtime audio channel (RTP over UDP). Each datagram is a 12-byte RTP header followed
 * by FairPlay AES-CBC encrypted audio (AAC-ELD for screen mirroring). A second UDP
 * socket receives control traffic (sync / retransmit), which is drained.
 */
class AudioChannel(
    private val decrypt: (ByteArray, Int) -> Unit,
    private val sink: AudioSink,
    private val onPacket: (Int) -> Unit,
) {
    private val data = DatagramSocket(null as java.net.SocketAddress?).apply {
        reuseAddress = true
        receiveBufferSize = 512 * 1024
        bind(InetSocketAddress(0))
    }
    private val control = DatagramSocket(null as java.net.SocketAddress?).apply {
        reuseAddress = true
        bind(InetSocketAddress(0))
    }
    val dataPort: Int get() = data.localPort
    val controlPort: Int get() = control.localPort

    @Volatile private var running = true
    private var lastSeq = -1

    fun start(): AudioChannel = apply {
        Thread(::dataLoop, "airplay-audio-$dataPort").apply { isDaemon = true }.start()
        Thread(::controlLoop, "airplay-audio-ctl-$controlPort").apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
        runCatching { data.close() }
        runCatching { control.close() }
        sink.onAudioStreamEnded()
    }

    private fun dataLoop() {
        val buf = ByteArray(4096)
        val packet = DatagramPacket(buf, buf.size)
        try {
            while (running) {
                packet.setData(buf, 0, buf.size)
                data.receive(packet)
                val len = packet.length
                if (len <= RTP_HEADER) continue
                onPacket(len)
                val seq = buf.readUShortBE(2)
                if (!isNewer(seq)) continue
                lastSeq = seq
                val timestamp = buf.readUIntBE(4)
                val payloadLen = len - RTP_HEADER
                val payload = buf.copyOfRange(RTP_HEADER, len)
                decrypt(payload, payloadLen)
                sink.onAudioFrame(payload, payloadLen, timestamp)
            }
        } catch (e: SocketException) {
            if (running) AppLog.w(TAG, "Audio socket closed", e)
        } catch (e: Exception) {
            AppLog.e(TAG, "Audio channel failed", e)
        }
    }

    private fun controlLoop() {
        val buf = ByteArray(2048)
        val packet = DatagramPacket(buf, buf.size)
        try {
            while (running) {
                packet.setData(buf, 0, buf.size)
                control.receive(packet)
            }
        } catch (_: Exception) {
        }
    }

    /** RTP sequence comparison with 16-bit wrap-around; late/duplicate packets are dropped. */
    private fun isNewer(seq: Int): Boolean {
        if (lastSeq < 0) return true
        val diff = (seq - lastSeq) and 0xFFFF
        return diff in 1..0x7FFF
    }

    companion object {
        private const val TAG = "AudioChannel"
        private const val RTP_HEADER = 12
    }
}
