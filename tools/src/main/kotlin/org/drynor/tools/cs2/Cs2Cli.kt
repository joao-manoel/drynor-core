package org.drynor.tools.cs2

import java.io.File
import kotlin.math.abs
import org.drynor.core.EnvVars
import org.drynor.cs2.ArgType
import org.drynor.cs2.Cs2Analyzer
import org.drynor.cs2.Cs2Cache
import org.drynor.cs2.Cs2Cfg
import org.drynor.cs2.Cs2Codec
import org.drynor.cs2.Cs2Corpus
import org.drynor.cs2.Cs2DeadCode
import org.drynor.cs2.Cs2DeadCodeKind
import org.drynor.cs2.Cs2Declarations
import org.drynor.cs2.Cs2Emitter
import org.drynor.cs2.Cs2HotReload
import org.drynor.cs2.Cs2Layout
import org.drynor.cs2.Cs2NameSync
import org.drynor.cs2.Cs2Names
import org.drynor.cs2.Cs2Op
import org.drynor.cs2.Cs2OperandInference
import org.drynor.cs2.Cs2Script
import org.drynor.cs2.Cs2SourceProject
import org.drynor.cs2.Cs2Structurer
import org.drynor.cs2.Cs2SymbolTable
import org.drynor.cs2.compile.Cs2CodeGen
import org.drynor.cs2.compile.Cs2Parser
import org.drynor.cs2.cs2FixPoint
import org.drynor.cs2.cs2RoundTrip
import org.drynor.cs2.verifyCodecRoundTrip
import org.drynor.cs2.vm.Cs2Event
import org.drynor.cs2.vm.Cs2Hook
import org.drynor.cs2.vm.Cs2HookDispatcher
import org.drynor.cs2.vm.Cs2Host
import org.drynor.cs2.vm.Cs2Values
import org.drynor.cs2.vm.Cs2Vm
import org.drynor.cs2.vm.NoOpCs2Host
import org.drynor.tools.rsinterface.ComponentOption
import org.drynor.tools.rsinterface.ComponentState
import org.drynor.tools.rsinterface.ComponentType
import org.drynor.tools.rsinterface.InterfaceCs2Host
import org.drynor.tools.rsinterface.InterfaceEvent
import org.drynor.tools.rsinterface.InterfaceInstance
import org.drynor.tools.rsinterface.InterfaceInteraction
import org.drynor.tools.rsinterface.OptionKind
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.definition.decoder.InterfaceDecoder

/**
 * `cs2` sub-commands for the tools CLI.
 *
 * ```
 * cs2 verify              decode + re-encode every script, assert byte identity
 * cs2 dump <id>           print the raw instruction listing for one script
 * cs2 info <id>           print one script's header
 * ```
 */
fun runCs2(cache: Cache, args: List<String>) {
    when (args.firstOrNull() ?: "help") {
        "verify" -> cs2Verify(cache)
        "dump" -> cs2Dump(cache, args.getOrNull(1)?.toIntOrNull())
        "info" -> cs2Info(cache, args.getOrNull(1)?.toIntOrNull())
        "unknown" -> cs2Unknown(cache)
        "analyse", "analyze" -> cs2Analyse(cache)
        "decompile" -> cs2Decompile(cache, args.getOrNull(1)?.toIntOrNull())
        "decompile-all" -> cs2DecompileAll(cache, args.getOrNull(1))
        "roundtrip" -> cs2RoundTrip(cache, args.getOrNull(1)?.toIntOrNull())
        "types" -> cs2Types(cache, args.getOrNull(1))
        "fixpoint" -> cs2FixPoint(cache, args.getOrNull(1)?.toIntOrNull(), args.getOrNull(2))
        "diff" -> cs2Diff(cache, args.getOrNull(1)?.toIntOrNull())
        "run" -> cs2Run(cache, args.getOrNull(1)?.toIntOrNull(), args.drop(2))
        "declarations" -> cs2Declarations(cache, args.getOrNull(1))
        "apply-names" -> cs2ApplyNames(args.getOrNull(1))
        "sync-names" -> cs2SyncNames(args.getOrNull(1), args.drop(2))
        "check-names" -> cs2CheckNames(args.getOrNull(1))
        "interface" -> cs2Interface(cache, args.getOrNull(1)?.toIntOrNull(), args.drop(2))
        "interfaces" -> cs2InterfaceSweep(cache)
        "compile" -> cs2Compile(cache, args.getOrNull(1), args.getOrNull(2)?.toIntOrNull())
        "export" -> cs2Export(cache, args.getOrNull(1), args.getOrNull(2)?.toIntOrNull())
        "watch" -> cs2Watch(cache, args.getOrNull(1), args.getOrNull(2)?.toIntOrNull())
        "sources", "verify-sources" -> cs2VerifySources(cache, args.getOrNull(1), args.getOrNull(2)?.toIntOrNull())
        "hotswap" -> cs2Hotswap(cache, args.getOrNull(1), args.getOrNull(2)?.toIntOrNull(), args.drop(3))
        else -> println(
            """
            cs2 sub-commands:
              decompile <id>      print one script as TypeScript, with its dead code
              decompile-all [dir] write every script, the declarations and dead-code.md;
                                  the folder defaults to cs2-dump
              roundtrip [n]       the stability check: bytes -> source -> bytes -> source
                                  -> bytes, requiring the last two bytecodes to match, the
                                  two sources to match, the rebuild to simulate, its work
                                  census to match and both versions to run the same
              fixpoint <id> [dir] the same five checks for one script, both sources written out
              types [OP:slot]     what the corpus says constants mean, or one slot's evidence
              diff <id>           show where one script's re-compiled bytecode diverges
              dump <id>           print the instruction listing for one script
              info <id>           print one script's header
              verify              decode and re-encode every clientscript, checking byte identity
              analyse             stack-simulate every script, checking the opcode table
              unknown             list opcodes no script uses
              run <id> [args]     execute a script on the virtual machine with a no-op host
              interface <id> ...  run one interface's load scripts and report what changed
              interfaces          run every interface's load scripts
              export <dir> [id]   decompile one script into dir, or just the declarations
              compile <dir> <id>  compile one edited script and compare it to the cache
              sources <dir> [n]   compile every source file in dir, reporting the byte-exact rate
              watch [dir] [secs]  hot-reload scripts as they are saved
              declarations <dir>  write cs2.d.ts, gamevals.d.ts and tsconfig.json
              apply-names <dir>   re-derive every name in dir from gamevals/*.json
              sync-names <dir>    write dir's renames back into gamevals/*.json, then re-derive
              check-names <dir>   report where dir and the catalogs disagree, changing nothing
            """.trimIndent(),
        )
    }
}

