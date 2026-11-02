package com.aldiianshr.iphonemiror.connection.airplay

import com.dd.plist.BinaryPropertyListWriter
import com.dd.plist.NSArray
import com.dd.plist.NSData
import com.dd.plist.NSDictionary

/**
 * What this receiver advertises. Only capabilities that are actually implemented are
 * announced: screen mirroring (H.264) with FairPlay SAP v3 + legacy pairing, and
 * AAC-ELD mirroring audio. Video URL playback (HLS) is NOT advertised.
 */
object AirPlayProfile {
    const val SOURCE_VERSION = "220.68"
    const val MODEL = "AppleTV3,2"
    const val STATUS_FLAGS = 0x44
    const val VV = 2
    const val DEFAULT_PORT = 7000

    private const val BIT_SCREEN = 7
    private const val BIT_AUDIO = 9
    private const val BIT_FPSAP_V2_5_AES_GCM = 12
    private const val BIT_AUTHENTICATION_4 = 14
    private const val BIT_AUDIO_FORMAT_2 = 19
    private const val BIT_AUDIO_FORMAT_3 = 20
    private const val BIT_AUDIO_FORMAT_4 = 21
    private const val BIT_LEGACY_PAIRING = 27
    private const val BIT_RAOP = 30

    val FEATURES: Long = listOf(
        BIT_SCREEN, BIT_AUDIO, BIT_FPSAP_V2_5_AES_GCM, BIT_AUTHENTICATION_4,
        BIT_AUDIO_FORMAT_2, BIT_AUDIO_FORMAT_3, BIT_AUDIO_FORMAT_4,
        BIT_LEGACY_PAIRING, BIT_RAOP,
    ).fold(0L) { acc, bit -> acc or (1L shl bit) }

    val FEATURES_TXT: String = "0x%X,0x%X".format(FEATURES and 0xFFFFFFFFL, FEATURES ushr 32)

    /** TXT record for `_airplay._tcp`. */
    fun airplayTxt(deviceId: String, publicKeyHex: String): Map<String, String> = linkedMapOf(
        "deviceid" to deviceId,
        "features" to FEATURES_TXT,
        "srcvers" to SOURCE_VERSION,
        "flags" to "0x%X".format(STATUS_FLAGS),
        "vv" to VV.toString(),
        "model" to MODEL,
        "rhd" to "5.6.0.0",
        "pw" to "false",
        "pk" to publicKeyHex,
    )

    /** Service instance name for `_raop._tcp`: `AABBCCDDEEFF@Name`. */
    fun raopServiceName(deviceId: String, name: String): String = deviceId.replace(":", "") + "@" + name

    /** TXT record for `_raop._tcp`. */
    fun raopTxt(publicKeyHex: String): Map<String, String> = linkedMapOf(
        "ch" to "2",
        "cn" to "1,3",
        "da" to "true",
        "et" to "0,3,5",
        "ek" to "1",
        "ft" to FEATURES_TXT,
        "am" to MODEL,
        "md" to "0,1,2",
        "sr" to "44100",
        "ss" to "16",
        "sv" to "false",
        "sm" to "false",
        "tp" to "UDP",
        "txtvers" to "1",
        "sf" to "0x%X".format(STATUS_FLAGS),
        "vs" to SOURCE_VERSION,
        "vn" to "65537",
        "pk" to publicKeyHex,
    )

    /** Body of `GET /info`. */
    fun infoPlist(name: String, width: Int, height: Int, fps: Int, publicKey: ByteArray, deviceId: String): ByteArray {
        fun audioFormat(type: Int) = NSDictionary().apply {
            put("audioInputFormats", 0x3FFFFFC)
            put("audioOutputFormats", 0x3FFFFFC)
            put("type", type)
        }
        fun audioLatency(type: Int) = NSDictionary().apply {
            put("audioType", "default")
            put("inputLatencyMicros", false)
            put("type", type)
        }
        val display = NSDictionary().apply {
            put("features", 0xE)
            put("height", height)
            put("heightPhysical", false)
            put("heightPixels", height)
            put("maxFPS", fps)
            put("overscanned", false)
            put("refreshRate", 60)
            put("rotation", false)
            put("uuid", "e5f7a68d-7b0f-4305-984b-974f677a150b")
            put("width", width)
            put("widthPhysical", false)
            put("widthPixels", width)
        }
        val root = NSDictionary().apply {
            put("audioFormats", NSArray(audioFormat(100), audioFormat(101)))
            put("audioLatencies", NSArray(audioLatency(100), audioLatency(101)))
            put("displays", NSArray(display))
            put("features", FEATURES)
            put("keepAliveSendStatsAsBody", 1)
            put("model", MODEL)
            put("name", name)
            put("deviceID", deviceId)
            put("pi", "b08f5a79-db29-4384-b456-a4784d9e6055")
            put("sourceVersion", SOURCE_VERSION)
            put("statusFlags", STATUS_FLAGS)
            put("vv", VV)
            put("pk", NSData(publicKey))
        }
        return BinaryPropertyListWriter.writeToArray(root)
    }

    fun setupVideoResponse(dataPort: Int, eventPort: Int, timingPort: Int): ByteArray {
        val stream = NSDictionary().apply {
            put("dataPort", dataPort)
            put("type", STREAM_TYPE_SCREEN)
        }
        val root = NSDictionary().apply {
            put("streams", NSArray(stream))
            put("eventPort", eventPort)
            put("timingPort", timingPort)
        }
        return BinaryPropertyListWriter.writeToArray(root)
    }

    fun setupAudioResponse(dataPort: Int, controlPort: Int): ByteArray {
        val stream = NSDictionary().apply {
            put("dataPort", dataPort)
            put("type", STREAM_TYPE_AUDIO)
            put("controlPort", controlPort)
        }
        return BinaryPropertyListWriter.writeToArray(NSDictionary().apply { put("streams", NSArray(stream)) })
    }

    const val STREAM_TYPE_SCREEN = 110
    const val STREAM_TYPE_AUDIO = 96
}
