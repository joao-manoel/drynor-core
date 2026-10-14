package org.darkan.cs2

import org.darkan.cs2.ir.ArrayAssign
import org.darkan.cs2.ir.ArrayDefine
import org.darkan.cs2.ir.ArrayRef
import org.darkan.cs2.ir.Binary
import org.darkan.cs2.ir.Callback
import org.darkan.cs2.ir.Compare
import org.darkan.cs2.ir.Cs2Type
import org.darkan.cs2.ir.Expr
import org.darkan.cs2.ir.ExprStmt
import org.darkan.cs2.ir.IntConst
import org.darkan.cs2.ir.Join
import org.darkan.cs2.ir.LocalAssign
import org.darkan.cs2.ir.LocalRef
import org.darkan.cs2.ir.LongConst
import org.darkan.cs2.ir.MultiAssign
import org.darkan.cs2.ir.OpCall
import org.darkan.cs2.ir.ParallelAssign
import org.darkan.cs2.ir.Return
import org.darkan.cs2.ir.ScriptCall
import org.darkan.cs2.ir.Stmt
import org.darkan.cs2.ir.StrConst
import org.darkan.cs2.ir.TempRef
import org.darkan.cs2.ir.TypedConst
import org.darkan.cs2.ir.VarAssign
import org.darkan.cs2.ir.VarRef
import org.darkan.cs2.ir.VarSpace

/** What a block does, once its instructions are folded back into expressions. */
class LiftedBlock(
    val statements: List<Stmt>,
    /** Set when the block ends in a conditional branch. */
    val condition: Compare?,
    /** Set when the block ends in a `SWITCH`. */
    val switchSubject: Expr?,
    val switchTable: Int,
    val returns: List<Expr>?,
    /** Values still on the stacks at the end, handed to the successor blocks. */
    val exitStack: StackState,
)

/** Expressions left on the three operand stacks at a control-flow edge. */
data class StackState(
    val ints: List<Expr> = emptyList(),
    val strings: List<Expr> = emptyList(),
    val longs: List<Expr> = emptyList(),
) {
    val isEmpty: Boolean get() = ints.isEmpty() && strings.isEmpty() && longs.isEmpty()
}

/**
 * Folds a basic block's instructions back into expression trees.
 *
 * CS2 is a stack machine emitted by an expression compiler, so replaying the
 * stack recovers the original expressions almost exactly: each opcode pops its
 * arguments in reverse order and pushes its results.
 */
