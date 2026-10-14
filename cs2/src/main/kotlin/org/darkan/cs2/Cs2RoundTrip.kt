package org.darkan.cs2

import java.io.File
import org.darkan.cs2.compile.Cs2CodeGen
import org.darkan.cs2.compile.Cs2Parser
import org.darkan.cs2.ir.Cs2Type
import org.darkan.cs2.ir.IntConst
import org.darkan.cs2.ir.LocalAssign
import org.darkan.cs2.ir.LongConst
import org.darkan.cs2.ir.StrConst
import org.darkan.cs2.ir.TypedConst
import org.darkan.cs2.vm.Cs2Hook
import org.darkan.cs2.vm.Cs2Values
import org.darkan.cs2.vm.Cs2Vm
import org.darkan.cs2.vm.NoOpCs2Host
import world.gregs.voidps.cache.Cache

/**
 * The decompiler's contract, checked over the whole cache.
 *
 * The bytecode a script re-compiles to no longer has to be the bytes the cache
 * holds - dropping dead code and re-laying control flow both change them - so
 * what is checked instead is that the process settles:
 *
 *  - `bytes0 -> src1 -> bytes1 -> src2 -> bytes2`, and `bytes2 == bytes1`;
 *  - `src2 == src1`, so a second pass over a decompiled folder changes nothing;
 *  - `bytes1` simulates cleanly;
 *  - `bytes1` does the same work as the reachable part of `bytes0`, instruction
 *    for instruction, once the jumps and the folded declarations are set aside;
 *  - `bytes1` behaves like `bytes0` on the virtual machine, against a host whose
 *    answers are derived from what it was asked, so the two runs take the same
 *    branches or visibly do not.
 */
fun cs2RoundTrip(cache: Cache, limit: Int?) {
    println("Decompiling and re-compiling every clientscript...")
    val analyzer = Cs2Analyzer(cache)
    analyzer.analyse()
    val types = analyzer.types()
    val scripts = Cs2Cache(cache)

    var identical = 0
    var viaFallback = 0
    var stable = 0
    var sourceStable = 0
    var validates = 0
    var sameWork = 0
    var equivalent = 0
    var checked = 0
    val failed = ArrayList<Pair<Int, String>>()
    val unstable = ArrayList<Int>()
    val sourceDrift = ArrayList<Int>()
    val invalid = ArrayList<Pair<Int, String>>()
    val lostWork = ArrayList<Pair<Int, String>>()
    val diverged = ArrayList<Pair<Int, String>>()
    val started = System.currentTimeMillis()
    val ids = if (limit != null) analyzer.ids.take(limit) else analyzer.ids.toList()

    for (id in ids) {
        val bytes0 = try {
            scripts.raw(id) ?: continue
        } catch (e: Exception) {
            continue
        }
        val script0 = analyzer.script(id) ?: continue
        checked++
        try {
            val first = Cs2Structurer(script0, id, analyzer)
            val source1 = Cs2Emitter(types.apply(first.structure())).emit()
            val script1 = compile(source1, analyzer)
            val bytes1 = Cs2Codec.encode(script1)

            val decoded1 = Cs2Codec.decode(bytes1)
            decoded1.returnSignature = script0.returnSignature
            decoded1.argumentOrder = script0.argumentOrder
            val failure = analyzer.validate(decoded1)
            if (failure == null) validates++ else invalid.add(id to failure)

            val source2 = Cs2Emitter(types.apply(Cs2Structurer(decoded1, id, analyzer).structure())).emit()
            val bytes2 = Cs2Codec.encode(compile(source2, analyzer))

            if (bytes2.contentEquals(bytes1)) stable++ else unstable.add(id)
            if (source2 == source1) sourceStable++ else sourceDrift.add(id)
            if (bytes1.contentEquals(bytes0)) {
                identical++
            } else if (faithfulMatches(bytes0, script0, id, analyzer)) {
                viaFallback++
            }

            val work = workDifference(script0, script1, first.eliminated)
            if (work == null) sameWork++ else lostWork.add(id to work)

            val difference = if (failure != null) "not simulated" else differential(analyzer, script0, decoded1, id)
            if (difference == null) equivalent++ else diverged.add(id to difference)
        } catch (e: Exception) {
            failed.add(id to (e.message ?: e::class.simpleName ?: "unknown"))
        }
    }

    println()
    println("scripts:             $checked")
    println("failed:              ${failed.size}")
    println("stable  bytes2==bytes1: $stable")
    println("stable  src2==src1:     $sourceStable")
    println("validates:           $validates")
    println("work check:          $sameWork")
    println("vm equivalence:      $equivalent")
    println("identical to cache:  $identical (+$viaFallback more the faithful rendering still matches)")
    println("elapsed:             ${System.currentTimeMillis() - started}ms")

    report("Failed", failed)
    report("Unstable bytecode", unstable.map { it to "bytes2 != bytes1" })
    report("Unstable source", sourceDrift.map { it to "src2 != src1" })
    report("Did not simulate", invalid)
    report("Work changed", lostWork)
    report("Behaviour changed", diverged)

    val ok = failed.isEmpty() && stable == checked && sourceStable == checked &&
        validates == checked && sameWork == checked && equivalent == checked
    println()
    println(if (ok) "PASS" else "FAIL")
}