private fun cs2Decompile(cache: Cache, scriptId: Int?) {
    if (scriptId == null) {
        println("usage: cs2 decompile <scriptId>")
        return
    }
    val analyzer = Cs2Analyzer(cache)
    analyzer.analyse()
    val script = analyzer.script(scriptId)
    if (script == null) {
        println("No clientscript $scriptId in the cache.")
        return
    }
    val structurer = Cs2Structurer(script, scriptId, analyzer)
    val function = analyzer.types().apply(structurer.structure())
    println(Cs2Emitter(function).emit())
    if (structurer.deadCode.isEmpty()) return
    println("// dead code (${summary(structurer.deadCode)}):")
    print(Cs2Corpus.notes(function, structurer.deadCode, "// "))
}

/** One line per finding kind, as `2 unreachable, 1 dead store`. */
private fun summary(findings: List<Cs2DeadCode>): String =
    Cs2DeadCodeKind.entries.mapNotNull { kind ->
        val count = findings.count { it.kind == kind }
        if (count == 0) null else "$count ${kind.label}"
    }.joinToString(", ")

/** Writes the ambient TypeScript declarations next to the decompiled scripts. */
private fun cs2Declarations(cache: Cache, directory: String?) {
    val target = File(directory ?: ".").apply { mkdirs() }
    // The param types are the one thing in cs2.d.ts that comes from the cache rather than from the
    // opcode table, and they are what lets the folder be compiled back with no cache at all, so
    // the analysis is worth its two seconds even for a bare `declarations` run - see
    // Cs2SourceContext. A folder written before the declaration existed is fixed by running this.
    val analyzer = Cs2Analyzer(cache)
    analyzer.analyse()
    val params = analyzer.stringParams()
    for (file in writeDeclarations(target, stringParams = params)) {
        println("wrote ${file.absolutePath}")
    }
    println("${params.size} params declared as yielding strings")
    Cs2Names.warnings.forEach { println("  note: $it") }
}

/**
 * Writes the three generated declaration files and the project file.
 *
 * `gamevals.d.ts` and `cs2.d.ts` are derived wholesale - from the catalogs and
 * from the opcode table - so they are always rewritten. `vars.d.ts` is not
 * written here: it is produced by a whole-cache decompile and carries the
 * developer's own renames.
 */
private fun writeDeclarations(
    target: File,
    learned: Map<String, ArgType> = emptyMap(),
    stringParams: Set<Int> = emptySet(),
): List<File> {
    val written = ArrayList<File>()
    for ((name, body) in listOf(
        Cs2Declarations.OPCODES_FILE to Cs2Declarations.generate(learned, stringParams),
        Cs2Declarations.GAMEVALS_FILE to Cs2Declarations.gamevals(),
        "tsconfig.json" to Cs2Declarations.tsconfig(),
    )) {
        val file = target.resolve(name)
        file.writeText(body)
        written.add(file)
    }
    return written
}

/**
 * Re-derives a folder's names from the catalogs.
 *
 * This is the catalog-first half of the workflow: edit a name in
 * `gamevals/<type>.json`, run this, and the declarations and every identifier in
 * every script follow. Nothing else has to be touched, and nothing can be left
 * pointing at the old name.
 */
private fun cs2ApplyNames(directory: String?) {
    val folder = File(directory ?: Cs2SourceProject.DEFAULT_FOLDER)
    val report = Cs2NameSync.apply(folder)
    report.problems.forEach { println("  $it") }
    if (!report.ok) return
    println("re-derived ${report.declarations.joinToString(", ")} in ${folder.absolutePath}")
    println(
        "${report.changes.size} names moved; ${report.filesRewritten} of ${report.filesScanned} " +
            "scripts rewritten, ${report.filesMoved} relocated",
    )
    report.changes.take(40).forEach { println("  $it") }
    if (report.changes.size > 40) println("  ... ${report.changes.size - 40} more")
}

/**
 * Writes a folder's renames back into the catalogs, then re-derives it.
 *
 * This is the editor-first half: rename a symbol in VS Code, which rewrites every
 * reference and the binding it hangs on, then run this to make the catalog say
 * the same thing. `--dry-run` reports what it would write without touching a file.
 */
private fun cs2SyncNames(directory: String?, options: List<String>) {
    val folder = File(directory ?: Cs2SourceProject.DEFAULT_FOLDER)
    val dryRun = options.contains("--dry-run") || options.contains("-n")
    val report = Cs2NameSync.sync(folder, File(EnvVars.gamevalPath), dryRun)
    if (report.changes.isEmpty() && report.refusals.isEmpty()) {
        println("no renames in ${folder.absolutePath}; the catalogs already say what it does")
        return
    }
    println("${report.changes.size} renames to write:")
    report.changes.take(40).forEach { println("  $it") }
    if (report.changes.size > 40) println("  ... ${report.changes.size - 40} more")
    if (report.refusals.isNotEmpty()) {
        println()
        println("REFUSED - nothing was written:")
        report.refusals.forEach { println("  $it") }
        return
    }
    if (dryRun) {
        println()
        println("--dry-run: no catalog was written")
        return
    }
    println()
    report.catalogs.forEach { (type, count) -> println("wrote $count names to ${EnvVars.gamevalPath}/$type.json") }
    val applied = report.applied
    if (applied != null) {
        println("re-derived ${applied.declarations.joinToString(", ")}; ${applied.filesRewritten} scripts rewritten, ${applied.filesMoved} relocated")
        applied.problems.forEach { println("  $it") }
    }
}

/** Reports where a folder and the catalogs disagree, without changing either. */
private fun cs2CheckNames(directory: String?) {
    val folder = File(directory ?: Cs2SourceProject.DEFAULT_FOLDER)
    if (!folder.isDirectory) {
        println("no such folder: ${folder.absolutePath}")
        return
    }
    val symbols = Cs2SymbolTable.read(folder)
    if (symbols.orphans.isNotEmpty()) {
        println("${symbols.orphans.size} files name a clientscript the catalogs no longer have:")
        symbols.orphans.take(20).forEach { println("  $it") }
        if (symbols.orphans.size > 20) println("  ... ${symbols.orphans.size - 20} more")
        println("nothing reads them as a script until `cs2 apply-names ${folder.path}` puts them back")
        println()
    }
    println("renamed away from the catalogs: ${symbols.renames.size}")
    symbols.renames.take(30).forEach { println("  $it") }
    if (symbols.renames.size > 30) println("  ... ${symbols.renames.size - 30} more")
    println()
    if (symbols.conflicts.isEmpty()) {
        println("no conflicts: every name means one thing")
        return
    }
    println("CONFLICTS - a compile of this folder will refuse:")
    symbols.conflicts.forEach { println("  $it") }
    println()
    println("run `cs2 sync-names ${folder.path}` to make the catalogs match the folder,")
    println("or `cs2 apply-names ${folder.path}` to make the folder match the catalogs")
}

