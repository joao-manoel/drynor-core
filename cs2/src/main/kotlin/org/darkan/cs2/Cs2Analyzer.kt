package org.darkan.cs2

import java.util.TreeSet
import java.util.concurrent.ConcurrentHashMap
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.definition.data.ParamDefinition
import world.gregs.voidps.cache.definition.decoder.ParamDecoder

/**
 * Whole-cache analysis that turns raw instruction lists into something the
 * decompiler can trust:
 *
 *  - every script's return signature, resolved across calls by fixpoint;
 *  - which param ids yield strings rather than ints;
 *  - confirmation that the opcode table's pop/push counts actually balance.
 *
 * The load-bearing observation is that CS2's compiler leaves all three operand
 * stacks empty at every basic-block boundary. Anything else would be a signature
 * error, so simulating each block in isolation both validates the table and
 * gives the decompiler a clean starting state per block.
 */
class Cs2Analyzer(private val cache: Cache) : Cs2Context {

    private val scripts = Cs2Cache(cache)

    /**
     * The param definitions, decoded from [cache] rather than from `Cache.params`.
     *
     * `Cache.params` is keyed to `Cache.get()`, which is the wrong cache twice over: it is not
     * necessarily the one being analysed, and with `CACHE_SOURCE_PATH` set reaching for it starts
     * the build the analysis may be part of. An analyzer answers about the cache it was handed.
     */
    private val params: Array<ParamDefinition> by lazy { ParamDecoder().load(cache) }

    /**
     * Decoded cache scripts, and the ids the cache has nothing usable for.
     *
     * Concurrent because hot reload compiles on a watcher thread while the
     * virtual machine resolves `GOSUB` targets on the render thread. Everything
     * is warm after [analyse], so in practice the only contended path is the
     * one-off load of an id nothing referenced during analysis; two threads
     * racing there simply decode the same bytes twice.
     */
    private val loaded = ConcurrentHashMap<Int, Cs2Script>()
    private val missing = ConcurrentHashMap.newKeySet<Int>()

    /**
     * Scripts compiled from source that stand in for the cached ones; see
     * [install]. Declared here rather than beside the rest of the hot reload
     * support because [script] reads it, and property initialisers run in
     * declaration order.
     */
    private val overrides = ConcurrentHashMap<Int, Cs2Script>()

    private val paramTypes = HashMap<Int, Boolean>()

    val ids: IntArray = scripts.scriptIds().sortedArray()

    override fun script(id: Int): Cs2Script? {
        overrides[id]?.let { return it }
        loaded[id]?.let { return it }
        if (id in missing) return null
        val decoded = try {
            scripts.load(id)
        } catch (e: Exception) {
            null
        }
        if (decoded == null) {
            missing.add(id)
            return null
        }
        loaded[id] = decoded
        return decoded
    }

    /**
     * The cached script for [id], ignoring any hot-reloaded replacement, so a
     * reverted edit can be compared against what the cache actually holds.
     */
    fun cachedScript(id: Int): Cs2Script? = loaded[id] ?: try {
        scripts.load(id)
    } catch (e: Exception) {
        null
    }

    /**
     * Param types come straight from the cache. Only the `s` type lands on the
     * string stack - the other type characters (`c`, `d`, `o`, `n`, ...) are all
     * int-stack types. Ids that the cache does not define fall back to whatever
     * the corpus solver worked out.
     */
    override fun paramIsString(paramId: Int): Boolean {
        val declared = params.getOrNull(paramId) ?: ParamDefinition.EMPTY
        if (declared.type != 0.toChar()) return declared.isString()
        return paramTypes[paramId] ?: false
    }

    /**
     * Every param id a `*_PARAM` opcode yields a *string* for, ascending.
     *
     * The whole truth about param types as far as a compile is concerned, and the one thing about
     * it a script's own source cannot say: the cache's declared type where there is one, and the
     * corpus solver's answer for the ids the cache does not declare. It is written into
     * `cs2.d.ts` when a folder is decompiled ([Cs2Declarations.stringParams]) so that folder can
     * be compiled back without a cache - see [Cs2SourceContext].
     */
    fun stringParams(): Set<Int> {
        val out = TreeSet<Int>()
        for (id in params.indices) {
            if (paramIsString(id)) {
                out.add(id)
            }
        }
        for ((id, string) in paramTypes) {
            if (string) {
                out.add(id)
            }
        }
        return out
    }

