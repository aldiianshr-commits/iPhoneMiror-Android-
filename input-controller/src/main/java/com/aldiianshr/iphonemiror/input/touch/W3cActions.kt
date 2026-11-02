package com.aldiianshr.iphonemiror.input.touch

import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds a W3C WebDriver "actions" payload (one touch pointer source per finger) that
 * replays a recorded gesture with its timing. All fingers are resampled onto a common
 * tick timeline because W3C actions execute tick-by-tick across input sources — this is
 * what lets pinch and multi-finger gestures play back with the fingers in sync.
 */
object W3cActions {
    const val MAX_TICKS = 60
    const val MIN_STEP_MS = 16L

    fun build(
        tracks: List<GestureRecorder.Track>,
        toPoint: (NPoint) -> Pair<Int, Int>,
    ): JSONObject {
        require(tracks.isNotEmpty()) { "no tracks" }
        val t0 = tracks.minOf { it.start.timeMs }
        val tEnd = tracks.maxOf { it.end.timeMs }
        val total = maxOf(tEnd - t0, 1L)
        val step = maxOf(MIN_STEP_MS, (total + MAX_TICKS - 1) / MAX_TICKS)
        val ticks = (total / step).toInt() + 2

        val sources = JSONArray()
        tracks.forEachIndexed { index, track ->
            val acts = JSONArray()
            val first = toPoint(track.start.point)
            acts.put(move(0, first))
            val startMs = track.start.timeMs - t0
            val endMs = track.end.timeMs - t0
            var down = false
            var up = false
            for (k in 1..ticks) {
                val t = k * step
                when {
                    !down && t >= startMs -> { acts.put(JSONObject().put("type", "pointerDown").put("button", 0)); down = true }
                    down && !up && t < endMs + step -> {
                        val p = toPoint(positionAt(track, t0 + minOf(t, endMs)))
                        acts.put(move(step, p))
                        if (t >= endMs) { /* reached the end: next tick lifts */ }
                    }
                    down && !up -> { acts.put(JSONObject().put("type", "pointerUp").put("button", 0)); up = true }
                    else -> acts.put(JSONObject().put("type", "pause").put("duration", step))
                }
            }
            if (!up) acts.put(JSONObject().put("type", "pointerUp").put("button", 0))
            sources.put(
                JSONObject()
                    .put("type", "pointer")
                    .put("id", "finger${index + 1}")
                    .put("parameters", JSONObject().put("pointerType", "touch"))
                    .put("actions", acts),
            )
        }
        return JSONObject().put("actions", sources)
    }

    private fun move(duration: Long, p: Pair<Int, Int>) = JSONObject()
        .put("type", "pointerMove").put("duration", duration).put("origin", "viewport")
        .put("x", p.first).put("y", p.second)

    /** Linear interpolation of a track at absolute time [t]. */
    fun positionAt(track: GestureRecorder.Track, t: Long): NPoint {
        val s = track.samples
        if (t <= s.first().timeMs) return s.first().point
        if (t >= s.last().timeMs) return s.last().point
        for (i in 1 until s.size) {
            val b = s[i]
            if (b.timeMs >= t) {
                val a = s[i - 1]
                val span = (b.timeMs - a.timeMs).coerceAtLeast(1)
                val f = (t - a.timeMs).toFloat() / span
                return NPoint(a.point.u + (b.point.u - a.point.u) * f, a.point.v + (b.point.v - a.point.v) * f)
            }
        }
        return s.last().point
    }
}
