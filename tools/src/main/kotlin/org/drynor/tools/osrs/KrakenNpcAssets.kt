package org.drynor.tools.osrs

import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.FileCache
import world.gregs.voidps.cache.Config
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.BASDefinition
import world.gregs.voidps.cache.definition.data.NPCDefinition
import world.gregs.voidps.cache.definition.decoder.BASDecoder
import world.gregs.voidps.cache.definition.decoder.ModelDecoder
import world.gregs.voidps.cache.definition.decoder.NPCDecoder
import world.gregs.voidps.cache.definition.encoder.BASEncoder
import world.gregs.voidps.cache.definition.encoder.ModelEncoder
import world.gregs.voidps.cache.definition.encoder.NPCEncoder
import java.nio.file.Files
import java.nio.file.Path

/** Native NPC assets shared by the cave, the encounter and its pet. */
internal object KrakenNpcAssets {
    private val npcIds = setOf(20492, 20493, 20494, 20496, 25534, 25535, 26640, 27412)
    private const val WHIRLPOOL_MODEL = 226699
    private val whirlpoolIds = setOf(20493, 20496, 25534)
    private val fightingSequences = setOf(23990, 23992, 23993, 23618, 23619, 23620, 27135, 23860)

    fun run(sourcePath: Path, targetPath: Path, output: Path, install: Boolean, collector: Boolean = false) =
        convert(sourcePath, targetPath, output, install, if (collector) setOf(28491) else npcIds,
            if (collector) emptySet() else fightingSequences, if (collector) emptySet() else whirlpoolIds)

    fun cerberus(sourcePath: Path, targetPath: Path, output: Path, install: Boolean) =
        convert(sourcePath, targetPath, output, install, setOf(25862, 25863, 25867, 25868, 25869, 25870, 20964),
            setOf(24486, 24487, 24490, 24491, 24492, 24494, 24495, 24503, 24504), emptySet())

    fun bossPets(sourcePath: Path, targetPath: Path, output: Path, install: Boolean) =
        convert(sourcePath, targetPath, output, install,
            setOf(26635, 26637, 26638, 26627, 26630, 26626, 20318, 28010, 26632, 26634, 26631, 26633, 26636, 22055) +
                (21672..21677), emptySet(), emptySet(), (21672..21677).associateWith { 16010 + it - 21672 })

    fun repairCerberusAttack(cachePath: Path) {
        val id = 25863
        val archive = id ushr 7
        val file = id and 127
        val (original, patched) = FileCache(cachePath.toString(), writable = false).use { cache ->
            val bytes = requireNotNull(cache.data(Index.NPCS, archive, file))
            val active = decodeNpc(25862, requireNotNull(cache.data(Index.NPCS, archive, 25862 and 127)))
            val dormant = decodeNpc(id, bytes)
            check(active.options[1] == "Attack" && dormant.options[1] == null)
            enableCerberusAttack(dormant)
            val encoded = encode { with(NPCEncoder()) { it.encode(dormant) } }
            val readback = decodeNpc(id, encoded)
            check(readback.options.contentEquals(dormant.options) && readback.name == dormant.name &&
                readback.renderEmote == dormant.renderEmote && readback.modelIds.contentEquals(dormant.modelIds))
            bytes to encoded
        }
        val replacement = InfernoAssetWriter.Asset(Index.NPCS, archive, file, patched, replaceExisting = true)
        check(decodeNpc(id, patched).options[1] == "Attack")
        try {
            InfernoAssetWriter.write(cachePath, listOf(replacement))
            FileCache(cachePath.toString(), writable = false).use { cache ->
                check(patched.contentEquals(cache.data(Index.NPCS, archive, file)))
            }
        } catch (failure: Exception) {
            InfernoAssetWriter.write(cachePath, listOf(replacement.copy(bytes = original)))
            throw failure
        }
        println("Verified Cerberus Attack option on NPC $id")
    }

    fun cerberusCombatAnimations(sourcePath: Path, cachePath: Path, output: Path, install: Boolean) {
        val result = FileCache(sourcePath.toString(), writable = false).use { source ->
            FileCache(cachePath.toString(), writable = false).use { target ->
                ZulrahAnimationAssets.plan(source, target, setOf(24493, 28528, 28529, 28530))
            }
        }
        Files.createDirectory(output)
        Files.writeString(output.resolve("animation-ids.txt"),
            result.sequenceMap.entries.joinToString("\n") { "${it.key}=${it.value}" })
        if (install) {
            InfernoAssetWriter.write(cachePath, result.assets)
            FileCache(cachePath.toString(), writable = false).use { target ->
                for (asset in result.assets) check(asset.bytes.contentEquals(target.data(asset.index, asset.archive, asset.file)))
            }
        }
        println("Verified ${result.assets.size} combat animation files (installed=$install)")
    }

    private fun decodeNpc(id: Int, bytes: ByteArray) =
        NPCDefinition(id).also { NPCDecoder().readLoop(it, BufferReader(bytes)) }

    private fun enableCerberusAttack(npc: NPCDefinition) {
        if (npc.id != 25863) return
        npc.options[1] = "Attack"
        npc.rawOptions?.set(1, "Attack")
    }

