package org.drynor.tools.osrs

import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.FileCache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.AnimationDefinition
import world.gregs.voidps.cache.definition.decoder.AnimationDecoder
import world.gregs.voidps.cache.definition.encoder.AnimationEncoder
import java.nio.file.Files
import java.nio.file.Path

internal object InfernalToolAnimations {
    private val overrides = mapOf(
        22116 to mapOf(13753 to 30126), // Canoe carving
        22117 to mapOf(13753 to 30126), // Woodcutting
        24481 to mapOf(13755 to 30127), // Mining
        27402 to mapOf(4483 to 30128),  // Fishing presentation item
    )
    private const val IVY_NATIVE = 2846
    private const val IVY_INFERNAL = 22118

    fun run(sourcePath: Path, targetPath: Path, output: Path, install: Boolean) {
        require(!Files.exists(output))
        val assets = FileCache(sourcePath.toString(), writable = false).use { source ->
            FileCache(targetPath.toString(), writable = false).use { target ->
                val sounds = overrides.keys.flatMap { id ->
                    OsrsSequenceDependencies.parse(requireNotNull(sequence(source, id))).sounds
                }.toSet()
                for (sound in sounds) {
                    val bytes = requireNotNull(source.data(Index.SYNTH_SOUNDS, sound, 0))
                    require(bytes.contentEquals(target.data(Index.SYNTH_SOUNDS, sound, 0))) { "Different synth sound $sound" }
                }
                val plan = ZulrahAnimationAssets.plan(source, target, overrides.keys,
                    verifiedSoundIds = sounds, equipmentOverrides = overrides)
                require(plan.sequenceMap.all { (sourceId, targetId) -> sourceId == targetId })
                require(sequence(target, IVY_INFERNAL) == null)
                val imported = plan.assets.map { asset ->
                    if (asset.index != Index.ANIMATIONS) return@map asset
                    val id = (asset.archive shl 7) or asset.file
                    val animation = decode(id, asset.bytes)
                    check(setOf(animation.leftHandItem, animation.rightHandItem) ==
                        setOf(65535, overrides.getValue(id).values.single()))
                    animation.sounds = animation.sounds?.map { row ->
                        row?.map { packed ->
                            require(packed and 128 == 0)
                            // Native 727 starts the loop count one bit later than OSRS.
                            (packed and -256) or (((packed ushr 4) and 7) shl 5) or (packed and 15)
                        }?.toIntArray()
                    }?.toTypedArray()
                    asset.copy(bytes = encode(animation))
                }
                val ivy = decode(IVY_INFERNAL, requireNotNull(sequence(target, IVY_NATIVE)))
                check(ivy.leftHandItem == 6739 && ivy.rightHandItem == 65535)
                ivy.leftHandItem = 30126
                imported + InfernoAssetWriter.Asset(Index.ANIMATIONS, IVY_INFERNAL ushr 7, IVY_INFERNAL and 127, encode(ivy))
            }
        }
        Files.createDirectory(output)
        for (asset in assets) Files.write(output.resolve("${asset.index}-${asset.archive}-${asset.file}.bin"), asset.bytes)
        if (install) {
            InfernoAssetWriter.write(targetPath, assets)
            FileCache(targetPath.toString(), writable = false).use { cache ->
                for (asset in assets) check(asset.bytes.contentEquals(cache.data(asset.index, asset.archive, asset.file)))
            }
        }
        println("Verified ${assets.size} assets for five infernal tool animations (installed=$install)")
    }

    private fun sequence(cache: Cache, id: Int) = cache.data(Index.ANIMATIONS, id ushr 7, id and 127)

    private fun decode(id: Int, bytes: ByteArray) = AnimationDefinition(id).also {
        AnimationDecoder().readLoop(it, BufferReader(bytes))
    }

    private fun encode(animation: AnimationDefinition): ByteArray {
        val writer = BufferWriter(1 shl 16)
        with(AnimationEncoder()) { writer.encode(animation) }
        val bytes = writer.toArray()
        check(decode(animation.id, bytes) == animation)
        return bytes
    }
}
