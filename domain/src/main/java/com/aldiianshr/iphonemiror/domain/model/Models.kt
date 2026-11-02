package com.aldiianshr.iphonemiror.domain.model

/** Physical/logical path over which an Apple device was seen or connected. */
enum class ConnectionType(val label: String) {
    WIFI("Wi-Fi (AirPlay)"),
    USB("USB OTG"),
    BLUETOOTH("Bluetooth"),
}

/** Lifecycle of the AirPlay receiver as seen by the UI. */
enum class ReceiverStatus {
    /** Receiver not running. */
    STOPPED,
    /** Server running and advertised on the network; waiting for an iPhone. */
    WAITING,
    /** An iPhone opened a control connection (info / pairing in progress). */
    CONNECTING,
    /** Pair-verify succeeded; stream not yet started. */
    PAIRED,
    /** Mirroring video is flowing. */
    MIRRORING,
    /** No usable local network (Wi-Fi off / not connected). */
    NO_NETWORK,
    /** Receiver failed to start or crashed. */
    ERROR,
}

/**
 * An Apple device as reported by a real protocol exchange. Fields are null when the
 * device did not send them — the app never fills in guesses.
 */
data class AppleDevice(
    val id: String,
    val name: String? = null,
    val model: String? = null,
    val osName: String? = null,
    val osVersion: String? = null,
    val osBuild: String? = null,
    val connectionType: ConnectionType,
    val address: String? = null,
) {
    val marketingName: String? get() = model?.let { AppleModels.marketingName(it) }
    val displayName: String get() = name ?: marketingName ?: model ?: id
}

data class ReceiverState(
    val status: ReceiverStatus = ReceiverStatus.STOPPED,
    val receiverName: String = "",
    val port: Int = 0,
    val ipAddresses: List<String> = emptyList(),
    val advertised: Boolean = false,
    val device: AppleDevice? = null,
    val pairVerified: Boolean = false,
    val encrypted: Boolean = false,
    val sessionStartedAt: Long? = null,
    val lastError: String? = null,
    val awaitingApproval: AppleDevice? = null,
)

data class StreamStats(
    val width: Int = 0,
    val height: Int = 0,
    val fps: Float = 0f,
    val bitrateKbps: Int = 0,
    val framesDecoded: Long = 0,
    val framesReceived: Long = 0,
    val decodeErrors: Long = 0,
    val lastFrameAgeMs: Long = -1,
    val decoderName: String? = null,
    val lowLatency: Boolean = false,
    val audioActive: Boolean = false,
    val audioCodec: String? = null,
) {
    val hasVideo: Boolean get() = width > 0 && height > 0
    val isPortrait: Boolean get() = height > width
}

enum class LinkQuality(val label: String) {
    NONE("Tidak ada stream"),
    POOR("Buruk"),
    FAIR("Cukup"),
    GOOD("Baik"),
    EXCELLENT("Sangat baik"),
}

data class HistoryEntry(
    val id: Long = 0,
    val deviceId: String,
    val deviceName: String?,
    val model: String?,
    val osVersion: String?,
    val connectionType: ConnectionType,
    val startedAt: Long,
    val endedAt: Long?,
    val endReason: String?,
    val mirrored: Boolean,
)

data class KnownDevice(
    val deviceId: String,
    val name: String?,
    val model: String?,
    val osVersion: String?,
    val allowed: Boolean,
    val blocked: Boolean,
    val firstSeen: Long,
    val lastSeen: Long,
)

enum class StreamResolution(val width: Int, val height: Int, val label: String) {
    HD(1280, 720, "720p"),
    FHD(1920, 1080, "1080p"),
    QHD(2560, 1440, "1440p"),
}

enum class InputMode(val label: String) {
    TRACKPAD("Trackpad (relatif)"),
    DIRECT("Sentuh langsung (eksperimental)"),
}

data class AppSettings(
    val receiverName: String = "iPhoneMiror",
    val resolution: StreamResolution = StreamResolution.FHD,
    val maxFps: Int = 60,
    val audioEnabled: Boolean = true,
    val requireApproval: Boolean = true,
    val autoOpenMirroring: Boolean = true,
    val keepScreenOn: Boolean = true,
    val lowLatencyDecoder: Boolean = true,
    val inputMode: InputMode = InputMode.TRACKPAD,
    val pointerSpeed: Float = 1.0f,
    val debugLogging: Boolean = false,
)
