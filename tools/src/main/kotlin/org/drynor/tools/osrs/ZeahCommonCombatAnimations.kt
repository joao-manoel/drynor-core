package org.drynor.tools.osrs

import kotlinx.serialization.json.*
import world.gregs.voidps.cache.FileCache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.SynthSoundDefinition
import world.gregs.voidps.cache.definition.decoder.SynthSoundDecoder
import world.gregs.voidps.cache.definition.encoder.SynthSoundEncoder
import world.gregs.voidps.buffer.write.BufferWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.security.MessageDigest

/** Adds the audited common NPC combat animations to one isolated Zeah preview. */
internal object ZeahCommonCombatAnimations {
    private val karuulmSequences = sortedMapOf(
        "sulphurLizard" to listOf(28283, 28284),
        "hydra" to (28261..28264).toList(),
        "drake" to (28275..28278).toList(),
        "wyrm" to (28270..28272).toList(),
        "wyrmTransform" to listOf(28268, 28269),
        "lizardman" to listOf(27192, 27194, 27196),
    )
    private val karuulmSpots = (6551..6556).toList() + (6654..6663).toList() +
        (6636..6638).toList() + (6634..6635).toList()
    private val sequences = sortedMapOf(
        "greaterNechryael" to listOf(21528, 21531, 21530),
        "templeSpider" to listOf(25327, 25328, 25329),
        "undeadDruid" to listOf(25578, 25579, 25580),
        "sulphurLizard" to listOf(28283, 28284),
        "lizardman" to listOf(27192, 27194, 27196),
        "wyrmTransform" to listOf(28268, 28269),
    )

