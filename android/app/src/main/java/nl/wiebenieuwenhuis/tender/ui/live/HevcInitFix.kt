package nl.wiebenieuwenhuis.tender.ui.live

import java.io.ByteArrayOutputStream

/**
 * go2rtc builds an HEVC stream's init segment from the camera's announced parameter sets, which
 * can be stale: one camera announces 2560×1440 while it sends 2688×1520 with another profile. The
 * decoder, set up from the init segment, then shows broken frames until it catches up. Every
 * keyframe carries the real VPS, SPS and PPS, so this puts those of the first keyframe into the
 * init segment's hvcC (and the size from the SPS into the sample entry) before ExoPlayer reads it.
 * The iOS app does the same in its own parser. Anything unexpected leaves the segment as it was.
 */
object HevcInitFix {
    private class Box(val type: String, val start: Int, val size: Int) {
        val end get() = start + size
    }

    private fun u32(b: ByteArray, i: Int) =
        ((b[i].toInt() and 0xFF) shl 24) or ((b[i + 1].toInt() and 0xFF) shl 16) or ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)

    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)

    private fun put32(b: ByteArray, i: Int, v: Int) {
        b[i] = (v ushr 24).toByte(); b[i + 1] = (v ushr 16).toByte(); b[i + 2] = (v ushr 8).toByte(); b[i + 3] = v.toByte()
    }

    private fun children(b: ByteArray, from: Int, to: Int): List<Box> {
        val out = mutableListOf<Box>()
        var i = from
        while (i + 8 <= to) {
            val size = u32(b, i)
            if (size < 8 || i + size > to) break
            out += Box(String(b, i + 4, 4, Charsets.ISO_8859_1), i, size)
            i += size
        }
        return out
    }

    private fun child(b: ByteArray, parent: Box, type: String, skip: Int = 0) =
        children(b, parent.start + 8 + skip, parent.end).firstOrNull { it.type == type }

    /** True when `data` is an init segment with an HEVC track: worth holding for [patch]. */
    fun isHevcInit(data: ByteArray): Boolean = runCatching { path(data) != null }.getOrDefault(false)

    /** moov … stsd, the HEVC sample entry and its hvcC, outermost first. */
    private fun path(b: ByteArray): List<Box>? {
        val moov = children(b, 0, b.size).firstOrNull { it.type == "moov" } ?: return null
        for (trak in children(b, moov.start + 8, moov.end).filter { it.type == "trak" }) {
            val mdia = child(b, trak, "mdia") ?: continue
            val minf = child(b, mdia, "minf") ?: continue
            val stbl = child(b, minf, "stbl") ?: continue
            val stsd = child(b, stbl, "stsd") ?: continue
            // stsd: version/flags and entry count before its entries.
            val entry = children(b, stsd.start + 16, stsd.end).firstOrNull { it.type == "hvc1" || it.type == "hev1" } ?: continue
            // A visual sample entry has 78 bytes of fields before its child boxes.
            val hvcc = children(b, entry.start + 8 + 78, entry.end).firstOrNull { it.type == "hvcC" } ?: continue
            return listOf(moov, trak, mdia, minf, stbl, stsd, entry, hvcc)
        }
        return null
    }

    /** The init segment with the parameter sets of the first keyframe in `fragment`, or `init` as it was. */
    fun patch(init: ByteArray, fragment: ByteArray): ByteArray = runCatching { patchOrNull(init, fragment) }.getOrNull() ?: init

    private fun patchOrNull(init: ByteArray, fragment: ByteArray): ByteArray? {
        val path = path(init) ?: return null
        val entry = path[6]
        val hvcc = path[7]
        val body = hvcc.start + 8
        if (hvcc.size < 8 + 23) return null
        val lengthSize = (init[body + 21].toInt() and 3) + 1
        val sets = leadingParameterSets(fragment, lengthSize) ?: return null
        val vps = sets[32] ?: return null
        val sps = sets[33] ?: return null
        val pps = sets[34] ?: return null
        val old = existing(init, body, hvcc.end)
        if (old[32]?.contentEquals(vps) == true && old[33]?.contentEquals(sps) == true && old[34]?.contentEquals(pps) == true) return null

        val config = init.copyOfRange(body, body + 22)
        // general profile, compatibility, constraint and level: from the SPS's profile_tier_level.
        val rbsp = unescape(sps)
        if (rbsp.size >= 15) System.arraycopy(rbsp, 3, config, 1, 12)
        val newBody = ByteArrayOutputStream().apply {
            write(config)
            write(3)
            for ((type, nal) in listOf(32 to vps, 33 to sps, 34 to pps)) {
                write(0x80 or type)
                write(0); write(1)
                write(nal.size ushr 8); write(nal.size and 0xFF)
                write(nal)
            }
        }.toByteArray()
        val delta = 8 + newBody.size - hvcc.size
        val out = ByteArrayOutputStream(init.size + delta).apply {
            write(init, 0, hvcc.start)
            val header = ByteArray(8)
            put32(header, 0, 8 + newBody.size)
            System.arraycopy(init, hvcc.start + 4, header, 4, 4)
            write(header)
            write(newBody)
            write(init, hvcc.end, init.size - hvcc.end)
        }.toByteArray()
        // Every box around hvcC grows or shrinks with it.
        for (box in path.dropLast(1)) put32(out, box.start, box.size + delta)
        // The sample entry's width and height, from the SPS.
        spsSize(rbsp)?.let { (w, h) ->
            val at = entry.start + 8 + 24
            out[at] = (w ushr 8).toByte(); out[at + 1] = w.toByte()
            out[at + 2] = (h ushr 8).toByte(); out[at + 3] = h.toByte()
        }
        return out
    }

    /** The parameter sets in an hvcC, by NAL type. */
    private fun existing(b: ByteArray, body: Int, end: Int): Map<Int, ByteArray> {
        val out = HashMap<Int, ByteArray>()
        var p = body + 22
        val arrays = b[p++].toInt() and 0xFF
        repeat(arrays) {
            if (p + 3 > end) return out
            val type = b[p].toInt() and 0x3F
            val count = u16(b, p + 1)
            p += 3
            repeat(count) {
                if (p + 2 > end) return out
                val len = u16(b, p)
                if (p + 2 + len > end) return out
                if (type !in out) out[type] = b.copyOfRange(p + 2, p + 2 + len)
                p += 2 + len
            }
        }
        return out
    }

    /** VPS, SPS and PPS at the start of the first sample of a fragment (moof then mdat), by NAL type. */
    private fun leadingParameterSets(b: ByteArray, lengthSize: Int): Map<Int, ByteArray>? {
        val top = children(b, 0, b.size)
        val moof = top.firstOrNull { it.type == "moof" } ?: return null
        val traf = child(b, moof, "traf") ?: return null
        val tfhd = child(b, traf, "tfhd") ?: return null
        val trun = child(b, traf, "trun") ?: return null
        var base = moof.start
        if (u32(b, tfhd.start + 8) and 0x1 != 0) {
            // An explicit base offset: from the start of this message.
            base = (((u32(b, tfhd.start + 16).toLong() shl 32) or (u32(b, tfhd.start + 20).toLong() and 0xFFFFFFFFL))).toInt()
        }
        val trunFlags = u32(b, trun.start + 8) and 0xFFFFFF
        var p = if (trunFlags and 0x1 != 0) base + u32(b, trun.start + 16) else (top.firstOrNull { it.type == "mdat" }?.start ?: return null) + 8
        val out = HashMap<Int, ByteArray>()
        while (p + lengthSize <= b.size) {
            var len = 0
            for (i in 0 until lengthSize) len = (len shl 8) or (b[p + i].toInt() and 0xFF)
            if (len <= 0 || p + lengthSize + len > b.size) break
            val type = (b[p + lengthSize].toInt() shr 1) and 0x3F
            // Parameter sets (and access unit delimiters, SEI) come first; the picture ends them.
            if (type in 32..34) { if (type !in out) out[type] = b.copyOfRange(p + lengthSize, p + lengthSize + len) }
            else if (type != 35 && type != 39) break
            p += lengthSize + len
        }
        return out.takeIf { it.isNotEmpty() }
    }

    /** A NAL unit without its emulation-prevention bytes (00 00 03). */
    private fun unescape(nal: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(nal.size)
        var zeros = 0
        for (byte in nal) {
            val v = byte.toInt() and 0xFF
            if (zeros >= 2 && v == 3) { zeros = 0; continue }
            out.write(v)
            zeros = if (v == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    private class Bits(val b: ByteArray, var pos: Int) {
        fun bit(): Int { val v = (b[pos ushr 3].toInt() shr (7 - (pos and 7))) and 1; pos++; return v }
        fun bits(n: Int): Int { var v = 0; repeat(n) { v = (v shl 1) or bit() }; return v }
        fun ue(): Int { var zeros = 0; while (bit() == 0) { zeros++; if (zeros > 31) error("bad ue") }; return (1 shl zeros) - 1 + bits(zeros) }
    }

    /** The picture size an SPS (without emulation prevention) describes, after its conformance window. */
    private fun spsSize(rbsp: ByteArray): Pair<Int, Int>? = runCatching {
        val r = Bits(rbsp, 16) // after the two-byte NAL header
        r.bits(4) // sps_video_parameter_set_id
        val maxSubLayersMinus1 = r.bits(3)
        r.bits(1)
        r.bits(96) // general profile_tier_level
        val profilePresent = IntArray(maxSubLayersMinus1)
        val levelPresent = IntArray(maxSubLayersMinus1)
        for (i in 0 until maxSubLayersMinus1) { profilePresent[i] = r.bit(); levelPresent[i] = r.bit() }
        if (maxSubLayersMinus1 > 0) repeat(8 - maxSubLayersMinus1) { r.bits(2) }
        for (i in 0 until maxSubLayersMinus1) {
            if (profilePresent[i] == 1) r.bits(88)
            if (levelPresent[i] == 1) r.bits(8)
        }
        r.ue() // sps_seq_parameter_set_id
        val chroma = r.ue()
        if (chroma == 3) r.bit()
        var width = r.ue()
        var height = r.ue()
        if (r.bit() == 1) {
            val left = r.ue(); val right = r.ue(); val top = r.ue(); val bottom = r.ue()
            val subW = if (chroma == 1 || chroma == 2) 2 else 1
            val subH = if (chroma == 1) 2 else 1
            width -= subW * (left + right)
            height -= subH * (top + bottom)
        }
        if (width in 16..16384 && height in 16..16384) width to height else null
    }.getOrNull()
}
