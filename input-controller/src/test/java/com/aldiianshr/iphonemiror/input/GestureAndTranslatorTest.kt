package com.aldiianshr.iphonemiror.input

import com.aldiianshr.iphonemiror.input.touch.GestureClassifier
import com.aldiianshr.iphonemiror.input.touch.GestureKind
import com.aldiianshr.iphonemiror.input.touch.GestureRecorder
import com.aldiianshr.iphonemiror.input.touch.HidCommand
import com.aldiianshr.iphonemiror.input.touch.HidTouchTranslator
import com.aldiianshr.iphonemiror.input.touch.NPoint
import com.aldiianshr.iphonemiror.input.touch.TouchEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureAndTranslatorTest {
    private val aspect = 1179f / 2556f

    private fun record(vararg events: TouchEvent): GestureRecorder =
        GestureRecorder().also { r -> events.forEach { r.onEvent(it) } }

    private fun swipe(from: NPoint, to: NPoint, ms: Long): Array<TouchEvent> {
        val steps = 10
        val out = ArrayList<TouchEvent>()
        out += TouchEvent.Down(0, from, 0)
        for (i in 1..steps) {
            val f = i.toFloat() / steps
            out += TouchEvent.Move(mapOf(0 to NPoint(from.u + (to.u - from.u) * f, from.v + (to.v - from.v) * f)), ms * i / steps)
        }
        out += TouchEvent.Up(0, to, ms)
        return out.toTypedArray()
    }

    @Test fun classifiesSingleFingerGestures() {
        val p = NPoint(0.5f, 0.5f)
        assertEquals(GestureKind.TAP, GestureClassifier.classify(record(TouchEvent.Down(0, p, 0), TouchEvent.Up(0, p, 90)).tracks(), aspect))
        assertEquals(GestureKind.LONG_PRESS, GestureClassifier.classify(record(TouchEvent.Down(0, p, 0), TouchEvent.Up(0, p, 800)).tracks(), aspect))
        assertEquals(GestureKind.DOUBLE_TAP, GestureClassifier.classify(
            record(TouchEvent.Down(0, p, 1000), TouchEvent.Up(0, p, 1080)).tracks(), aspect, p to 800L))
        assertEquals(GestureKind.SWIPE_UP, GestureClassifier.classify(record(*swipe(NPoint(0.5f, 0.9f), NPoint(0.5f, 0.3f), 250)).tracks(), aspect))
        assertEquals(GestureKind.SWIPE_DOWN, GestureClassifier.classify(record(*swipe(NPoint(0.5f, 0.1f), NPoint(0.5f, 0.6f), 250)).tracks(), aspect))
        assertEquals(GestureKind.SWIPE_LEFT, GestureClassifier.classify(record(*swipe(NPoint(0.9f, 0.5f), NPoint(0.1f, 0.5f), 250)).tracks(), aspect))
        assertEquals(GestureKind.SWIPE_RIGHT, GestureClassifier.classify(record(*swipe(NPoint(0.1f, 0.5f), NPoint(0.9f, 0.5f), 250)).tracks(), aspect))
        assertEquals(GestureKind.DRAG, GestureClassifier.classify(record(*swipe(NPoint(0.2f, 0.2f), NPoint(0.8f, 0.8f), 2000)).tracks(), aspect))
    }

    @Test fun classifiesTwoFingerGestures() {
        val pinchOut = record(
            TouchEvent.Down(0, NPoint(0.45f, 0.5f), 0), TouchEvent.Down(1, NPoint(0.55f, 0.5f), 5),
            TouchEvent.Move(mapOf(0 to NPoint(0.3f, 0.5f), 1 to NPoint(0.7f, 0.5f)), 200),
            TouchEvent.Up(0, NPoint(0.3f, 0.5f), 300), TouchEvent.Up(1, NPoint(0.7f, 0.5f), 300),
        )
        assertEquals(GestureKind.PINCH_OUT, GestureClassifier.classify(pinchOut.tracks(), aspect))
        val scroll = record(
            TouchEvent.Down(0, NPoint(0.4f, 0.4f), 0), TouchEvent.Down(1, NPoint(0.6f, 0.4f), 5),
            TouchEvent.Move(mapOf(0 to NPoint(0.4f, 0.7f), 1 to NPoint(0.6f, 0.7f)), 200),
            TouchEvent.Up(0, NPoint(0.4f, 0.7f), 300), TouchEvent.Up(1, NPoint(0.6f, 0.7f), 300),
        )
        assertEquals(GestureKind.TWO_FINGER_SCROLL, GestureClassifier.classify(scroll.tracks(), aspect))
    }

    private fun reports(cmds: List<HidCommand>) = cmds.filterIsInstance<HidCommand.Report>()
    private fun buttons(r: HidCommand.Report) = r.data[0].toInt()
    private fun x(r: HidCommand.Report) = (r.data[1].toInt() and 0xFF) or ((r.data[2].toInt() and 0xFF) shl 8)
    private fun y(r: HidCommand.Report) = (r.data[3].toInt() and 0xFF) or ((r.data[4].toInt() and 0xFF) shl 8)

    @Test fun absoluteTapHoversThenPressesAtTheTouchedPoint() {
        val t = HidTouchTranslator().apply { aspect = this@GestureAndTranslatorTest.aspect }
        val p = NPoint(0.25f, 0.5f)
        val down = t.onEvent(TouchEvent.Down(0, p, 10_000))
        val hovers = reports(down.commands)
        // First touch after idle: the position is sent twice (wake, then place) before pressing.
        assertEquals(2, hovers.size)
        assertTrue(down.commands.any { it is HidCommand.Delay })
        hovers.forEach { assertEquals(0, buttons(it)); assertEquals(8191, x(it)); assertEquals(16383, y(it)) }
        assertEquals(HidTouchTranslator.WAKE_SETTLE_MS, down.pressAfterMs)
        // A touch right after the previous one is placed once and pressed almost immediately.
        t.onPressDue(); t.onEvent(TouchEvent.Up(0, p, 10_050))
        val quick = t.onEvent(TouchEvent.Down(0, p, 10_400))
        assertEquals(1, reports(quick.commands).size)
        assertEquals(HidTouchTranslator.ARRIVE_SETTLE_MS, quick.pressAfterMs)
        val press = reports(t.onPressDue()).single()
        assertEquals(HidReports.BUTTON_LEFT, buttons(press))
        val up = reports(t.onEvent(TouchEvent.Up(0, p, 10_100)).commands)
        assertEquals(0, buttons(up.last()))
        assertEquals(8191, x(up.last()))
    }

    @Test fun quickTapBeforeSettleStillClicks() {
        val t = HidTouchTranslator()
        val p = NPoint(0.5f, 0.5f)
        t.onEvent(TouchEvent.Down(0, p, 0))
        val up = t.onEvent(TouchEvent.Up(0, p, 20)).commands
        val r = reports(up)
        assertEquals(listOf(HidReports.BUTTON_LEFT, 0), r.map { buttons(it) })
        assertTrue(up.any { it is HidCommand.Delay })
        assertTrue("press must not fire after the tap", t.onPressDue().isEmpty())
    }

    @Test fun swipeStreamsPositionsWithButtonHeld() {
        val t = HidTouchTranslator()
        t.onEvent(TouchEvent.Down(0, NPoint(0.5f, 0.9f), 0)); t.onPressDue()
        val mid = reports(t.onEvent(TouchEvent.Move(mapOf(0 to NPoint(0.5f, 0.5f)), 50)).commands).single()
        assertEquals(HidReports.BUTTON_LEFT, buttons(mid))
        assertEquals(16383, y(mid))
        assertEquals("abs-pos", mid.coalesceKey)
        val up = reports(t.onEvent(TouchEvent.Up(0, NPoint(0.5f, 0.2f), 120)).commands)
        assertEquals(0, buttons(up.last()))
        assertEquals((0.2f * HidReports.ABS_MAX).toInt(), y(up.last()))
    }

    @Test fun extraFingersAreIgnoredLikeASinglePointer() {
        val t = HidTouchTranslator()
        t.onEvent(TouchEvent.Down(0, NPoint(0.4f, 0.4f), 0)); t.onPressDue()
        // A second finger does not release, scroll or move the pointer.
        assertTrue(t.onEvent(TouchEvent.Down(1, NPoint(0.6f, 0.4f), 40)).commands.isEmpty())
        val move = reports(t.onEvent(TouchEvent.Move(mapOf(0 to NPoint(0.4f, 0.6f), 1 to NPoint(0.9f, 0.9f)), 120)).commands).single()
        assertEquals(HidReports.BUTTON_LEFT, buttons(move))
        assertEquals((0.6f * HidReports.ABS_MAX).toInt(), y(move))
        assertTrue(t.onEvent(TouchEvent.Move(mapOf(1 to NPoint(0.1f, 0.1f)), 150)).commands.isEmpty())
        assertTrue(t.onEvent(TouchEvent.Up(1, NPoint(0.1f, 0.1f), 160)).commands.isEmpty())
        assertTrue(t.isPressed)
        val up = reports(t.onEvent(TouchEvent.Up(0, NPoint(0.4f, 0.6f), 200)).commands)
        assertEquals(0, buttons(up.last()))
        assertNull(t.onEvent(TouchEvent.Down(2, NPoint(0.5f, 0.5f), 300)).unsupported)
    }

    @Test fun relativeModeTapClicksAndDragMoves() {
        val t = HidTouchTranslator().apply { absolute = false }
        val p = NPoint(0.5f, 0.5f)
        assertTrue(t.onEvent(TouchEvent.Down(0, p, 0)).commands.isEmpty())
        val click = reports(t.onEvent(TouchEvent.Up(0, p, 60)).commands)
        assertEquals(listOf(HidReports.BUTTON_LEFT, 0), click.map { buttons(it) })
        t.onEvent(TouchEvent.Down(0, p, 1000))
        val move = reports(t.onEvent(TouchEvent.Move(mapOf(0 to NPoint(0.5f, 0.6f)), 1050)).commands)
        assertTrue(move.isNotEmpty())
        assertTrue(move.sumOf { it.data[2].toInt() } > 0)
    }
}
