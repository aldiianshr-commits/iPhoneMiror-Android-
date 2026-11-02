package com.aldiianshr.iphonemiror.mirroring

import com.aldiianshr.iphonemiror.domain.stream.AudioCodec
import com.aldiianshr.iphonemiror.domain.stream.AudioFormatInfo
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class MirroringTest {

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun parsesBaselineSps1080p() {
        // Baseline SPS, 120x68 MBs (1920x1088) cropped by 8 rows → 1920x1080.
        val sps = hex("67420028da01e0089f95")
        val info = SpsParser.parse(sps)
        assertNotNull(info)
        assertEquals(66, info!!.profileIdc)
        assertEquals(1920, info.width)
        assertEquals(1080, info.height)
    }

    @Test fun parsesHighProfilePortraitSps() {
        // High profile, 68x120 MBs (1088x1920) cropped 8 px on the right → 1080x1920 portrait.
        val sps = hex("6764002aacb402201e3cba")
        val info = SpsParser.parse(sps)
        assertNotNull(info)
        assertEquals(100, info!!.profileIdc)
        assertEquals(42, info.levelIdc)
        assertEquals(1080, info.width)
        assertEquals(1920, info.height)
    }

    @Test fun unescapesEmulationPrevention() {
        assertArrayEquals(hex("00000100"), SpsParser.unescape(hex("0000030100")))
    }

    @Test fun splitsAnnexB() {
        val data = hex("0000000167aa000001688800000165")
        val nals = SpsParser.splitAnnexB(data)
        assertEquals(3, nals.size)
        assertArrayEquals(hex("67aa"), nals[0])
        assertArrayEquals(hex("6888"), nals[1])
        assertArrayEquals(hex("65"), nals[2])
    }

    @Test fun aacEldAudioSpecificConfigMatchesKnownValue() {
        // Well-known ASC used by AirPlay receivers for AAC-ELD 44.1 kHz stereo, 480 spf.
        val asc = AudioPlayerEngine.audioSpecificConfig(AudioFormatInfo(AudioCodec.AAC_ELD, 44100, 2, 480))
        assertArrayEquals(hex("f8e85000"), asc)
    }

    @Test fun aacLcAudioSpecificConfig() {
        val asc = AudioPlayerEngine.audioSpecificConfig(AudioFormatInfo(AudioCodec.AAC_LC, 44100, 2, 1024))
        assertArrayEquals(hex("1210"), asc)
    }
}
