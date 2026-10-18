package org.drynor.build.gameval

/**
 * Emits the typed gameval constants: one Java class of `public static final int` per catalog.
 *
 * Java, not Kotlin, because a `public static final int` carries a ConstantValue attribute that
 * both javac and the Kotlin compiler inline, and because Java inherits statics through a subclass:
 * a type with more than [shardSize] ids continues into package-private superclasses
 * (`Loc extends Loc2 extends Loc3`), so `Loc.<name>` resolves for every name from Kotlin and from
 * Java alike. A JVM class holds at most 65535 constant-pool entries and each constant costs two,
 * hence the shard size. `docs/gameval.md` has the measurements.
 *
 * Output is deterministic: entries are emitted in ascending id order and nothing timestamped is
 * written, so the same catalogs always produce the same bytes.
 *
 * [includePlaceholders] `false` reproduces, byte for byte, the constants the retired
 * `gamevals/gen_kotlin.py` produced (named ids only); `true` is what the build ships - every id in
 * every catalog gets a constant, an unnamed one under its placeholder identifier.
 */
class GamevalCodegen(
    private val generatorLabel: String,
    private val regenerateCommand: String,
    private val includePlaceholders: Boolean,
    private val packageName: String = "org.drynor.core.gameval.gen",
    private val shardSize: Int = 32000,
) {

    /** One emitted constant. */
    private class Row(val key: String, val id: Int, val ident: String, val raw: String)

    /** What one catalog produced. */
    class TypeResult(
        val type: String,
        val className: String,
        val files: Map<String, String>,
        val constants: Int,
        val rewrites: List<Rewrite>,
    )

    private val clientscriptName = Regex("\\[([^,\\]]*),([^\\]]*)\\]")

    // ---- driver ------------------------------------------------------------------------------

    /** Validates [catalog] and returns its generated files, keyed by file name. */
    fun generate(catalog: Catalog): TypeResult {
        validateCatalog(catalog)
        val cls = GAMEVAL_CLASSES.getValue(catalog.type)
        val selected = select(catalog)
        return when (catalog.type) {
            COMPONENT -> component(catalog, cls, selected)
            CLIENTSCRIPT -> clientscript(catalog, cls, selected)
            else -> simple(catalog, cls, selected)
        }
    }

    /** An entry with its sort key: the id, or `(iface, comp)` for a component. */
    private class Selected(val key: String, val name: String, val first: Long, val second: Long)

    /** The entries to emit, ascending by id (by `(iface, comp)` for components). */
    private fun select(catalog: Catalog): List<Selected> {
        val out = ArrayList<Selected>(catalog.entries.size)
        for ((key, name) in catalog.entries) {
            if (!includePlaceholders && name == placeholderName(catalog.type, key)) continue
            val colon = key.indexOf(':')
            val first = if (colon < 0) key else key.substring(0, colon)
            val second = if (colon < 0) null else key.substring(colon + 1)
            out.add(Selected(key, name, sortKey(first), second?.let { sortKey(it) } ?: -1L))
        }
        out.sortWith(compareBy({ it.first }, { it.second }))
        return out
    }

    /** Ids sort numerically; anything else sorts after them, in catalog order. */
    private fun sortKey(part: String): Long = part.toLongOrNull() ?: (1L shl 40)

    // ---- identifier assignment ---------------------------------------------------------------

    /**
     * Identifiers for [items] (key, id, name-to-turn-into-an-identifier), with the rewrites.
     * A collision that survives the rewrite rules is a build failure, not something to paper over:
     * two ids would otherwise silently share one constant or one would be renamed behind the
     * caller's back.
     */
    private fun assign(type: String, scope: String, items: List<Triple<String, Int, String>>): Pair<List<Row>, List<Rewrite>> {
        val rows = ArrayList<Row>(items.size)
        val rewrites = ArrayList<Rewrite>()
        val taken = HashMap<String, String>(items.size * 2)
        for ((key, id, raw) in items) {
            val ident = identifierFor(raw, id.toString())
            val previous = taken.put(ident.value, key)
            if (previous != null) {
                throw GamevalGenerationException(
                    "$type: $scope$previous and $scope$key both become the identifier '${ident.value}'" +
                        " (\"${itemName(items, previous)}\" and \"$raw\"). Rename one of them in gamevals/$type.json.",
                )
            }
            if (ident.reasons.isNotEmpty()) {
                rewrites.add(Rewrite("$type $key", raw, ident.value, ident.reasons.joinToString(", ")))
            }
            rows.add(Row(key, id, ident.value, raw))
        }
        return rows to rewrites
    }

    private fun itemName(items: List<Triple<String, Int, String>>, key: String): String =
        items.firstOrNull { it.first == key }?.third ?: "?"

    // ---- per-flavour emitters ----------------------------------------------------------------

    private fun simple(catalog: Catalog, cls: String, selected: List<Selected>): TypeResult {
        val items = selected.map { Triple(it.key, it.key.toInt(), it.name) }
        val (rows, rewrites) = assign(catalog.type, "", items)
        return TypeResult(catalog.type, cls, sharded(catalog, cls, rows, rewrites), rows.size, rewrites)
    }

    /** `[proc,quicksort]` becomes `quicksort`, or `proc_quicksort` when the bare name is in more than one category. */
    private fun clientscript(catalog: Catalog, cls: String, selected: List<Selected>): TypeResult {
        val parsed = selected.map {
            val m = clientscriptName.matchEntire(it.name)
            val category = m?.groupValues?.get(1) ?: ""
            val bare = m?.groupValues?.get(2) ?: it.name
            Quad(it.key, category, bare, it.name)
        }
        val categoriesOf = HashMap<String, MutableSet<String>>()
        for (p in parsed) categoriesOf.getOrPut(p.bare) { LinkedHashSet() }.add(p.category)
        val items = parsed.map { p ->
            val ident = if (categoriesOf.getValue(p.bare).size == 1 || p.category.isEmpty()) p.bare else "${p.category}_${p.bare}"
            Triple(p.key, p.key.toInt(), ident)
        }
        val (identRows, rewrites) = assign(catalog.type, "", items)
        // Keep Jagex's full [category,name] in the trailing comment so the raw form stays greppable.
        val rawByKey = parsed.associate { it.key to it.raw }
        val rows = identRows.map { Row(it.key, it.id, it.ident, rawByKey.getValue(it.key)) }
        return TypeResult(catalog.type, cls, sharded(catalog, cls, rows, rewrites), rows.size, rewrites)
    }

    private class Quad(val key: String, val category: String, val bare: String, val raw: String)

    /**
     * `Component.<interface>.<component>` is the packed `(iface << 16) | comp` the interface
     * packets take and `Component.<interface>.IFACE` is the interface id. An interface with no
     * proven name is `interface_<id>`, the same placeholder `Interface` uses for it.
     */
    private fun component(catalog: Catalog, cls: String, selected: List<Selected>): TypeResult {
        val byIface = LinkedHashMap<Int, MutableList<Triple<Int, String, String?>>>() // iface -> (comp, comp name, iface name)
        for ((key, name) in selected.map { it.key to it.name }) {
            val ik = key.substringBefore(':')
            val ck = key.substringAfter(':')
            val ifaceId = ik.toIntOrNull() ?: continue
            val compId = ck.toIntOrNull() ?: continue
            val ifaceName = if (name.contains(':')) name.substringBefore(':') else null
            val compName = if (name.contains(':')) name.substringAfter(':') else name
            byIface.getOrPut(ifaceId) { ArrayList() }.add(Triple(compId, compName, ifaceName))
        }
        val ifaceItems = byIface.keys.sorted().map { ifaceId ->
            val named = byIface.getValue(ifaceId).firstNotNullOfOrNull { it.third }
            Triple("$ifaceId:*", ifaceId, named ?: placeholderName("interface", ifaceId.toString()))
        }
        val (ifaceRows, ifaceRewrites) = assign(catalog.type, "", ifaceItems)
        val rewrites = ArrayList<Rewrite>(ifaceRewrites)

        val body = ArrayList<String>()
        var count = 0
        for (row in ifaceRows) {
            val ifaceId = row.id
            val comps = byIface.getValue(ifaceId).sortedBy { it.first }
            val compItems = comps.map { (compId, compName, _) -> Triple("$ifaceId:$compId", compId, compName) }
            val (compRows, compRewrites) = assign(catalog.type, "$ifaceId:", compItems)
            rewrites.addAll(compRewrites)
            body.add("")
            val ifaceDoc = if (row.raw == placeholderName("interface", ifaceId.toString())) "no proven name" else row.raw
            body.add("    /** Interface $ifaceId $ifaceDoc. */")
            body.add("    public static final class ${row.ident} {")
            body.add("        private ${row.ident}() {}")
            body.add("        public static final int $IFACE_MEMBER = $ifaceId;")
            for (comp in compRows) {
                val packed = (ifaceId shl 16) or (comp.id and 0xFFFF)
                val describedName = if (comp.raw == placeholderName(catalog.type, comp.key)) "" else " ${row.raw}:${comp.raw}"
                body.add("        public static final int ${comp.ident} = $packed; // ${comp.key}$describedName")
                count++
            }
            body.add("    }")
        }

        val lines = ArrayList<String>()
        lines.addAll(header(catalog, emptyList(), rewrites))
        lines.add("")
        lines.add("package $packageName;")
        lines.add("")
        lines.add("/**")
        lines.add(" * Revision-727 interface components by Jagex dev-name: {@code Component.<interface>.<component>} is the")
        lines.add(" * packed id {@code (iface << 16) | comp} the interface packets use, and {@code Component.<interface>.IFACE}")
        lines.add(" * is the interface id. Generated from gamevals/component.json; regenerate with")
        lines.add(" * {@code $regenerateCommand}.")
        lines.add(" */")
        lines.add("public final class $cls {")
        lines.add("    private $cls() {}")
        lines.addAll(body)
        lines.add("}")
        return TypeResult(catalog.type, cls, mapOf("$cls.java" to lines.joinToString("\n") + "\n"), count, rewrites)
    }

    // ---- file bodies -------------------------------------------------------------------------

    /** How many ids of the catalog carry a proven dev-name rather than a placeholder. */
    private fun namedCount(catalog: Catalog): Int =
        catalog.entries.count { (key, name) -> name != placeholderName(catalog.type, key) }

    private fun header(catalog: Catalog, extra: List<String>, rewrites: List<Rewrite>): List<String> {
        val total = catalog.entries.size
        val named = namedCount(catalog)
        val lines = ArrayList<String>()
        lines.add("// GENERATED by $generatorLabel from gamevals/${catalog.type}.json (revision ${catalog.revision}). Do not edit by hand.")
        lines.add(
            if (includePlaceholders) {
                "// $total ids: $named named, ${total - named} placeholder; every id has a constant. See docs/gameval.md, \"Typed constants\"."
            } else {
                "// $named named ids of $total; placeholders are skipped. See docs/gameval.md, \"Typed constants\"."
            },
        )
        for (e in extra) lines.add("// $e")
        if (rewrites.isNotEmpty()) {
            lines.add("// Identifiers that differ from the dev-name:")
            for (r in rewrites) lines.add("//   $r")
        }
        return lines
    }

    private fun classFile(
        catalog: Catalog,
        cls: String,
        superclass: String?,
        public: Boolean,
        rows: List<Row>,
        extra: List<String>,
        rewrites: List<Rewrite>,
    ): String {
        val lines = ArrayList<String>(rows.size + 16)
        lines.addAll(header(catalog, extra, rewrites))
        lines.add("")
        lines.add("package $packageName;")
        lines.add("")
        if (public) {
            lines.add("/**")
            if (includePlaceholders) {
                lines.add(" * Revision-727 ${catalog.type} ids by name: {@code $cls.<name>} from Kotlin or Java. Every id in the")
                lines.add(" * catalog has a constant; an id with no proven Jagex dev-name carries its placeholder, so renaming")
                lines.add(" * it in gamevals/${catalog.type}.json renames the constant and stale call sites stop compiling.")
                lines.add(" * Regenerate with {@code $regenerateCommand}.")
            } else {
                lines.add(" * Revision-727 ${catalog.type} ids by Jagex dev-name: {@code $cls.<name>} from Kotlin or Java.")
                lines.add(" * Generated from gamevals/${catalog.type}.json; regenerate with {@code $regenerateCommand}.")
            }
            lines.add(" */")
        } else {
            lines.add("/** Continuation of {@link ${cls.trimEnd('0', '1', '2', '3', '4', '5', '6', '7', '8', '9')}}; reference the constants through that class. */")
        }
        val ext = if (superclass != null) " extends $superclass" else ""
        lines.add("${if (public) "public " else ""}${if (public) "final " else ""}class $cls$ext {")
        lines.add("    ${if (public) "private" else "protected"} $cls() {}")
        lines.add("")
        for (row in rows) {
            val comment = if (row.ident != row.raw || row.key.contains(':')) " // ${row.key} ${row.raw}" else ""
            lines.add("    public static final int ${row.ident} = ${row.id};$comment")
        }
        lines.add("}")
        return lines.joinToString("\n") + "\n"
    }

    /** The public class plus, above [shardSize] constants, the package-private superclass chain. */
    private fun sharded(catalog: Catalog, cls: String, rows: List<Row>, rewrites: List<Rewrite>): Map<String, String> {
        val chunks = if (rows.isEmpty()) listOf(emptyList()) else rows.chunked(shardSize)
        val names = listOf(cls) + (1 until chunks.size).map { "$cls${it + 1}" }
        val files = LinkedHashMap<String, String>()
        for ((i, chunk) in chunks.withIndex()) {
            val superclass = names.getOrNull(i + 1)
            val extra = ArrayList<String>()
            if (chunks.size > 1) {
                val span = if (chunk.isEmpty()) "empty" else "ids ${chunk.first().id}..${chunk.last().id}"
                extra.add("Part ${i + 1} of ${chunks.size} ($span); the class chain ${names.joinToString(" -> ")} keeps every constant reachable as $cls.<name>.")
            }
            val partRewrites = if (chunks.size > 1) {
                val keys = chunk.mapTo(HashSet()) { "${catalog.type} ${it.key}" }
                rewrites.filter { it.key in keys }
            } else {
                rewrites
            }
            files["${names[i]}.java"] = classFile(catalog, names[i], superclass, i == 0, chunk, extra, partRewrites)
        }
        return files
    }
}
