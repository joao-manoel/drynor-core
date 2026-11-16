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

    fun run(sourcePath: Path, targetPath: Path, output: Path, install: Boolean, collector: Boolean = false) {
        require(!Files.exists(output))
        val assets = FileCache(sourcePath.toString(), writable = false).use { source ->
            FileCache(targetPath.toString(), writable = false).use { target ->
                val selected = if (collector) setOf(28491) else npcIds
                val definitions = selected.sorted().associateWith {
                    check(target.data(Index.NPCS, it ushr 7, it and 127) == null) { "Occupied source NPC $it" }
                    requireNotNull(source.data(Index.NPCS, it ushr 7, it and 127))
                }
                val deps = definitions.values.map(OsrsNpcDependencies::parse)
                check(deps.all { it.morphs.isEmpty() && it.textures.isEmpty() && it.headIcons.isEmpty() })
                if (!collector) check(definitions.filterValues { WHIRLPOOL_MODEL - 200_000 in OsrsNpcDependencies.parse(it).models }.keys == whirlpoolIds)
                val modelIds = deps.flatMap { dep -> dep.models.map { it + 200_000 } }.toSet()
                val models = DeterministicIds.plan(modelIds, target.archiveIds(Index.MODELS).toSet())
                val sequences = (if (collector) emptySet() else fightingSequences) + deps.flatMap { dep -> dep.animations.map { it + 20_000 } }
                val animations = ZulrahAnimationAssets.plan(source, target, sequences)
                val result = animations.assets.toMutableList()
                val reservedBas = target.files(Index.CONFIGS, Config.RENDER_ANIMATIONS).toMutableSet()
                for ((id, definition) in definitions) {
                    val bas = (0..65534).first { it !in reservedBas }
                    reservedBas.add(bas)
                    val converted = OsrsNpcConverter.convert(id, definition, models, animations.sequenceMap, bas, preview = false)
                    val npcBytes = encode { with(NPCEncoder()) { it.encode(converted.npc) } }
                    val npcRead = NPCDefinition(id)
                    NPCDecoder().readLoop(npcRead, BufferReader(npcBytes))
                    check(npcRead.name == converted.npc.name && npcRead.size == converted.npc.size &&
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