class Cs2Lifter(
    private val script: Cs2Script,
    private val context: Cs2Context,
) {
    private var tempCounter = 0

    private val binaryOperators = mapOf(
        Cs2Op.ADD to "+", Cs2Op.SUB to "-", Cs2Op.MULTIPLY to "*", Cs2Op.DIVIDE to "/",
        Cs2Op.MODULO to "%", Cs2Op.AND to "&", Cs2Op.OR to "|",
    )

    fun lift(block: Cs2Cfg.Block, entry: StackState): LiftedBlock {
        val ints = ArrayList(entry.ints)
        val strings = ArrayList(entry.strings)
        val longs = ArrayList(entry.longs)
        val statements = ArrayList<Stmt>()

        // The three stacks are independent, so their contents alone cannot say
        // which value was pushed first. Return values have to go back out in the
        // original order, so live values are also tracked in one ordered list.
        val live = ArrayList<Expr>(entry.ints + entry.strings + entry.longs)

        fun stackFor(type: Cs2Type) = when (type) {
            Cs2Type.INT -> ints
            Cs2Type.STRING -> strings
            Cs2Type.LONG -> longs
        }

        fun pop(type: Cs2Type): Expr {
            val stack = stackFor(type)
            require(stack.isNotEmpty()) { "$type stack underflow in block ${block.id}" }
            val value = stack.removeAt(stack.size - 1)
            val position = live.indexOfLast { it.type == type }
            if (position >= 0) live.removeAt(position)
            return value
        }

        /** Pops `count` values, restoring source order. */
        fun popMany(type: Cs2Type, count: Int): List<Expr> {
            val out = ArrayList<Expr>(count)
            repeat(count) { out.add(pop(type)) }
            out.reverse()
            return out
        }

        fun push(value: Expr) {
            stackFor(value.type).add(value)
            live.add(value)
        }

        /**
         * The top `count` live values in the order they were pushed. Arguments
         * are spread across three stacks, so their relative order is only
         * visible here - and it has to survive to re-compilation.
         */
        fun argumentsInPushOrder(count: Int): List<Expr> = live.takeLast(count)

        var index = block.start
        while (index < block.end) {
            val instruction = script[index]
            val op = instruction.op
            val operand = instruction.intOperand

            // A run of stores with nothing pushed between them is one parallel
            // assignment: every value was computed before any was stored.
            val run = storeRunLength(index, block.end)
            if (run > 1) {
                // The first store takes the value pushed last, so both lists are
                // reversed to put them back in source order.
                val stores = ArrayList<Expr>(run)
                val popped = ArrayList<Expr>(run)
                for (offset in 0 until run) {
                    val store = script[index + offset]
                    val type = storeType(store.op)!!
                    stores.add(storeTarget(store, type))
                    popped.add(pop(type))
                }
                statements.add(ParallelAssign(stores.reversed(), popped.reversed()))
                index += run
                continue
            }

            when {
                Cs2Cfg.isReturn(op) -> return LiftedBlock(
                    statements, null, null, -1, live.toList(), StackState(),
                )

                Cs2Cfg.isConditionalBranch(op) -> {
                    val condition = liftCondition(op, ::pop)
                    return LiftedBlock(
                        statements, condition, null, -1, null,
                        StackState(ints.toList(), strings.toList(), longs.toList()),
                    )
                }

                Cs2Cfg.isSwitch(op) -> {
                    val subject = pop(Cs2Type.INT)
                    return LiftedBlock(
                        statements, null, subject, operand, null,
                        StackState(ints.toList(), strings.toList(), longs.toList()),
                    )
                }

                Cs2Cfg.isUnconditionalBranch(op) -> Unit

                else -> liftInstruction(
                    instruction, statements, ::pop, ::popMany, ::push, ::argumentsInPushOrder,
                )
            }
            index++
        }

        return LiftedBlock(
            statements, null, null, -1, null,
            StackState(ints.toList(), strings.toList(), longs.toList()),
        )
    }

    /** The stack a single-value store consumes, or null when it is not one. */
    private fun storeType(op: Cs2Op): Cs2Type? = when (op) {
        Cs2Op.POP_INT_LOCAL, Cs2Op.POP_VAR, Cs2Op.POP_VARBIT, Cs2Op.POP_VARC_INT -> Cs2Type.INT
        Cs2Op.POP_STRING_LOCAL, Cs2Op.POP_VARC_STRING -> Cs2Type.STRING
        Cs2Op.POP_LONG_LOCAL -> Cs2Type.LONG
        else -> null
    }

    /** Where a single-value store puts its value. */
    private fun storeTarget(instruction: Cs2Instruction, type: Cs2Type): Expr =
        when (instruction.op) {
            Cs2Op.POP_INT_LOCAL, Cs2Op.POP_STRING_LOCAL, Cs2Op.POP_LONG_LOCAL ->
                LocalRef(instruction.intOperand, type)
            Cs2Op.POP_VAR -> VarRef(VarSpace.VARP, instruction.intOperand, Cs2Type.INT)
            Cs2Op.POP_VARBIT -> VarRef(VarSpace.VARBIT, instruction.intOperand, Cs2Type.INT)
            Cs2Op.POP_VARC_INT -> VarRef(VarSpace.VARC, instruction.intOperand, Cs2Type.INT)
            Cs2Op.POP_VARC_STRING -> VarRef(VarSpace.VARC_STRING, instruction.intOperand, Cs2Type.STRING)
            else -> error("Not a store: ${instruction.op.name}")
        }

    /**
     * How many consecutive local stores of the *same* type start at [index].
     *
     * Same-type runs pop in exact reverse of the push order, so the pairing is
     * unambiguous. A mixed-type run draws from different stacks and its push
     * order cannot be recovered positionally, so it is left as separate stores.
     */
    private fun storeRunLength(index: Int, end: Int): Int {
        val type = storeType(script[index].op) ?: return 0
        var length = 0
        while (index + length < end && storeType(script[index + length].op) == type) length++
        return length
    }

    private fun liftCondition(op: Cs2Op, pop: (Cs2Type) -> Expr): Compare {
        // `BRANCH_IF_TRUE` / `BRANCH_IF_FALSE` compare a single value against a
        // literal; everything else pops both sides.
        val longs = Cs2Cfg.isLongBranch(op)
        val type = if (longs) Cs2Type.LONG else Cs2Type.INT
        return when (op) {
            Cs2Op.BRANCH_IF_TRUE -> Compare(op, pop(type), IntConst(1), negated = false)
            Cs2Op.BRANCH_IF_FALSE -> Compare(op, pop(type), IntConst(0), negated = false)
            else -> {
                val right = pop(type)
                val left = pop(type)
                Compare(op, left, right, negated = false)
            }
        }
    }

    private fun liftInstruction(
        instruction: Cs2Instruction,
        statements: MutableList<Stmt>,
        pop: (Cs2Type) -> Expr,
        popMany: (Cs2Type, Int) -> List<Expr>,
        push: (Expr) -> Unit,
        argumentsInPushOrder: (Int) -> List<Expr>,
    ) {
        val op = instruction.op
        val operand = instruction.intOperand

        when (op) {
            Cs2Op.PUSH_CONSTANT_INT -> push(IntConst(operand))
            Cs2Op.PUSH_CONSTANT_STRING -> push(StrConst(instruction.strOperand ?: ""))
            Cs2Op.PUSH_LONG_CONSTANT -> push(LongConst(instruction.longOperand))

            Cs2Op.PUSH_INT_LOCAL -> push(LocalRef(operand, Cs2Type.INT))
            Cs2Op.PUSH_STRING_LOCAL -> push(LocalRef(operand, Cs2Type.STRING))
            Cs2Op.PUSH_LONG_LOCAL -> push(LocalRef(operand, Cs2Type.LONG))

            Cs2Op.POP_INT_LOCAL -> statements.add(LocalAssign(operand, Cs2Type.INT, pop(Cs2Type.INT), false))
            Cs2Op.POP_STRING_LOCAL -> statements.add(LocalAssign(operand, Cs2Type.STRING, pop(Cs2Type.STRING), false))
            Cs2Op.POP_LONG_LOCAL -> statements.add(LocalAssign(operand, Cs2Type.LONG, pop(Cs2Type.LONG), false))

            Cs2Op.POP_INT_DISCARD -> statements.add(ExprStmt(pop(Cs2Type.INT)))
            Cs2Op.POP_STRING_DISCARD -> statements.add(ExprStmt(pop(Cs2Type.STRING)))
            Cs2Op.POP_LONG_DISCARD -> statements.add(ExprStmt(pop(Cs2Type.LONG)))

            Cs2Op.PUSH_VAR -> push(VarRef(VarSpace.VARP, operand, Cs2Type.INT))
            Cs2Op.POP_VAR -> statements.add(VarAssign(VarRef(VarSpace.VARP, operand, Cs2Type.INT), pop(Cs2Type.INT)))
            Cs2Op.PUSH_VARBIT -> push(VarRef(VarSpace.VARBIT, operand, Cs2Type.INT))
            Cs2Op.POP_VARBIT -> statements.add(VarAssign(VarRef(VarSpace.VARBIT, operand, Cs2Type.INT), pop(Cs2Type.INT)))
            Cs2Op.PUSH_VARC_INT -> push(VarRef(VarSpace.VARC, operand, Cs2Type.INT))
            Cs2Op.POP_VARC_INT -> statements.add(VarAssign(VarRef(VarSpace.VARC, operand, Cs2Type.INT), pop(Cs2Type.INT)))
            Cs2Op.PUSH_VARC_STRING -> push(VarRef(VarSpace.VARC_STRING, operand, Cs2Type.STRING))
            Cs2Op.POP_VARC_STRING ->
                statements.add(VarAssign(VarRef(VarSpace.VARC_STRING, operand, Cs2Type.STRING), pop(Cs2Type.STRING)))
            Cs2Op.GET_VARP_OLD -> push(VarRef(VarSpace.VARP, operand, Cs2Type.INT, "old"))
            Cs2Op.GET_VARPBIT_OLD -> push(VarRef(VarSpace.VARBIT, operand, Cs2Type.INT, "old"))
            Cs2Op.GET_VARN_OLD -> push(VarRef(VarSpace.VARN, operand, Cs2Type.INT, "old"))
            Cs2Op.GET_VARNBIT_OLD -> push(VarRef(VarSpace.VARNBIT, operand, Cs2Type.INT, "old"))

            Cs2Op.DEFINE_ARRAY ->
                statements.add(ArrayDefine(operand shr 16, operand and 0xFFFF, pop(Cs2Type.INT)))
            Cs2Op.PUSH_ARRAY_INT -> push(ArrayRef(operand, pop(Cs2Type.INT)))
            Cs2Op.POP_ARRAY_INT -> {
                val value = pop(Cs2Type.INT)
                val index = pop(Cs2Type.INT)
                statements.add(ArrayAssign(operand, index, value))
            }

            Cs2Op.JOIN_STRING -> push(Join(popMany(Cs2Type.STRING, operand)))

            Cs2Op.GOSUB_WITH_PARAMS -> {
                val callee = context.script(operand) ?: error("GOSUB to missing script $operand")
                val args = argumentsInPushOrder(
                    callee.intArgsCount + callee.stringArgsCount + callee.longArgsCount,
                )
                popMany(Cs2Type.LONG, callee.longArgsCount)
                popMany(Cs2Type.STRING, callee.stringArgsCount)
                popMany(Cs2Type.INT, callee.intArgsCount)
                val returns = callee.returnSignature ?: ReturnSignature.EMPTY
                emitResults(
                    ScriptCall(operand, args, resultType(returns)),
                    returns, statements, push, callee.returnOrder,
                )
            }

            else -> liftGenericOp(instruction, statements, pop, popMany, push, argumentsInPushOrder)
        }
    }

    private fun liftGenericOp(
        instruction: Cs2Instruction,
        statements: MutableList<Stmt>,
        pop: (Cs2Type) -> Expr,
        popMany: (Cs2Type, Int) -> List<Expr>,
        push: (Expr) -> Unit,
        argumentsInPushOrder: (Int) -> List<Expr>,
    ) {
        val op = instruction.op

        if (op.kind == OpKind.HOOK) {
            statements.add(ExprStmt(liftHook(instruction, pop, popMany)))
            return
        }

        val symbol = binaryOperators[op]
        if (symbol != null && op.popInt == 2 && op.pushInt == 1) {
            val right = pop(Cs2Type.INT)
            val left = pop(Cs2Type.INT)
            push(Binary(symbol, left, right, Cs2Type.INT))
            return
        }

        val raw = argumentsInPushOrder(op.popInt + op.popStr + op.popLong)
        popMany(Cs2Type.LONG, op.popLong)
        popMany(Cs2Type.STRING, op.popStr)
        val intArgs = popMany(Cs2Type.INT, op.popInt)
        val args = applyArgTypes(op, raw)

        val signature = pushCounts(op, intArgs)
        if (signature.total == 0) {
            statements.add(ExprStmt(OpCall(op, args, Cs2Type.INT, instruction.intOperand)))
            return
        }
        emitResults(
            OpCall(op, args, resultType(signature), instruction.intOperand),
            signature, statements, push,
        )
    }

    /** The type a hook format character names, where it is known. */
    private fun hookArgType(spec: Char): ArgType? = when (spec) {
        'I' -> ArgType.COMPONENT
        else -> null
    }

    /**
     * Replaces constant arguments with typed ones where the opcode says what
     * they mean. Only literals are rewritten - an expression keeps its shape.
     */
    private fun applyArgTypes(op: Cs2Op, args: List<Expr>): List<Expr> {
        if (op.argTypes.isEmpty()) return args
        var intIndex = 0
        return args.map { argument ->
            if (argument.type != Cs2Type.INT) return@map argument
            val kind = op.argTypes.getOrNull(intIndex++) ?: ArgType.INT
            if (kind == ArgType.INT || argument !is IntConst) argument
            else TypedConst(argument.value, kind)
        }
    }

    /**
     * Result counts for one instruction.
     *
     * `ENUM` is self-describing: its second argument is the value type as a
     * character code. The other polymorphic opcodes take the param id as their
     * last int argument and the type comes from the param definition.
     */
    private fun pushCounts(op: Cs2Op, intArgs: List<Expr>): ReturnSignature {
        if (op.kind != OpKind.PARAM) {
            return ReturnSignature(op.pushInt, op.pushStr, op.pushLong)
        }
        val yieldsString = if (op == Cs2Op.ENUM) {
            (intArgs.getOrNull(1) as? IntConst)?.value == 's'.code
        } else {
            val paramId = (intArgs.lastOrNull() as? IntConst)?.value
                ?: error("${op.name} needs a constant param id")
            context.paramIsString(paramId)
        }
        return if (yieldsString) ReturnSignature(0, 1, 0) else ReturnSignature(1, 0, 0)
    }

    private fun resultType(signature: ReturnSignature): Cs2Type = when {
        signature.strings > 0 && signature.ints == 0 && signature.longs == 0 -> Cs2Type.STRING
        signature.longs > 0 && signature.ints == 0 && signature.strings == 0 -> Cs2Type.LONG
        else -> Cs2Type.INT
    }

    /**
     * Pushes a call's results. A single result becomes the expression itself; a
     * call producing several binds them all in one statement so the emitted
     * TypeScript can destructure it.
     */
    private fun emitResults(
        call: Expr,
        signature: ReturnSignature,
        statements: MutableList<Stmt>,
        push: (Expr) -> Unit,
        /** The callee's push order, when known; results are bound in it. */
        order: List<Cs2Type> = emptyList(),
    ) {
        if (signature.total == 0) {
            statements.add(ExprStmt(call))
            return
        }
        if (signature.total == 1) {
            push(call)
            return
        }
        // The name carries the type: a destructured binding has no annotation,
        // so this is how the compiler knows which stack each result came off.
        val types = if (order.size == signature.total) order else {
            List(signature.ints) { Cs2Type.INT } +
                List(signature.strings) { Cs2Type.STRING } +
                List(signature.longs) { Cs2Type.LONG }
        }
        val names = types.map { type ->
            val prefix = when (type) {
                Cs2Type.INT -> "tmpInt"
                Cs2Type.STRING -> "tmpStr"
                Cs2Type.LONG -> "tmpLong"
            }
            TempRef("$prefix${tempCounter++}", type)
        }
        statements.add(MultiAssign(names, call))
        names.forEach(push)
    }

    /**
     * Hook setters take a callback described by a format string: one character
     * per bound argument, optionally followed by `Y` for a trigger list.
     */
    private fun liftHook(
        instruction: Cs2Instruction,
        pop: (Cs2Type) -> Expr,
        popMany: (Cs2Type, Int) -> List<Expr>,
    ): Expr {
        val op = instruction.op
        val component = if (op.popInt == 1) pop(Cs2Type.INT) else null
        val formatExpr = pop(Cs2Type.STRING)
        val format = (formatExpr as? StrConst)?.value
            ?: error("${op.name} needs a constant format string")

        var spec = format
        var triggers = emptyList<Expr>()
        if (op.hasTriggerArray && spec.endsWith("Y")) {
            spec = spec.dropLast(1)
            val count = (pop(Cs2Type.INT) as? IntConst)?.value
                ?: error("${op.name} needs a constant trigger-list size")
            triggers = popMany(Cs2Type.INT, count)
        }

        // Bound arguments were pushed in order, so pop them back to front. The
        // spec character also names the type: `I` marks a component.
        val args = arrayOfNulls<Expr>(spec.length)
        for (position in spec.indices.reversed()) {
            args[position] = when (spec[position]) {
                's' -> pop(Cs2Type.STRING)
                '§' -> pop(Cs2Type.LONG)
                else -> {
                    val value = pop(Cs2Type.INT)
                    val kind = hookArgType(spec[position])
                    if (kind != null && value is IntConst) TypedConst(value.value, kind) else value
                }
            }
        }
        val scriptId = (pop(Cs2Type.INT) as? IntConst)?.value ?: -1

        val callback = Callback(scriptId, format, args.filterNotNull(), triggers)
        val callArgs = if (component != null) listOf(callback, component) else listOf(callback)
        return OpCall(op, callArgs, Cs2Type.INT, instruction.intOperand)
    }
}
