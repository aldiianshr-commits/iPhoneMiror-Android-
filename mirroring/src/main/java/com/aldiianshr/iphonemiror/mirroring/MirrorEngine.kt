package com.aldiianshr.iphonemiror.mirroring

import android.view.Surface
import com.aldiianshr.iphonemiror.domain.model.StreamStats
import com.aldiianshr.iphonemiror.domain.stream.AudioFormatInfo
import com.aldiianshr.iphonemiror.domain.stream.AudioSink
import com.aldiianshr.iphonemiror.domain.stream.VideoSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** Facade over the video decoder and audio player; the receiver writes into it. */
class MirrorEngine(
    lowLatency: () -> Boolean,
    audioEnabled: () -> Boolean,
) : VideoSink, AudioSink {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val video = VideoDecoderEngine(lowLatency)
    val audio = AudioPlayerEngine(audioEnabled)

    val stats: StateFlow<StreamStats> = combine(video.stats, audio.status) { v, a ->
        StreamStats(
            width = if (v.active) v.width else 0,
            height = if (v.active) v.height else 0,
            fps = v.fps,
            bitrateKbps = v.bitrateKbps,
            framesDecoded = v.framesDecoded,
            framesReceived = v.framesReceived,
            decodeErrors = v.decodeErrors,
            lastFrameAgeMs = if (v.lastFrameAtMs > 0) System.currentTimeMillis() - v.lastFrameAtMs else -1,
            decoderName = v.decoderName,
            lowLatency = v.lowLatency,
            audioActive = a.playing,
            audioCodec = a.codec,
        )
    }.stateIn(scope, SharingStarted.Eagerly, StreamStats())

    fun attachSurface(surface: Surface) = video.attachSurface(surface)
    fun detachSurface(surface: Surface) = video.detachSurface(surface)

    override fun onCodecConfig(spsPps: ByteArray) = video.onCodecConfig(spsPps)
    override fun onVideoFrame(annexB: ByteArray, ptsUs: Long) = video.onVideoFrame(annexB, ptsUs)
    override fun onVideoStreamEnded() = video.onVideoStreamEnded()
    override fun onAudioFormat(format: AudioFormatInfo) = audio.onAudioFormat(format)
    override fun onAudioFrame(data: ByteArray, length: Int, rtpTimestamp: Long) = audio.onAudioFrame(data, length, rtpTimestamp)
    override fun onVolume(linear: Float) = audio.onVolume(linear)
    override fun onAudioStreamEnded() = audio.onAudioStreamEnded()
}
