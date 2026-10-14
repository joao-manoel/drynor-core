package world.gregs.voidps.cache.definition.decoder

import it.unimi.dsi.fastutil.ints.IntArrayList
import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.DefinitionDecoder
import world.gregs.voidps.cache.Index.ANIMATION_FRAMES
import world.gregs.voidps.cache.definition.data.AnimationFrameDefinition

/**
 * Index 0: one animation frame per file, several files per frame set archive.
 *
 * Not an opcode keyed type - the file is a four byte header, a flag byte per skeleton transform and one
 * run of signed smarts - so the whole read is [readLoop] and there is no terminator. The definition id
 * packs the archive and the file as `archive shl 16 or file`, which is how the source tree addresses a
 * frame; nothing at runtime loads frames through this decoder, so [size] is deliberately not overridden.
 */
class AnimationFrameDecoder : DefinitionDecoder<AnimationFrameDefinition>(ANIMATION_FRAMES) {

    override fun create(size: Int) = Array(size) { AnimationFrameDefinition(it) }

    override fun getArchive(id: Int) = id ushr BITS

    override fun getFile(id: Int) = id and MASK

    override fun readLoop(definition: AnimationFrameDefinition, buffer: Reader) {
        definition.read(-1, buffer)
    }

    override fun AnimationFrameDefinition.read(opcode: Int, buffer: Reader) {
        header = buffer.readUnsignedByte()
        baseId = buffer.readUnsignedShort()
        val declared = buffer.readUnsignedByte()
        // A declared 0 with a payload behind it is 256 written into a byte; see WRAPPED_COUNT.
        val count = if (declared == 0 && buffer.readableBytes() > 0) {
            AnimationFrameDefinition.WRAPPED_COUNT
        } else {
            declared
        }
        flags = IntArray(count) { buffer.readUnsignedByte() }
        var components = 0
        for (flag in flags) {
            for (bit in COMPONENTS) {
                if (flag and bit != 0) {
                    components++
                }
            }
        }
        val read = IntArray(components)
        var wide: IntArrayList? = null
        for (index in 0 until components) {
            val start = buffer.position()
            read[index] = buffer.readSignedSmart()
            // The two byte form can hold a one byte value; only the file says which it used.
            if (buffer.position() - start == 2 && read[index] in NARROW) {
                (wide ?: IntArrayList().also { wide = it }).add(index)
            }
        }
        values = read
        wideValues = wide?.toIntArray()
        check(buffer.readableBytes() == 0) {
            "Animation frame $id has ${buffer.readableBytes()} bytes past its $components transform values."
        }
    }

    companion object {
        /** How many low bits of a frame's definition id hold its file id within the frame set. */
        const val BITS = 16

        private const val MASK = (1 shl BITS) - 1

        /** The flag bits that each ask for one signed smart, in the order they are written. */
        private val COMPONENTS = intArrayOf(0x1, 0x2, 0x4)

        /** The values the one byte smart form holds. */
        private val NARROW = -64..63
    }
}
