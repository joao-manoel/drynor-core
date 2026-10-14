package world.gregs.voidps.cache

/**
 * Index 30's archive names, which are a formula rather than a catalogue - see
 * `docs/cache-archive-names.md`.
 *
 * The client builds `<os>/<arch>/<library file name>` in `NativeLibraryLoader`: the operating
 * system and architecture from its own system properties, and the file name from the platform's
 * spelling of a bare library name (`hw3d.dll`, `libhw3d.so`, `libhw3d.dylib`). Sixty strings come
 * out of the three lists, and the 727 cache's thirty-three named archives are all among them.
 *
 * [name] is the way back: a reference table records only the hash, so an archive's name is
 * recovered by hashing every candidate once and looking the hash up.
 */
object NativeLibrary {

    /** The operating systems the client's loader spells, in its own order. */
    val operatingSystems = listOf("windows", "linux", "macos")

    /** The architectures it spells. `universal` is its fallback, and macOS's in practice. */
    val architectures = listOf("x86", "x86_64", "ppc", "universal")

    /** The libraries it asks for. `jagtheora` is asked for and is not in the 727 cache. */
    val libraries = listOf("hw3d", "sw3d", "jaggl", "jaclib", "jagdx", "jagtheora")

    private val byHash: Map<Int, String> = buildMap {
        for (os in operatingSystems) {
            for (architecture in architectures) {
                for (library in libraries) {
                    val name = path(os, architecture, library)
                    put(name.hashCode(), name)
                }
            }
        }
    }

    /** `windows/x86/hw3d.dll` - the archive name of [library] for [os] and [architecture]. */
    fun path(os: String, architecture: String, library: String): String =
        "$os/$architecture/${file(os, library)}"

    /** The platform's spelling of a bare library name. */
    fun file(os: String, library: String): String = when (os) {
        "windows" -> "$library.dll"
        "linux" -> "lib$library.so"
        "macos" -> "lib$library.dylib"
        else -> throw IllegalArgumentException("No native library naming rule for '$os'.")
    }

    /** The name whose hash is [hash], or null when no name the formula builds hashes to it. */
    fun name(hash: Int): String? = byHash[hash]

    /** Every name the formula builds, which is what [name] looks through. */
    fun names(): Collection<String> = byHash.values
}
