package org.darkan.cs2.ir

import org.darkan.cs2.ArgType
import org.darkan.cs2.Cs2Op

/** The three value types CS2 tracks, one per operand stack. */
enum class Cs2Type { INT, STRING, LONG }

// ---------------------------------------------------------------- expressions

sealed interface Expr {
    val type: Cs2Type
}

data class IntConst(val value: Int) : Expr {
    override val type get() = Cs2Type.INT
}

data class StrConst(val value: String) : Expr {
    override val type get() = Cs2Type.STRING
}

data class LongConst(val value: Long) : Expr {
    override val type get() = Cs2Type.LONG
}

/**
 * A constant whose meaning is known: a component hash, a packed coordinate, an
 * item id and so on. It prints in that form and packs straight back to [value],
 * so nothing is lost.
 */
data class TypedConst(val value: Int, val kind: ArgType) : Expr {
    override val type get() = Cs2Type.INT
}

/** A script local. Args occupy the lowest slots of each type. */
data class LocalRef(val slot: Int, override val type: Cs2Type) : Expr

/** One of the five global int arrays. */
data class ArrayRef(val array: Int, val index: Expr) : Expr {
    override val type get() = Cs2Type.INT
}

/** Which variable space a [VarRef] addresses. */
enum class VarSpace { VARP, VARBIT, VARC, VARC_STRING, VARN, VARNBIT, CLAN, CLAN_SETTING }

data class VarRef(
    val space: VarSpace,
    val id: Int,
    override val type: Cs2Type,
    /** Set for the clan spaces, which have bit and long flavours. */
    val variant: String? = null,
) : Expr

/** A binary operator that reads better as an operator than as a call. */
data class Binary(val symbol: String, val left: Expr, val right: Expr, override val type: Cs2Type) : Expr

/** `JOIN_STRING`: template-literal concatenation. */
data class Join(val parts: List<Expr>) : Expr {
    override val type get() = Cs2Type.STRING
}

/**
 * A plain opcode call used as a value.
 *
 * [operand] is the instruction's own byte, which several opcodes use to pick
 * which active-component register they act on.
 */
data class OpCall(
    val op: Cs2Op,
    val args: List<Expr>,
    override val type: Cs2Type,
    val operand: Int = 0,
) : Expr

/** A call to another clientscript used as a value. */
data class ScriptCall(val scriptId: Int, val args: List<Expr>, override val type: Cs2Type) : Expr

/**
 * One result of a call that produces several values. The producing call is
 * emitted once as a [MultiAssign]; each result is then referenced by name.
 */
data class TempRef(val name: String, override val type: Cs2Type) : Expr

/**
 * A multi-value call spliced into an argument list: `f(a, ...g(), b)`.
 *
 * Every result of the call is pushed where the spread sits. This is the one
 * form that can say a call happened *between* two of another call's arguments,
 * which is exactly what the bytecode can do and a named temporary cannot.
 */
data class Spread(val call: Expr) : Expr {
    override val type get() = call.type
}

/**
 * A callback passed to a hook setter: the script to run, its bound arguments,
 * and the trigger list that decides when it fires.
 */
data class Callback(
    val scriptId: Int,
    /**
     * The compiler's type spec, one character per bound argument. The client
     * only distinguishes `s` and the long marker, but the other characters name
     * real types (`I` component, `o` obj, `d` ...), so they are kept verbatim.
     */
    val format: String,
    val args: List<Expr>,
    val triggers: List<Expr>,
) : Expr {
    val hasTriggerList: Boolean get() = format.endsWith("Y")

    override val type get() = Cs2Type.INT
}

// ----------------------------------------------------------------- statements

sealed interface Stmt

/** A call evaluated for its effect. */
data class ExprStmt(val call: Expr) : Stmt

/**
 * A store to a local. [declare] marks the store that also declares it, which
 * reads as `let int4: number = 1;` and compiles to the same push and store as
 * any other assignment - the declaration is only about where the name is
 * introduced, never about what runs.
 */
data class LocalAssign(val slot: Int, val type: Cs2Type, val value: Expr, val declare: Boolean) : Stmt

data class VarAssign(val target: VarRef, val value: Expr) : Stmt

data class ArrayAssign(val array: Int, val index: Expr, val value: Expr) : Stmt

data class ArrayDefine(val array: Int, val elementType: Int, val size: Expr) : Stmt

/**
 * `[a, b] = [x, y]` - both values are computed before either is stored, which
 * is how CS2 compiles assignments that read each other.
 */
data class ParallelAssign(
    /** Each target is a [LocalRef] or a [VarRef]. */
    val targets: List<Expr>,
    val values: List<Expr>,
) : Stmt

/** Binds every result of a multi-value call at once. */
data class MultiAssign(val names: List<TempRef>, val call: Expr) : Stmt

/**
 * `[a, b] = f()` - a multi-value call whose results go straight into locals or
 * variables, with no named temporaries in between.
 *
 * [targets] are in the order the call pushes its results, which is the order
 * they read in; the code generator stores them back to front because that is
 * the order the stacks pop in. [declare] marks the form that also declares its
 * targets, `let [a, b] = f()`.
 */
