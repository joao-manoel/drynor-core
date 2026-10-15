package org.drynor.cs2.compile

import org.drynor.core.gameval.Gameval
import org.drynor.cs2.Cs2Identifiers
import org.drynor.cs2.Cs2Layout
import org.drynor.cs2.Cs2Names
import org.drynor.cs2.Cs2Op
import org.drynor.cs2.ArgType
import org.drynor.cs2.Cs2SymbolTable
import org.drynor.cs2.Cs2Events
import org.drynor.cs2.Cs2Symbols
import org.drynor.cs2.Cs2TypeChars
import org.drynor.cs2.Cs2Packing
import org.drynor.cs2.OpKind
import org.drynor.cs2.Cs2Strings
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
import org.drynor.cs2.ir.negate
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
import org.drynor.cs2.ir.SwitchCase
import org.drynor.cs2.ir.TempRef
import org.drynor.cs2.ir.TypedConst
import org.drynor.cs2.ir.VarAssign
import org.drynor.cs2.ir.VarRef
import org.drynor.cs2.ir.VarSpace
import org.drynor.cs2.ir.While

/**
 * Parses the TypeScript subset the decompiler emits back into the IR.
 *
 * Local slots are assigned by declaration order within each type - parameters
 * first, then `let` declarations - so locals can be renamed freely without
 * changing which slot they compile to.
 */
