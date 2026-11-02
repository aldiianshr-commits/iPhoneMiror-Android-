package com.aldiianshr.iphonemiror.input.touch

/** A point in normalized iPhone-screen coordinates (0..1, origin top-left of the mirrored picture). */
data class NPoint(val u: Float, val v: Float) {
    fun distanceTo(o: NPoint, aspect: Float = 1f): Float {
        val du = (u - o.u) * aspect
        val dv = v - o.v
        return kotlin.math.sqrt(du * du + dv * dv)
    }
}

/** Raw touch stream after coordinate mapping (built from Android MotionEvents). */
sealed interface TouchEvent {
    val timeMs: Long

    data class Down(val id: Int, val point: NPoint, override val timeMs: Long) : TouchEvent
    data class Move(val points: Map<Int, NPoint>, override val timeMs: Long) : TouchEvent
    data class Up(val id: Int, val point: NPoint, override val timeMs: Long) : TouchEvent
    data class Cancel(override val timeMs: Long) : TouchEvent
}

enum class GestureKind(val label: String) {
    TAP("Tap"),
    DOUBLE_TAP("Double tap"),
    LONG_PRESS("Long press"),
    SWIPE_UP("Swipe up"),
    SWIPE_DOWN("Swipe down"),
    SWIPE_LEFT("Swipe left"),
    SWIPE_RIGHT("Swipe right"),
    DRAG("Drag"),
    TWO_FINGER_SCROLL("Scroll 2 jari"),
    PINCH_IN("Pinch in"),
    PINCH_OUT("Pinch out"),
    MULTI_FINGER("Gesture multi-jari"),
}

enum class BackendKind(val label: String) { BLUETOOTH_HID("Bluetooth HID"), XCTEST("XCTest / WDA") }

/**
 * Lifecycle of one gesture's feedback. Nothing is reported as accepted by the iPhone
 * without evidence: SENT only means the report left the tablet; CONFIRMED means the
 * XCTest runner on the iPhone answered OK; SCREEN_RESPONSE means a new mirrored frame
 * arrived after the input (the iPhone redrew), which is the strongest signal HID allows.
 */
enum class FeedbackStatus(val label: String) {
    SENDING("Mengirim…"),
    SENT("Terkirim, menunggu layar"),
    CONFIRMED("Dikonfirmasi iPhone"),
    SCREEN_RESPONSE("Layar iPhone merespons"),
    NO_SCREEN_CHANGE("Terkirim, layar tidak berubah"),
    UNSUPPORTED("Tidak didukung"),
    FAILED("Gagal"),
}

data class TouchFeedback(
    val gestureId: Long,
    val kind: GestureKind?,
    val backend: BackendKind?,
    val status: FeedbackStatus,
    val message: String? = null,
    /** Time from the end of the gesture (or the press, for live input) to confirmation/frame. */
    val latencyMs: Long? = null,
    val points: List<NPoint> = emptyList(),
    val timeMs: Long = System.currentTimeMillis(),
)

data class BackendStatus(
    val available: Boolean,
    val reason: String,
    val supportsLive: Boolean,
    val supportsMultiTouch: Boolean,
)

data class TouchControlState(
    val activeBackend: BackendKind? = null,
    val hid: BackendStatus = BackendStatus(false, "Bluetooth HID belum aktif", supportsLive = true, supportsMultiTouch = false),
    val xctest: BackendStatus = BackendStatus(false, "WebDriverAgent belum terhubung", supportsLive = false, supportsMultiTouch = true),
    val unsupportedReason: String? = "Belum ada jalur input yang tersedia",
    val lastFeedback: TouchFeedback? = null,
    val averageResponseMs: Long? = null,
    val connectionLatencyMs: Long? = null,
    val gesturesSent: Int = 0,
) {
    val supported: Boolean get() = activeBackend != null
}
