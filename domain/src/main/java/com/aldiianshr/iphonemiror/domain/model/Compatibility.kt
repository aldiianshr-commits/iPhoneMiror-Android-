package com.aldiianshr.iphonemiror.domain.model

enum class CompatibilityLevel {
    /** Matches the project's target (iOS 26/27) but has not been verified on real hardware yet. */
    TARGET_UNVERIFIED,
    /** Older iOS that speaks the same AirPlay mirroring protocol; expected to work, not verified. */
    LIKELY,
    /** iOS older than AirPlay mirroring with legacy pairing support. */
    UNSUPPORTED,
    /** Device did not report a version. */
    UNKNOWN,
}

data class Compatibility(val level: CompatibilityLevel, val message: String)

/**
 * Honest compatibility assessment. Nothing is reported as "verified" until it has been
 * tested on a physical device and recorded in docs/COMPATIBILITY.md.
 */
object CompatibilityChecker {
    fun evaluate(osVersion: String?): Compatibility {
        val major = osVersion?.substringBefore('.')?.toIntOrNull()
            ?: return Compatibility(CompatibilityLevel.UNKNOWN, "Versi iOS tidak dilaporkan oleh perangkat.")
        return when {
            major >= 26 -> Compatibility(
                CompatibilityLevel.TARGET_UNVERIFIED,
                "iOS $osVersion adalah target proyek, tetapi belum diverifikasi pada perangkat nyata.",
            )
            major >= 12 -> Compatibility(
                CompatibilityLevel.LIKELY,
                "iOS $osVersion memakai protokol AirPlay mirroring yang sama; belum diverifikasi.",
            )
            else -> Compatibility(
                CompatibilityLevel.UNSUPPORTED,
                "iOS $osVersion terlalu lama untuk jalur pairing yang diimplementasikan.",
            )
        }
    }
}
