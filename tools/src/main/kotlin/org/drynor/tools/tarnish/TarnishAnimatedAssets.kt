package org.drynor.tools.tarnish

import kotlinx.serialization.json.*
import org.drynor.tools.osrs.InfernoAssetWriter
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.AnimationBaseDefinition
import world.gregs.voidps.cache.definition.data.AnimationDefinition
import world.gregs.voidps.cache.definition.data.AnimationFrameDefinition
import world.gregs.voidps.cache.definition.data.ObjectDefinition
import world.gregs.voidps.cache.definition.decoder.AnimationBaseDecoder
import world.gregs.voidps.cache.definition.decoder.AnimationDecoder
import world.gregs.voidps.cache.definition.decoder.AnimationFrameDecoder
import world.gregs.voidps.cache.definition.decoder.ObjectDecoder
import world.gregs.voidps.cache.definition.encoder.AnimationBaseEncoder
import world.gregs.voidps.cache.definition.encoder.AnimationEncoder
import world.gregs.voidps.cache.definition.encoder.AnimationFrameEncoder

/** Bounded animated Fairy Ring closure. Every sequence, frame and skeleton is read before mutation. */
internal object TarnishAnimatedAssets {
    const val FAIRY_RING_STATUS = "garden-fairy-ring-native-readback"
    private val animationIndices = setOf(Index.ANIMATION_FRAMES, Index.ANIMATION_SKELETONS, Index.ANIMATIONS)

    fun allowedIndices(status: String): Set<Int> = if (status == FAIRY_RING_STATUS)
        setOf(Index.ANIMATION_FRAMES, Index.ANIMATION_SKELETONS, Index.MODELS, Index.OBJECTS, Index.ANIMATIONS)
    else setOf(Index.MODELS, Index.OBJECTS, Index.ITEMS)

    fun verify(manifest: JsonObject, assets: List<InfernoAssetWriter.Asset>) {
        if (assets.none { it.index in animationIndices }) {
            require(manifest.getValue("status").jsonPrimitive.content != FAIRY_RING_STATUS) { "Animated ring package has no animation" }
            return
        }
        require(manifest.getValue("status").jsonPrimitive.content == FAIRY_RING_STATUS) { "Unreviewed animated package" }
        require(assets.all { it.index in allowedIndices(FAIRY_RING_STATUS) })
        fun mapping(name: String, source: String): Int {
            val ids = manifest.getValue(name).jsonObject
            require(ids.keys == setOf(source)) { "Unexpected source animation namespace: $name" }
            return ids.getValue(source).jsonPrimitive.int
        }
        val sequenceId = mapping("sourceAnimationIds", "9845")
        val frameGroup = mapping("sourceFrameGroupIds", "3357")
        val baseId = mapping("sourceBaseIds", "2112")
        require(sequenceId in 0..65534 && frameGroup in 0..32767 && baseId in 0..65535)
        val baseAssets = assets.filter { it.index == Index.ANIMATION_SKELETONS }
        val frameAssets = assets.filter { it.index == Index.ANIMATION_FRAMES }
        val sequenceAssets = assets.filter { it.index == Index.ANIMATIONS }
        val objectAssets = assets.filter { it.index == Index.OBJECTS }
        val modelIds = assets.filter { it.index == Index.MODELS }.map { it.archive }.toSet()
        require(baseAssets.size == 1 && baseAssets.single().archive == baseId && baseAssets.single().file == 0)
        require(frameAssets.size == 16 && frameAssets.all { it.archive == frameGroup && it.file in 0..32767 })
        require(sequenceAssets.size == 1 && sequenceAssets.single().archive == (sequenceId ushr 7) && sequenceAssets.single().file == (sequenceId and 127))
        require(objectAssets.size == 1 && modelIds.size == 2)

        val base = readBase(baseId, baseAssets.single().bytes)
        val frames = frameAssets.associate { asset ->
            val packed = (asset.archive shl 16) or asset.file
            packed to readFrame(packed, asset.bytes, base)
        }
        require(frames.size == frameAssets.size) { "Duplicate packed frame" }
        val sequence = readSequence(sequenceId, sequenceAssets.single().bytes)
        require(sequence.frames!!.toSet() == frames.keys && sequence.expressionFrames == null) { "Sequence frame dependency closure mismatch" }
        require(frames.values.all { it.baseId == baseId })
        val objectAsset = objectAssets.single()
        require(objectAsset.file in 0..255)
        val obj = ObjectDefinition((objectAsset.archive shl 8) or objectAsset.file)
        val reader = BufferReader(objectAsset.bytes)
        ObjectDecoder().readLoop(obj, reader)
        require(reader.remaining == 0)
        require(obj.name == "Fairy ring" && obj.animations?.toSet() == setOf(sequenceId))
        require(obj.modelIds?.flatMap { it.toList() }?.toSet() == modelIds)
    }

    internal fun readBase(id: Int, bytes: ByteArray): AnimationBaseDefinition {
        val reader = BufferReader(bytes)
        val base = AnimationBaseDefinition(id).also { AnimationBaseDecoder().readLoop(it, reader) }
        require(reader.remaining == 0)
        require(base.types.isNotEmpty() && base.types.size <= 256 && base.types.all { it in 0..5 }) { "Unreviewed skeleton transform" }
        require(base.labels.size == base.types.size && base.submeshes.size == base.types.size && base.shadowed.size == base.types.size)
        check(encode { with(AnimationBaseEncoder()) { encode(base) } }.contentEquals(bytes)) { "Base round trip changed bytes" }
        return base
    }

    internal fun readFrame(id: Int, bytes: ByteArray, base: AnimationBaseDefinition): AnimationFrameDefinition {
        val reader = BufferReader(bytes)
        val frame = AnimationFrameDefinition(id).also { AnimationFrameDecoder().readLoop(it, reader) }
        require(reader.remaining == 0 && frame.header == 1 && frame.baseId == base.id)
        require(frame.flags.size <= base.types.size && frame.flags.all { it in 0..7 }) { "Frame exceeds reviewed skeleton/flags" }
        check(encode { with(AnimationFrameEncoder()) { encode(frame) } }.contentEquals(bytes)) { "Frame round trip changed bytes" }
        return frame
    }

    internal fun readSequence(id: Int, bytes: ByteArray): AnimationDefinition {
        val reader = BufferReader(bytes)
        val sequence = AnimationDefinition(id).also { AnimationDecoder().readLoop(it, reader) }
        require(reader.remaining == 0 && sequence.frames?.isNotEmpty() == true)
        require(sequence.durations?.size == sequence.frames!!.size && sequence.durations!!.all { it > 0 })
        require(sequence.leftHandItem == -1 && sequence.rightHandItem == -1 && sequence.sounds == null) { "Unreviewed sequence item/sound dependency" }
        check(encode { with(AnimationEncoder()) { encode(sequence) } }.contentEquals(bytes)) { "Sequence round trip changed bytes" }
        return sequence
    }

    private fun encode(block: BufferWriter.() -> Unit) = BufferWriter(1 shl 20).apply(block).toArray()
}
