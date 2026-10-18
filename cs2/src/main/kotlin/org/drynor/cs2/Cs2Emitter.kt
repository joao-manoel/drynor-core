package org.drynor.cs2

import org.drynor.core.gameval.Gameval
import org.drynor.cs2.ArgType
import org.drynor.cs2.Cs2Packing
import org.drynor.cs2.ir.ArrayAssign
import org.drynor.cs2.ir.ArrayDefine
import org.drynor.cs2.ir.ArrayRef
import org.drynor.cs2.ir.Binary
import org.drynor.cs2.ir.Break
import org.drynor.cs2.ir.Callback
import org.drynor.cs2.ir.AndAll
import org.drynor.cs2.ir.Compare
import org.drynor.cs2.ir.Condition
import org.drynor.cs2.ir.OrAny
import org.drynor.cs2.ir.Continue
import org.drynor.cs2.ir.Cs2Function
import org.drynor.cs2.ir.Cs2Type
import org.drynor.cs2.ir.DestructureAssign
import org.drynor.cs2.ir.Expr
import org.drynor.cs2.ir.ExprStmt
import org.drynor.cs2.ir.Goto
import org.drynor.cs2.ir.If
import org.drynor.cs2.ir.IntConst
import org.drynor.cs2.ir.Join
import org.drynor.cs2.ir.Label
import org.drynor.cs2.ir.LocalAssign
import org.drynor.cs2.ir.LocalRef
import org.drynor.cs2.ir.LongConst
import org.drynor.cs2.ir.MultiAssign
import org.drynor.cs2.ir.OpCall
import org.drynor.cs2.ir.ParallelAssign
import org.drynor.cs2.ir.Return
import org.drynor.cs2.ir.ScriptCall
import org.drynor.cs2.ir.Spread
import org.drynor.cs2.ir.Stmt
import org.drynor.cs2.ir.StrConst
import org.drynor.cs2.ir.Switch
import org.drynor.cs2.ir.TempRef
import org.drynor.cs2.ir.TypedConst
import org.drynor.cs2.ir.VarAssign
import org.drynor.cs2.ir.VarRef
import org.drynor.cs2.ir.While

/**
 * Renders a decompiled script as TypeScript.
 *
 * Locals are declared where they are first written when that is a statement of
 * the function body itself, and in the header otherwise. Either way the slot a
 * name compiles back to comes from the name, so a local can be renamed freely.
 */
class Cs2Emitter(private val function: Cs2Function) {

    private val out = StringBuilder()
    private var indent = 0

    /** Locals a `let` in the body introduces, in the order they appear. */
    private val declaredInBody: List<Int> = declarationsIn(function.body)

    /**
     * The name each local reads as.
     *
     * A local's slot is not its name any more: names are numbered in declaration
     * order - parameters, then the slots the header reserves, then the ones the
     * body declares as it goes - because that is the order the compiler hands
     * slots out in. Numbering them any other way would make an untouched file
     * compile to different slots than it came from.
     */
    private val localNames: Map<Int, String> = nameLocals()

    fun emit(): String {
        out.setLength(0)
        emitHeader()
        emitSignature()
        indent = 1
        emitLocalDeclarations()
        emitBody(function.body)
        indent = 0
        line("}")
        return out.toString()
    }

    /** Renders a fragment on its own, for reports about code that was dropped. */
    fun renderStatements(statements: List<Stmt>): String {
        out.setLength(0)
        indent = 0
        emitAll(statements)
        return out.toString()
    }

    /** Renders a condition on its own, for the same reports. */
    fun renderCondition(value: Condition): String = condition(value)

    private fun emitHeader() {
        // A triple-slash reference makes a single file resolve the opcode and
        // gameval names on its own; cross-script calls still need the tsconfig.
        // The prefix walks back out of the category folder the file sits in.
        val up = Cs2Layout.referencePrefix(function.scriptId)
        line("/// <reference path=\"$up${Cs2Declarations.OPCODES_FILE}\" />")
        line("/// <reference path=\"$up${Cs2Declarations.GAMEVALS_FILE}\" />")
        line("/// <reference path=\"$up${Cs2Declarations.VARS_FILE}\" />")
        // The gameval name is what this script *is*: `gamevals/clientscript.json`
        // is what turns it into the archive id the bytecode is written to, and the
        // path this file sits at says the same thing.
        line("// ${Cs2Layout.headerOf(function.scriptId)}")
        if (function.name != null) line("// name: ${function.name}")
        line("")
    }