/**
 * Decompiles the whole corpus into a folder.
 *
 * The folder this writes is the cache source tree's `clientscripts/` directory
 * (`docs/cache-source.md`), so the layout belongs to [Cs2Corpus] and not to this
 * command: two implementations of it would show up as a tree that churns
 * depending on which tool touched it last.
 */
private fun cs2DecompileAll(cache: Cache, directory: String?) {
    println("Decompiling every clientscript...")
    // With no folder named, this writes the project folder every other command
    // already defaults to, which is where an editor keeps the sources.
    val target = File(directory ?: Cs2SourceProject.DEFAULT_FOLDER)
    val corpus = Cs2Corpus(cache).write(target) { println("  $it") }

    // A folder written by an older build holds the same scripts at the flat
    // `clientscript-<id>.ts` paths. Left in place they would declare every
    // function a second time, so they are named rather than silently shadowed.
    val stale = Cs2Layout.legacyFiles(target)
    if (stale.isNotEmpty()) {
        println()
        println("WARNING: ${stale.size} files from the old flat layout are still in the folder")
        println("  (${stale.first().name} ...); delete them or every function is declared twice")
    }

    println()
    Cs2Names.warnings.forEach { println("note: $it") }
    println("decompiled:      ${corpus.decompiled}")
    println("with dead code:  ${corpus.scriptsWithDeadCode} scripts, ${corpus.deadCodeFindings} findings")
    Cs2DeadCodeKind.entries.forEach { kind ->
        println("  %-20s %d".format(kind.label, corpus.deadCodeCounts[kind] ?: 0))
    }
    println("variables:       ${corpus.variables.size}")
    println("failed:          ${corpus.failures.size}")
    println("with raw gotos:  ${corpus.gotos}")
    println("elapsed:         ${corpus.millis}ms")
    println("written to:      ${target.absolutePath}")

    if (corpus.failures.isNotEmpty()) {
        println()
        println("First failures:")
        corpus.failures.take(10).forEach { (id, message) -> println("  script $id: $message") }
        val grouped = corpus.failures.groupingBy { it.second.take(60) }.eachCount()
            .entries.sortedByDescending { it.value }.take(10)
        println()
        println("Grouped:")
        grouped.forEach { (message, count) -> println("  %-62s %d".format(message, count)) }
    }
}

/** What the corpus-wide type inference worked out, and what it was read from. */
private fun cs2Types(cache: Cache, slot: String?) {
    val analyzer = Cs2Analyzer(cache)
    analyzer.analyse()
    val started = System.currentTimeMillis()
    val types = analyzer.types { println("  $it") }
    if (slot != null) {
        println()
        println("evidence for $slot:")
        val evidence = types.evidenceFor(slot)
        if (evidence.isEmpty()) println("  nothing voted at that slot")
        evidence.forEach { println("  $it") }
        return
    }
    val report = types.describe()
    println()
    println("symbols typed:")
    report.symbols.forEach { (what, count) -> println("  %-16s %d".format(what, count)) }

    println()
    println("type characters, against what the corpus makes of the same values:")
    println("  %-6s %-12s %8s %8s  %s".format("char", "claims", "agreed", "against", "kept"))
    for (check in report.chars) {
        val against = check.conflicts.entries.joinToString(", ") { "${it.key}x${it.value}" }
        println(
            "  %-6s %-12s %8d %8s  %s".format(
                check.char, check.kind, check.agreed,
                if (check.conflicts.isEmpty()) "-" else against,
                if (check.kept) "yes" else "no",
            ),
        )
    }

    println()
    println("learned opcode results (${report.learnedResults.size}):")
    report.learnedResults.entries.sortedBy { it.key }
        .forEach { (slot, kind) -> println("  %-52s %s".format(slot.removePrefix("o:"), kind)) }
    println()
    println("learned argument slots (${report.learnedArguments.size}):")
    report.learnedArguments.entries.sortedBy { it.key }
        .forEach { (slot, kind) -> println("  %-52s %s".format(slot.removePrefix("a:"), kind)) }

    println()
    println("constants written out, by kind:")
    report.constantsByKind.entries.sortedByDescending { it.value }
        .forEach { (kind, count) -> println("  %-14s %d".format(kind, count)) }
    println()
    println("constants written out, by what said so:")
    report.constantsBySource.entries.sortedByDescending { it.value }
        .forEach { (source, count) -> println("  %-22s %d".format(source, count)) }
    println()
    println("elapsed: ${System.currentTimeMillis() - started}ms")
}

private fun cs2Analyse(cache: Cache) {
    println("Analysing clientscripts...")
    val started = System.currentTimeMillis()
    val report = Cs2Analyzer(cache).analyse { println("  $it") }
    println()
    println("analysed:      ${report.analysed}")
    println("string params: ${report.stringParams.size}")
    println("problems:      ${report.problems.size}")
    println("elapsed:       ${System.currentTimeMillis() - started}ms")

    val returning = report.returnSignatures.values.count { it.total > 0 }
    println("scripts returning values: $returning")

    if (report.problems.isNotEmpty()) {
        println()
        println("First problems:")
        report.problems.take(15).forEach { println("  script ${it.scriptId}: ${it.message}") }
        val opcode = Regex("\\(([A-Z][A-Z0-9_]*)\\)")
        val byOpcode = report.problems
            .groupingBy { opcode.find(it.message)?.groupValues?.get(1) ?: it.message.substringBefore(' ') }
            .eachCount()
            .entries.sortedByDescending { it.value }
        println()
        println("Grouped by opcode:")
        byOpcode.forEach { (name, count) -> println("  %-32s %d".format(name, count)) }
        printSuspectOpcodes(cache, report)
    }
    println()
    println(if (report.ok) "PASS - every script balances." else "FAIL")
}

/**
 * Underflows surface at whichever opcode happens to consume the stack, not at
 * the one whose signature is wrong. Ranking opcodes by how much more often they
 * appear in failing scripts than in passing ones points at the real culprit.
 */
private fun printSuspectOpcodes(cache: Cache, report: Cs2Analyzer.Report) {
    val scripts = Cs2Cache(cache)
    val failing = report.problems.map { it.scriptId }.toSet()
    val inFailing = HashMap<Cs2Op, Int>()
    val inPassing = HashMap<Cs2Op, Int>()

    for (id in scripts.scriptIds()) {
        val script = try {
            scripts.load(id) ?: continue
        } catch (e: Exception) {
            continue
        }
        val used = script.instructions.mapTo(HashSet()) { it.op }
        val into = if (id in failing) inFailing else inPassing
        used.forEach { into[it] = (into[it] ?: 0) + 1 }
    }

    val failCount = failing.size.coerceAtLeast(1)
    val passCount = (report.analysed - failing.size).coerceAtLeast(1)
    val ranked = inFailing.entries
        .filter { it.value >= 3 }
        .map { (op, count) ->
            val failRate = count.toDouble() / failCount
            val passRate = (inPassing[op] ?: 0).toDouble() / passCount
            Triple(op, failRate / (passRate + 1e-6), count)
        }
        .sortedByDescending { it.second }
        .take(15)

    println()
    println("Opcodes enriched in failing scripts (likely wrong signatures):")
    ranked.forEach { (op, enrichment, count) ->
        println(
            "  %-32s x%-8.1f in %d failing   pop(%d,%d,%d) push(%d,%d,%d)".format(
                op.name, enrichment, count,
                op.popInt, op.popStr, op.popLong,
                op.pushInt, op.pushStr, op.pushLong,
            ),
        )
    }
}

