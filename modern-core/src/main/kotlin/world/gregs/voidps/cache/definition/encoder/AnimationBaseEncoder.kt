package world.gregs.voidps.cache.definition.encoder

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.DefinitionEncoder
import world.gregs.voidps.cache.definition.data.AnimationBaseDefinition

/**
 * Index 1's skeleton files, byte for byte: the five columns in order, then whatever followed them.
 */
class AnimationBaseEncoder : DefinitionEncoder<AnimationBaseDefinition> {

    override fun Writer.encode(definition: AnimationBaseDefinition) {
        val count = definition.types.size
        require(count <= AnimationBaseDefinition.WRAPPED_COUNT) {
            "Animation base ${definition.id} has $count transforms; the count byte holds at most " +
                "${AnimationBaseDefinition.WRAPPED_COUNT}."
        }
        writeByte(count and 0xff)
        for (type in definition.types) {
            writeByte(type)
        }
        for (shadow in definition.shadowed) {
            writeByte(shadow)
        }
        for (submesh in definition.submeshes) {
            writeShort(submesh)
        }
        for (transform in 0 until count) {
            writeByte(definition.labels[transform]?.size ?: 0)
        }
        for (transform in 0 until count) {
            for (label in definition.labels[transform] ?: IntArray(0)) {
                writeByte(label)
            }
        }
        definition.trailing?.let { writeBytes(it) }
    }
}
