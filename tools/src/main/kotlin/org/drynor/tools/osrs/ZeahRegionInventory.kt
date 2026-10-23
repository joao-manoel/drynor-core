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
import java.io.DataInputStream
import java.security.MessageDigest

/** Read-only first pass. Bounding coordinates are candidates, not a claim of complete area coverage. */
object ZeahRegionInventory {
    fun inspect(sourcePath: Path, settingsPath: Path, sourceKeysPath: Path, targetPath: Path, reportPath: Path, minX: Int, maxX: Int, minY: Int, maxY: Int) {
        require(minX in 0..255 && maxX in minX..255 && minY in 0..255 && maxY in minY..255)
        require((maxX - minX + 1) * (maxY - minY + 1) <= 1024) { "Inspect a bounded set of regions." }
        require(sourcePath.toAbsolutePath().normalize() != targetPath.toAbsolutePath().normalize())
        require(!Files.exists(reportPath)) { "Report already exists: $reportPath" }
        val sourceKeys = loadPackedKeys(sourceKeysPath)
        val osrsRegionIds = readDonorOsrsMapIds(settingsPath)
        FileCache(sourcePath.toString(), writable = false).use { source ->
            FileCache(targetPath.toString(), writable = false).use { target ->
                val osrsObjectIds = sortedSetOf<Int>()
                val report = buildJsonObject {
                    put("schemaVersion", 1)
                    put("purpose", "read-only Zeah candidate region inventory")
                    put("sourceCache", sourcePath.toString())
                    put("sourceSettingsSha256", hash(Files.readAllBytes(settingsPath)))
                    put("sourceOsrsRegionCount", osrsRegionIds.size)
                    put("sourceKeysFile", sourceKeysPath.toString())
                    put("sourceKeysSha256", hash(Files.readAllBytes(sourceKeysPath)))
                    put("sourceKeyCount", sourceKeys.size)
                    put("targetCache", targetPath.toString())
                    put("dependencyClosureComplete", false)
                    put("regions", buildJsonArray {
                        for (x in minX..maxX) for (y in minY..maxY) {
                            add(buildJsonObject {
                                put("regionId", (x shl 8) or y)
                                put("regionX", x)
                                put("regionY", y)
                                put("donorOsrsMapId", ((x shl 8) or y) in osrsRegionIds)
                                val regionKey = sourceKeys[(x shl 8) or y]?.takeUnless { it.all { word -> word == 0 } }
                                put("sourceXteaPresent", regionKey != null)
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
                                                val bytes = requireNotNull(source.data(Index.MAPS, sourceArchive, 0, if (prefix == "l") regionKey else null))
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
                                                    if (((x shl 8) or y) in osrsRegionIds) {
                                                        osrsObjectIds.addAll(objects.map { it + 100_000 })
                                                    }
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
                    put("osrsObjectDefinitions", buildJsonArray {
                        for (id in osrsObjectIds) {
                            add(buildJsonObject {
                                put("runtimeId", id)
                                put("rawLandscapeId", id - 100_000)
                                val archive = id ushr 8
                                val file = id and 255
                                put("sourceArchive", archive)
                                put("sourceFile", file)
                                try {
                                    val bytes = source.data(Index.OBJECTS, archive, file)
                                    put("sourceStatus", if (bytes == null) "missing" else "readable")
                                    if (bytes != null) put("sourceSha256", hash(bytes))
                                } catch (error: Exception) {
                                    put("sourceStatus", "unreadable-or-incompatible")
                                    put("sourceError", "${error.javaClass.simpleName}: ${error.message}")
                                }
                                val targetOccupied = target.files(Index.OBJECTS, archive).contains(file)
                                put("targetIdOccupied", targetOccupied)
                                if (targetOccupied) {
                                    try {
                                        val bytes = requireNotNull(target.data(Index.OBJECTS, archive, file))
                                        put("targetSha256", hash(bytes))
                                    } catch (error: Exception) {
                                        put("targetError", "${error.javaClass.simpleName}: ${error.message}")
                                    }
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

    private fun loadPackedKeys(path: Path): Map<Int, IntArray> {
        require(Files.isRegularFile(path)) { "Gielenor packed map keys missing: $path" }
        require(Files.size(path) % 18L == 0L) { "Incomplete Gielenor packed map key file: $path" }
        val keys = mutableMapOf<Int, IntArray>()
        DataInputStream(Files.newInputStream(path)).use { input ->
            repeat((Files.size(path) / 18L).toInt()) {
                val regionId = input.readUnsignedShort()
                check(keys.put(regionId, IntArray(4) { input.readInt() }) == null) { "Duplicate XTEA region $regionId" }
            }
        }
        return keys
    }

    private fun readDonorOsrsMapIds(path: Path): Set<Int> {
        val source = Files.readString(path)
        val body = requireNotNull(Regex("OSRS_MAP_IDS\\s*=\\s*\\{([\\s\\S]*?)\\};").find(source)) {
            "Gielenor OSRS_MAP_IDS declaration not found: $path"
        }.groupValues[1]
        val withoutComments = body.replace(Regex("//[^\\r\\n]*|/\\*[\\s\\S]*?\\*/"), "")
        require(withoutComments.matches(Regex("[\\d,\\s]*"))) { "Unexpected Gielenor OSRS_MAP_IDS grammar" }
        return withoutComments.split(',').mapNotNull { it.trim().takeIf(String::isNotEmpty)?.toInt() }.toSet()
    }
}
