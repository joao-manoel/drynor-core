package org.drynor.tools.osrs

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.cache.FileCache
import world.gregs.voidps.cache.Index
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Stages only divergent icon graphics and their rewritten configs in a fresh experimental cache. */
object ZeahIconRemapPreview {
    private val configGroups = setOf(34, 36)

    fun build(basePreviewRoot: Path, planPath: Path, iconAuditPath: Path, aliasAuditPath: Path, outputPath: Path) {
        val baseRoot = basePreviewRoot.toRealPath()
        val baseReport = Json.parseToJsonElement(Files.readString(baseRoot.resolve("preview-report.json"))).jsonObject
        require(baseReport.getValue("scopeComplete").jsonPrimitive.content == "false")
        val baseCache = Path.of(baseReport.getValue("packedCache").jsonPrimitive.content).toRealPath()
        require(baseCache == baseRoot.resolve("packed-cache"))
        val planBytes = Files.readAllBytes(planPath)
        val plan = Json.parseToJsonElement(planBytes.decodeToString()).jsonObject
        val iconBytes = Files.readAllBytes(iconAuditPath)
        val iconAudit = Json.parseToJsonElement(iconBytes.decodeToString()).jsonObject
        val aliases = Json.parseToJsonElement(Files.readString(aliasAuditPath)).jsonObject
        val planHash = sha256(planBytes)
        require(baseReport.getValue("objectPlanSha256").jsonPrimitive.content == planHash)
        require(iconAudit.getValue("objectPlanSha256").jsonPrimitive.content == planHash)
        require(aliases.getValue("objectPlanSha256").jsonPrimitive.content == planHash)
        require(aliases.getValue("iconAuditSha256").jsonPrimitive.content == sha256(iconBytes))
        val sourceRoot = Path.of(plan.getValue("sourceCache").jsonPrimitive.content).toRealPath()
        val targetRoot = Path.of(plan.getValue("targetCache").jsonPrimitive.content).toRealPath()
        val output = outputPath.toAbsolutePath().normalize()
        require(!Files.exists(output))
        require(listOf(baseRoot, sourceRoot, targetRoot).none { output.startsWith(it) || it.startsWith(output) })

        val divergentGraphics = aliases.getValue("reports").jsonArray.associate { record ->
            val item = record.jsonObject
            require(item.getValue("identicalTargetGraphicsFiles").jsonArray.isEmpty()) { "Use an existing alias instead of importing a graphic" }
            item.getValue("sourceGraphicsFile").jsonPrimitive.int to item.getValue("sourceSha256").jsonPrimitive.content
        }.toSortedMap()
        require(divergentGraphics.size == aliases.getValue("blockedSpriteFiles").jsonPrimitive.int)
        require(divergentGraphics.isNotEmpty())
        require(divergentGraphics.values.toSet().size == divergentGraphics.size) {
            "Duplicate donor sprite payloads need a shared target ID"
        }
        val blockedConfigs = iconAudit.getValue("iconClosures").jsonArray.map { it.jsonObject }.filter {
            it.getValue("safeByteReuse").jsonPrimitive.content == "false"
        }
        val expectedGraphics = blockedConfigs.flatMap { config -> config.getValue("graphics").jsonArray.map { it.jsonObject } }
            .filter { it.getValue("sameBytes").jsonPrimitive.content == "false" }
            .map { it.getValue("fileId").jsonPrimitive.int }.toSet()
        require(expectedGraphics == divergentGraphics.keys)
        require(blockedConfigs.all { it.getValue("sameConfigBytes").jsonPrimitive.content == "true" }) {
            "Divergent config payload requires a separate conversion"
        }

        FileCache(sourceRoot.toString(), writable = false).use { source ->
            FileCache(targetRoot.toString(), writable = false).use { target ->
                FileCache(baseCache.toString(), writable = false).use { base ->
                    val occupiedGraphics = base.archiveIds(Index.GRAPHICS).toMutableSet()
                    val graphicRemaps = divergentGraphics.keys.associateWith { allocate(occupiedGraphics, 32766) }
                    val occupiedConfigs = configGroups.associateWith { base.files(Index.CONFIGS, it).toMutableSet() }
                    val configRemaps = blockedConfigs.sortedWith(compareBy({ it.getValue("configArchive").jsonPrimitive.int }, { it.getValue("id").jsonPrimitive.int }))
                        .associate { config ->
                            val key = config.getValue("configArchive").jsonPrimitive.int to config.getValue("id").jsonPrimitive.int
                            require(key.first in configGroups)
                            key to allocate(occupiedConfigs.getValue(key.first), 65534)
                        }
                    val graphicsPayloads = graphicRemaps.map { (id, destination) ->
                        val sourceBytes = requireNotNull(source.data(Index.GRAPHICS, id))
                        check(sha256(sourceBytes) == divergentGraphics.getValue(id))
                        check(target.data(Index.GRAPHICS, destination) == null && base.data(Index.GRAPHICS, destination) == null)
                        destination to sourceBytes
                    }
                    val configPayloads = configRemaps.map { (key, destination) ->
                        val (group, id) = key
                        val original = requireNotNull(source.data(Index.CONFIGS, group, id))
                        check(original.contentEquals(target.data(Index.CONFIGS, group, id)))
                        check(original.contentEquals(base.data(Index.CONFIGS, group, id)))
                        check(base.data(Index.CONFIGS, group, destination) == null)
                        val (rewritten, referenced) = remapConfig(group, original, graphicRemaps)
                        check(referenced.any { it in graphicRemaps }) { "Config $group/$id has no divergent graphic" }
                        check(!rewritten.contentEquals(original))
                        check(remapConfig(group, rewritten, emptyMap()).second == referenced.map { graphicRemaps[it] ?: it }.toSet())
                        Triple(group, destination, rewritten)
                    }

                    Files.createDirectories(output)
                    val packed = output.resolve("packed-cache")
                    Files.createDirectory(packed)
                    Files.list(baseCache).use { paths ->
                        paths.filter { it.fileName.toString().startsWith("main_file_cache.") }
                            .forEach { Files.copy(it, packed.resolve(it.fileName)) }
                    }
                    FileCache(packed.toString(), writable = true).use { staged ->
                        graphicsPayloads.forEach { (id, bytes) -> staged.write(Index.GRAPHICS, id, 0, bytes) }
                        configPayloads.forEach { (group, id, bytes) -> staged.write(Index.CONFIGS, group, id, bytes) }
                    }
                    FileCache(packed.toString(), writable = false).use { result ->
                        graphicsPayloads.forEach { (id, bytes) -> check(result.data(Index.GRAPHICS, id).contentEquals(bytes)) }
                        configPayloads.forEach { (group, id, bytes) -> check(result.data(Index.CONFIGS, group, id).contentEquals(bytes)) }
                        for (id in base.archiveIds(Index.GRAPHICS)) {
                            check(base.container(Index.GRAPHICS, id).contentEquals(result.container(Index.GRAPHICS, id))) {
                                "Existing graphic archive changed: $id"
                            }
                        }
                        for (group in configGroups) for (id in base.files(Index.CONFIGS, group)) {
                            check(base.data(Index.CONFIGS, group, id).contentEquals(result.data(Index.CONFIGS, group, id))) {
                                "Existing icon config changed: $group/$id"
                            }
                        }
                        for (id in base.archiveIds(Index.MAPS)) {
                            check(base.container(Index.MAPS, id).contentEquals(result.container(Index.MAPS, id))) {
                                "Map archive changed: $id"
                            }
                        }
                    }
                    val report = buildJsonObject {
                        put("status", "experimental-icon-remap-only")
                        put("scopeComplete", false)
                        put("packedCache", packed.toString())
                        put("objectPlanSha256", planHash)
                        put("iconAuditSha256", sha256(iconBytes))
                        put("aliasAuditSha256", sha256(Files.readAllBytes(aliasAuditPath)))
                        put("graphicRemaps", buildJsonArray { graphicRemaps.toSortedMap().forEach { (sourceId, targetId) -> add(buildJsonObject {
                            put("sourceGraphicsFile", sourceId)
                            put("targetGraphicsFile", targetId)
                            put("sourceSha256", divergentGraphics.getValue(sourceId))
                        }) } })
                        put("mapsceneConfigRemaps", buildJsonArray { configRemaps.filterKeys { it.first == 34 }.forEach { (key, id) -> add(buildJsonObject {
                            put("donorClientMapSpriteId", key.second)
                            put("targetMapSpriteId", id)
                        }) } })
                        put("mapAreaConfigRemaps", buildJsonArray { configRemaps.filterKeys { it.first == 36 }.forEach { (key, id) -> add(buildJsonObject {
                            put("donorClientMapAreaId", key.second)
                            put("targetMapAreaId", id)
                        }) } })
                        put("existingGraphicsPreserved", true)
                        put("existingConfigsPreserved", true)
                        put("existingMapsPreserved", true)
                        put("writesRuntimeCache", false)
                        put("clientVisualValidated", false)
                        put("limitations", "Icon assets only. The object converter must consume this mapping explicitly, then the client must verify map and minimap visuals.")
                    }
                    Files.writeString(output.resolve("preview-report.json"), Json { prettyPrint = true }.encodeToString(report) + "\n")
                }
            }
        }
    }