    private fun emitSignature() {
        // Parameters follow the order callers push them, which can interleave the
        // stacks. Slots stay correct because they are assigned per type in
        // declaration order, so only the reading order changes.
        val total = function.intArgs + function.stringArgs + function.longArgs
        val order = function.argumentOrder.takeIf { it.size == total }
            ?: List(function.intArgs) { Cs2Type.INT } +
            List(function.stringArgs) { Cs2Type.STRING } +
            List(function.longArgs) { Cs2Type.LONG }

        val next = HashMap<Cs2Type, Int>()
        val params = order.map { type ->
            val slot = next.getOrDefault(type, 0)
            next[type] = slot + 1
            val kind = if (type == Cs2Type.INT) function.intParamKinds.getOrNull(slot) else null
            "${localName(slot, type)}: ${tsType(type, kind)}"
        }

        val returns = when (function.returns.size) {
            0 -> "void"
            1 -> tsType(function.returns.first(), function.returnKinds.firstOrNull())
            else -> function.returns.mapIndexed { index, type ->
                tsType(type, function.returnKinds.getOrNull(index))
            }.joinToString(", ", "[", "]")
        }
        // Not exported: clientscripts share one flat namespace, and keeping the
        // files as scripts rather than modules is what lets one call another
        // without an import.
        line("function ${Cs2Symbols.scriptName(function.scriptId)}(${params.joinToString(", ")}): $returns {")
    }

    /**
     * Reserves the slots the body does not declare itself, in slot order. A
     * declaration with no initialiser produces no instructions at all - it only
     * says which slot the name means.
     */
    private fun emitLocalDeclarations() {
        var declared = false
        for (slot in function.intArgs until function.intLocals) {
            if (key(slot, Cs2Type.INT) in declaredInBody) continue
            line("let ${localName(slot, Cs2Type.INT)}: ${localType(slot)};")
            declared = true
        }
        for (slot in function.stringArgs until function.stringLocals) {
            if (key(slot, Cs2Type.STRING) in declaredInBody) continue
            line("let ${localName(slot, Cs2Type.STRING)}: string;")
            declared = true
        }
        for (slot in function.longArgs until function.longLocals) {
            if (key(slot, Cs2Type.LONG) in declaredInBody) continue
            line("let ${localName(slot, Cs2Type.LONG)}: bigint;")
            declared = true
        }
        if (declared) line("")
    }

    private fun key(slot: Int, type: Cs2Type) = (slot shl 2) or type.ordinal

    /** The locals the body introduces with a `let` of its own, in source order. */
    private fun declarationsIn(body: List<Stmt>): List<Int> {
        val out = ArrayList<Int>()
        for (statement in body) {
            when {
                statement is LocalAssign && statement.declare ->
                    out.add(key(statement.slot, statement.type))
                statement is DestructureAssign && statement.declare ->
                    statement.targets.filterIsInstance<LocalRef>()
                        .forEach { out.add(key(it.slot, it.type)) }
            }
        }
        return out
    }

    private fun nameLocals(): Map<Int, String> {
        val out = HashMap<Int, String>()
        for (type in Cs2Type.entries) {
            val prefix = prefixOf(type)
            val args = argumentsOf(type)
            val total = localsOf(type)
            for (slot in 0 until args) out[key(slot, type)] = "${prefix}Arg$slot"

            val inline = declaredInBody.filter { it and 3 == type.ordinal }
            val header = (args until total).map { key(it, type) }.filterNot { it in inline }
            var index = args
            for (local in header + inline) {
                out[local] = "$prefix$index"
                index++
            }
        }
        return out
    }

    private fun prefixOf(type: Cs2Type) = when (type) {
        Cs2Type.INT -> "int"
        Cs2Type.STRING -> "str"
        Cs2Type.LONG -> "long"
    }

    private fun argumentsOf(type: Cs2Type) = when (type) {
        Cs2Type.INT -> function.intArgs
        Cs2Type.STRING -> function.stringArgs
        Cs2Type.LONG -> function.longArgs
    }

    private fun localsOf(type: Cs2Type) = when (type) {
        Cs2Type.INT -> function.intLocals
        Cs2Type.STRING -> function.stringLocals
        Cs2Type.LONG -> function.longLocals
    }

    private fun tsType(type: Cs2Type, kind: ArgType? = null): String = when (type) {
        Cs2Type.INT -> kind?.let { Cs2Names.aliasFor(it) } ?: "number"
        Cs2Type.STRING -> "string"
        Cs2Type.LONG -> "bigint"
    }

    /** What an int local reads as, which is what its uses agree it holds. */
    private fun localType(slot: Int) = tsType(Cs2Type.INT, function.intLocalKinds[slot])

