package org.drynor.cs2

import java.io.File
import world.gregs.voidps.cache.Cache

/**
 * Compiles one folder of TypeScript clientscripts to bytecode, headlessly.
 *
 * This is the entry point for code outside the tools CLI - the cache source tree packer, whose
 * index 12 is stored as exactly the folder `cs2 decompile-all` writes (see `docs/cache-source.md`).
 * Nothing here touches a window, a GL context or ImGui; the whole `:cs2` module is headless by
 * construction and depends only on `:core`.
 *
 * ```kotlin
 * val compiler = Cs2Compiler.fromSources(File(tree, "clientscripts"))
 * val bytes = compiler.compile(File(tree, "clientscripts/proc/quicksort.ts"))
 * val id = compiler.scriptIdOf(File(tree, "clientscripts/proc/quicksort.ts"))
 * ```
 *
 * ### Where the signatures come from
 *
 * Compiling one file needs the *others*: a script's return signature decides what a call site
 * discards, and a `*_PARAM` opcode pushes an int or a string depending on the param type. Neither
 * is in the file being compiled, and there are two places to get them.
 *
 *  - [fromSources] reads them out of the folder - each script's own declaration, and `cs2.d.ts`
 *    for the param types ([Cs2SourceContext]). This is what a cache source tree is packed with,
 *    and the only thing that works while a server is building its cache *from* that tree.
 *  - The [Cache] constructor analyses the corpus instead ([Cs2Analyzer.analyse]), which is what
 *    an editor holding a cache open wants, since it is also decompiling out of it.
 *
 * ### Cost, and what is shared
 *
 * Either way the work happens **once**, lazily, on first use, and is then shared by every compile:
 * seconds for the analysis, a fraction of one for the folder scan.
 * [compile] itself allocates a fresh [Cs2Parser][org.drynor.cs2.compile.Cs2Parser]
 * and [Cs2CodeGen][org.drynor.cs2.compile.Cs2CodeGen] per call and holds no state between calls,
 * so a pack can compile the folder's files in parallel: the analysis, the gameval derivations
 * ([Cs2Names]) and the folder's symbol table are all read-only once built, and each is either
 * concurrent or guarded internally.
 *
 * Compiling one file never requires the rest of the folder to compile. What it does require is the
 * folder's *declarations* - `vars.d.ts` and `gamevals.d.ts` bind renamed identifiers to ids - which
 * are read once into [Cs2SourceProject.symbols]. Call [reloadSources] after editing those, or after
 * adding or renaming script files, and the next compile picks the change up.
 *
 * ### The lower-level pieces
 *
 * The facade is deliberately thin. Where more is needed, the pipeline is public:
 *
 *  - [Cs2Analyzer] `(cache).analyse()` - the corpus analysis, and one of the two `Cs2Context`s
 *    everything downstream resolves calls through; [Cs2SourceContext] `.read(folder)` is the other;
 *  - [Cs2SourceProject] `(context, folder)` - the folder itself: paths ([Cs2Layout]),
 *    the symbol table ([Cs2SymbolTable]), per-file [Cs2SourceProject.compileFile] with a
 *    [Cs2SourceProject.Diagnostic] carrying `file:line:col`, [Cs2SourceProject.verify] against the
 *    cache and [Cs2SourceProject.exportSource] the other way;
 *  - [Cs2HotReload.sweepFolder] - the whole-folder sweep `cs2 sources` / `cs2 verify-sources` run,
 *    which compiles every source file and reports how many still match the cache byte for byte;
 *  - [Cs2Codec] - the raw index 12 file format, and [verifyCodecRoundTrip] over the whole index;
 *  - [Cs2Structurer] / [Cs2Emitter] - the other direction, bytecode to TypeScript.
 *
 * @property folder the folder of `.ts` sources this compiler reads, as handed in.
 */
