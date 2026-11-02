package com.aldiianshr.iphonemiror.domain.model

/**
 * Derives a connection-quality grade from real stream measurements (decoded fps,
 * time since the last frame and decode errors). No synthetic values.
 */
object LinkQualityCalculator {
    fun evaluate(stats: StreamStats, maxFps: Int): LinkQuality {
        if (!stats.hasVideo || stats.framesReceived == 0L) return LinkQuality.NONE
        // iOS stops sending frames while the screen is static, so a long gap alone is not
        // a failure; only grade it as poor when it is very long.
        if (stats.lastFrameAgeMs > 5_000) return LinkQuality.POOR
        val errorRatio = if (stats.framesReceived > 0) stats.decodeErrors.toFloat() / stats.framesReceived else 0f
        if (errorRatio > 0.05f) return LinkQuality.POOR
        val fpsRatio = if (maxFps > 0) stats.fps / maxFps else 0f
        return when {
            stats.lastFrameAgeMs in 0..1_000 && fpsRatio >= 0.75f -> LinkQuality.EXCELLENT
            fpsRatio >= 0.45f -> LinkQuality.GOOD
            fpsRatio >= 0.2f -> LinkQuality.FAIR
            // Low fps with recent frames is usually a static screen, not a bad link.
            stats.lastFrameAgeMs in 0..1_500 -> LinkQuality.GOOD
            else -> LinkQuality.FAIR
        }
    }
}
