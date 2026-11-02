package com.aldiianshr.iphonemiror.connection.airplay

import com.aldiianshr.iphonemiror.connection.rtsp.RtspCodec
import com.aldiianshr.iphonemiror.connection.rtsp.RtspRequest
import com.aldiianshr.iphonemiror.connection.rtsp.RtspResponse
import com.aldiianshr.iphonemiror.core.AppLog
import com.aldiianshr.iphonemiror.domain.stream.AudioCodec
import com.aldiianshr.iphonemiror.domain.stream.AudioFormatInfo
import com.aldiianshr.iphonemiror.domain.stream.AudioSink
import com.aldiianshr.iphonemiror.domain.stream.VideoSink
import com.github.serezhka.airplay.protocol.FairPlaySession
import com.github.serezhka.airplay.protocol.pairing.PairingIdentity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.BindException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class AirPlayReceiverConfig(
    val name: String,
    /** Stable MAC-style receiver id (AA:BB:CC:DD:EE:FF). */
    val deviceId: String,
    val width: Int,
    val height: Int,
    val maxFps: Int,
    val identity: PairingIdentity,
    val audioEnabled: Boolean,
)

/**
 * AirPlay screen-mirroring receiver (control channel + mirror/audio data channels).
 *
 * Implements the legacy-pairing + FairPlay SAP v3 receiver path described by public
 * reverse-engineering work (see docs/CONNECTION.md). It does not implement HomeKit
 * pairing, PIN/password pairing, or video URL playback.
 */
