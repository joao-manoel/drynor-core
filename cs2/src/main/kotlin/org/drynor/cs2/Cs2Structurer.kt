package org.drynor.cs2

import org.drynor.cs2.ir.Break
import org.drynor.cs2.ir.AndAll
import org.drynor.cs2.ir.Compare
import org.drynor.cs2.ir.Condition
import org.drynor.cs2.ir.OrAny
import org.drynor.cs2.ir.negate
import org.drynor.cs2.ir.Continue
import org.drynor.cs2.ir.Cs2Function
import org.drynor.cs2.ir.Cs2Type
import org.drynor.cs2.ir.Goto
import org.drynor.cs2.ir.If
import org.drynor.cs2.ir.IntConst
import org.drynor.cs2.ir.Label
import org.drynor.cs2.ir.LocalAssign
import org.drynor.cs2.ir.LongConst
import org.drynor.cs2.ir.Return
import org.drynor.cs2.ir.StrConst
import org.drynor.cs2.ir.TypedConst
import org.drynor.cs2.ir.Stmt
import org.drynor.cs2.ir.Switch
import org.drynor.cs2.ir.SwitchCase
import org.drynor.cs2.ir.While

/**
 * Rebuilds `if` / `else` / `while` / `switch` from the control-flow graph.
 *
 * CS2 comes out of a straightforward expression compiler, so the block layout
 * follows a small number of shapes and address order is meaningful: forward
 * branches skip over a region, and the only backward branches are loop edges.
 * Anything that does not match a known shape falls back to a label and an
 * explicit `goto`, which still re-compiles exactly.
 */
