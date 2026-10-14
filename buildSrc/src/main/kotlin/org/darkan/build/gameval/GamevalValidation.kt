package org.darkan.build.gameval

/**
 * The checks that stop the build before a bad catalog becomes a bad constant.
 *
 * Every id is a symbol now, placeholders included, so a name that is merely odd is no longer
 * harmless: two ids sharing one identifier would silently collapse into one constant, and a real
 * name spelled like another id's placeholder would make `Loc.loc_9` mean id 5. Each of these is a
 * hard failure naming the type, the ids and the strings, because the fix is always a one-line edit
 * in `gamevals/<type>.json`.
 */
private const val MAX_REPORTED = 20

private val SIMPLE_KEY = Regex("\\d+")
private val COMPONENT_KEY = Regex("(\\d+):(\\d+)")
private val CLIENTSCRIPT_NAME = Regex("\\[([^,\\]]*),([^\\]]*)\\]")

private class Problems {
    private val byRule = LinkedHashMap<String, MutableList<String>>()

    fun add(rule: String, detail: String) {
        byRule.getOrPut(rule) { ArrayList() }.add(detail)
    }

    fun isEmpty(): Boolean = byRule.isEmpty()

    fun message(type: String, source: String): String {
        val sb = StringBuilder("gamevals/$type.json cannot be turned into constants ($source):")
        for ((rule, details) in byRule) {
            sb.append("\n  ").append(rule).append(" (").append(details.size).append("):")
            for (detail in details.take(MAX_REPORTED)) sb.append("\n    ").append(detail)
            if (details.size > MAX_REPORTED) sb.append("\n    ... and ").append(details.size - MAX_REPORTED).append(" more")
        }
        sb.append("\n  Fix the catalog entry (or entries) named above; the constants are generated from it verbatim.")
        return sb.toString()
    }
}

/** Throws [GamevalGenerationException] listing everything wrong with [catalog]. */
fun validateCatalog(catalog: Catalog) {
    val type = catalog.type
    val problems = Problems()
    val nameOwner = HashMap<String, String>()
    val identOwner = HashMap<String, String>()
    val ifaceNames = HashMap<Int, MutableMap<String, String>>() // iface -> (iface-name -> first key using it)
    val ifaceIdents = HashMap<String, Int>()
    val prefix = if (type == CLIENTSCRIPT) "cs2_" else "${type}_"
    val placeholderTail = Regex("\\d+(_\\d+)?")

    // Clientscript identifiers depend on which bare names appear in more than one category.
    val categoriesOf = HashMap<String, MutableSet<String>>()
    if (type == CLIENTSCRIPT) {
        for (name in catalog.entries.values) {
            val m = CLIENTSCRIPT_NAME.matchEntire(name)
            categoriesOf.getOrPut(m?.groupValues?.get(2) ?: name) { LinkedHashSet() }.add(m?.groupValues?.get(1) ?: "")
        }
    }

    for ((key, name) in catalog.entries) {
        // -- the name is usable at all
        if (name.isBlank() || name == "null") {
            problems.add("empty or 'null' name", "$key -> \"$name\"")
            continue
        }
        if (name != name.trim()) {
            problems.add("name has leading or trailing whitespace", "$key -> \"$name\"")
            continue
        }

        // -- the key addresses an id of this type
        val componentKey = if (type == COMPONENT) COMPONENT_KEY.matchEntire(key) else null
        if (type == COMPONENT) {
            if (componentKey == null) {
                problems.add("malformed component key", "\"$key\" is not \"<interfaceId>:<componentId>\" (name \"$name\")")
                continue
            }
        } else if (!SIMPLE_KEY.matches(key) || key.toIntOrNull() == null) {
            problems.add("malformed id", "\"$key\" is not a non-negative 32-bit integer (name \"$name\")")
            continue
        }
        if (componentKey != null && (componentKey.groupValues[1].toIntOrNull() == null || componentKey.groupValues[2].toIntOrNull() == null)) {
            problems.add("malformed component key", "\"$key\" does not fit two 32-bit integers (name \"$name\")")
            continue
        }

        val placeholder = placeholderName(type, key)
        val isPlaceholder = name == placeholder

        // -- a real name must not impersonate another id's placeholder
        if (!isPlaceholder && name.startsWith(prefix) && placeholderTail.matches(name.removePrefix(prefix))) {
            problems.add(
                "name is another id's placeholder",
                "$key is named \"$name\", which is the placeholder form of a different id; it would make $name mean $key",
            )
            continue
        }

        // -- names are unique within a type
        val previousOwner = nameOwner.put(name, key)
        if (previousOwner != null) {
            problems.add("duplicate name", "$previousOwner and $key are both named \"$name\"")
            continue
        }

        // -- the component halves have to be separable
        var identSource = name
        var scope = ""
        if (type == COMPONENT) {
            val ifaceId = componentKey!!.groupValues[1].toInt()
            if (isPlaceholder) {
                identSource = name
            } else {
                val halves = name.split(":")
                if (halves.size != 2 || halves[0].isEmpty() || halves[1].isEmpty()) {
                    problems.add(
                        "malformed component name",
                        "$key -> \"$name\" is neither \"<interfaceName>:<componentName>\" nor the placeholder \"$placeholder\"",
                    )
                    continue
                }
                identSource = halves[1]
                val known = ifaceNames.getOrPut(ifaceId) { LinkedHashMap() }
                val first = known.keys.firstOrNull()
                if (first == null) {
                    known[halves[0]] = key
                } else if (first != halves[0] && known.size == 1) {
                    known[halves[0]] = key
                    problems.add(
                        "interface named two ways",
                        "interface $ifaceId is \"$first\" in ${known.getValue(first)} but \"${halves[0]}\" in $key",
                    )
                    continue
                } else if (first != halves[0]) {
                    continue
                }
            }
            scope = "$ifaceId:"
        } else if (type == CLIENTSCRIPT) {
            val m = CLIENTSCRIPT_NAME.matchEntire(name)
            val category = m?.groupValues?.get(1) ?: ""
            val bare = m?.groupValues?.get(2) ?: name
            identSource = if (categoriesOf.getValue(bare).size == 1 || category.isEmpty()) bare else "${category}_$bare"
        }

        // -- and the identifier it becomes must be free
        val id = if (type == COMPONENT) componentKey!!.groupValues[2] else key
        val ident = scope + identifierFor(identSource, id).value
        val previousIdent = identOwner.put(ident, key)
        if (previousIdent != null) {
            problems.add(
                "names collide after escaping",
                "$previousIdent (\"${catalog.entries[previousIdent]}\") and $key (\"$name\") both become '${ident.removePrefix(scope)}'",
            )
        }
    }

    // -- one nested class per interface, so the interface names must be identifiers too
    if (type == COMPONENT) {
        val ifaces = catalog.entries.keys.mapNotNull { COMPONENT_KEY.matchEntire(it)?.groupValues?.get(1)?.toInt() }.distinct().sorted()
        for (ifaceId in ifaces) {
            val name = ifaceNames[ifaceId]?.keys?.firstOrNull() ?: placeholderName("interface", ifaceId.toString())
            val ident = identifierFor(name, ifaceId.toString()).value
            val previous = ifaceIdents.put(ident, ifaceId)
            if (previous != null) {
                problems.add("interface classes collide", "interfaces $previous and $ifaceId both become Component.$ident")
            }
        }
    }

    validateCoverage(catalog, problems)

    if (!problems.isEmpty()) throw GamevalGenerationException(problems.message(type, catalog.source))
}

