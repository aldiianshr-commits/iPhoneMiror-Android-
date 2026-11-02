package com.aldiianshr.iphonemiror.connection.airplay

import com.aldiianshr.iphonemiror.core.AppLog
import com.aldiianshr.iphonemiror.core.readIntLE
import com.aldiianshr.iphonemiror.core.readLongLE
import com.aldiianshr.iphonemiror.domain.stream.VideoSink
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException

/**
 * TCP channel that carries the mirrored screen: a 128-byte little-endian header followed
 * by the payload. Type 0 = FairPlay-encrypted AVCC video, type 1 = plaintext codec config,
 * other types (heartbeat / stream reports) are skipped.
 */
class MirrorChannel(
    private val decrypt: (ByteArray) -> Unit,
    private val sink: VideoSink,
    private val listener: Listener,
) {
    interface Listener {
        fun onMirrorConnected(remote: String)
        fun onMirrorPacket(bytes: Int)
        fun onMirrorClosed(error: Throwable?)
        fun onSourceSize(width: Int, height: Int) {}
    }

    private val server = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(0))
    }
    val port: Int get() = server.localPort

    @Volatile private var socket: Socket? = null
    @Volatile private var running = true
    private val thread = Thread(::acceptLoop, "airplay-mirror-$port").apply { isDaemon = true }

    fun start(): MirrorChannel = apply { thread.start() }

    fun stop() {
        running = false
        runCatching { server.close() }
        runCatching { socket?.close() }
    }

    private fun acceptLoop() {
        var error: Throwable? = null
        try {
            val s = server.accept()
            socket = s
            s.tcpNoDelay = true
            s.receiveBufferSize = 1 shl 20
            listener.onMirrorConnected(s.remoteSocketAddress.toString())
            readLoop(DataInputStream(s.getInputStream().buffered(256 * 1024)))
        } catch (e: SocketException) {
            if (running) error = e
        } catch (e: IOException) {
            if (running) error = e
        } catch (e: Exception) {
            error = e
            AppLog.e(TAG, "Mirror channel failed", e)
        } finally {
            runCatching { server.close() }
            runCatching { socket?.close() }
            sink.onVideoStreamEnded()
            listener.onMirrorClosed(error)
        }
    }

    private fun readLoop(input: DataInputStream) {
        val header = ByteArray(HEADER_SIZE)
        var videoPackets = 0L
        while (running) {
            input.readFully(header)
            val size = header.readIntLE(0)
            val type = header[4].toInt() and 0xFF
            if (size < 0 || size > MAX_PAYLOAD) throw IOException("Invalid mirror payload size $size")
            val payload = ByteArray(size)
            input.readFully(payload)
            listener.onMirrorPacket(HEADER_SIZE + size)
            when (type) {
                TYPE_VIDEO -> {
                    decrypt(payload)
                    if (H264Framing.avccToAnnexBInPlace(payload)) {
                        // The header timestamp is an NTP-style 32.32 value; convert to µs.
                        val ntp = header.readLongLE(8)
                        val ptsUs = ((ntp ushr 32) * 1_000_000L) + (((ntp and 0xFFFFFFFFL) * 1_000_000L) ushr 32)
                        sink.onVideoFrame(payload, ptsUs)
                        videoPackets++
                    } else {
                        AppLog.w(TAG, "Dropped corrupt video packet ($size bytes) — decrypt mismatch?")
                    }
                }
                TYPE_CODEC -> {
                    val w = java.lang.Float.intBitsToFloat(header.readIntLE(40)).toInt()
                    val h = java.lang.Float.intBitsToFloat(header.readIntLE(44)).toInt()
                    if (w in 1..10000 && h in 1..10000) listener.onSourceSize(w, h)
                    val config = H264Framing.parseAvcConfig(payload)
                    if (config != null) {
                        AppLog.i(TAG, "Codec config: ${config.sps.size} SPS / ${config.pps.size} PPS, source ${w}x$h")
                        sink.onCodecConfig(config.toAnnexB())
                    } else {
                        AppLog.w(TAG, "Unparseable codec config (${payload.size} bytes)")
                    }
                }
                else -> AppLog.d(TAG, "Skipping mirror packet type $type ($size bytes)")
            }
        }
    }

    companion object {
        private const val TAG = "MirrorChannel"
        const val HEADER_SIZE = 128
        const val TYPE_VIDEO = 0
        const val TYPE_CODEC = 1
        private const val MAX_PAYLOAD = 16 * 1024 * 1024
    }
}
