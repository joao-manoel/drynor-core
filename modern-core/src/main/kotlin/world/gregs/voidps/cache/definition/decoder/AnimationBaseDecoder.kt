package world.gregs.voidps.cache.definition.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.DefinitionDecoder
import world.gregs.voidps.cache.Index.ANIMATION_SKELETONS
import world.gregs.voidps.cache.definition.data.AnimationBaseDefinition

/**
 * Index 1: one animation skeleton per archive, one file per archive.
 *
 * Five columns of one row per transform and nothing else - no opcodes, no terminator - so the whole read
 * is [readLoop]. The label table's own lengths are the last column, so the file's length is a function of
 * its contents and anything past the table is stored verbatim ([AnimationBaseDefinition.trailing]).
 */
class AnimationBaseDecoder : DefinitionDecoder<AnimationBaseDefinition>(ANIMATION_SKELETONS) {

    override fun create(size: Int) = Array(size) { AnimationBaseDefinition(it) }

    override fun getFile(id: Int) = 0

    override fun getArchive(id: Int) = id

    override fun readLoop(definition: AnimationBaseDefinition, buffer: Reader) {
        definition.read(-1, buffer)
    }

    override fun AnimationBaseDefinition.read(opcode: Int, buffer: Reader) {
        val declared = buffer.readUnsignedByte()
        // A declared 0 with a payload behind it is 256 written into a byte; see WRAPPED_COUNT.
        val count = if (declared == 0 && buffer.readableBytes() > 0) {
            AnimationBaseDefinition.WRAPPED_COUNT
        } else {
            declared
        }
        types = IntArray(count) { buffer.readUnsignedByte() }
        shadowed = IntArray(count) { buffer.readUnsignedByte() }
        submeshes = IntArray(count) { buffer.readUnsignedShort() }
        val lengths = IntArray(count) { buffer.readUnsignedByte() }
        labels = Array(count) { transform -> IntArray(lengths[transform]) { buffer.readUnsignedByte() } }
        if (buffer.readableBytes() > 0) {
            trailing = ByteArray(buffer.readableBytes()).also { buffer.readBytes(it) }
        }
    }
}
