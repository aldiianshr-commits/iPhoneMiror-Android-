package com.aldiianshr.iphonemiror.connection

import com.aldiianshr.iphonemiror.connection.airplay.AirPlayProfile
import com.aldiianshr.iphonemiror.connection.airplay.AirPlayReceiver
import com.aldiianshr.iphonemiror.connection.airplay.H264Framing
import com.aldiianshr.iphonemiror.connection.airplay.SetupRequest
import com.aldiianshr.iphonemiror.connection.airplay.StreamRequest
import com.aldiianshr.iphonemiror.domain.stream.AudioCodec
import com.dd.plist.BinaryPropertyListWriter
import com.dd.plist.NSArray
import com.dd.plist.NSDictionary
import com.dd.plist.NSNumber
import com.dd.plist.PropertyListParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPlayProfileTest {

    @Test fun featureMaskAdvertisesOnlyImplementedCapabilities() {
        val f = AirPlayProfile.FEATURES
        fun bit(n: Int) = (f and (1L shl n)) != 0L
        assertTrue("screen", bit(7))
        assertTrue("legacy pairing", bit(27))
        assertFalse("video URL playback not implemented", bit(0))
        assertFalse("HLS not implemented", bit(4))
        assertFalse("HomeKit pairing not implemented", bit(46))
        assertEquals("0x%X,0x0".format(f), AirPlayProfile.FEATURES_TXT)
    }

    @Test fun txtRecordsFitDnsSdLimits() {
        val txt = AirPlayProfile.airplayTxt("AA:BB:CC:DD:EE:FF", "ab".repeat(32)) + AirPlayProfile.raopTxt("ab".repeat(32))
        for ((k, v) in txt) assertTrue("$k too long", k.length + v.length + 1 <= 255)
        assertEquals("AABBCCDDEEFF@Pad", AirPlayProfile.raopServiceName("AA:BB:CC:DD:EE:FF", "Pad"))
    }

    @Test fun infoPlistContainsDisplayAndKey() {
        val pk = ByteArray(32) { it.toByte() }
        val dict = PropertyListParser.parse(AirPlayProfile.infoPlist("Pad 6", 1920, 1080, 60, pk, "AA:BB:CC:DD:EE:FF")) as NSDictionary
        assertEquals("Pad 6", dict["name"].toString())
        val display = (dict["displays"] as NSArray).array[0] as NSDictionary
        assertEquals(1920, (display["width"] as NSNumber).intValue())
        assertEquals(60, (display["maxFPS"] as NSNumber).intValue())
        assertArrayEquals(pk, dict["pk"]!!.toJavaObject() as ByteArray)
    }

    @Test fun parsesSenderInfoAndStreams() {
        val body = BinaryPropertyListWriter.writeToArray(NSDictionary().apply {
            put("ekey", ByteArray(72)); put("eiv", ByteArray(16))
            put("deviceID", "11:22:33:44:55:66"); put("model", "iPhone17,1"); put("name", "Aldi")
            put("osName", "iPhone OS"); put("osVersion", "26.1"); put("osBuildVersion", "23B85")
        })
        val parsed = SetupRequest.parse(body)!!
        assertTrue(parsed.hasEncryptionKey)
        assertEquals("iPhone17,1", parsed.sender.model)
        assertEquals("26.1", parsed.sender.osVersion)
        assertTrue(parsed.streams.isEmpty())

        val streamBody = BinaryPropertyListWriter.writeToArray(NSDictionary().apply {
            put("streams", NSArray(NSDictionary().apply { put("type", 110); put("streamConnectionID", -5L) }))
        })
        val s = SetupRequest.parse(streamBody)!!
        assertEquals(110, s.streams.single().type)
        assertEquals(-5L, s.streams.single().streamConnectionId)
        assertNull(s.sender.model)
        assertNull(SetupRequest.parse(ByteArray(0)))
        assertNull(SetupRequest.parse(byteArrayOf(1, 2, 3)))
    }

    @Test fun avccConversion() {
        val payload = byteArrayOf(0, 0, 0, 2, 0x65, 0x11, 0, 0, 0, 1, 0x41)
        assertTrue(H264Framing.avccToAnnexBInPlace(payload))
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x65, 0x11, 0, 0, 0, 1, 0x41), payload)
        assertTrue(H264Framing.containsIdr(payload))
        assertFalse(H264Framing.avccToAnnexBInPlace(byteArrayOf(0, 0, 0, 9, 1, 2)))
    }

    @Test fun parsesAvcDecoderConfigurationRecord() {
        val sps = byteArrayOf(0x67, 0x64, 0x00, 0x28, 0x11)
        val pps = byteArrayOf(0x68, 0x22)
        val record = byteArrayOf(1, 0x64, 0, 0x28, 0xFF.toByte(), 0xE1.toByte(), 0, 5) + sps + byteArrayOf(1, 0, 2) + pps
        val cfg = H264Framing.parseAvcConfig(record)
        assertNotNull(cfg)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1) + sps + byteArrayOf(0, 0, 0, 1) + pps, cfg!!.toAnnexB())
        assertNull(H264Framing.parseAvcConfig(byteArrayOf(1, 2, 3)))
    }

    @Test fun audioFormatMapping() {
        val f = AirPlayReceiver.audioFormatOf(StreamRequest(96, null, 8, 0x1000000L, 480))
        assertEquals(AudioCodec.AAC_ELD, f.codec)
        assertEquals(44100, f.sampleRate)
        assertEquals(2, f.channels)
        assertEquals(0f, AirPlayReceiver.volumeFromDb(-144.0))
        assertEquals(1f, AirPlayReceiver.volumeFromDb(0.0))
        assertTrue(AirPlayReceiver.volumeFromDb(-15.0) in 0.1f..0.2f)
    }
}