    fun build(sourcePath: Path, previewRoot: Path, outputRoot: Path) {
        val sourceRoot = sourcePath.toRealPath()
        val inputRoot = previewRoot.toRealPath()
        val baselinePath = inputRoot.resolve("packed-cache").toRealPath()
        val output = outputRoot.toAbsolutePath().normalize()
        require(!Files.exists(output) && !output.startsWith(sourceRoot) && !output.startsWith(inputRoot))
        val sourceIds = sequences.values.flatten().toSet()
        FileCache(sourceRoot.toString(), writable = false).use { source ->
            FileCache(baselinePath.toString(), writable = false).use { baseline ->
                val donor = sourceIds.sorted().associateWith { id ->
                    requireNotNull(source.data(Index.ANIMATIONS, id ushr 7, id and 127)) { "Missing donor sequence $id" }
                }
                val dependencies = donor.mapValues { (_, bytes) -> OsrsSequenceDependencies.parse(bytes) }
                require(dependencies.values.all { it.frameIds.isNotEmpty() && it.equipment.isEmpty() }) {
                    "Common NPC sequence needs explicit sound/equipment handling: $dependencies"
                }
                val soundIds = dependencies.values.flatMap { it.sounds }.toSet()
                for (soundId in soundIds) {
                    val raw = requireNotNull(source.data(Index.SYNTH_SOUNDS, soundId, 0))
                    require(raw.contentEquals(baseline.data(Index.SYNTH_SOUNDS, soundId, 0))) { "Unverified sound $soundId" }
                    val definition = SynthSoundDefinition(soundId)
                    SynthSoundDecoder().read(definition, raw)
                    val writer = BufferWriter(raw.size + 32)
                    with(SynthSoundEncoder()) { writer.encode(definition) }
                    require(writer.toArray().contentEquals(raw)) { "Sound $soundId failed native roundtrip" }
                }
                val previous = Json.parseToJsonElement(Files.readString(inputRoot.resolve("preview-report.json"))).jsonObject
                val known = mutableMapOf<Int, Int>()
                previous["commonCombatAnimationMap"]?.jsonObject?.let { groups ->
                    for ((name, entries) in groups) {
                        val ids = sequences[name] ?: continue
                        for ((index, key) in listOf("attack", "defend", "death", "other").withIndex()) {
                            if (index < ids.size) entries.jsonObject[key]?.let { known[ids[index]] = it.jsonPrimitive.int }
                        }
                    }
                }
                previous["shamanAnimationMap"]?.jsonObject?.forEach { (id, target) ->
                    val sourceId = id.toInt()
                    if (sourceId in sourceIds) known[sourceId] = target.jsonPrimitive.int
                }
                val newIds = sourceIds - known.keys
                val animation = ZulrahAnimationAssets.plan(source, baseline, newIds, verifiedSoundIds = soundIds)
                val sequenceMap = known + animation.sequenceMap
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
                    put("commonCombatAnimationSourceCache", sourceRoot.toString())
                    put("commonCombatAnimationInputCache", baselinePath.toString())
                    put("commonCombatAnimationAssetCount", assets.size)
                    put("commonCombatAnimationMap", buildJsonObject {
                        for ((name, ids) in sequences) put(name, buildJsonObject {
                            put("attack", sequenceMap.getValue(ids[0]))
                            put("defend", sequenceMap.getValue(ids[1]))
                            if (ids.size > 2) put("death", sequenceMap.getValue(ids[2]))
                            if (ids.size > 3) put("other", sequenceMap.getValue(ids[3]))
                        })
                    })
                    put("commonCombatSourceSequenceHashes", buildJsonObject {
                        for ((id, bytes) in donor) put(id.toString(), sha256(bytes))
                    })
                    put("commonCombatVerifiedSoundIds", JsonArray(soundIds.sorted().map(::JsonPrimitive)))
                    put("commonCombatAssetReport", animation.report)
                    put("commonCombatNativeReadback", true)
                    put("commonCombatGameplayValidated", false)
                }
                Files.writeString(output.resolve("preview-report.json"), Json { prettyPrint = true }.encodeToString(report) + "\n", CREATE_NEW)
            }
        }
    }

    fun auditKaruulm(sourcePath: Path, previewPath: Path, outputPath: Path) {
        require(!Files.exists(outputPath))
        val sourceRoot = sourcePath.toRealPath()
        val targetRoot = previewPath.toRealPath()
        require(sourceRoot != targetRoot)
        FileCache(sourceRoot.toString(), writable = false).use { source ->
            FileCache(targetRoot.toString(), writable = false).use { target ->
                val animations = karuulmSequences.values.flatten().toSet().sorted().map { id ->
                    val raw = source.data(Index.ANIMATIONS, id ushr 7, id and 127)
                    val deps = raw?.let { runCatching { OsrsSequenceDependencies.parse(it) } }
                    buildJsonObject {
                        put("id", id)
                        put("sourcePresent", raw != null)
                        put("targetOccupied", target.data(Index.ANIMATIONS, id ushr 7, id and 127) != null)
                        raw?.let { put("sourceSha256", sha256(it)) }
                        put("parseStatus", if (deps?.isSuccess == true) "parsed" else "blocked")
                        deps?.getOrNull()?.let { put("frames", it.frameIds.size); put("sounds", JsonArray(it.sounds.sorted().map(::JsonPrimitive))); put("equipment", JsonArray(it.equipment.sorted().map(::JsonPrimitive))) }
                        deps?.exceptionOrNull()?.let { put("issue", it.message ?: it.javaClass.simpleName) }
                    }
                }
                val spots = karuulmSpots.sorted().map { id ->
                    val raw = source.data(Index.SPOTANIMS, id ushr 8, id and 255)
                    val spot = raw?.let { runCatching { OsrsSpotConverter.parse(id, it) } }
                    buildJsonObject {
                        put("id", id)
                        put("sourcePresent", raw != null)
                        put("targetOccupied", target.data(Index.SPOTANIMS, id ushr 8, id and 255) != null)
                        raw?.let { put("sourceSha256", sha256(it)) }
                        put("parseStatus", if (spot?.isSuccess == true) "parsed" else "blocked")
                        spot?.getOrNull()?.let {
                            put("modelId", it.modelId); put("animationId", it.animationId)
                            put("sourceModelPresent", source.data(Index.MODELS, it.modelId) != null)
                        }
                        spot?.exceptionOrNull()?.let { put("issue", it.message ?: it.javaClass.simpleName) }
                    }
                }
                val spotSequenceIds = spots.mapNotNull { it["animationId"]?.jsonPrimitive?.int?.takeIf { id -> id >= 0 } }
                val npcAnimationIds = listOf(28610, 28611, 28613).flatMap { id ->
                    source.data(Index.NPCS, id ushr 7, id and 127)?.let { OsrsNpcDependencies.parse(it).animations.map { raw -> raw + 20000 } } ?: emptyList()
                }
                val soundIds = (karuulmSequences.values.flatten() + spotSequenceIds + npcAnimationIds).toSet().mapNotNull { id ->
                    source.data(Index.ANIMATIONS, id ushr 7, id and 127)?.let { OsrsSequenceDependencies.parse(it).sounds }
                }.flatten().toSet()
                val soundReports = soundIds.sorted().map { id ->
                    val raw = source.data(Index.SYNTH_SOUNDS, id, 0)
                    val current = target.data(Index.SYNTH_SOUNDS, id, 0)
                    val roundtrip = raw?.let { bytes -> runCatching {
                        val def = SynthSoundDefinition(id)
                        SynthSoundDecoder().read(def, bytes)
                        val writer = BufferWriter(bytes.size + 32)
                        with(SynthSoundEncoder()) { writer.encode(def) }
                        writer.toArray().contentEquals(bytes)
                    }.getOrDefault(false) } ?: false
                    buildJsonObject {
                        put("id", id); put("sourcePresent", raw != null); put("targetPresent", current != null)
                        put("identical", raw != null && current != null && raw.contentEquals(current))
                        put("nativeRoundtrip", roundtrip)
                        raw?.let { put("sourceSha256", sha256(it)) }
                        current?.let { put("targetSha256", sha256(it)) }
                    }
                }
                val npcReports = listOf(28610, 28611, 28613).map { id ->
                    val raw = source.data(Index.NPCS, id ushr 7, id and 127)
                    val deps = raw?.let { runCatching { OsrsNpcDependencies.parse(it) } }
                    buildJsonObject {
                        put("id", id); put("sourcePresent", raw != null)
                        put("targetOccupied", target.data(Index.NPCS, id ushr 7, id and 127) != null)
                        raw?.let { put("sourceSha256", sha256(it)) }
                        put("parseStatus", if (deps?.isSuccess == true) "parsed" else "blocked")
                        deps?.getOrNull()?.let { put("models", JsonArray(it.models.sorted().map(::JsonPrimitive))); put("animations", JsonArray(it.animations.sorted().map(::JsonPrimitive))); put("morphs", JsonArray(it.morphs.sorted().map(::JsonPrimitive))) }
                        deps?.exceptionOrNull()?.let { put("issue", it.message ?: it.javaClass.simpleName) }
                    }
                }
                val report = buildJsonObject {
                    put("status", "read-only-karuulm-combat-visual-audit")
                    put("sourceCache", sourceRoot.toString())
                    put("targetCache", targetRoot.toString())
                    put("animationGroups", buildJsonObject { karuulmSequences.forEach { (name, ids) -> put(name, JsonArray(ids.map(::JsonPrimitive))) } })
                    put("animations", JsonArray(animations))
                    put("spots", JsonArray(spots))
                    put("sounds", JsonArray(soundReports))
                    put("npcs", JsonArray(npcReports))
                    put("limitations", "Presence and OSRS grammar alone do not prove native conversion or client playback; spot sequences/models need closed conversion before runtime use.")
                }
                Files.createDirectories(outputPath.toAbsolutePath().parent)
                Files.writeString(outputPath, Json { prettyPrint = true }.encodeToString(report) + "\n", CREATE_NEW)
            }
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
