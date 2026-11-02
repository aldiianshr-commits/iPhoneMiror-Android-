package com.aldiianshr.iphonemiror.connection.airplay

/** Converts AirPlay mirror payloads (AVCC / avcC) into Annex-B for MediaCodec. */
object H264Framing {

    private val START_CODE = byteArrayOf(0, 0, 0, 1)

    /**
     * Rewrites 4-byte big-endian NAL length prefixes into start codes, in place.
     * Returns false when the lengths do not add up (typically a decryption error),
     * in which case the frame must not be fed to the decoder.
     */
    fun avccToAnnexBInPlace(payload: ByteArray): Boolean {
        var idx = 0
        if (payload.size < 5) return false
        while (idx + 4 <= payload.size) {
            val size = ((payload[idx].toInt() and 0xFF) shl 24) or
                ((payload[idx + 1].toInt() and 0xFF) shl 16) or
                ((payload[idx + 2].toInt() and 0xFF) shl 8) or
                (payload[idx + 3].toInt() and 0xFF)
            if (size <= 0 || idx + 4 + size > payload.size) return false
            payload[idx] = 0; payload[idx + 1] = 0; payload[idx + 2] = 0; payload[idx + 3] = 1
            idx += 4 + size
        }
        return idx == payload.size
    }

    data class CodecConfig(val sps: List<ByteArray>, val pps: List<ByteArray>) {
        fun toAnnexB(): ByteArray {
            val total = (sps + pps).sumOf { it.size + 4 }
            val out = ByteArray(total)
            var pos = 0
            for (nal in sps + pps) {
                START_CODE.copyInto(out, pos); pos += 4
                nal.copyInto(out, pos); pos += nal.size
            }
            return out
        }
    }

    /** Parses an AVCDecoderConfigurationRecord (type-1 mirror packet). */
    fun parseAvcConfig(payload: ByteArray): CodecConfig? {
        if (payload.size < 7 || payload[0].toInt() != 1) return null
        var pos = 5
        val numSps = payload[pos].toInt() and 0x1F
        pos++
        val sps = ArrayList<ByteArray>(numSps)
        repeat(numSps) {
            if (pos + 2 > payload.size) return null
            val len = ((payload[pos].toInt() and 0xFF) shl 8) or (payload[pos + 1].toInt() and 0xFF)
            pos += 2
            if (len == 0 || pos + len > payload.size) return null
            sps += payload.copyOfRange(pos, pos + len)
            pos += len
        }
        if (pos >= payload.size) return null
        val numPps = payload[pos].toInt() and 0xFF
        pos++
        val pps = ArrayList<ByteArray>(numPps)
        repeat(numPps) {
            if (pos + 2 > payload.size) return null
            val len = ((payload[pos].toInt() and 0xFF) shl 8) or (payload[pos + 1].toInt() and 0xFF)
            pos += 2
            if (len == 0 || pos + len > payload.size) return null
            pps += payload.copyOfRange(pos, pos + len)
            pos += len
        }
        if (sps.isEmpty() || pps.isEmpty()) return null
        return CodecConfig(sps, pps)
    }

    /** True when the Annex-B access unit contains an IDR slice (NAL type 5). */
    fun containsIdr(annexB: ByteArray): Boolean {
        var i = 0
        while (i + 4 < annexB.size) {
            if (annexB[i].toInt() == 0 && annexB[i + 1].toInt() == 0 &&
                annexB[i + 2].toInt() == 0 && annexB[i + 3].toInt() == 1
            ) {
                if ((annexB[i + 4].toInt() and 0x1F) == 5) return true
                i += 4
            } else {
                i++
            }
        }
        return false
    }
}
