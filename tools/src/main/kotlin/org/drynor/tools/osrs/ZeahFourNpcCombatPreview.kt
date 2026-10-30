package org.drynor.tools.osrs

import kotlinx.serialization.json.*
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.cache.FileCache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.SynthSoundDefinition
import world.gregs.voidps.cache.definition.decoder.SynthSoundDecoder
import world.gregs.voidps.cache.definition.encoder.SynthSoundEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW

/** Isolated animation package for the four selected ordinary Zeah cave NPCs. */
internal object ZeahFourNpcCombatPreview {
    private val requested = sortedMapOf(
        "twistedBanshee" to listOf(21526, 21524),
        "deviantSpectre" to listOf(21507, 21509, 21508),
    )
    private val nativeDragonSequences = setOf(81, 89, 91, 92)
    private val nativeSpots = setOf(1, 335)

    fun build(sourcePath: Path, previewRoot: Path, outputRoot: Path) {
        val donorPath = sourcePath.toRealPath()
        val inputRoot = previewRoot.toRealPath()
        val baselinePath = inputRoot.resolve("packed-cache").toRealPath()
        val output = outputRoot.toAbsolutePath().normalize()
        require(!Files.exists(output) && !output.startsWith(inputRoot) && !output.startsWith(donorPath))
        val previous = Json.parseToJsonElement(Files.readString(inputRoot.resolve("preview-report.json"))).jsonObject
        require(previous.getValue("karuulmNativeReadback").jsonPrimitive.boolean)
        FileCache(donorPath.toString(), writable = false).use { donor ->
            FileCache(baselinePath.toString(), writable = false).use { baseline ->
                for (id in nativeDragonSequences)
                    require(baseline.data(Index.ANIMATIONS, id ushr 7, id and 127) != null) { "Missing native dragon sequence $id" }
                for (id in nativeSpots)
                    require(baseline.data(Index.SPOTANIMS, id ushr 8, id and 255) != null) { "Missing native combat spot $id" }
                val donorSequences = requested.values.flatten().toSet().associateWith { id ->
                    requireNotNull(donor.data(Index.ANIMATIONS, id ushr 7, id and 127)) { "Missing donor sequence $id" }
                }
                val dependencies = donorSequences.mapValues { OsrsSequenceDependencies.parse(it.value) }
                require(dependencies.values.all { it.frameIds.isNotEmpty() && it.equipment.isEmpty() })
                val soundIds = dependencies.values.flatMap { it.sounds }.toSet()
                for (soundId in soundIds) {
                    val raw = requireNotNull(donor.data(Index.SYNTH_SOUNDS, soundId, 0))
                    require(raw.contentEquals(baseline.data(Index.SYNTH_SOUNDS, soundId, 0))) { "Unverified sound $soundId" }
                    val sound = SynthSoundDefinition(soundId)
                    SynthSoundDecoder().read(sound, raw)
                    val writer = BufferWriter(raw.size + 32)
                    with(SynthSoundEncoder()) { writer.encode(sound) }
                    require(writer.toArray().contentEquals(raw)) { "Sound $soundId failed native roundtrip" }
                }
                val animation = ZulrahAnimationAssets.plan(donor, baseline, donorSequences.keys, verifiedSoundIds = soundIds)
                val assets = animation.assets
                ZulrahActorPreview.validateFreshAssets(baseline, assets)
                Files.createDirectories(output)
                ZulrahActorPreview.stage(output.resolve("editable-assets"), assets, baseline)
                val packed = output.resolve("packed-cache")
                Files.createDirectory(packed)
                Files.list(baselinePath).use { files ->
                    files.filter { it.fileName.toString().startsWith("main_file_cache.") }
                        .forEach { Files.copy(it, packed.resolve(it.fileName)) }
                }
                InfernoAssetWriter.write(packed, assets)
                FileCache(packed.toString(), writable = false).use { result ->
                    for (asset in assets) check(result.data(asset.index, asset.archive, asset.file).contentEquals(asset.bytes))
                    val touched = assets.map { it.index to it.archive }.toSet()
                    for (index in baseline.indices()) for (archive in baseline.archiveIds(index)) {
                        if (index to archive !in touched) check(baseline.container(index, archive).contentEquals(result.container(index, archive)))
                        else for (file in baseline.files(index, archive)) check(baseline.data(index, archive, file).contentEquals(result.data(index, archive, file)))
                    }
                }
                val report = buildJsonObject {
                    previous.forEach { (key, value) -> put(key, value) }
                    put("packedCache", "packed-cache")
                    put("fourNpcCombatInputCache", baselinePath.toString())
                    put("fourNpcCombatAssets", assets.size)
                    put("fourNpcCombatSequenceMap", buildJsonObject {
                        for ((name, ids) in requested) put(name, buildJsonObject {
                            for (id in ids) put(id.toString(), animation.sequenceMap.getValue(id))
                        })
                    })
                    put("fourNpcCombatSoundIds", JsonArray(soundIds.sorted().map(::JsonPrimitive)))
                    put("fourNpcCombatNativeReadback", true)
                    put("fourNpcCombatClientValidated", false)
                }
                Files.writeString(output.resolve("preview-report.json"), Json { prettyPrint = true }.encodeToString(report) + "\n", CREATE_NEW)
            }
        }
    }
}