class Cs2Compiler private constructor(
    val folder: File,
    /**
     * The project, built on first use.
     *
     * `by lazy` rather than an `init` block on purpose: constructing a compiler for a tree that
     * turns out to need no rebuild has to cost nothing, and building the project is where the
     * seconds are - either the corpus analysis or the scan of the folder's signatures.
     */
    project: () -> Cs2SourceProject,
) {

    /**
     * Compiles [folder] against [cache], analysing the corpus out of it.
     *
     * @param analysis where the corpus analysis comes from. The default analyses [cache] itself,
     *   which is what a caller that only compiles wants. A caller that is also decompiling the
     *   same cache passes its own [Cs2Analyzer] instead, because the analysis takes seconds and
     *   there is no reason to do it twice.
     */
    constructor(
        cache: Cache,
        folder: File,
        analysis: () -> Cs2Analyzer = { Cs2Analyzer(cache).also { it.analyse() } },
    ) : this(folder, { Cs2SourceProject(cache, analysis(), folder) })

    private val project: Cs2SourceProject by lazy(project)

    /**
     * Compiles one source file to the bytes index 12 holds.
     *
     * Throws [Cs2CompileException] when the file does not compile, or when it does not say which
     * script it is; the message is `file:line:col: what went wrong`, positioned as well as the
     * lexer and parser allow (see [Cs2SourceProject.Diagnostic]).
     */
    fun compile(file: File): ByteArray = when (val result = project.compileFile(file)) {
        is Cs2SourceProject.CompileResult.Ok -> result.result.bytes
        is Cs2SourceProject.CompileResult.Failed -> throw Cs2CompileException(result.error)
    }

    /**
     * Which script [file] is: its `// [proc,quicksort]` header run through
     * `gamevals/clientscript.json`, falling back to the path it sits at when it has no header at
     * all ([Cs2Layout.scriptIdOf]).
     *
     * Throws [Cs2CompileException] rather than returning a sentinel, because every caller of this
     * has to have an id: a file that cannot say which archive it belongs to has no safe default,
     * and picking one would silently write a script into the wrong slot. Use
     * [Cs2SourceProject.scriptIdOf] where a null is genuinely wanted.
     */
    fun scriptIdOf(file: File): Int = project.scriptIdOf(file)
        ?: throw Cs2CompileException(
            Cs2SourceProject.Diagnostic(
                file = file,
                scriptId = -1,
                message = "no clientscript header, and the path names no script in gamevals/clientscript.json",
                line = 1,
                column = 1,
                located = false,
            ),
        )

    /**
     * Where [scriptId]'s source lives, or null when the folder has no file for it.
     *
     * The path is the catalog's: `<category>/<name>.ts` from the script's gameval name. A folder
     * written by an older build still holding it at the flat `clientscript-<id>.ts` path is
     * accepted where that is what exists.
     */
    fun sourceFileOf(scriptId: Int): File? = project.fileFor(scriptId).takeIf { it.isFile }

    /** Every script id the folder has a source file for, ascending. */
    fun sourceIds(): List<Int> = project.sourceIds()

    /**
     * Re-reads `vars.d.ts`, `gamevals.d.ts` and the per-script name bindings.
     *
     * Needed after those files change on disk; the bindings are folder-scoped, so a compile using
     * a stale table would resolve a renamed identifier to the old id. Not thread-safe against
     * concurrent [compile] calls - reload between sweeps, not during one.
     */
    fun reloadSources() = project.reloadSymbols()

    companion object {

        /**
         * A compiler that reads nothing but [folder]: no cache, and no corpus analysis.
         *
         * This is what a cache source tree is packed with. The analysis exists to recover what
         * bytecode does not state - each script's argument and return order, and which params yield
         * strings - and a decompiled folder states all of it: every `.ts` carries its own
         * signature and `cs2.d.ts` carries the param types ([Cs2SourceContext]). A server building
         * its cache from a tree therefore has everything it needs, which matters because the cache
         * an analysis would read is the one that build is producing.
         *
         * Costs one lex of every script's header line rather than a decode and fixpoint over the
         * whole corpus, and is paid on the first compile rather than on construction.
         */
        fun fromSources(folder: File): Cs2Compiler =
            Cs2Compiler(folder) { Cs2SourceProject(Cs2SourceContext.read(folder), folder) }
    }
}

/** A source file that did not compile, with somewhere to jump to. */
class Cs2CompileException(val diagnostic: Cs2SourceProject.Diagnostic) :
    RuntimeException(diagnostic.toString())