    private fun localName(slot: Int, type: Cs2Type): String =
        localNames[key(slot, type)] ?: "${prefixOf(type)}$slot"

    // ----------------------------------------------------------- statements

    /**
     * The function body, with the blank lines that separate the run of leading
     * declarations from the work and the work from the first block statement.
     */
    private fun emitBody(body: List<Stmt>) {
        var leading = true
        for ((index, statement) in body.withIndex()) {
            val declaration = statement is LocalAssign && statement.declare
            if (index > 0) {
                if (statement is If || statement is While || statement is Switch) line("")
                else if (leading && !declaration) line("")
            }
            if (!declaration) leading = false
            emit(statement)
        }
    }

    private fun emitAll(statements: List<Stmt>) = statements.forEach(::emit)

    private fun emit(statement: Stmt) {
        when (statement) {
            // A bare result that is thrown away reads better as an explicit
            // discard than as a lone identifier.
            is ExprStmt -> if (statement.call is TempRef) {
                line("discard(${expr(statement.call)});")
            } else {
                line("${expr(statement.call)};")
            }

            is LocalAssign -> {
                val name = localName(statement.slot, statement.type)
                val type = if (statement.type == Cs2Type.INT) localType(statement.slot)
                else tsType(statement.type)
                val declaration = if (statement.declare) "let $name: $type" else name
                line("$declaration = ${expr(statement.value)};")
            }

            is ParallelAssign -> {
                val targets = statement.targets.joinToString(", ") { expr(it) }
                val values = statement.values.joinToString(", ") { expr(it) }
                line("[$targets] = [$values];")
            }

            is DestructureAssign -> {
                val targets = statement.targets.joinToString(", ") { expr(it) }
                val keyword = if (statement.declare) "let " else ""
                line("$keyword[$targets] = ${expr(statement.call)};")
            }

            is VarAssign -> line("${expr(statement.target)} = ${expr(statement.value)};")

            is ArrayAssign ->
                line("array${statement.array}[${expr(statement.index)}] = ${expr(statement.value)};")

            // The element type is a type code, like an enum's: it decides what
            // the array is filled with when it is made.
            is ArrayDefine -> {
                val element = Cs2TypeChars.nameOf(statement.elementType) ?: statement.elementType.toString()
                line("defineArray(${statement.array}, $element, ${expr(statement.size)});")
            }

            is MultiAssign -> {
                val names = statement.names.joinToString(", ") { it.name }
                line("const [$names] = ${expr(statement.call)};")
            }

            is Return -> when (statement.values.size) {
                0 -> line("return;")
                1 -> line("return ${expr(statement.values.first())};")
                else -> line("return [${statement.values.joinToString(", ") { expr(it) }}];")
            }

            is If -> emitIf(statement, "")

            is While -> {
                line("while (${statement.condition?.let { condition(it) } ?: "true"}) {")
                indented { emitAll(statement.body) }
                line("}")
            }

            is Switch -> {
                line("switch (${expr(statement.subject)}) {")
                indented {
                    for (case in statement.cases) {
                        case.keys.forEach { line("case ${caseKey(it, statement.keyKind)}:") }
                        indented {
                            emitAll(case.body)
                            if (!exits(case.body)) line("break;")
                        }
                    }
                    // `default:` always goes last, which is the layout the code
                    // generator produces; the structurer still reads both.
                    if (statement.default.isNotEmpty()) {
                        line("default:")
                        indented { emitAll(statement.default) }
                    }
                }
                line("}")
            }

            is Label -> {
                indent--
                // The empty statement keeps a label legal even when it lands at
                // the end of a block, which TypeScript otherwise rejects.
                line("${statement.name}: ;")
                indent++
            }

            is Goto -> line("goto(\"${statement.label}\");")
            is Break -> line(if (statement.label == null) "break;" else "break ${statement.label};")
            is Continue -> line(if (statement.label == null) "continue;" else "continue ${statement.label};")
        }
    }

    /**
     * An `if` whose else-arm is nothing but another `if` is one link of a chain,
     * and reads as `} else if (...) {` rather than as a new level of nesting.
     */
    private fun emitIf(statement: If, prefix: String) {
        line("${prefix}if (${condition(statement.condition)}) {")
        indented { emitAll(statement.then) }
        val chained = statement.otherwise.singleOrNull() as? If
        when {
            statement.otherwise.isEmpty() -> line("}")
            chained != null -> emitIf(chained, "} else ")
            else -> {
                line("} else {")
                indented { emitAll(statement.otherwise) }
                line("}")
            }
        }
    }

