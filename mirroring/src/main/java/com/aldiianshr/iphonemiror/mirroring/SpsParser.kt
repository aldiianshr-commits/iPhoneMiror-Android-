package com.aldiianshr.iphonemiror.mirroring

/**
 * Minimal H.264 SPS parser (ITU-T H.264 §7.3.2.1.1) — enough to get the coded picture
 * size with cropping applied, plus profile/level for diagnostics.
 */
object SpsParser {

    data class SpsInfo(val profileIdc: Int, val levelIdc: Int, val width: Int, val height: Int)

    private class BitReader(private val data: ByteArray) {
        private var pos = 0
        fun bit(): Int {
            if (pos >= data.size * 8) throw IndexOutOfBoundsException()
            val v = (data[pos ushr 3].toInt() shr (7 - (pos and 7))) and 1
            pos++
            return v
        }
        fun bits(n: Int): Int { var v = 0; repeat(n) { v = (v shl 1) or bit() }; return v }
        fun ue(): Int {
            var zeros = 0
            while (bit() == 0) { zeros++; if (zeros > 31) throw IllegalStateException("bad ue") }
            return ((1 shl zeros) - 1) + bits(zeros)
        }
        fun se(): Int { val k = ue(); return if (k and 1 == 1) (k + 1) / 2 else -(k / 2) }
    }

    /** Removes emulation-prevention bytes (00 00 03 → 00 00). */
    fun unescape(nal: ByteArray): ByteArray {
        val out = ByteArray(nal.size)
        var o = 0
        var zeros = 0
        for (b in nal) {
            if (zeros >= 2 && b.toInt() == 3) { zeros = 0; continue }
            out[o++] = b
            zeros = if (b.toInt() == 0) zeros + 1 else 0
        }
        return out.copyOf(o)
    }

    /** Parses an SPS NAL unit (with its 1-byte NAL header, without start code). */
    fun parse(spsNal: ByteArray): SpsInfo? = runCatching {
        val r = BitReader(unescape(spsNal))
        r.bits(8) // nal header
        val profile = r.bits(8)
        r.bits(8) // constraint flags
        val level = r.bits(8)
        r.ue() // sps id
        var chromaFormat = 1
        var separateColourPlane = 0
        if (profile in HIGH_PROFILES) {
            chromaFormat = r.ue()
            if (chromaFormat == 3) separateColourPlane = r.bit()
            r.ue(); r.ue(); r.bit()
            if (r.bit() == 1) {
                val count = if (chromaFormat != 3) 8 else 12
                for (i in 0 until count) if (r.bit() == 1) skipScalingList(r, if (i < 6) 16 else 64)
            }
        }
        r.ue() // log2_max_frame_num
        val pocType = r.ue()
        if (pocType == 0) r.ue()
        else if (pocType == 1) {
            r.bit(); r.se(); r.se()
            repeat(r.ue()) { r.se() }
        }
        r.ue(); r.bit()
        val widthMbs = r.ue() + 1
        val heightMapUnits = r.ue() + 1
        val frameMbsOnly = r.bit()
        if (frameMbsOnly == 0) r.bit()
        r.bit()
        var cropL = 0; var cropR = 0; var cropT = 0; var cropB = 0
        if (r.bit() == 1) { cropL = r.ue(); cropR = r.ue(); cropT = r.ue(); cropB = r.ue() }
        val chromaArrayType = if (separateColourPlane == 1) 0 else chromaFormat
        val subW = if (chromaArrayType == 1 || chromaArrayType == 2) 2 else 1
        val subH = if (chromaArrayType == 1) 2 else 1
        val cropUnitX = if (chromaArrayType == 0) 1 else subW
        val cropUnitY = (if (chromaArrayType == 0) 1 else subH) * (2 - frameMbsOnly)
        val width = widthMbs * 16 - cropUnitX * (cropL + cropR)
        val height = (2 - frameMbsOnly) * heightMapUnits * 16 - cropUnitY * (cropT + cropB)
        SpsInfo(profile, level, width, height)
    }.getOrNull()

    private fun skipScalingList(r: BitReader, size: Int) {
        var last = 8; var next = 8
        for (j in 0 until size) {
            if (next != 0) next = (last + r.se() + 256) % 256
            last = if (next == 0) last else next
        }
    }

    /** Splits Annex-B data into NAL units (without start codes). */
    fun splitAnnexB(data: ByteArray): List<ByteArray> {
        val starts = ArrayList<Pair<Int, Int>>() // (startOfCode, payloadStart)
        var i = 0
        while (i + 3 <= data.size) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0) {
                if (data[i + 2].toInt() == 1) { starts += i to i + 3; i += 3; continue }
                if (i + 4 <= data.size && data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1) { starts += i to i + 4; i += 4; continue }
            }
            i++
        }
        return starts.mapIndexed { idx, (_, payload) ->
            val end = if (idx + 1 < starts.size) starts[idx + 1].first else data.size
            data.copyOfRange(payload, end)
        }.filter { it.isNotEmpty() }
    }

    private val HIGH_PROFILES = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)
}
