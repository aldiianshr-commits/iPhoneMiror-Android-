package com.aldiianshr.iphonemiror.mirroring

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import com.aldiianshr.iphonemiror.core.AppLog
import com.aldiianshr.iphonemiror.domain.stream.AudioCodec
import com.aldiianshr.iphonemiror.domain.stream.AudioFormatInfo
import com.aldiianshr.iphonemiror.domain.stream.AudioSink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Plays the mirroring audio stream. iOS screen mirroring sends AAC-ELD (480 samples per
 * frame); AAC-LC is also handled. ALAC (music-only AirPlay) is not decoded — Android has
 * no platform ALAC decoder — and is reported as unsupported instead of faked.
 */
class AudioPlayerEngine(private val enabled: () -> Boolean = { true }) : AudioSink {

    data class AudioStatus(val codec: String? = null, val playing: Boolean = false, val error: String? = null)

    private sealed interface Item {
        class Format(val format: AudioFormatInfo) : Item
        class Frame(val data: ByteArray) : Item
        data object End : Item
        data object Quit : Item
    }

    private val queue = LinkedBlockingQueue<Item>(200)
    private var codec: MediaCodec? = null
    private var track: AudioTrack? = null
    @Volatile private var volume = 1f
    private val info = MediaCodec.BufferInfo()
    private var pcm = ByteArray(0)

    private val _status = MutableStateFlow(AudioStatus())
    val status: StateFlow<AudioStatus> = _status.asStateFlow()

    init { Thread(::loop, "audio-player").apply { isDaemon = true }.start() }

    override fun onAudioFormat(format: AudioFormatInfo) { queue.offer(Item.Format(format)) }

    override fun onAudioFrame(data: ByteArray, length: Int, rtpTimestamp: Long) {
        if (!enabled()) return
        if (!queue.offer(Item.Frame(data.copyOf(length)))) {
            queue.poll() // drop the oldest to keep latency bounded
            queue.offer(Item.Frame(data.copyOf(length)))
        }
    }

    override fun onVolume(linear: Float) {
        volume = linear
        runCatching { track?.setVolume(linear) }
    }

    override fun onAudioStreamEnded() { queue.offer(Item.End) }

    fun release() { queue.clear(); queue.offer(Item.Quit) }

    private fun loop() {
        while (true) {
            when (val item = queue.poll(20, TimeUnit.MILLISECONDS)) {
                null -> Unit
                is Item.Format -> setup(item.format)
                is Item.Frame -> decode(item.data)
                Item.End -> teardown()
                Item.Quit -> { teardown(); return }
            }
            drain()
        }
    }

    private fun setup(format: AudioFormatInfo) {
        teardown()
        if (format.codec != AudioCodec.AAC_ELD && format.codec != AudioCodec.AAC_LC) {
            _status.value = AudioStatus(codec = format.codec.name, error = "Codec ${format.codec} tidak didukung untuk diputar")
            AppLog.w(TAG, "Audio codec ${format.codec} not supported")
            return
        }
        try {
            val mf = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, format.sampleRate, format.channels).apply {
                setInteger(MediaFormat.KEY_IS_ADTS, 0)
                setInteger(
                    MediaFormat.KEY_AAC_PROFILE,
                    if (format.codec == AudioCodec.AAC_ELD) MediaCodecInfo.CodecProfileLevel.AACObjectELD
                    else MediaCodecInfo.CodecProfileLevel.AACObjectLC,
                )
                setByteBuffer("csd-0", ByteBuffer.wrap(audioSpecificConfig(format)))
            }
            val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            c.configure(mf, null, null, 0)
            c.start()
            codec = c
            val channelMask = if (format.channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val minBuf = AudioTrack.getMinBufferSize(format.sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(format.sampleRate)
                        .setChannelMask(channelMask)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setBufferSizeInBytes(minBuf * 2)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
                .apply { setVolume(volume); play() }
            _status.value = AudioStatus(codec = format.codec.name, playing = true)
            AppLog.i(TAG, "Audio ${format.codec} ${format.sampleRate}Hz x${format.channels} started")
        } catch (e: Exception) {
            AppLog.e(TAG, "Audio setup failed", e)
            _status.value = AudioStatus(codec = format.codec.name, error = e.message)
            teardown()
        }
    }

    private fun decode(data: ByteArray) {
        val c = codec ?: return
        try {
            val index = c.dequeueInputBuffer(5_000)
            if (index < 0) return
            val buf = c.getInputBuffer(index) ?: return
            buf.clear()
            buf.put(data, 0, minOf(data.size, buf.capacity()))
            c.queueInputBuffer(index, 0, minOf(data.size, buf.capacity()), 0, 0)
        } catch (e: Exception) {
            AppLog.w(TAG, "Audio decode error", e)
        }
    }

    private fun drain() {
        val c = codec ?: return
        val t = track ?: return
        try {
            while (true) {
                val index = c.dequeueOutputBuffer(info, 0)
                if (index < 0) break
                val out = c.getOutputBuffer(index)
                if (out != null && info.size > 0) {
                    if (pcm.size < info.size) pcm = ByteArray(info.size)
                    out.position(info.offset)
                    out.get(pcm, 0, info.size)
                    t.write(pcm, 0, info.size, AudioTrack.WRITE_NON_BLOCKING)
                }
                c.releaseOutputBuffer(index, false)
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "Audio output error", e)
        }
    }

    private fun teardown() {
        codec?.let { runCatching { it.stop() }; runCatching { it.release() } }
        codec = null
        track?.let { runCatching { it.stop() }; runCatching { it.release() } }
        track = null
        if (_status.value.playing) _status.value = _status.value.copy(playing = false)
    }

    companion object {
        private const val TAG = "AudioPlayer"

        private val SAMPLE_RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

        /** Builds the MPEG-4 AudioSpecificConfig (ISO 14496-3) for AAC-LC or AAC-ELD. */
        fun audioSpecificConfig(format: AudioFormatInfo): ByteArray {
            val sfi = SAMPLE_RATES.indexOf(format.sampleRate).takeIf { it >= 0 } ?: 4
            val bits = BitWriter()
            if (format.codec == AudioCodec.AAC_ELD) {
                bits.write(31, 5); bits.write(39 - 32, 6) // escape + AOT 39 (ER AAC ELD)
                bits.write(sfi, 4); bits.write(format.channels, 4)
                bits.write(if (format.samplesPerFrame == 480) 1 else 0, 1) // frameLengthFlag
                bits.write(0, 3) // resilience flags
                bits.write(0, 1) // ldSbrPresentFlag
                bits.write(0, 4) // ELDEXT_TERM
            } else {
                bits.write(2, 5)
                bits.write(sfi, 4); bits.write(format.channels, 4)
                bits.write(0, 3)
            }
            return bits.toByteArray()
        }

        private class BitWriter {
            private val out = java.io.ByteArrayOutputStream()
            private var cur = 0
            private var n = 0
            fun write(value: Int, count: Int) {
                for (i in count - 1 downTo 0) {
                    cur = (cur shl 1) or ((value shr i) and 1)
                    n++
                    if (n == 8) { out.write(cur); cur = 0; n = 0 }
                }
            }
            fun toByteArray(): ByteArray {
                if (n > 0) { out.write(cur shl (8 - n)); cur = 0; n = 0 }
                return out.toByteArray()
            }
        }
    }
}
