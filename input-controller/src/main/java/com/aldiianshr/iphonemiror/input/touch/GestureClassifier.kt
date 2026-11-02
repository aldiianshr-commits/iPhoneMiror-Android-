package com.aldiianshr.iphonemiror.input.touch

/** Records a full gesture (every pointer's track) and classifies it. */
class GestureRecorder {
    data class Sample(val point: NPoint, val timeMs: Long)

    class Track(val id: Int) {
        val samples = ArrayList<Sample>()
        var ended = false
        val start: Sample get() = samples.first()
        val end: Sample get() = samples.last()
    }

    private val tracks = LinkedHashMap<Int, Track>()
    var maxPointers = 0
        private set
    var startMs = 0L
        private set

    val isEmpty: Boolean get() = tracks.isEmpty()
    val activeCount: Int get() = tracks.values.count { !it.ended }
    fun tracks(): List<Track> = tracks.values.toList()

    fun onEvent(e: TouchEvent) {
        when (e) {
            is TouchEvent.Down -> {
                if (tracks.isEmpty()) startMs = e.timeMs
                tracks[e.id] = Track(e.id).also { it.samples += Sample(e.point, e.timeMs) }
                maxPointers = maxOf(maxPointers, activeCount)
            }
            is TouchEvent.Move -> for ((id, p) in e.points) {
                val t = tracks[id] ?: continue
                if (!t.ended && t.samples.last().point != p) t.samples += Sample(p, e.timeMs)
            }
            is TouchEvent.Up -> tracks[e.id]?.let { t ->
                if (t.samples.last().point != e.point) t.samples += Sample(e.point, e.timeMs)
                else t.samples += Sample(e.point, e.timeMs)
                t.ended = true
            }
            is TouchEvent.Cancel -> tracks.values.forEach { it.ended = true }
        }
    }

    fun reset() {
        tracks.clear()
        maxPointers = 0
    }
}

object GestureClassifier {
    const val TAP_MAX_MS = 280L
    const val LONG_PRESS_MS = 500L
    const val MOVE_SLOP = 0.02f       // fraction of the screen height
    const val SWIPE_MIN = 0.06f
    const val SWIPE_MAX_MS = 700L
    const val PINCH_RATIO = 0.15f
    const val DOUBLE_TAP_GAP_MS = 320L

    /** aspect = picture width / height, so distances are measured in screen-height units. */
    fun classify(tracks: List<GestureRecorder.Track>, aspect: Float, previousTap: Pair<NPoint, Long>? = null): GestureKind? {
        if (tracks.isEmpty()) return null
        if (tracks.size >= 3) return GestureKind.MULTI_FINGER
        if (tracks.size == 2) {
            val (a, b) = tracks
            val d0 = a.start.point.distanceTo(b.start.point, aspect)
            val d1 = a.end.point.distanceTo(b.end.point, aspect)
            if (d0 > 0.01f) {
                val ratio = (d1 - d0) / d0
                if (ratio > PINCH_RATIO) return GestureKind.PINCH_OUT
                if (ratio < -PINCH_RATIO) return GestureKind.PINCH_IN
            }
            return GestureKind.TWO_FINGER_SCROLL
        }
        val t = tracks.single()
        val duration = t.end.timeMs - t.start.timeMs
        val maxDist = t.samples.maxOf { it.point.distanceTo(t.start.point, aspect) }
        if (maxDist < MOVE_SLOP) {
            if (duration >= LONG_PRESS_MS) return GestureKind.LONG_PRESS
            if (previousTap != null && t.start.timeMs - previousTap.second <= DOUBLE_TAP_GAP_MS &&
                previousTap.first.distanceTo(t.start.point, aspect) < MOVE_SLOP * 2
            ) return GestureKind.DOUBLE_TAP
            return GestureKind.TAP
        }
        val du = (t.end.point.u - t.start.point.u) * aspect
        val dv = t.end.point.v - t.start.point.v
        val dist = kotlin.math.sqrt(du * du + dv * dv)
        val heldFirst = t.samples.firstOrNull { it.point.distanceTo(t.start.point, aspect) >= MOVE_SLOP }
            ?.let { it.timeMs - t.start.timeMs >= LONG_PRESS_MS } ?: false
        if (!heldFirst && duration <= SWIPE_MAX_MS && dist >= SWIPE_MIN) {
            return if (kotlin.math.abs(du) > kotlin.math.abs(dv)) {
                if (du > 0) GestureKind.SWIPE_RIGHT else GestureKind.SWIPE_LEFT
            } else {
                if (dv > 0) GestureKind.SWIPE_DOWN else GestureKind.SWIPE_UP
            }
        }
        return GestureKind.DRAG
    }
}
