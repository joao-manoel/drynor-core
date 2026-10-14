package org.darkan.cs2

import org.darkan.core.gameval.Gameval
import org.darkan.core.gameval.GamevalCatalog
import java.io.File

/**
 * Where a decompiled clientscript lives, and how a file says which one it is.
 *
 * A script's identity is its gameval name, not a number: `[proc,quicksort]` is
 * `proc/quicksort.ts` and its header reads `// [proc,quicksort]`. The name is
 * what `gamevals/clientscript.json` maps to a cache archive id, so the folder,
 * the file name and the header all say the same thing and the catalog turns it
 * into the id the bytecode is written to.
 *
 * Two consequences worth stating:
 *
 *  - **Renaming a script in the catalog moves its file.** `cs2 apply-names` does
 *    the move; nothing else may, or the path would say one thing and the catalog
 *    another.
 *  - **A name the catalog no longer has does not resolve.** A file left behind by
 *    a hand-edited catalog fails to compile with a message naming it, rather than
 *    quietly landing on some other id.
 *
 * The numbered flat layout an older dump used - `clientscript-<id>.ts` with a
 * `// clientscript <id>` header - is still read, so an existing folder keeps
 * working; it is never written.
 */
object Cs2Layout {

    /** The path a script's source file goes at, relative to the project folder. */
    fun pathOf(scriptId: Int): String =
        Cs2Names.scriptPaths()[scriptId] ?: "${Cs2Names.UNCATEGORISED}/${headerOf(scriptId)}.ts"

    fun fileFor(folder: File, scriptId: Int): File = File(folder, pathOf(scriptId))

    /**
     * The header line that binds a file to a script: its catalog name, verbatim.
     * An id the catalog somehow lacks falls back to the placeholder that catalog
     * would have given it, so the name is still the catalog's own formula.
     */
    fun headerOf(scriptId: Int): String =
        Gameval.rawName(Gameval.CLIENTSCRIPT, scriptId)
            ?: GamevalCatalog.placeholder(Gameval.CLIENTSCRIPT, scriptId.toString())

    /** How many `../` a file at [scriptId]'s path needs to reach the folder root. */
    fun referencePrefix(scriptId: Int): String =
        "../".repeat(pathOf(scriptId).count { it == '/' })

    // ------------------------------------------------------------- resolution

    /**
     * Which script a source file is, read from the file itself.
     *
     * The header wins, because it survives the file being copied or moved: a
     * `// clientscript <id>` from an older dump first, then the gameval name this
     * layout writes. Only a file with no header at all falls back to its path,
     * which is what lets a hand-made file work without one.
     *
     * A header that names a script the catalogs no longer have resolves to null
     * rather than dropping through to the path. The two would have to disagree for
     * that to matter, and picking the path there would compile the file into
     * whichever archive it happened to be filed under - the exact silent
     * mis-binding this layout has to rule out.
     */
    fun scriptIdOf(source: String, relativePath: String?): Int? {
        NUMERIC_HEADER.find(source)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        val header = gamevalHeader(source)
        if (header != null) return Cs2Names.scriptIdOfRawName(header)
        return relativePath?.let { Cs2Names.scriptIdOfPath(it) }
    }

    /** The catalog name a file's header claims, whether or not it still resolves. */
    fun gamevalHeader(source: String): String? = GAMEVAL_HEADER.find(source)?.groupValues?.get(1)

    /** [file] relative to [folder] in `a/b.ts` form, or null when it is not inside it. */
    fun relativePath(folder: File, file: File): String? {
        val root = folder.absoluteFile.normalize().path
        val path = file.absoluteFile.normalize().path
        if (!path.startsWith("$root${File.separatorChar}")) return null
        return path.substring(root.length + 1).replace(File.separatorChar, '/')
    }

    /**
     * True when [file] is one of the folder's scripts.
     *
     * Anything ending `.d.ts` is a declaration file, and everything else ending
     * `.ts` is a script wherever it sits - a folder is free to grow categories
     * this build has never heard of.
     */
    fun isScriptFile(file: File): Boolean =
        file.name.endsWith(".ts") && !file.name.endsWith(".d.ts")

    /** Every script file in [folder], nested categories included, in path order. */
    fun scriptFiles(folder: File): List<File> {
        if (!folder.isDirectory) return emptyList()
        val out = ArrayList<File>(8192)
        fun walk(directory: File, depth: Int) {
            val entries = directory.listFiles() ?: return
            for (entry in entries.sortedBy { it.name }) {
                when {
                    // One level of category folders is all the layout uses; the
                    // bound stops a stray symlink or a build output turning a scan
                    // of the folder into a scan of the disk.
                    entry.isDirectory -> if (depth < MAX_DEPTH) walk(entry, depth + 1)
                    isScriptFile(entry) -> out.add(entry)
                }
            }
        }
        walk(folder, 0)
        return out
    }

    /** Script files sitting in the numbered flat layout an older dump wrote. */
    fun legacyFiles(folder: File): List<File> =
        folder.listFiles().orEmpty().filter {
            it.isFile && LEGACY_NAME.matches(it.name)
        }.sortedBy { it.name }

    /**
     * Moves a script's file to where the catalog now puts it.
     *
     * Empty category folders are cleared up behind the move, so a category that
     * loses its last script does not linger as an empty directory the next scan
     * has to walk.
     */
    fun move(from: File, to: File): Boolean {
        if (from.absoluteFile.normalize() == to.absoluteFile.normalize()) return false
        to.parentFile?.mkdirs()
        if (!from.renameTo(to)) {
            to.writeBytes(from.readBytes())
            from.delete()
        }
        val parent = from.parentFile
        if (parent != null && parent.isDirectory && parent.list().orEmpty().isEmpty()) parent.delete()
        return true
    }

    private const val MAX_DEPTH = 4

    /** The file name prefix the numbered flat layout used. */
    const val LEGACY_PREFIX = "clientscript-"

    private val LEGACY_NAME = Regex("""^clientscript-\d+\.ts$""")

    private val NUMERIC_HEADER = Regex("""^// clientscript (\d+)$""", RegexOption.MULTILINE)

    /**
     * `// [proc,quicksort]`, or `// cs2_2` for a script with no proven name.
     *
     * Deliberately narrow: a bracketed name or a bare identifier and nothing else,
     * so none of the other `//` lines a file carries - the triple-slash references,
     * `// name: ...` - can be read as a binding.
     */
    private val GAMEVAL_HEADER = Regex("""^// (\[[^\]\n]*\]|\w+)$""", RegexOption.MULTILINE)
}
