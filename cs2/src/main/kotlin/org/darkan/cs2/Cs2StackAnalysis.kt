package org.darkan.cs2

import org.darkan.cs2.ir.Cs2Type

/** Values an instruction takes off and puts back on the three operand stacks. */
data class StackEffect(
    val popInt: Int = 0,
    val popStr: Int = 0,
    val popLong: Int = 0,
    val pushInt: Int = 0,
    val pushStr: Int = 0,
    val pushLong: Int = 0,
)

/** Everything the effective-signature calculation needs but cannot see locally. */
interface Cs2Context {
    /** Header + return signature of a called script, or null when it is missing. */
    fun script(id: Int): Cs2Script?

    /** True when a `*_PARAM` opcode with this param id yields a string. */
    fun paramIsString(paramId: Int): Boolean

    /** The cache's own type character for a param, or 0 when it has none. */
    fun paramType(paramId: Int): Char = 0.toChar()

    /**
     * What a script leaves on the stacks, in the order it pushes them.
     *
     * The compiler needs this to type the names in `let [a, b] = f();`, which carry no annotation
     * of their own: the order is the callee's own push order where something has revealed it, and
     * the grouped signature otherwise - exactly the order the decompiler binds the results in.
     *
     * Derived from [script] rather than answered separately, so a context that can produce a
     * callee's header at all produces this too: the whole-cache analysis
     * ([Cs2Analyzer]) recovers the order from what callers push, and a context built from a
     * folder of sources ([Cs2SourceContext]) reads it off each script's own return annotation.
     */
    fun returnTypes(scriptId: Int): List<Cs2Type> {
        val script = script(scriptId) ?: return emptyList()
        val signature = script.returnSignature ?: ReturnSignature.EMPTY
        script.returnOrder.takeIf { it.size == signature.total }?.let { return it }
        return List(signature.ints) { Cs2Type.INT } +
            List(signature.strings) { Cs2Type.STRING } +
            List(signature.longs) { Cs2Type.LONG }
    }
}

/**
 * The stack effect of one instruction, given what is already on the stacks.
 *
 * Most opcodes have a fixed signature. The exceptions all depend on operands or
 * on constants sitting on the stack:
 *  - `JOIN_STRING` joins `operand` strings.
 *  - `GOSUB_WITH_PARAMS` takes the callee's arguments and leaves its returns.
 *  - hook setters pop a format string, then one value per character it lists,
 *    plus a trailing int array when the format ends in `Y`.
 *  - `*_PARAM` opcodes push an int or a string depending on the param's type.
 */
fun effectiveSignature(
    instruction: Cs2Instruction,
    context: Cs2Context,
    stacks: SymbolicStacks,
): StackEffect {
    val op = instruction.op
    return when (op.kind) {
        OpKind.NORMAL -> StackEffect(
            op.popInt, op.popStr, op.popLong, op.pushInt, op.pushStr, op.pushLong,
        )

        OpKind.JOIN_STRING -> StackEffect(popStr = instruction.intOperand, pushStr = 1)

        OpKind.GOSUB -> {
            val callee = context.script(instruction.intOperand)
                ?: error("GOSUB to missing script ${instruction.intOperand}")
            val returns = callee.returnSignature ?: ReturnSignature.EMPTY
            StackEffect(
                popInt = callee.intArgsCount,
                popStr = callee.stringArgsCount,
                popLong = callee.longArgsCount,
                pushInt = returns.ints,
                pushStr = returns.strings,
                pushLong = returns.longs,
            )
        }

        OpKind.PARAM -> {
            val yieldsString = if (op == Cs2Op.ENUM) enumYieldsString(stacks) else {
                val paramId = stacks.topInt()
                    ?: error("${op.name} needs a constant param id to resolve its result type")
                context.paramIsString(paramId)
            }
            if (yieldsString) {
                StackEffect(op.popInt, op.popStr, op.popLong, pushStr = 1)
            } else {
                StackEffect(op.popInt, op.popStr, op.popLong, pushInt = 1)
            }
        }

        OpKind.HOOK -> hookSignature(op, stacks)
    }
}

/**
 * `ENUM` pops `keyType, valueType, enumId, key`. It is self-describing: the
 * value type is a character code pushed as a constant, so no param table is
 * needed to know which stack the result lands on.
 */
private fun enumYieldsString(stacks: SymbolicStacks): Boolean {
    val valueType = stacks.intBelowTop(2)
        ?: error("ENUM needs a constant value type to resolve its result type")
    return valueType == 's'.code
}

