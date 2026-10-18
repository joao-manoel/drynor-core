package org.drynor.cs2.source

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import org.drynor.cs2.Cs2CompileException
import org.drynor.cs2.Cs2Compiler
import org.drynor.cs2.Cs2Corpus
import org.drynor.cs2.Cs2Declarations
import org.drynor.cs2.Cs2Layout
import org.drynor.cs2.Cs2Names
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.source.ArchiveMetadata
import world.gregs.voidps.cache.source.SourceFiles
import world.gregs.voidps.cache.source.codec.Owner
import world.gregs.voidps.cache.source.codec.PackedArchive
import world.gregs.voidps.cache.source.codec.SourceArchive
import world.gregs.voidps.cache.source.codec.SourceCodec
import world.gregs.voidps.cache.source.codec.SourceFile
import world.gregs.voidps.cache.source.codec.UnpackedArchive

/**
 * Index 12 as TypeScript: the cache source tree's clientscripts codec.
 *
 * The `clientscripts/` directory of a source tree is exactly the folder `cs2 decompile-all`
 * writes and the CS2 editor opens - `<category>/<name>.ts` per script, named by its gameval name
 * through `gamevals/clientscript.json`, plus the project files a TypeScript editor needs
 * (`cs2.d.ts`, `gamevals.d.ts`, `vars.d.ts`, `tsconfig.json`) and the `dead-code.md` report.
 * Editing a script therefore means editing the TypeScript, and the server compiles it back to
 * bytecode when it rebuilds the cache; see `docs/cache-source.md` and `docs/cs2-editor.md`.
 *
 * ### Parity is functional
 *
 * Every script is compiled from its `.ts` on every pack: what the tree ships for index 12 is the
 * compiler's output and nothing else. The decompiler normalises - it drops unreachable code, folds
 * initialisers into declarations and lays branches out canonically - so 1,065 of the 6,569 scripts
 * compile back to *equivalent* but different bytes, and the other 5,504 come back exactly. That is
 * what [functional] declares, and it is why `cache verify` counts those 1,065 as *recompiled*
 * rather than different.
 *
 * Nothing is kept beside a script to make up the difference: [unpack] writes the `.ts` and nothing
 * else, no archive records a `pristine` guard, and a `<name>.ts.pristine` left in a tree by an
 * older unpack is an input of nothing ([files], [owner]) - deleting one changes no build.
 *
 * ### The two directions do not need the same things
 *
 * **[pack] reads nothing but the tree.** Compiling one script needs every other script's
 * signature and the param types, and a decompiled folder states all of it - each `.ts` carries
 * its own `function ...(): [number, string]` and `cs2.d.ts` carries the params
 * ([org.drynor.cs2.Cs2SourceContext]) - so [packIndex] builds a [Cs2Compiler.fromSources] and no
 * [Cache] is involved. That is what lets a server with `CACHE_SOURCE_PATH` set build a tree whose
 * clientscripts changed: the cache a corpus analysis would read is the one that build is
 * producing.
 *
 * **[unpack] needs the cache it is unpacking.** Decompiling is the direction where the bytes are
 * all there is, and a script's argument and return *order* is only recoverable from what its
 * callers push, so the whole corpus has to be analysed first ([org.drynor.cs2.Cs2Analyzer]).
 * [SourceCodec] hands a codec one archive at a time and no cache, so [cache] is the seam: it
 * defaults to [Cache.get], which is right for every tool and every test, because unpacking a
 * cache means having one.
 */
object Cs2SourceCodec : SourceCodec {

    /** `.ts` - what a script's source is stored as. */
    const val EXTENSION = ".ts"

    /** Index 12's archives hold one file each, and it is always file 0. */
    private const val FILE = 0

    /**
     * Where the corpus is analysed from when [unpack] runs. Replaceable so a caller unpacking a
     * cache other than [Cache.get]'s can say which. Nothing in [pack] reads it.
     */
    @Volatile
    var cache: () -> Cache = { Cache.get() }

    override val id: String
        get() = "cs2-source"

    /**
     * Index 12 is reproduced functionally rather than byte for byte, which is a property of the
     * whole index and not of one archive.
     */
    override val functional: Boolean
        get() = true

    /**
     * True, because the framework's check only counts the archives a pristine sidecar had to
     * rescue and this codec keeps none. Saying false would compile all 6,569 scripts a second
     * time during an unpack to rediscover what [functional] already states.
     */
    override fun exact(archive: Int): Boolean = true

    /** A script is one file and its name carries the script, not the file id. */
    override fun fileIdsFromMetadata(archive: Int): Boolean = true

    // ------------------------------------------------------------------------- unpack

