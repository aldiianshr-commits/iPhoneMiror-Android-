package com.aldiianshr.iphonemiror.input.touch

import com.aldiianshr.iphonemiror.domain.model.HidOrientation
import com.aldiianshr.iphonemiror.input.HidReports

enum class ScaleMode(val label: String) {
    /** Keep the iPhone aspect ratio, letterbox/pillarbox inside the tablet view. */
    ASPECT("Rasio asli"),
    /** Keep aspect ratio but fill the tablet view (edges cropped). */
    FILL("Isi (crop)"),
    /** Stretch to the tablet view (Fit to Screen). */
    STRETCH("Fit to screen"),
}

data class Rect(val left: Float, val top: Float, val width: Float, val height: Float) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height
}

/**
 * Converts between tablet view coordinates and iPhone screen coordinates, accounting for
 * the iPhone stream resolution, the tablet view size, aspect ratio, letterboxing, scaling,
 * user zoom/pan and the iPhone's orientation. The UI places the video with [contentRect]
 * so drawing and touch mapping use exactly the same math.
 */
object TouchMapper {

    /** Where the (unzoomed) video is drawn inside a container of the given size. */
    fun contentRect(containerW: Float, containerH: Float, videoW: Int, videoH: Int, mode: ScaleMode): Rect {
        if (containerW <= 0f || containerH <= 0f || videoW <= 0 || videoH <= 0) return Rect(0f, 0f, containerW, containerH)
        if (mode == ScaleMode.STRETCH) return Rect(0f, 0f, containerW, containerH)
        val sx = containerW / videoW
        val sy = containerH / videoH
        val s = if (mode == ScaleMode.ASPECT) minOf(sx, sy) else maxOf(sx, sy)
        val w = videoW * s
        val h = videoH * s
        return Rect((containerW - w) / 2f, (containerH - h) / 2f, w, h)
    }

    /** The content rect after zooming around its centre and panning. */
    fun displayedRect(content: Rect, zoom: Float, panX: Float, panY: Float): Rect {
        val cx = content.left + content.width / 2f
        val cy = content.top + content.height / 2f
        val w = content.width * zoom
        val h = content.height * zoom
        return Rect(cx - w / 2f + panX, cy - h / 2f + panY, w, h)
    }

    /** Tablet view point → normalized iPhone point, or null if the touch is outside the picture. */
    fun toPhone(x: Float, y: Float, displayed: Rect, clamp: Boolean = false): NPoint? {
        if (displayed.width <= 0f || displayed.height <= 0f) return null
        val u = (x - displayed.left) / displayed.width
        val v = (y - displayed.top) / displayed.height
        if (clamp) return NPoint(u.coerceIn(0f, 1f), v.coerceIn(0f, 1f))
        if (u < 0f || u > 1f || v < 0f || v > 1f) return null
        return NPoint(u, v)
    }

    /** Normalized iPhone point → tablet view point (for drawing touch indicators). */
    fun toView(p: NPoint, displayed: Rect): Pair<Float, Float> =
        (displayed.left + p.u * displayed.width) to (displayed.top + p.v * displayed.height)

    /**
     * Normalized point in the mirrored picture → HID absolute coordinates (0..32767).
     * When the iPhone is in landscape the absolute range may follow the panel's native
     * portrait orientation; [orientation] selects how to rotate in that case.
     */
    fun toHidAbsolute(p: NPoint, videoLandscape: Boolean, orientation: HidOrientation): Pair<Int, Int> {
        val (pu, pv) = if (!videoLandscape) p.u to p.v else when (orientation) {
            HidOrientation.FOLLOW_SCREEN -> p.u to p.v
            HidOrientation.PORTRAIT_ROT90 -> p.v to (1f - p.u)
            HidOrientation.PORTRAIT_ROT270 -> (1f - p.v) to p.u
        }
        val max = HidReports.ABS_MAX
        return (pu.coerceIn(0f, 1f) * max).toInt() to (pv.coerceIn(0f, 1f) * max).toInt()
    }

    /**
     * Normalized point → XCTest/WDA coordinates (points, current orientation). If the window
     * size reported by WDA has the other orientation than the picture, the axes are swapped.
     */
    fun toPoints(p: NPoint, windowW: Int, windowH: Int, videoLandscape: Boolean): Pair<Int, Int> {
        val landscapeWindow = windowW > windowH
        val (w, h) = if (landscapeWindow == videoLandscape) windowW to windowH else windowH to windowW
        return (p.u.coerceIn(0f, 1f) * (w - 1)).toInt() to (p.v.coerceIn(0f, 1f) * (h - 1)).toInt()
    }
}
