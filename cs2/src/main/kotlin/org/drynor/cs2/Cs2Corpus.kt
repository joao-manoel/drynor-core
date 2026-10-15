package org.drynor.cs2

import java.io.File
import org.drynor.cs2.ir.AndAll
import org.drynor.cs2.ir.ArrayAssign
import org.drynor.cs2.ir.ArrayDefine
import org.drynor.cs2.ir.ArrayRef
import org.drynor.cs2.ir.Binary
import org.drynor.cs2.ir.Callback
import org.drynor.cs2.ir.Compare
import org.drynor.cs2.ir.Condition
import org.drynor.cs2.ir.Cs2Function
import org.drynor.cs2.ir.Expr
import org.drynor.cs2.ir.ExprStmt
import org.drynor.cs2.ir.If
import org.drynor.cs2.ir.Join
import org.drynor.cs2.ir.LocalAssign
import org.drynor.cs2.ir.MultiAssign
import org.drynor.cs2.ir.OpCall
import org.drynor.cs2.ir.OrAny
import org.drynor.cs2.ir.ParallelAssign
import org.drynor.cs2.ir.Return
import org.drynor.cs2.ir.ScriptCall
import org.drynor.cs2.ir.Stmt
import org.drynor.cs2.ir.Switch
import org.drynor.cs2.ir.VarAssign
import org.drynor.cs2.ir.VarRef
import org.drynor.cs2.ir.While
import world.gregs.voidps.cache.Cache

/**
 * The whole clientscript corpus decompiled once, and the folder it decompiles into.
 *
 * `cs2 decompile-all` and the cache source tree's index 12 codec
 * ([org.drynor.cs2.source.Cs2SourceCodec]) have to produce the *same* folder, byte for byte: the
 * tree stores index 12 as exactly what the CLI writes, so two implementations of that layout
 * would show up as a tree that churns depending on which tool touched it last. This is the one
 * implementation. Both callers want the same two things:
 *
 *  - **[decompile] / [decompileScript]** - a script as TypeScript, either one at a time from the
 *    bytes the caller holds, or the whole corpus in id order;
 *  - **[projectFiles]** - the files that are a property of the *corpus* rather than of any one
 *    script, so no per-archive codec could produce them: `cs2.d.ts` (opcode signatures, argument
 *    kinds included), `gamevals.d.ts` (the id constants), `vars.d.ts` (every variable any script
 *    touches, bound to its id), `tsconfig.json` and the `dead-code.md` report.
 *
 * Whole-corpus work - return signatures by fixpoint across calls, the type inference behind the
 * constant names - is done once by [Cs2Analyzer] and shared, so nothing here pays for it twice.
 *
 * Once [analyzer] and [types] are warm, [decompileScript] holds no state and may be called from
 * any number of threads at once, which is what lets a source tree unpack index 12 an archive per
 * core.
 */
class Cs2Corpus(private val cache: Cache) {

    /** The corpus analysis, done once on first use. */
    val analyzer: Cs2Analyzer by lazy {
        Cs2Analyzer(cache).also { it.analyse() }
    }

    /** Index 12 itself, for checking that a caller's bytes are the ones we analysed. */
    private val scripts = Cs2Cache(cache)

    /** What the corpus says every constant means; whole-cache work, cached by the analyzer. */
    fun types(log: (String) -> Unit = {}): Cs2Types = analyzer.types(log)

    /** Every script the cache holds, ascending. */
    val ids: IntArray get() = analyzer.ids

    /** One script's decompiled source, and what decompiling it turned up. */
    class Decompiled(
        val scriptId: Int,
        val source: String,
        val function: Cs2Function,
        val deadCode: List<Cs2DeadCode>
    ) {
        /** Where this script's source belongs, relative to the folder. */
        val path: String get() = Cs2Layout.pathOf(scriptId)
    }

    /**
     * What one whole-corpus [decompile] pass learned.
     *
     * The sources themselves are handed to the pass's callback rather than kept: all 6,567 of
     * them are 30 MB of text and every caller writes them straight out.
     */
    class Corpus(
        /** How many scripts were decompiled. */
        val decompiled: Int,
        /** Scripts nothing could render, with why. Empty for the 727 cache. */
        val failures: List<Pair<Int, String>>,
        /** Every variable any script reads or writes, in first-seen order. */
        val variables: Set<VarRef>,
        /** How many scripts still need an unstructured `goto`. Zero for the 727 cache. */
        val gotos: Int,
        val scriptsWithDeadCode: Int,
        val deadCodeCounts: Map<Cs2DeadCodeKind, Int>,
        /** `dead-code.md`, rendered as the pass went. */
        val deadCodeReport: String,
        val millis: Long
    ) {
        val deadCodeFindings: Int get() = deadCodeCounts.values.sum()
    }

