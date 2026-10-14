package world.gregs.voidps.cache.store

/**
 * The compression types a JS5 container can use.
 *
 * The 727 cache uses all three and nothing else - no LZMA (type 3) container exists in it, so
 * decoding one is an error rather than a case to support.
 */
enum class Compression(val id: Int) {
    NONE(0),
    BZIP2(1),
    GZIP(2);

    companion object {
        private val byId = arrayOfNulls<Compression>(3).also { types ->
            for (type in entries) {
                types[type.id] = type
            }
        }

        /** The type [id] came from a container header; anything but 0-2 is not a 727 container. */
        fun of(id: Int): Compression = byId.getOrNull(id)
            ?: throw IllegalArgumentException("Unsupported container compression type $id.")
    }
}