private fun cs2Unknown(cache: Cache) {
    println("Inferring operand widths for opcodes the client no longer defines...")
    val result = Cs2OperandInference.infer(cache) { println("  $it") }
    println()
    println("resolved (${result.resolved.size}):")
    result.resolved.entries.sortedBy { it.key }.forEach { (id, width) ->
        println("  opcode %4d  %-4s  %d uses".format(id, width.name, result.occurrences[id] ?: 0))
    }
    if (result.ambiguous.isNotEmpty()) {
        println()
        println("ambiguous (${result.ambiguous.size}):")
        result.ambiguous.entries.sortedBy { it.key }.forEach { (id, widths) ->
            println("  opcode %4d  %s".format(id, widths.map { it.name }))
        }
    }
    if (result.unsolvedScripts.isNotEmpty()) {
        println()
        println("unsolved scripts (${result.unsolvedScripts.size}): ${result.unsolvedScripts.take(20)}")
    }
}

private fun cs2Verify(cache: Cache) {
    println("Verifying clientscript codec round-trip...")
    val started = System.currentTimeMillis()
    val report = verifyCodecRoundTrip(cache) { println("  $it") }
    val elapsed = System.currentTimeMillis() - started

    println()
    println("archives:   ${report.total}")
    println("identical:  ${report.identical}")
    println("mismatched: ${report.mismatched.size}")
    println("failed:     ${report.failed.size}")
    println("elapsed:    ${elapsed}ms")

    if (report.failed.isNotEmpty()) {
        println()
        println("First failures:")
        report.failed.take(10).forEach { (id, message) -> println("  script $id: $message") }
    }
    if (report.mismatched.isNotEmpty()) {
        println()
        println("First mismatches: ${report.mismatched.take(20)}")
    }
    println()
    println(if (report.ok) "PASS - every script re-encoded byte-identically." else "FAIL")
}

private fun cs2Dump(cache: Cache, scriptId: Int?) {
    if (scriptId == null) {
        println("usage: cs2 dump <scriptId>")
        return
    }
    val script = Cs2Cache(cache).load(scriptId)
    if (script == null) {
        println("No clientscript $scriptId in the cache.")
        return
    }
    printHeader(scriptId, script)
    println()
    script.instructions.forEachIndexed { index, instruction ->
        println("%5d  %s".format(index, instruction))
    }
    script.switchTables.forEachIndexed { table, cases ->
        println()
        println("switch table $table:")
        cases.forEach { println("  case ${it.key} -> +${it.offset}") }
    }
}

private fun cs2Info(cache: Cache, scriptId: Int?) {
    if (scriptId == null) {
        println("usage: cs2 info <scriptId>")
        return
    }
    val script = Cs2Cache(cache).load(scriptId)
    if (script == null) {
        println("No clientscript $scriptId in the cache.")
        return
    }
    printHeader(scriptId, script)
}

private fun printHeader(scriptId: Int, script: Cs2Script) {
    println("script $scriptId")
    println("  name:         ${script.name ?: "<none>"}")
    println("  instructions: ${script.size}")
    println("  int args:     ${script.intArgsCount}   locals: ${script.intLocalsCount}")
    println("  string args:  ${script.stringArgsCount}   locals: ${script.stringLocalsCount}")
    println("  long args:    ${script.longArgsCount}   locals: ${script.longLocalsCount}")
    println("  switch tables: ${script.switchTables.size}")
}

/** Shows the original and re-compiled instruction listings side by side. */
private fun cs2Diff(cache: Cache, scriptId: Int?) {
    if (scriptId == null) {
        println("usage: cs2 diff <scriptId>")
        return
    }
    val analyzer = Cs2Analyzer(cache)
    analyzer.analyse()
    val original = analyzer.script(scriptId) ?: run {
        println("No clientscript $scriptId in the cache.")
        return
    }
    val source = Cs2Emitter(analyzer.types().apply(Cs2Structurer(original, scriptId, analyzer).structure())).emit()
    val rebuilt = Cs2CodeGen(
        Cs2Parser(source, analyzer::paramIsString, returnTypes = analyzer::returnTypes).parse(),
        analyzer,
    ).generate()

    println(source)
    println("header: locals ${original.intLocalsCount}/${original.stringLocalsCount}/${original.longLocalsCount} " +
        "vs ${rebuilt.intLocalsCount}/${rebuilt.stringLocalsCount}/${rebuilt.longLocalsCount}")
    println("switch tables: ${original.switchTables.size} vs ${rebuilt.switchTables.size}")
    println("instructions: ${original.size} vs ${rebuilt.size}")
    original.switchTables.forEachIndexed { index, table ->
        val other = rebuilt.switchTables.getOrNull(index)
        if (table != other) {
            println("switch table $index differs:")
            println("  original:   ${table.take(12)}")
            println("  recompiled: ${other?.take(12)}")
        }
    }
    println()
    reportWorkDifference(original, rebuilt)
    println("%-4s %-40s %s".format("#", "original", "recompiled"))
    val count = maxOf(original.size, rebuilt.size)
    var shown = 0
    for (index in 0 until count) {
        val left = original.instructions.getOrNull(index)
        val right = rebuilt.instructions.getOrNull(index)
        // Jump distances shift as soon as anything else differs, so only flag
        // places where the actual opcode sequence diverges.
        val jump = left?.op?.let { Cs2Cfg.isConditionalBranch(it) || Cs2Cfg.isUnconditionalBranch(it) } == true
        val same = if (jump) left?.op == right?.op else left?.toString() == right?.toString()
        if (same) continue
        println("%-4d %-40s %-40s".format(index, left?.toString() ?: "", right?.toString() ?: ""))
        if (++shown > 12) {
            println("... stopping after the first dozen opcode differences")
            break
        }
    }
    if (shown == 0) {
        println("(the opcode sequence matches)")
        for (index in 0 until count) {
            val left = original.instructions.getOrNull(index) ?: continue
            val right = rebuilt.instructions.getOrNull(index) ?: continue
            if (left.toString() == right.toString()) continue
            println("%-4d %-40s %-40s  target %d vs %d".format(
                index, left, right, index + left.intOperand + 1, index + right.intOperand + 1))
            if (++shown > 8) break
        }
    }
}

