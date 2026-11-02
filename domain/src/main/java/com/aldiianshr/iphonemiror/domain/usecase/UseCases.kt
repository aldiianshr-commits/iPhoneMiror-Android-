package com.aldiianshr.iphonemiror.domain.usecase

import com.aldiianshr.iphonemiror.domain.model.AppleDevice
import com.aldiianshr.iphonemiror.domain.repository.KnownDeviceRepository

/** Decision for an incoming AirPlay sender. */
enum class ApprovalDecision { ALLOW, ASK, DENY }

/**
 * Pairing is never silent: when approval is required, a device that has not been
 * explicitly allowed by the user must be confirmed on the tablet first.
 */
class EvaluateDeviceApproval(private val known: KnownDeviceRepository) {
    suspend operator fun invoke(device: AppleDevice, requireApproval: Boolean): ApprovalDecision {
        val record = known.get(device.id)
        return when {
            record?.blocked == true -> ApprovalDecision.DENY
            record?.allowed == true -> ApprovalDecision.ALLOW
            !requireApproval -> ApprovalDecision.ALLOW
            else -> ApprovalDecision.ASK
        }
    }
}
