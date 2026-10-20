package org.drynor.tools.osrs

import kotlinx.serialization.json.*
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.AnimationBaseDefinition
import world.gregs.voidps.cache.definition.data.AnimationDefinition
import world.gregs.voidps.cache.definition.data.AnimationFrameDefinition
import world.gregs.voidps.cache.definition.decoder.AnimationBaseDecoder
import world.gregs.voidps.cache.definition.decoder.AnimationDecoder
import world.gregs.voidps.cache.definition.decoder.AnimationFrameDecoder
import world.gregs.voidps.cache.definition.encoder.AnimationBaseEncoder
import world.gregs.voidps.cache.definition.encoder.AnimationEncoder
import world.gregs.voidps.cache.definition.encoder.AnimationFrameEncoder
import java.security.MessageDigest

/** Builds a closed native visual asset set without writing either input cache. */
internal object ZulrahAnimationAssets {
    data class Result(
        val sequenceMap: Map<Int, Int>,
        val assets: List<InfernoAssetWriter.Asset>,
        val report: JsonObject,
    )
    private data class Frame(val archive: Int, val file: Int, val sourceBase: Int, val bytes: ByteArray)

    fun plan(source: Cache, target: Cache, sequenceIds: Set<Int>, equipmentClearedByDonor: Set<Int> = emptySet(), sceneOnlySequences: Set<Int> = emptySet()): Result {
        require(sequenceIds.isNotEmpty() && sequenceIds.all { it >= 20000 })
        val sequences = sequenceIds.sorted().associateWith { id ->
            requireNotNull(source.data(Index.ANIMATIONS, id ushr 7, id and 127)) { "Missing source sequence $id" }
        }
        val dependencies = sequences.mapValues { (_, bytes) -> OsrsSequenceDependencies.parse(bytes) }
        require(equipmentClearedByDonor.all { it == 25061 && it in sequenceIds }) { "Only the audited CustomAnims blowpipe override may clear equipment" }
        require(sceneOnlySequences.all { it in sequenceIds })
        require(dependencies.all { (id,deps) -> deps.sounds.isEmpty() && (deps.equipment.isEmpty() || id in equipmentClearedByDonor || id in sceneOnlySequences) }) {
            "Actor animations require explicit sound/equipment import: $dependencies"
        }
        val frames = sortedMapOf<Long, Frame>()
        for ((sequenceId, deps) in dependencies) for (packed in deps.frameIds) {
            val archive = (packed ushr 16) + 5000
            val file = packed and 65535
            val key = (archive.toLong() shl 16) or file.toLong()
            val bytes = requireNotNull(source.data(Index.ANIMATION_FRAMES, archive, file)) { "Missing source frame $archive/$file" }
            val info = OsrsFrameDependencies.parseFrame(bytes)
            val base = GielenorVisualProfile.sourceBaseId(info.baseId, GielenorVisualProfile.forceOsrsSkeleton(sequenceId))
            val frame = Frame(archive, file, base, bytes)
            val previous = frames.putIfAbsent(key, frame)
            require(previous == null || previous.sourceBase == base) { "Frame $archive/$file resolves to conflicting source skeletons" }
        }
        require(frames.isNotEmpty()) { "No actor frames resolved" }
        val sequenceMap = DeterministicIds.plan(sequenceIds, target.archiveIds(Index.ANIMATIONS).flatMap { a ->
            target.files(Index.ANIMATIONS, a).map { (a shl 7) or it }
        }.toSet())
        val frameMap = DeterministicIds.plan(frames.values.map { it.archive }.toSet(), target.archiveIds(Index.ANIMATION_FRAMES).toSet())
        val baseMap = DeterministicIds.plan(frames.values.map { it.sourceBase }.toSet(), target.archiveIds(Index.ANIMATION_SKELETONS).toSet())
        require(sequenceMap.values.all { it in 0..65534 } && frameMap.values.all { it in 0..65534 } && baseMap.values.all { it in 0..65535 }) {
            "Animation allocation exceeds native consumer field widths"
        }
        val assets = mutableListOf<InfernoAssetWriter.Asset>()
        val bases = baseMap.toSortedMap().mapValues { (origin, targetId) ->
            val sourceBytes=requireNotNull(source.data(Index.ANIMATION_SKELETONS, origin))
            // Class298_Sub37_Sub4.skipOSRSSkin keeps raw skeleton 0 on the native base grammar,
            // while AnimationFrame still receives osrs=true and applies OSRS frame transforms.
            val base = decodeSourceBase(origin,targetId,sourceBytes)
            val bytes = encode { with(AnimationBaseEncoder()) { it.encode(base) } }
            val decoded = AnimationBaseDefinition(targetId)
            AnimationBaseDecoder().readLoop(decoded, BufferReader(bytes))
            check(decoded == base)
            check(encode { with(AnimationBaseEncoder()) { it.encode(decoded) } }.contentEquals(bytes))
            assets.add(InfernoAssetWriter.Asset(Index.ANIMATION_SKELETONS, targetId, 0, bytes))
            base
        }
        for ((_, frame) in frames) {
            val archive = frameMap.getValue(frame.archive)
            val converted = OsrsAnimationConverter.convertFrame((archive shl 16) or frame.file, frame.bytes, bases.getValue(frame.sourceBase))
            val bytes = encode { with(AnimationFrameEncoder()) { it.encode(converted) } }
            val decoded = AnimationFrameDefinition(converted.id)
            AnimationFrameDecoder().readLoop(decoded, BufferReader(bytes))
            check(decoded.baseId == converted.baseId && decoded.flags.contentEquals(converted.flags) && decoded.values.contentEquals(converted.values))
            check(encode { with(AnimationFrameEncoder()) { it.encode(decoded) } }.contentEquals(bytes))
            assets.add(InfernoAssetWriter.Asset(Index.ANIMATION_FRAMES, archive, frame.file, bytes))
        }
        for ((origin, targetId) in sequenceMap) {
            // Unlike the older generic previews, this actor set has no foreign sounds/equipment.
            // Keep the strict converter mode so a new dependency fails instead of being dropped.
            // CustomAnims.modify case25061 clears both hand replacements after source decoding.
            val converted = OsrsAnimationConverter.convertSequence(targetId, sequences.getValue(origin), frameMap,origin in equipmentClearedByDonor || origin in sceneOnlySequences)
            val bytes = encode { with(AnimationEncoder()) { it.encode(converted) } }
            val decoded = AnimationDefinition(targetId)
            AnimationDecoder().readLoop(decoded, BufferReader(bytes))
            check(decoded.frames.contentEquals(converted.frames) && decoded.durations.contentEquals(converted.durations))
            check(encode { with(AnimationEncoder()) { it.encode(decoded) } }.contentEquals(bytes))
            assets.add(InfernoAssetWriter.Asset(Index.ANIMATIONS, targetId ushr 7, targetId and 127, bytes))
        }
        check(assets.all { it.file !in target.files(it.index, it.archive) }) { "Actor animation allocation overwrites an existing file" }
        val report = buildJsonObject {
            put("sequences", mappings(sequenceMap)); put("frameSets", mappings(frameMap)); put("bases", mappings(baseMap))
            put("sequenceCount", sequenceMap.size); put("frameCount", frames.size); put("baseCount", bases.size)
            put("nativeBinaryReadbackVerified", true); put("soundsOrEquipmentDiscarded", sceneOnlySequences.any { dependencies.getValue(it).equipment.isNotEmpty() })
            put("equipmentClearedByDonorCustomAnims",JsonArray(equipmentClearedByDonor.sorted().map(::JsonPrimitive)))
            put("sceneOnlyEquipmentIgnored",JsonArray(sceneOnlySequences.filter { dependencies.getValue(it).equipment.isNotEmpty() }.sorted().map(::JsonPrimitive)))
            put("nativeSourceBases",JsonArray(baseMap.keys.filter { it == 0 }.map(::JsonPrimitive)))
            put("sourceFrames", JsonArray(frames.values.map { frame -> buildJsonObject {
                put("archive", frame.archive); put("file", frame.file); put("base", frame.sourceBase); put("sha256", hash(frame.bytes))
            } }))
            put("gielenorClientRevision", GielenorVisualProfile.CLIENT_COMMIT)
            put("translationMultiplier", 4); put("rotationMultiplierBeforeNativeLoad", 16)
        }
        return Result(sequenceMap, assets, report)
    }

    private fun mappings(map: Map<Int, Int>) = JsonArray(map.toSortedMap().map { (source, target) -> buildJsonObject { put("source", source); put("target", target) } })
    internal fun decodeSourceBase(sourceId: Int,targetId: Int,bytes: ByteArray): AnimationBaseDefinition =
        if(sourceId == 0) AnimationBaseDefinition(targetId).also {
            AnimationBaseDecoder().readLoop(it,BufferReader(bytes))
            require(it.trailing?.isEmpty() != false) { "Unrecognized native humanoid skeleton trailer" }
            it.types=it.types.map { type -> if(type == 6) 2 else type }.toIntArray()
        } else OsrsAnimationConverter.convertBase(targetId,bytes)
    private fun encode(block: (Writer) -> Unit) = BufferWriter(1 shl 20).also(block).toArray()
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}


