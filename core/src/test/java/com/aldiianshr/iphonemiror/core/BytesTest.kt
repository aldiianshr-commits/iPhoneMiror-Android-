package com.aldiianshr.iphonemiror.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class BytesTest {
    @Test fun hexRoundTrip() {
        val bytes = byteArrayOf(0, 1, 0x7f, 0x80.toByte(), 0xff.toByte())
        assertEquals("00017f80ff", bytes.toHex())
        assertArrayEquals(bytes, "00017f80ff".hexToBytes())
        assertArrayEquals(bytes, "00:01:7F:80:FF".hexToBytes())
    }

    @Test fun macFormatting() {
        assertEquals("0A:1B:2C:3D:4E:5F", "0a1b2c3d4e5f".hexToBytes().toMacString())
    }

    @Test fun littleEndianReaders() {
        val b = byteArrayOf(0x78, 0x56, 0x34, 0x12, 1, 0, 0, 0)
        assertEquals(0x12345678, b.readIntLE(0))
        assertEquals(0x5678, b.readUShortLE(0))
        assertEquals(0x0000000112345678L, b.readLongLE(0))
        assertEquals(0x78563412L, b.readUIntBE(0))
    }
}
