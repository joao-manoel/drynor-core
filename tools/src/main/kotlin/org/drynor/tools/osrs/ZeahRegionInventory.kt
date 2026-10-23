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
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest

/** Read-only first pass. Bounding coordinates are candidates, not a claim of complete area coverage. */
object ZeahRegionInventory {
    fun inspect(sourcePath: Path, targetPath: Path, reportPath: Path, minX: Int, maxX: Int, minY: Int, maxY: Int) {
        require(minX in 0..255 && maxX in minX..255 && minY in 0..255 && maxY in minY..255)
        require((maxX - minX + 1) * (maxY - minY + 1) <= 1024) { "Inspect a bounded set of regions." }
        require(sourcePath.toAbsolutePath().normalize() != targetPath.toAbsolutePath().normalize())
        require(!Files.exists(reportPath)) { "Report already exists: $reportPath" }
        FileCache(sourcePath.toString(), writable = false).use { source ->
            FileCache(targetPath.toString(), writable = false).use { target ->
                val report = buildJsonObject {
                    put("schemaVersion", 1)
                    put("purpose", "read-only Zeah candidate region inventory")
                    put("sourceCache", sourcePath.toString())
                    put("targetCache", targetPath.toString())
                    put("dependencyClosureComplete", false)
                    put("regions", buildJsonArray {
                        for (x in minX..maxX) for (y in minY..maxY) {
                            add(buildJsonObject {
                                put("regionId", (x shl 8) or y)
                                put("regionX", x)
                                put("regionY", y)
                                for (prefix in listOf("m", "l")) {
                                    val name = "$prefix${x}_$y"
                                    put(if (prefix == "m") "terrain" else "landscape", buildJsonObject {
                                        put("name", name)
                                        val sourceArchive = source.archiveId(Index.MAPS, name)
                                        val targetArchive = target.archiveId(Index.MAPS, name)
                                        put("sourceArchive", sourceArchive)
                                        put("targetArchive", targetArchive)
                                        if (sourceArchive < 0) {
                                            put("sourceStatus", "missing")
                                        } else {
                                            try {
                                                val bytes = requireNotNull(source.data(Index.MAPS, sourceArchive))
                                                put("sourceStatus", "readable")
                                                put("sourceBytes", bytes.size)
                                                put("sourceSha256", hash(bytes))
                                                if (prefix == "l") {
                                                    val objects = sortedSetOf<Int>()
                                                    var placements = 0
                                                    val decoder = object : MapObjectDecoder() {
                                                        override fun add(objectId: Int, localX: Int, localY: Int, level: Int, shape: Int, rotation: Int, regionTileX: Int, regionTileY: Int) {
                                                            require(level in 0..3 && shape in 0..22)
                                                            objects.add(objectId)
                                                            placements++
                                                        }
                                                    }
                                                    val end = decoder.decode(bytes, ByteArray(16384), x, y)
                                                    require(bytes.drop(end).all { it == 0.toByte() })
                                                    put("placements", placements)
                                                    put("rawObjectIds", buildJsonArray { objects.forEach { add(JsonPrimitive(it)) } })
                                                }
                                            } catch (error: Exception) {
                                                put("sourceStatus", "unreadable-or-incompatible")
                                                put("sourceError", "${error.javaClass.simpleName}: ${error.message}")
                                            }
                                        }
                                        if (targetArchive >= 0) {
                                            try {
                                                val bytes = requireNotNull(target.data(Index.MAPS, targetArchive))
                                                put("targetStatus", "readable")
                                                put("targetBytes", bytes.size)
                                                put("targetSha256", hash(bytes))
                                            } catch (error: Exception) {
                                                put("targetStatus", "unreadable-or-incompatible")
                                                put("targetError", "${error.javaClass.simpleName}: ${error.message}")
                                            }
                                        } else put("targetStatus", "missing")
                                    })
                                }
                            })
                        }
                    })
                }
                Files.createDirectories(reportPath.toAbsolutePath().parent)
                Files.writeString(reportPath, Json { prettyPrint = true }.encodeToString(report) + "\n", CREATE_NEW, WRITE)
            }
        }
    }

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