    private fun convert(
        sourcePath: Path, targetPath: Path, output: Path, install: Boolean, selected: Set<Int>,
        combatSequences: Set<Int>, adjustedWhirlpools: Set<Int>, npcTargets: Map<Int, Int> = emptyMap(),
    ) {
        require(!Files.exists(output))
        val assets = FileCache(sourcePath.toString(), writable = false).use { source ->
            FileCache(targetPath.toString(), writable = false).use { target ->
                val definitions = selected.sorted().associateWith {
                    val nativeId = npcTargets[it] ?: it
                    check(target.data(Index.NPCS, nativeId ushr 7, nativeId and 127) == null) { "Occupied source NPC $it" }
                    requireNotNull(source.data(Index.NPCS, it ushr 7, it and 127))
                }
                val deps = definitions.values.map(OsrsNpcDependencies::parse)
                check(deps.all { it.morphs.isEmpty() && it.textures.isEmpty() && it.headIcons.isEmpty() })
                check(definitions.filterValues { WHIRLPOOL_MODEL - 200_000 in OsrsNpcDependencies.parse(it).models }.keys == adjustedWhirlpools)
                val modelIds = deps.flatMap { dep -> dep.models.map { it + 200_000 } }.toSet()
                val models = DeterministicIds.plan(modelIds, target.archiveIds(Index.MODELS).toSet())
                val sequences = combatSequences + deps.flatMap { dep -> dep.animations.map { it + 20_000 } }
                val verifiedSounds = if (selected.contains(26635)) sequences.flatMap { sequence ->
                    OsrsSequenceDependencies.parse(requireNotNull(source.data(Index.ANIMATIONS, sequence ushr 7, sequence and 127))).sounds
                }.toSet().also { sounds ->
                    for (sound in sounds) check(requireNotNull(source.data(Index.SYNTH_SOUNDS, sound, 0))
                        .contentEquals(target.data(Index.SYNTH_SOUNDS, sound, 0))) { "Different pet sound $sound" }
                } else emptySet()
                val animations = ZulrahAnimationAssets.plan(source, target, sequences, verifiedSoundIds = verifiedSounds)
                val result = animations.assets.toMutableList()
                val reservedBas = target.files(Index.CONFIGS, Config.RENDER_ANIMATIONS).toMutableSet()
                for ((sourceId, definition) in definitions) {
                    val id = npcTargets[sourceId] ?: sourceId
                    val bas = (0..65534).first { it !in reservedBas }
                    reservedBas.add(bas)
                    val converted = OsrsNpcConverter.convert(id, definition, models, animations.sequenceMap, bas, preview = false)
                    enableCerberusAttack(converted.npc)
                    if (selected.contains(26635)) {
                        converted.npc.options = arrayOf("Talk-to", "Pick-up", if (id in setOf(26637, 26638, 20318, 28010)) "Metamorphosis" else null, null, null, "Examine")
                        converted.npc.rawOptions = null
                        converted.npc.combat = 0
                        converted.npc.drawMinimapDot = false
                        if (sourceId in 21672..21677) {
                            converted.npc.scaleXY = 64
                            converted.npc.scaleZ = 64
                        }
                    }
                    val npcBytes = encode { with(NPCEncoder()) { it.encode(converted.npc) } }
                    val npcRead = NPCDefinition(id)
                    NPCDecoder().readLoop(npcRead, BufferReader(npcBytes))
                    check(npcRead.name == converted.npc.name && npcRead.size == converted.npc.size &&
                        npcRead.options.contentEquals(converted.npc.options) &&
                        npcRead.modelIds.contentEquals(converted.npc.modelIds) &&
                        npcRead.dialogueModels.contentEquals(converted.npc.dialogueModels) && npcRead.renderEmote == bas)
                    val basBytes = encode { with(BASEncoder()) { it.encode(converted.bas) } }
                    val basRead = BASDefinition(bas)
                    BASDecoder().readLoop(basRead, BufferReader(basBytes))
                    check(basRead.standAnimation == converted.bas.standAnimation && basRead.walkAnimation == converted.bas.walkAnimation)
                    result.add(InfernoAssetWriter.Asset(Index.NPCS, id ushr 7, id and 127, npcBytes))
                    result.add(InfernoAssetWriter.Asset(Index.CONFIGS, Config.RENDER_ANIMATIONS, bas, basBytes))
                }
                for ((origin, id) in models) {
                    val model = ModelDecoder.read(id, requireNotNull(source.data(Index.MODELS, origin)))
                    check(model.faceTextures?.all { it == (-1).toShort() } != false) { "Unaudited material on Kraken model $origin" }
                    check(model.billboards.isNullOrEmpty() && model.particleEmitters.isNullOrEmpty() && model.particleEffectors.isNullOrEmpty())
                    // NPCConfig in Gielenor lifts the dormant forms before applying their BAS.
                    val adjusted = if (origin == WHIRLPOOL_MODEL) model.copy(
                        vertexY = IntArray(model.vertexCount) { model.vertexY[it] - 200 },
                        vertexFlags = null,
                    ) else model
                    val bytes = ModelEncoder.encode(adjusted)
                    val readback = ModelDecoder.read(id, bytes)
                    check(readback.vertexY.contentEquals(adjusted.vertexY))
                    check(ModelEncoder.encode(readback).contentEquals(bytes))
                    result.add(InfernoAssetWriter.Asset(Index.MODELS, id, 0, bytes))
                }
                ZulrahActorPreview.validateFreshAssets(target, result)
                Files.createDirectory(output)
                for (asset in result) Files.write(output.resolve("${asset.index}-${asset.archive}-${asset.file}.bin"), asset.bytes)
                Files.writeString(output.resolve("animation-ids.txt"),
                    animations.sequenceMap.entries.joinToString("\n") { "${it.key}=${it.value}" })
                result
            }
        }
        if (install) {
            InfernoAssetWriter.write(targetPath, assets)
            FileCache(targetPath.toString(), writable = false).use { target ->
                for (asset in assets) check(asset.bytes.contentEquals(target.data(asset.index, asset.archive, asset.file)))
            }
        }
        println("Verified ${assets.size} native NPC assets (installed=$install)")
    }
    private fun encode(block: (Writer) -> Unit) = BufferWriter(1 shl 20).also(block).toArray()
}
