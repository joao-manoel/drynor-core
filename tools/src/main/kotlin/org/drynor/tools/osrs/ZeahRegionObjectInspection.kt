package org.drynor.tools.osrs

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import world.gregs.voidps.cache.FileCache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.decoder.MapObjectDecoder
import java.nio.file.Files
import java.nio.file.Path

/** Read-only placement inspection; the result is evidence, not a route definition. */
object ZeahRegionObjectInspection {
    fun inspect(cachePath: Path, regionId: Int, reportPath: Path) {
        require(regionId in 0..65535)
        val cache = cachePath.toRealPath()
        val output = reportPath.toAbsolutePath().normalize()
        require(!Files.exists(output))
        require(!output.startsWith(cache))
        val regionX = regionId ushr 8
        val regionY = regionId and 255
        val placements = mutableListOf<List<Int>>()
        FileCache(cache.toString(), writable = false).use { source ->
            val landscapeName = "l${regionX}_${regionY}"
            val landscape = requireNotNull(source.data(Index.MAPS, landscapeName)) { "Missing $landscapeName" }
            val decoder = object : MapObjectDecoder() {
                override fun add(objectId: Int, localX: Int, localY: Int, level: Int, shape: Int, rotation: Int, regionTileX: Int, regionTileY: Int) {
                    placements += listOf(objectId, (regionX shl 6) + localX, (regionY shl 6) + localY, level, shape, rotation)
                }
            }
            val end = decoder.decode(landscape, ByteArray(16384), regionX, regionY)
            check(landscape.drop(end).all { it == 0.toByte() })
        }
        Files.createDirectories(output.parent)
        val json = buildJsonObject {
            put("regionId", regionId)
            put("cache", cache.toString())
            put("columns", "objectId,x,y,plane,shape,rotation")
            put("placements", buildJsonArray {
                placements.sortedWith(compareBy<List<Int>>({ it[0] }, { it[1] }, { it[2] }, { it[3] })).forEach { row ->
                    add(buildJsonArray { row.forEach { add(JsonPrimitive(it)) } })
                }
            })
        }
        Files.writeString(output, Json { prettyPrint = true }.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), json))
        println("Inspected ${placements.size} placements in region $regionId: $output")
    }
}
