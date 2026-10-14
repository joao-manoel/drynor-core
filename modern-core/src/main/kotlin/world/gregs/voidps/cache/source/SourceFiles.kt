package world.gregs.voidps.cache.source

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * The handful of file operations the unpacker, packer, builder and the codecs all need.
 *
 * Public because a codec is not the only thing that has to name a pristine sidecar or hash a file
 * the way `index.json` records it: `cache status` reads the same guards, and duplicating either
 * rule is how the two drift apart.
 */
object SourceFiles {

    /**
     * The sidecar a codec keeps a file's original bytes in when it cannot reproduce them.
     *
     * The name is the framework's, not any codec's: `<name>.pristine` sits beside `<name>`, the
     * SHA-256 of `<name>` is the guard `index.json` records under `pristine`, and a pack emits the
     * sidecar while the guard still holds. See `docs/cache-source.md`, "The pristine fallback".
     */
    const val PRISTINE_EXTENSION = ".pristine"

    private val hex = "0123456789abcdef".toCharArray()

    /** Whether [name] - a file name or a `/` separated path - names a pristine sidecar. */
    fun isPristine(name: String): Boolean = name.endsWith(PRISTINE_EXTENSION)

    /** `<name>.pristine` beside the codec relative path [path]. */
    fun pristine(path: String): String = "$path$PRISTINE_EXTENSION"

    /** `<name>.pristine` beside [file]. */
    fun pristine(file: Path): Path = file.resolveSibling("${file.fileName}$PRISTINE_EXTENSION")

    /** The path a pristine sidecar guards, or null when [path] is not one. */
    fun guarded(path: String): String? =
        if (isPristine(path)) path.dropLast(PRISTINE_EXTENSION.length) else null

    /** The file a pristine sidecar guards, or null when [file] is not one. */
    fun guarded(file: Path): Path? {
        val name = guarded(file.fileName.toString()) ?: return null
        return file.resolveSibling(name)
    }

    /**
     * Write [bytes] to [path], creating the parent directories, unless the file is already
     * exactly that. Returns whether anything was written.
     *
     * Re-unpacking an unchanged cache has to leave the tree alone - a rewritten file is a
     * changed mtime, which is a rebuilt archive on the next build and a dirty file to every tool
     * that watches the tree - so the comparison is worth the read it costs.
     */
    fun write(path: Path, bytes: ByteArray): Boolean {
        if (unchanged(path, bytes)) {
            return false
        }
        val parent = path.parent
        if (parent != null) {
            Files.createDirectories(parent)
        }
        Files.write(path, bytes)
        return true
    }

    private fun unchanged(path: Path, bytes: ByteArray): Boolean {
        try {
            if (Files.size(path) != bytes.size.toLong()) {
                return false
            }
        } catch (e: IOException) {
            return false
        }
        return Files.readAllBytes(path).contentEquals(bytes)
    }

    /** SHA-256 of [bytes] as lower case hex. */
    fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /** SHA-256 of [path]'s contents as lower case hex. */
    fun sha256(path: Path): String = sha256(Files.readAllBytes(path))

    /** The first [bytes] bytes of [value]'s SHA-256, as lower case hex. */
    fun digest(value: String, bytes: Int = 8): String =
        hex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).copyOf(bytes))

    fun hex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (index in bytes.indices) {
            val value = bytes[index].toInt() and 0xff
            out[index * 2] = hex[value shr 4]
            out[index * 2 + 1] = hex[value and 0xf]
        }
        return String(out)
    }

    /**
     * Delete [path] and then every directory above it, up to but not including [stop], that the
     * deletion left empty.
     */
    fun delete(path: Path, stop: Path) {
        Files.deleteIfExists(path)
        var parent = path.parent
        while (parent != null && parent != stop && parent.startsWith(stop)) {
            val empty = Files.list(parent).use { !it.findAny().isPresent }
            if (!empty) {
                return
            }
            Files.deleteIfExists(parent)
            parent = parent.parent
        }
    }
}