/** The `coverage` block is the catalog's own claim about its id space; it has to be true. */
private fun validateCoverage(catalog: Catalog, problems: Problems) {
    val entries = catalog.entries
    val named = entries.count { (key, name) -> name != placeholderName(catalog.type, key) }
    val cacheIds = catalog.coverage.cacheIds
    if (cacheIds != null && cacheIds != entries.size) {
        val detail = StringBuilder("coverage.cache_ids says $cacheIds but the catalog has ${entries.size} entries")
        if (catalog.type != COMPONENT && entries.size < cacheIds) {
            val ids = entries.keys.mapNotNull { it.toIntOrNull() }.sorted()
            if (ids.isNotEmpty() && ids.last() - ids.first() + 1 == cacheIds) {
                val present = ids.toHashSet()
                val missing = (ids.first()..ids.last()).filter { it !in present }
                detail.append("; ids missing from the contiguous space ${ids.first()}..${ids.last()}: ")
                    .append(missing.take(MAX_REPORTED).joinToString(", "))
                if (missing.size > MAX_REPORTED) detail.append(", ... and ${missing.size - MAX_REPORTED} more")
            }
        }
        problems.add("coverage disagrees with the entries", detail.toString())
    }
    val claimedNamed = catalog.coverage.named
    if (claimedNamed != null && claimedNamed != named) {
        problems.add("coverage disagrees with the entries", "coverage.named says $claimedNamed but $named entries carry a name")
    }
    val claimedPlaceholder = catalog.coverage.placeholder
    if (claimedPlaceholder != null && claimedPlaceholder != entries.size - named) {
        problems.add("coverage disagrees with the entries", "coverage.placeholder says $claimedPlaceholder but ${entries.size - named} entries carry a placeholder")
    }
}

/**
 * Cross-catalog check: `Component.<interface>` is named from component.json and `Interface.<name>`
 * from interface.json, so the two files must agree on what an interface is called or the same id
 * would carry two different symbols.
 */
fun validateInterfaceNamesAgree(component: Catalog, interfaces: Catalog) {
    val problems = ArrayList<String>()
    val seen = HashSet<Int>()
    for ((key, name) in component.entries) {
        val m = COMPONENT_KEY.matchEntire(key) ?: continue
        if (!name.contains(':')) continue
        val ifaceId = m.groupValues[1].toInt()
        if (!seen.add(ifaceId)) continue
        val fromComponent = name.substringBefore(':')
        val fromInterface = interfaces.entries[ifaceId.toString()] ?: continue
        if (fromInterface == placeholderName("interface", ifaceId.toString())) continue
        if (fromInterface != fromComponent) {
            problems.add("interface $ifaceId is \"$fromInterface\" in interface.json but \"$fromComponent\" in component.json (entry $key)")
        }
    }
    if (problems.isNotEmpty()) {
        throw GamevalGenerationException(
            "gamevals/component.json and gamevals/interface.json disagree on interface names (${problems.size}):\n    " +
                problems.take(MAX_REPORTED).joinToString("\n    ") +
                (if (problems.size > MAX_REPORTED) "\n    ... and ${problems.size - MAX_REPORTED} more" else "") +
                "\n  Component.<interface> and Interface.<name> are generated from these two files; rename one so they agree.",
        )
    }
}
