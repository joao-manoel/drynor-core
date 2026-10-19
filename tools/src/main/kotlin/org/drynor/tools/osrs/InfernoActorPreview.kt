package org.drynor.tools.osrs

import kotlinx.serialization.json.*
import org.drynor.tools.rs3port.SkeletonCache
import world.gregs.voidps.cache.FileCache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.Config
import world.gregs.voidps.cache.definition.decoder.ModelDecoder
import world.gregs.voidps.cache.definition.encoder.ModelEncoder
import world.gregs.voidps.cache.source.ArchiveMetadata
import world.gregs.voidps.cache.source.IndexNames
import world.gregs.voidps.cache.source.codec.AnimationFrameCodec
import world.gregs.voidps.cache.source.codec.SourceArchive
import world.gregs.voidps.cache.source.codec.SourceCodecs
import world.gregs.voidps.cache.store.ArchiveGroup
import java.nio.file.Files
import java.nio.file.Path

/** Development cache only: actors are available to spawn, with no Inferno gameplay registration. */
object InfernoActorPreview {
    private data class Asset(val index: Int, val archive: Int, val file: Int, val bytes: ByteArray)

    fun build(sourcePath: Path, baselinePath: Path, output: Path) {
        require(!Files.exists(output))
        StaticInfernoPreview.build(sourcePath, baselinePath, output.resolve("arena"))
        InfernoActorAudit.build(sourcePath, baselinePath, output.resolve("actors"))
        val audit = Json.parseToJsonElement(Files.readString(output.resolve("actors/actor-audit.json"))).jsonObject
        require(audit["counts"]!!.jsonObject.values.all { it.jsonObject["blocked"]!!.jsonPrimitive.int == 0 }) { "Actor audit has blockers; no actor cache applied" }
        val plan = Json.parseToJsonElement(Files.readString(output.resolve("arena/dependency-plan.json"))).jsonObject
        val assets = mutableListOf<Asset>()
        fun collect(directory: String, index: Int, bits: Int?) {
            val root = output.resolve("actors/$directory")
            Files.walk(root).use { paths -> paths.filter { Files.isRegularFile(it) }.sorted().forEach { path ->
                val id = path.fileName.toString().removeSuffix(".bin").toInt()
                val archive = when { directory == "frames" -> path.parent.fileName.toString().toInt(); bits != null -> id ushr bits; directory == "bas" -> Config.RENDER_ANIMATIONS; else -> id }
                val file = when { directory == "frames" || directory == "bas" -> id; bits != null -> id and ((1 shl bits) - 1); else -> 0 }
                assets.add(Asset(index, archive, file, Files.readAllBytes(path)))
            } }
        }
        collect("bases", Index.ANIMATION_SKELETONS, null)
        collect("frames", Index.ANIMATION_FRAMES, null)
        collect("sequences", Index.ANIMATIONS, 7)
        collect("spots", Index.SPOTANIMS, 8)
        collect("npcs", Index.NPCS, 7)
        collect("bas", Index.CONFIGS, null)
        val packed = output.resolve("arena/packed-cache")
        FileCache(sourcePath.toString(), writable = false).use { source ->
            FileCache(packed.toString(), writable = false).use { baseline ->
                for (record in plan["proposedIds"]!!.jsonObject["models"]!!.jsonArray) {
                    val entry = record.jsonObject
                    val id = entry["source"]!!.jsonPrimitive.int
                    val target = entry["target"]!!.jsonPrimitive.int
                    if (baseline.data(Index.MODELS, target) != null) continue // Already verified by arena builder.
                    val raw = requireNotNull(source.data(Index.MODELS, id))
                    val model = if (raw.size == 18 && raw.all { it == 0.toByte() }) {
                        ModelDecoder.read(target, requireNotNull(baseline.data(Index.MODELS, 1077))).also {
                            check(it.vertexCount == 0 && it.faceCount == 0 && it.particleEmitters.isNullOrEmpty() && it.particleEffectors.isNullOrEmpty() && it.billboards.isNullOrEmpty())
                        }
                    } else ModelDecoder.read(target, raw)
                    require(model.faceTextures?.all { it == (-1).toShort() } != false) { "Model $id requires material conversion" }
                    val bytes = ModelEncoder.encode(model)
                    check(ModelEncoder.encode(ModelDecoder.read(target, bytes)).contentEquals(bytes))
                    assets.add(Asset(Index.MODELS, target, 0, bytes))
                }
                for (asset in assets) require(asset.file !in baseline.files(asset.index, asset.archive)) { "Existing resource collision: ${asset.index}/${asset.archive}/${asset.file}" }
            }
        }
        val editable = output.resolve("actor-source-package")
        val skeletons = assets.filter { it.index == Index.ANIMATION_SKELETONS }.associate { it.archive to it.bytes }
        AnimationFrameCodec.reset()
        AnimationFrameCodec.cache = { SkeletonCache(skeletons) }
        try {
            for ((address, group) in assets.groupBy { it.index to it.archive }) {
                val (index, archive) = address
                val ordered = group.sortedBy { it.file }
                val ids = ordered.map { it.file }.toIntArray()
                val data = ordered.map { it.bytes }
                val metadata = ArchiveMetadata().also { it.fileIds(ids) }
                val directory = editable.resolve(IndexNames.name(index))
                val codec = SourceCodecs.codec(index)
                // Frame GLBs preserve the native three-section group: header, flags, values.
                val chunks = if (index == Index.ANIMATION_FRAMES && data.size > 1) listOf(
                    IntArray(data.size) { 4 },
                    IntArray(data.size) { data[it][3].toInt() and 255 },
                    IntArray(data.size) { data[it].size - 4 - (data[it][3].toInt() and 255) },
                ) else emptyList()
                val unpacked = codec.unpack(directory, SourceArchive(index, archive, null, ids, ArchiveGroup.join(data, chunks.ifEmpty { null }), data, chunks, metadata))
                check(unpacked.pristine.isEmpty()) { "Source conversion fallback: $address" }
                for (file in unpacked.files) {
                    val path = directory.resolve(file.path)
                    Files.createDirectories(path.parent)
                    Files.write(path, file.bytes)
                }
                val compiled = codec.pack(directory, archive, metadata)
                check(compiled.fileIds.contentEquals(ids))
                val readback = ArchiveGroup.split(compiled.group, compiled.fileIds).files
                check(readback.indices.all { readback[it].contentEquals(data[it]) }) { "Editable actor roundtrip failed: $address" }
                Files.writeString(directory.resolve("$archive.metadata.json"), metadata.write())
            }
        } finally { AnimationFrameCodec.reset() }
        // All conversion gates passed before the experimental packed copy is opened for writes.
        InfernoAssetWriter.write(packed, assets.map { InfernoAssetWriter.Asset(it.index, it.archive, it.file, it.bytes) })
        var unchanged = 0
        FileCache(baselinePath.toString(), writable = false).use { baseline ->
            FileCache(packed.toString(), writable = false).use { cache ->
                for (asset in assets) check(cache.data(asset.index, asset.archive, asset.file).contentEquals(asset.bytes))
                val touched = assets.map { it.index to it.archive }.toSet()
                // Arena modifications were verified independently; only compare actor writes here.
                for (index in baseline.indices()) for (archive in baseline.archiveIds(index)) {
                    if (index == Index.CONFIGS && archive == 4) continue // Two arena overlays.
                    if (index to archive !in touched) {
                        check(baseline.container(index, archive).contentEquals(cache.container(index, archive)))
                        unchanged++
                    } else for (file in baseline.files(index, archive)) check(baseline.data(index, archive, file).contentEquals(cache.data(index, archive, file)))
                }
            }
        }
        val report = buildJsonObject {
            put("status", "experimental-actor-preview"); put("packedCache", packed.toString())
            put("addedActorAssets", assets.size); put("unchangedOriginalArchivesVerified", unchanged)
            put("actorCounts", audit["counts"]!!); put("editableRoundtripVerified", true)
            put("renderingValidated", false); put("gameplayImplemented", false)
            put("limitations", "Arena retains static-preview omissions. NPC Java overrides, effects, materials, sounds and equipment are not fully integrated; no waves, combat or rewards registered.")
        }
        Files.writeString(output.resolve("actor-preview-report.json"), Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), report))
        println("Experimental Inferno actors packed and verified: $output")
    }
}
