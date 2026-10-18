package org.drynor.build.gameval

/**
 * Turning a Jagex dev-name into an identifier that is legal in both Java and Kotlin.
 *
 * The rules are the ones `docs/gameval.md` documents and the committed constants were produced
 * under: keep the name as-is wherever it already is an identifier, and record every deviation so
 * the generated file's header can list it.
 */
private val JAVA_RESERVED = """
    abstract assert boolean break byte case catch char class const continue default do double else
    enum extends final finally float for goto if implements import instanceof int interface long
    native new package private protected public return short static strictfp super switch
    synchronized this throw throws transient try void volatile while true false null _
""".trimIndent().split(Regex("\\s+")).filter { it.isNotEmpty() }.toSet()

private val KOTLIN_HARD_KEYWORDS = """
    as break class continue do else false for fun if in interface is null object package return
    super this throw true try typealias typeof val var when while
""".trimIndent().split(Regex("\\s+")).filter { it.isNotEmpty() }.toSet()

/** Words that cannot be an identifier in Java or in Kotlin. */
val RESERVED_WORDS: Set<String> = JAVA_RESERVED + KOTLIN_HARD_KEYWORDS

/** The one member of a `Component.<interface>` class that is not a component. */
const val IFACE_MEMBER = "IFACE"

private val ILLEGAL = Regex("[^A-Za-z0-9_]")

/** An identifier and the reasons it differs from the dev-name it came from. */
class Ident(val value: String, val reasons: List<String>)

/**
 * The identifier for [raw], with the reason for each rewrite.
 *
 * A character outside `[A-Za-z0-9_]` becomes `_`; a leading digit (or an empty result) gets a
 * leading `_`; a reserved word and `IFACE` get a trailing `_`; a name that is only underscores
 * becomes `_<id>`, since Kotlin reserves those.
 */
fun identifierFor(raw: String, id: String): Ident {
    val reasons = ArrayList<String>(2)
    var ident = ILLEGAL.replace(raw, "_")
    if (ident != raw) reasons.add("illegal character(s) replaced by _")
    if (ident.isEmpty() || ident[0] in '0'..'9') {
        ident = "_$ident"
        reasons.add("leading digit")
    }
    if (ident in RESERVED_WORDS) {
        ident += "_"
        reasons.add("reserved word")
    }
    if (ident == IFACE_MEMBER) {
        ident += "_"
        reasons.add("clashes with $IFACE_MEMBER")
    }
    if (ident.all { it == '_' }) {
        ident = "_" + id.replace(":", "_")
        reasons.add("underscores only")
    }
    return Ident(ident, reasons)
}

/** One identifier rewrite, for the generated header and the task's report. */
class Rewrite(val key: String, val raw: String, val ident: String, val why: String) {
    override fun toString(): String = "$key \"$raw\" -> $ident ($why)"
}
