package org.drynor.tools.osrs

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.FileCache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.AnimationDefinition
import world.gregs.voidps.cache.definition.data.SynthSoundDefinition
import world.gregs.voidps.cache.definition.decoder.AnimationDecoder
import world.gregs.voidps.cache.definition.decoder.SynthSoundDecoder
import world.gregs.voidps.cache.definition.encoder.SynthSoundEncoder
import world.gregs.voidps.cache.definition.encoder.AnimationEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Bounded canonical-cache import. The caller must stop consumers and capture rollback before apply. */
object PoolAnimationAssets {
    private const val SOURCE_SEQUENCE = 27305
    private const val SOUND = 2401

    fun run(args: List<String>) {
        require(args.size in 4..5 && args[0] in setOf("audit", "apply")) {
            "osrs pool-animation <audit|apply> <gielenor-packed> <canonical-drynor-packed> <report.json> [raw-assets-directory]"
        }
        val apply = args[0] == "apply"
        val sourcePath = Path.of(args[1]).toRealPath()
        val targetPath = Path.of(args[2]).toRealPath()
        require(sourcePath != targetPath)
        val reportPath = Path.of(args[3]).toAbsolutePath().normalize()
        require(!Files.exists(reportPath)) { "Refusing to overwrite a previous import report" }
        val rawRoot = args.getOrNull(4)?.let(Path::of)?.toAbsolutePath()?.normalize()
            ?: Path.of("tools/build/pool-animation").toAbsolutePath().normalize()
        require(!rawRoot.startsWith(sourcePath) && !rawRoot.startsWith(targetPath))
        lateinit var plan: ZulrahAnimationAssets.Result
        lateinit var beforeArchives: Map<Pair<Int, Int>, String>
        lateinit var beforeFiles: Map<Triple<Int, Int, Int>, String>
        lateinit var sourceSnapshot: JsonObject
        var soundHash = ""
        FileCache(sourcePath.toString(), writable = false).use { source ->
            FileCache(targetPath.toString(), writable = false).use { target ->
                sourceSnapshot = InfernoDependencyPlan.snapshot(source)
                val sound = requireNotNull(source.data(Index.SYNTH_SOUNDS, SOUND, 0))
                require(sound.contentEquals(target.data(Index.SYNTH_SOUNDS, SOUND, 0))) { "Pool sound $SOUND differs from native cache" }
                val decodedSound = SynthSoundDefinition(SOUND)
                SynthSoundDecoder().read(decodedSound, sound)
                val soundWriter = BufferWriter(sound.size + 32)
                with(SynthSoundEncoder()) { soundWriter.encode(decodedSound) }
                require(soundWriter.toArray().contentEquals(sound)) { "Pool sound failed native round trip" }
                soundHash = hash(sound)
                val dependency = OsrsSequenceDependencies.parse(requireNotNull(source.data(Index.ANIMATIONS, SOURCE_SEQUENCE ushr 7, SOURCE_SEQUENCE and 127)))
                require(dependency.frameIds.size == 5 && dependency.sounds == setOf(SOUND) && dependency.equipment == setOf(0)) {
                    "Unexpected pool dependencies: $dependency"
                }
                plan = ZulrahAnimationAssets.plan(source, target, setOf(SOURCE_SEQUENCE),
                    verifiedSoundIds = setOf(SOUND), preservedSceneEquipmentSequences = setOf(SOURCE_SEQUENCE))
                // OSRS uses three loop bits at bit 4 and four radius bits; 727 starts loops at bit 5.
                // Translate this audited sequence only, rather than inheriting an inaudible zero-loop event.
                plan = plan.copy(assets = plan.assets.map { asset ->
                    if (asset.index != Index.ANIMATIONS) asset else {
                        val sequence = AnimationDefinition(plan.sequenceMap.getValue(SOURCE_SEQUENCE))
                        AnimationDecoder().readLoop(sequence, BufferReader(asset.bytes))
                        sequence.sounds = sequence.sounds?.map { values -> values?.map(::nativePoolSound)?.toIntArray() }?.toTypedArray()
                        val writer = BufferWriter(asset.bytes.size + 32)
                        with(AnimationEncoder()) { writer.encode(sequence) }
                        val check = AnimationDefinition(sequence.id)
                        AnimationDecoder().readLoop(check, BufferReader(writer.toArray()))
                        require(check == sequence)
                        asset.copy(bytes = writer.toArray())
                    }
                })
                val touched = plan.assets.map { it.index to it.archive }.toSet()
                beforeArchives = buildMap {
                    for (index in target.indices()) for (archive in target.archiveIds(index))
                        if (index to archive !in touched) put(index to archive, hash(requireNotNull(target.container(index, archive))))
                }
                beforeFiles = buildMap {
                    for ((index, archive) in touched) for (file in target.files(index, archive))
                        put(Triple(index, archive, file), hash(requireNotNull(target.data(index, archive, file))))
                }
                for (asset in plan.assets) {
                    val relative = when (asset.index) {
                        Index.ANIMATIONS -> "sequences/${(asset.archive shl 7) or asset.file}.bin"
                        Index.ANIMATION_FRAMES -> "frames/${asset.archive}/${asset.file}.bin"
                        Index.ANIMATION_SKELETONS -> "bases/${asset.archive}.bin"
                        else -> error("Unexpected pool asset index ${asset.index}")
                    }
                    val path = rawRoot.resolve(relative)
                    Files.createDirectories(path.parent)
                    if (Files.exists(path)) require(Files.readAllBytes(path).contentEquals(asset.bytes)) { "Raw export changed: $path" }
                    else Files.write(path, asset.bytes)
                }
                val nativeSound = rawRoot.resolve("sounds/$SOUND.bin")
                Files.createDirectories(nativeSound.parent)
                if (Files.exists(nativeSound)) require(Files.readAllBytes(nativeSound).contentEquals(sound)) else Files.write(nativeSound, sound)
                val editable = rawRoot.resolve("source-package")
                if (!Files.exists(editable)) ZulrahActorPreview.stage(editable, plan.assets, target)
                require(sourceSnapshot == InfernoDependencyPlan.snapshot(source))
            }
        }
        if (apply) {
            // All read-only stores are closed before appending assets and replacing reference tables.
            InfernoAssetWriter.write(targetPath, plan.assets)
            FileCache(targetPath.toString(), writable = false).use { result ->
                for (asset in plan.assets) check(result.data(asset.index, asset.archive, asset.file).contentEquals(asset.bytes))
                for ((address, expected) in beforeArchives) check(hash(requireNotNull(result.container(address.first, address.second))) == expected) {
                    "Unrelated archive changed: $address"
                }
                for ((address, expected) in beforeFiles) check(hash(requireNotNull(result.data(address.first, address.second, address.third))) == expected) {
                    "Sibling definition changed: $address"
                }
                check(hash(requireNotNull(result.data(Index.SYNTH_SOUNDS, SOUND, 0))) == soundHash)
                val animationId = plan.sequenceMap.getValue(SOURCE_SEQUENCE)
                val sequence = AnimationDefinition(animationId)
                AnimationDecoder().readLoop(sequence, BufferReader(requireNotNull(result.data(Index.ANIMATIONS, animationId ushr 7, animationId and 127))))
                check(sequence.leftHandItem == 65535 && sequence.rightHandItem == 65535)
                check(sequence.sounds?.filterNotNull()?.flatMap { it.toList() }?.map { it ushr 8 }?.toSet() == setOf(SOUND))
            }
        }
        val animationId = plan.sequenceMap.getValue(SOURCE_SEQUENCE)
        val json = buildJsonObject {
            put("schemaVersion", 1)
            put("status", if (apply) "pool-animation-native-readback" else "pool-animation-audit-only")
            put("packedCache", "../../../cache")
            put("sourceOsrsAnimationId", 7305); put("sourceGielenorAnimationId", SOURCE_SEQUENCE)
            put("animationId", animationId); put("soundId", SOUND); put("soundSha256", soundHash)
            put("sequenceSha256", hash(plan.assets.single { it.index == Index.ANIMATIONS }.bytes))
            put("rawFolder", rawRoot.toString()); put("animation", plan.report)
            put("preservedArchivesVerified", if (apply) beforeArchives.size else 0)
            put("preservedSiblingFilesVerified", if (apply) beforeFiles.size else 0)
            put("editableRoundtripVerified", true)
            put("renderingValidated", false)
            put("assets", JsonArray(plan.assets.map { asset -> buildJsonObject {
                put("index", asset.index); put("archive", asset.archive); put("file", asset.file); put("sha256", hash(asset.bytes))
            } }))
        }
        Files.createDirectories(reportPath.parent)
        Files.writeString(reportPath, Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), json))
        println("POOL_ANIMATION_${if (apply) "APPLY" else "AUDIT"}_PASS animation=$animationId sound=$SOUND assets=${plan.assets.size} report=$reportPath")
    }

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    internal fun nativePoolSound(packed: Int): Int {
        require(packed ushr 8 == SOUND && packed and 128 == 0)
        return (packed and -256) or (((packed ushr 4) and 7) shl 5) or (packed and 15)
    }
}