/** Lists instructions the rebuilt script gained or lost, ignoring jumps. */
private fun reportWorkDifference(original: Cs2Script, rebuilt: Cs2Script) {
    fun census(script: Cs2Script) = script.instructions
        .filterNot { Cs2Cfg.isConditionalBranch(it.op) || Cs2Cfg.isUnconditionalBranch(it.op) }
        .groupingBy { it.toString() }
        .eachCount()
    val before = census(original)
    val after = census(rebuilt)
    val changed = (before.keys + after.keys)
        .mapNotNull { key ->
            val delta = (after[key] ?: 0) - (before[key] ?: 0)
            if (delta == 0) null else key to delta
        }
    if (changed.isEmpty()) {
        println("work: unchanged (layout only)")
        return
    }
    println("work changed:")
    changed.sortedByDescending { abs(it.second) }.take(10).forEach { (key, delta) ->
        println("  %+d  %s".format(delta, key))
    }
}

/** Runs a script on the virtual machine with a do-nothing host, for smoke testing. */
private fun cs2Run(cache: Cache, scriptId: Int?, args: List<String>) {
    if (scriptId == null) {
        println("usage: cs2 run <scriptId> [intArgs...]")
        return
    }
    val analyzer = Cs2Analyzer(cache)
    analyzer.analyse()
    val script = analyzer.script(scriptId) ?: run {
        println("No clientscript $scriptId in the cache.")
        return
    }

    val calls = ArrayList<String>()
    val host = object : NoOpCs2Host() {
        override fun invoke(op: Cs2Op, operand: Int, values: Cs2Values): Cs2Values {
            calls.add("${op.name}(${values.ints.joinToString(", ")}${
                if (values.strings.isEmpty()) "" else ", " + values.strings.joinToString(", ") { "\"$it\"" }
            })")
            return Cs2Host.defaults(op)
        }

        override fun setHook(op: Cs2Op, component: Int, hook: Cs2Hook?) {
            calls.add("${op.name} on $component -> ${hook?.scriptId ?: "none"} args=${hook?.args?.ints}")
        }

        override fun onError(message: String) = println("  error: $message")
    }

    val ints = args.mapNotNull { it.toIntOrNull() }
    val result = Cs2Vm(analyzer, host).execute(script, ints, scriptId = scriptId)
    println("ran script $scriptId with ints=$ints")
    println("returned: ints=${result.ints} strings=${result.strings} longs=${result.longs}")
    println("host calls (${calls.size}):")
    calls.take(40).forEach { println("  $it") }
}

/**
 * Opens an interface headlessly, runs its components' load scripts through the
 * virtual machine, and reports what the scripts actually did. This is the same
 * path the editor takes, minus the rendering.
 *
 * [extra] optionally fires more hooks, in order, and dumps the resolved box model:
 *
 * ```
 * cs2 interface 1102 tree                              boxes after loading
 * cs2 interface 1102 mouse_press:47 tree               open the dropdown, then boxes
 * cs2 interface 1102 mouse_press:47 mouse_enter:148    open it, then hover a row
 * ```
 *
 * The tree is the only way to see what a widget built at runtime actually looks
 * like without a window: it lists the components `CC_CREATE` made, which the
 * cache knows nothing about, with the position and size the layout gave them.
 * Chaining hooks is what makes a widget that only exists once the user has touched
 * it reachable at all.
 */
private fun cs2Interface(cache: Cache, interfaceId: Int?, extra: List<String> = emptyList()) {
    if (interfaceId == null) {
        println("usage: cs2 interface <interfaceId> [<event> <componentIndex>] [tree]")
        return
    }
    val analyzer = Cs2Analyzer(cache)
    analyzer.analyse()

    val decoder = InterfaceDecoder()
    val definitions = decoder.create(decoder.size(cache) + 1)
    if (interfaceId !in definitions.indices) {
        println("No interface $interfaceId in the cache.")
        return
    }
    decoder.load(definitions, cache, interfaceId)
    val definition = definitions[interfaceId]
    if (definition.components == null) {
        println("Interface $interfaceId has no components.")
        return
    }

    val instance = InterfaceInstance(interfaceId, definition)
    var current: InterfaceInstance? = instance
    val host = InterfaceCs2Host(
        interfaces = { id -> if (id == interfaceId) current else null },
        enums = { id -> Cache.enums.getOrNull(id) },
        structs = { id -> Cache.structs.getOrNull(id) },
        params = { id -> Cache.params.getOrNull(id) },
    )
    val dispatcher = Cs2HookDispatcher(analyzer, host)
    // Only used to answer "what would a click here do" for the `hit` request; it is
    // never stepped, so its hover and press state stay empty.
    val interaction = InterfaceInteraction({ current }, dispatcher, host)

    // Snapshot the fields scripts most often change, so the report can show
    // what running the interface actually did rather than just that it ran.
    val before = instance.components.map { Triple(it.hidden, it.text, it.graphicId) }

    // The client only ever runs a load hook on an interface it has already drawn,
    // so the box model has to exist before the scripts that read it do.
    instance.layoutDeclared()

    var ran = 0
    for (component in instance.components) {
        val hook = component.definition.onLoadScript ?: continue
        host.setActive(0, component)
        dispatcher.dispatch(hook, Cs2Event(source = component.hash))
        ran++
    }
    current = instance

    val changed = instance.components.filterIndexed { index, state ->
        val (hidden, text, graphic) = before[index]
        state.hidden != hidden || state.text != text || state.graphicId != graphic
    }

    println("interface $interfaceId: ${instance.components.size} components")
    println("  components with hooks: ${instance.components.count { it.definition.hooks().isNotEmpty() }}")
    println("  onLoad scripts run:    $ran")
    println("  components changed:    ${changed.size}")
    println("  hooks bound by scripts: ${host.boundHooks.values.sumOf { it.size }}")

    changed.take(10).forEach { state ->
        val (hidden, text, graphic) = before[state.index]
        val parts = ArrayList<String>()
        if (state.hidden != hidden) parts.add("hidden $hidden -> ${state.hidden}")
        if (state.text != text) parts.add("text \"$text\" -> \"${state.text}\"")
        if (state.graphicId != graphic) parts.add("graphic $graphic -> ${state.graphicId}")
        println("    component ${state.index}: ${parts.joinToString(", ")}")
    }
    host.boundHooks.entries.take(6).forEach { (hash, events) ->
        println("    hooks on component ${hash and 0xFFFF}: ${events.keys.joinToString(", ")}")
    }
    if (host.log.isNotEmpty()) {
        println("  script output:")
        host.log.take(10).forEach { println("    $it") }
    }

    for (request in extra) {
        if (request.equals("tree", ignoreCase = true)) {
            continue
        }
        val parts = request.split(':')
        if (parts[0].equals("hit", ignoreCase = true)) {
            printHitChain(
                instance,
                host,
                interaction,
                parts.getOrNull(1)?.toIntOrNull() ?: -1,
                parts.getOrNull(2)?.toIntOrNull() ?: -1,
            )
            continue
        }
        fireHook(
            instance, host, dispatcher, parts[0],
            parts.getOrNull(1)?.toIntOrNull(),
            parts.getOrNull(2)?.toIntOrNull() ?: -1,
        )
    }
    if (extra.any { it.equals("tree", ignoreCase = true) }) {
        printResolvedTree(instance)
    }
}

