package org.drynor.tools.tarnish

import kotlinx.serialization.json.*
import org.drynor.tools.osrs.InfernoAssetWriter
import org.drynor.tools.osrs.OsrsObjectDependencies
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.cache.FileCache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.*
import world.gregs.voidps.cache.definition.decoder.*
import world.gregs.voidps.cache.definition.encoder.*
import java.nio.file.Files
import java.nio.file.Path

/** Full legacy ring animation, with the last-destination selector owned by the world plugin. */
object TarnishFairyRing {
    fun prepare(source: Path, target: Path, output: Path) {
        require(!Files.exists(output)) { "Output exists: $output" }
        val assets = mutableListOf<InfernoAssetWriter.Asset>()
        val sourceHashes = sortedMapOf<String, String>()
        var targetAnimation = -1
        var targetGroup = -1
        var targetBase = -1
        val modelIds = sortedMapOf<Int, Int>()
        lateinit var resultDefinition: ObjectDefinition
        TarnishCache(source).use { donor ->
            val definitions = donor.objects()
            val rawRing = definitions[11761]
            check(TarnishGarden.sha(rawRing) == "93749aa065c82c47ed0e6d0111eb0cd591be496e4fa8bda79f3dbb018f7b60d0")
            val deps = OsrsObjectDependencies.parse(rawRing)
            require(deps.name == "Fairy ring" && deps.models == setOf(32109, 47197) && deps.animations == setOf(9845))
            require(deps.morphs.isEmpty() && deps.textures.isEmpty() && deps.sounds.isEmpty() && deps.sprites.isEmpty() && deps.mapIcons.isEmpty())
            val selector = OsrsObjectDependencies.parse(definitions[29228])
            require(11761 in selector.morphs && selector.models.isEmpty()) { "Ring selector no longer selects the reviewed appearance" }
            val archive = donor.archive(2)
            val sequenceBytes = TarnishAnimations.records(archive.getValue(TarnishCache.hash("seq.dat")), highestIdHeader = true).getValue(9845)
            sourceHashes["object/11761"] = TarnishGarden.sha(rawRing)
            sourceHashes["object/29228"] = TarnishGarden.sha(definitions[29228])
            sourceHashes["sequence/9845"] = TarnishGarden.sha(sequenceBytes)
            val sequence = TarnishAnimations.sequence(sequenceBytes)
            require(sequence.frames.map { it ushr 16 }.toSet() == setOf(3357))
            val sourceFrames = TarnishAnimations.frames(donor, 3357)
            val requiredFiles = sequence.frames.map { it and 65535 }.toSet()
            require(requiredFiles == sourceFrames.keys) { "Unreviewed unreferenced frames in group3357" }
            val sourceBases = TarnishAnimations.records(archive.getValue(TarnishCache.hash("framebases.dat")))
            val baseIds = sourceFrames.values.map { ((it[0].toInt() and 255) shl 8) or (it[1].toInt() and 255) }.toSet()
            require(baseIds == setOf(2112))
            sourceHashes["base/2112"] = TarnishGarden.sha(sourceBases.getValue(2112))
            FileCache(target.toRealPath().toString(), writable = false).use { baseline ->
                check(baseline.data(Index.OBJECTS, 120010 ushr 8, 120010 and 255) == null) { "Ring object120010 occupied" }
                targetGroup = baseline.lastArchiveId(Index.ANIMATION_FRAMES) + 1
                targetBase = baseline.lastArchiveId(Index.ANIMATION_SKELETONS) + 1
                val lastSequenceArchive = baseline.lastArchiveId(Index.ANIMATIONS)
                targetAnimation = (lastSequenceArchive shl 7) + baseline.lastFileId(Index.ANIMATIONS, lastSequenceArchive) + 1
                require(targetGroup in 0..32767 && targetBase in 0..65535 && targetAnimation in 0..65534)
                val base = TarnishAnimations.convertBase(targetBase, sourceBases.getValue(2112))
                val baseBytes = encode { with(AnimationBaseEncoder()) { encode(base) } }
                val baseDecoded = AnimationBaseDefinition(targetBase).also { AnimationBaseDecoder().readLoop(it, BufferReader(baseBytes)) }
                check(encode { with(AnimationBaseEncoder()) { encode(baseDecoded) } }.contentEquals(baseBytes))
                assets += InfernoAssetWriter.Asset(Index.ANIMATION_SKELETONS, targetBase, 0, baseBytes)
                for ((fileId, raw) in sourceFrames.toSortedMap()) {
                    sourceHashes["frame/3357/$fileId"] = TarnishGarden.sha(raw)
                    val frame = TarnishAnimations.convertFrame((targetGroup shl 16) or fileId, raw, base)
                    val bytes = encode { with(AnimationFrameEncoder()) { encode(frame) } }
                    val decoded = AnimationFrameDefinition(frame.id).also { AnimationFrameDecoder().readLoop(it, BufferReader(bytes)) }
                    check(encode { with(AnimationFrameEncoder()) { encode(decoded) } }.contentEquals(bytes))
                    assets += InfernoAssetWriter.Asset(Index.ANIMATION_FRAMES, targetGroup, fileId, bytes)
                }
                val nativeSequence = TarnishAnimations.convertSequence(targetAnimation, sequence, mapOf(3357 to targetGroup))
                val sequenceEncoded = encode { with(AnimationEncoder()) { encode(nativeSequence) } }
                val sequenceDecoded = AnimationDefinition(targetAnimation).also { AnimationDecoder().readLoop(it, BufferReader(sequenceEncoded)) }
                check(sequenceDecoded.frames.contentEquals(nativeSequence.frames) && sequenceDecoded.durations.contentEquals(nativeSequence.durations))
                check(sequenceDecoded.loopOffset == sequence.loopOffset)
                assets += InfernoAssetWriter.Asset(Index.ANIMATIONS, targetAnimation ushr 7, targetAnimation and 127, sequenceEncoded)
                var nextModel = baseline.lastArchiveId(Index.MODELS) + 1
                for (sourceId in deps.models.sorted()) {
                    val raw = donor.model(sourceId)
                    sourceHashes["model/$sourceId"] = TarnishGarden.sha(raw)
                    val bytes = TarnishModels.nativeBytes(raw)
                    val destination = nextModel++
                    val model = ModelDecoder.read(destination, bytes)
                    require((model.faceTextures ?: shortArrayOf()).none { it.toInt() >= 0 })
                    check(ModelEncoder.encode(model).contentEquals(bytes))
                    modelIds[sourceId] = destination
                    assets += InfernoAssetWriter.Asset(Index.MODELS, destination, 0, bytes)
                }
                resultDefinition = TarnishObjectConverter.convert(120010, rawRing, modelIds, mapOf(9845 to targetAnimation), emptyMap())
                require(resultDefinition.options?.get(2) == "Last-destination (AIP)")
                resultDefinition.options!![2] = "Last-destination"
                val objectBytes = encode { with(ObjectEncoder()) { encode(resultDefinition) } }
                val objectDecoded = ObjectDefinition(120010).also { ObjectDecoder().readLoop(it, BufferReader(objectBytes)) }
                check(objectDecoded.modelIds.contentDeepEquals(resultDefinition.modelIds) && objectDecoded.singleAnimation == targetAnimation)
                check(objectDecoded.options.contentEquals(resultDefinition.options) && objectDecoded.sizeX == resultDefinition.sizeX && objectDecoded.sizeY == resultDefinition.sizeY)
                assets += InfernoAssetWriter.Asset(Index.OBJECTS, 120010 ushr 8, 120010 and 255, objectBytes)
                assets.forEach { check(baseline.data(it.index, it.archive, it.file) == null) { "Destination occupied: ${it.index}/${it.archive}/${it.file}" } }
            }
        }
        assets.forEach { asset ->
            val file = output.resolve("assets/${asset.index}/${asset.archive}/${asset.file}.bin")
            Files.createDirectories(file.parent); Files.write(file, asset.bytes)
        }
        val manifest = buildJsonObject {
            put("schemaVersion", 1); put("status", "garden-fairy-ring-native-readback"); put("validated", false)
            put("source", source.toRealPath().toString()); put("target", target.toRealPath().toString())
            put("sourceObjectIds", buildJsonObject { put("11761", 120010); put("29228", 120010) })
            put("fairyRingSourceId", 29228)
            put("sourceModelIds", buildJsonObject { modelIds.forEach { (a,b) -> put(a.toString(), b) } })
            put("sourceAnimationIds", buildJsonObject { put("9845", targetAnimation) })
            put("sourceFrameGroupIds", buildJsonObject { put("3357", targetGroup) })
            put("sourceBaseIds", buildJsonObject { put("2112", targetBase) })
            put("sourceHashes", buildJsonObject { sourceHashes.forEach { (a,b) -> put(a,b) } })
            put("resolvedSelector", buildJsonObject {
                put("sourceSelector", 29228); put("sourceAppearance", 11761)
                put("policy", "World owns ring code selection and last-destination action; donor selector vars/morph variants are not copied")
                put("lastDestinationLabel", "Removed source-specific (AIP) label because the world owns the saved destination")
                put("visualAnimationPreserved", true)
            })
            put("sizeX", resultDefinition.sizeX); put("sizeY", resultDefinition.sizeY)
            put("options", JsonArray(resultDefinition.options.orEmpty().map { it?.let(::JsonPrimitive) ?: JsonNull }))
            put("omissions", JsonArray(emptyList()))
            put("remaining", "Client animation, placement/rotation, collision, code selection and last-destination validation")
            put("assets", JsonArray(assets.map { a -> buildJsonObject {
                put("index", a.index); put("archive", a.archive); put("file", a.file); put("sha256", TarnishGarden.sha(a.bytes))
            } }))
        }
        Files.writeString(output.resolve("manifest.json"), Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), manifest))
        println("Prepared animated Fairy ring: ${assets.size} assets, object120010, sequence$targetAnimation, frameGroup$targetGroup, base$targetBase. No cache changed.")
    }

    private fun encode(block: BufferWriter.() -> Unit): ByteArray = BufferWriter(1 shl 20).apply(block).toArray()
}