private fun hookSignature(op: Cs2Op, stacks: SymbolicStacks): StackEffect {
    val format = stacks.topString()
        ?: error("${op.name} needs a constant format string")

    var popInt = op.popInt + 1 // component id (IF_ forms) plus the callback script id
    var popStr = 1 // the format string itself
    var popLong = 0

    var spec = format
    if (op.hasTriggerArray && spec.endsWith("Y")) {
        spec = spec.dropLast(1)
        // The trigger list is `count` ints preceded by the count itself. It sits
        // under the component id for the IF_ forms, and on top for the CC_ ones.
        val count = stacks.intBelowTop(op.popInt)
            ?: error("${op.name} needs a constant trigger-list size")
        popInt += 1 + count
    }
    for (char in spec) {
        when (char) {
            's' -> popStr++
            '§' -> popLong++
            else -> popInt++
        }
    }
    return StackEffect(popInt, popStr, popLong)
}

/**
 * Operand stacks during simulation, tracking constant values where they are
 * known. Hook setters and `*_PARAM` opcodes need to read constants that were
 * pushed a few instructions earlier, so depth alone is not enough.
 */
class SymbolicStacks {
    private val ints = ArrayList<Int?>()
    private val strings = ArrayList<String?>()
    private var longDepth = 0

    /**
     * Types of the live values in the order they were pushed. The three stacks
     * are independent, so this is the only record of how they interleave - which
     * is exactly what a script's return order is.
     */
    private val live = ArrayList<Cs2Type>()

    /** The live values' types, oldest first. */
    fun order(): List<Cs2Type> = live.toList()

    val intDepth: Int get() = ints.size
    val stringDepth: Int get() = strings.size
    val longs: Int get() = longDepth

    val isEmpty: Boolean get() = ints.isEmpty() && strings.isEmpty() && longDepth == 0

    fun topInt(): Int? = ints.lastOrNull()

    /** The int `depth` slots below the top, or null when unknown. */
    fun intBelowTop(depth: Int): Int? = ints.getOrNull(ints.size - 1 - depth)

    fun topString(): String? = strings.lastOrNull()

    fun apply(effect: StackEffect, pushedIntConstant: Int? = null, pushedString: String? = null) {
        repeat(effect.popInt) {
            require(ints.isNotEmpty()) { "int stack underflow" }
            ints.removeAt(ints.size - 1)
            live.removeLastOfType(Cs2Type.INT)
        }
        repeat(effect.popStr) {
            require(strings.isNotEmpty()) { "string stack underflow" }
            strings.removeAt(strings.size - 1)
            live.removeLastOfType(Cs2Type.STRING)
        }
        repeat(effect.popLong) {
            longDepth--
            live.removeLastOfType(Cs2Type.LONG)
        }
        require(longDepth >= 0) { "long stack underflow" }

        repeat(effect.pushInt) {
            ints.add(pushedIntConstant)
            live.add(Cs2Type.INT)
        }
        repeat(effect.pushStr) {
            strings.add(pushedString)
            live.add(Cs2Type.STRING)
        }
        repeat(effect.pushLong) {
            longDepth++
            live.add(Cs2Type.LONG)
        }
    }

    private fun MutableList<Cs2Type>.removeLastOfType(type: Cs2Type) {
        val at = indexOfLast { it == type }
        if (at >= 0) removeAt(at)
    }

    fun clear() {
        ints.clear()
        strings.clear()
        longDepth = 0
        live.clear()
    }

    fun copy(): SymbolicStacks {
        val copy = SymbolicStacks()
        copy.ints.addAll(ints)
        copy.strings.addAll(strings)
        copy.longDepth = longDepth
        copy.live.addAll(live)
        return copy
    }

    fun sameDepthAs(other: SymbolicStacks): Boolean =
        intDepth == other.intDepth && stringDepth == other.stringDepth && longs == other.longs

    /**
     * Merges another state in at a control-flow join. Depths must already agree;
     * constants that differ become unknown.
     */
    fun mergeConstantsFrom(other: SymbolicStacks) {
        for (i in ints.indices) if (ints[i] != other.ints[i]) ints[i] = null
        for (i in strings.indices) if (strings[i] != other.strings[i]) strings[i] = null
    }

    override fun toString() = "int=$intDepth str=$stringDepth long=$longDepth"
}
