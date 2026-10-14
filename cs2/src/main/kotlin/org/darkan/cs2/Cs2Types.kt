package org.darkan.cs2

import org.darkan.cs2.ir.AndAll
import org.darkan.cs2.ir.ArrayAssign
import org.darkan.cs2.ir.ArrayDefine
import org.darkan.cs2.ir.ArrayRef
import org.darkan.cs2.ir.Binary
import org.darkan.cs2.ir.Callback
import org.darkan.cs2.ir.Compare
import org.darkan.cs2.ir.Condition
import org.darkan.cs2.ir.Cs2Function
import org.darkan.cs2.ir.Cs2Type
import org.darkan.cs2.ir.DestructureAssign
import org.darkan.cs2.ir.Expr
import org.darkan.cs2.ir.ExprStmt
import org.darkan.cs2.ir.If
import org.darkan.cs2.ir.IntConst
import org.darkan.cs2.ir.Join
import org.darkan.cs2.ir.LocalAssign
import org.darkan.cs2.ir.LocalRef
import org.darkan.cs2.ir.MultiAssign
import org.darkan.cs2.ir.OpCall
import org.darkan.cs2.ir.OrAny
import org.darkan.cs2.ir.ParallelAssign
import org.darkan.cs2.ir.Return
import org.darkan.cs2.ir.ScriptCall
import org.darkan.cs2.ir.Spread
import org.darkan.cs2.ir.Stmt
import org.darkan.cs2.ir.Switch
import org.darkan.cs2.ir.SwitchCase
import org.darkan.cs2.ir.TempRef
import org.darkan.cs2.ir.TypedConst
import org.darkan.cs2.ir.VarAssign
import org.darkan.cs2.ir.VarRef
import org.darkan.cs2.ir.VarSpace
import org.darkan.cs2.ir.While

/**
 * The type character a compiler wrote for each kind of value.
 *
 * These are the characters hook formats, `ENUM`'s two type arguments and the
 * cache's param definitions are written in. The table is Jagex's; which entries
 * of it are actually true of this revision is checked against the corpus rather
 * than assumed - see [Cs2Types.charEvidence].
 */
object Cs2TypeChars {

    /** What each character claims a value is. */
    val KINDS: Map<Char, ArgType> = linkedMapOf(
        'i' to ArgType.INT,
        '1' to ArgType.BOOLEAN,
        'o' to ArgType.OBJ,
        'n' to ArgType.NPC,
        'l' to ArgType.LOC,
        'I' to ArgType.COMPONENT,
        'c' to ArgType.COORD,
        'd' to ArgType.GRAPHIC,
        'g' to ArgType.ENUM,
        'A' to ArgType.SEQ,
        't' to ArgType.SPOTANIM,
        'S' to ArgType.STAT,
        'C' to ArgType.COLOUR,
        'J' to ArgType.STRUCT,
        'M' to ArgType.MIDI,
        'P' to ArgType.SOUND,
        'v' to ArgType.INV,
        'm' to ArgType.MODEL,
        'a' to ArgType.INTERFACE,
        'µ' to ArgType.MAPELEMENT,
    )

    /** The name each character reads as where one is written out as a constant. */
    val NAMES: Map<Char, String> = linkedMapOf(
        'i' to "type_int",
        '1' to "type_boolean",
        's' to "type_string",
        'o' to "type_obj",
        'n' to "type_npc",
        'l' to "type_loc",
        'I' to "type_component",
        'c' to "type_coord",
        'd' to "type_graphic",
        'g' to "type_enum",
        'A' to "type_seq",
        't' to "type_spotanim",
        'S' to "type_stat",
        'C' to "type_colour",
        'J' to "type_struct",
        'M' to "type_midi",
        'P' to "type_sound",
        'v' to "type_inv",
        'm' to "type_model",
        'a' to "type_interface",
        'µ' to "type_mapelement",
    )

    private val byName: Map<String, Char> = NAMES.entries.associate { (char, name) -> name to char }

    /** The character a `type_*` constant stands for, or null when it is not one. */
    fun charNamed(name: String): Char? = byName[name]

    /** The name a type code reads as, or null when nothing is known about it. */
    fun nameOf(code: Int): String? = NAMES[code.toChar()]
}

/**
 * The opcodes that are told what type they are working with.
 *
 * An enum lookup takes the key and value type of the enum it reads, and checks
 * them against the definition, so the characters say exactly what the key
 * argument is and what comes back. Read off the client's own handlers.
 */