    override fun unpack(directory: Path, archive: SourceArchive): UnpackedArchive {
        check(archive.files.size == 1) {
            "Clientscript ${archive.archive} has ${archive.files.size} files; index " +
                "${Index.CLIENT_SCRIPTS} holds one script per archive."
        }
        val state = state(directory)
        val bytes = archive.files[FILE]
        val decompiled = state.corpus.decompileScript(archive.archive, bytes, state.types)
            ?: throw IOException("Clientscript ${archive.archive} could not be decompiled from its ${bytes.size} bytes.")
        val path = Cs2Layout.pathOf(archive.archive)
        return UnpackedArchive(listOf(SourceFile(path, decompiled.source.toByteArray(Charsets.UTF_8))))
    }

    /**
     * The five project files, written once after every archive of the index.
     *
     * None of them is a function of one archive: `vars.d.ts` only knows a variable exists because
     * some script uses it, `cs2.d.ts` carries argument kinds and param types learned from all
     * 6,569 scripts at once, and `dead-code.md` is a report over the corpus. Returning them here
     * is also how they are *claimed*, so the unpacker's prune leaves them alone.
     */
    override fun unpackIndex(directory: Path, index: Int, archives: IntArray): List<SourceFile> {
        // An index with no archives has no corpus to decompile, and asking for one would open a
        // cache to analyse nothing. Nothing in 727 hits this; a cache without index 12 would.
        if (archives.isEmpty()) {
            return emptyList()
        }
        return state(directory).projectFiles.map { (name, body) ->
            SourceFile(name, body.toByteArray(Charsets.UTF_8))
        }
    }

    // --------------------------------------------------------------------------- pack

    /**
     * Prepares the folder's compiler for the archives about to be packed.
     *
     * Two things happen here rather than per archive. The compiler's context is a scan of every
     * script's signature, which must be done once and shared - a build repacking three scripts
     * must not pay for it three times - and it has to be *re*done when the tree has moved since
     * the last build in this process: a changed signature, a renamed variable in `vars.d.ts` or a
     * new script all change what the next compile resolves. So the folder's state is dropped here
     * and rebuilt lazily by the first archive that actually compiles something; the framework only
     * calls this when there is something to pack.
     */
    override fun packIndex(directory: Path, index: Int, archives: IntArray, changedIndexFiles: List<String>) {
        state(directory).invalidateCompiler()
    }

    override fun pack(directory: Path, archive: Int, metadata: ArchiveMetadata): PackedArchive {
        val file = sourceFile(directory, archive)
        if (!Files.isRegularFile(file)) {
            throw IOException("Clientscript $archive has no source at ${file.toAbsolutePath()}.")
        }
        val ids = metadata.fileIds()
        check(ids.size == 1 && ids[FILE] == FILE) {
            "Clientscript $archive is one file on disk but its metadata lists ${ids.joinToString()}."
        }

        val handle = file.toFile()
        val compiler = state(directory).compiler
        val declared = try {
            compiler.scriptIdOf(handle)
        } catch (e: Cs2CompileException) {
            throw IOException("${e.diagnostic}", e)
        }
        if (declared != archive) {
            throw IOException(
                "${file.toAbsolutePath()} says it is clientscript $declared but sits where " +
                    "clientscript $archive belongs; a rename moves the file and the catalog " +
                    "together, so run `cs2 apply-names` or fix the file's header."
            )
        }
        val bytes = try {
            compiler.compile(handle)
        } catch (e: Cs2CompileException) {
            throw IOException("${e.diagnostic}", e)
        }
        return PackedArchive(bytes, ids)
    }

    // -------------------------------------------------------------------------- paths

    /**
     * Which script a tree path is, resolved through the catalog.
     *
     * [SourceCodec.archiveOf] is given the path and nothing else, so the header inside the file -
     * which is what actually binds a source to a script - cannot be read here; the path is the
     * catalog's own (`<category>/<name>.ts` from `gamevals/clientscript.json`) and a rename moves
     * the file and the catalog entry together, so the two agree for anything the tools wrote.
     * [archiveOf] with a directory is the version that reads the header, for a caller that has one.
     *
     * The project files belong to no archive and answer null, as does anything else - an editor's
     * scratch file, a stray `.d.ts` - which an incremental build reports as unclaimed rather than
     * repacking something at random. The project files are still this codec's; [owner] is where
     * that is said.
     */
    override fun archiveOf(path: String): Int? {
        if (!path.endsWith(EXTENSION) || path.endsWith(DECLARATION_EXTENSION)) {
            return null
        }
        return Cs2Names.scriptIdOfPath(path) ?: legacyId(path)
    }

    /**
     * Which script the file at [path] under [directory] is, header first.
     *
     * The header survives a file being copied or moved, so it is the binding [Cs2Layout] trusts;
     * this is [archiveOf] with the one thing the interface cannot pass it. A `.ts.pristine` left
     * behind by an older unpack is bytecode rather than source and belongs to no archive, so it is
     * not read for a header.
     */
    override fun archiveOf(directory: Path, path: String): Int? {
        if (SourceFiles.isPristine(path)) {
            return null
        }
        val file = directory.resolve(path)
        if (Files.isRegularFile(file)) {
            Cs2Layout.scriptIdOf(Files.readString(file), path)?.let { return it }
        }
        return archiveOf(path)
    }

