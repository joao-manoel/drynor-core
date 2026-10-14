package world.gregs.voidps.cache.definition.data

import world.gregs.voidps.cache.Definition

/**
 * The huffman code lengths chat is compressed with: index 10, archive `huffman`, one file.
 *
 * The file is exactly one unsigned byte per cp1252 symbol - the number of bits that symbol's code takes -
 * and the tree itself is derived from those lengths alone ([world.gregs.voidps.cache.secure.Huffman]).
 * So the file is the lengths and there is nothing else in it: 256 numbers, editable, with the shorter
 * codes on the letters chat actually uses.
 */
data class HuffmanTableDefinition(
    override var id: Int = -1,
    var lengths: IntArray = IntArray(0)
) : Definition {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as HuffmanTableDefinition
        return id == other.id && lengths.contentEquals(other.lengths)
    }

    override fun hashCode(): Int = 31 * id + lengths.contentHashCode()
}
