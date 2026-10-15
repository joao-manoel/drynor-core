package org.drynor.cs2

/**
 * Control-flow graph over a script's instruction list.
 *
 * Jump operands are relative to the *next* instruction: the interpreter
 * increments its instruction pointer before executing, then adds the operand,
 * so an instruction at `index` with operand `d` continues at `index + d + 1`.
 */
class Cs2Cfg private constructor(
    val script: Cs2Script,
    val blocks: List<Block>,
    /** Block index that starts at a given instruction index. */
    private val blockAt: Map<Int, Int>,
) {
    /** A maximal run of instructions with a single entry and a single exit. */
    class Block(
        val id: Int,
        val start: Int,
        val end: Int,
        val successors: MutableList<Int> = ArrayList(2),
        val predecessors: MutableList<Int> = ArrayList(2),
    ) {
        /** Instruction indices in this block. */
        val indices: IntRange get() = start until end

        override fun toString() = "B$id[$start,$end) -> $successors"
    }

    fun blockStartingAt(index: Int): Block? = blockAt[index]?.let { blocks[it] }

    val entry: Block get() = blocks.first()

    companion object {

        private val CONDITIONAL_BRANCHES = setOf(
            Cs2Op.BRANCH_NOT, Cs2Op.BRANCH_EQUALS, Cs2Op.BRANCH_LESS_THAN, Cs2Op.BRANCH_GREATER_THAN,
            Cs2Op.BRANCH_LESS_THAN_OR_EQUALS, Cs2Op.BRANCH_GREATER_THAN_OR_EQUALS,
            Cs2Op.LONG_BRANCH_NOT, Cs2Op.LONG_BRANCH_EQUALS, Cs2Op.LONG_BRANCH_LESS_THAN,
            Cs2Op.LONG_BRANCH_GREATER_THAN, Cs2Op.LONG_BRANCH_LESS_THAN_OR_EQUALS,
            Cs2Op.LONG_BRANCH_GREATER_THAN_OR_EQUALS, Cs2Op.BRANCH_IF_TRUE, Cs2Op.BRANCH_IF_FALSE,
        )

        private val LONG_BRANCHES = setOf(
            Cs2Op.LONG_BRANCH_NOT, Cs2Op.LONG_BRANCH_EQUALS, Cs2Op.LONG_BRANCH_LESS_THAN,
            Cs2Op.LONG_BRANCH_GREATER_THAN, Cs2Op.LONG_BRANCH_LESS_THAN_OR_EQUALS,
            Cs2Op.LONG_BRANCH_GREATER_THAN_OR_EQUALS,
        )

        fun isConditionalBranch(op: Cs2Op) = op in CONDITIONAL_BRANCHES

        fun isLongBranch(op: Cs2Op) = op in LONG_BRANCHES

        fun isUnconditionalBranch(op: Cs2Op) = op == Cs2Op.BRANCH

        fun isSwitch(op: Cs2Op) = op == Cs2Op.SWITCH

        fun isReturn(op: Cs2Op) = op == Cs2Op.RETURN

        /** Where control goes after `index`, ignoring fallthrough. */
        fun jumpTargets(script: Cs2Script, index: Int): List<Int> {
            val instruction = script[index]
            val op = instruction.op
            return when {
                isUnconditionalBranch(op) || isConditionalBranch(op) ->
                    listOf(index + instruction.intOperand + 1)
                isSwitch(op) -> {
                    val table = script.switchTables.getOrNull(instruction.intOperand) ?: emptyList()
                    table.map { index + it.offset + 1 }
                }
                else -> emptyList()
            }
        }

        /** True when control can continue at `index + 1`. */
        fun fallsThrough(op: Cs2Op) = !isUnconditionalBranch(op) && !isReturn(op)

        fun build(script: Cs2Script): Cs2Cfg {
            val leaders = sortedSetOf(0)
            for (index in script.instructions.indices) {
                val op = script[index].op
                for (target in jumpTargets(script, index)) {
                    require(target in 0..script.size) {
                        "Jump out of range: instruction $index targets $target"
                    }
                    leaders.add(target)
                }
                // A switch ends a block even when its table is empty, otherwise
                // the instructions after it would be folded into the same block
                // and lost when the block is lifted.
                if (!fallsThrough(op) || isSwitch(op) || jumpTargets(script, index).isNotEmpty()) {
                    if (index + 1 <= script.size) leaders.add(index + 1)
                }
            }
            leaders.removeIf { it >= script.size }

            val starts = leaders.toList()
            val blocks = starts.mapIndexed { id, start ->
                val end = starts.getOrNull(id + 1) ?: script.size
                Block(id, start, end)
            }
            val blockAt = starts.withIndex().associate { (id, start) -> start to id }

            for (block in blocks) {
                val last = block.end - 1
                val op = script[last].op
                for (target in jumpTargets(script, last)) {
                    blockAt[target]?.let { block.successors.add(it) }
                }
                if (fallsThrough(op) && block.end < script.size) {
                    blockAt[block.end]?.let { block.successors.add(it) }
                }
            }
            for (block in blocks) {
                for (successor in block.successors) blocks[successor].predecessors.add(block.id)
            }

            return Cs2Cfg(script, blocks, blockAt)
        }
    }
}
