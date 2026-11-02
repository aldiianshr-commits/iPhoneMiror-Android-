package com.aldiianshr.iphonemiror.input

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HidReportsTest {

    @Test fun descriptorCollectionsAreBalanced() {
        for (abs in listOf(true, false)) checkBalanced(HidReports.descriptor(abs))
    }

    @Test fun keyboardIsDeclaredFirst() {
        // iOS only treats the device as a hardware keyboard when the keyboard comes first.
        val d = HidReports.descriptor(true)
        assertEquals(0x09, d[2].toInt() and 0xFF)
        assertEquals(0x06, d[3].toInt() and 0xFF)
    }

    @Test fun absoluteDescriptorUsesUint16Range() {
        val d = HidReports.descriptor(true).map { it.toInt() and 0xFF }
        val idx = (0 until d.size - 6).first { d[it] == 0x26 && d[it + 1] == 0xFF && d[it + 2] == 0x7F }
        assertTrue(idx > 0)
        // Input (Data,Var,Abs) for X/Y
        assertTrue((0 until d.size - 1).any { d[it] == 0x81 && d[it + 1] == 0x02 })
    }

    @Test fun absMouseReportIsLittleEndian() {
        assertArrayEquals(byteArrayOf(1, 0xFF.toByte(), 0x7F, 0, 0x40, 0, 0), HidReports.absMouse(1, 40000, 0x4000))
        assertEquals(7, HidReports.absMouse(0, 0, 0).size)
        assertArrayEquals(byteArrayOf(0x40, 0), HidReports.consumer(HidReports.CONSUMER_HOME))
        assertArrayEquals(byteArrayOf(0x21, 0x02), HidReports.consumer(HidReports.CONSUMER_SEARCH))
    }

    private fun checkBalanced(bytes: ByteArray) {
        val d = bytes.map { it.toInt() and 0xFF }
        // Walk HID short items and count Collection (0xA1) / End Collection (0xC0).
        var i = 0; var open = 0; var maxDepth = 0
        while (i < d.size) {
            val prefix = d[i]
            val size = when (prefix and 0x03) { 3 -> 4; else -> prefix and 0x03 }
            when (prefix and 0xFC) {
                0xA0 -> { open++; maxDepth = maxOf(maxDepth, open) }
                0xC0 -> open--
            }
            i += 1 + size
        }
        assertEquals(0, open)
        assertEquals(d.size, i)
        assertEquals(2, maxDepth)
    }

    @Test fun mouseReportClampsAndEncodes() {
        assertArrayEquals(byteArrayOf(1, 127, -127, 0, 0), HidReports.mouse(HidReports.BUTTON_LEFT, 500, -500))
        assertEquals(5, HidReports.mouse(0, 0, 0).size)
    }

    @Test fun keyboardReport() {
        val r = HidReports.keyboard(HidReports.MOD_LGUI, listOf(KeyMap.SPACE))
        assertArrayEquals(byteArrayOf(0x08, 0, 0x2C, 0, 0, 0, 0, 0), r)
    }

    @Test fun splitMovementPreservesTotal() {
        val steps = HidReports.splitMovement(300, -50)
        assertTrue(steps.all { kotlin.math.abs(it.first) <= 127 && kotlin.math.abs(it.second) <= 127 })
        assertEquals(300, steps.sumOf { it.first })
        assertEquals(-50, steps.sumOf { it.second })
        assertTrue(HidReports.splitMovement(0, 0).isEmpty())
        val fine = HidReports.splitMovement(25, 13, maxStep = 6)
        assertEquals(25, fine.sumOf { it.first }); assertEquals(13, fine.sumOf { it.second })
    }

    @Test fun keyMapUsLayout() {
        assertEquals(KeyMap.Stroke(0x04), KeyMap.forChar('a'))
        assertEquals(KeyMap.Stroke(0x04, HidReports.MOD_LSHIFT), KeyMap.forChar('A'))
        assertEquals(KeyMap.Stroke(0x27), KeyMap.forChar('0'))
        assertEquals(KeyMap.Stroke(0x1F, HidReports.MOD_LSHIFT), KeyMap.forChar('@'))
        assertNull(KeyMap.forChar('é'))
    }
}
