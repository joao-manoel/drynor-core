package org.drynor.tools.osrs

import kotlinx.serialization.json.*
import world.gregs.voidps.cache.FileCache
import world.gregs.voidps.cache.Index
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Read-only inventory of assets referenced by Gielenor's Lizardman Shaman combat script. */
object ZeahShamanCombatAudit {
    private val animationIds = listOf(27192, 27193, 27194, 27196)
    private val spotIds = listOf(6290, 6291, 6293, 6294, 6295)

    fun audit(sourcePath: Path, targetPath: Path, outputPath: Path) {
        require(!Files.exists(outputPath))
        val sourceRoot = sourcePath.toRealPath()
        val targetRoot = targetPath.toRealPath()
        require(sourceRoot != targetRoot)
        FileCache(sourceRoot.toString(), writable = false).use { source ->
            FileCache(targetRoot.toString(), writable = false).use { target ->
                val spotSequenceIds = spotIds.mapNotNull { id ->
                    source.data(Index.SPOTANIMS, id ushr 8, id and 255)?.let { raw ->
                        runCatching { OsrsSpotConverter.parse(id, raw).animationId }.getOrNull()
                    }
                }.filter { it >= 0 }
                val animations = (animationIds + spotSequenceIds).distinct().sorted().map { id ->
                    val donor = source.data(Index.ANIMATIONS, id ushr 7, id and 127)
                    val native = target.data(Index.ANIMATIONS, id ushr 7, id and 127)
                    buildJsonObject {
                        put("id", id); put("donorPresent", donor != null); put("targetOccupied", native != null)
                        donor?.let { put("donorSha256", hash(it)) }
                        native?.let { put("targetSha256", hash(it)) }
                        if (donor != null) {
                            val parsed = runCatching { OsrsSequenceDependencies.parse(donor) }
                            put("donorParseStatus", if (parsed.isSuccess) "parsed" else "blocked")
                            parsed.exceptionOrNull()?.let { put("donorParseIssue", it.message ?: it.javaClass.simpleName) }
                            parsed.getOrNull()?.let {
                                put("frameCount", it.frameIds.size)
                                put("sounds", JsonArray(it.sounds.sorted().map(::JsonPrimitive)))
                                put("equipment", JsonArray(it.equipment.sorted().map(::JsonPrimitive)))
                            }
                        }
                    }
                }
                val spots = spotIds.map { id ->
                    val donor = source.data(Index.SPOTANIMS, id ushr 8, id and 255)
                    val native = target.data(Index.SPOTANIMS, id ushr 8, id and 255)
                    buildJsonObject {
                        put("id", id); put("donorPresent", donor != null); put("targetOccupied", native != null)
                        donor?.let { put("donorSha256", hash(it)) }
                        native?.let { put("targetSha256", hash(it)) }
                        if (donor != null) {
                            val parsed = runCatching { OsrsSpotConverter.parse(id, donor) }
                            put("donorParseStatus", if (parsed.isSuccess) "parsed" else "blocked")
                            parsed.exceptionOrNull()?.let { put("donorParseIssue", it.message ?: it.javaClass.simpleName) }
                            parsed.getOrNull()?.let {
                                put("modelId", it.modelId)
                                put("animationId", it.animationId)
                                put("donorModelPresent", source.data(Index.MODELS, it.modelId) != null)
                            }
                        }
                    }
                }
                val animationPlan = runCatching {
                    ZulrahAnimationAssets.plan(source, target, (animationIds + spotSequenceIds).toSet())
                }
                val result = buildJsonObject {
                    put("status", "read-only-shaman-combat-asset-audit")
                    put("sourceCache", sourceRoot.toString()); put("targetCache", targetRoot.toString())
                    put("animations", JsonArray(animations)); put("spots", JsonArray(spots))
                    put("animationConversionStatus", if (animationPlan.isSuccess) "planned" else "blocked")
                    animationPlan.exceptionOrNull()?.let { put("animationConversionIssue", it.message ?: it.javaClass.simpleName) }
                    animationPlan.getOrNull()?.let { put("animationAssetCount", it.assets.size) }
                    put("limitations", "Presence, parsing and occupied IDs do not establish compatible native conversion, animation frames, spot models, client playback or gameplay.")
                }
                Files.createDirectories(outputPath.toAbsolutePath().parent)
                Files.writeString(outputPath, Json { prettyPrint = true }.encodeToString(result) + "\n")
            }
        }
    }

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
