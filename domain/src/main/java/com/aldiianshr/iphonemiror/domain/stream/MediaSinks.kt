package com.aldiianshr.iphonemiror.domain.stream

/** Receives decrypted H.264 from the AirPlay mirror channel (Annex-B framed). */
interface VideoSink {
    /** SPS + PPS, each prefixed with a 4-byte start code. */
    fun onCodecConfig(spsPps: ByteArray)

    /** One access unit (one or more NAL units, Annex-B framed). */
    fun onVideoFrame(annexB: ByteArray, ptsUs: Long)

    fun onVideoStreamEnded()
}

enum class AudioCodec { AAC_ELD, AAC_LC, ALAC, PCM, OTHER }

data class AudioFormatInfo(
    val codec: AudioCodec,
    val sampleRate: Int,
    val channels: Int,
    val samplesPerFrame: Int,
)

/** Receives decrypted audio frames from the AirPlay RTP channel. */
interface AudioSink {
    fun onAudioFormat(format: AudioFormatInfo)
    fun onAudioFrame(data: ByteArray, length: Int, rtpTimestamp: Long)
    fun onVolume(linear: Float)
    fun onAudioStreamEnded()
}