private class TypeCodeRule(
    /** Int argument index -> the argument holding the character describing it. */
    val typedArgs: Map<Int, Int>,
    /** The argument holding the character describing the result, if any. */
    val resultChar: Int? = null,
)

private val TYPE_CODE_RULES: Map<Cs2Op, TypeCodeRule> = mapOf(
    // enumOp(keyType, valueType, enum, key) -> value
    Cs2Op.ENUM to TypeCodeRule(mapOf(3 to 0), resultChar = 1),
    // enumGetreversecount(valueType, enum, value) -> count
    Cs2Op.ENUM_GETREVERSECOUNT to TypeCodeRule(mapOf(2 to 0)),
    // enumGetreverseindex(valueType, keyType, enum, value, index) -> key
    Cs2Op.ENUM_GETREVERSEINDEX to TypeCodeRule(mapOf(3 to 0), resultChar = 1),
    // enumGetreverseindexString(keyType, enum, index, text) -> key
    Cs2Op.ENUM_GETREVERSEINDEX_STRING to TypeCodeRule(emptyMap(), resultChar = 0),
    // enumHasoutput(valueType, enum, key) -> flag; the key's own type is the
    // enum's key type, which the call does not say.
    Cs2Op.ENUM_HASOUTPUT to TypeCodeRule(emptyMap()),
)

/**
 * What every constant in the corpus means, worked out from how it is used.
 *
 * CS2 keeps no types: an item id, a colour and a component hash are all plain
 * integers by the time they reach the bytecode. The opcode table does say what
 * some arguments are, though, and everything else follows from that by the
 * values being passed around: a local that is handed to `ocName` is an obj, a
 * script parameter that a caller fills with one is an obj too, and so on.
 *
 * A symbol takes a kind only when everything that flows through it agrees. Two
 * different kinds mean the symbol is being used for two things - which happens -
 * and it drops back to a plain number rather than guessing at the more popular
 * one.
 */
