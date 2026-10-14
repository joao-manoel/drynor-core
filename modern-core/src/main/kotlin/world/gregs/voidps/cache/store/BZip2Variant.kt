package world.gregs.voidps.cache.store

/**
 * Which bzip2 encoder reproduces a container's payload.
 *
 * Every bzip2 container the 727 cache ships was produced at block size 1, but not all of them by
 * the same program: 3,794 of the 3,796 come back out of libbzip2 1.0.8 - which
 * [world.gregs.voidps.cache.compress.BZip2Encoder] is a port of - and the reference tables of
 * index 2 and index 9 come back only out of Apache commons-compress, whose block sorter makes
 * different choices. The difference is invisible to a decompressor and irrelevant to anything but
 * byte parity, which is exactly what the source tree promises, so the variant is detected on
 * unpack and recorded when it is not the default.
 */
enum class BZip2Variant {
    LIBBZIP2,
    COMMONS;

    companion object {
        /** The name recorded in `index.json`; [LIBBZIP2] is the default and never written. */
        fun of(name: String): BZip2Variant = when (name.lowercase()) {
            "libbzip2" -> LIBBZIP2
            "commons" -> COMMONS
            else -> throw IllegalArgumentException("Unknown bzip2 variant '$name'.")
        }
    }

    /** The lower case name used in `index.json`. */
    val id: String
        get() = name.lowercase()
}