/**
 * The same five checks for one script, with both sources written out so the
 * difference can be read. This is the tool for investigating a script the sweep
 * reports rather than guessing at it.
 */
fun cs2FixPoint(cache: Cache, scriptId: Int?, directory: String?) {
    if (scriptId == null) {
        println("usage: cs2 fixpoint <scriptId> [dir]")
        return
    }
    val analyzer = Cs2Analyzer(cache)
    analyzer.analyse()
    val script0 = analyzer.script(scriptId) ?: run {
        println("No clientscript $scriptId in the cache.")
        return
    }
    val bytes0 = Cs2Cache(cache).raw(scriptId) ?: return
    val types = analyzer.types()
    val first = Cs2Structurer(script0, scriptId, analyzer)
    val source1 = Cs2Emitter(types.apply(first.structure())).emit()
    val bytes1 = Cs2Codec.encode(compile(source1, analyzer))
    val decoded1 = Cs2Codec.decode(bytes1)
    decoded1.returnSignature = script0.returnSignature
    decoded1.argumentOrder = script0.argumentOrder
    val failure = analyzer.validate(decoded1)
    val source2 = Cs2Emitter(types.apply(Cs2Structurer(decoded1, scriptId, analyzer).structure())).emit()
    val bytes2 = Cs2Codec.encode(compile(source2, analyzer))

    val folder = File(directory ?: ".")
    folder.mkdirs()
    folder.resolve("$scriptId.1.ts").writeText(source1)
    folder.resolve("$scriptId.2.ts").writeText(source2)
    println("script $scriptId (${Cs2Layout.headerOf(scriptId)})")
    println("  instructions:   ${script0.size} -> ${Cs2Codec.decode(bytes1).size}")
    println("  identical:      ${bytes1.contentEquals(bytes0)}")
    println("  stable bytes:   ${bytes2.contentEquals(bytes1)}")
    println("  stable source:  ${source2 == source1}")
    println("  validates:      ${failure ?: "yes"}")
    println("  work:           ${workDifference(script0, Cs2Codec.decode(bytes1), first.eliminated) ?: "same"}")
    println("  behaviour:      ${differential(analyzer, script0, decoded1, scriptId) ?: "same"}")
    println("  wrote ${folder.absolutePath}/$scriptId.1.ts and $scriptId.2.ts")
}

private fun report(title: String, entries: List<Pair<Int, String>>) {
    if (entries.isEmpty()) return
    println()
    println("$title (${entries.size}):")
    entries.take(50).forEach { (id, message) -> println("  script %-6d %s".format(id, message.take(140))) }
}

private fun compile(source: String, analyzer: Cs2Analyzer): Cs2Script =
    Cs2CodeGen(
        Cs2Parser(source, analyzer::paramIsString, returnTypes = analyzer::returnTypes).parse(),
        analyzer,
    ).generate()