    /**
     * The type character the cache records for a param, which is what a
     * `*_PARAM` opcode returns one of.
     */
    override fun paramType(paramId: Int): Char =
        (params.getOrNull(paramId) ?: ParamDefinition.EMPTY).type

    /**
     * What the corpus says every constant means, worked out once and kept.
     *
     * Deciding that a number is an item id needs every other script that touches
     * it, so this is whole-cache work like the return signatures are. It is done
     * on demand because a tool that never renders source never needs it.
     */
    @Synchronized
    fun types(log: (String) -> Unit = {}): Cs2Types {
        inferred?.let { return it }
        val functions = ids.toList().mapNotNull { id ->
            val script = script(id) ?: return@mapNotNull null
            try {
                Cs2Structurer(script, id, this).structure()
            } catch (e: Exception) {
                null
            }
        }
        log("${functions.size} scripts lifted for type inference")
        return Cs2Types.analyse(this, functions, log).also { inferred = it }
    }

    private var inferred: Cs2Types? = null

    /** Problems found while validating a single script. */
    data class ScriptProblem(val scriptId: Int, val message: String)

    data class Report(
        val analysed: Int,
        val problems: List<ScriptProblem>,
        val stringParams: Set<Int>,
        val returnSignatures: Map<Int, ReturnSignature>,
    ) {
        val ok: Boolean get() = problems.isEmpty()
    }

    fun analyse(log: (String) -> Unit = {}): Report {
        val all: List<Pair<Int, Cs2Script>> =
            ids.toList().mapNotNull { id -> script(id)?.let { id to it } }
        log("${all.size} scripts loaded")

        resolveParamTypes(all, log)
        resolveReturnSignatures(all, log)
        refineParamTypes(all, log)

        val problems = ArrayList<ScriptProblem>()
        for ((id, script) in all) {
            val failure = validate(script)
            if (failure != null) problems.add(ScriptProblem(id, failure))
        }
        log("${problems.size} scripts failed validation")

        return Report(
            analysed = all.size,
            problems = problems,
            stringParams = paramTypes.filterValues { it }.keys,
            returnSignatures = all.associate { (id, script) ->
                id to (script.returnSignature ?: ReturnSignature.EMPTY)
            },
        )
    }

    // ------------------------------------------------------------ param types

    /**
     * `*_PARAM` opcodes push an int or a string depending on the param's
     * declared type, which is not in the script. The type is still pinned down
     * by the stacks having to balance: try a script's unknown param ids both
     * ways and keep the assignment that validates.
     */
    private fun resolveParamTypes(all: List<Pair<Int, Cs2Script>>, log: (String) -> Unit) {
        var resolved = 0
        for ((_, script) in all) {
            val unknown = paramIdsIn(script).filter { it !in paramTypes }.distinct()
            if (unknown.isEmpty()) continue
            if (unknown.size > 12) continue // too many to enumerate; a later script will pin them
            val choice = (0 until (1 shl unknown.size)).firstOrNull { mask ->
                unknown.forEachIndexed { bit, id -> paramTypes[id] = (mask shr bit) and 1 == 1 }
                validate(script, assumeReturns = true) == null
            }
            if (choice == null) {
                unknown.forEach { paramTypes.remove(it) }
            } else {
                resolved += unknown.size
            }
        }
        log("$resolved param ids typed, ${paramTypes.count { it.value }} of them strings")
    }

    /**
     * The first pass assigns each param id the moment it is seen, so an early
     * script can lock in a wrong guess. This revisits scripts that still fail,
     * allowing already-assigned ids to be flipped, and keeps any change that
     * fixes the script without breaking one that already passed.
     */
    private fun refineParamTypes(all: List<Pair<Int, Cs2Script>>, log: (String) -> Unit) {
        indexParamUsers(all)
        repeat(4) { round ->
            var fixed = 0
            for ((_, script) in all) {
                if (validate(script) == null) continue
                val ids = paramIdsIn(script).distinct()
                if (ids.isEmpty() || ids.size > 12) continue
                val original = ids.associateWith { paramTypes[it] }

                val worked = (0 until (1 shl ids.size)).any { mask ->
                    ids.forEachIndexed { bit, id -> paramTypes[id] = (mask shr bit) and 1 == 1 }
                    validate(script) == null && !breaksOthers(ids, script)
                }
                if (worked) {
                    fixed++
                } else {
                    original.forEach { (id, value) ->
                        if (value == null) paramTypes.remove(id) else paramTypes[id] = value
                    }
                }
            }
            log("param refinement round ${round + 1}: $fixed scripts fixed")
            if (fixed == 0) return
        }
    }

