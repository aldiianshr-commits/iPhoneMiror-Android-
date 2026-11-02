package com.aldiianshr.iphonemiror.selftest

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import com.aldiianshr.iphonemiror.connection.ReceiverController
import com.aldiianshr.iphonemiror.connection.diagnostics.LoopbackSender
import com.aldiianshr.iphonemiror.core.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * Receiver self-test: encodes a generated test card with the tablet's own H.264 encoder and
 * streams it through the complete AirPlay receive path over localhost (pairing, FairPlay,
 * decryption, MediaCodec decode, rendering). Every frame is stamped "SELF-TEST — BUKAN
 * IPHONE" and the session is named accordingly, so it can never be mistaken for a real
 * iPhone mirror. Use it to verify the Pad 6 side before testing with an iPhone.
 */
@Singleton
class SelfTestRunner @Inject constructor(private val receiver: ReceiverController) {

    data class Result(val running: Boolean = false, val message: String? = null, val success: Boolean? = null)

    private val _state = MutableStateFlow(Result())
    val state: StateFlow<Result> = _state.asStateFlow()

    suspend fun run(seconds: Int = 10, width: Int = 1280, height: Int = 720, fps: Int = 30) = withContext(Dispatchers.IO) {
        if (_state.value.running) return@withContext
        val port = receiver.state.value.port
        if (!receiver.isRunning || port == 0) {
            _state.value = Result(message = "Nyalakan penerima terlebih dahulu.", success = false)
            return@withContext
        }
        _state.value = Result(running = true, message = "Menjalankan handshake…")
        var encoder: MediaCodec? = null
        try {
            LoopbackSender("127.0.0.1", port).use { sender ->
                sender.info()
                sender.handshake()
                val status = sender.setupSession()
                check(status == 200) { "SETUP ditolak ($status)" }
                sender.setupMirror()
                _state.value = Result(running = true, message = "Streaming kartu uji ${width}x$height @ $fps fps…")

                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
                    setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                    setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                    setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
                }
                val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                encoder = enc
                enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val input = enc.createInputSurface()
                enc.start()

                val info = MediaCodec.BufferInfo()
                val total = seconds * fps
                var sentConfig = false
                val start = System.nanoTime()
                for (frame in 0 until total) {
                    if (!coroutineContext.isActive) break
                    drawCard(input, width, height, frame, fps)
                    drain(enc, info) { data, isConfig ->
                        val nals = splitNals(data)
                        if (isConfig) {
                            val sps = nals.first { (it[0].toInt() and 0x1F) == 7 }
                            val pps = nals.first { (it[0].toInt() and 0x1F) == 8 }
                            sender.sendCodecConfig(sps, pps, width, height)
                            sentConfig = true
                        } else if (sentConfig) {
                            sender.sendFrame(nals, ((System.nanoTime() - start) / 1000L) shl 12)
                        }
                    }
                    if (frame % fps == 0) sender.feedback()
                    val due = start + (frame + 1) * 1_000_000_000L / fps
                    val wait = (due - System.nanoTime()) / 1_000_000L
                    if (wait > 0) Thread.sleep(wait)
                }
                enc.signalEndOfInputStream()
                sender.teardown()
            }
            _state.value = Result(message = "Self-test selesai: handshake, dekripsi dan decoding berhasil dijalankan.", success = true)
        } catch (e: Exception) {
            AppLog.e(TAG, "Self-test failed", e)
            _state.value = Result(message = "Self-test gagal: ${e.message}", success = false)
        } finally {
            encoder?.let { runCatching { it.stop() }; runCatching { it.release() } }
        }
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private fun drawCard(surface: android.view.Surface, w: Int, h: Int, frame: Int, fps: Int) {
        val canvas = surface.lockHardwareCanvas()
        try {
            canvas.drawColor(Color.rgb(18, 18, 20))
            val bars = intArrayOf(0xFFF2F2F2.toInt(), 0xFFB0B0B5.toInt(), 0xFF6E6E73.toInt(), 0xFF3A3A3C.toInt(), 0xFF1C1C1E.toInt())
            val bw = w / bars.size.toFloat()
            bars.forEachIndexed { i, c -> paint.color = c; canvas.drawRect(i * bw, 0f, (i + 1) * bw, h * 0.18f, paint) }
            // Moving marker proves frames are live.
            val x = (frame * 12f) % w
            paint.color = Color.WHITE
            canvas.drawRect(x, h * 0.80f, x + 40f, h * 0.88f, paint)
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            paint.textSize = h * 0.09f
            canvas.drawText("SELF-TEST — BUKAN IPHONE", w * 0.06f, h * 0.40f, paint)
            paint.typeface = Typeface.MONOSPACE
            paint.textSize = h * 0.05f
            paint.color = Color.rgb(200, 200, 205)
            canvas.drawText("iPhoneMiror receiver loopback  ${w}x$h @ ${fps}fps", w * 0.06f, h * 0.52f, paint)
            canvas.drawText("frame %05d   %s".format(frame, clock.format(Date())), w * 0.06f, h * 0.62f, paint)
        } finally {
            surface.unlockCanvasAndPost(canvas)
        }
    }

    private inline fun drain(enc: MediaCodec, info: MediaCodec.BufferInfo, onData: (ByteArray, Boolean) -> Unit) {
        while (true) {
            val index = enc.dequeueOutputBuffer(info, 10_000)
            if (index < 0) {
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
                break
            }
            val buf = enc.getOutputBuffer(index)
            if (buf != null && info.size > 0) {
                val data = ByteArray(info.size)
                buf.position(info.offset)
                buf.get(data)
                onData(data, info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0)
            }
            enc.releaseOutputBuffer(index, false)
        }
    }

    companion object {
        private const val TAG = "SelfTest"

        fun splitNals(data: ByteArray): List<ByteArray> {
            val out = ArrayList<ByteArray>()
            var i = 0
            var start = -1
            while (i + 3 <= data.size) {
                val sc3 = data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1
                val sc4 = i + 4 <= data.size && data[i].toInt() == 0 && data[i + 1].toInt() == 0 &&
                    data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1
                if (sc3 || sc4) {
                    if (start >= 0) out += data.copyOfRange(start, i)
                    i += if (sc4) 4 else 3
                    start = i
                } else {
                    i++
                }
            }
            if (start >= 0 && start < data.size) out += data.copyOfRange(start, data.size)
            return out.filter { it.isNotEmpty() }
        }
    }
}