data class DestructureAssign(
    /** Each target is a [LocalRef] or a [VarRef]. */
    val targets: List<Expr>,
    val call: Expr,
    val declare: Boolean = false,
) : Stmt

data class Return(val values: List<Expr>) : Stmt

data class If(val condition: Condition, val then: List<Stmt>, val otherwise: List<Stmt>) : Stmt

/**
 * A loop. A null [condition] is `while (true)`: a loop whose test sits part way
 * through the body, which is where the compiler put it when the work before the
 * test has to run on every pass.
 */
data class While(val condition: Condition?, val body: List<Stmt>) : Stmt

data class SwitchCase(val keys: List<Int>, val body: List<Stmt>)

data class Switch(
    val subject: Expr,
    val cases: List<SwitchCase>,
    val default: List<Stmt>,
    /** Index of the switch table this came from, so re-compilation keeps its slot. */
    val table: Int,
    /** What the subject is, so the case keys read as the same thing. */
    val keyKind: ArgType = ArgType.INT,
) : Stmt

/** A goto the structurer could not fold into a loop or conditional. */
data class Goto(val label: String) : Stmt

data class Label(val name: String) : Stmt

data class Break(val label: String? = null) : Stmt

data class Continue(val label: String? = null) : Stmt

/**
 * A branch condition.
 *
 * CS2 has no boolean values: a comparison *is* a branch, and `&&` / `||` are
 * chains of them sharing a target. Keeping the chain structure is what lets the
 * compiler lay the branches back out the way it found them.
 */
sealed interface Condition

/**
 * A single comparison. [comparison] keeps the opcode that produced it so
 * re-compilation emits the same one back.
 */
data class Compare(
    val comparison: Cs2Op,
    val left: Expr,
    val right: Expr,
    /** True when the source branch jumped on the condition being false. */
    val negated: Boolean,
) : Condition {
    /** The TypeScript operator this reads as, taking [negated] into account. */
    val symbol: String get() = if (negated) invert(naturalSymbol) else naturalSymbol

    /** The operator the opcode itself means, ignoring [negated]. */
    val naturalSymbol: String
        get() {
            return when (comparison) {
                Cs2Op.BRANCH_EQUALS, Cs2Op.LONG_BRANCH_EQUALS -> "=="
                Cs2Op.BRANCH_NOT, Cs2Op.LONG_BRANCH_NOT -> "!="
                Cs2Op.BRANCH_LESS_THAN, Cs2Op.LONG_BRANCH_LESS_THAN -> "<"
                Cs2Op.BRANCH_GREATER_THAN, Cs2Op.LONG_BRANCH_GREATER_THAN -> ">"
                Cs2Op.BRANCH_LESS_THAN_OR_EQUALS, Cs2Op.LONG_BRANCH_LESS_THAN_OR_EQUALS -> "<="
                Cs2Op.BRANCH_GREATER_THAN_OR_EQUALS, Cs2Op.LONG_BRANCH_GREATER_THAN_OR_EQUALS -> ">="
                Cs2Op.BRANCH_IF_TRUE -> "=="
                Cs2Op.BRANCH_IF_FALSE -> "=="
                else -> error("Not a comparison: ${comparison.name}")
            }
        }

    private fun invert(symbol: String) = when (symbol) {
        "==" -> "!="
        "!=" -> "=="
        "<" -> ">="
        ">" -> "<="
        "<=" -> ">"
        ">=" -> "<"
        else -> error("Cannot invert $symbol")
    }
}

/** `a && b && ...` - every term branches past the body on failure. */
data class AndAll(val terms: List<Condition>) : Condition

/** `a || b || ...` - every term branches into the body on success. */
data class OrAny(val terms: List<Condition>) : Condition

/** Negates a condition, pushing the negation down to the comparisons. */
fun Condition.negate(): Condition = when (this) {
    is Compare -> copy(negated = !negated)
    is AndAll -> OrAny(terms.map { it.negate() })
    is OrAny -> AndAll(terms.map { it.negate() })
}

/** A whole decompiled script. */
class Cs2Function(
    val scriptId: Int,
    val name: String?,
    val intArgs: Int,
    val stringArgs: Int,
    val longArgs: Int,
    val intLocals: Int,
    val stringLocals: Int,
    val longLocals: Int,
    val body: List<Stmt>,
    val returns: List<Cs2Type>,
    /** Parameter types in the order callers push them, when observed. */
    val argumentOrder: List<Cs2Type> = emptyList(),
    /** What each int parameter holds, by slot, where the corpus agrees. */
    val intParamKinds: List<ArgType> = emptyList(),
    /** What each int local holds, by slot, where the function's own uses agree. */
    val intLocalKinds: Map<Int, ArgType> = emptyMap(),
    /** What each returned value is, by position in the tuple. */
    val returnKinds: List<ArgType> = emptyList(),
)