    /** Scripts that mention a given param id, so refinement only re-checks those. */
    private val scriptsByParamId = HashMap<Int, MutableList<Cs2Script>>()

    private fun indexParamUsers(all: List<Pair<Int, Cs2Script>>) {
        if (scriptsByParamId.isNotEmpty()) return
        for ((_, script) in all) {
            for (id in paramIdsIn(script).distinct()) {
                scriptsByParamId.getOrPut(id) { ArrayList() }.add(script)
            }
        }
    }

    /** True when the current param typing breaks another script that uses these ids. */
    private fun breaksOthers(ids: List<Int>, exclude: Cs2Script): Boolean =
        ids.asSequence()
            .flatMap { scriptsByParamId[it].orEmpty().asSequence() }
            .distinct()
            .any { it !== exclude && validate(it) != null }

    /** Param ids reachable by `*_PARAM` opcodes, read from the constant push before them. */
    private fun paramIdsIn(script: Cs2Script): List<Int> {
        val out = ArrayList<Int>()
        for (index in script.instructions.indices) {
            if (script[index].op.kind != OpKind.PARAM) continue
            for (back in index - 1 downTo maxOf(0, index - 4)) {
                val candidate = script[back]
                if (candidate.op == Cs2Op.PUSH_CONSTANT_INT) {
                    out.add(candidate.intOperand)
                    break
                }
            }
        }
        return out
    }

    // ------------------------------------------------------ return signatures

    private fun resolveReturnSignatures(all: List<Pair<Int, Cs2Script>>, log: (String) -> Unit) {
        all.forEach { (_, script) -> script.returnSignature = ReturnSignature.EMPTY }

        var pass = 0
        while (true) {
            pass++
            var changed = 0
            for ((_, script) in all) {
                val computed = computeReturnSignature(script) ?: continue
                if (computed != script.returnSignature) {
                    script.returnSignature = computed
                    changed++
                }
            }
            log("return-signature pass $pass: $changed changed")
            if (changed == 0 || pass >= 12) break
        }
        solveRecursiveReturnSignatures(all, log)
    }

    /**
     * A directly or mutually recursive script cannot be bootstrapped by the
     * fixpoint: working out what it returns needs the answer already. There are
     * only a handful, and the stacks still have to balance, so the signature can
     * be found by trying the plausible shapes and keeping the one that works.
     */
    private fun solveRecursiveReturnSignatures(
        all: List<Pair<Int, Cs2Script>>,
        log: (String) -> Unit,
    ) {
        val candidates = ArrayList<ReturnSignature>()
        for (ints in 0..4) for (strings in 0..2) for (longs in 0..2) {
            candidates.add(ReturnSignature(ints, strings, longs))
        }
        candidates.sortBy { it.total }

        repeat(3) {
            var solved = 0
            for ((_, script) in all) {
                if (validate(script) == null) continue
                val original = script.returnSignature
                val match = candidates.firstOrNull { candidate ->
                    script.returnSignature = candidate
                    validate(script) == null
                }
                if (match == null) script.returnSignature = original else solved++
            }
            log("recursive return-signature solve: $solved resolved")
            if (solved == 0) return
        }
    }

    /**
     * Simulates to each `RETURN` and reports what is left on the stacks. Returns
     * null when the script does not simulate cleanly with the current
     * assumptions, leaving the previous estimate in place for the next pass.
     */
    private fun computeReturnSignature(script: Cs2Script): ReturnSignature? {
        val walk = simulate(script)
        return if (walk.failure != null) null else walk.returns
    }

    // -------------------------------------------------------------- validation

    /**
     * Returns null when the script simulates cleanly, or a description of the
     * first inconsistency found.
     */
    fun validate(script: Cs2Script, assumeReturns: Boolean = false): String? =
        simulate(script).failure

