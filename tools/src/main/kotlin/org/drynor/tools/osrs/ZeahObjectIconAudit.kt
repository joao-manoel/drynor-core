package org.drynor.tools.osrs

import kotlinx.serialization.json.Json
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
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest

/** Read-only closure check for the donor's OSRS object icon conversion. */
object ZeahObjectIconAudit {
    private const val MAP_SPRITE_CONFIG_ARCHIVE = 34
    private const val MAP_AREA_CONFIG_ARCHIVE = 36

    fun audit(planPath: Path, donorClientObjectConfig: Path, outputPath: Path) {
        require(!Files.exists(outputPath))
        val planBytes = Files.readAllBytes(planPath)
        val plan = Json.parseToJsonElement(planBytes.decodeToString()).jsonObject
        val clientBytes = Files.readAllBytes(donorClientObjectConfig)
        val clientSource = clientBytes.decodeToString()
        require("objectIconID = 0" in clientSource && "osrsMapIcons" in clientSource)
        val iconMap = Regex("map\\.put\\((\\d+),\\s*(\\d+)\\)")
            .findAll(clientSource).associate { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
        require(iconMap[0] == 555 && iconMap[71] == 625) { "Donor client icon map changed" }
        val sourceRoot = Path.of(plan.getValue("sourceCache").jsonPrimitive.content).toRealPath()
        val targetRoot = Path.of(plan.getValue("targetCache").jsonPrimitive.content).toRealPath()
        val objects = plan.getValue("sourceObjects").jsonArray.map { it.jsonObject }
        FileCache(sourceRoot.toString(), writable = false).use { source ->
            FileCache(targetRoot.toString(), writable = false).use { target ->
                val configs = mutableMapOf<Pair<Int, Int>, IconClosure>()
                fun closure(archive: Int, id: Int): IconClosure = configs.getOrPut(archive to id) {
                    val sourceConfig = source.data(Index.CONFIGS, archive, id)
                    val targetConfig = target.data(Index.CONFIGS, archive, id)
                    val identicalConfig = sourceConfig != null && targetConfig != null && sourceConfig.contentEquals(targetConfig)
                    val spriteFiles = sourceConfig?.let { if (archive == MAP_SPRITE_CONFIG_ARCHIVE) setOf(mapSpriteFile(it)) else mapAreaSprites(it) }
                    val sprites = spriteFiles?.filter { it >= 0 }?.sorted()?.map { spriteFile ->
                        val sourceSprite = source.data(Index.GRAPHICS, spriteFile)
                        val targetSprite = target.data(Index.GRAPHICS, spriteFile)
                        SpriteClosure(spriteFile, sourceSprite != null, targetSprite != null,
                            sourceSprite != null && targetSprite != null && sourceSprite.contentEquals(targetSprite))
                    }.orEmpty()
                    IconClosure(archive, id, sourceConfig != null, targetConfig != null, spriteFiles, identicalConfig, sprites)
                }
                var objectsWithIcons = 0
                var reusableObjects = 0
                val reports = buildJsonArray {
                    for (record in objects) {
                        val id = record.getValue("runtimeId").jsonPrimitive.int
                        val bytes = requireNotNull(source.data(Index.OBJECTS, id ushr 8, id and 255))
                        check(sha256(bytes) == record.getValue("sourceSha256").jsonPrimitive.content)
                        val deps = OsrsObjectDependencies.parse(bytes)
                        if (deps.sprites.isEmpty() && deps.mapIcons.isEmpty()) continue
                        objectsWithIcons++
                        // Opcode 68 feeds objectIconID. The donor special-cases source ID 88 as 0.
                        val mapScenes = deps.sprites.map { raw -> raw to (if (raw == 88) 0 else raw) }
                        // Opcode 82 feeds mapIconID only when the donor client has a mapping.
                        val mapIcons = deps.mapIcons.map { raw -> raw to iconMap[raw] }
                        val reusable = mapScenes.all { closure(MAP_SPRITE_CONFIG_ARCHIVE, it.second).safe } &&
                            mapIcons.mapNotNull { it.second }.all { closure(MAP_AREA_CONFIG_ARCHIVE, it).safe }
                        if (reusable) reusableObjects++
                        add(buildJsonObject {
                            put("sourceRuntimeId", id)
                            put("plannedTargetId", record.getValue("plannedTargetId").jsonPrimitive.int)
                            put("allDisplayedIconsReusable", reusable)
                            put("mapScenes", buildJsonArray {
                                mapScenes.forEach { (raw, effective) -> add(buildJsonObject {
                                    put("rawOsrsId", raw)
                                    put("donorClientMapSpriteId", effective)
                                }) }
                            })
                            put("mapIcons", buildJsonArray {
                                mapIcons.forEach { (raw, mapped) -> add(buildJsonObject {
                                    put("rawOsrsId", raw)
                                    put("donorClientMapSpriteId", mapped ?: -1)
                                    put("drawnByDonor", mapped != null)
                                }) }
                            })
                        })
                    }
                }
                val report = buildJsonObject {
                    put("schemaVersion", 1)
                    put("scopeComplete", false)
                    put("objectPlanSha256", sha256(planBytes))
                    put("donorClientObjectConfigSha256", sha256(clientBytes))
                    put("donorClientObjectConfig", donorClientObjectConfig.toRealPath().toString())
                    put("objectsWithIconOpcodes", objectsWithIcons)
                    put("objectsWithReusableDisplayedIcons", reusableObjects)
                    put("iconConfigsChecked", configs.size)
                    put("iconConfigsReusable", configs.values.count { it.safe })
                    put("iconClosures", buildJsonArray {
                        configs.values.sortedWith(compareBy<IconClosure> { it.archive }.thenBy { it.id }).forEach { icon -> add(buildJsonObject {
                            put("configArchive", icon.archive)
                            put("id", icon.id)
                            put("graphicsSpriteFiles", buildJsonArray { icon.spriteFiles?.sorted()?.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
                            put("sourceConfigPresent", icon.sourceConfigPresent)
                            put("targetConfigPresent", icon.targetConfigPresent)
                            put("sameConfigBytes", icon.sameConfig)
                            put("sameGraphicsBytes", icon.sameSprite)
                            put("safeByteReuse", icon.safe)
                            put("graphics", buildJsonArray { icon.sprites.forEach { sprite -> add(buildJsonObject {
                                put("fileId", sprite.id)
                                put("sourcePresent", sprite.sourcePresent)
                                put("targetPresent", sprite.targetPresent)
                                put("sameBytes", sprite.sameBytes)
                            }) } })
                        }) }
                    })
                    put("objectReports", reports)
                    put("writesCache", false)
                    put("clientVisualValidated", false)
                    put("limitations", "Byte equality and donor client mappings do not verify visual rendering or authorize stripping OSRS icon opcodes.")
                }
                Files.createDirectories(outputPath.toAbsolutePath().parent)
                Files.writeString(outputPath, Json { prettyPrint = true }.encodeToString(report) + "\n", CREATE_NEW, WRITE)
            }
        }
    }

    private fun mapSpriteFile(bytes: ByteArray): Int {
        val reader = BufferReader(bytes)
        var file = 0
        while (true) {
            when (val opcode = reader.readUnsignedByte()) {
                0 -> { check(reader.remaining == 0); return file }
                1 -> file = reader.readBigSmart()
                2 -> reader.readUnsignedMedium()
                3 -> Unit
                4 -> file = -1
                else -> error("Unsupported map sprite config opcode $opcode")
            }
        }
    }

    private fun mapAreaSprites(bytes: ByteArray): Set<Int> {
        val reader = BufferReader(bytes)
        val sprites = sortedSetOf<Int>()
        while (true) {
            when (val opcode = reader.readUnsignedByte()) {
                0 -> { check(reader.remaining == 0); return sprites }
                1, 2, 18 -> sprites += reader.readBigSmart()
                3, 17, in 10..14 -> reader.readString()
                4, 5 -> reader.readUnsignedMedium()
                6, 7, 8, 19 -> reader.readUnsignedByte().also { if (opcode == 19) reader.readUnsignedByte() }
                9, 20 -> reader.skip(12)
                15 -> {
                    val count = reader.readUnsignedByte()
                    reader.skip(count * 4 + 4)
                    val colors = reader.readUnsignedByte()
                    reader.skip(colors * 4 + count)
                }
                16 -> Unit
                21, 22 -> reader.readInt()
                23 -> reader.skip(3)
                24 -> reader.skip(4)
                249 -> repeat(reader.readUnsignedByte()) {
                    val string = reader.readUnsignedByte() == 1
                    reader.readUnsignedMedium()
                    if (string) reader.readString() else reader.readInt()
                }
                else -> error("Unsupported map area config opcode $opcode")
            }
        }
    }

    private data class SpriteClosure(val id: Int, val sourcePresent: Boolean, val targetPresent: Boolean, val sameBytes: Boolean)

    private data class IconClosure(
        val archive: Int,
        val id: Int,
        val sourceConfigPresent: Boolean,
        val targetConfigPresent: Boolean,
        val spriteFiles: Set<Int>?,
        val sameConfig: Boolean,
        val sprites: List<SpriteClosure>
    ) {
        val sameSprite: Boolean get() = spriteFiles != null && sprites.all { it.sameBytes }
        val safe: Boolean get() = sameConfig && sameSprite
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
