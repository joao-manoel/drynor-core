package org.drynor.cs2

import java.io.File
import org.drynor.cs2.compile.Cs2CodeGen
import org.drynor.cs2.compile.Cs2Lexer
import org.drynor.cs2.compile.Cs2Parser
import world.gregs.voidps.cache.Cache

/**
 * A folder of decompiled clientscripts, and the path from a file on disk back to
 * bytecode.
 *
 * The folder is the same shape `cs2 decompile-all` writes: `<category>/<name>.ts`
 * per script - `proc/quicksort.ts` - plus `cs2.d.ts` for the opcode signatures,
 * `gamevals.d.ts` for the id constants and `vars.d.ts` binding renamed variables
 * to their ids. Two facts about that layout drive everything here:
 *
 *  - **The gameval name is the script's identity**, not the function name.
 *    `gamevals/clientscript.json` maps it to the archive id, and the header and
 *    the path both spell it out ([Cs2Layout]), so a renamed function still
 *    compiles to the right script.
 *  - **Renamed identifiers only resolve through the folder's declarations.**
 *    [Cs2SymbolTable.read] scans `vars.d.ts`, `gamevals.d.ts` and every script
 *    header, so it is folder-scoped state that has to be re-read whenever those
 *    files change.
 *
 * Compilation happens on whichever thread asks - in practice the hot reload
 * watcher thread - so the mutable state here ([folder], [symbols]) is volatile
 * and replaced wholesale rather than mutated in place.
 */