class Cs2Parser(
    source: String,
    /** Resolves whether a param id yields a string; see [Cs2Context]. */
    private val paramTypes: (Int) -> Boolean = { false },
    /**
     * Resolves identifiers that have been renamed away from their default
     * names. Without one, only the generated names are understood - which is
     * enough to compile a file nobody has touched.
     */
    private val symbols: Cs2SymbolTable = Cs2SymbolTable.EMPTY,
    /**
     * Where the file sits under the project folder, e.g. `proc/quicksort.ts`.
     *
     * The layout is a binding in its own right - the catalog turns a script's name
     * into its archive id, and the path spells that name out - so it stands in for
     * a header a hand-made file never had.
     */
    private val relativePath: String? = null,
    /**
     * What a called script leaves on the stacks, in push order. A destructuring
     * declaration has nowhere to write the types of the names it introduces, so
     * they come from the call on its right.
     */
    private val returnTypes: (Int) -> List<Cs2Type> = { emptyList() },
) {

    // What the file says it is: its gameval name, or the numbered header an older
    // dump wrote. See [Cs2Layout.scriptIdOf].
    private val headerId: Int? = Cs2Layout.scriptIdOf(source, relativePath)

    private val gamevalHeader: String? = Cs2Layout.gamevalHeader(source)

    private val nameComment: String? =
        Regex("^// name: (.*)$", RegexOption.MULTILINE).find(source)?.groupValues?.get(1)

    private val tokens = Cs2Lexer(source).tokenise()
    private var at = 0

    private class Local(val slot: Int, val type: Cs2Type)

    private val locals = HashMap<String, Local>()
    private var intCount = 0
    private var stringCount = 0
    private var longCount = 0
    private var intArgs = 0
    private var stringArgs = 0
    private var longArgs = 0
    private var scriptId = -1
    private var scriptName: String? = null
    private var returns = emptyList<Cs2Type>()

    /** The declared parameter types in declaration order; see [parseHeader]. */
    private val argumentTypes = ArrayList<Cs2Type>()

    fun parse(): Cs2Function {
        parseSignature()
        val body = parseBlock()
        return Cs2Function(
            scriptId = scriptId,
            name = scriptName,
            intArgs = intArgs,
            stringArgs = stringArgs,
            longArgs = longArgs,
            intLocals = intCount,
            stringLocals = stringCount,
            longLocals = longCount,
            body = body,
            returns = returns,
        )
    }

    // ------------------------------------------------------------- signature

    private fun parseSignature() {
        val name = parseHeader()
        // The header is authoritative, so the function can be renamed freely. A
        // header naming a script the catalogs no longer have is an error rather
        // than something to fall back from: falling back would compile this file
        // into whatever archive the function name happens to resolve to.
        if (headerId == null && gamevalHeader != null) {
            error(
                "'$gamevalHeader' is not a clientscript in gamevals/clientscript.json; " +
                    "the catalog was renamed without `cs2 apply-names`",
            )
        }
        scriptId = headerId ?: Cs2Symbols.scriptOf(name) ?: symbols.script(name)
            ?: error("Cannot tell which clientscript '$name' is")
    }

    /**
     * The declaration itself - `function name(a: number, b: string): [number, string] {` - up to
     * and including the brace that opens the body, leaving [returns] and the argument slots set.
     *
     * Split out of [parseSignature] because which script a file *is* and what its signature *says*
     * are separate questions, and [Companion.signature] asks only the second one: a folder about to
     * be compiled needs every other file's signature, and resolving 6,567 headers against the
     * catalogs to get them would refuse the whole folder over one orphan.
     *
     * @return the function's name, which is what a caller writes.
     */
    private fun parseHeader(): String {
        scriptName = nameComment
        while (peek().kind == TokenKind.IDENT && peek().text != "function") skip()
        expectIdent("function")
        val name = expect(TokenKind.IDENT).text
        expectPunct("(")
        while (!atPunct(")")) {
            val parameter = expect(TokenKind.IDENT).text
            expectPunct(":")
            val type = parseType()
            declare(parameter, type, argument = true)
            // Declaration order, which can interleave the stacks and is therefore not the slot
            // order; it is the order callers push in and the order a signature has to be read in.
            argumentTypes.add(type)
            if (atPunct(",")) skip()
        }
        expectPunct(")")
        expectPunct(":")
        returns = parseReturnType()
        expectPunct("{")
        return name
    }

    private fun parseType(): Cs2Type = typeNamed(expect(TokenKind.IDENT).text)

    private fun parseReturnType(): List<Cs2Type> {
        if (atPunct("[")) {
            skip()
            val types = ArrayList<Cs2Type>()
            while (!atPunct("]")) {
                types.add(parseType())
                if (atPunct(",")) skip()
            }
            skip()
            return types
        }
        val text = peek().text
        if (text == "void") {
            skip()
            return emptyList()
        }
        return listOf(parseType())
    }

    private fun declare(name: String, type: Cs2Type, argument: Boolean): Local {
        val slot = when (type) {
            Cs2Type.INT -> intCount++
            Cs2Type.STRING -> stringCount++
            Cs2Type.LONG -> longCount++
        }
        if (argument) {
            when (type) {
                Cs2Type.INT -> intArgs++
                Cs2Type.STRING -> stringArgs++
                Cs2Type.LONG -> longArgs++
            }
        }
        val local = Local(slot, type)
        locals[name] = local
        return local
    }

    // ------------------------------------------------------------ statements

    private fun parseBlock(): List<Stmt> {
        val out = ArrayList<Stmt>()
        while (!atPunct("}")) {
            if (peek().kind == TokenKind.EOF) error("Unexpected end of input, expected '}'")
            out.add(parseStatement() ?: continue)
        }
        skip()
        return out
    }

    private fun parseStatement(): Stmt? {
        // An empty statement carries no instructions; it only exists so a label
        // at the end of a block is valid TypeScript.
        if (atPunct(";")) {
            skip()
            return null
        }
        val token = peek()
        if (token.kind == TokenKind.IDENT) {
            when (token.text) {
                "let", "const" -> return parseDeclaration()
                "if" -> return parseIf()
                "while" -> return parseWhile()
                "switch" -> return parseSwitch()
                "return" -> return parseReturn()
                "goto" -> {
                    skip()
                    expectPunct("(")
                    val label = expect(TokenKind.STRING).text
                    expectPunct(")")
                    expectPunct(";")
                    return Goto(label)
                }
                "break" -> { skip(); expectPunct(";"); return Break() }
                "continue" -> { skip(); expectPunct(";"); return Continue() }
            }
            // `L12:` is a jump label, not an expression.
            if (tokens.getOrNull(at + 1)?.text == ":" && token.text.startsWith("L")) {
                skip()
                skip()
                return Label(token.text)
            }
        }
        if (atPunct("[")) return parseParallelAssign()
        return parseExpressionStatement()
    }

    private fun parseDeclaration(): Stmt? {
        val keyword = expect(TokenKind.IDENT).text
        if (atPunct("[")) {
            // `const [...]` names a call's results; `let [...]` puts them
            // straight into locals it declares as it goes.
            return if (keyword == "const") parseTemporaries() else parseDeclaredDestructuring()
        }

        val name = expect(TokenKind.IDENT).text
        expectPunct(":")
        val type = parseType()
        // Slots are handed out in declaration order, which is what lets a local
        // be renamed to anything: the name never says which slot it is.
        val local = declare(name, type, argument = false)
        // A declaration with no initialiser only reserves the slot; one with an
        // initialiser is an ordinary store that happens to introduce the name.
        if (!atPunct("=")) {
            expectPunct(";")
            return null
        }
        skip()
        val value = parseExpression()
        expectPunct(";")
        return LocalAssign(local.slot, local.type, value, declare = true)
    }

    /** `[a, b] = f();` - a call's results, stored into locals that already exist. */
    private fun parseDestructuring(): Stmt {
        expectPunct("[")
        val targets = ArrayList<Expr>()
        while (!atPunct("]")) {
            val target = parseExpression()
            require(target is LocalRef || target is VarRef) { "Cannot assign to $target" }
            targets.add(target)
            if (atPunct(",")) skip()
        }
        skip()
        expectPunct("=")
        val call = parseExpression()
        expectPunct(";")
        return DestructureAssign(targets, call, declare = false)
    }

    /**
     * `let [a, b] = f();` - a call's results, into locals it declares here.
     *
     * The names are read before the call so the call can say what they are: a
     * destructured name has no type annotation, and which stack it lives on is
     * exactly what decides the opcode that stores it.
     */
    private fun parseDeclaredDestructuring(): Stmt {
        expectPunct("[")
        val names = ArrayList<String>()
        while (!atPunct("]")) {
            names.add(expect(TokenKind.IDENT).text)
            if (atPunct(",")) skip()
        }
        skip()
        expectPunct("=")
        val call = parseExpression()
        expectPunct(";")
        val types = resultTypes(call)
        if (types.size != names.size) {
            error("${names.size} names but the call leaves ${types.size} values")
        }
        val targets = names.mapIndexed { index, name ->
            val local = declare(name, types[index], argument = false)
            LocalRef(local.slot, local.type) as Expr
        }
        return DestructureAssign(targets, call, declare = true)
    }

    /** The stacks a multi-value call leaves its results on, in push order. */
    private fun resultTypes(call: Expr): List<Cs2Type> = when (call) {
        is ScriptCall -> returnTypes(call.scriptId)
        is OpCall -> List(call.op.pushInt) { Cs2Type.INT } +
            List(call.op.pushStr) { Cs2Type.STRING } +
            List(call.op.pushLong) { Cs2Type.LONG }
        else -> error("$call does not leave several values")
    }

    /** `const [tmpInt0, tmpStr0] = f();` - a call's results, bound to names. */
    private fun parseTemporaries(): Stmt {
        expectPunct("[")
        val names = ArrayList<String>()
        while (!atPunct("]")) {
            names.add(expect(TokenKind.IDENT).text)
            if (atPunct(",")) skip()
        }
        skip()
        expectPunct("=")
        val call = parseExpression()
        expectPunct(";")
        val temps = names.map { TempRef(it, tempType(it)) }
        temps.forEach { tempTypes[it.name] = it }
        return MultiAssign(temps, call)
    }

    private val tempTypes = HashMap<String, TempRef>()

    /**
     * Which stack an opcode's single result lands on. This decides whether `+`
     * means arithmetic or string joining, so guessing `number` would silently
     * turn a `JOIN_STRING` into an `ADD`.
     */
    private fun opResultType(op: Cs2Op, args: List<Expr>): Cs2Type {
        if (op.kind == OpKind.PARAM) {
            val yieldsString = if (op == Cs2Op.ENUM) {
                (args.getOrNull(1) as? IntConst)?.value == 's'.code
            } else {
                val paramId = (args.lastOrNull { it.type == Cs2Type.INT } as? IntConst)?.value
                paramId != null && paramTypes(paramId)
            }
            return if (yieldsString) Cs2Type.STRING else Cs2Type.INT
        }
        return when {
            op.pushStr == 1 && op.pushInt == 0 && op.pushLong == 0 -> Cs2Type.STRING
            op.pushLong == 1 && op.pushInt == 0 && op.pushStr == 0 -> Cs2Type.LONG
            else -> Cs2Type.INT
        }
    }

    /**
     * Turns a spelled-out constant back into the integer it packs. This is the
     * inverse of the decompiler's typed-constant rendering.
     *
     * A gameval constant has already become an [IntConst] by the time it gets
     * here, so a name and a number reach the bytecode identically; `scriptId`
     * additionally accepts the referenced script's own function.
     */
    private fun packedConstant(name: String, args: List<Expr>): Expr? {
        val values = args.map {
            when (it) {
                is IntConst -> it.value
                is TypedConst -> it.value
                is ScriptCall -> if (it.args.isEmpty()) it.scriptId else return null
                else -> return null
            }
        }
        val kind = Cs2Names.argTypeOf(name) ?: return null
        val packed = when {
            kind == ArgType.COMPONENT && values.size == 2 -> Cs2Packing.packComponent(values[0], values[1])
            kind == ArgType.COORD && values.size == 3 -> Cs2Packing.packCoord(values[0], values[1], values[2])
            values.size == 1 -> values[0]
            else -> error("$name takes ${values.size} arguments")
        }
        return TypedConst(packed, kind)
    }

    /** Destructured results carry their stack in the name. */
    private fun tempType(name: String) = when {
        name.startsWith("tmpStr") -> Cs2Type.STRING
        name.startsWith("tmpLong") -> Cs2Type.LONG
        else -> Cs2Type.INT
    }

    private fun parseIf(): Stmt {
        expectIdent("if")
        expectPunct("(")
        val condition = parseCondition()
        expectPunct(")")
        expectPunct("{")
        val then = parseBlock()
        var otherwise = emptyList<Stmt>()
        if (peek().text == "else") {
            skip()
            if (peek().text == "if") {
                otherwise = listOf(parseIf())
            } else {
                expectPunct("{")
                otherwise = parseBlock()
            }
        }
        return If(condition, then, otherwise)
    }

    private fun parseWhile(): Stmt {
        expectIdent("while")
        expectPunct("(")
        // `while (true)` is a loop with its test written out in the body.
        val condition = if (peek().text == "true") skip().let { null } else parseCondition()
        expectPunct(")")
        expectPunct("{")
        return While(condition, parseBlock())
    }

    private fun parseSwitch(): Stmt {
        expectIdent("switch")
        expectPunct("(")
        val subject = parseExpression()
        expectPunct(")")
        expectPunct("{")

        val cases = ArrayList<SwitchCase>()
        var default = emptyList<Stmt>()
        while (!atPunct("}")) {
            if (peek().text == "default") {
                skip()
                expectPunct(":")
                default = parseCaseBody()
                continue
            }
            val keys = ArrayList<Int>()
            while (peek().text == "case") {
                skip()
                keys.add(parseCaseKey())
                expectPunct(":")
            }
            cases.add(SwitchCase(keys, parseCaseBody()))
        }
        skip()
        // Where `default:` sits says nothing about the layout any more: the code
        // generator writes the jump-to-default shape, which is the one the
        // structurer reads back, unless the arms are a bare jump table.
        return Switch(subject, cases, default, table = -1)
    }

    /** The number a constant expression holds, whatever spelling it was given. */
    private fun constantValue(value: Expr): Int = when (value) {
        is IntConst -> value.value
        is TypedConst -> value.value
        else -> error("Expected a constant, but found $value")
    }

    /** A case key is any constant expression, so `case colour(0xFF0000):` works. */
    private fun parseCaseKey(): Int = when (val key = parseExpression()) {
        is IntConst -> key.value
        is TypedConst -> key.value
        else -> error("A case key has to be a constant, but found $key")
    }

    private fun parseCaseBody(): List<Stmt> {
        val out = ArrayList<Stmt>()
        while (!atPunct("}") && peek().text != "case" && peek().text != "default") {
            if (peek().text == "break" && tokens.getOrNull(at + 1)?.text == ";") {
                skip()
                skip()
                break
            }
            out.add(parseStatement() ?: continue)
        }
        return out
    }

    private fun parseReturn(): Stmt {
        expectIdent("return")
        if (atPunct(";")) {
            skip()
            return Return(emptyList())
        }
        val values = ArrayList<Expr>()
        if (atPunct("[")) {
            skip()
            while (!atPunct("]")) {
                values.add(parseExpression())
                if (atPunct(",")) skip()
            }
            skip()
        } else {
            values.add(parseExpression())
        }
        expectPunct(";")
        return Return(values)
    }

    /** `[a, b] = [x, y];` - a parallel assignment, or a destructured call. */
    private fun parseParallelAssign(): Stmt {
        val mark = at
        expectPunct("[")
        val targets = ArrayList<Expr>()
        while (!atPunct("]")) {
            val target = parseExpression()
            require(target is LocalRef || target is VarRef) { "Cannot assign to $target" }
            targets.add(target)
            if (atPunct(",")) skip()
        }
        skip()
        expectPunct("=")
        // A list on the right is a parallel assignment; anything else is a call
        // whose results go straight into the targets.
        if (!atPunct("[")) {
            at = mark
            return parseDestructuring()
        }
        expectPunct("[")
        val values = ArrayList<Expr>()
        while (!atPunct("]")) {
            values.add(parseExpression())
            if (atPunct(",")) skip()
        }
        skip()
        expectPunct(";")
        return ParallelAssign(targets, values)
    }

    private fun parseExpressionStatement(): Stmt {
        val target = parseExpression()
        if (atPunct("=")) {
            skip()
            val value = parseExpression()
            expectPunct(";")
            return when (target) {
                is LocalRef -> LocalAssign(target.slot, target.type, value, declare = false)
                is VarRef -> VarAssign(target, value)
                is ArrayRef -> ArrayAssign(target.array, target.index, value)
                else -> error("Cannot assign to $target")
            }
        }
        expectPunct(";")
        if (target is OpCall && target.op == Cs2Op.DEFINE_ARRAY) {
            val array = constantValue(target.args[0])
            val elementType = constantValue(target.args[1])
            return ArrayDefine(array, elementType, target.args[2])
        }
        return ExprStmt(target)
    }

    // ----------------------------------------------------------- expressions

    private fun parseCondition(): Condition = parseOr()

    private fun parseOr(): Condition {
        val terms = ArrayList<Condition>()
        terms.add(parseAnd())
        while (atPunct("||")) {
            skip()
            terms.add(parseAnd())
        }
        return if (terms.size == 1) terms.first() else OrAny(terms)
    }

    private fun parseAnd(): Condition {
        val terms = ArrayList<Condition>()
        terms.add(parseComparison())
        while (atPunct("&&")) {
            skip()
            terms.add(parseComparison())
        }
        return if (terms.size == 1) terms.first() else AndAll(terms)
    }

    /**
     * A leading `(` is ambiguous: it opens either a grouped condition or a
     * parenthesised operand. Try the group first and rewind if it does not
     * close cleanly.
     */
    private fun parseComparison(): Condition {
        if (atPunct("!")) {
            skip()
            expectPunct("(")
            val inner = parseOr()
            expectPunct(")")
            return when (inner) {
                is Compare -> inner.copy(negated = true)
                else -> inner.negate()
            }
        }
        if (atPunct("(")) {
            val mark = at
            try {
                skip()
                val inner = parseOr()
                if (atPunct(")")) {
                    skip()
                    return inner
                }
            } catch (e: Exception) {
                // fall through to the operand reading below
            }
            at = mark
        }

        val left = parseExpression()
        if (left is OpCall && (left.op == Cs2Op.BRANCH_IF_TRUE || left.op == Cs2Op.BRANCH_IF_FALSE)) {
            return Compare(left.op, left.args.first(), IntConst(0), negated = false)
        }
        val symbol = expect(TokenKind.PUNCT).text
        val right = parseExpression()
        val longs = left.type == Cs2Type.LONG || right.type == Cs2Type.LONG
        val op = when (symbol) {
            "==", "===" -> if (longs) Cs2Op.LONG_BRANCH_EQUALS else Cs2Op.BRANCH_EQUALS
            "!=", "!==" -> if (longs) Cs2Op.LONG_BRANCH_NOT else Cs2Op.BRANCH_NOT
            "<" -> if (longs) Cs2Op.LONG_BRANCH_LESS_THAN else Cs2Op.BRANCH_LESS_THAN
            ">" -> if (longs) Cs2Op.LONG_BRANCH_GREATER_THAN else Cs2Op.BRANCH_GREATER_THAN
            "<=" -> if (longs) Cs2Op.LONG_BRANCH_LESS_THAN_OR_EQUALS else Cs2Op.BRANCH_LESS_THAN_OR_EQUALS
            ">=" -> if (longs) Cs2Op.LONG_BRANCH_GREATER_THAN_OR_EQUALS else Cs2Op.BRANCH_GREATER_THAN_OR_EQUALS
            else -> error("Unsupported comparison '$symbol'")
        }
        return Compare(op, left, right, negated = false)
    }

    /**
     * Binary operators by binding strength, loosest first - TypeScript's own
     * order, which is what the decompiler prints against.
     */
    private val precedenceLevels = listOf(
        setOf("|"), setOf("&"), setOf("+", "-"), setOf("*", "/", "%"),
    )

    private fun parseExpression(): Expr = parseBinary(0)

    private fun parseBinary(level: Int): Expr {
        if (level >= precedenceLevels.size) return parseUnary()
        val operators = precedenceLevels[level]
        var left = parseBinary(level + 1)
        // String `+` is JOIN_STRING, which takes all its parts at once. A
        // bracketed join is a JOIN_STRING of its own, so only the parts this
        // loop reads are joined here.
        var parts: MutableList<Expr>? = null
        while (peek().kind == TokenKind.PUNCT && peek().text in operators) {
            val symbol = skip().text
            val right = parseBinary(level + 1)
            if (left.type == Cs2Type.STRING || right.type == Cs2Type.STRING) {
                val joined = parts ?: ArrayList<Expr>().also { it.add(left) }
                joined.add(right)
                parts = joined
                left = Join(joined.toList())
            } else {
                left = Binary(symbol, left, right, Cs2Type.INT)
                parts = null
            }
        }
        return left
    }

    private fun parseUnary(): Expr {
        if (atPunct("-")) {
            skip()
            return when (val value = parsePrimary()) {
                is IntConst -> IntConst(-value.value)
                is LongConst -> LongConst(-value.value)
                else -> Binary("-", IntConst(0), value, Cs2Type.INT)
            }
        }
        return parsePrimary()
    }

    private fun parsePrimary(): Expr {
        val token = peek()
        if (atPunct("(")) {
            skip()
            val inner = parseExpression()
            expectPunct(")")
            return inner
        }
        return when (token.kind) {
            TokenKind.NUMBER -> IntConst(skip().text.let(::parseIntLiteral))
            TokenKind.BIGINT -> LongConst(skip().text.toLong())
            TokenKind.STRING -> StrConst(Cs2Strings.unescape(skip().text))
            TokenKind.IDENT -> parseIdentifier()
            else -> error("Unexpected token $token")
        }
    }

    private fun parseIntLiteral(text: String): Int =
        if (text.startsWith("0x") || text.startsWith("0X")) text.substring(2).toLong(16).toInt()
        else text.toLong().toInt()

    private fun parseSignedInt(): Int {
        val negative = atPunct("-").also { if (it) skip() }
        val value = parseIntLiteral(expect(TokenKind.NUMBER).text)
        return if (negative) -value else value
    }

    private fun parseIdentifier(): Expr {
        val name = skip().text

        if (name == "true") return TypedConst(1, ArgType.BOOLEAN)
        if (name == "false") return TypedConst(0, ArgType.BOOLEAN)
        // `type_obj` and friends are the characters a compiler wrote for a type.
        Cs2TypeChars.charNamed(name)?.let { return TypedConst(it.code, ArgType.TYPECHAR) }
        // `event_com` and friends are the values the client substitutes on dispatch.
        Cs2Events.valueOf(name)?.let { return IntConst(it) }

        if (atPunct(".") && name in Cs2Names.NAMESPACES.values) return gamevalConstant(name)

        locals[name]?.let { return LocalRef(it.slot, it.type) }
        tempTypes[name]?.let { return it }

        (symbols.variable(name) ?: Cs2Symbols.variableOf(name))?.let { return it }

        if (name.startsWith("array") && name.drop(5).toIntOrNull() != null) {
            expectPunct("[")
            val index = parseExpression()
            expectPunct("]")
            return ArrayRef(name.drop(5).toInt(), index)
        }

        // A bare script reference: the id of the script, not a call to it.
        val referenced = Cs2Symbols.scriptOf(name) ?: symbols.script(name)
        if (referenced != null && !atPunct("(")) {
            return TypedConst(referenced, ArgType.SCRIPT)
        }
        // `name<1>(...)` carries the instruction's own operand.
        var operand = 0
        if (atPunct("<")) {
            skip()
            operand = parseSignedInt()
            expectPunct(">")
        }
        if (atPunct("(")) return parseCall(name, operand)
        error("Unknown identifier '$name'")
    }

    /**
     * `Obj.abyssal_whip`, `Component.<interface>.<component>` - a gameval name
     * standing for the number the bytecode holds.
     *
     * The name is looked up in exactly two places, in this order: the ids the
     * project's `gamevals.d.ts` binds its names to, which is where an editor
     * rename lands, and the catalogs those defaults came from. Nothing else may
     * name an id, and where the two disagree the folder is refused before a
     * compile starts (see [Cs2SymbolTable.conflicts]).
     */
    private fun gamevalConstant(namespace: String): Expr {
        val path = StringBuilder(namespace)
        expectPunct(".")
        val first = expect(TokenKind.IDENT).text
        path.append('.').append(first)
        var second: String? = null
        if (namespace == COMPONENT_NAMESPACE && atPunct(".")) {
            skip()
            second = expect(TokenKind.IDENT).text
            path.append('.').append(second)
        }
        val text = path.toString()
        val kind = Cs2Names.NAMESPACE_KINDS[namespace] ?: ArgType.INT
        symbols.gameval(text)?.let { return TypedConst(it, kind) }
        val id = when {
            namespace != COMPONENT_NAMESPACE -> {
                val type = Cs2Names.NAMESPACES.entries.first { it.value == namespace }.key
                Cs2Names.idOf(type, first)
            }
            second == null -> error("'$text' is an interface group, not a component")
            second == Cs2Identifiers.IFACE_MEMBER -> Cs2Names.componentInterfaceId(first)
            else -> Cs2Names.componentOf(first, second)
        }
        return TypedConst(id ?: error("Unknown gameval '$text'"), kind)
    }

    private val COMPONENT_NAMESPACE = Cs2Names.NAMESPACES.getValue(Gameval.COMPONENT)

    private fun parseCall(name: String, operand: Int = 0): Expr {
        expectPunct("(")
        val args = ArrayList<Expr>()
        val lists = ArrayList<List<Expr>>()
        while (!atPunct(")")) {
            if (atPunct("[")) {
                skip()
                val items = ArrayList<Expr>()
                while (!atPunct("]")) {
                    items.add(parseExpression())
                    if (atPunct(",")) skip()
                }
                skip()
                lists.add(items)
                args.add(IntConst(lists.size - 1))
            } else if (atPunct("...")) {
                skip()
                args.add(Spread(parseExpression()))
            } else {
                args.add(parseExpression())
            }
            if (atPunct(",")) skip()
        }
        skip()

        if (name == "hook") {
            val scriptRef = args.first()
            val callee = (scriptRef as? ScriptCall)?.scriptId
                ?: (scriptRef as? TypedConst)?.value
                ?: (scriptRef as? IntConst)?.value
                ?: error("hook() needs a script reference")
            val format = (args.getOrNull(1) as? StrConst)?.value
                ?: error("hook() needs its type spec")
            return Callback(callee, format, lists.getOrNull(0).orEmpty(), lists.getOrNull(1).orEmpty())
        }
        // Where a helper shares a name with an opcode, the number of arguments
        // says which is meant: `coord()` is the opcode that reads the player's
        // position, `coord(x, y, level)` is a coordinate written out.
        val named = Cs2Op.byTsName(name)
        val arity = named?.let { it.popInt + it.popStr + it.popLong }
        if (arity != args.size) packedConstant(name, args)?.let { return it }
        if (name == "discard") return args.first()
        if (name == "noHook") {
            return Callback(-1, (args.firstOrNull() as? StrConst)?.value ?: "", emptyList(), emptyList())
        }
        if (name == "defineArray") {
            return OpCall(Cs2Op.DEFINE_ARRAY, args, Cs2Type.INT)
        }
        if (name == "isTruthy") {
            return OpCall(Cs2Op.BRANCH_IF_TRUE, args, Cs2Type.INT)
        }
        if (name == "isFalsy") {
            return OpCall(Cs2Op.BRANCH_IF_FALSE, args, Cs2Type.INT)
        }
        val callee = Cs2Symbols.scriptOf(name) ?: symbols.script(name)
        if (callee != null) return ScriptCall(callee, args, Cs2Type.INT)
        val op = Cs2Op.byTsName(name) ?: error("Unknown opcode function '$name'")
        return OpCall(op, args, opResultType(op, args), operand)
    }

    // ---------------------------------------------------------------- tokens

    private fun peek() = tokens[at]

    private fun skip() = tokens[at++]

    private fun atPunct(text: String) = peek().kind == TokenKind.PUNCT && peek().text == text

    private fun expectPunct(text: String) {
        if (!atPunct(text)) error("Expected '$text' but found ${peek()}")
        at++
    }

    private fun expectIdent(text: String) {
        if (peek().kind != TokenKind.IDENT || peek().text != text) error("Expected '$text' but found ${peek()}")
        at++
    }

    private fun expect(kind: TokenKind): Token {
        if (peek().kind != kind) error("Expected $kind but found ${peek()}")
        return tokens[at++]
    }

    /** What a script's declaration says about it, without parsing a line of its body. */
    class Signature(
        /** The function's name, which is what a caller writes. */
        val name: String,
        /** The arguments in the order callers push them, which can interleave the stacks. */
        val arguments: List<Cs2Type>,
        /** The values the script returns, in push order; empty for `void`. */
        val returns: List<Cs2Type>,
    )

    companion object {

        /**
         * Reads the `function name(a: number): [number, string]` line and stops there.
         *
         * Compiling one file needs every *other* file's signature - a call's results have to be
         * discarded on the right stacks, and `let [a, b] = f();` types its names from the callee -
         * so a folder that is about to be compiled has to be able to answer that for all 6,567
         * scripts without parsing 6,567 bodies. Parsing a body is also circular: it is the thing
         * that needs the answer.
         *
         * Only the header is lexed. The body opens at the first `{`, because nothing in a
         * signature can contain one, and the whole file is lexed as a fallback for a hand-written
         * header that somehow does; either way it is this parser's own grammar walking it, not a
         * second reading of the same shape.
         *
         * @return null when the file declares no function, or its header does not parse.
         */
        fun signature(source: String): Signature? {
            val declaration = DECLARATION.find(source) ?: return null
            val body = source.indexOf('{', declaration.range.first)
            val header = if (body < 0) source else source.substring(0, body + 1)
            return read(header) ?: if (body < 0) null else read(source)
        }

        /**
         * The Cs2 type a TypeScript type name stands for.
         *
         * One spelling for the parser and for anything reading a signature back: an alias
         * ([Cs2Names.ALIASES]) says what an int *means* and never which stack it is on, so every
         * one of them is an [Cs2Type.INT].
         */
        fun typeNamed(text: String): Cs2Type = when {
            text == "number" -> Cs2Type.INT
            text == "string" -> Cs2Type.STRING
            text == "bigint" -> Cs2Type.LONG
            // An alias says what an int means, never which stack it is on.
            Cs2Names.isAlias(text) -> Cs2Type.INT
            else -> error("Unsupported type '$text'")
        }

        /** Lexes [header] and walks the declaration in it, or null when it will not walk. */
        private fun read(header: String): Signature? = try {
            Cs2Parser(header).headerOnly()
        } catch (e: Exception) {
            null
        }

        /** `function <name>(` at the start of a line - the shape the emitter always writes. */
        private val DECLARATION = Regex("""^function \w+\s*\(""", RegexOption.MULTILINE)
    }

    /**
     * [parseHeader] alone, for [Companion.signature]. Never called on a parser that is going
     * to parse a body, so the locals it declares along the way are thrown away with it.
     */
    private fun headerOnly(): Signature =
        Signature(name = parseHeader(), arguments = argumentTypes.toList(), returns = returns)
}
