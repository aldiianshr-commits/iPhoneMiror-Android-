package com.aldiianshr.iphonemiror.input.touch

import com.aldiianshr.iphonemiror.domain.model.HidOrientation
import com.aldiianshr.iphonemiror.input.HidReports

/** A unit of work for the Bluetooth HID sender. */
sealed interface HidCommand {
    /** [coalesceKey]: a pending report with the same key may be replaced by a newer one. */
    class Report(val id: Int, val data: ByteArray, val coalesceKey: String? = null) : HidCommand {
        override fun toString() = "Report($id, ${data.joinToString(",") { (it.toInt() and 0xFF).toString() }})"
    }
    data class Delay(val ms: Long) : HidCommand
}

/**
 * Turns the mapped touch stream into Bluetooth HID reports in real time.
 *
 * Absolute mode (iOS 18+): the finger *is* the pointer. Down hovers the pointer onto the
 * point, then (after a short settle so iOS registers the arrival) presses; moves stream
 * absolute positions with the button held (swipe / drag / scroll a list); up releases.
 * Taps, double taps, long presses, swipes and drag-and-drop are therefore reproduced
 * exactly as performed. Two fingers become wheel/pan scrolling. Pinch and 3+ fingers
 * cannot be expressed by a single pointer and are reported as unsupported for HID.
 *
 * Relative mode (older iOS): trackpad semantics — drag moves the pointer, tap clicks,
 * hold-then-drag drags, two-finger tap = secondary click.
 */
class HidTouchTranslator {
    var absolute = true
    var orientation = HidOrientation.FOLLOW_SCREEN
    var invertScroll = false
    var relativeSpeed = 1f
    var videoLandscape = false
    /** Picture width / height, so vertical and horizontal distances compare correctly. */
    var aspect = 9f / 19.5f

    data class Output(val commands: List<HidCommand>, val pressAfterMs: Long? = null, val unsupported: String? = null)

    private val active = LinkedHashMap<Int, NPoint>()
    private var primary = -1
    private var lastPos = NPoint(0.5f, 0.5f)
    private var pressed = false
    private var pressPending = false
    private var lastReportMs = Long.MIN_VALUE / 2
    private var scrollMode = false
    private var centroid: NPoint? = null
    private var startSpread = 0f
    private var wheelAcc = 0f
    private var panAcc = 0f
    private var unsupportedFlagged = false

    // relative-mode state
    private var downAt = 0L
    private var downPoint = NPoint(0f, 0f)
    private var moved = false
    private var dragging = false
    private var relAccX = 0f
    private var relAccY = 0f

    val isPressed: Boolean get() = pressed

    fun onEvent(e: TouchEvent): Output = if (absolute) absEvent(e) else relEvent(e)

    /** Called when the press scheduled by a Down falls due. */
    fun onPressDue(): List<HidCommand> {
        if (!absolute || !pressPending || scrollMode || primary !in active) return emptyList()
        pressPending = false
        pressed = true
        return listOf(abs(HidReports.BUTTON_LEFT, lastPos))
    }

    fun reset(): List<HidCommand> {
        val out = if (pressed) listOf(if (absolute) abs(0, lastPos) else rel(0, 0, 0)) else emptyList()
        active.clear(); primary = -1; pressed = false; pressPending = false; scrollMode = false
        centroid = null; dragging = false; moved = false; unsupportedFlagged = false
        return out
    }

    private fun absEvent(e: TouchEvent): Output {
        when (e) {
            is TouchEvent.Down -> {
                active[e.id] = e.point
                if (active.size == 1) {
                    primary = e.id
                    lastPos = e.point
                    unsupportedFlagged = false
                    pressPending = true
                    // A pointer idle for a while is hidden by iOS; the first report only wakes it.
                    val settle = if (e.timeMs - lastReportMs > WAKE_AFTER_MS) WAKE_SETTLE_MS else ARRIVE_SETTLE_MS
                    lastReportMs = e.timeMs
                    return Output(listOf(abs(0, lastPos)), pressAfterMs = settle)
                }
                // Second finger: leave pointer mode, become two-finger scroll.
                val cmds = ArrayList<HidCommand>()
                if (pressed) cmds += abs(0, lastPos)
                pressed = false
                pressPending = false
                scrollMode = true
                centroid = centroidOf()
                startSpread = spread()
                wheelAcc = 0f; panAcc = 0f
                lastReportMs = e.timeMs
                val unsupported = if (active.size >= 3 && !unsupportedFlagged) {
                    unsupportedFlagged = true
                    "Gesture 3+ jari tidak dapat dikirim lewat HID (satu pointer)."
                } else null
                return Output(cmds, unsupported = unsupported)
            }
            is TouchEvent.Move -> {
                for ((id, p) in e.points) if (id in active) active[id] = p
                lastReportMs = e.timeMs
                if (scrollMode) return scroll()
                val p = active[primary] ?: return Output(emptyList())
                lastPos = p
                return Output(listOf(abs(if (pressed) HidReports.BUTTON_LEFT else 0, p, coalesce = true)))
            }
            is TouchEvent.Up -> {
                active.remove(e.id)
                lastReportMs = e.timeMs
                if (scrollMode) {
                    if (active.isEmpty()) { scrollMode = false; primary = -1 }
                    return Output(emptyList())
                }
                if (e.id != primary) return Output(emptyList())
                lastPos = e.point
                primary = -1
                val cmds = when {
                    pressPending -> listOf(abs(HidReports.BUTTON_LEFT, lastPos), HidCommand.Delay(TAP_HOLD_MS), abs(0, lastPos))
                    pressed -> listOf(abs(HidReports.BUTTON_LEFT, lastPos), abs(0, lastPos))
                    else -> emptyList()
                }
                pressPending = false
                pressed = false
                return Output(cmds)
            }
            is TouchEvent.Cancel -> return Output(reset())
        }
    }