    /**
     * The project files are the index's, everything else is an archive's or nobody's.
     *
     * They are what [unpackIndex] writes, so this is the other half of the same claim: a change to
     * one of them repacks no archive - nothing in the cache is a function of it - and is handed to
     * [packIndex] instead.
     */
    override fun owner(directory: Path, path: String): Owner {
        if (path in PROJECT_FILES) {
            return Owner.Index
        }
        val archive = archiveOf(directory, path)
        return if (archive == null) Owner.None else Owner.Archive(archive)
    }

    /**
     * The `.ts` alone. A `.ts.pristine` left behind by an older unpack is an input of nothing, so
     * a build must neither be triggered by one nor read one.
     */
    override fun files(directory: Path, archive: Int): List<Path> {
        val file = sourceFile(directory, archive)
        return if (Files.exists(file)) listOf(file) else emptyList()
    }

    /**
     * Where [archive]'s source is: the catalog's path, or the numbered flat path an older dump
     * wrote where that is what is actually there.
     */
    private fun sourceFile(directory: Path, archive: Int): Path {
        val derived = directory.resolve(Cs2Layout.pathOf(archive))
        if (Files.isRegularFile(derived)) {
            return derived
        }
        val legacy = directory.resolve("${Cs2Layout.LEGACY_PREFIX}$archive$EXTENSION")
        return if (Files.isRegularFile(legacy)) legacy else derived
    }

    private fun legacyId(path: String): Int? {
        if (path.contains('/') || !path.startsWith(Cs2Layout.LEGACY_PREFIX)) {
            return null
        }
        return path.substring(Cs2Layout.LEGACY_PREFIX.length).dropLast(EXTENSION.length).toIntOrNull()
    }

    // -------------------------------------------------------------------- shared state

    /**
     * One set of shared state per directory: the corpus [unpack] decompiles from, and the compiler
     * [pack] compiles with.
     *
     * Both are lazy and both are shared, for the same reason and with different costs: analysing
     * the corpus takes seconds and scanning the folder's signatures a fraction of one, while
     * compiling a file takes well under a millisecond. So a build that repacks three scripts must
     * not pay for either three times, and a build that repacks nothing must not pay at all.
     */
    private fun state(directory: Path): State =
        states.computeIfAbsent(directory.toAbsolutePath().normalize().toString()) {
            State(directory.toAbsolutePath().normalize())
        }

    private val states = ConcurrentHashMap<String, State>()

    /**
     * Forget every directory's state and put [cache] back to its default.
     *
     * For tests, and for a tool that swapped the cache underneath a folder: the corpus is keyed by
     * directory and would otherwise still be the old cache's.
     */
    fun reset() {
        states.clear()
        cache = { Cache.get() }
    }

    private class State(val directory: Path) {

        private val resolved: Cache by lazy { cache() }

        val corpus: Cs2Corpus by lazy { Cs2Corpus(resolved) }

        val types by lazy { corpus.types() }

        /**
         * The whole-corpus decompile, done once, for the files no single script can produce.
         *
         * The pass is what fills in `vars.d.ts`: a variable is only known to be used because some
         * script uses it, so the file is a function of all 6,569 of them.
         */
        val projectFiles: Map<String, String> by lazy {
            val decompiled = corpus.decompile()
            corpus.projectFiles(decompiled, types)
        }

        /**
         * The folder's own compiler: reads its signatures and its declarations, so it honours
         * renames and needs no cache at all.
         *
         * Held behind a reference rather than a `lazy` because it has to be *droppable*: the tree
         * can move between two builds in one process, and a compiler holding the previous scan
         * would resolve a call against a signature that has since changed. [invalidateCompiler] is
         * how a pack says so, and the next [compiler] read builds a fresh one.
         */
        val compiler: Cs2Compiler
            get() = current ?: synchronized(this) {
                current ?: Cs2Compiler.fromSources(directory.toFile()).also { current = it }
            }

        @Volatile
        private var current: Cs2Compiler? = null

        fun invalidateCompiler() {
            synchronized(this) { current = null }
        }
    }

    /** The corpus-wide files, which belong to the index rather than to any archive. */
    private val PROJECT_FILES: Set<String> = setOf(
        Cs2Declarations.OPCODES_FILE,
        Cs2Declarations.GAMEVALS_FILE,
        Cs2Declarations.VARS_FILE,
        Cs2Corpus.TSCONFIG_FILE,
        Cs2Corpus.DEAD_CODE_FILE
    )

    /** A `.d.ts` is a declaration file, never a script; [Cs2Layout] draws the same line. */
    private const val DECLARATION_EXTENSION = ".d$EXTENSION"
}
