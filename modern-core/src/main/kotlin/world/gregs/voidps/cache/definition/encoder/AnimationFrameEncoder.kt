package world.gregs.voidps.cache.definition.encoder

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.DefinitionEncoder
import world.gregs.voidps.cache.definition.data.AnimationFrameDefinition

/**
 * Index 0's frame files, byte for byte.
 *
 * The header, the raw flag bytes and the smarts in the width the file used - see
 * [AnimationFrameDefinition] for why none of that can be derived from what the client keeps.
 */
class AnimationFrameEncoder : DefinitionEncoder<AnimationFrameDefinition> {

    override fun Writer.encode(definition: AnimationFrameDefinition) {
        writeByte(definition.header)
        writeShort(definition.baseId)
        val count = definition.flags.size
        require(count <= AnimationFrameDefinition.WRAPPED_COUNT) {
            "Animation frame ${definition.id} has $count transforms; the count byte holds at most " +
                "${AnimationFrameDefinition.WRAPPED_COUNT}."
        }
        writeByte(count and 0xff)
        for (flag in definition.flags) {
            writeByte(flag)
        }
        val wide = definition.wideValues
        for (index in definition.values.indices) {
            writeSignedSmart(definition.values[index], wide != null && wide.indexOf(index) >= 0)
        }
    }
}