    private fun scroll(): Output {
        val c = centroidOf()
        val prev = centroid ?: c
        centroid = c
        val spreadNow = spread()
        if (!unsupportedFlagged && startSpread > 0.01f && kotlin.math.abs(spreadNow - startSpread) / startSpread > PINCH_RATIO) {
            unsupportedFlagged = true
            return Output(emptyList(), unsupported = "Pinch tidak dapat dikirim lewat HID (satu pointer). Gunakan backend XCTest.")
        }
        if (unsupportedFlagged) return Output(emptyList())
        val sign = if (invertScroll) -1f else 1f
        // Fingers moving down reveal content above (wheel up, positive); right reveals the left (pan negative).
        wheelAcc += (c.v - prev.v) * SCROLL_UNITS * sign
        panAcc += -(c.u - prev.u) * aspect * SCROLL_UNITS * sign
        val w = wheelAcc.toInt()
        val p = panAcc.toInt()
        if (w == 0 && p == 0) return Output(emptyList())
        wheelAcc -= w; panAcc -= p
        val report = if (absolute) HidReports.absMouse(0, hidX(lastPos), hidY(lastPos), w, p) else HidReports.mouse(0, 0, 0, w, p)
        return Output(listOf(HidCommand.Report(HidReports.ID_MOUSE, report)))
    }

    private fun relEvent(e: TouchEvent): Output {
        when (e) {
            is TouchEvent.Down -> {
                active[e.id] = e.point
                if (active.size == 1) {
                    primary = e.id; downAt = e.timeMs; downPoint = e.point; lastPos = e.point
                    moved = false; dragging = false; relAccX = 0f; relAccY = 0f
                    return Output(emptyList())
                }
                val cmds = if (dragging) listOf(rel(0, 0, 0)) else emptyList()
                dragging = false; pressed = false
                scrollMode = true; centroid = centroidOf(); startSpread = spread(); wheelAcc = 0f; panAcc = 0f
                return Output(cmds)
            }
            is TouchEvent.Move -> {
                for ((id, p) in e.points) if (id in active) active[id] = p
                if (scrollMode) { moved = true; return scroll() }
                val p = active[primary] ?: return Output(emptyList())
                val cmds = ArrayList<HidCommand>()
                if (!moved && p.distanceTo(downPoint, aspect) > GestureClassifier.MOVE_SLOP / 2) {
                    moved = true
                    if (e.timeMs - downAt >= GestureClassifier.LONG_PRESS_MS) {
                        dragging = true; pressed = true
                        cmds += rel(HidReports.BUTTON_LEFT, 0, 0)
                    }
                }
                if (moved) {
                    relAccX += (p.u - lastPos.u) * aspect * REL_UNITS * relativeSpeed
                    relAccY += (p.v - lastPos.v) * REL_UNITS * relativeSpeed
                    val dx = relAccX.toInt(); val dy = relAccY.toInt()
                    relAccX -= dx; relAccY -= dy
                    for ((sx, sy) in HidReports.splitMovement(dx, dy)) {
                        cmds += HidCommand.Report(HidReports.ID_MOUSE, HidReports.mouse(if (dragging) HidReports.BUTTON_LEFT else 0, sx, sy))
                    }
                }
                lastPos = p
                return Output(cmds)
            }
            is TouchEvent.Up -> {
                active.remove(e.id)
                if (scrollMode) {
                    if (active.isEmpty()) {
                        scrollMode = false
                        if (!moved) return Output(click(HidReports.BUTTON_RIGHT))
                    }
                    return Output(emptyList())
                }
                if (e.id != primary) return Output(emptyList())
                primary = -1
                return Output(
                    when {
                        dragging -> { dragging = false; pressed = false; listOf(rel(0, 0, 0)) }
                        !moved -> click(HidReports.BUTTON_LEFT)
                        else -> emptyList()
                    },
                )
            }
            is TouchEvent.Cancel -> return Output(reset())
        }
    }

    private fun click(button: Int) = listOf(rel(button, 0, 0), HidCommand.Delay(TAP_HOLD_MS), rel(0, 0, 0))

    private fun centroidOf(): NPoint {
        val pts = active.values
        return NPoint(pts.map { it.u }.average().toFloat(), pts.map { it.v }.average().toFloat())
    }

    private fun spread(): Float {
        val pts = active.values.toList()
        if (pts.size < 2) return 0f
        return pts[0].distanceTo(pts[1], aspect)
    }

    private fun hidX(p: NPoint) = TouchMapper.toHidAbsolute(p, videoLandscape, orientation).first
    private fun hidY(p: NPoint) = TouchMapper.toHidAbsolute(p, videoLandscape, orientation).second

    private fun abs(buttons: Int, p: NPoint, coalesce: Boolean = false): HidCommand.Report {
        val (x, y) = TouchMapper.toHidAbsolute(p, videoLandscape, orientation)
        return HidCommand.Report(HidReports.ID_MOUSE, HidReports.absMouse(buttons, x, y), if (coalesce) "abs-pos" else null)
    }

    private fun rel(buttons: Int, dx: Int, dy: Int) = HidCommand.Report(HidReports.ID_MOUSE, HidReports.mouse(buttons, dx, dy))

    companion object {
        const val ARRIVE_SETTLE_MS = 30L
        const val WAKE_AFTER_MS = 2_000L
        const val WAKE_SETTLE_MS = 300L
        const val TAP_HOLD_MS = 40L
        const val SCROLL_UNITS = 40f
        const val REL_UNITS = 900f
        const val PINCH_RATIO = 0.18f
    }
}