    /** True when a body already transfers control, so no `break` is needed. */
    private fun exits(body: List<Stmt>): Boolean =
        when (body.lastOrNull()) {
            is Return, is Break, is Continue, is Goto -> true
            else -> false
        }

    /**
     * `BRANCH_IF_TRUE` / `BRANCH_IF_FALSE` test a single value against a literal
     * rather than comparing two. Rendering them as `x == 1` would re-compile to
     * a different opcode, so they keep a form of their own.
     */
    private fun condition(condition: Condition, parentBinds: Boolean = false): String = when (condition) {
        is Compare -> {
            val subject = expr(condition.left, COMPARISON)
            when (condition.comparison) {
                Cs2Op.BRANCH_IF_TRUE -> if (condition.negated) "isFalsy($subject)" else "isTruthy($subject)"
                Cs2Op.BRANCH_IF_FALSE -> if (condition.negated) "isTruthy($subject)" else "isFalsy($subject)"
                // A branch taken when the comparison fails reads as the opposite
                // operator; the opcode it compiles back to is chosen from that.
                else -> "$subject ${condition.symbol} ${expr(condition.right, COMPARISON)}"
            }
        }
        is AndAll -> condition.terms.joinToString(" && ") { condition(it, parentBinds = true) }
            .let { if (parentBinds) "($it)" else it }
        is OrAny -> condition.terms.joinToString(" || ") { condition(it, parentBinds = true) }
            .let { if (parentBinds) "($it)" else it }
    }

    // ---------------------------------------------------------- expressions

    /**
     * Operator binding strength, TypeScript's own, so the printed expression
     * parses back into the tree it came from with the fewest brackets.
     *
     * Comparisons sit between the additive operators and `&`, which is why
     * `a & b == c` means `a & (b == c)` in TypeScript and why a bitwise operand
     * of a comparison is always bracketed.
     */
    private fun precedence(symbol: String) = when (symbol) {
        "*", "/", "%" -> MULTIPLICATIVE
        "+", "-" -> ADDITIVE
        "&" -> BITWISE_AND
        "|" -> BITWISE_OR
        else -> ATOM
    }

    private fun precedenceOf(value: Expr) = when (value) {
        is Binary -> precedence(value.symbol)
        // String concatenation is `+`, and a nested one is a second JOIN_STRING
        // rather than more parts of this one, so it has to stay bracketed.
        is Join -> ADDITIVE
        else -> ATOM
    }

    private fun expr(value: Expr, minimum: Int = 0): String {
        val text = render(value)
        return if (precedenceOf(value) < minimum) "($text)" else text
    }

    private fun render(value: Expr): String = when (value) {
        is IntConst -> Cs2Events.nameOf(value.value) ?: value.value.toString()
        is TypedConst -> typedConst(value)
        is StrConst -> "\"${Cs2Strings.escape(value.value)}\""
        is LongConst -> "${value.value}n"
        is LocalRef -> localName(value.slot, value.type)
        is TempRef -> value.name
        is ArrayRef -> "array${value.array}[${expr(value.index)}]"
        is VarRef -> varAccess(value)
        is Binary -> binary(value)
        is Join -> value.parts.joinToString(" + ") { expr(it, ADDITIVE + 1) }
        is OpCall -> {
            // The instruction's own operand rides along as a type argument, so
            // it survives editing without cluttering the call's arguments.
            val variant = if (value.operand != 0) "<${value.operand}>" else ""
            "${value.op.tsName}$variant(${value.args.joinToString(", ") { expr(it) }})"
        }
        is ScriptCall -> "${Cs2Symbols.scriptName(value.scriptId)}(${value.args.joinToString(", ") { expr(it) }})"
        is Spread -> "...${expr(value.call)}"
        is Callback -> callback(value)
    }

    /**
     * A binary operator with only the brackets it needs. The right operand keeps
     * them at equal strength unless the operator is one the compiler can
     * re-associate without changing the value.
     */
    private fun binary(value: Binary): String {
        val level = precedence(value.symbol)
        val associative = value.symbol in ASSOCIATIVE &&
            (value.right as? Binary)?.symbol == value.symbol
        val left = expr(value.left, level)
        val right = if (value.symbol in MASKS && value.right is IntConst) {
            hex(value.right.value)
        } else {
            expr(value.right, if (associative) level else level + 1)
        }
        return "$left ${value.symbol} $right"
    }

    /** A bit mask reads as one; the value is the same number either way. */
    private fun hex(value: Int) = "0x%X".format(value.toLong() and 0xFFFFFFFFL)

