package world.gregs.voidps.cache.store

/**
 * The files of a group and the chunk layout they were stored in.
 *
 * [chunks] has one entry per chunk, each holding that chunk's contribution to every file in
 * file id order. Handing it back to [ArchiveGroup.join] reproduces the group byte for byte.
 */
class GroupFiles(
    val files: List<ByteArray>,
    val chunks: List<IntArray>
)

/**
 * Splits and joins a decompressed archive - a *group* in JS5 terms.
 *
 * A group of one file is that file's bytes and nothing else. A group of several is
 *
 * ```
 * [file data...][i32 length delta * chunkCount * fileCount][u8 chunkCount]
 * ```
 *
 * where the deltas run chunk-major and are cumulative *within* a chunk, so the length of file
 * `f` in chunk `c` is the sum of deltas 0..f of that chunk. The data is laid out in the same
 * order: for every chunk, for every file, that chunk's slice of the file.
 *
 * Almost every multi-file group in the 727 cache uses a single chunk. Index 0 is the exception:
 * all 4,074 of its multi-file archives use three, with a split that is not derivable from
 * anything, so it is recorded and replayed rather than recomputed.
 */
object ArchiveGroup {

    /**
     * Split a decompressed [group] into its files.
     *
     * @param fileIds the archive's file ids from its reference table, ascending. Only the count
     *   is needed to parse the group; the ids are what the returned files are keyed by upstream.
     */
    fun split(group: ByteArray, fileIds: IntArray): GroupFiles {
        val fileCount = fileIds.size
        require(fileCount > 0) { "A group has at least one file." }
        if (fileCount == 1) {
            return GroupFiles(listOf(group.copyOf()), listOf(intArrayOf(group.size)))
        }
        require(group.isNotEmpty()) { "A $fileCount file group cannot be empty." }
        var tableOffset = group.size - 1
        val chunkCount = group[tableOffset].toInt() and 0xff
        require(chunkCount > 0) { "A $fileCount file group claims 0 chunks." }
        tableOffset -= chunkCount * fileCount * 4
        require(tableOffset >= 0) {
            "A $fileCount file group of ${group.size} bytes cannot hold a $chunkCount chunk table."
        }

        val chunks = ArrayList<IntArray>(chunkCount)
        val sizes = IntArray(fileCount)
        var offset = tableOffset
        for (chunk in 0 until chunkCount) {
            val lengths = IntArray(fileCount)
            var length = 0
            for (file in 0 until fileCount) {
                length += getInt(group, offset)
                offset += 4
                lengths[file] = length
                sizes[file] += length
            }
            chunks.add(lengths)
        }

        val files = List(fileCount) { ByteArray(sizes[it]) }
        val written = IntArray(fileCount)
        var position = 0
        for (chunk in chunks) {
            for (file in 0 until fileCount) {
                val length = chunk[file]
                require(position + length <= tableOffset) {
                    "A $fileCount file group's chunk table describes more data than the group holds."
                }
                System.arraycopy(group, position, files[file], written[file], length)
                position += length
                written[file] += length
            }
        }
        return GroupFiles(files, chunks)
    }

    /**
     * Join [files] back into a group.
     *
     * @param chunks the recorded layout, whose per-chunk lengths must add up to each file's
     *   size. Null writes a single chunk, which is what every index but 0 uses.
     */
    fun join(files: List<ByteArray>, chunks: List<IntArray>? = null): ByteArray {
        require(files.isNotEmpty()) { "A group has at least one file." }
        if (files.size == 1) {
            return files[0].copyOf()
        }
        val fileCount = files.size
        val layout = chunks ?: listOf(IntArray(fileCount) { files[it].size })
        require(layout.isNotEmpty() && layout.size <= 255) {
            "A group has 1 to 255 chunks, not ${layout.size}."
        }
        var total = 0
        for (chunk in layout) {
            require(chunk.size == fileCount) {
                "Chunk layout describes ${chunk.size} files but the group has $fileCount."
            }
            for (length in chunk) {
                require(length >= 0) { "A chunk length cannot be negative." }
                total += length
            }
        }
        for (file in 0 until fileCount) {
            val expected = layout.sumOf { it[file] }
            require(expected == files[file].size) {
                "Chunk layout gives file $file $expected bytes but it has ${files[file].size}."
            }
        }

        val output = ByteArray(total + layout.size * fileCount * 4 + 1)
        val read = IntArray(fileCount)
        var position = 0
        for (chunk in layout) {
            for (file in 0 until fileCount) {
                val length = chunk[file]
                System.arraycopy(files[file], read[file], output, position, length)
                position += length
                read[file] += length
            }
        }
        for (chunk in layout) {
            var previous = 0
            for (file in 0 until fileCount) {
                putInt(output, position, chunk[file] - previous)
                previous = chunk[file]
                position += 4
            }
        }
        output[position] = layout.size.toByte()
        return output
    }

    private fun getInt(data: ByteArray, offset: Int) =
        ((data[offset].toInt() and 0xff) shl 24) or
            ((data[offset + 1].toInt() and 0xff) shl 16) or
            ((data[offset + 2].toInt() and 0xff) shl 8) or
            (data[offset + 3].toInt() and 0xff)

    private fun putInt(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value shr 24).toByte()
        data[offset + 1] = (value shr 16).toByte()
        data[offset + 2] = (value shr 8).toByte()
        data[offset + 3] = value.toByte()
    }
}