    /**
     * Decompiles every script in the cache, handing each one to [onScript] as it is rendered.
     *
     * Sequential on purpose: the pass takes under two seconds for all 6,567 scripts once the
     * analysis is warm, and holding no more than one script's IR at a time keeps a whole-cache
     * decompile inside a default heap.
     */
    fun decompile(log: (String) -> Unit = {}, onScript: (Decompiled) -> Unit = {}): Corpus {
        val start = System.nanoTime()
        val types = types(log)
        val failures = ArrayList<Pair<Int, String>>()
        val variables = LinkedHashSet<VarRef>()
        val counts = HashMap<Cs2DeadCodeKind, Int>()
        val report = StringBuilder(DEAD_CODE_HEADER)
        var decompiled = 0
        var withDeadCode = 0
        var gotos = 0
        for (id in ids) {
            val script = analyzer.script(id) ?: continue
            val rendered = try {
                render(script, id, types, faithful = false)
            } catch (e: Exception) {
                try {
                    render(script, id, types, faithful = true)
                } catch (fallback: Exception) {
                    failures.add(id to (fallback.message ?: fallback::class.simpleName ?: "unknown"))
                    continue
                }
            }
            if (rendered.deadCode.isNotEmpty()) {
                withDeadCode++
                rendered.deadCode.forEach { counts[it.kind] = (counts[it.kind] ?: 0) + 1 }
                appendDeadCode(report, rendered)
            }
            collectVariables(rendered.function.body, variables)
            if (rendered.source.contains(GOTO)) {
                gotos++
            }
            onScript(rendered)
            decompiled++
        }
        return Corpus(
            decompiled, failures, variables, gotos, withDeadCode, counts, report.toString(),
            (System.nanoTime() - start) / 1_000_000
        )
    }

    /**
     * Decompiles one script, exactly as a whole-corpus [decompile] would.
     *
     * **It has to be the analyzed script, not a fresh decode of the same bytes.** A script's
     * argument and return *order* is not in its bytecode - the stacks only say how many of each
     * type there are - and is recovered by [Cs2Analyzer.analyse] from what its callers push.
     * Decoding the bytes again loses that and 118 of the 6,567 scripts come out with their
     * parameters in the grouped order instead, which still compiles but is a different file.
     *
     * [bytes] is therefore a check rather than an input: a codec is handed an archive's bytes and
     * this is where it finds out whether they are the ones its cache was analysed from.
     *
     * Where the structurer cannot reproduce a script's branch layout the faithful rendering -
     * block by block, with explicit jumps - can, so that is tried rather than giving up: source
     * that will not compile back is worse than source that is ugly.
     */
    fun decompileScript(scriptId: Int, bytes: ByteArray? = null, types: Cs2Types = types()): Decompiled? {
        val script = analyzer.script(scriptId) ?: return null
        if (bytes != null) {
            val cached = scripts.raw(scriptId)
            require(cached != null && cached.contentEquals(bytes)) {
                "Clientscript $scriptId is ${bytes.size} bytes here and " +
                    "${cached?.size ?: "absent"} in the cache this corpus was analysed from. " +
                    "Decompiling needs the whole corpus, so it has to be the same cache - see " +
                    "Cs2SourceCodec.cache."
            }
        }
        return try {
            render(script, scriptId, types, faithful = false)
        } catch (e: Exception) {
            render(script, scriptId, types, faithful = true)
        }
    }

    private fun render(script: Cs2Script, scriptId: Int, types: Cs2Types, faithful: Boolean): Decompiled {
        val structurer = Cs2Structurer(script, scriptId, analyzer, faithful)
        val function = types.apply(structurer.structure())
        return Decompiled(scriptId, Cs2Emitter(function).emit(), function, structurer.deadCode)
    }

    // ------------------------------------------------------------------ project files

    /**
     * The files a decompiled folder needs that belong to the corpus rather than to one script,
     * by their name in the folder root.
     *
     * A fixed order and derived from the corpus alone, because a source tree holding them must
     * come out the same every time it is written.
     */
    fun projectFiles(corpus: Corpus, types: Cs2Types = types()): Map<String, String> = linkedMapOf(
        // The param types go into cs2.d.ts because they are the one thing a compile needs that no
        // script's source states, and a folder has to be compilable from the folder alone - see
        // Cs2SourceContext.
        Cs2Declarations.OPCODES_FILE to
            Cs2Declarations.generate(types.argumentKinds, analyzer.stringParams()),
        Cs2Declarations.GAMEVALS_FILE to Cs2Declarations.gamevals(),
        Cs2Declarations.VARS_FILE to Cs2Declarations.variables(corpus.variables) {
            types.varKind(it.space, it.id)
        },
        TSCONFIG_FILE to Cs2Declarations.tsconfig(),
        DEAD_CODE_FILE to corpus.deadCodeReport
    )

