package com.aldiianshr.iphonemiror.mirroring

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.view.Surface
import com.aldiianshr.iphonemiror.core.AppLog
import com.aldiianshr.iphonemiror.domain.stream.VideoSink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Hardware H.264 decoder for the mirrored iPhone screen (Android MediaCodec, rendering
 * straight to a Surface — no CPU copies of decoded frames).
 *
 * Frames are rendered as soon as they are decoded (no presentation-time scheduling) to
 * keep latency minimal. When no view is attached the codec keeps decoding into a detached
 * surface with rendering disabled, so the stream stays in sync and can be shown again
 * instantly via [MediaCodec.setOutputSurface].
 */
class VideoDecoderEngine(
    private val lowLatencyEnabled: () -> Boolean = { true },
) : VideoSink {

    data class DecoderStats(
        val width: Int = 0,
        val height: Int = 0,
        val fps: Float = 0f,
        val bitrateKbps: Int = 0,
        val framesDecoded: Long = 0,
        val framesReceived: Long = 0,
        val decodeErrors: Long = 0,
        val lastFrameAtMs: Long = 0,
        val decoderName: String? = null,
        val lowLatency: Boolean = false,
        val profile: Int = 0,
        val level: Int = 0,
        val active: Boolean = false,
    )

    private sealed interface Item {
        class Config(val data: ByteArray) : Item
        class Frame(val data: ByteArray, val ptsUs: Long) : Item
        data object End : Item
        data object Quit : Item
    }

    private val queue = LinkedBlockingQueue<Item>(240)
    private val lock = ReentrantLock()
    private var codec: MediaCodec? = null
    private var currentConfig: ByteArray? = null
    private var awaitingKeyframe = true
    private var droppedWhileWaiting = 0
    private var outputSurface: Surface? = null
    private var dummyTexture: SurfaceTexture? = null
    private var dummySurface: Surface? = null
    private var renderEnabled = false
    private val info = MediaCodec.BufferInfo()

    private val _stats = MutableStateFlow(DecoderStats())
    val stats: StateFlow<DecoderStats> = _stats.asStateFlow()

    private var framesDecoded = 0L
    private val framesReceived = java.util.concurrent.atomic.AtomicLong()
    private var decodeErrors = 0L
    private var windowFrames = 0
    private val windowBytes = java.util.concurrent.atomic.AtomicLong()
    private var windowStart = System.nanoTime()
    @Volatile private var lastFrameAt = 0L

    private val thread = Thread(::loop, "h264-decoder").apply { isDaemon = true; priority = Thread.MAX_PRIORITY }

    init { thread.start() }

    // ---- VideoSink (called from the network thread) ----

    override fun onCodecConfig(spsPps: ByteArray) {
        offer(Item.Config(spsPps))
    }

    override fun onVideoFrame(annexB: ByteArray, ptsUs: Long) {
        lastFrameAt = System.currentTimeMillis()
        framesReceived.incrementAndGet()
        windowBytes.addAndGet(annexB.size.toLong())
        offer(Item.Frame(annexB, ptsUs))
    }

    override fun onVideoStreamEnded() {
        offer(Item.End)
    }

    private fun offer(item: Item) {
        if (!queue.offer(item)) {
            // Decoder can't keep up: drop everything up to the next config/keyframe.
            AppLog.w(TAG, "Decoder queue full — flushing and waiting for next keyframe")
            queue.clear()
            lock.withLock { awaitingKeyframe = true }
            queue.offer(item)
        }
    }

    // ---- Surface management (called from the UI thread) ----

    fun attachSurface(surface: Surface) = lock.withLock {
        outputSurface = surface
        renderEnabled = true
        codec?.let { c ->
            runCatching { c.setOutputSurface(surface) }.onFailure {
                AppLog.w(TAG, "setOutputSurface failed; recreating decoder", it)
                recreateFromCurrentConfig()
            }
        }
    }

    fun detachSurface(surface: Surface) = lock.withLock {
        if (outputSurface !== surface) return@withLock
        outputSurface = null
        renderEnabled = false
        codec?.let { c -> runCatching { c.setOutputSurface(dummy()) } }
    }

    fun release() {
        queue.clear()
        queue.offer(Item.Quit)
    }

    // ---- Decoder thread ----

    private fun loop() {
        try {
            while (true) {
                val item = queue.poll(8, TimeUnit.MILLISECONDS)
                when (item) {
                    null -> Unit
                    is Item.Config -> handleConfig(item.data)
                    is Item.Frame -> handleFrame(item)
                    Item.End -> lock.withLock { releaseCodec(); currentConfig = null; publishStats(force = true, active = false) }
                    Item.Quit -> { lock.withLock { releaseCodec(); releaseDummy() }; return }
                }
                drainOutput()
                publishStats(force = false, active = codec != null)
            }
        } catch (_: InterruptedException) {
        }
    }

    private fun handleConfig(config: ByteArray) = lock.withLock {
        if (codec != null && currentConfig?.contentEquals(config) == true) return@withLock
        currentConfig = config
        createCodec(config)
    }

    private fun recreateFromCurrentConfig() {
        val cfg = currentConfig ?: return
        createCodec(cfg)
    }

    private fun createCodec(config: ByteArray) {
        releaseCodec()
        val nals = SpsParser.splitAnnexB(config)
        val sps = nals.firstOrNull { (it[0].toInt() and 0x1F) == 7 }
        val pps = nals.firstOrNull { (it[0].toInt() and 0x1F) == 8 }
        if (sps == null || pps == null) {
            AppLog.w(TAG, "Codec config without SPS/PPS")
            return
        }
        val spsInfo = SpsParser.parse(sps)
        val width = spsInfo?.width?.takeIf { it > 0 } ?: 1920
        val height = spsInfo?.height?.takeIf { it > 0 } ?: 1080
        val choice = chooseDecoder()
        val wantLowLatency = lowLatencyEnabled()
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1) + sps))
            setByteBuffer("csd-1", ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1) + pps))
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4 * 1024 * 1024)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            val maxDim = maxOf(width, height, 1920)
            setInteger(MediaFormat.KEY_MAX_WIDTH, maxDim)
            setInteger(MediaFormat.KEY_MAX_HEIGHT, maxDim)
            if (wantLowLatency) {
                if (choice.lowLatency) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                if (choice.name.startsWith("c2.qti") || choice.name.startsWith("OMX.qcom")) {
                    // Qualcomm (Snapdragon 870 / Adreno 650 platform) vendor extensions.
                    setInteger("vendor.qti-ext-dec-low-latency.enable", 1)
                    setInteger("vendor.qti-ext-dec-picture-order.enable", 1)
                }
            }
        }
        try {
            val c = MediaCodec.createByCodecName(choice.name)
            c.configure(format, outputSurface ?: dummy(), null, 0)
            c.start()
            codec = c
            awaitingKeyframe = true
            droppedWhileWaiting = 0
            _stats.value = _stats.value.copy(
                width = width, height = height, decoderName = choice.name,
                lowLatency = wantLowLatency && (choice.lowLatency || choice.name.startsWith("c2.qti")),
                profile = spsInfo?.profileIdc ?: 0, level = spsInfo?.levelIdc ?: 0, active = true,
            )
            AppLog.i(TAG, "Decoder ${choice.name} started ${width}x$height (hw=${choice.hardware}, lowLatency=${choice.lowLatency})")
        } catch (e: Exception) {
            decodeErrors++
            AppLog.e(TAG, "Failed to start decoder ${choice.name}", e)
            releaseCodec()
        }
    }

    private fun handleFrame(frame: Item.Frame) = lock.withLock {
        val c = codec ?: return@withLock
        if (awaitingKeyframe) {
            if (containsIdr(frame.data) || droppedWhileWaiting > 120) {
                awaitingKeyframe = false
            } else {
                droppedWhileWaiting++
                return@withLock
            }
        }
        try {
            val index = c.dequeueInputBuffer(20_000)
            if (index < 0) {
                AppLog.d(TAG, "No input buffer; frame dropped")
                return@withLock
            }
            val buf = c.getInputBuffer(index) ?: return@withLock
            buf.clear()
            if (frame.data.size > buf.capacity()) {
                AppLog.w(TAG, "Frame larger than input buffer (${frame.data.size} > ${buf.capacity()})")
                c.queueInputBuffer(index, 0, 0, frame.ptsUs, 0)
                return@withLock
            }
            buf.put(frame.data)
            c.queueInputBuffer(index, 0, frame.data.size, frame.ptsUs, 0)
        } catch (e: Exception) {
            onCodecError(e)
        }
    }

    private fun drainOutput() = lock.withLock {
        val c = codec ?: return@withLock
        try {
            while (true) {
                val index = c.dequeueOutputBuffer(info, 0)
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> onOutputFormat(c.outputFormat)
                    index >= 0 -> {
                        c.releaseOutputBuffer(index, renderEnabled)
                        framesDecoded++
                        windowFrames++
                    }
                    else -> break
                }
            }
        } catch (e: Exception) {
            onCodecError(e)
        }
    }

    private fun onOutputFormat(format: MediaFormat) {
        var w = format.getIntegerOr(MediaFormat.KEY_WIDTH, 0)
        var h = format.getIntegerOr(MediaFormat.KEY_HEIGHT, 0)
        if (format.containsKey("crop-right") && format.containsKey("crop-left")) {
            w = format.getInteger("crop-right") - format.getInteger("crop-left") + 1
        }
        if (format.containsKey("crop-bottom") && format.containsKey("crop-top")) {
            h = format.getInteger("crop-bottom") - format.getInteger("crop-top") + 1
        }
        if (w > 0 && h > 0) {
            AppLog.i(TAG, "Output format ${w}x$h")
            _stats.value = _stats.value.copy(width = w, height = h)
        }
    }

    private fun onCodecError(e: Exception) {
        decodeErrors++
        AppLog.e(TAG, "Decoder error — recreating", e)
        recreateFromCurrentConfig()
    }

    private fun publishStats(force: Boolean, active: Boolean) {
        val now = System.nanoTime()
        val elapsed = now - windowStart
        if (!force && elapsed < 500_000_000L) return
        val seconds = elapsed / 1e9f
        _stats.value = _stats.value.copy(
            fps = if (seconds > 0) windowFrames / seconds else 0f,
            bitrateKbps = if (seconds > 0) ((windowBytes.getAndSet(0) * 8) / seconds / 1000f).toInt() else 0,
            framesDecoded = framesDecoded,
            framesReceived = framesReceived.get(),
            decodeErrors = decodeErrors,
            lastFrameAtMs = lastFrameAt,
            active = active,
        )
        windowFrames = 0
        windowStart = now
    }

    private fun releaseCodec() {
        codec?.let { c ->
            runCatching { c.stop() }
            runCatching { c.release() }
        }
        codec = null
    }

    private fun dummy(): Surface {
        dummySurface?.let { return it }
        val texture = SurfaceTexture(false)
        dummyTexture = texture
        return Surface(texture).also { dummySurface = it }
    }

    private fun releaseDummy() {
        dummySurface?.release(); dummySurface = null
        dummyTexture?.release(); dummyTexture = null
    }

    private fun containsIdr(annexB: ByteArray): Boolean =
        SpsParser.splitAnnexB(annexB).any { (it[0].toInt() and 0x1F) == 5 }

    private data class DecoderChoice(val name: String, val hardware: Boolean, val lowLatency: Boolean)

    private fun chooseDecoder(): DecoderChoice {
        val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } }
        val best = infos.firstOrNull { it.isHardwareAccelerated && !it.isSoftwareOnly }
            ?: infos.firstOrNull()
            ?: throw IllegalStateException("No H.264 decoder on this device")
        val caps = best.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val ll = caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
        return DecoderChoice(best.name, best.isHardwareAccelerated, ll)
    }

    private fun MediaFormat.getIntegerOr(key: String, default: Int) =
        if (containsKey(key)) getInteger(key) else default

    companion object {
        private const val TAG = "VideoDecoder"

        /** Lists the H.264 decoders on this device (for the About/diagnostics page). */
        fun availableDecoders(): List<String> =
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } }
                .map { "${it.name}${if (it.isHardwareAccelerated) " (HW)" else " (SW)"}" }
    }
}