/**
 * Fires one more hook after the load pass, so a widget that only builds itself
 * when the user touches it can be inspected headlessly.
 *
 * The box model is resolved first. A real interaction always happens on a drawn
 * frame, so every geometry opcode the hook calls would see a laid-out tree; skip
 * it here and the hook reads zero heights that the editor never would, which
 * makes the diagnostic lie in the one direction that matters.
 */
private fun fireHook(
    instance: InterfaceInstance,
    host: InterfaceCs2Host,
    dispatcher: Cs2HookDispatcher,
    eventName: String,
    componentIndex: Int?,
    /**
     * The event's `mouseY`, which is also where the client smuggles a wheel notch
     * count into a hook - `mouse_wheel:57:1` is one notch over component 57.
     */
    value: Int = -1,
) {
    val event = InterfaceEvent.entries.firstOrNull { it.name.equals(eventName, ignoreCase = true) }
        ?: InterfaceEvent.entries.firstOrNull { it.boundKey.equals(eventName, ignoreCase = true) }
    if (event == null) {
        println()
        println("Unknown event \"$eventName\". Known: ${InterfaceEvent.entries.joinToString(", ") { it.name }}")
        return
    }
    instance.layoutDeclared()
    val state = componentIndex?.let { instance.state(it) }
    if (state == null) {
        println()
        println("No component $componentIndex to fire $eventName on.")
        return
    }
    val bound = host.boundHooks[state.hash]?.get(event.boundKey)
    val defined = event.definitionHook(state.definition)
    println()
    println("Firing ${event.name} on component ${state.index}: " + when {
        bound != null -> "runtime hook, script ${bound.scriptId}"
        defined != null -> "cache hook, script ${defined.script}"
        else -> "no hook bound - nothing to run"
    })
    val logFrom = host.log.size
    host.setActive(0, state)
    // A runtime component reports the slot it was created in, which is what the
    // client substitutes for `event_comsubid` and how a row hook identifies itself.
    val data = Cs2Event(
        mouseY = value,
        source = state.hash,
        sourceSlot = state.slotId,
        clickedOption = 1,
        optionName = state.opBase,
    )
    when {
        bound != null -> dispatcher.dispatch(bound, data)
        defined != null -> dispatcher.dispatch(defined, data)
    }
    if (host.log.size > logFrom) {
        println("  script output:")
        host.log.subList(logFrom, host.log.size).take(10).forEach { println("    $it") }
    }
}

/**
 * Prints every component's resolved box, cache and runtime alike, indented by
 * depth. This is the headless equivalent of looking at the stage: rows that all
 * report the same `y`, or a size of zero, show up here immediately.
 */
private fun printResolvedTree(instance: InterfaceInstance) {
    instance.layoutDeclared()
    println()
    println("Resolved tree (idx parent slot type  x,y  wxh  @absX,absY):")
    fun walk(parentIndex: Int, depth: Int, originX: Int, originY: Int) {
        for (state in instance.children(parentIndex)) {
            val absX = originX + state.x
            val absY = originY + state.y
            val slot = if (state.slotId < 0) "-" else "s${state.slotId}"
            val label = buildList {
                if (state.text.isNotEmpty()) add("text \"${state.text}\"")
                if (state.graphicId != -1) add("graphic ${state.graphicId}")
                if (state.hidden) add("hidden")
                if (state.scrollHeight != 0) add("scrollH ${state.scrollHeight}")
                if (state.scrollY != 0) add("scrollY ${state.scrollY}")
                if (state.draggable) add("draggable")
                if (state.scripted) add("scripted")
                if (state.type == ComponentType.FIGURE) {
                    add("col ${Integer.toHexString(state.colour)} ${if (state.filled) "filled" else "outline"} trans ${state.transparency}")
                }
            }.joinToString(", ")
            println(
                "  %s%-4d p%-4d %-4s %-9s %4d,%-4d %3dx%-3d @%4d,%-4d %s".format(
                    "  ".repeat(depth), state.index, state.parent, slot,
                    ComponentType.name(state.type), state.x, state.y,
                    state.width, state.height, absX, absY, label,
                )
            )
            // Only containers give their children a coordinate space, and they shift
            // it by their scroll offset - the same rule the renderer walks.
            if (state.type == ComponentType.CONTAINER) {
                walk(state.index, depth + 1, absX - state.scrollX, absY - state.scrollY)
            }
        }
    }
    walk(-1, 0, 0, 0)
}

/**
 * Prints what the pointer would hit at ([pointX], [pointY]) in stage coordinates,
 * back to front, and which of those hits the interaction runtime would keep.
 *
 * This is the headless answer to "why does hovering that row do nothing". The
 * three columns that matter are whether the component is in the raw hit list at
 * all (it is clipped or hidden if not), whether it survived the click-through
 * rule, and whether [org.drynor.tools.rsinterface.ComponentState.interactive]
 * admits it - a component that fails the last one is drawn and nothing more.
 */
private fun printHitChain(
    instance: InterfaceInstance,
    host: InterfaceCs2Host,
    interaction: InterfaceInteraction,
    pointX: Int,
    pointY: Int,
) {
    instance.layoutDeclared()
    val width = InterfaceInstance.DEFAULT_VIEWPORT_WIDTH
    val height = InterfaceInstance.DEFAULT_VIEWPORT_HEIGHT
    val hits = instance.hitTest(0, 0, width, height, pointX, pointY)
    val hover = instance.hoverChain(0, 0, width, height, pointX, pointY)
    println()
    println("Hit chain at $pointX,$pointY in a ${width}x$height stage (back to front):")
    if (hits.isEmpty()) {
        println("  nothing under the pointer")
    }
    for (state in hits) {
        val flags = buildList {
            if (hover.none { it === state }) add("click-through blocked")
            if (!state.interactive) add("NOT interactive")
            host.boundHooks[state.hash]?.keys?.takeIf { it.isNotEmpty() }
                ?.let { add("bound ${it.joinToString("/")}") }
            state.definition.hooks().keys.takeIf { it.isNotEmpty() }
                ?.let { add("cache ${it.joinToString("/")}") }
            if (state.events != 0) add("events ${Integer.toHexString(state.events)}")
            if (state.contentType != 0) add("content ${state.contentType}")
            if (state.noClickThrough) add("noClickThrough")
            if (state.optionHook) add("op hook")
            val options = (0 until ComponentState.OPTION_COUNT).mapNotNull { option ->
                state.optionAt(option)?.let { "${option + 1}=$it" }
            }
            if (options.isNotEmpty()) add("ops ${options.joinToString(" ")}")
            if (state.text.isNotEmpty()) add("text \"${state.text}\"")
        }.joinToString(", ")
        println(
            "  %-4d p%-4d %-4s %-9s %s".format(
                state.index, state.parent,
                if (state.slotId < 0) "-" else "s${state.slotId}",
                ComponentType.name(state.type), flags,
            )
        )
    }
    println("  interaction target: " + (
        instance.interactiveAt(0, 0, width, height, pointX, pointY)?.index?.toString() ?: "none"
        ))
    // What a plain left click would run, resolved by the same rule the runtime uses.
    val entries = ArrayList<ComponentOption>()
    for (state in hover) {
        entries.addAll(interaction.optionsFor(state, pointX, pointY).reversed())
    }
    val default = entries.lastOrNull { it.kind != OptionKind.OPTION || it.option <= InterfaceInteraction.LEFT_CLICK_OPTIONS }
    println("  menu entries: " + (
        if (entries.isEmpty()) "none" else entries.joinToString(", ") { "${it.component.index}:${it.option} ${it.label}" }
        ))
    println("  left click runs: " + (
        if (default == null) "nothing" else "component ${default.component.index} option ${default.option} \"${default.label}\""
        ))
}