class Cs2Structurer(
    private val script: Cs2Script,
    private val scriptId: Int,
    private val context: Cs2Context,
    /**
     * Skips structuring and renders the blocks literally. Used as a fallback for
     * the few scripts whose shape the structurer cannot reproduce exactly.
     */
    private val faithful: Boolean = false,
) {
    private val cfg = Cs2Cfg.build(script)
    private val lifter = Cs2Lifter(script, context)
    private val lifted = arrayOfNulls<LiftedBlock>(cfg.blocks.size)
    private val entryStacks = arrayOfNulls<StackState>(cfg.blocks.size)
    private val labelled = HashSet<Int>()

    /** Blocks control can actually get to from the entry block. */
    private val reached = BooleanArray(cfg.blocks.size)

    /** Set once the structured rendering is committed to; see [live]. */
    private var dropUnreachable = false

    /** What the bytecode contains that cannot affect what the script does. */
    val deadCode = ArrayList<Cs2DeadCode>()

    /** Def-initialiser stores folded into declarations, for the work check. */
    val eliminated = ArrayList<LocalAssign>()

    fun structure(): Cs2Function {
        liftAll()
        if (faithful) return function(renumberLabels(dropUnusedLabels(unstructured())))

        dropUnreachable = true
        // A jump can be discovered after its target block was already emitted,
        // so structure once to collect the labels and again to place them.
        structureRange(0, cfg.blocks.size)
        val structured = structureRange(0, cfg.blocks.size)

        // If any jump ended up without a landing place, the shape was one this
        // structurer does not recognise. Fall back to a literal block-by-block
        // rendering, which is less pleasant to read but always correct: it keeps
        // every block, so it also keeps the bytecode byte for byte.
        if (danglingLabels(structured).isNotEmpty()) {
            dropUnreachable = false
            return function(renumberLabels(dropUnusedLabels(unstructured())))
        }

        reportUnreachable()
        val cleanup = Cs2Cleanup(
            cfg, lifted, reached,
            script.intArgsCount, script.stringArgsCount, script.longArgsCount,
        )
        val body = cleanup.clean(structured, returnsValues = returnTypesFrom(structured).isNotEmpty())
        deadCode.addAll(cleanup.findings)
        eliminated.addAll(cleanup.eliminated)
        return function(renumberLabels(dropUnusedLabels(body)))
    }

    /**
     * Reports the blocks nothing can reach, which are dropped from the output.
     *
     * The trailing block is left out of the report: every script ends in the
     * compiler's own default `return`, and both arms of a leading `if` returning
     * makes it unreachable without anyone having written anything dead.
     */
    private fun reportUnreachable() {
        var id = 0
        while (id < cfg.blocks.size) {
            if (reached[id] || isImplicitReturn(id)) {
                id++
                continue
            }
            val start = id
            val statements = ArrayList<Stmt>()
            while (id < cfg.blocks.size && !reached[id] && !isImplicitReturn(id)) {
                lifted[id]?.let { statements.addAll(blockStatements(script, cfg, id, it)) }
                id++
            }
            // A stranded jump is the compiler's own layout, not something
            // anybody wrote: the `&&` and `switch` shapes leave one behind
            // whenever every path into it was taken earlier. Nothing can be
            // judged from it, so only unreachable code that does something is
            // reported. It is dropped from the output either way.
            if (statements.isNotEmpty() && statements.all { it is Goto || it is Label }) {
                continue
            }
            val range = cfg.blocks[start].start until cfg.blocks[id - 1].end
            val note = if (statements.isEmpty()) "could not be lifted" else null
            deadCode.add(
                Cs2DeadCode(
                    Cs2DeadCodeKind.UNREACHABLE, statements, note = note,
                    instructions = range,
                ),
            )
        }
    }

    /**
     * True for the compiler's own trailing `return`, which every script carries
     * whether or not anything can reach it.
     */
    private fun isImplicitReturn(id: Int): Boolean {
        if (id != cfg.blocks.lastIndex || reached[id]) return false
        val block = lifted[id] ?: return false
        if (block.statements.isNotEmpty()) return false
        val values = block.returns ?: return false
        return values.all { it is IntConst || it is StrConst || it is LongConst || it is TypedConst }
    }

    /** True when a block belongs in the output. */
    private fun live(id: Int): Boolean =
        lifted[id] != null && (!dropUnreachable || reached[id])

    /**
     * Return values come back in the order they were pushed, which can interleave
     * the stacks, so the tuple type is taken from an actual `return` rather than
     * from the grouped signature.
     */
    private fun returnTypesFrom(body: List<Stmt>): List<Cs2Type> {
        val returned = firstReturn(body) ?: return script.returnOrder.ifEmpty { returnTypes() }
        return returned.values.map { it.type }
    }

    private fun firstReturn(statements: List<Stmt>): Return? {
        for (statement in statements) {
            when (statement) {
                is Return -> if (statement.values.isNotEmpty()) return statement
                is If -> (firstReturn(statement.then) ?: firstReturn(statement.otherwise))?.let { return it }
                is While -> firstReturn(statement.body)?.let { return it }
                is Switch -> {
                    statement.cases.forEach { case -> firstReturn(case.body)?.let { return it } }
                    firstReturn(statement.default)?.let { return it }
                }
                else -> Unit
            }
        }
        return null
    }

    /** Drops the labels nothing jumps to any more. */
    private fun dropUnusedLabels(body: List<Stmt>): List<Stmt> {
        val wanted = HashSet<String>()
        fun collect(statements: List<Stmt>) {
            for (statement in statements) {
                when (statement) {
                    is Goto -> wanted.add(statement.label)
                    is If -> {
                        collect(statement.then)
                        collect(statement.otherwise)
                    }
                    is While -> collect(statement.body)
                    is Switch -> {
                        statement.cases.forEach { collect(it.body) }
                        collect(statement.default)
                    }
                    else -> Unit
                }
            }
        }
        collect(body)

        fun prune(statements: List<Stmt>): List<Stmt> = statements.mapNotNull { statement ->
            when (statement) {
                is Label -> statement.takeIf { it.name in wanted }
                is If -> If(statement.condition, prune(statement.then), prune(statement.otherwise))
                is While -> While(statement.condition, prune(statement.body))
                is Switch -> statement.copy(
                    cases = statement.cases.map { SwitchCase(it.keys, prune(it.body)) },
                    default = prune(statement.default),
                )
                else -> statement
            }
        }
        return prune(body)
    }

    /**
     * Renames the labels to the order they appear in.
     *
     * A label is named after the block it lands on, and block numbers move as
     * soon as anything about the layout does - so two decompilations of the same
     * script would differ in nothing but its label names. Numbering them by where
     * they appear makes the name a property of the structure instead.
     */
    private fun renumberLabels(body: List<Stmt>): List<Stmt> {
        val names = LinkedHashMap<String, String>()
        fun collect(statements: List<Stmt>) {
            for (statement in statements) {
                when (statement) {
                    is Label -> names.getOrPut(statement.name) { "L${names.size}" }
                    is If -> {
                        collect(statement.then)
                        collect(statement.otherwise)
                    }
                    is While -> collect(statement.body)
                    is Switch -> {
                        statement.cases.forEach { collect(it.body) }
                        collect(statement.default)
                    }
                    else -> Unit
                }
            }
        }
        collect(body)
        if (names.isEmpty()) return body

        fun rename(statements: List<Stmt>): List<Stmt> = statements.map { statement ->
            when (statement) {
                is Label -> Label(names[statement.name] ?: statement.name)
                is Goto -> Goto(names[statement.label] ?: statement.label)
                is If -> If(statement.condition, rename(statement.then), rename(statement.otherwise))
                is While -> While(statement.condition, rename(statement.body))
                is Switch -> statement.copy(
                    cases = statement.cases.map { SwitchCase(it.keys, rename(it.body)) },
                    default = rename(statement.default),
                )
                else -> statement
            }
        }
        return rename(body)
    }

    private fun function(body: List<Stmt>) = Cs2Function(
        scriptId = scriptId,
        name = script.name,
        intArgs = script.intArgsCount,
        stringArgs = script.stringArgsCount,
        longArgs = script.longArgsCount,
        intLocals = script.intLocalsCount,
        stringLocals = script.stringLocalsCount,
        longLocals = script.longLocalsCount,
        body = body,
        returns = returnTypesFrom(body),
        argumentOrder = script.argumentOrder,
    )

    /** Labels jumped to but never placed. */
    private fun danglingLabels(body: List<Stmt>): Set<String> {
        val wanted = HashSet<String>()
        val placed = HashSet<String>()
        fun walk(statements: List<Stmt>) {
            for (statement in statements) {
                when (statement) {
                    is Goto -> wanted.add(statement.label)
                    is Label -> placed.add(statement.name)
                    is If -> {
                        walk(statement.then)
                        walk(statement.otherwise)
                    }
                    is While -> walk(statement.body)
                    is Switch -> {
                        statement.cases.forEach { walk(it.body) }
                        walk(statement.default)
                    }
                    else -> Unit
                }
            }
        }
        walk(body)
        return wanted - placed
    }

    /**
     * Every block in address order, each labelled, with its terminator written
     * out as an explicit jump. This is the shape the code generator reproduces
     * exactly, so it is the safe answer when structuring does not fit.
     */
    private fun unstructured(): List<Stmt> {
        val out = ArrayList<Stmt>()
        for (id in cfg.blocks.indices) {
            val block = lifted[id] ?: continue
            out.add(Label("L$id"))
            out.addAll(block.statements)
            when {
                block.returns != null -> out.add(Return(block.returns))
                block.condition != null -> {
                    val target = branchTarget(id)
                    if (target >= 0) out.add(If(block.condition, listOf(Goto("L$target")), emptyList()))
                }
                block.switchSubject != null -> out.add(switchAsJumps(id, block))
                endsWithGoto(id) -> {
                    val target = branchTarget(id)
                    if (target >= 0) out.add(Goto("L$target"))
                }
            }
        }
        return out
    }

    /** A switch rendered as one jump per key, with no structured arms. */
    private fun switchAsJumps(id: Int, block: LiftedBlock): Switch {
        val table = script.switchTables.getOrNull(block.switchTable) ?: emptyList()
        val last = cfg.blocks[id].end - 1
        val cases = table.mapNotNull { case ->
            val target = cfg.blockStartingAt(last + case.offset + 1)?.id ?: return@mapNotNull null
            SwitchCase(listOf(case.key), listOf(Goto("L$target")))
        }
        return Switch(block.switchSubject!!, cases, emptyList(), block.switchTable)
    }

    private fun returnTypes(): List<Cs2Type> {
        val signature = script.returnSignature ?: ReturnSignature.EMPTY
        return List(signature.ints) { Cs2Type.INT } +
            List(signature.strings) { Cs2Type.STRING } +
            List(signature.longs) { Cs2Type.LONG }
    }

    /** Lifts every block, propagating the operand stacks along control-flow edges. */
    private fun liftAll() {
        entryStacks[0] = StackState()
        val worklist = ArrayDeque(listOf(0))
        while (worklist.isNotEmpty()) {
            val id = worklist.removeFirst()
            reached[id] = true
            if (lifted[id] != null) continue
            val entry = entryStacks[id] ?: StackState()
            val block = lifter.lift(cfg.blocks[id], entry)
            lifted[id] = block
            for (successor in cfg.blocks[id].successors) {
                if (entryStacks[successor] == null) entryStacks[successor] = block.exitStack
                if (lifted[successor] == null) worklist.addLast(successor)
            }
        }

        // Scripts carry a trailing default `return` that both arms of an
        // if/else can make unreachable. Dropping it would change the bytecode,
        // so unreached blocks are lifted from an empty stack as well.
        for (id in cfg.blocks.indices) {
            if (lifted[id] != null) continue
            lifted[id] = try {
                lifter.lift(cfg.blocks[id], StackState())
            } catch (e: Exception) {
                null
            }
        }
    }

    /** Block a conditional branch jumps to, or -1. */
    private fun branchTarget(id: Int): Int {
        val block = cfg.blocks[id]
        val targets = Cs2Cfg.jumpTargets(script, block.end - 1)
        return targets.firstOrNull()?.let { cfg.blockStartingAt(it)?.id } ?: -1
    }

    private fun endsWithGoto(id: Int): Boolean =
        Cs2Cfg.isUnconditionalBranch(script[cfg.blocks[id].end - 1].op)

    private fun endsWithCondition(id: Int): Boolean = lifted[id]?.condition != null

    private fun endsWithSwitch(id: Int): Boolean = lifted[id]?.switchSubject != null

    private fun endsWithReturn(id: Int): Boolean = lifted[id]?.returns != null

    /**
     * Structures blocks `[from, to)` into a statement list.
     *
     * [breakAt] is the block a `break` lands on - the innermost loop's or
     * switch's exit. [exits] holds the blocks that running off the end of this
     * region reaches anyway: the region's own join point, and where that is also
     * the end of everything enclosing it, every join point it passes through.
     * A jump to one of those from the last block says nothing, so it is dropped.
     */
    private fun structureRange(
        from: Int,
        to: Int,
        breakAt: Int = -1,
        exits: Set<Int> = emptySet(),
        /** A jump here goes back to a loop test. */
        continueTarget: Int = -1,
    ): List<Stmt> {
        val out = ArrayList<Stmt>()
        var id = from
        // Whether control reaches the end of the region by falling out of it
        // rather than by jumping or returning. Where it does, and the block it
        // falls into is not one the region's exits lead to, the jump has to be
        // written out - the region is followed by an else-arm or by the next
        // case, not by the block that came next in the bytecode.
        var fallsOut = false
        while (id < to) {
            val block = lifted[id]
            if (block == null || !live(id)) {
                id++
                continue
            }
            if (id in labelled) out.add(Label("L$id"))
            // A rotated loop re-runs its header on every pass, so those
            // statements belong inside the loop, not in front of it.
            if (!rotatesInto(id, to)) out.addAll(block.statements)
            fallsOut = true

            when {
                endsWithReturn(id) -> {
                    out.add(Return(block.returns!!))
                    fallsOut = false
                    id++
                }

                endsWithSwitch(id) -> {
                    id = structureSwitch(id, to, out, breakAt, exits, continueTarget)
                }

                endsWithCondition(id) -> {
                    id = structureConditional(id, to, out, breakAt, exits, continueTarget)
                }

                endsWithGoto(id) -> {
                    val target = branchTarget(id)
                    val last = nextLiveBlock(id + 1, to) < 0
                    when {
                        target < 0 -> Unit
                        target == continueTarget -> if (!last) out.add(Continue())
                        // Falling out of the region reaches its exits, so a jump
                        // to one from the last block says nothing. From anywhere
                        // else it says everything: the blocks after it in the
                        // region would otherwise run.
                        target in exits -> if (!last) {
                            labelled.add(target)
                            out.add(Goto("L$target"))
                        }
                        // A `break` is never implied: falling out of a loop body
                        // goes back to the test, not past the loop.
                        target == breakAt -> out.add(Break())
                        else -> {
                            labelled.add(target)
                            out.add(Goto("L$target"))
                        }
                    }
                    fallsOut = false
                    id++
                }

                else -> id++
            }
        }
        if (fallsOut && !alwaysExits(out) && id == to && to !in exits &&
            to in cfg.blocks.indices && live(to)
        ) {
            when {
                to == continueTarget -> Unit
                to == breakAt -> out.add(Break())
                else -> {
                    labelled.add(to)
                    out.add(Goto("L$to"))
                }
            }
        }
        return out
    }

    /**
     * Where an arm ending at [join] can jump to and still just be finishing.
     *
     * Running off the end of the arm continues after the construct it belongs
     * to, which is [join] while the enclosing region still has blocks left. Once
     * [join] is the enclosing region's own end there is nothing after the
     * construct, so finishing the arm finishes the enclosing region too and
     * everything *it* could leave through is what finishing reaches.
     */
    private fun joined(join: Int, to: Int, exits: Set<Int>): Set<Int> = when {
        // The enclosing region still has blocks to emit, so it carries on there.
        join < to -> setOf(join)
        // The enclosing region ends by falling out, so finishing the arm
        // finishes it too and reaches whatever it reaches.
        to in exits -> exits
        // The enclosing region ends with a jump written out, which lands here.
        else -> setOf(to)
    }

    /**
     * The enclosing exits a switch arm inherits, without the switch's own end:
     * an arm reaches that by `break`, which is written out.
     */
    private fun carried(join: Int, to: Int, exits: Set<Int>): Set<Int> =
        if (join == to) exits - join else emptySet()

    /** True when control cannot run off the end of a statement list. */
    private fun alwaysExits(statements: List<Stmt>): Boolean = when (val last = statements.lastOrNull()) {
        is Return, is Goto, is Break, is Continue -> true
        is If -> last.otherwise.isNotEmpty() && alwaysExits(last.then) && alwaysExits(last.otherwise)
        // A switch only accounts for every value when it has a default arm, and
        // a `break` is a way out of the switch rather than out of the region.
        is Switch -> last.default.isNotEmpty() && alwaysExits(last.default) &&
            last.cases.all { alwaysExits(it.body) && it.body.none { statement -> statement is Break } }
        else -> false
    }

    /**
     * Handles a block ending in a conditional branch.
     *
     * Three shapes are recognised, in order: a `while` loop (the region the
     * branch skips ends by jumping back here), an `if`/`else` (the then-region
     * ends by jumping past the else-region), and a bare `if`.
     */
    /**
     * A conditional branch reduced to "run [regionStart, skipTarget) when
     * [condition] holds".
     */
    private data class Guard(val condition: Condition, val regionStart: Int, val skipTarget: Int)

    /**
     * The condition guarding block [id]'s branch.
     *
     * A condition compiles to a little graph of tests: blocks that do nothing
     * but compare and branch, joined by lone unconditional branches, with
     * exactly two ways out - into the body when it holds, past it when it does
     * not. Reading that graph back is what recovers `&&`, `||` and every mixture
     * of them at once, including the shapes where one test's failure path is
     * another's success path.
     */
    private fun guardAt(id: Int): Guard? = graphGuard(id) ?: singleGuard(id)

    /** What one node of a condition graph comes out as. */
    private sealed interface Truth

    /** The node reaches the body: the tests behind it all passed. */
    private object Always : Truth

    /** The node reaches the skip target. */
    private object Never : Truth

    private class Holds(val condition: Condition) : Truth

    /**
     * Reads the condition graph starting at [id], or null when it is not one.
     *
     * The tests of one condition are laid out one after another and the body
     * follows them, so the graph is the run of blocks `[id, cut)`, and `cut` is
     * the body. The run is extended for as long as the blocks are tests or lone
     * jumps that nothing outside the run branches into, and the furthest cut
     * that leaves exactly two ways out - into the body at `cut`, and past it -
     * is the condition. Stopping earlier would also be correct, just less of the
     * condition: it would leave the rest of it as nested `if`s.
     */
    private fun graphGuard(id: Int): Guard? {
        if (lifted[id]?.condition == null || branchTarget(id) < 0) return null
        val nodes = LinkedHashSet<Int>()
        nodes.add(id)
        var best: Guard? = null
        var cut = id + 1
        while (true) {
            val sinks = sinksOf(nodes) ?: break
            if (sinks.size == 2 && sinks.min() == cut) {
                val skip = sinks.max()
                val truth = evaluate(id, cut, skip, nodes, HashMap()) as? Holds
                if (truth != null) best = Guard(truth.condition, cut, skip)
            }
            if (cut >= cfg.blocks.size || !absorbable(cut)) break
            // A block something outside the run branches into is a join in its
            // own right: folding it into an expression would leave that jump
            // with nowhere to land. A back edge counts, which is what keeps a
            // loop header out of the condition in front of it.
            if (cfg.blocks[cut].predecessors.any { it !in nodes }) break
            nodes.add(cut)
            cut++
        }
        return best
    }

    /** Where the run of blocks [nodes] can leave to, or null when that is unclear. */
    private fun sinksOf(nodes: Set<Int>): Set<Int>? {
        val out = LinkedHashSet<Int>()
        for (node in nodes) {
            for (next in successorsOf(node) ?: return null) {
                if (next < 0) return null
                if (next !in nodes) out.add(next)
            }
        }
        return out
    }

    /** Where a graph node can go: its branch target, and its fall-through. */
    private fun successorsOf(id: Int): List<Int>? {
        val block = lifted[id] ?: return null
        val target = branchTarget(id)
        if (target < 0) return null
        if (block.condition == null) return listOf(target)
        val fall = id + 1
        if (fall !in cfg.blocks.indices) return null
        return listOf(target, fall)
    }

    /** True when a block is a test or a lone jump, and so part of a condition. */
    private fun absorbable(id: Int): Boolean {
        val block = lifted[id] ?: return false
        if (block.statements.isNotEmpty() || block.returns != null || block.switchSubject != null) {
            return false
        }
        return block.condition != null || isLoneBranch(id)
    }

    private fun isLoneBranch(id: Int): Boolean {
        val block = cfg.blocks[id]
        return block.end - block.start == 1 && Cs2Cfg.isUnconditionalBranch(script[block.start].op)
    }

    /** What reaching [node] means, as a condition over the tests behind it. */
    private fun evaluate(
        node: Int,
        body: Int,
        skip: Int,
        nodes: Set<Int>,
        memo: HashMap<Int, Truth?>,
    ): Truth? {
        if (node == body) return Always
        if (node == skip) return Never
        if (node !in nodes) return null
        memo[node]?.let { return it }
        // A node still being worked out is a cycle, which is a loop edge rather
        // than part of a condition.
        if (memo.containsKey(node)) return null
        memo[node] = null

        val block = lifted[node]!!
        val test = block.condition
        val result = if (test == null) {
            evaluate(branchTarget(node), body, skip, nodes, memo)
        } else {
            val taken = evaluate(branchTarget(node), body, skip, nodes, memo)
            val fall = evaluate(node + 1, body, skip, nodes, memo)
            if (taken == null || fall == null) null else combine(test, taken, fall)
        }
        memo[node] = result
        return result
    }

    /**
     * `test ? whenTrue : whenFalse` as a condition, where one exists.
     *
     * A test whose two ways out are the body and the skip target is the
     * condition itself; everything else is one operand of an `&&` or an `||`.
     * The last case is the one that gives `(a && b) || c` its shape: the failing
     * `&&` falls into the alternative that the passing one also ends at, so the
     * two sides share a tail.
     */
    private fun combine(test: Condition, whenTrue: Truth, whenFalse: Truth): Truth? {
        // A test whose two ways out land in the same place decides nothing, but
        // it still runs - it can call something. Folding it into the condition
        // would drop it, so the graph stops here and it becomes a test of its
        // own, guarding nothing.
        if (whenTrue is Always && whenFalse is Always) return null
        if (whenTrue is Never && whenFalse is Never) return null
        if (whenTrue is Always && whenFalse is Never) return Holds(test)
        if (whenTrue is Never && whenFalse is Always) return Holds(test.negate())
        if (whenTrue is Always) return Holds(anyOf(test, condition(whenFalse)))
        if (whenFalse is Never) return Holds(allOf(test, condition(whenTrue)))
        if (whenTrue is Never) return Holds(allOf(test.negate(), condition(whenFalse)))
        if (whenFalse is Always) return Holds(anyOf(test.negate(), condition(whenTrue)))
        val taken = condition(whenTrue)
        val fall = condition(whenFalse)
        shareTail(test, taken, fall)?.let { return Holds(it) }
        shareTail(test.negate(), fall, taken)?.let { return Holds(it) }
        shareGuard(test, taken, fall)?.let { return Holds(it) }
        shareGuard(test.negate(), fall, taken)?.let { return Holds(it) }
        return null
    }

    private fun condition(truth: Truth): Condition = (truth as Holds).condition

    /**
     * `test ? (head || tail) : tail` is `(test && head) || tail`: the same tests
     * in the same order, which is what the graph laid out.
     */
    private fun shareTail(test: Condition, longer: Condition, shorter: Condition): Condition? {
        if (longer !is OrAny) return null
        val tail = if (shorter is OrAny) shorter.terms else listOf(shorter)
        if (tail.size >= longer.terms.size) return null
        if (longer.terms.takeLast(tail.size) != tail) return null
        val head = longer.terms.dropLast(tail.size)
        val guarded = allOf(test, if (head.size == 1) head.first() else OrAny(head))
        return OrAny(listOf(guarded) + tail)
    }

    /**
     * `test ? tail : (head && tail)` is `(test || head) && tail`: the same tests
     * in the same order again, which is how an `||` in front of an `&&` lands.
     */
    private fun shareGuard(test: Condition, shorter: Condition, longer: Condition): Condition? {
        if (longer !is AndAll) return null
        val tail = if (shorter is AndAll) shorter.terms else listOf(shorter)
        if (tail.size >= longer.terms.size) return null
        if (longer.terms.takeLast(tail.size) != tail) return null
        val head = longer.terms.dropLast(tail.size)
        val guard = anyOf(test, if (head.size == 1) head.first() else AndAll(head))
        return AndAll(listOf(guard) + tail)
    }

    private fun allOf(left: Condition, right: Condition): Condition {
        val terms = ArrayList<Condition>()
        if (left is AndAll) terms.addAll(left.terms) else terms.add(left)
        if (right is AndAll) terms.addAll(right.terms) else terms.add(right)
        return AndAll(terms)
    }

    private fun anyOf(left: Condition, right: Condition): Condition {
        val terms = ArrayList<Condition>()
        if (left is OrAny) terms.addAll(left.terms) else terms.add(left)
        if (right is OrAny) terms.addAll(right.terms) else terms.add(right)
        return OrAny(terms)
    }

    /**
     * The fallback when the graph is not one: the block's own test, read as
     * skipping the region it branches past.
     *
     * A branch to the very next block skips nothing, so it keeps its own sense
     * and guards an empty region - which is what the bytecode it compiles back
     * to reads as.
     */
    private fun singleGuard(id: Int): Guard? {
        val first = lifted[id]?.condition ?: return null
        val taken = branchTarget(id)
        if (taken < 0) return null
        if (taken == id + 1) return Guard(first, taken, taken)
        return Guard(first.negate(), id + 1, taken)
    }

    /**
     * True when [id] is a loop header that does work before its test, so the
     * back edge lands on that work rather than on the comparison.
     */
    private fun rotatesInto(id: Int, to: Int): Boolean {
        if (!endsWithCondition(id) || lifted[id]?.statements.isNullOrEmpty()) return false
        val skipTarget = guardAt(id)?.skipTarget ?: return false
        if (skipTarget <= id || skipTarget > to) return false
        val tail = skipTarget - 1
        return tail > id && endsWithGoto(tail) && branchTarget(tail) == id
    }

    /**
     * True when a block does nothing but jump *and* nothing else jumps to it.
     * Folding one away that another branch targets would leave that branch with
     * nowhere to land.
     */
    private fun isTrampoline(id: Int): Boolean {
        val block = cfg.blocks[id]
        return block.end - block.start == 1 &&
            Cs2Cfg.isUnconditionalBranch(script[block.start].op) &&
            lifted[id]?.statements.isNullOrEmpty() &&
            block.predecessors.size <= 1
    }

    private fun structureConditional(
        id: Int,
        to: Int,
        out: MutableList<Stmt>,
        breakAt: Int,
        exits: Set<Int>,
        continueTarget: Int,
    ): Int {
        val guard = guardAt(id) ?: return id + 1
        val (condition, regionStart, skipTarget) = guard

        // A test that skips nothing still has to run: it guards an empty body,
        // wherever in the region it sits.
        if (regionStart == skipTarget) {
            out.add(If(condition, emptyList(), emptyList()))
            return skipTarget
        }

        // Inside a loop, skipping the rest of the body leaves the loop, which is
        // not what running off the end of it does: written as an `if` around the
        // rest, control would fall back into the loop instead of out of it.
        if (continueTarget >= 0 && skipTarget == breakAt && skipTarget == to &&
            regionStart in (id + 1)..to
        ) {
            out.add(If(condition.negate(), listOf(Break()), emptyList()))
            return regionStart
        }

        // A branch out of this region is still just finishing it when it lands
        // where finishing lands, so the rest of the region is what it guards.
        if (skipTarget > to && skipTarget in exits && regionStart in (id + 1)..to) {
            val then = structureRange(regionStart, to, breakAt, exits, continueTarget)
            out.add(If(condition, then, emptyList()))
            return to
        }

        // Anything else that leaves the region cannot be structured here. Fall
        // back to a guarded jump: consuming a chain's blocks would leave the
        // enclosing region to emit them a second time.
        if (skipTarget <= id || skipTarget > to || regionStart >= to) {
            val target = branchTarget(id)
            // Consecutive tests that all jump to the same place are one `||`:
            // each is tried in turn and any of them being true takes the jump.
            val terms = ArrayList<Condition>()
            var cursor = id
            while (cursor < to) {
                val block = lifted[cursor] ?: break
                val term = block.condition ?: break
                if (cursor != id && block.statements.isNotEmpty()) break
                if (branchTarget(cursor) != target) break
                terms.add(term)
                cursor++
            }
            labelled.add(target.coerceIn(0, cfg.blocks.size - 1))
            val test = if (terms.size == 1) terms.first() else OrAny(terms)
            out.add(If(test, listOf(Goto("L$target")), emptyList()))
            return cursor
        }

        val lastOfRegion = skipTarget - 1
        if (lastOfRegion >= regionStart && endsWithGoto(lastOfRegion)) {
            val jump = branchTarget(lastOfRegion)

            // while: the body jumps back to the test.
            if (jump == id) {
                val body = structureRange(regionStart, skipTarget, skipTarget, continueTarget = id)
                if (rotatesInto(id, to)) {
                    // The back edge lands on the header's own statements, so the
                    // test happens part way through the loop rather than at its
                    // top - which is `while (true)` with the test written out.
                    val header = lifted[id]!!.statements +
                        If(condition.negate(), listOf(Break()), emptyList())
                    out.add(While(null, header + body))
                } else {
                    out.add(While(condition, body))
                }
                return skipTarget
            }

            // if/else: the then-region jumps past the else-region.
            if (jump in (skipTarget + 1)..to) {
                val join = joined(jump, to, exits)
                val then = structureRange(regionStart, skipTarget, breakAt, join, continueTarget)
                val otherwise = structureRange(skipTarget, jump, breakAt, join, continueTarget)
                out.add(If(condition, then, otherwise))
                return jump
            }
        }

        val then = structureRange(
            regionStart, skipTarget, breakAt, joined(skipTarget, to, exits), continueTarget,
        )
        out.add(If(condition, then, emptyList()))
        return skipTarget
    }

    /**
     * Handles a `SWITCH`. Case bodies run in address order and each ends by
     * jumping to the block after the statement, which becomes the `break`.
     */
    /** The next block in `[from, to)` that belongs in the output, or -1. */
    private fun nextLiveBlock(from: Int, to: Int): Int {
        for (id in from until to) if (live(id)) return id
        return -1
    }

    private fun structureSwitch(
        id: Int,
        to: Int,
        out: MutableList<Stmt>,
        breakAt: Int,
        exits: Set<Int>,
        continueTarget: Int,
    ): Int {
        val block = lifted[id]!!
        val table = script.switchTables.getOrNull(block.switchTable) ?: emptyList()
        val last = cfg.blocks[id].end - 1

        // Group keys that jump to the same block into one case.
        val byTarget = LinkedHashMap<Int, MutableList<Int>>()
        for (case in table) {
            val targetBlock = cfg.blockStartingAt(last + case.offset + 1)?.id ?: continue
            byTarget.getOrPut(targetBlock) { ArrayList() }.add(case.key)
        }

        // The block right after the switch jumps to the default arm, which sits
        // past every case body.
        val afterSwitch = id + 1
        val jumpsToDefault = afterSwitch < cfg.blocks.size && isTrampoline(afterSwitch)
        val defaultTarget = if (jumpsToDefault) branchTarget(afterSwitch) else afterSwitch

        val caseStarts = byTarget.keys.sorted()
        if (caseStarts.isEmpty()) {
            out.add(Switch(block.switchSubject!!, emptyList(), emptyList(), block.switchTable))
            return afterSwitch
        }

        var end = switchEnd(caseStarts, afterSwitch, defaultTarget, to)

        // With the default arm laid out first, the cases follow it and the
        // switch only ends where the default arm jumps to. Taking the no-match
        // target for the end would put every case body outside the switch and
        // lose the table.
        if (!jumpsToDefault) {
            val tail = caseStarts.min() - 1
            if (tail >= afterSwitch && endsWithGoto(tail)) {
                val target = branchTarget(tail)
                if (target > caseStarts.max() && target <= to) end = target
            }
        }

        // The default arm runs from where the no-match path lands until the next
        // case body starts, or to the join point when none follows it. Bounding
        // it this way keeps it from swallowing case bodies whichever layout the
        // compiler used.
        val defaultStop = minOf(end, caseStarts.firstOrNull { it > defaultTarget } ?: end)
        val hasDefault = defaultTarget < defaultStop

        val boundaries = (caseStarts + listOfNotNull(defaultTarget.takeIf { hasDefault }) + end)
            .filter { it <= end }
            .distinct()
            .sorted()

        val cases = ArrayList<SwitchCase>()
        for (start in caseStarts) {
            if (start >= end) continue
            val stop = boundaries.firstOrNull { it > start } ?: end
            val arm = structureRange(start, stop, end, carried(end, to, exits), continueTarget)
            cases.add(SwitchCase(byTarget.getValue(start), arm))
        }
        var default =
            if (hasDefault) structureRange(defaultTarget, defaultStop, end, carried(end, to, exits), continueTarget)
            else emptyList()

        // With no default arm, a case that does nothing lands exactly where a
        // key nothing matches does. Keeping it would say the switch cares about
        // that key, and the bytecode it compiles to no longer even lists it.
        val meaningful =
            if (default.isEmpty()) cases.filter { it.body.isNotEmpty() } else cases.toList()
        cases.clear()
        cases.addAll(meaningful)

        // A switch whose every arm is a bare jump and which has no default arm
        // is how the literal block-by-block rendering writes a jump table, and
        // the code generator reads it as one. Spelling the no-match path out
        // keeps a structured switch that happens to look like that from being
        // compiled as a table, which would let a no-match fall into the first arm.
        if (default.isEmpty() && cases.isNotEmpty() && cases.all { it.body.singleOrNull() is Goto }) {
            default = listOf(Break())
        }

        out.add(Switch(block.switchSubject!!, cases, default, block.switchTable))
        return end
    }

    /**
     * Where a `switch` ends.
     *
     * The no-match jump lands either on a real default arm or straight on the
     * join point. Only a case body's *own* trailing jump can tell them apart:
     * if one jumps past the no-match target, that target is a default arm.
     * Branches from nested `if`s inside a case body must not be mistaken for it.
     */
    private fun switchEnd(caseStarts: List<Int>, afterSwitch: Int, defaultTarget: Int, to: Int): Int {
        var end = to
        var found = false
        for ((index, start) in caseStarts.withIndex()) {
            val boundary = caseStarts.getOrNull(index + 1) ?: defaultTarget
            val tail = boundary - 1
            if (tail < start || tail == afterSwitch || tail >= cfg.blocks.size) continue
            if (!endsWithGoto(tail)) continue
            val target = branchTarget(tail)
            // A case tail jumping exactly to the end of the enclosing region
            // marks a default arm laid out after the cases just as one jumping
            // further does.
            if (target > defaultTarget && target <= end) {
                end = target
                found = true
            }
        }
        return if (found) end else defaultTarget
    }
}