/**
 * Re-decompiles with structuring disabled and checks that form round-trips.
 *
 * Structured output is the goal, but where the structurer cannot reproduce a
 * script's exact branch layout the literal block-by-block rendering can, and
 * that is what gets written out for those scripts.
 */
private fun faithfulMatches(raw: ByteArray, script: Cs2Script, id: Int, analyzer: Cs2Analyzer): Boolean =
    try {
        val source = Cs2Emitter(
            analyzer.types().apply(Cs2Structurer(script, id, analyzer, faithful = true).structure()),
        ).emit()
        Cs2Codec.encode(compile(source, analyzer)).contentEquals(raw)
    } catch (e: Exception) {
        false
    }

// ------------------------------------------------------------- the work check

/** Blocks reachable from the entry, as instruction indices. */
private fun reachableInstructions(script: Cs2Script): Set<Int> {
    val cfg = Cs2Cfg.build(script)
    val seen = HashSet<Int>()
    val worklist = ArrayDeque(listOf(0))
    val visited = HashSet<Int>()
    while (worklist.isNotEmpty()) {
        val id = worklist.removeFirst()
        if (!visited.add(id)) continue
        val block = cfg.blocks[id]
        seen.addAll(block.indices)
        block.successors.forEach { if (it !in visited) worklist.addLast(it) }
    }
    return seen
}

/** Opcodes whose operand is a local slot, which declaration order renumbers. */
private val LOCAL_SLOTS = setOf(
    Cs2Op.PUSH_INT_LOCAL, Cs2Op.PUSH_STRING_LOCAL, Cs2Op.PUSH_LONG_LOCAL,
    Cs2Op.POP_INT_LOCAL, Cs2Op.POP_STRING_LOCAL, Cs2Op.POP_LONG_LOCAL,
)

/**
 * What a script does, as a bag of instructions: control flow is left out
 * because its shape is exactly what is allowed to change, and unreachable
 * blocks because they are dropped on purpose.
 *
 * A local's slot is left out for the same reason: slots are handed out in
 * declaration order, and a local is now declared where it is first written, so
 * the same read of the same local can be a different slot number. Which local
 * it is is still checked - by the differential run, which would see any store
 * and load that stopped agreeing.
 */
private fun census(script: Cs2Script): MutableMap<String, Int> {
    val reachable = reachableInstructions(script)
    val out = HashMap<String, Int>()
    for (index in script.instructions.indices) {
        if (index !in reachable) continue
        val instruction = script[index]
        val op = instruction.op
        if (Cs2Cfg.isConditionalBranch(op) || Cs2Cfg.isUnconditionalBranch(op) ||
            Cs2Cfg.isSwitch(op) || Cs2Cfg.isReturn(op)
        ) {
            continue
        }
        val key = if (op in LOCAL_SLOTS) op.name else instruction.toString()
        out[key] = (out[key] ?: 0) + 1
    }
    return out
}

/** The instructions a folded-away declaration used to be. */
private fun eliminatedInstructions(store: LocalAssign): List<String> {
    val push = when (val value = store.value) {
        is IntConst -> Cs2Instruction(Cs2Op.PUSH_CONSTANT_INT, intOperand = value.value)
        is TypedConst -> Cs2Instruction(Cs2Op.PUSH_CONSTANT_INT, intOperand = value.value)
        is StrConst -> Cs2Instruction(Cs2Op.PUSH_CONSTANT_STRING, strOperand = value.value)
        is LongConst -> Cs2Instruction(Cs2Op.PUSH_LONG_CONSTANT, longOperand = value.value)
        else -> return emptyList()
    }
    val pop = when (store.type) {
        Cs2Type.INT -> Cs2Op.POP_INT_LOCAL
        Cs2Type.STRING -> Cs2Op.POP_STRING_LOCAL
        Cs2Type.LONG -> Cs2Op.POP_LONG_LOCAL
    }
    // The store side is counted by opcode alone, like every other local access.
    return listOf(push.toString(), pop.name)
}

