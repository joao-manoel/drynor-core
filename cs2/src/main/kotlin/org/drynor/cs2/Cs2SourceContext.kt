package org.drynor.cs2

import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.drynor.cs2.compile.Cs2Parser
import org.drynor.cs2.ir.Cs2Type

/**
 * Everything compiling a folder of clientscripts needs, read from the folder and nothing else.
 *
 * [Cs2Analyzer] is the other implementation of [Cs2Context] and gets the same answers out of a
 * cache: it decodes all 6,567 scripts and recovers by whole-corpus fixpoint what the bytecode does
 * not state. That is right for decompiling - the bytes are the only thing there is - and impossible
 * for a server building its cache *from* a source tree, because the cache the analysis would read
 * is the one the build is producing (`docs/cache-source.md`, "clientscripts").
 *
 * A decompiled folder does not need it: every fact a compile asks about another file is written
 * out in the TypeScript.
 *
 * | question | where the answer is |
 * |---|---|
 * | what does script *n* return, in push order? | its own `function ...(): [number, string]` |
 * | how many arguments does it take, on which stacks? | its own parameter list |
 * | does a `*_PARAM` opcode with param *n* yield a string? | `cs2.d.ts`'s `StringParam` |
 *
 * So this reads each script's declaration - the header line only, never a body, which would be
 * circular - and the one declaration in `cs2.d.ts` that carries what a script cannot state about
 * itself. Nothing else about a script matters to the *others*: a call site emits its arguments and
 * a `GOSUB`, and needs the callee's returns to know what to discard.
 *
 * Reading is a fraction of a second for the whole corpus (one lex of ~6,600 header lines, spread
 * over every core) against about seven seconds for the corpus analysis, so a pack that has to
 * compile is cheaper this way as well as possible at all.
 *
 * Immutable once built and safe to share across threads, which is what lets a pack compile an
 * archive per core. A folder that changed on disk needs a fresh one; [Cs2Compiler] holds this
 * behind its own lazy and is discarded rather than refreshed.
 */
class Cs2SourceContext private constructor(
    private val scripts: Map<Int, Cs2Script>,
    /**
     * The param ids that yield strings, or null when `cs2.d.ts` declares none.
     *
     * Null rather than empty because the two mean different things to a reader: a folder written
     * before the declaration existed says nothing, and a folder whose params are all ints says
     * that. Neither fails a compile - the compiler's own default is int - but the first is worth
     * running `cs2 declarations <dir>` over. See [declaresStringParams].
     */
    private val stringParams: Set<Int>?,
    /** Files whose header names no script in the catalogs, relative to the folder. */
    val orphans: List<String>,
) : Cs2Context {

    /** How many of the folder's files declared a signature. */
    val size: Int get() = scripts.size

    /**
     * Whether `cs2.d.ts` carried a `StringParam` declaration at all.
     *
     * False for a folder written before it existed. Nothing fails - no param id yields a string,
     * which is what the compiler assumes without an answer, and no script in the 727 corpus
     * compiles differently either way - but a hand-edited script reading a string param would be
     * compiled against the int stack, so `cs2 declarations <dir>` (or a re-unpack) is worth
     * running on such a folder.
     */
    val declaresStringParams: Boolean get() = stringParams != null

    /**
     * The callee's header, as a [Cs2Script] with no instructions.
     *
     * Only the header is ever asked for through this interface: the argument counts, and
     * [Cs2Script.returnSignature] / [Cs2Script.returnOrder], which is what a call site needs to
     * discard the right number of values off the right stacks. Nothing in the compile path reads
     * the instructions, and a folder has none to give - they are what it is about to produce.
     */
    override fun script(id: Int): Cs2Script? = scripts[id]

    /**
     * Whether a `*_PARAM` opcode with this param id pushes a string.
     *
     * The one thing a script's own source cannot say - the type is the param definition's, and the
     * opcode's return type follows it - so it is declared once for the folder in `cs2.d.ts`; see
     * [Cs2Declarations.stringParams].
     */
    override fun paramIsString(paramId: Int): Boolean = stringParams?.contains(paramId) ?: false

    companion object {

        /**
         * Reads [folder]'s signatures and its `cs2.d.ts` param declaration.
         *
         * A file that declares no function, or whose header will not parse, is skipped rather than
         * failing the read: the compile of *that* file then reports its own diagnostic with a
         * position, which is a better error than a folder that will not open. A file whose header
         * names a script the catalogs no longer have is skipped too and listed in [orphans],
         * exactly as [Cs2SymbolTable] lists it.
         */
        fun read(folder: File): Cs2SourceContext {
            val files = Cs2Layout.scriptFiles(folder)
            val threads = Runtime.getRuntime().availableProcessors()
            val pool = Executors.newFixedThreadPool(threads) { runnable ->
                Thread(runnable, "cs2-signatures").apply { isDaemon = true }
            }
            val read = try {
                pool.invokeAll(files.map { file -> Callable { signature(folder, file) } }).map { it.get() }
            } finally {
                pool.shutdown()
            }
            val scripts = HashMap<Int, Cs2Script>(files.size * 2)
            val orphans = ArrayList<String>()
            for (entry in read) {
                when (entry) {
                    null -> Unit
                    is Read.Script -> scripts[entry.id] = entry.script
                    is Read.Orphan -> orphans.add(entry.path)
                }
            }
            val declarations = folder.resolve(Cs2Declarations.OPCODES_FILE).takeIf { it.isFile }
            return Cs2SourceContext(
                scripts,
                declarations?.let { Cs2Declarations.readStringParams(it.readText()) },
                orphans,
            )
        }

        /** What one file turned out to be. */
        private sealed interface Read {
            class Script(val id: Int, val script: Cs2Script) : Read
            class Orphan(val path: String) : Read
        }

        private fun signature(folder: File, file: File): Read? {
            val source = try {
                file.readText()
            } catch (e: Exception) {
                return null
            }
            val relative = Cs2Layout.relativePath(folder, file)
            val id = Cs2Layout.scriptIdOf(source, relative)
            if (id == null) {
                val claimed = Cs2Layout.gamevalHeader(source) ?: return null
                return Read.Orphan("${relative ?: file.name}: '$claimed'")
            }
            val signature = Cs2Parser.signature(source) ?: return null
            return Read.Script(id, header(signature))
        }

        /**
         * A signature as the header a caller resolves against.
         *
         * The local counts are the argument counts because a header is all this is: nothing reads
         * them, and leaving them at zero would describe a script whose arguments have nowhere to
         * live.
         */
        private fun header(signature: Cs2Parser.Signature): Cs2Script {
            val arguments = signature.arguments
            val ints = arguments.count { it == Cs2Type.INT }
            val strings = arguments.count { it == Cs2Type.STRING }
            val longs = arguments.count { it == Cs2Type.LONG }
            val script = Cs2Script(
                name = null,
                instructions = emptyList(),
                intLocalsCount = ints,
                stringLocalsCount = strings,
                longLocalsCount = longs,
                intArgsCount = ints,
                stringArgsCount = strings,
                longArgsCount = longs,
                switchTables = emptyList(),
            )
            val returns = signature.returns
            script.returnSignature = ReturnSignature(
                ints = returns.count { it == Cs2Type.INT },
                strings = returns.count { it == Cs2Type.STRING },
                longs = returns.count { it == Cs2Type.LONG },
            )
            // The annotation is the push order, which the grouped counts cannot express: it is
            // what the decompiler wrote and what a destructuring call site reads back.
            script.returnOrder = returns
            script.argumentOrder = arguments
            return script
        }
    }
}