class Cs2SourceProject private constructor(
    /**
     * What compiling one file has to know about the others: every callee's signature, and which
     * params yield strings.
     *
     * Either the whole-cache analysis ([Cs2Analyzer]) or the folder's own declarations
     * ([Cs2SourceContext]). Nothing in the compile path can tell the difference, which is the
     * point: a cache source tree is packed by a server that has no other cache to analyse.
     */
    private val context: Cs2Context,
    /** Index 12's bytes, for [verify]; null for a project that only compiles. */
    cache: Cache?,
    /** The analysis, where there is one: decompiling into the folder needs it, compiling does not. */
    private val analyzer: Cs2Analyzer?,
    folder: File,
) {

    /** A project over a cache: compiles, verifies against it and decompiles out of it. */
    constructor(cache: Cache, analyzer: Cs2Analyzer, folder: File = File(DEFAULT_FOLDER)) :
        this(analyzer, cache, analyzer, folder)

    /**
     * A project that only compiles, from [folder] and [context] alone.
     *
     * There is no cache, so [verify] has nothing to compare against and [exportSource] has nothing
     * to decompile; both say so. Everything else - [compile], [compileFile], [scriptIdOf], the
     * symbol table - works exactly as it does with one.
     */
    constructor(context: Cs2Context, folder: File) : this(context, null, null, folder)

    @Volatile
    var folder: File = folder
        private set

    @Volatile
    var symbols: Cs2SymbolTable = Cs2SymbolTable.EMPTY
        private set

    private val scripts = cache?.let { Cs2Cache(it) }

    init {
        reloadSymbols()
    }

    /**
     * Points the project at a different folder and re-reads its declarations.
     * Returns the folder actually in use, which is unchanged when it is missing.
     */
    fun useFolder(directory: File): File {
        if (!directory.isDirectory) return folder
        folder = directory
        reloadSymbols()
        return folder
    }

    /** Re-reads `vars.d.ts` and the per-script name bindings. */
    fun reloadSymbols() {
        symbols = try {
            if (folder.isDirectory) Cs2SymbolTable.read(folder) else Cs2SymbolTable.EMPTY
        } catch (e: Exception) {
            Cs2SymbolTable.EMPTY
        }
    }

    // -------------------------------------------------------------- diagnostics

    /**
     * A compile failure with somewhere to jump to.
     *
     * The lexer and parser signal problems with plain `error(...)`, so there is no
     * structured position to read: [line] and [column] are recovered from the
     * message and the token stream on a best-effort basis (see [locate]) and
     * default to the top of the file when nothing is recoverable.
     */
    data class Diagnostic(
        val file: File?,
        val scriptId: Int,
        val message: String,
        val line: Int,
        val column: Int,
        /** True when [line] and [column] were actually recovered rather than guessed. */
        val located: Boolean,
    ) {
        /** `file:line:col`, the form editors and terminals both understand. */
        val location: String
            get() = "${file?.path ?: "<unknown>"}:$line:$column"

        override fun toString(): String = "$location: $message"
    }

    /** A successful compile: the script, and the bytes it encodes to. */
    class Compiled(
        val scriptId: Int,
        val file: File,
        val script: Cs2Script,
        val bytes: ByteArray,
    )

    sealed class CompileResult {
        class Ok(val result: Compiled) : CompileResult()
        class Failed(val error: Diagnostic) : CompileResult()

        val compiled: Compiled? get() = (this as? Ok)?.result
        val diagnostic: Diagnostic? get() = (this as? Failed)?.error
    }

    // ------------------------------------------------------------------- paths

    /**
     * Where a script's source belongs: `<category>/<name>.ts`, from its gameval
     * name. A folder written by an older build still holds it at the numbered flat
     * path, so that is accepted where it is what actually exists.
     */
    fun fileFor(scriptId: Int): File {
        val derived = Cs2Layout.fileFor(folder, scriptId)
        if (derived.isFile) return derived
        val legacy = folder.resolve("${Cs2Layout.LEGACY_PREFIX}$scriptId.ts")
        return if (legacy.isFile) legacy else derived
    }

    fun hasSource(scriptId: Int): Boolean = fileFor(scriptId).isFile

    /** The id a file declares - its gameval name, or the path it sits at. */
    fun scriptIdOf(file: File): Int? = try {
        Cs2Layout.scriptIdOf(file.readText(), Cs2Layout.relativePath(folder, file))
    } catch (e: Exception) {
        null
    }

    /** True when a file is one of the folder's scripts rather than a declaration file. */
    fun isScriptFile(file: File): Boolean = Cs2Layout.isScriptFile(file)

    /** True when a file changing invalidates the symbol table for the whole folder. */
    fun isDeclarationFile(file: File): Boolean =
        file.name == Cs2Declarations.VARS_FILE ||
            file.name == Cs2Declarations.OPCODES_FILE ||
            file.name == Cs2Declarations.GAMEVALS_FILE

    /** Every script id the folder has a source file for, ascending. */
    fun sourceIds(): List<Int> =
        Cs2Layout.scriptFiles(folder).mapNotNull { scriptIdOf(it) }.distinct().sorted()

    fun cachedBytes(scriptId: Int): ByteArray? = try {
        scripts?.raw(scriptId)
    } catch (e: Exception) {
        null
    }

    // ----------------------------------------------------------------- compile

    fun compile(scriptId: Int): CompileResult {
        val file = fileFor(scriptId)
        if (!file.isFile) {
            return CompileResult.Failed(
                Diagnostic(file, scriptId, "no source file", 1, 1, located = false),
            )
        }
        return compileFile(file)
    }

    /**
     * Parses and lowers one file.
     *
     * The [context] is used twice over: as the parser's param-type oracle (a
     * `*_PARAM` opcode pushes an int or a string depending on the param's declared
     * type, which the source cannot say) and as the code generator's context, so a
     * statement-position call discards exactly what its callee leaves behind.
     * Whether it came from a cache or from the folder's own declarations makes no
     * difference here - see [Cs2SourceContext].
     */
    fun compileFile(file: File): CompileResult {
        val source = try {
            file.readText()
        } catch (e: Exception) {
            return CompileResult.Failed(
                Diagnostic(file, -1, "cannot read: ${e.message}", 1, 1, located = false),
            )
        }
        val relative = Cs2Layout.relativePath(folder, file)
        val headerId = Cs2Layout.scriptIdOf(source, relative) ?: -1
        // A name that means one id here and another in the catalogs would compile
        // either way, so the folder is refused rather than read one of the two ways.
        symbols.refusal()?.let {
            return CompileResult.Failed(Diagnostic(file, headerId, it, 1, 1, located = false))
        }
        return try {
            val function = Cs2Parser(source, context::paramIsString, symbols, relative, context::returnTypes).parse()
            val script = Cs2CodeGen(function, context).generate()
            CompileResult.Ok(Compiled(function.scriptId, file, script, Cs2Codec.encode(script)))
        } catch (e: Exception) {
            val message = e.message ?: e::class.simpleName ?: "compile failed"
            CompileResult.Failed(locate(file, headerId, source, message))
        }
    }

    // ------------------------------------------------------------------ verify

    /**
     * Whether a file still compiles to the bytes the cache holds.
     *
     * Worth doing before editing: the decompiler round-trips most scripts exactly
     * but not all of them, and knowing an untouched file is byte-exact is what
     * separates "my edit broke this" from "this script never round-tripped".
     */
    data class VerifyResult(
        val scriptId: Int,
        val identical: Boolean,
        val compiledSize: Int,
        val cachedSize: Int,
        val diagnostic: Diagnostic?,
    ) {
        val summary: String
            get() = when {
                diagnostic != null -> "compile failed: ${diagnostic.message}"
                cachedSize == 0 -> "compiled $compiledSize bytes; nothing in the cache to compare against"
                identical -> "byte-identical to the cached script ($compiledSize bytes)"
                else -> "DIFFERS from the cached script ($compiledSize bytes vs $cachedSize)"
            }
    }

    fun verify(scriptId: Int): VerifyResult {
        val result = compile(scriptId)
        val compiled = result.compiled
            ?: return VerifyResult(scriptId, false, 0, 0, result.diagnostic)
        val original = cachedBytes(compiled.scriptId)
        return VerifyResult(
            scriptId = compiled.scriptId,
            identical = original != null && compiled.bytes.contentEquals(original),
            compiledSize = compiled.bytes.size,
            cachedSize = original?.size ?: 0,
            diagnostic = null,
        )
    }

    // ------------------------------------------------------------------ export

    data class ExportResult(val file: File?, val message: String) {
        val ok: Boolean get() = file != null
    }

    /**
     * Decompiles a cached script into the folder so it can be edited.
     *
     * Uses the same structurer and emitter the CLI does; where the structurer
     * cannot reproduce a script's branch layout exactly the faithful rendering
     * can, so that is tried as a fallback rather than emitting source that will
     * not compile back.
     */
    fun exportSource(scriptId: Int, overwrite: Boolean = false): ExportResult {
        val target = fileFor(scriptId)
        if (target.isFile && !overwrite) {
            return ExportResult(target, "already present at ${target.path}")
        }
        val analysis = analyzer
            ?: return ExportResult(null, "this project has no cache to decompile out of")
        val script = analysis.cachedScript(scriptId)
            ?: return ExportResult(null, "no clientscript $scriptId in the cache")
        val source = try {
            emit(analysis, script, scriptId, faithful = false)
        } catch (e: Exception) {
            try {
                emit(analysis, script, scriptId, faithful = true)
            } catch (fallback: Exception) {
                return ExportResult(null, "decompile failed: ${fallback.message}")
            }
        }
        return try {
            target.parentFile?.mkdirs()
            target.writeText(source)
            reloadSymbols()
            ExportResult(target, "wrote ${target.path}")
        } catch (e: Exception) {
            ExportResult(null, "cannot write ${target.path}: ${e.message}")
        }
    }

    private fun emit(analysis: Cs2Analyzer, script: Cs2Script, scriptId: Int, faithful: Boolean): String =
        Cs2Emitter(analysis.types().apply(Cs2Structurer(script, scriptId, analysis, faithful).structure())).emit()

    /**
     * Writes the ambient declarations a fresh folder needs for the editor to
     * resolve opcode names. `vars.d.ts` is deliberately left alone: it is produced
     * by a whole-cache decompile and carries the developer's renames.
     */
    fun exportDeclarations(): ExportResult = try {
        folder.mkdirs()
        folder.resolve(Cs2Declarations.OPCODES_FILE)
            .writeText(Cs2Declarations.generate(stringParams = analyzer?.stringParams() ?: emptySet()))
        folder.resolve(Cs2Declarations.GAMEVALS_FILE).writeText(Cs2Declarations.gamevals())
        folder.resolve("tsconfig.json").writeText(Cs2Declarations.tsconfig())
        ExportResult(
            folder,
            "wrote ${Cs2Declarations.OPCODES_FILE}, ${Cs2Declarations.GAMEVALS_FILE} " +
                "and tsconfig.json to ${folder.path}",
        )
    } catch (e: Exception) {
        ExportResult(null, "cannot write declarations: ${e.message}")
    }

    // --------------------------------------------------------------- positions

    /**
     * Best-effort position for a compiler message.
     *
     * Neither the lexer nor the parser carries a position into its errors, but
     * both leave a trail:
     *
     *  - the lexer's own message ends in `on line N`, and every parser message
     *    built from a [org.drynor.cs2.compile.Token] ends in `at line N`,
     *    because that is what `Token.toString` prints;
     *  - the rest quote the offending identifier (`Unknown identifier 'foo'`),
     *    which can be found in the token stream, giving the line of its first
     *    occurrence.
     *
     * The column is then the offset of that text on the line, or the first
     * non-blank column. Both handles are all that is available without editing the
     * lexer and parser, which are owned elsewhere.
     */
    private fun locate(file: File, scriptId: Int, source: String, message: String): Diagnostic {
        val lines = source.lines()
        val reported = LINE_IN_MESSAGE.find(message)?.groupValues?.get(1)?.toIntOrNull()
        val quoted = QUOTED.find(message)?.groupValues?.get(1)

        var line = reported
        if (line == null && quoted != null) {
            line = try {
                Cs2Lexer(source).tokenise().firstOrNull { it.text == quoted }?.line
            } catch (e: Exception) {
                null
            }
        }
        if (line == null && quoted != null) {
            val index = lines.indexOfFirst { it.contains(quoted) }
            if (index >= 0) line = index + 1
        }

        val resolved = (line ?: 1).coerceIn(1, maxOf(1, lines.size))
        val text = lines.getOrNull(resolved - 1) ?: ""
        val column = when {
            quoted != null && text.contains(quoted) -> text.indexOf(quoted) + 1
            else -> text.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) 1 else it + 1 }
        }
        return Diagnostic(file, scriptId, message, resolved, column, located = line != null)
    }

    companion object {
        /**
         * Resolved against the working directory, which for `gradle :tools:run` is
         * the repository root.
         */
        const val DEFAULT_FOLDER = "cs2-dump"

        private val LINE_IN_MESSAGE = Regex("""(?:at|on) line (\d+)""")
        private val QUOTED = Regex("""'([^']+)'""")
    }
}