    /**
     * Decompiles the corpus straight into [folder] - what `cs2 decompile-all` does, and what the
     * cache source tree's index 12 directory holds.
     */
    fun write(folder: File, log: (String) -> Unit = {}): Corpus {
        folder.mkdirs()
        val corpus = decompile(log) { script ->
            val file = Cs2Layout.fileFor(folder, script.scriptId)
            file.parentFile?.mkdirs()
            file.writeText(script.source)
        }
        for ((name, body) in projectFiles(corpus)) {
            folder.resolve(name).writeText(body)
        }
        return corpus
    }

    private fun appendDeadCode(out: StringBuilder, script: Decompiled) {
        out.append("\n## ").append(Cs2Layout.headerOf(script.scriptId))
            .append(" (script ").append(script.scriptId).append(")\n")
        for (finding in script.deadCode) {
            val where = finding.instructions?.let { " (instructions ${it.first}..${it.last})" } ?: ""
            out.append("\n- **").append(finding.kind.label).append("**").append(where)
            finding.note?.let { out.append(" - ").append(it) }
            out.append('\n')
            val body = notes(script.function, listOf(finding), "").lines().drop(1)
                .joinToString("\n").trimEnd()
            if (body.isNotBlank()) {
                out.append("\n```ts\n").append(body).append("\n```\n")
            }
        }
    }

    companion object {

        const val TSCONFIG_FILE = "tsconfig.json"

        const val DEAD_CODE_FILE = "dead-code.md"

        /** What the emitter writes where control flow has no structured form. */
        private const val GOTO = "goto("

        private const val DEAD_CODE_HEADER =
            "# Dead code in the 727 clientscripts\n\n" +
                "Everything here is in the bytecode but cannot affect what the script does.\n" +
                "A dead store is kept in the decompiled source; unreachable code is dropped from it.\n" +
                "Unreachable code that only jumps somewhere is the compiler's own layout and is not listed,\n" +
                "and neither is the default `return` every script ends with.\n"

        /**
         * One finding rendered as the code it is about, each line prefixed with [prefix].
         *
         * Shared by the report and by `cs2 decompile`, which prints the same blocks as comments.
         */
        fun notes(function: Cs2Function, findings: List<Cs2DeadCode>, prefix: String): String {
            val emitter = Cs2Emitter(function)
            val out = StringBuilder()
            for (finding in findings) {
                val where = finding.instructions?.let { " (instructions ${it.first}..${it.last})" } ?: ""
                val note = finding.note?.let { ": $it" } ?: ""
                out.append(prefix).append(finding.kind.label).append(where).append(note).append('\n')
                finding.condition?.let {
                    out.append(prefix).append("  ").append(emitter.renderCondition(it)).append('\n')
                }
                if (finding.statements.isEmpty()) {
                    continue
                }
                emitter.renderStatements(finding.statements).trimEnd().lines()
                    .forEach { out.append(prefix).append("  ").append(it).append('\n') }
            }
            return out.toString()
        }

        /** Every variable a statement tree reads or writes, added to [into]. */
        fun collectVariables(statements: List<Stmt>, into: MutableSet<VarRef>) {
            fun walkExpr(value: Expr) {
                when (value) {
                    is VarRef -> into.add(value)
                    is Binary -> { walkExpr(value.left); walkExpr(value.right) }
                    is Join -> value.parts.forEach { walkExpr(it) }
                    is ArrayRef -> walkExpr(value.index)
                    is OpCall -> value.args.forEach { walkExpr(it) }
                    is ScriptCall -> value.args.forEach { walkExpr(it) }
                    is Callback -> { value.args.forEach { walkExpr(it) }; value.triggers.forEach { walkExpr(it) } }
                    else -> Unit
                }
            }
            fun walkCondition(condition: Condition) {
                when (condition) {
                    is Compare -> { walkExpr(condition.left); walkExpr(condition.right) }
                    is AndAll -> condition.terms.forEach { walkCondition(it) }
                    is OrAny -> condition.terms.forEach { walkCondition(it) }
                }
            }
            for (statement in statements) {
                when (statement) {
                    is ExprStmt -> walkExpr(statement.call)
                    is LocalAssign -> walkExpr(statement.value)
                    is VarAssign -> { into.add(statement.target); walkExpr(statement.value) }
                    is ParallelAssign -> {
                        statement.targets.forEach { walkExpr(it) }
                        statement.values.forEach { walkExpr(it) }
                    }
                    is ArrayAssign -> { walkExpr(statement.index); walkExpr(statement.value) }
                    is ArrayDefine -> walkExpr(statement.size)
                    is MultiAssign -> walkExpr(statement.call)
                    is Return -> statement.values.forEach { walkExpr(it) }
                    is If -> {
                        walkCondition(statement.condition)
                        collectVariables(statement.then, into)
                        collectVariables(statement.otherwise, into)
                    }
                    is While -> {
                        statement.condition?.let { walkCondition(it) }
                        collectVariables(statement.body, into)
                    }
                    is Switch -> {
                        walkExpr(statement.subject)
                        statement.cases.forEach { collectVariables(it.body, into) }
                        collectVariables(statement.default, into)
                    }
                    else -> Unit
                }
            }
        }
    }
}