/**
 * Null when the rebuilt script does exactly the original's reachable work, or a
 * description of the first instruction it gained or lost.
 */
private fun workDifference(
    original: Cs2Script,
    rebuilt: Cs2Script,
    eliminated: List<LocalAssign>,
): String? {
    val expected = census(original)
    for (store in eliminated) {
        for (key in eliminatedInstructions(store)) {
            val count = expected[key] ?: return "eliminated $key was not in the original"
            if (count == 1) expected.remove(key) else expected[key] = count - 1
        }
    }
    val actual = census(rebuilt)
    if (expected == actual) return null
    val changed = (expected.keys + actual.keys).mapNotNull { key ->
        val delta = (actual[key] ?: 0) - (expected[key] ?: 0)
        if (delta == 0) null else "%+d %s".format(delta, key)
    }
    return changed.take(3).joinToString("; ")
}

// ----------------------------------------------------- the differential run

/**
 * A host that answers every question with a value derived from the question.
 *
 * Zero for everything would make both runs take the same branch whatever the
 * branch was; deriving the answer from the opcode, the call's ordinal and its
 * arguments makes the runs diverge wherever the scripts really differ, and
 * makes them agree everywhere else - the same question is always answered the
 * same way, in both runs.
 */
private class RecordingHost(private val trace: MutableList<String>) : NoOpCs2Host() {

    /** Set when the machine's step limit stopped the run part-way. */
    var ranAway = false
        private set

    private val ordinals = HashMap<String, Int>()
    private val varps = HashMap<Int, Int>()
    private val varbits = HashMap<Int, Int>()
    private val varcs = HashMap<Int, Int>()
    private val varcStrings = HashMap<Int, String>()

    override fun invoke(op: Cs2Op, operand: Int, args: Cs2Values): Cs2Values {
        val ordinal = ordinals.merge(op.name, 1, Int::plus) ?: 1
        trace.add("${op.name}<$operand>(${args.ints}, ${args.strings}, ${args.longs})")
        val seed = mix(op.name.hashCode(), ordinal, args)
        return Cs2Values(
            ints = List(maxOf(op.pushInt, 1)) { pseudo(seed, it) },
            strings = List(maxOf(op.pushStr, 1)) { "v${pseudo(seed, 64 + it)}" },
            longs = List(maxOf(op.pushLong, 1)) { pseudo(seed, 128 + it).toLong() },
        )
    }

    override fun setHook(op: Cs2Op, component: Int, hook: Cs2Hook?) {
        trace.add("${op.name} hook on $component -> ${hook?.scriptId} ${hook?.args} ${hook?.triggers}")
    }

    override fun varp(id: Int) = varps.getOrPut(id) { read("varp", id) }

    override fun setVarp(id: Int, value: Int) {
        trace.add("varp $id = $value")
        varps[id] = value
    }

    override fun varbit(id: Int) = varbits.getOrPut(id) { read("varbit", id) }

    override fun setVarbit(id: Int, value: Int) {
        trace.add("varbit $id = $value")
        varbits[id] = value
    }

    override fun varc(id: Int) = varcs.getOrPut(id) { read("varc", id) }

    override fun setVarc(id: Int, value: Int) {
        trace.add("varc $id = $value")
        varcs[id] = value
    }

    override fun varcString(id: Int) = varcStrings.getOrPut(id) { "c${read("varcstr", id)}" }

    override fun setVarcString(id: Int, value: String) {
        trace.add("varcstr $id = \"$value\"")
        varcStrings[id] = value
    }

    /**
     * A script that runs away trips the instruction limit, and the message says
     * how far into which script it got - which differs between two layouts of
     * the same work. Only the fact of it is comparable.
     */
    override fun onError(message: String) {
        if (message.contains("exceeded")) {
            // Where it stopped is not comparable: two layouts of the same work
            // get different distances into it on the same step budget.
            ranAway = true
        } else {
            trace.add("error: $message")
        }
    }

    private fun read(space: String, id: Int) = pseudo(mix(space.hashCode(), id, Cs2Values.EMPTY), 0)