    /**
     * Spells out a composite constant. A component hash becomes the interface and
     * component it packs, by their gameval names where they have them; every other
     * id becomes its gameval constant. Values that carry no such structure - `-1`
     * and other sentinels - and ids the catalogs have no proven name for keep the
     * bare number, so they still round-trip unchanged.
     */
    private fun caseKey(key: Int, kind: ArgType): String =
        if (kind == ArgType.INT) key.toString() else typedConst(TypedConst(key, kind))

    /**
     * A constant, written as what it means.
     *
     * A gameval constant says its own kind - `Obj.coins` is an obj because of
     * where the name comes from - so nothing wraps it. What has no name in a
     * catalog is a plain number: -1 and the client's other sentinels, and any
     * value in a typed position that is not an id of that type at all. Only the
     * packed values keep a helper, having no name to carry them.
     */
    private fun typedConst(value: TypedConst): String {
        Cs2Events.nameOf(value.value)?.let { return it }
        return when (value.kind) {
            // A type code is a character, and reads as the type it names.
            ArgType.TYPECHAR -> Cs2TypeChars.nameOf(value.value) ?: value.value.toString()
            ArgType.COMPONENT -> component(value.value)
            ArgType.COORD ->
                if (Cs2Packing.isPackedCoord(value.value)) {
                    "coord(${Cs2Packing.xOf(value.value)}, ${Cs2Packing.yOf(value.value)}, " +
                        "${Cs2Packing.planeOf(value.value)})"
                } else {
                    value.value.toString()
                }
            // A script referred to by id names the function it is, so renaming the
            // function renames the reference too.
            ArgType.SCRIPT -> scriptReference(value.value)
            // A flag reads as a flag; anything outside 0/1 keeps its number so the
            // value still round-trips.
            ArgType.BOOLEAN -> when (value.value) {
                0 -> "false"
                1 -> "true"
                else -> value.value.toString()
            }
            // Only a plain RGB triple reads as one: -1 is "no colour", and anything
            // with bits above the low three bytes is not a colour at all.
            ArgType.COLOUR ->
                if (value.value in 0..0xFFFFFF) "colour(0x%06X)".format(value.value)
                else value.value.toString()
            else -> constant(value.kind, value.value)
        }
    }

    /**
     * The gameval constant for an id, or the bare number when no catalog covers
     * it - which is what a sentinel is.
     */
    private fun constant(kind: ArgType, id: Int): String {
        val type = Cs2Names.catalogType(kind) ?: return id.toString()
        val name = Cs2Names.identifier(type, id) ?: return id.toString()
        return "${Cs2Names.NAMESPACES.getValue(type)}.$name"
    }

    private fun component(hash: Int): String {
        Cs2Names.componentPath(hash)?.let { (interfaceName, componentName) ->
            return "${Cs2Names.NAMESPACES.getValue(Gameval.COMPONENT)}.$interfaceName.$componentName"
        }
        return hash.toString()
    }

    /** The function name of a clientscript, or its id when the cache has no such script. */
    private fun scriptReference(id: Int): String =
        Cs2Names.scriptIdentifiers()[id] ?: id.toString()

    private fun varAccess(value: VarRef): String = Cs2Symbols.variableName(value)

    private fun callback(value: Callback): String {
        if (value.scriptId == -1) return "noHook(\"${Cs2Strings.escape(value.format)}\")"
        val format = "\"${Cs2Strings.escape(value.format)}\""
        val args = "[${value.args.joinToString(", ") { expr(it) }}]"
        val script = Cs2Symbols.scriptName(value.scriptId)
        if (!value.hasTriggerList) return "hook($script, $format, $args)"
        val triggers = "[${value.triggers.joinToString(", ") { expr(it) }}]"
        return "hook($script, $format, $args, $triggers)"
    }

    // --------------------------------------------------------------- output

    private fun line(text: String) {
        if (text.isEmpty()) {
            // Two blank lines in a row say nothing the first one did not.
            if (out.isEmpty() || out.endsWith("\n\n")) return
            out.append('\n')
            return
        }
        repeat(indent) { out.append("    ") }
        out.append(text).append('\n')
    }

    private inline fun indented(body: () -> Unit) {
        indent++
        body()
        indent--
    }

    private companion object {
        const val BITWISE_OR = 1
        const val BITWISE_AND = 2
        const val COMPARISON = 3
        const val ADDITIVE = 4
        const val MULTIPLICATIVE = 5
        const val ATOM = 100

        /** Operators a compiler may re-associate without changing the result. */
        val ASSOCIATIVE = setOf("+", "*", "&", "|")

        /** Operators whose right-hand constant is a bit pattern, not a count. */
        val MASKS = setOf("&", "|")
    }
}