    /** Outcome of walking a script's control-flow graph with the operand stacks. */
    class Walk(
        val cfg: Cs2Cfg?,
        /** Stack state on entry to each block, once reachability settles. */
        val entryStates: Array<SymbolicStacks?>,
        val returns: ReturnSignature,
        val failure: String?,
    )

    /**
     * Propagates stack state along control-flow edges.
     *
     * Stacks are usually empty at block boundaries, but not always - the
     * compiler leaves a value live across a branch for chained comparisons - so
     * state is carried along edges rather than reset per block. Reaching a block
     * with mismatched depths means a signature is wrong.
     */
    fun simulate(script: Cs2Script): Walk {
        val cfg = try {
            Cs2Cfg.build(script)
        } catch (e: Exception) {
            return Walk(null, emptyArray(), ReturnSignature.EMPTY, "cfg: ${e.message}")
        }

        val entryStates = arrayOfNulls<SymbolicStacks>(cfg.blocks.size)
        entryStates[0] = SymbolicStacks()
        val worklist = ArrayDeque(listOf(0))
        var returns: ReturnSignature? = null

        while (worklist.isNotEmpty()) {
            val blockId = worklist.removeFirst()
            val block = cfg.blocks[blockId]
            val stacks = entryStates[blockId]!!.copy()

            var returned = false
            for (index in block.indices) {
                val instruction = script[index]
                if (Cs2Cfg.isReturn(instruction.op)) {
                    val here = ReturnSignature(stacks.intDepth, stacks.stringDepth, stacks.longs)
                    if (here.total > 0) script.returnOrder = stacks.order()
                    if (returns != null && returns != here) {
                        return Walk(cfg, entryStates, returns, "inconsistent return signature: $returns vs $here")
                    }
                    returns = here
                    returned = true
                    break
                }
                // A call reveals the order its arguments were pushed in, which
                // the callee's own header cannot express.
                if (instruction.op.kind == OpKind.GOSUB) {
                    recordArgumentOrder(instruction.intOperand, stacks)
                }
                try {
                    step(instruction, stacks)
                } catch (e: Exception) {
                    return Walk(
                        cfg, entryStates, returns ?: ReturnSignature.EMPTY,
                        "instruction $index (${instruction.op.name}): ${e.message}",
                    )
                }
            }
            if (returned) continue

            for (successor in block.successors) {
                val existing = entryStates[successor]
                if (existing == null) {
                    entryStates[successor] = stacks.copy()
                    worklist.addLast(successor)
                } else if (!existing.sameDepthAs(stacks)) {
                    return Walk(
                        cfg, entryStates, returns ?: ReturnSignature.EMPTY,
                        "block $successor reached with mismatched stacks ($existing vs $stacks)",
                    )
                } else {
                    existing.mergeConstantsFrom(stacks)
                }
            }
        }

        return Walk(cfg, entryStates, returns ?: ReturnSignature.EMPTY, null)
    }

    private fun recordArgumentOrder(scriptId: Int, stacks: SymbolicStacks) {
        val callee = script(scriptId) ?: return
        val total = callee.intArgsCount + callee.stringArgsCount + callee.longArgsCount
        if (total == 0 || callee.argumentOrder.isNotEmpty()) return
        val order = stacks.order()
        if (order.size >= total) callee.argumentOrder = order.takeLast(total)
    }

    // ------------------------------------------------------------ hot reloading
    //
    // Replacements live in this analyzer rather than in a wrapping Cs2Context on
    // purpose: the virtual machine, the code generator and the structurer all
    // resolve calls through whichever context they were handed, and any of them
    // holding the bare analyzer would keep running the stale bytecode. Overriding
    // at the one place they all end up means a replacement cannot be missed.

    /** Script ids currently served from source rather than from the cache. */
    val overriddenIds: List<Int> get() = overrides.keys.sorted()

    fun isOverridden(id: Int): Boolean = overrides.containsKey(id)

    /**
     * What [install] managed to work out about a replacement script.
     *
     * [failure] is the simulation error, if any; a script that does not balance
     * is still installed, because refusing it would leave the developer running
     * code they cannot see the effect of, but its return signature is then only
     * the previous version's guess.
     */
    class Installed(
        val scriptId: Int,
        val returnSignature: ReturnSignature,
        val previousReturnSignature: ReturnSignature?,
        /** True when the argument counts in the header moved. */
        val headerChanged: Boolean,
        val failure: String?,
    ) {
        val signatureChanged: Boolean
            get() = previousReturnSignature != null && previousReturnSignature != returnSignature
    }

