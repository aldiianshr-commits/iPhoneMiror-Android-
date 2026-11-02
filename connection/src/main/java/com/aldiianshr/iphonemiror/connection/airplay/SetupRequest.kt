package com.aldiianshr.iphonemiror.connection.airplay

import com.dd.plist.NSArray
import com.dd.plist.NSData
import com.dd.plist.NSDictionary
import com.dd.plist.NSNumber
import com.dd.plist.NSObject
import com.dd.plist.NSString
import com.dd.plist.PropertyListParser

/** Information an AirPlay sender reports about itself in the session SETUP request. */
data class SenderInfo(
    val deviceId: String?,
    val macAddress: String?,
    val name: String?,
    val model: String?,
    val osName: String?,
    val osVersion: String?,
    val osBuild: String?,
    val sourceVersion: String?,
    val isScreenMirroringSession: Boolean?,
) {
    val hasAnything: Boolean
        get() = listOf(deviceId, macAddress, name, model, osName, osVersion, osBuild).any { it != null }
}

data class StreamRequest(
    val type: Int,
    val streamConnectionId: Long?,
    val compressionType: Int?,
    val audioFormat: Long?,
    val samplesPerFrame: Int?,
)

/** Parsed body of an RTSP SETUP request (binary plist). Every field is optional. */
data class SetupRequest(
    val hasEncryptionKey: Boolean,
    val sender: SenderInfo,
    val streams: List<StreamRequest>,
) {
    companion object {
        fun parse(body: ByteArray): SetupRequest? {
            if (body.isEmpty()) return null
            val root = runCatching { PropertyListParser.parse(body) }.getOrNull() as? NSDictionary ?: return null
            val sender = SenderInfo(
                deviceId = root.string("deviceID"),
                macAddress = root.string("macAddress"),
                name = root.string("name"),
                model = root.string("model"),
                osName = root.string("osName"),
                osVersion = root.string("osVersion"),
                osBuild = root.string("osBuildVersion"),
                sourceVersion = root.string("sourceVersion"),
                isScreenMirroringSession = (root["isScreenMirroringSession"] as? NSNumber)?.boolValue(),
            )
            val streams = (root["streams"] as? NSArray)?.array.orEmpty().mapNotNull { obj ->
                val dict = obj as? NSDictionary ?: return@mapNotNull null
                val type = (dict["type"] as? NSNumber)?.intValue() ?: return@mapNotNull null
                StreamRequest(
                    type = type,
                    streamConnectionId = (dict["streamConnectionID"] as? NSNumber)?.longValue(),
                    compressionType = (dict["ct"] as? NSNumber)?.intValue(),
                    audioFormat = (dict["audioFormat"] as? NSNumber)?.longValue(),
                    samplesPerFrame = (dict["spf"] as? NSNumber)?.intValue(),
                )
            }
            return SetupRequest(
                hasEncryptionKey = root.containsKey("ekey"),
                sender = sender,
                streams = streams,
            )
        }

        private fun NSDictionary.string(key: String): String? = when (val v: NSObject? = this[key]) {
            is NSString -> v.content.takeIf { it.isNotBlank() }
            is NSNumber -> v.toString()
            is NSData -> null
            else -> null
        }
    }
}