/** Runs every interface's load scripts, checking the whole path holds up. */
private fun cs2InterfaceSweep(cache: Cache) {
    println("Running load scripts for every interface...")
    val analyzer = Cs2Analyzer(cache)
    analyzer.analyse()

    val decoder = InterfaceDecoder()
    val definitions = decoder.create(decoder.size(cache) + 1)

    var opened = 0
    var withComponents = 0
    var scriptsRun = 0
    var componentsChanged = 0
    var hooksBound = 0
    val failures = ArrayList<Pair<Int, String>>()
    val scriptErrors = ArrayList<Pair<Int, String>>()
    val started = System.currentTimeMillis()

    for (id in definitions.indices) {
        try {
            decoder.load(definitions, cache, id)
        } catch (e: Exception) {
            failures.add(id to "decode: ${e.message}")
            continue
        }
        opened++
        val definition = definitions[id]
        if (definition.components == null) continue
        withComponents++

        try {
            val instance = InterfaceInstance(id, definition)
            val host = InterfaceCs2Host(
                interfaces = { other -> if (other == id) instance else null },
                enums = { Cache.enums.getOrNull(it) },
                structs = { Cache.structs.getOrNull(it) },
                params = { Cache.params.getOrNull(it) },
            )
            val dispatcher = Cs2HookDispatcher(analyzer, host)
            val before = instance.components.map { Triple(it.hidden, it.text, it.graphicId) }
            instance.layoutDeclared()

            for (component in instance.components) {
                val hook = component.definition.onLoadScript ?: continue
                host.setActive(0, component)
                dispatcher.dispatch(hook, Cs2Event(source = component.hash))
                scriptsRun++
            }
            componentsChanged += instance.components.count { state ->
                val (hidden, text, graphic) = before[state.index]
                state.hidden != hidden || state.text != text || state.graphicId != graphic
            }
            hooksBound += host.boundHooks.values.sumOf { it.size }
            // An error the machine reports is not an exception - it logs and carries
            // on - so a sweep that only counts exceptions calls a runaway script a
            // success. These are what actually tell you an interface is broken.
            host.log.filter { it.startsWith("error: ") }
                .forEach { scriptErrors.add(id to it.removePrefix("error: ")) }
        } catch (e: Exception) {
            failures.add(id to "run: ${e.message ?: e::class.simpleName}")
        }
    }

    println()
    println("interfaces decoded:   $opened")
    println("with components:      $withComponents")
    println("onLoad scripts run:   $scriptsRun")
    println("components changed:   $componentsChanged")
    println("hooks bound at run:   $hooksBound")
    println("failures:             ${failures.size}")
    println("script errors:        ${scriptErrors.size} across ${scriptErrors.distinctBy { it.first }.size} interfaces")
    println("elapsed:              ${System.currentTimeMillis() - started}ms")
    if (failures.isNotEmpty()) {
        failures.groupingBy { it.second.take(60) }.eachCount()
            .entries.sortedByDescending { it.value }.take(8)
            .forEach { (message, count) -> println("  %-62s %d".format(message, count)) }
    }
    if (scriptErrors.isNotEmpty()) {
        println()
        println("Script errors, most common first:")
        scriptErrors.groupingBy { it.second.take(76) }.eachCount()
            .entries.sortedByDescending { it.value }.take(12)
            .forEach { (message, count) -> println("  %-78s %d".format(message, count)) }
        println()
        println("First few, with the interface they came from:")
        scriptErrors.take(8).forEach { (id, message) -> println("  interface $id: $message") }
    }
}

/**
 * Seeds a source folder: one script, or the ambient declarations it needs.
 *
 * A folder with no `cs2.d.ts` will not type-check in an editor even though it
 * compiles here, so exporting nothing but a script is rarely what is wanted.
 */
private fun cs2Export(cache: Cache, directory: String?, scriptId: Int?) {
    if (directory == null) {
        println("usage: cs2 export <dir> [scriptId]")
        return
    }
    val analyzer = Cs2Analyzer(cache)
    analyzer.analyse()
    val project = Cs2SourceProject(cache, analyzer, File(directory))

    if (scriptId == null) {
        println(project.exportDeclarations().message)
        println("pass a script id to export one script, or use `cs2 decompile-all $directory` for the lot")
        return
    }
    val result = project.exportSource(scriptId, overwrite = true)
    println(result.message)
    if (result.ok) println(project.verify(scriptId).summary)
}

/**
 * Compiles every source file in a folder and reports how many still encode to
 * exactly the cached bytes.
 *
 * This is the number that decides how much hot reload can be trusted: a script
 * that does not round-trip untouched will not round-trip edited either, and the
 * developer needs to know that before wondering why their change misbehaves.
 */
private fun cs2VerifySources(cache: Cache, directory: String?, limit: Int?) {
    val folder = File(directory ?: Cs2SourceProject.DEFAULT_FOLDER)
    if (!folder.isDirectory) {
        println("no such folder: ${folder.absolutePath}")
        return
    }
    println("Analysing the cache...")
    val analyzer = Cs2Analyzer(cache)
    analyzer.analyse()
    val reload = Cs2HotReload(cache, analyzer, Cs2SourceProject(cache, analyzer, folder))

    println("Compiling ${folder.absolutePath}...")
    val started = System.currentTimeMillis()
    val sweep = reload.sweepFolder(limit, installEdits = false) { done, total ->
        if (done % 500 == 0) println("  $done / $total")
    }

    println()
    println("compiled:        ${sweep.compiled}")
    println("byte-identical:  ${sweep.identical}  (%.2f%%)".format(sweep.exactRate * 100))
    println("differing:       ${sweep.differing}")
    println("failed:          ${sweep.failed}")
    println("elapsed:         ${System.currentTimeMillis() - started}ms")

    if (sweep.failures.isNotEmpty()) {
        println()
        println("First failures:")
        sweep.failures.take(10).forEach { println("  $it") }
        println()
        println("Grouped:")
        sweep.failures.groupingBy { it.message.take(70) }.eachCount()
            .entries.sortedByDescending { it.value }.take(12)
            .forEach { (message, count) -> println("  %-72s %d".format(message, count)) }
        println()
        println("located: ${sweep.failures.count { it.located }} of ${sweep.failures.size} have a line")
    }
}