class Cs2Types private constructor(
    private val context: Cs2Context,
    private val functions: List<Cs2Function>,
) {

    /** Kinds that hold across the corpus: parameters, returns, variables, opcodes. */
    private val global = Store()

    /** Characters the corpus agrees with, which is what the char rules are read from. */
    private var trustedChars: Map<Char, ArgType> = emptyMap()

    /** Result kinds learned from where an opcode's results end up. */
    private var learnedResults: Map<String, ArgType> = emptyMap()

    /** Argument kinds learned from what an opcode's untyped slots are given. */
    private var learnedArguments: Map<String, ArgType> = emptyMap()

    /** Set while learning: what flowed through each opcode slot. */
    private val resultEvidence = HashMap<String, MutableMap<ArgType, Votes>>()
    private val argumentEvidence = HashMap<String, MutableMap<ArgType, Votes>>()

    /** How often a kind was seen at a slot, and in how many different scripts. */
    private class Votes {
        var uses = 0
        val scripts = HashSet<Int>()

        /** A few of the values that voted, and where their kind came from. */
        val examples = ArrayList<String>()

        /** How many voters were typed by direct authority in their own script. */
        var direct = 0

        /** The distinct places the voters' kinds were decided. */
        val origins = HashSet<String>()
    }

    /** A symbol as a person would read it. */
    private fun describe(symbol: String): String {
        val parts = symbol.split(":")
        return when (parts[0]) {
            "p" -> "script ${parts[1]} parameter ${parts[2]}"
            "l" -> "script ${parts[1]} local ${parts[2]}"
            "r" -> "script ${parts[1]} return ${parts[2]}"
            "v" -> "${parts[1]} ${parts[2]}"
            "o" -> "${parts[1]} result"
            "t" -> "script ${parts[1]} ${parts[2]}"
            else -> symbol
        }
    }

    /** Set while validating the type characters: what the corpus said instead. */
    private val charEvidence = HashMap<Char, MutableMap<ArgType, Votes>>()

    /**
     * How a learned argument slot was decided: the kinds that were voted for,
     * how many scripts voted, and a few of the values that did.
     */
    fun evidenceFor(slot: String): List<String> {
        val votes = argumentEvidence["a:$slot"] ?: resultEvidence["o:$slot"] ?: return emptyList()
        val out = ArrayList<String>()
        for ((kind, seen) in votes.entries.sortedByDescending { it.value.uses }) {
            out.add(
                "$kind: ${seen.uses} uses in ${seen.scripts.size} scripts, " +
                    "${seen.direct} typed on the spot, ${seen.origins.size} distinct origins",
            )
            seen.examples.forEach { out.add("    $it") }
        }
        return out
    }

    /** What a variable holds, where every use of it agrees. */
    fun varKind(space: VarSpace, id: Int): ArgType? =
        if (tracked(space)) global.kindOf(varSymbol(space, id)) else null

    /** What the corpus learned about each opcode's untyped argument slots. */
    val argumentKinds: Map<String, ArgType>
        get() = learnedArguments.mapKeys { it.key.removePrefix("a:") }

    /** What the corpus learned each opcode's results are. */
    val resultKinds: Map<String, ArgType>
        get() = learnedResults.mapKeys { it.key.removePrefix("o:") }

    /** Counts for the report, filled in by [describe]. */
    private val bySource = LinkedHashMap<String, Int>()
    private val byKind = LinkedHashMap<ArgType, Int>()

    // ------------------------------------------------------------------ store

    /** A symbol's kind, or nothing once two kinds have been seen for it. */
    private class Store {
        val kinds = HashMap<String, ArgType>()
        val conflicts = HashSet<String>()

        /** What first gave a symbol its kind, for explaining an answer. */
        val origins = HashMap<String, String>()

        /** Symbols something counts or compares by size, which no id is. */
        val quantities = HashSet<String>()
        var changed = false

        /**
         * Marks a symbol as a quantity. Ids are never ordered - nothing asks
         * whether one obj is less than another - so a value compared for order
         * against a real number is a count, a level, a timer or a threshold, and
         * cannot be an id however it got here.
         */
        fun quantity(symbol: String): Boolean {
            if (!quantities.add(symbol)) return false
            val had = kinds.remove(symbol) != null
            origins.remove(symbol)
            conflicts.add(symbol)
            changed = true
            return had
        }

        fun assign(symbol: String, kind: ArgType, why: String = "") {
            if (kind == ArgType.INT || symbol in conflicts) return
            val existing = kinds[symbol]
            when {
                existing == null -> {
                    kinds[symbol] = kind
                    if (why.isNotEmpty()) origins[symbol] = why
                    changed = true
                }
                existing != kind -> {
                    kinds.remove(symbol)
                    origins.remove(symbol)
                    conflicts.add(symbol)
                    changed = true
                }
            }
        }

        fun kindOf(symbol: String): ArgType? = kinds[symbol]
    }

    // -------------------------------------------------------------- analysis

    private fun analyse(log: (String) -> Unit) {
        // The characters are a claim about this revision, so they are held back
        // until everything else has settled and then checked against it.
        settle(log, "without type characters")
        validateChars()
        global.kinds.clear()
        global.conflicts.clear()
        trustedChars = Cs2TypeChars.KINDS.filterKeys { char ->
            Cs2TypeChars.KINDS[char] != ArgType.INT &&
                charEvidence[char].orEmpty().none { (kind, _) -> kind != Cs2TypeChars.KINDS[char] }
        }
        settle(log, "with ${trustedChars.size} of ${Cs2TypeChars.KINDS.size} type characters")

        learn()
        log(
            "learned ${learnedResults.size} opcode result kinds and " +
                "${learnedArguments.size} opcode argument slots",
        )
        settle(log, "with the learned opcode kinds")
    }

    /** Runs the walk over every function until no symbol changes any more. */
    private fun settle(log: (String) -> Unit, what: String) {
        var round = 0
        while (round < ROUNDS) {
            round++
            global.changed = false
            for (function in functions) Walker(function, null, rewrite = false).run()
            if (!global.changed) break
        }
        log("$what: settled in $round rounds, ${global.kinds.size} symbols typed")
    }

    /**
     * Compares each character's claim against the kind the corpus gives the
     * values that character describes. A character that is ever contradicted is
     * dropped rather than argued with.
     */
    private fun validateChars() {
        charEvidence.clear()
        for (function in functions) Walker(function, null, rewrite = false, chars = true).run()
    }

    /**
     * Learns what an opcode's untyped argument slots and results are, from the
     * kinds that reach them. This runs once the carriers have settled and never
     * feeds itself: a learned kind is evidence for the symbols around it, never
     * for another learned kind.
     */
    private fun learn() {
        resultEvidence.clear()
        argumentEvidence.clear()
        for (function in functions) Walker(function, null, rewrite = false, learning = true).run()
        // A result is only voted for from positions that know what they hold on
        // their own, so it needs less corroboration than an argument slot, whose
        // votes come from values that were themselves worked out.
        learnedResults = resultEvidence
            .mapNotNull { (slot, votes) -> agreed(votes, direct = false)?.let { slot to it } }.toMap()
        learnedArguments = argumentEvidence
            .mapNotNull { (slot, votes) -> agreed(votes, direct = true)?.let { slot to it } }.toMap()
    }

    /**
     * The one kind a slot was given, when enough uses agree and none disagree.
     *
     * The uses have to come from more than one script - a script that reuses a
     * local for two things on its own says the same wrong thing several times -
     * and, for an argument slot, at least one of them has to be a value that
     * something said what it was on the spot. Without that last rule a kind can
     * travel a long way through parameters and struct params before it lands in
     * a slot that has nothing to do with it: `basecolour`'s second argument is
     * the colour index being set, and every value reaching it was typed a graphic
     * somewhere else entirely.
     */
    private fun agreed(votes: Map<ArgType, Votes>, direct: Boolean): ArgType? {
        if (votes.size != 1) return null
        val (kind, seen) = votes.entries.first()
        if (kind == ArgType.INT) return null
        if (seen.uses < LEARNING_USES || seen.scripts.size < LEARNING_SCRIPTS) return null
        if (!direct) return kind
        // At least one of the values has to have been typed where it stands, by
        // something that says what it is on its own authority. Without that a
        // kind can travel a long way - through parameters, variables and struct
        // params - and land in a slot that has nothing to do with it: every
        // value reaching `basecolour`'s second argument, the colour index being
        // set, was typed a graphic id somewhere else entirely.
        return if (seen.direct > 0) kind else null
    }

    // ---------------------------------------------------------------- symbols

    private fun paramSymbol(script: Int, slot: Int) = "p:$script:$slot"

    private fun localSymbol(script: Int, slot: Int) = "l:$script:$slot"

    private fun returnSymbol(script: Int, index: Int) = "r:$script:$index"

    private fun varSymbol(space: VarSpace, id: Int) = "v:$space:$id"

    private fun resultSymbol(op: String, index: Int) = "o:$op:$index"

    private fun argumentSlot(op: String, index: Int) = "a:$op:$index"

    private fun tempSymbol(script: Int, name: String) = "t:$script:$name"

    /** Variable spaces whose ids mean the same thing everywhere. */
    private fun tracked(space: VarSpace) =
        space == VarSpace.VARP || space == VarSpace.VARBIT || space == VarSpace.VARC

    // ------------------------------------------------------------- rendering

    /**
     * The same function with every constant in a typed position written as what
     * it means, and with the kinds its signature and declarations read as.
     *
     * Local slots are worked out here rather than looked up, because a local's
     * slot is a property of the file it was written into, not of the script: the
     * same script re-compiled from its own source can hand its locals different
     * slots. Everything a caller can see - parameters, returns, variables - is
     * keyed on what does not move.
     */
    fun apply(function: Cs2Function): Cs2Function {
        val locals = Store()
        var round = 0
        while (round < LOCAL_ROUNDS) {
            round++
            locals.changed = false
            Walker(function, locals, rewrite = false).run()
            if (!locals.changed) break
        }
        val walker = Walker(function, locals, rewrite = true)
        val body = walker.run()
        val paramKinds = (0 until function.intArgs).map {
            global.kindOf(paramSymbol(function.scriptId, it)) ?: ArgType.INT
        }
        val localKinds = HashMap<Int, ArgType>()
        for (slot in function.intArgs until function.intLocals) {
            locals.kindOf(localSymbol(function.scriptId, slot))?.let { localKinds[slot] = it }
        }
        val returnKinds = function.returns.mapIndexed { index, type ->
            if (type != Cs2Type.INT) ArgType.INT
            else global.kindOf(returnSymbol(function.scriptId, index)) ?: ArgType.INT
        }
        return Cs2Function(
            scriptId = function.scriptId,
            name = function.name,
            intArgs = function.intArgs,
            stringArgs = function.stringArgs,
            longArgs = function.longArgs,
            intLocals = function.intLocals,
            stringLocals = function.stringLocals,
            longLocals = function.longLocals,
            body = body,
            returns = function.returns,
            argumentOrder = function.argumentOrder,
            intParamKinds = paramKinds,
            intLocalKinds = localKinds,
            returnKinds = returnKinds,
        )
    }

    // ----------------------------------------------------------------- report

    class Report(
        val symbols: Map<String, Int>,
        val constantsByKind: Map<ArgType, Int>,
        val constantsBySource: Map<String, Int>,
        val chars: List<CharCheck>,
        val learnedResults: Map<String, ArgType>,
        val learnedArguments: Map<String, ArgType>,
    )

    class CharCheck(
        val char: Char,
        val kind: ArgType,
        val agreed: Int,
        val conflicts: Map<ArgType, Int>,
        val kept: Boolean,
    )

    /** Applies the analysis to every function once, counting what it wrote. */
    fun describe(): Report {
        bySource.clear()
        byKind.clear()
        val locals = functions.sumOf { apply(it).intLocalKinds.size }
        val symbols = linkedMapOf(
            "parameters" to global.kinds.keys.count { it.startsWith("p:") },
            "return slots" to global.kinds.keys.count { it.startsWith("r:") },
            "variables" to global.kinds.keys.count { it.startsWith("v:") },
            "opcode results" to global.kinds.keys.count { it.startsWith("o:") },
            "locals" to locals,
            "ordered, so not ids" to global.quantities.size,
        )
        val chars = Cs2TypeChars.KINDS.map { (char, kind) ->
            val votes = charEvidence[char].orEmpty()
            CharCheck(
                char = char,
                kind = kind,
                agreed = votes[kind]?.uses ?: 0,
                conflicts = votes.filterKeys { it != kind }.mapValues { it.value.uses },
                kept = trustedChars.containsKey(char),
            )
        }
        return Report(symbols, byKind.toMap(), bySource.toMap(), chars, learnedResults, learnedArguments)
    }

    private fun counted(kind: ArgType, source: String) {
        byKind[kind] = (byKind[kind] ?: 0) + 1
        bySource[source] = (bySource[source] ?: 0) + 1
    }

    // ----------------------------------------------------------------- walker

    /**
     * One pass over a function: it applies every rule to every position, and
     * either records what it learns or writes the constants out, depending on
     * what it was asked for.
     */
    private inner class Walker(
        private val fn: Cs2Function,
        /** Where this function's own local kinds live, when they are not global. */
        private val locals: Store?,
        private val rewrite: Boolean,
        private val chars: Boolean = false,
        private val learning: Boolean = false,
    ) {
        fun run(): List<Stmt> = fn.body.map { statement(it) }

        // -------------------------------------------------------- statements

        private fun statement(statement: Stmt): Stmt = when (statement) {
            is LocalAssign -> {
                val symbol = localOf(statement.slot, statement.type)
                unify(symbol, symbolOf(statement.value))
                statement.copy(value = expr(statement.value, kindOf(symbol), "local"))
            }

            is VarAssign -> {
                val symbol = symbolOf(statement.target)
                unify(symbol, symbolOf(statement.value))
                statement.copy(value = expr(statement.value, kindOf(symbol), "var"))
            }

            is ParallelAssign -> {
                val values = statement.values.mapIndexed { index, value ->
                    val symbol = symbolOf(statement.targets.getOrNull(index) ?: value)
                    unify(symbol, symbolOf(value))
                    expr(value, kindOf(symbol), "assignment")
                }
                statement.copy(values = values, targets = statement.targets.map { expr(it, null, "") })
            }

            is DestructureAssign -> {
                results(statement.call, statement.targets)
                statement.copy(call = expr(statement.call, null, ""))
            }

            is MultiAssign -> {
                results(statement.call, statement.names)
                statement.copy(call = expr(statement.call, null, ""))
            }

            is Return -> Return(
                statement.values.mapIndexed { index, value ->
                    if (value.type != Cs2Type.INT) value else {
                        val symbol = returnSymbol(fn.scriptId, index)
                        unify(symbol, symbolOf(value))
                        expr(value, kindOf(symbol), "return")
                    }
                },
            )

            is ExprStmt -> ExprStmt(expr(statement.call, null, ""))
            is ArrayAssign -> statement.copy(
                index = expr(statement.index, null, ""),
                value = expr(statement.value, null, ""),
            )
            is ArrayDefine -> statement.copy(size = expr(statement.size, null, ""))
            is If -> If(condition(statement.condition), statement.then.map { statement(it) }, statement.otherwise.map { statement(it) })
            is While -> While(statement.condition?.let { condition(it) }, statement.body.map { statement(it) })
            is Switch -> {
                val subject = expr(statement.subject, null, "")
                val kind = kindOf(symbolOf(statement.subject)) ?: ArgType.INT
                if (rewrite && kind != ArgType.INT) {
                    statement.cases.forEach { case -> repeat(case.keys.size) { counted(kind, "switch subject") } }
                }
                Switch(
                    subject,
                    statement.cases.map { SwitchCase(it.keys, it.body.map { inner -> statement(inner) }) },
                    statement.default.map { statement(it) },
                    statement.table,
                    keyKind = kind,
                )
            }
            else -> statement
        }

        /** Ties a multi-value call's results to where they were put. */
        private fun results(call: Expr, targets: List<Expr>) {
            targets.forEachIndexed { index, target ->
                if (target.type != Cs2Type.INT) return@forEachIndexed
                val produced = when (call) {
                    is ScriptCall -> returnSymbol(call.scriptId, index)
                    is OpCall -> resultSymbol(call.op.name, index)
                    else -> null
                }
                unify(symbolOf(target), produced)
            }
        }

        private fun condition(condition: Condition): Condition = when (condition) {
            is Compare -> {
                val left = symbolOf(condition.left)
                val right = symbolOf(condition.right)
                if (condition.symbol in ORDERINGS) {
                    ordered(left, condition.right)
                    ordered(right, condition.left)
                }
                unify(left, right)
                val kind = kindOf(left) ?: kindOf(right)
                Compare(
                    condition.comparison,
                    expr(condition.left, kind, "comparison"),
                    expr(condition.right, kind, "comparison"),
                    condition.negated,
                )
            }
            is AndAll -> AndAll(condition.terms.map { condition(it) })
            is OrAny -> OrAny(condition.terms.map { condition(it) })
        }

        // ------------------------------------------------------- expressions

        /**
         * Visits an expression, and writes it out as [expected] when it is a
         * bare constant sitting in a position of that kind.
         */
        private fun expr(value: Expr, expected: ArgType?, source: String): Expr {
            if (expected != null && expected != ArgType.INT) {
                assign(symbolOf(value), expected, why(source))
                if (learning) noteResult(value, expected, source)
            }
            val visited = when (value) {
                is OpCall -> opCall(value)
                is ScriptCall -> scriptCall(value)
                is Callback -> callback(value)
                is Binary -> value.copy(left = expr(value.left, null, ""), right = expr(value.right, null, ""))
                is Join -> value.copy(parts = value.parts.map { expr(it, null, "") })
                is ArrayRef -> value.copy(index = expr(value.index, null, ""))
                is Spread -> Spread(expr(value.call, null, ""))
                else -> value
            }
            // A constant the opcode table already spelled out arrives typed; it
            // is counted where it stands so the tally is of everything, not only
            // of what this pass added.
            if (rewrite && visited is TypedConst) counted(visited.kind, "opcode table")
            if (!rewrite || expected == null || expected == ArgType.INT) return visited
            if (visited !is IntConst) return visited
            counted(expected, source)
            return TypedConst(visited.value, expected)
        }

        private fun opCall(value: OpCall): OpCall {
            val op = value.op
            val rule = TYPE_CODE_RULES[op]
            val ints = value.args.filter { it.type == Cs2Type.INT }
            var intIndex = 0
            val args = value.args.map { argument ->
                if (argument.type != Cs2Type.INT) return@map expr(argument, null, "")
                val index = intIndex++
                val declared = op.argTypes.getOrNull(index) ?: ArgType.INT
                val described = rule?.typedArgs?.get(index)?.let { typeCharAt(ints, it) }
                val kind = when {
                    declared != ArgType.INT -> declared
                    // An enum lookup says what its key is in one of its own
                    // arguments, which is better than anything guessed.
                    described != null -> described
                    else -> learnedArguments[argumentSlot(op.name, index)] ?: ArgType.INT
                }
                // A slot a type code already describes is not one to guess at.
                val describable = rule?.typedArgs?.containsKey(index) == true
                if (learning && declared == ArgType.INT && kind == ArgType.INT && !describable) {
                    noteArgument(op.name, index, argument)
                }
                val source = when {
                    declared != ArgType.INT -> "opcode argument"
                    described != null -> "enum type code"
                    else -> "learned argument slot"
                }
                expr(argument, kind, source)
            }
            // What the call leaves behind: a param's own type, the type code an
            // enum lookup was given, or whatever the corpus learned.
            val result = resultSymbol(op.name, 0)
            val described = rule?.resultChar?.let { typeCharAt(ints, it) }
            when {
                described != null -> declare(result, described)
                op.kind == OpKind.PARAM -> paramKind(value)?.let { declare(result, it) }
                rule == null -> learnedResults[result]?.let { declare(result, it) }
            }
            return value.copy(args = args)
        }

        /** The kind the type character in one of a call's arguments names. */
        private fun typeCharAt(ints: List<Expr>, index: Int): ArgType? {
            val argument = ints.getOrNull(index) ?: return null
            val code = (argument as? IntConst)?.value ?: (argument as? TypedConst)?.value ?: return null
            return trustedChars[code.toChar()]
        }

        /** The kind a `*_PARAM` opcode's result takes, from the cache's own type. */
        private fun paramKind(value: OpCall): ArgType? {
            val id = value.args.lastOrNull { it.type == Cs2Type.INT }
            val paramId = (id as? IntConst)?.value ?: (id as? TypedConst)?.value ?: return null
            return trustedChars[context.paramType(paramId)]
        }

        private fun scriptCall(value: ScriptCall): ScriptCall {
            var intIndex = 0
            val args = value.args.map { argument ->
                if (argument.type != Cs2Type.INT) return@map expr(argument, null, "")
                val symbol = paramSymbol(value.scriptId, intIndex++)
                unify(symbol, symbolOf(argument))
                expr(argument, global.kindOf(symbol), "script parameter")
            }
            return value.copy(args = args)
        }

        /**
         * A hook's format string names the type of every bound argument, and
         * those arguments become the callee's parameters in the same order.
         */
        private fun callback(value: Callback): Callback {
            var intIndex = 0
            val args = value.args.mapIndexed { index, argument ->
                if (argument.type != Cs2Type.INT) return@mapIndexed expr(argument, null, "")
                val char = value.format.getOrNull(index)
                val slot = intIndex++
                val callee = if (value.scriptId >= 0) paramSymbol(value.scriptId, slot) else null
                unify(callee, symbolOf(argument))
                val claimed = char?.let { Cs2TypeChars.KINDS[it] }
                // `i` claims a plain number, which says nothing to check.
                if (chars && char != null && claimed != null && claimed != ArgType.INT) {
                    noteChar(char, argument, callee)
                }
                val kind = char?.let { trustedChars[it] } ?: ArgType.INT
                if (kind != ArgType.INT) assign(callee, kind, "hook format '$char'")
                val known = if (kind != ArgType.INT) kind else callee?.let { global.kindOf(it) }
                expr(argument, known, if (kind != ArgType.INT) "hook format" else "hook parameter")
            }
            return value.copy(args = args, triggers = value.triggers.map { expr(it, null, "") })
        }

        /**
         * Records that [symbol] was compared for order against [against]. Only a
         * real threshold counts: 0 and -1 are how everything is tested for being
         * set at all, ids included.
         */
        private fun ordered(symbol: String?, against: Expr) {
            if (symbol == null || chars) return
            val value = (against as? IntConst)?.value ?: (against as? TypedConst)?.value ?: return
            if (value == 0 || value == -1) return
            store(symbol).quantity(symbol)
        }

        /** How a position that has just typed something would explain itself. */
        private fun why(source: String) = if (source.isEmpty()) "" else "$source in ${fn.scriptId}"

        // ------------------------------------------------------------ symbols

        private fun localOf(slot: Int, type: Cs2Type): String? {
            if (type != Cs2Type.INT) return null
            return if (slot < fn.intArgs) paramSymbol(fn.scriptId, slot) else localSymbol(fn.scriptId, slot)
        }

        private fun symbolOf(value: Expr): String? {
            if (value.type != Cs2Type.INT) return null
            return when (value) {
                is LocalRef -> localOf(value.slot, value.type)
                is VarRef -> if (tracked(value.space)) varSymbol(value.space, value.id) else null
                is TempRef -> tempSymbol(fn.scriptId, value.name)
                is OpCall -> resultSymbol(value.op.name, 0)
                is ScriptCall -> returnSymbol(value.scriptId, 0)
                else -> null
            }
        }

        /** Which store holds a symbol: a local's is the function's own. */
        private fun store(symbol: String): Store =
            if (locals != null && symbol.startsWith("l:")) locals else global

        private fun kindOf(symbol: String?): ArgType? = symbol?.let { store(it).kindOf(it) }

        private fun assign(symbol: String?, kind: ArgType, why: String = "") {
            if (symbol == null || chars) return
            // An opcode's results mean the same thing in every script, so one
            // comparison against an item is not enough to say the opcode returns
            // items. Those are learned from many uses that agree, further down.
            if (symbol.startsWith("o:")) return
            store(symbol).assign(symbol, kind, why)
        }

        /** States what an opcode leaves behind, where the opcode itself says so. */
        private fun declare(symbol: String, kind: ArgType) {
            if (!chars) global.assign(symbol, kind, "the opcode says so")
        }

        private fun unify(left: String?, right: String?) {
            if (left == null || right == null || chars) return
            kindOf(left)?.let { assign(right, it, "same value as ${describe(left)}") }
            kindOf(right)?.let { assign(left, it, "same value as ${describe(right)}") }
        }

        // ----------------------------------------------------------- learning

        /**
         * Records where an opcode's result ended up, but only where the position
         * says what it is on its own authority - the opcode table, a hook
         * format, a param definition. A position that only knows what it holds
         * because something else was inferred would let one guess make another.
         */
        private fun noteResult(value: Expr, kind: ArgType, source: String) {
            val call = value as? OpCall ?: return
            if (call.op.kind == OpKind.PARAM || source !in AUTHORITATIVE) return
            val slot = resultSymbol(call.op.name, 0)
            if (global.kindOf(slot) != null || slot in global.conflicts) return
            vote(resultEvidence, slot, kind)
        }

        private fun noteArgument(op: String, index: Int, argument: Expr) {
            val symbol = symbolOf(argument) ?: return
            val kind = kindOf(symbol) ?: return
            val votes = vote(argumentEvidence, argumentSlot(op, index), kind)
            val origin = store(symbol).origins[symbol] ?: "unrecorded"
            votes.origins.add(origin)
            if (votes.examples.size < EXAMPLES) {
                votes.examples.add("script ${fn.scriptId} ${describe(symbol)} <- $origin")
            }
            if (directlyTyped(symbol)) votes.direct++
        }

        /**
         * True when a value's kind was settled by something that says what it is
         * on its own authority - an opcode's own argument, a hook format - inside
         * this same script, rather than reaching it through a parameter.
         */
        private fun directlyTyped(symbol: String): Boolean {
            val origin = store(symbol).origins[symbol] ?: return false
            return AUTHORITATIVE.any { origin.startsWith(it) } && origin.endsWith(" ${fn.scriptId}")
        }

        private fun vote(
            into: MutableMap<String, MutableMap<ArgType, Votes>>,
            slot: String,
            kind: ArgType,
        ): Votes {
            val votes = into.getOrPut(slot) { HashMap() }.getOrPut(kind) { Votes() }
            votes.uses++
            votes.scripts.add(fn.scriptId)
            return votes
        }

        /**
         * Records what the corpus makes of a value a type character describes,
         * which is what the character is then judged against.
         */
        private fun noteChar(char: Char, argument: Expr, callee: String?) {
            val seen = kindOf(symbolOf(argument)) ?: callee?.let { global.kindOf(it) } ?: return
            val votes = charEvidence.getOrPut(char) { HashMap() }.getOrPut(seen) { Votes() }
            votes.uses++
            votes.scripts.add(fn.scriptId)
        }
    }

    companion object {
        /** How many corpus-wide rounds a settling pass may take. */
        private const val ROUNDS = 12

        /** How many rounds a single function's own locals may take. */
        private const val LOCAL_ROUNDS = 6

        /** How many agreeing uses an opcode slot needs before it is believed. */
        private const val LEARNING_USES = 3

        /** How many different scripts a slot's uses have to come from. */
        private const val LEARNING_SCRIPTS = 2

        /** Comparisons that ask which value is bigger, which no id is asked. */
        private val ORDERINGS = setOf("<", ">", "<=", ">=")

        /** Positions that say what they hold without anything being inferred. */
        private val AUTHORITATIVE = setOf("opcode argument", "hook format", "enum type code")

        /** How many voters an unclear slot is explained with. */
        private const val EXAMPLES = 6

        /** Works out what every constant in the corpus means. */
        fun analyse(
            context: Cs2Context,
            functions: List<Cs2Function>,
            log: (String) -> Unit = {},
        ): Cs2Types = Cs2Types(context, functions).apply { analyse(log) }
    }
}
