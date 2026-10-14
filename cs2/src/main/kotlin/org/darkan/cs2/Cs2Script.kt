package org.darkan.cs2

import org.darkan.cs2.ir.Cs2Type

/**
 * A single decoded CS2 instruction.
 *
 * Exactly one operand field is meaningful, chosen by [Cs2Op]:
 * `PUSH_CONSTANT_STRING` uses [strOperand], `PUSH_LONG_CONSTANT` uses
 * [longOperand], everything else uses [intOperand].
 */
data class Cs2Instruction(
    val op: Cs2Op,
    val intOperand: Int = 0,
    val strOperand: String? = null,
    val longOperand: Long = 0,
) {
    override fun toString(): String = when {
        strOperand != null -> "${op.name} \"${Cs2Strings.escape(strOperand)}\""
        op == Cs2Op.PUSH_LONG_CONSTANT -> "${op.name} $longOperand"
        else -> "${op.name} $intOperand"
    }
}

/** One `case key: -> jump` entry of a switch table. */
data class Cs2SwitchCase(val key: Int, val offset: Int)

/**
 * A decoded clientscript.
 *
 * Field order mirrors the on-disk header so the encoder can walk it directly.
 * [switchTables] preserves both table order and within-table case order, which
 * the client does not care about but byte-identical re-encoding does.
 */
class Cs2Script(
    /** Script name from the file header, or null when the file stores a bare 0 byte. */
    val name: String?,
    val instructions: List<Cs2Instruction>,
    val intLocalsCount: Int,
    val stringLocalsCount: Int,
    val longLocalsCount: Int,
    val intArgsCount: Int,
    val stringArgsCount: Int,
    val longArgsCount: Int,
    val switchTables: List<List<Cs2SwitchCase>>,
) {
    val size: Int get() = instructions.size

    operator fun get(index: Int): Cs2Instruction = instructions[index]

    /**
     * Number of values this script leaves on each stack for its caller.
     * Filled in by interprocedural analysis; null until then.
     */
    var returnSignature: ReturnSignature? = null

    /**
     * Types of the returned values in the order they were pushed. The grouped
     * counts in [returnSignature] cannot express that order, and callers have to
     * destructure the results in it.
     */
    var returnOrder: List<Cs2Type> = emptyList()

    /**
     * Types of this script's arguments in the order callers push them. Like
     * [returnOrder] this can interleave the stacks, and the parameter list has to
     * be written in it for a call to line up with the declaration.
     */
    var argumentOrder: List<Cs2Type> = emptyList()

    override fun toString(): String =
        "Cs2Script(name=$name, instructions=${instructions.size}, " +
            "args=$intArgsCount/$stringArgsCount/$longArgsCount, " +
            "locals=$intLocalsCount/$stringLocalsCount/$longLocalsCount, " +
            "switches=${switchTables.size})"
}

/** Values a script returns, by stack. */
data class ReturnSignature(val ints: Int, val strings: Int, val longs: Int) {
    val total: Int get() = ints + strings + longs

    companion object {
        val EMPTY = ReturnSignature(0, 0, 0)
    }
}