/**
 * Runs one script as the cache has it, then as the source folder has it.
 *
 * This is the whole hot reload path in one command - compile, install, dispatch
 * through [Cs2HookDispatcher] against the overlay context - which is what proves
 * an edit reaches the running virtual machine rather than merely compiling. The
 * dispatcher is deliberately built *before* the reload, mirroring the editor,
 * where it is constructed once when the interface opens.
 */
private fun cs2Hotswap(cache: Cache, directory: String?, scriptId: Int?, args: List<String>) {
    if (scriptId == null) {
        println("usage: cs2 hotswap <dir> <scriptId> [intArgs...]")
        return
    }
    val folder = File(directory ?: Cs2SourceProject.DEFAULT_FOLDER)
    val analyzer = Cs2Analyzer(cache)
    analyzer.analyse()
    val reload = Cs2HotReload(cache, analyzer, Cs2SourceProject(cache, analyzer, folder))

    val calls = ArrayList<String>()
    val host = object : NoOpCs2Host() {
        override fun invoke(op: Cs2Op, operand: Int, values: Cs2Values): Cs2Values {
            calls.add("${op.name}(${(values.ints.map { it.toString() } + values.strings.map { "\"$it\"" }).joinToString(", ")})")
            return Cs2Host.defaults(op)
        }

        override fun onError(message: String) = println("  error: $message")
    }
    val dispatcher = Cs2HookDispatcher(reload, host)
    val hook = Cs2Hook(scriptId, Cs2Values(ints = args.mapNotNull { it.toIntOrNull() }))

    fun run(label: String) {
        calls.clear()
        val result = dispatcher.dispatch(hook, Cs2Event())
        println("$label:")
        println("  returned: ints=${result?.ints} strings=${result?.strings} longs=${result?.longs}")
        println("  host calls (${calls.size}):")
        calls.take(20).forEach { println("    $it") }
    }

    run("cached")
    println()
    val event = reload.reload(scriptId)
    println("reload: $event")
    event.diagnostic?.let { println("  ${it.location}") }
    println()
    run("from source")
}

/**
 * Runs the hot reload watcher without a window.
 *
 * Same pipeline the editor uses, so a reload that works here works there. With a
 * duration it runs unattended, which is how it gets tested; without one it takes
 * commands on stdin.
 */
private fun cs2Watch(cache: Cache, directory: String?, seconds: Int?) {
    val folder = File(directory ?: Cs2SourceProject.DEFAULT_FOLDER)
    println("Analysing the cache...")
    val analyzer = Cs2Analyzer(cache)
    analyzer.analyse()
    val reload = Cs2HotReload(cache, analyzer, Cs2SourceProject(cache, analyzer, folder))

    if (!reload.start()) {
        println("cannot watch: ${reload.watcherError}")
        return
    }
    println("watching ${reload.watchedFolder?.absolutePath}")
    println("save a <category>/<name>.ts to reload it. Commands: r <id>, v <id>, l, q")

    val drained = ArrayList<Cs2HotReload.Event>()
    val printer = Thread {
        try {
            while (true) {
                reload.drainInto(drained)
                drained.forEach { event ->
                    println("  $event")
                    val diagnostic = event.diagnostic
                    if (diagnostic != null && diagnostic.located) println("    at ${diagnostic.location}")
                    if (event.staleCallers.isNotEmpty()) {
                        println("    stale callers: ${event.staleCallers.take(20)}")
                    }
                }
                drained.clear()
                Thread.sleep(100)
            }
        } catch (e: InterruptedException) {
            // Shutting down.
        }
    }
    printer.isDaemon = true
    printer.start()

    if (seconds != null) {
        Thread.sleep(seconds * 1000L)
    } else {
        var interactive = false
        while (true) {
            val line = readlnOrNull()?.trim() ?: break
            interactive = true
            val parts = line.split(' ')
            when (parts[0]) {
                "q", "quit", "exit" -> break
                "l", "list" -> println("  overridden: ${reload.overridden}")
                "r" -> parts.getOrNull(1)?.toIntOrNull()?.let { println("  ${reload.reload(it)}") }
                "v" -> parts.getOrNull(1)?.toIntOrNull()?.let { println("  ${reload.project.verify(it).summary}") }
                "" -> Unit
                else -> println("  commands: r <id>, v <id>, l, q")
            }
        }
        // `gradle run` does not forward stdin, so an immediate EOF means there is
        // no console rather than a developer asking to stop.
        if (!interactive) {
            println("no console input; watching until interrupted")
            printer.join()
        }
    }
    printer.interrupt()
    reload.close()
    println("stopped. ${reload.log.count { !it.ok }} failures in ${reload.log.size} events")
}

/**
 * Compiles an edited script back from disk and reports whether it still matches
 * the cache. Names are resolved through the folder's declarations, so a renamed
 * variable or function compiles to exactly what it did before.
 */
private fun cs2Compile(cache: Cache, directory: String?, scriptId: Int?) {
    if (directory == null || scriptId == null) {
        println("usage: cs2 compile <dir> <scriptId>")
        return
    }
    val folder = File(directory)
    val analyzer = Cs2Analyzer(cache)
    analyzer.analyse()
    val project = Cs2SourceProject(cache, analyzer, folder)
    val file = project.fileFor(scriptId)
    if (!file.isFile) {
        println("No ${file.path}")
        return
    }

    val symbols = project.symbols
    symbols.refusal()?.let {
        println(it)
        return
    }

    val relative = Cs2Layout.relativePath(folder, file)
    val rebuilt = try {
        Cs2CodeGen(
            Cs2Parser(file.readText(), analyzer::paramIsString, symbols, relative, analyzer::returnTypes).parse(),
            analyzer,
        ).generate()
    } catch (e: Exception) {
        println("compile failed: ${e.message}")
        return
    }
    val bytes = Cs2Codec.encode(rebuilt)
    val original = Cs2Cache(cache).raw(scriptId)

    println("compiled ${relative ?: file.name}: ${bytes.size} bytes, ${rebuilt.size} instructions")
    println(
        when {
            original == null -> "no original in the cache to compare against"
            bytes.contentEquals(original) -> "identical to the cached script"
            else -> "DIFFERS from the cached script (${original.size} bytes)"
        },
    )
}
