package org.drynor.tools.osrs

import java.io.ByteArrayOutputStream

/** Native 727 main/area records. Raw spans preserve palettes and unrelated regions exactly. */
internal object WorldMapAreaRecords {
    data class Record(val region: Int, val raw: ByteArray)
    data class Area(val header: ByteArray, val records: List<Record>) {
        fun patch(replacements: Map<Int, ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
            out.write(header)
            records.filter { it.region !in replacements }.forEach { out.write(it.raw) }
            replacements.toSortedMap().forEach { (region, bytes) ->
                val parsed = parse(header + bytes)
                require(parsed.records.size == 1 && parsed.records.single().region == region)
                out.write(bytes)
            }
        }.toByteArray()
    }

    fun parse(bytes: ByteArray): Area {
        var p = 0
        fun u8(): Int { require(p < bytes.size) { "Truncated world map at $p" }; return bytes[p++].toInt() and 255 }
        fun skip(size: Int) { require(size in 0..(bytes.size-p)) { "Truncated world map payload at $p" }; p += size }
        val underlayPalette = u8()
        skip(underlayPalette)
        val overlayPalette = u8()
        skip(overlayPalette)
        val header = bytes.copyOfRange(0,p)
        val records = mutableListOf<Record>()
        while (p < bytes.size) {
            val start = p
            val type = u8()
            require(type in 0..1) { "Unknown world map record type $type" }
            val region = (u8() shl 8) or u8()
            if (type == 1) { require(u8() in 0..7); require(u8() in 0..7) }
            repeat(if (type == 0) 4096 else 64) {
                val flags = u8()
                if (flags and 1 == 0) {
                    val palette = (flags ushr 2) and 63
                    if (palette != 62) {
                        if (palette == 63) u8() else require(palette < if (flags and 2 == 0) underlayPalette else overlayPalette) { "Invalid world map palette $palette" }
                        if (flags and 2 != 0) u8()
                    }
                } else repeat(((flags ushr 1) and 3) + 1) {
                    u8()
                    if (flags and 8 != 0) { u8(); u8() }
                    val objects = if (flags and 16 != 0) u8() else 0
                    repeat(objects) {
                        val first = u8()
                        skip(if (first and 128 != 0) 3 else 1)
                        u8()
                    }
                }
            }
            records.add(Record(region,bytes.copyOfRange(start,p)))
        }
        return Area(header,records)
    }

    /** Always use explicit floor/loc form, avoiding changes to the preserved palette header. */
    fun region(region: Int, underlays: IntArray, overlays: IntArray, shapes: IntArray, objects: List<List<Pair<Int, Int>>>): ByteArray {
        require(region in 0..65535 && underlays.size == 4096 && overlays.size == 4096 && shapes.size == 4096 && objects.size == 4096)
        val out = ByteArrayOutputStream()
        out.write(0); out.write(region ushr 8); out.write(region and 255)
        for (i in 0 until 4096) {
            require(underlays[i] in 0..255 && overlays[i] in 0..255 && shapes[i] in 0..255 && objects[i].size <= 255)
            val hasOverlay = overlays[i] != 0
            val hasObjects = objects[i].isNotEmpty()
            out.write(1 or (if (hasOverlay) 8 else 0) or (if (hasObjects) 16 else 0))
            out.write(underlays[i])
            if (hasOverlay) { out.write(overlays[i]); out.write(shapes[i]) }
            if (hasObjects) {
                out.write(objects[i].size)
                for ((id, rotationShape) in objects[i]) {
                    require(id >= 0 && rotationShape in 0..255)
                    if (id < 32767) { out.write(id ushr 8); out.write(id and 255) }
                    else { val packed = id or Int.MIN_VALUE; out.write(packed ushr 24); out.write(packed ushr 16); out.write(packed ushr 8); out.write(packed) }
                    out.write(rotationShape)
                }
            }
        }
        return out.toByteArray()
    }
}