class AirPlayReceiver(
    private val config: AirPlayReceiverConfig,
    private val videoSink: VideoSink,
    private val audioSink: AudioSink,
    private val events: Events,
    private val approvalGate: ApprovalGate,
) {
    interface Events {
        fun onClientConnected(sessionId: String, remote: String) {}
        fun onPairVerified(sessionId: String, verified: Boolean) {}
        fun onSenderInfo(sessionId: String, info: SenderInfo, remote: String) {}
        fun onMirrorStarted(sessionId: String) {}
        fun onMirrorStopped(sessionId: String, error: Throwable?) {}
        fun onAudioStarted(sessionId: String, format: AudioFormatInfo) {}
        fun onSessionEnded(sessionId: String, reason: String) {}
        fun onTraffic(bytes: Int) {}
        fun onSourceSize(width: Int, height: Int) {}
    }

    /** Called (on a network thread) before a sender is allowed to start streaming. */
    fun interface ApprovalGate {
        fun approve(sessionId: String, info: SenderInfo, remote: String): Boolean
    }

    private inner class Session(val id: String) {
        val fairPlay = FairPlaySession(config.identity)
        @Volatile var mirror: MirrorChannel? = null
        @Volatile var audio: AudioChannel? = null
        @Volatile var approved = false
        @Volatile var lastActivity = System.currentTimeMillis()
        val connections = ConcurrentHashMap.newKeySet<Socket>()

        fun stopStreams() {
            mirror?.stop(); mirror = null
            audio?.stop(); audio = null
        }
    }

    private var server: ServerSocket? = null
    @Volatile private var running = false
    private val sessions = ConcurrentHashMap<String, Session>()
    private val connectionCounter = AtomicInteger()
    private var watchdog: Thread? = null

    val port: Int get() = server?.localPort ?: 0
    val isRunning: Boolean get() = running

    /** Binds the control port (7000 when free, otherwise an ephemeral port) and starts accepting. */
    @Synchronized
    fun start(preferredPort: Int = AirPlayProfile.DEFAULT_PORT): Int {
        if (running) return port
        val socket = ServerSocket().apply { reuseAddress = true }
        try {
            socket.bind(InetSocketAddress(preferredPort))
        } catch (e: BindException) {
            AppLog.w(TAG, "Port $preferredPort busy, using an ephemeral port")
            socket.bind(InetSocketAddress(0))
        }
        server = socket
        running = true
        Thread({ acceptLoop(socket) }, "airplay-control-accept").apply { isDaemon = true }.start()
        watchdog = Thread(::watchdogLoop, "airplay-watchdog").apply { isDaemon = true; start() }
        AppLog.i(TAG, "AirPlay receiver '${config.name}' listening on port ${socket.localPort}")
        return socket.localPort
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        runCatching { server?.close() }
        server = null
        disconnectAll("Receiver dihentikan")
        watchdog?.interrupt()
        watchdog = null
    }

    /** Closes every active session (user pressed Disconnect / Stop Mirroring). */
    fun disconnectAll(reason: String = "Diputus oleh pengguna") {
        for (session in sessions.values.toList()) endSession(session, reason)
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running) {
            try {
                val client = socket.accept()
                client.tcpNoDelay = true
                val connId = "conn-${connectionCounter.incrementAndGet()}"
                Thread({ handleConnection(client, connId) }, "airplay-$connId").apply { isDaemon = true }.start()
            } catch (e: SocketException) {
                if (running) AppLog.w(TAG, "Accept failed", e)
            } catch (e: IOException) {
                if (running) AppLog.e(TAG, "Accept failed", e)
            }
        }
    }

    private fun handleConnection(socket: Socket, connId: String) {
        val remote = socket.inetAddress?.hostAddress ?: "?"
        AppLog.i(TAG, "Control connection $connId from $remote")
        val input = socket.getInputStream().buffered(64 * 1024)
        val output = socket.getOutputStream().buffered(64 * 1024)
        var session: Session? = null
        var announced = false
        try {
            while (running && !socket.isClosed) {
                val request = RtspCodec.readRequest(input) ?: break
                val key = request.header("Active-Remote") ?: request.header("X-Apple-Session-ID") ?: connId
                val current = sessions.getOrPut(key) { Session(key) }
                if (session !== current) {
                    session = current
                    current.connections += socket
                }
                current.lastActivity = System.currentTimeMillis()
                if (!announced) {
                    announced = true
                    events.onClientConnected(key, remote)
                }
                val response = try {
                    dispatch(current, request, remote)
                } catch (e: Exception) {
                    AppLog.e(TAG, "Error handling $request", e)
                    RtspResponse(500, "Internal Server Error")
                }
                RtspCodec.writeResponse(output, request, response)
                if (response.status == 403) break
            }
        } catch (e: IOException) {
            if (running) AppLog.d(TAG, "Control $connId closed: ${e.message}")
        } finally {
            runCatching { socket.close() }
            val s = session
            if (s != null) {
                s.connections -= socket
                if (s.connections.isEmpty()) endSession(s, "Koneksi kontrol ditutup")
            }
        }
    }

    private fun dispatch(session: Session, request: RtspRequest, remote: String): RtspResponse {
        AppLog.d(TAG, "<- $request")
        return when {
            request.method == "GET" && request.path == "/info" -> RtspResponse(body = info()).plist()
            request.method == "POST" && request.path == "/pair-setup" -> {
                val out = ByteArrayOutputStream(32)
                session.fairPlay.pairSetup(out)
                RtspResponse(body = out.toByteArray()).octets()
            }
            request.method == "POST" && request.path == "/pair-verify" -> {
                val out = ByteArrayOutputStream(96)
                session.fairPlay.pairVerify(ByteArrayInputStream(request.body), out)
                if (request.body.isNotEmpty() && request.body[0].toInt() == 0) {
                    events.onPairVerified(session.id, session.fairPlay.isPairVerified)
                }
                RtspResponse(body = out.toByteArray()).octets()
            }
            request.method == "POST" && request.path == "/fp-setup" -> {
                val out = ByteArrayOutputStream(164)
                session.fairPlay.fairPlaySetup(ByteArrayInputStream(request.body), out)
                RtspResponse(body = out.toByteArray()).octets()
            }
            request.method == "SETUP" -> handleSetup(session, request, remote)
            request.method == "RECORD" -> RtspResponse()
                .header("Audio-Latency", "11025")
                .header("Audio-Jack-Status", "connected; type=analog")
            request.method == "GET_PARAMETER" -> RtspResponse(body = "volume: 0.000000\r\n".toByteArray())
                .header("Content-Type", "text/parameters")
            request.method == "SET_PARAMETER" -> {
                handleSetParameter(request)
                RtspResponse().header("Audio-Jack-Status", "connected; type=analog")
            }
            request.method == "POST" && request.path == "/feedback" -> RtspResponse()
            request.method == "FLUSH" -> RtspResponse()
            request.method == "TEARDOWN" -> handleTeardown(session, request)
            request.method == "OPTIONS" -> RtspResponse().header(
                "Public",
                "ANNOUNCE, SETUP, RECORD, FLUSH, TEARDOWN, OPTIONS, GET_PARAMETER, SET_PARAMETER, POST, GET",
            )
            request.method == "POST" && request.path == "/audioMode" -> RtspResponse()
            request.method == "ANNOUNCE" -> RtspResponse()
            else -> {
                AppLog.w(TAG, "Unsupported request: $request")
                RtspResponse(404, "Not Found")
            }
        }
    }

    private fun info(): ByteArray = AirPlayProfile.infoPlist(
        name = config.name,
        width = config.width,
        height = config.height,
        fps = config.maxFps,
        publicKey = config.identity.publicKey(),
        deviceId = config.deviceId,
    )

    private fun handleSetup(session: Session, request: RtspRequest, remote: String): RtspResponse {
        val parsed = SetupRequest.parse(request.body)
        if (parsed != null && parsed.sender.hasAnything) {
            events.onSenderInfo(session.id, parsed.sender, remote)
        }
        if (!session.approved) {
            val info = parsed?.sender ?: SenderInfo(null, null, null, null, null, null, null, null, null)
            session.approved = approvalGate.approve(session.id, info, remote)
            if (!session.approved) {
                AppLog.w(TAG, "Sender $remote rejected by user")
                return RtspResponse(403, "Forbidden")
            }
        }
        // Let the protocol module record ekey/eiv/streamConnectionID. It may throw for
        // audio formats it does not model; that must not abort the SETUP.
        runCatching { session.fairPlay.rtspSetup(ByteArrayInputStream(request.body)) }
            .onFailure { AppLog.w(TAG, "Protocol SETUP parse issue: ${it.message}") }

        val stream = parsed?.streams?.firstOrNull() ?: return RtspResponse()
        return when (stream.type) {
            AirPlayProfile.STREAM_TYPE_SCREEN -> {
                session.mirror?.stop()
                val channel = MirrorChannel(
                    decrypt = { session.fairPlay.decryptVideo(it) },
                    sink = videoSink,
                    listener = object : MirrorChannel.Listener {
                        override fun onMirrorConnected(remote: String) {
                            AppLog.i(TAG, "Mirror stream connected from $remote")
                            events.onMirrorStarted(session.id)
                        }
                        override fun onMirrorPacket(bytes: Int) {
                            session.lastActivity = System.currentTimeMillis()
                            events.onTraffic(bytes)
                        }
                        override fun onMirrorClosed(error: Throwable?) = events.onMirrorStopped(session.id, error)
                        override fun onSourceSize(width: Int, height: Int) = events.onSourceSize(width, height)
                    },
                ).start()
                session.mirror = channel
                AppLog.i(TAG, "Mirror SETUP: data port ${channel.port}")
                RtspResponse(body = AirPlayProfile.setupVideoResponse(channel.port, port, 0)).plist()
            }
            AirPlayProfile.STREAM_TYPE_AUDIO -> {
                session.audio?.stop()
                val format = audioFormatOf(stream)
                val channel = AudioChannel(
                    decrypt = { data, len -> session.fairPlay.decryptAudio(data, len) },
                    sink = if (config.audioEnabled) audioSink else NoAudio,
                    onPacket = { session.lastActivity = System.currentTimeMillis(); events.onTraffic(it) },
                ).start()
                session.audio = channel
                if (config.audioEnabled) audioSink.onAudioFormat(format)
                events.onAudioStarted(session.id, format)
                AppLog.i(TAG, "Audio SETUP ${format.codec}: data ${channel.dataPort} control ${channel.controlPort}")
                RtspResponse(body = AirPlayProfile.setupAudioResponse(channel.dataPort, channel.controlPort)).plist()
            }
            else -> {
                AppLog.w(TAG, "Unsupported stream type ${stream.type}")
                RtspResponse()
            }
        }
    }

    private fun handleTeardown(session: Session, request: RtspRequest): RtspResponse {
        val parsed = SetupRequest.parse(request.body)
        val streams = parsed?.streams.orEmpty()
        runCatching { session.fairPlay.rtspTeardown(ByteArrayInputStream(request.body)) }
        if (streams.isEmpty()) {
            session.stopStreams()
        } else {
            for (s in streams) when (s.type) {
                AirPlayProfile.STREAM_TYPE_SCREEN -> { session.mirror?.stop(); session.mirror = null }
                AirPlayProfile.STREAM_TYPE_AUDIO -> { session.audio?.stop(); session.audio = null }
            }
        }
        return RtspResponse()
    }

    private fun handleSetParameter(request: RtspRequest) {
        val type = request.header("Content-Type").orEmpty()
        if (!type.contains("text/parameters")) return
        val body = String(request.body, Charsets.US_ASCII)
        body.lineSequence().firstOrNull { it.startsWith("volume:", ignoreCase = true) }?.let { line ->
            val db = line.substringAfter(':').trim().toDoubleOrNull() ?: return
            audioSink.onVolume(volumeFromDb(db))
        }
    }

    private fun endSession(session: Session, reason: String) {
        if (sessions.remove(session.id) == null) return
        session.stopStreams()
        for (c in session.connections) runCatching { c.close() }
        AppLog.i(TAG, "Session ${session.id} ended: $reason")
        events.onSessionEnded(session.id, reason)
    }

    private fun watchdogLoop() {
        try {
            while (running) {
                Thread.sleep(2_000)
                val now = System.currentTimeMillis()
                for (s in sessions.values) {
                    if (s.mirror != null && now - s.lastActivity > SESSION_TIMEOUT_MS) {
                        endSession(s, "Tidak ada data dari iPhone selama ${SESSION_TIMEOUT_MS / 1000} detik")
                    }
                }
            }
        } catch (_: InterruptedException) {
        }
    }

    private fun RtspResponse.plist() = header("Content-Type", "application/x-apple-binary-plist")
    private fun RtspResponse.octets() = header("Content-Type", "application/octet-stream")

    private object NoAudio : AudioSink {
        override fun onAudioFormat(format: AudioFormatInfo) {}
        override fun onAudioFrame(data: ByteArray, length: Int, rtpTimestamp: Long) {}
        override fun onVolume(linear: Float) {}
        override fun onAudioStreamEnded() {}
    }

    companion object {
        private const val TAG = "AirPlayReceiver"
        /** iOS sends /feedback every ~2 s and frames continuously; 20 s of silence = gone. */
        const val SESSION_TIMEOUT_MS = 20_000L

        /** AirPlay volume is -30..0 dB, with -144 meaning mute. */
        fun volumeFromDb(db: Double): Float = when {
            db <= -144.0 -> 0f
            db >= 0.0 -> 1f
            db <= -30.0 -> 0f
            else -> Math.pow(10.0, db / 20.0).toFloat().coerceIn(0f, 1f)
        }

        fun audioFormatOf(stream: StreamRequest): AudioFormatInfo {
            val codec = when (stream.compressionType) {
                8 -> AudioCodec.AAC_ELD
                4 -> AudioCodec.AAC_LC
                2 -> AudioCodec.ALAC
                1 -> AudioCodec.PCM
                else -> AudioCodec.OTHER
            }
            val (rate, channels) = when (stream.audioFormat) {
                0x1000000L -> 44100 to 2
                0x2000000L -> 48000 to 2
                0x4000000L -> 16000 to 1
                0x8000000L -> 24000 to 1
                0x80000000L -> 44100 to 1
                0x100000000L -> 48000 to 1
                0x400000L -> 44100 to 2
                0x800000L -> 48000 to 2
                else -> 44100 to 2
            }
            return AudioFormatInfo(codec, rate, channels, stream.samplesPerFrame ?: 480)
        }
    }
}