    private fun allocate(occupied: MutableSet<Int>, max: Int): Int {
        val id = (1..max).firstOrNull { it !in occupied } ?: error("No free icon ID below $max")
        occupied += id
        return id
    }

    /** Changes only big-smart sprite references; all other bytes retain their original offsets. */
    private fun remapConfig(group: Int, bytes: ByteArray, mapping: Map<Int, Int>): Pair<ByteArray, Set<Int>> {
        val result = bytes.copyOf()
        val reader = BufferReader(bytes)
        val references = sortedSetOf<Int>()
        fun sprite() {
            val position = reader.buffer.position()
            val old = reader.readBigSmart()
            references += old
            val replacement = mapping[old] ?: return
            val length = reader.buffer.position() - position
            if (length == 2) {
                require(replacement <= 32766)
                result[position] = (replacement ushr 8).toByte()
                result[position + 1] = replacement.toByte()
            } else {
                val encoded = replacement or Int.MIN_VALUE
                repeat(4) { result[position + it] = (encoded ushr (24 - 8 * it)).toByte() }
            }
        }
        while (true) {
            when (val opcode = reader.readUnsignedByte()) {
                0 -> { check(reader.remaining == 0); return result to references }
                1 -> sprite()
                2 -> if (group == 34) reader.readUnsignedMedium() else sprite()
                18 -> { require(group == 36); sprite() }
                3, 17, in 10..14 -> if (group == 34) {
                    check(opcode == 3)
                } else reader.readString()
                4 -> if (group == 34) Unit else reader.readUnsignedMedium()
                5 -> { require(group == 36); reader.readUnsignedMedium() }
                6, 7, 8 -> { require(group == 36); reader.readUnsignedByte() }
                9, 20 -> { require(group == 36); reader.skip(12) }
                15 -> {
                    require(group == 36)
                    val count = reader.readUnsignedByte()
                    reader.skip(count * 4 + 4)
                    val colors = reader.readUnsignedByte()
                    reader.skip(colors * 4 + count)
                }
                16 -> require(group == 36)
                19 -> { require(group == 36); reader.readUnsignedShort() }
                21, 22 -> { require(group == 36); reader.readInt() }
                23 -> { require(group == 36); reader.skip(3) }
                24 -> { require(group == 36); reader.skip(4) }
                249 -> {
                    require(group == 36)
                    repeat(reader.readUnsignedByte()) {
                        val string = reader.readUnsignedByte() == 1
                        reader.readUnsignedMedium()
                        if (string) reader.readString() else reader.readInt()
                    }
                }
                else -> error("Unsupported icon config opcode $group/$opcode")
            }
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
