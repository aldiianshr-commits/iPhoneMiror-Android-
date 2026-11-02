package com.aldiianshr.iphonemiror.input

import com.aldiianshr.iphonemiror.domain.model.HidOrientation
import com.aldiianshr.iphonemiror.input.touch.NPoint
import com.aldiianshr.iphonemiror.input.touch.ScaleMode
import com.aldiianshr.iphonemiror.input.touch.TouchMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TouchMapperTest {
    private val eps = 0.001f

    @Test fun portraitPhoneInLandscapeTabletIsPillarboxed() {
        // Pad 6 landscape 2880x1800, iPhone portrait stream 1179x2556.
        val r = TouchMapper.contentRect(2880f, 1800f, 1179, 2556, ScaleMode.ASPECT)
        assertEquals(1800f, r.height, 0.5f)
        assertEquals(1800f * 1179 / 2556, r.width, 0.5f)
        assertEquals((2880f - r.width) / 2, r.left, 0.5f)
        // Touch in the black bar is not forwarded.
        assertNull(TouchMapper.toPhone(100f, 900f, r))
        // Centre of the picture is the centre of the iPhone.
        val c = TouchMapper.toPhone(1440f, 900f, r)!!
        assertEquals(0.5f, c.u, eps); assertEquals(0.5f, c.v, eps)
        // Top-left corner of the picture.
        val tl = TouchMapper.toPhone(r.left, r.top, r)!!
        assertEquals(0f, tl.u, eps); assertEquals(0f, tl.v, eps)
    }

    @Test fun landscapePhoneInPortraitTabletIsLetterboxed() {
        val r = TouchMapper.contentRect(1800f, 2880f, 2556, 1179, ScaleMode.ASPECT)
        assertEquals(1800f, r.width, 0.5f)
        assertEquals((2880f - r.height) / 2, r.top, 0.5f)
        assertNull(TouchMapper.toPhone(900f, 10f, r))
    }

    @Test fun stretchFillsWholeView() {
        val r = TouchMapper.contentRect(2880f, 1800f, 1179, 2556, ScaleMode.STRETCH)
        val p = TouchMapper.toPhone(2880f * 0.25f, 1800f * 0.75f, r)!!
        assertEquals(0.25f, p.u, eps); assertEquals(0.75f, p.v, eps)
    }

    @Test fun fillCropsAndStillMapsExactly() {
        val r = TouchMapper.contentRect(2880f, 1800f, 1179, 2556, ScaleMode.FILL)
        assertEquals(2880f, r.width, 0.5f)
        val p = TouchMapper.toPhone(1440f, 900f, r)!!
        assertEquals(0.5f, p.u, eps); assertEquals(0.5f, p.v, eps)
    }

    @Test fun zoomAndPanAreInverted() {
        val content = TouchMapper.contentRect(1000f, 1000f, 500, 1000, ScaleMode.ASPECT) // 500x1000 at x=250
        val d = TouchMapper.displayedRect(content, 2f, 100f, 0f)
        // Zoom 2x around centre (500,500) then pan +100: the iPhone centre is now at view x=600.
        val p = TouchMapper.toPhone(600f, 500f, d)!!
        assertEquals(0.5f, p.u, eps); assertEquals(0.5f, p.v, eps)
        val (x, y) = TouchMapper.toView(NPoint(0.5f, 0.5f), d)
        assertEquals(600f, x, eps); assertEquals(500f, y, eps)
    }

    @Test fun clampKeepsDragsOnScreen() {
        val r = TouchMapper.contentRect(1000f, 1000f, 1000, 1000, ScaleMode.ASPECT)
        assertNull(TouchMapper.toPhone(-50f, 500f, r))
        val p = TouchMapper.toPhone(-50f, 500f, r, clamp = true)
        assertNotNull(p); assertEquals(0f, p!!.u, eps)
    }

    @Test fun hidAbsoluteMappingAndRotation() {
        val p = NPoint(0.25f, 0.75f)
        assertEquals(8191 to 24575, TouchMapper.toHidAbsolute(p, videoLandscape = false, HidOrientation.PORTRAIT_ROT90))
        assertEquals(8191 to 24575, TouchMapper.toHidAbsolute(p, videoLandscape = true, HidOrientation.FOLLOW_SCREEN))
        // Rotations map landscape picture space onto the native portrait range.
        assertEquals(24575 to 24575, TouchMapper.toHidAbsolute(p, true, HidOrientation.PORTRAIT_ROT90))
        assertEquals(8191 to 8191, TouchMapper.toHidAbsolute(p, true, HidOrientation.PORTRAIT_ROT270))
    }

    @Test fun pointsFollowWindowOrientation() {
        // iPhone 15: 393x852 points.
        assertEquals(196 to 425, TouchMapper.toPoints(NPoint(0.5f, 0.5f), 393, 852, videoLandscape = false))
        // Landscape picture but WDA still reports a portrait window: axes are swapped.
        assertEquals(425 to 196, TouchMapper.toPoints(NPoint(0.5f, 0.5f), 393, 852, videoLandscape = true))
    }
}
