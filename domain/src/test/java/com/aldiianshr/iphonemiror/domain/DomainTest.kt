package com.aldiianshr.iphonemiror.domain

import com.aldiianshr.iphonemiror.domain.model.AppleDevice
import com.aldiianshr.iphonemiror.domain.model.AppleModels
import com.aldiianshr.iphonemiror.domain.model.CompatibilityChecker
import com.aldiianshr.iphonemiror.domain.model.CompatibilityLevel
import com.aldiianshr.iphonemiror.domain.model.ConnectionType
import com.aldiianshr.iphonemiror.domain.model.KnownDevice
import com.aldiianshr.iphonemiror.domain.model.LinkQuality
import com.aldiianshr.iphonemiror.domain.model.LinkQualityCalculator
import com.aldiianshr.iphonemiror.domain.model.StreamStats
import com.aldiianshr.iphonemiror.domain.repository.KnownDeviceRepository
import com.aldiianshr.iphonemiror.domain.usecase.ApprovalDecision
import com.aldiianshr.iphonemiror.domain.usecase.EvaluateDeviceApproval
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DomainTest {

    @Test fun compatibilityNeverClaimsVerified() {
        assertEquals(CompatibilityLevel.TARGET_UNVERIFIED, CompatibilityChecker.evaluate("26.1").level)
        assertEquals(CompatibilityLevel.TARGET_UNVERIFIED, CompatibilityChecker.evaluate("27.0").level)
        assertEquals(CompatibilityLevel.LIKELY, CompatibilityChecker.evaluate("17.5.1").level)
        assertEquals(CompatibilityLevel.UNSUPPORTED, CompatibilityChecker.evaluate("9.3").level)
        assertEquals(CompatibilityLevel.UNKNOWN, CompatibilityChecker.evaluate(null).level)
        assertEquals(CompatibilityLevel.UNKNOWN, CompatibilityChecker.evaluate("abc").level)
    }

    @Test fun modelNames() {
        assertEquals("iPhone 15 Pro Max", AppleModels.marketingName("iPhone16,2"))
        assertNull(AppleModels.marketingName("iPhone99,9"))
        val unknown = AppleDevice(id = "x", model = "iPhone99,9", connectionType = ConnectionType.WIFI)
        assertEquals("iPhone99,9", unknown.displayName)
    }

    @Test fun linkQuality() {
        assertEquals(LinkQuality.NONE, LinkQualityCalculator.evaluate(StreamStats(), 60))
        val good = StreamStats(width = 1080, height = 1920, fps = 58f, framesReceived = 500, lastFrameAgeMs = 16)
        assertEquals(LinkQuality.EXCELLENT, LinkQualityCalculator.evaluate(good, 60))
        val stale = good.copy(lastFrameAgeMs = 9_000)
        assertEquals(LinkQuality.POOR, LinkQualityCalculator.evaluate(stale, 60))
        val errors = good.copy(decodeErrors = 100)
        assertEquals(LinkQuality.POOR, LinkQualityCalculator.evaluate(errors, 60))
        val staticScreen = good.copy(fps = 2f, lastFrameAgeMs = 400)
        assertEquals(LinkQuality.GOOD, LinkQualityCalculator.evaluate(staticScreen, 60))
    }

    @Test fun approvalIsNeverSilentForUnknownDevices() = runTest {
        val repo = FakeKnown()
        val use = EvaluateDeviceApproval(repo)
        val dev = AppleDevice(id = "AA", connectionType = ConnectionType.WIFI)
        assertEquals(ApprovalDecision.ASK, use(dev, requireApproval = true))
        assertEquals(ApprovalDecision.ALLOW, use(dev, requireApproval = false))
        repo.map["AA"] = KnownDevice("AA", null, null, null, allowed = true, blocked = false, firstSeen = 1, lastSeen = 1)
        assertEquals(ApprovalDecision.ALLOW, use(dev, requireApproval = true))
        repo.map["AA"] = KnownDevice("AA", null, null, null, allowed = false, blocked = true, firstSeen = 1, lastSeen = 1)
        assertEquals(ApprovalDecision.DENY, use(dev, requireApproval = false))
    }

    private class FakeKnown : KnownDeviceRepository {
        val map = mutableMapOf<String, KnownDevice>()
        override fun observe(): Flow<List<KnownDevice>> = MutableStateFlow(map.values.toList())
        override suspend fun get(deviceId: String) = map[deviceId]
        override suspend fun upsertSeen(device: AppleDevice, now: Long) {}
        override suspend fun setAllowed(deviceId: String, allowed: Boolean) {}
        override suspend fun setBlocked(deviceId: String, blocked: Boolean) {}
        override suspend fun forget(deviceId: String) { map.remove(deviceId) }
        override suspend fun forgetAll() { map.clear() }
    }
}