    /**
     * Replaces script [scriptId] with [script] and derives the analysis the code
     * generator and decompiler need from it.
     *
     * The code generator fills in the header and the instructions but nothing
     * interprocedural: `returnSignature`, `returnOrder` and `argumentOrder` are
     * products of whole-cache analysis. Left null, a statement-position call to
     * this script would discard the wrong number of results, so they are
     * re-derived here:
     *
     *  - `returnSignature` and `returnOrder` come from simulating the new
     *    instructions, exactly as [resolveReturnSignatures] does, bootstrapped
     *    from `EMPTY` so a self-recursive script still terminates.
     *  - `argumentOrder` is a property of the *callers* - it records the order
     *    they push in - so it carries over untouched as long as the header's
     *    argument counts did not move, and is dropped when they did.
     *
     * Callers already compiled into the cache are not adjusted, and cannot be:
     * see [callersOf] and [revalidate].
     */
    fun install(scriptId: Int, script: Cs2Script): Installed {
        val previous = script(scriptId)
        val headerChanged = previous != null && (
            previous.intArgsCount != script.intArgsCount ||
                previous.stringArgsCount != script.stringArgsCount ||
                previous.longArgsCount != script.longArgsCount
            )
        if (!headerChanged && script.argumentOrder.isEmpty()) {
            script.argumentOrder = previous?.argumentOrder.orEmpty()
        }

        // Derive everything before publishing, so a script the render thread can
        // already see is never half-analysed.
        script.returnSignature = ReturnSignature.EMPTY
        val walk = simulate(script)
        if (walk.failure == null) {
            script.returnSignature = walk.returns
            // A script that returns values through a self-call needs the second
            // walk to see its own signature; it also re-derives returnOrder
            // against the settled one.
            simulate(script)
        } else {
            script.returnSignature = previous?.returnSignature ?: ReturnSignature.EMPTY
        }

        overrides[scriptId] = script
        return Installed(
            scriptId = scriptId,
            returnSignature = script.returnSignature ?: ReturnSignature.EMPTY,
            previousReturnSignature = previous?.returnSignature,
            headerChanged = headerChanged,
            failure = walk.failure,
        )
    }

    /** Drops the replacement for [scriptId], falling back to the cached bytecode. */
    fun uninstall(scriptId: Int): Boolean = overrides.remove(scriptId) != null

    /** Drops every replacement. Returns how many there were. */
    fun uninstallAll(): Int {
        val count = overrides.size
        overrides.clear()
        return count
    }

    /**
     * Scripts that `GOSUB` into [scriptId].
     *
     * A call site is compiled against the callee's return signature - it discards
     * exactly as many results as the callee was known to leave - so when an edit
     * changes that signature, every caller's *existing* bytecode is wrong. They
     * cannot be patched incrementally; they have to be recompiled from source, or
     * the edit reverted. Scanned on demand because it is only needed when a
     * signature actually moves.
     */
    fun callersOf(scriptId: Int): List<Int> {
        val callers = ArrayList<Int>()
        for (id in ids) {
            val script = script(id) ?: continue
            val calls = script.instructions.any {
                it.op.kind == OpKind.GOSUB && it.intOperand == scriptId
            }
            if (calls) callers.add(id)
        }
        return callers
    }

    /** Re-simulates the given scripts against the installed overrides. */
    fun revalidate(scriptIds: Collection<Int>): List<ScriptProblem> =
        scriptIds.mapNotNull { id ->
            val script = script(id) ?: return@mapNotNull null
            validate(script)?.let { ScriptProblem(id, it) }
        }

    private fun step(instruction: Cs2Instruction, stacks: SymbolicStacks) {
        val effect = effectiveSignature(instruction, this, stacks)
        val constant = when (instruction.op) {
            Cs2Op.PUSH_CONSTANT_INT -> instruction.intOperand
            else -> null
        }
        stacks.apply(effect, pushedIntConstant = constant, pushedString = instruction.strOperand)
    }
}