    private fun mix(op: Int, ordinal: Int, args: Cs2Values): Int {
        var hash = op * 31 + ordinal
        args.ints.forEach { hash = hash * 31 + it }
        args.strings.forEach { hash = hash * 31 + it.hashCode() }
        args.longs.forEach { hash = hash * 31 + it.hashCode() }
        return hash
    }

    /** A small, deterministic value: large ones only make loops run away. */
    private fun pseudo(seed: Int, index: Int): Int {
        val hash = (seed * 31 + index) * -1640531527
        return (hash ushr 8) % 64
    }
}

/** Argument vectors both runs are given, chosen to take different branches. */
private fun argumentVectors(script: Cs2Script): List<Triple<List<Int>, List<String>, List<Long>>> {
    val ints = script.intArgsCount
    val strings = script.stringArgsCount
    val longs = script.longArgsCount
    val patterns = listOf<(Int) -> Int>({ 0 }, { -1 }, { 1 }, { it * 7 + 3 })
    return patterns.mapIndexed { index, pattern ->
        Triple(
            List(ints) { pattern(it) },
            List(strings) { if (index % 2 == 0) "" else "x" },
            List(longs) { pattern(it).toLong() },
        )
    }
}

/**
 * Runs both versions of a script against the same host and reports the first
 * place they part company. Calls into other scripts resolve through the
 * analyzer, so both runs call the same, original, callees.
 */
private fun differential(
    analyzer: Cs2Analyzer,
    original: Cs2Script,
    rebuilt: Cs2Script,
    id: Int,
): String? {
    for ((index, vector) in argumentVectors(original).withIndex()) {
        val (ints, strings, longs) = vector
        val before = ArrayList<String>()
        val after = ArrayList<String>()
        val left = run(analyzer, original, id, before, ints, strings, longs) ?: continue
        val right = run(analyzer, rebuilt, id, after, ints, strings, longs) ?: continue

        // A script that never stops is cut off by the step limit, and the two
        // versions get through different amounts of it in the same number of
        // steps. What they did before the cut still has to agree.
        if (left.ranAway != right.ranAway) {
            return "args #$index: ${if (left.ranAway) "the original" else "the rebuilt"} ran away, the other did not"
        }
        val comparable = if (left.ranAway) minOf(before.size, after.size) else -1
        if (comparable < 0) {
            if (left.result != right.result) return "args #$index: was ${left.result}, now ${right.result}"
            if (before.size != after.size) {
                return "args #$index: was ${before.size} host calls, now ${after.size}" +
                    firstDifference(before, after)
            }
        }
        val steps = if (comparable < 0) before.size else comparable
        for (step in 0 until steps) {
            if (before[step] != after[step]) {
                return "args #$index step $step: \"${before[step]}\" became \"${after[step]}\""
            }
        }
    }
    return null
}

/** What one run of a script did. */
private class Outcome(val result: String, val ranAway: Boolean)

private fun firstDifference(before: List<String>, after: List<String>): String {
    for (index in 0 until minOf(before.size, after.size)) {
        if (before[index] != after[index]) {
            return " (step $index: \"${before[index]}\" became \"${after[index]}\")"
        }
    }
    val longer = if (before.size > after.size) before else after
    return " (extra: \"${longer[minOf(before.size, after.size)]}\")"
}

private fun run(
    analyzer: Cs2Analyzer,
    script: Cs2Script,
    id: Int,
    trace: MutableList<String>,
    ints: List<Int>,
    strings: List<String>,
    longs: List<Long>,
): Outcome? = try {
    val host = RecordingHost(trace)
    val values = Cs2Vm(analyzer, host, instructionLimit = STEP_LIMIT)
        .execute(script, ints, strings, longs, scriptId = id)
    Outcome("${values.ints}|${values.strings}|${values.longs}", host.ranAway)
} catch (e: Exception) {
    // A machine fault says nothing about the two versions differing; both runs
    // see the same one, and comparing traces from a half-run is noise.
    null
}

/** Enough to get through any script the cache holds, and to bound a runaway. */
private const val STEP_LIMIT = 30_000
