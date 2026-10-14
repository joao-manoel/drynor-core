package org.darkan.cs2

import org.darkan.core.gameval.Gameval
import org.darkan.core.gameval.GamevalCatalog
import org.darkan.cs2.ir.Cs2Type
import org.darkan.cs2.ir.VarRef
import org.darkan.cs2.ir.VarSpace

/**
 * Turns a Jagex dev-name into an identifier, by the same rules the generated Java
 * gameval constants use.
 *
 * Kept deliberately identical to `buildSrc/.../gameval/Identifiers.kt` so
 * `Obj.abyssal_whip` means the same thing in a decompiled clientscript as it does
 * in server Kotlin, with one addition: TypeScript reserves words Java and Kotlin
 * do not, and a name that landed on one of those would not parse.
 *
 * No name is stored here. Everything is derived from `gamevals/<type>.json` at
 * runtime through [Gameval]; the catalogs are the only place a name lives.
 */
object Cs2Identifiers {

    private val JAVA_RESERVED = """
        abstract assert boolean break byte case catch char class const continue default do double else
        enum extends final finally float for goto if implements import instanceof int interface long
        native new package private protected public return short static strictfp super switch
        synchronized this throw throws transient try void volatile while true false null _
    """.trimIndent().split(Regex("\\s+")).filter { it.isNotEmpty() }.toSet()

    private val KOTLIN_RESERVED = """
        as break class continue do else false for fun if in interface is null object package return
        super this throw true try typealias typeof val var when while
    """.trimIndent().split(Regex("\\s+")).filter { it.isNotEmpty() }.toSet()

    /** Words Java and Kotlin allow but TypeScript does not, plus globals a name could shadow. */
    private val TYPESCRIPT_RESERVED = """
        let function with yield await export debugger delete arguments eval undefined NaN Infinity
        globalThis Function Object Number String Boolean Array Symbol BigInt declare namespace module
        require async constructor
    """.trimIndent().split(Regex("\\s+")).filter { it.isNotEmpty() }.toSet()

    val RESERVED_WORDS: Set<String> = JAVA_RESERVED + KOTLIN_RESERVED + TYPESCRIPT_RESERVED

    /** The one member of a `Component.<interface>` namespace that is not a component. */
    const val IFACE_MEMBER = "IFACE"

    private val ILLEGAL = Regex("[^A-Za-z0-9_]")

    /**
     * The identifier for [raw]. A character outside `[A-Za-z0-9_]` becomes `_`; a
     * leading digit (or an empty result) gets a leading `_`; a reserved word and
     * `IFACE` get a trailing `_`; a name that is only underscores becomes `_<key>`.
     */
    fun escape(raw: String, key: String): String {
        var ident = ILLEGAL.replace(raw, "_")
        if (ident.isEmpty() || ident[0] in '0'..'9') ident = "_$ident"
        if (ident in RESERVED_WORDS) ident += "_"
        if (ident == IFACE_MEMBER) ident += "_"
        if (ident.all { it == '_' }) ident = "_" + key.replace(':', '_')
        return ident
    }
}

/**
 * The names decompiled clientscripts use for everything they address by id.
 *
 * Every name comes from `gamevals/<type>.json` through [Gameval], read at runtime:
 * the catalogs are the single source of truth, and nothing here survives a
 * [reload]. What is held is only the *derivation* - the escaped identifier and its
 * reverse - rebuilt from the catalogs on demand.
 *
 * The identifiers mirror the generated Java constants ([Cs2Identifiers]):
 *
 *  - a simple type is `<Namespace>.<name>`, e.g. `Obj.abyssal_whip`;
 *  - a component is `Component.<interface>.<component>`, holding the packed
 *    `(iface shl 16) or comp` the bytecode carries, with `Component.<iface>.IFACE`
 *    for the interface id itself;
 *  - a clientscript's `[category,name]` becomes `name`, or `category_name` when the
 *    bare name is used in more than one category;
 *  - a variable is `<space>_<name>`, e.g. `varbit_pouch_slot0` - a global that is
 *    assigned to, so it cannot live in a namespace.
 *
 * An id with no proven name has no identifier and keeps its number in the emitted
 * source. Scripts are the exception: a function has to be called something, so an
 * unnamed one uses the catalog's own `cs2_<id>` placeholder.
 */
object Cs2Names {

    /** Catalog type -> the namespace decompiled source reads its ids through. */
    val NAMESPACES: Map<String, String> = linkedMapOf(
        Gameval.OBJ to "Obj",
        Gameval.NPC to "Npc",
        Gameval.LOC to "Loc",
        Gameval.SEQ to "Seq",
        Gameval.SPOTANIM to "Spotanim",
        Gameval.ENUM to "Enum",
        Gameval.STRUCT to "Struct",
        Gameval.PARAM to "Param",
        Gameval.INV to "Inv",
        Gameval.GRAPHIC to "Graphic",
        Gameval.INTERFACE to "Interface",
        Gameval.COMPONENT to "Component",
        Gameval.SOUND to "Sound",
        Gameval.MIDI to "Midi",
        Gameval.CURSOR to "Cursor",
        Gameval.MAPELEMENT to "Mapelement",
        Gameval.QUEST to "Quest",
        Gameval.WORLDMAP to "Worldmap",
        Gameval.MODEL to "Model",
    )

    /** The types that render as a flat `<Namespace>.<name>`; components nest one deeper. */
    val SIMPLE_TYPES: List<String> = NAMESPACES.keys.filter { it != Gameval.COMPONENT }

    /**
     * The kinds still written through a helper call.
     *
     * A gameval constant carries its own kind - `Obj.coins` is an obj because of
     * where the name comes from - so wrapping it would only repeat the namespace.
     * What is left is the values no catalog names: a packed colour, a packed
     * coordinate. Everything else is a plain number, which is what it is.
     */
    val WRAPPED: Map<ArgType, String> = linkedMapOf(
        ArgType.COLOUR to "colour",
        ArgType.COORD to "coord",
    )

    fun helperFor(kind: ArgType): String? = WRAPPED[kind]

    /**
     * Helper names the compiler still understands, including the ones the
     * decompiler no longer writes, so a folder dumped by an older build still
     * compiles. `tile` was Jagex's `coord`, `item` its `obj`, `skill` its `stat`.
     */
    private val helperKinds: Map<String, ArgType> = linkedMapOf(
        "colour" to ArgType.COLOUR,
        "coord" to ArgType.COORD,
        "tile" to ArgType.COORD,
        "component" to ArgType.COMPONENT,
        "obj" to ArgType.OBJ,
        "item" to ArgType.OBJ,
        "npc" to ArgType.NPC,
        "loc" to ArgType.LOC,
        "seq" to ArgType.SEQ,
        "spotanim" to ArgType.SPOTANIM,
        "enumId" to ArgType.ENUM,
        "struct" to ArgType.STRUCT,
        "param" to ArgType.PARAM,
        "inv" to ArgType.INV,
        "stat" to ArgType.STAT,
        "skill" to ArgType.STAT,
        "scriptId" to ArgType.SCRIPT,
        "graphic" to ArgType.GRAPHIC,
        "interfaceId" to ArgType.INTERFACE,
        "sound" to ArgType.SOUND,
        "midi" to ArgType.MIDI,
        "cursor" to ArgType.CURSOR,
        "mapelement" to ArgType.MAPELEMENT,
        "quest" to ArgType.QUEST,
        "worldmap" to ArgType.WORLDMAP,
        "model" to ArgType.MODEL,
    )

    /**
     * The TypeScript type a kind reads as: Jagex's own word for it, which is what
     * the catalogs are named after. `enum` and `interface` are TypeScript's own
     * keywords and cannot be type names, so those two take the capitalised
     * namespace instead; `boolean` is TypeScript's own type and is used as it is.
     */
    val ALIASES: Map<ArgType, String> = linkedMapOf(
        ArgType.OBJ to "obj",
        ArgType.NPC to "npc",
        ArgType.LOC to "loc",
        ArgType.SEQ to "seq",
        ArgType.SPOTANIM to "spotanim",
        ArgType.ENUM to "Enum",
        ArgType.STRUCT to "struct",
        ArgType.PARAM to "param",
        ArgType.INV to "inv",
        ArgType.GRAPHIC to "graphic",
        ArgType.INTERFACE to "Interface",
        ArgType.COMPONENT to "component",
        ArgType.SOUND to "sound",
        ArgType.MIDI to "midi",
        ArgType.CURSOR to "cursor",
        ArgType.MAPELEMENT to "mapelement",
        ArgType.QUEST to "quest",
        ArgType.WORLDMAP to "worldmap",
        ArgType.MODEL to "model",
        ArgType.COORD to "coord",
        ArgType.STAT to "stat",
        ArgType.COLOUR to "colour",
        ArgType.BOOLEAN to "boolean",
    )

    fun aliasFor(kind: ArgType): String? = ALIASES[kind]

    private val aliasKinds: Map<String, ArgType> =
        ALIASES.entries.associate { (kind, alias) -> alias to kind } + mapOf(
            // What older dumps wrote.
            "item" to ArgType.OBJ,
            "tile" to ArgType.COORD,
            "skill" to ArgType.STAT,
            "flag" to ArgType.BOOLEAN,
            "enumId" to ArgType.ENUM,
            "interfaceId" to ArgType.INTERFACE,
        )

    /** True when a name is one of the type aliases, which are all plain ints. */
    fun isAlias(name: String): Boolean = name in aliasKinds

    /** The kind of constant a helper name spells out, or null when it is not one. */
    fun argTypeOf(helper: String): ArgType? = helperKinds[helper]

    /** The kind a gameval namespace names, so `Obj.coins` knows what it is. */
    val NAMESPACE_KINDS: Map<String, ArgType> = linkedMapOf(
        "Obj" to ArgType.OBJ,
        "Npc" to ArgType.NPC,
        "Loc" to ArgType.LOC,
        "Seq" to ArgType.SEQ,
        "Spotanim" to ArgType.SPOTANIM,
        "Enum" to ArgType.ENUM,
        "Struct" to ArgType.STRUCT,
        "Param" to ArgType.PARAM,
        "Inv" to ArgType.INV,
        "Graphic" to ArgType.GRAPHIC,
        "Interface" to ArgType.INTERFACE,
        "Component" to ArgType.COMPONENT,
        "Sound" to ArgType.SOUND,
        "Midi" to ArgType.MIDI,
        "Cursor" to ArgType.CURSOR,
        "Mapelement" to ArgType.MAPELEMENT,
        "Quest" to ArgType.QUEST,
        "Worldmap" to ArgType.WORLDMAP,
        "Model" to ArgType.MODEL,
    )

    /** The catalog an [ArgType] names ids in, where there is one. */
    fun catalogType(kind: ArgType): String? = when (kind) {
        ArgType.OBJ -> Gameval.OBJ
        ArgType.NPC -> Gameval.NPC
        ArgType.LOC -> Gameval.LOC
        ArgType.SEQ -> Gameval.SEQ
        ArgType.SPOTANIM -> Gameval.SPOTANIM
        ArgType.ENUM -> Gameval.ENUM
        ArgType.STRUCT -> Gameval.STRUCT
        ArgType.PARAM -> Gameval.PARAM
        ArgType.INV -> Gameval.INV
        ArgType.GRAPHIC -> Gameval.GRAPHIC
        ArgType.INTERFACE -> Gameval.INTERFACE
        ArgType.COMPONENT -> Gameval.COMPONENT
        ArgType.SOUND -> Gameval.SOUND
        ArgType.MIDI -> Gameval.MIDI
        ArgType.CURSOR -> Gameval.CURSOR
        ArgType.MAPELEMENT -> Gameval.MAPELEMENT
        ArgType.QUEST -> Gameval.QUEST
        ArgType.WORLDMAP -> Gameval.WORLDMAP
        ArgType.MODEL -> Gameval.MODEL
        // STAT has no catalog; the rest are not ids at all.
        ArgType.INT, ArgType.COORD, ArgType.STAT, ArgType.SCRIPT,
        ArgType.BOOLEAN, ArgType.COLOUR, ArgType.TYPECHAR,
        -> null
    }

    /** The catalog a variable space stores names in, or null where none exists. */
    fun catalogType(space: VarSpace): String? = when (space) {
        VarSpace.VARP -> Gameval.VARP
        VarSpace.VARBIT -> Gameval.VARBIT
        VarSpace.VARC -> Gameval.VARC
        VarSpace.VARC_STRING -> Gameval.VARCSTR
        VarSpace.CLAN -> Gameval.VARCLAN
        VarSpace.CLAN_SETTING -> Gameval.VARCLANSETTING
        // The cache has no varn catalog; those keep their numbered defaults.
        VarSpace.VARN, VarSpace.VARNBIT -> null
    }

    /**
     * The `<space>_` a variable identifier leads with: the catalog's own type
     * name, so `varbit_11654` is both the identifier for an unnamed varbit and the
     * placeholder `gamevals/varbit.json` holds for it. No prefix contains `_`, so
     * `<prefix>_<name>` is unambiguous across spaces however the name reads.
     */
    val VAR_PREFIXES: Map<VarSpace, String> = linkedMapOf(
        VarSpace.VARP to Gameval.VARP,
        VarSpace.VARBIT to Gameval.VARBIT,
        VarSpace.VARC to Gameval.VARC,
        VarSpace.VARC_STRING to Gameval.VARCSTR,
        VarSpace.VARN to "varn",
        VarSpace.VARNBIT to "varnbit",
        VarSpace.CLAN to Gameval.VARCLAN,
        VarSpace.CLAN_SETTING to Gameval.VARCLANSETTING,
    )

    // ------------------------------------------------------------------ state

    /**
     * Every derivation, built from the catalogs on demand.
     *
     * Held whole and replaced whole by [reload], so one command always works
     * against one set of names rather than picking up a catalog edit halfway.
     */
    private class Derived {
        val byType = HashMap<String, Map<Int, String>>()
        val reverse = HashMap<String, Map<String, Int>>()

        var componentsBuilt = false
        var interfaces: List<ComponentInterface> = emptyList()
        var interfacesByName: Map<String, ComponentInterface> = emptyMap()
        var componentPaths: Map<Int, Pair<String, String>> = emptyMap()

        var scriptsBuilt = false
        var scripts: Map<Int, String> = emptyMap()
        var scriptsByName: Map<String, Int> = emptyMap()

        var rawScriptsBuilt = false
        var scriptsByRawName: Map<String, Int> = emptyMap()

        var layoutBuilt = false
        var paths: Map<Int, String> = emptyMap()
        var pathIds: Map<String, Int> = emptyMap()

        val vars = HashMap<VarSpace, Map<Int, String>>()
        val varsByName = HashMap<VarSpace, Map<String, Int>>()

        val warnings = ArrayList<String>()
    }

    /** One interface's named components, as `Component.<name>` renders them. */
    class ComponentInterface(
        val interfaceId: Int,
        /** The nested namespace's identifier, from the interface's own dev-name. */
        val identifier: String,
        /** The interface's dev-name, or its placeholder when it has none. */
        val rawName: String,
        val components: List<Component>,
    )

    class Component(val componentId: Int, val identifier: String, val rawName: String)

    @Volatile
    private var derived: Derived = Derived()

    private fun state(): Derived = derived

    /** Forgets every derivation so the next lookup re-reads `gamevals/<type>.json`. */
    fun reload() {
        Gameval.reload()
        derived = Derived()
    }

    /** Names that had to be changed to stay unique or legal, for a command to report. */
    val warnings: List<String> get() = synchronized(state()) { ArrayList(state().warnings) }

    // ------------------------------------------------------------ simple types

    /** The identifier for [id] of [type], or null when the id has no proven name. */
    fun identifier(type: String, id: Int): String? = identifiers(type)[id]

    /** The id [identifier] names in [type], or null. */
    fun idOf(type: String, identifier: String): Int? {
        val current = state()
        identifiers(type)
        return synchronized(current) { current.reverse[type]?.get(identifier) }
    }

    /**
     * id -> identifier for every id of [type], ascending, placeholders included.
     *
     * An id with no proven name still gets its catalog placeholder - `obj_5`,
     * `struct_1234` - rather than being left as a bare number, because a name is
     * what an editor can rename and `cs2 sync-names` can write back. A number is
     * not: it would have to be found and understood before it could be named.
     */
    fun identifiers(type: String): Map<Int, String> {
        val current = state()
        synchronized(current) {
            current.byType[type]?.let { return it }
            val entries = Gameval.allEntries(type).entries.sortedBy { it.key }
            val out = LinkedHashMap<Int, String>(entries.size * 2)
            val taken = HashMap<String, Int>(entries.size * 2)
            for ((id, name) in entries) {
                out[id] = unique(taken, Cs2Identifiers.escape(name, id.toString()), id, "$type $id", current)
            }
            current.byType[type] = out
            current.reverse[type] = taken
            return out
        }
    }

    /**
     * [ident] if free, else `<ident>_<id>`. A collision can only come from two
     * dev-names that escape to the same identifier; renaming one silently would
     * hide it, so the deviation is recorded in [warnings].
     */
    private fun unique(taken: HashMap<String, Int>, ident: String, id: Int, what: String, into: Derived): String {
        if (taken.putIfAbsent(ident, id) == null) return ident
        var candidate = "${ident}_$id"
        var n = 2
        while (taken.putIfAbsent(candidate, id) != null) {
            candidate = "${ident}_${id}_$n"
            n++
        }
        into.warnings.add("$what: '$ident' is already taken, using '$candidate'")
        return candidate
    }

    // ------------------------------------------------------------- components

    /** Every interface that has at least one named component, ascending by id. */
    fun componentInterfaces(): List<ComponentInterface> {
        components()
        return state().interfaces
    }

    /** `Component.<iface>.<comp>` for a packed component id, or null when unnamed. */
    fun componentPath(packed: Int): Pair<String, String>? {
        components()
        return state().componentPaths[packed]
    }

    /** The packed id `Component.<iface>.<comp>` stands for, or null. */
    fun componentOf(interfaceName: String, componentName: String): Int? {
        components()
        val group = state().interfacesByName[interfaceName] ?: return null
        val component = group.components.firstOrNull { it.identifier == componentName } ?: return null
        return GamevalCatalog.pack(group.interfaceId, component.componentId)
    }

    /** The interface id `Component.<iface>.IFACE` stands for, or null. */
    fun componentInterfaceId(interfaceName: String): Int? {
        components()
        return state().interfacesByName[interfaceName]?.interfaceId
    }

    /**
     * Groups the named components by interface.
     *
     * An interface's own name comes from the `<interface>:<component>` dev-name its
     * components carry; where the components are named but the interface is not, it
     * falls back to the placeholder `interface_<id>`, exactly as the generated Java
     * constants do.
     */
    private fun components() {
        val current = state()
        synchronized(current) {
            if (current.componentsBuilt) return
            current.componentsBuilt = true
            val byInterface = HashMap<Int, MutableList<Triple<Int, String, String?>>>()
            for ((key, name) in Gameval.rawEntries(Gameval.COMPONENT)) {
                val colon = key.indexOf(':')
                if (colon < 0) continue
                val interfaceId = key.substring(0, colon).toIntOrNull() ?: continue
                val componentId = key.substring(colon + 1).toIntOrNull() ?: continue
                val split = name.indexOf(':')
                val interfaceName = if (split < 0) null else name.substring(0, split)
                val componentName = if (split < 0) name else name.substring(split + 1)
                byInterface.getOrPut(interfaceId) { ArrayList() }
                    .add(Triple(componentId, componentName, interfaceName))
            }

            val groups = ArrayList<ComponentInterface>(byInterface.size)
            val takenInterfaces = HashMap<String, Int>(byInterface.size * 2)
            val paths = HashMap<Int, Pair<String, String>>(byInterface.size * 4)
            for (interfaceId in byInterface.keys.sorted()) {
                val raw = byInterface.getValue(interfaceId).firstNotNullOfOrNull { it.third }
                    ?: GamevalCatalog.placeholder(Gameval.INTERFACE, interfaceId.toString())
                val ident = unique(
                    takenInterfaces, Cs2Identifiers.escape(raw, interfaceId.toString()),
                    interfaceId, "component interface $interfaceId", current,
                )
                val taken = HashMap<String, Int>()
                taken[Cs2Identifiers.IFACE_MEMBER] = interfaceId
                val members = ArrayList<Component>()
                for ((componentId, componentName, _) in byInterface.getValue(interfaceId).sortedBy { it.first }) {
                    val key = "$interfaceId:$componentId"
                    val member = unique(
                        taken, Cs2Identifiers.escape(componentName, key),
                        componentId, "component $key", current,
                    )
                    members.add(Component(componentId, member, componentName))
                    paths[GamevalCatalog.pack(interfaceId, componentId)] = ident to member
                }
                groups.add(ComponentInterface(interfaceId, ident, raw, members))
            }
            current.interfaces = groups
            current.interfacesByName = groups.associateBy { it.identifier }
            current.componentPaths = paths
        }
    }

    // ---------------------------------------------------------------- scripts

    /** Names that already mean something at the top level of a decompiled file. */
    private val reservedGlobals: Set<String> by lazy {
        val out = HashSet<String>()
        Cs2Op.entries.mapTo(out) { it.tsName }
        out.addAll(Cs2Declarations.HELPERS)
        out.addAll(NAMESPACES.values)
        out.addAll(Cs2Identifiers.RESERVED_WORDS)
        out
    }

    /** Names a local, an array, a label or the old numbered defaults already use. */
    private val LOCAL_NAME = Regex("^(?:(?:int|str|long)(?:Arg)?\\d+|array\\d+|tmp\\w*|L\\d+)$")

    private val LEGACY_NAME =
        Regex("^(?:script|varp|varbit|varcstring|varcstr|varc|varnbit|varn|clanvar|clansetting|varclansetting|varclan)\\d+(?:Old)?$")

    private val VAR_PREFIX =
        Regex("^(?:varp|varbit|varcstring|varcstr|varc|varnbit|varn|clanvar|clansetting|varclansetting|varclan)_")

    private fun shadowsGlobal(name: String): Boolean =
        name in reservedGlobals || LOCAL_NAME.matches(name) || LEGACY_NAME.matches(name)

    /**
     * The function name for a clientscript.
     *
     * Always defined: a script with no proven name uses the catalog's own
     * `cs2_<id>` placeholder, so the JSON stays the only place a name comes from.
     */
    fun scriptIdentifier(id: Int): String = scriptIdentifiers()[id]
        ?: GamevalCatalog.placeholder(Gameval.CLIENTSCRIPT, id.toString())

    /** The clientscript [identifier] names, or null. */
    fun scriptIdOf(identifier: String): Int? {
        scriptIdentifiers()
        return state().scriptsByName[identifier]
    }

    /**
     * The id whose catalog entry reads exactly [raw] - `[proc,quicksort]`, or the
     * placeholder `cs2_2`.
     *
     * This is the binding a decompiled file carries in its header. Catalog names
     * are unique per type and a real name may not impersonate another id's
     * placeholder, so the mapping is one-to-one over every id in the catalog.
     */
    fun scriptIdOfRawName(raw: String): Int? {
        rawScripts()
        return state().scriptsByRawName[raw]
    }

    private fun rawScripts(): Map<String, Int> {
        val current = state()
        synchronized(current) {
            if (current.rawScriptsBuilt) return current.scriptsByRawName
            current.rawScriptsBuilt = true
            val all = Gameval.allEntries(Gameval.CLIENTSCRIPT)
            val out = HashMap<String, Int>(all.size * 2)
            for ((id, raw) in all.entries.sortedBy { it.key }) out.putIfAbsent(raw, id)
            current.scriptsByRawName = out
            return out
        }
    }

    /** id -> function name for every clientscript in the catalog, ascending. */
    fun scriptIdentifiers(): Map<Int, String> {
        val current = state()
        synchronized(current) {
            if (current.scriptsBuilt) return current.scripts
            current.scriptsBuilt = true
            val all = Gameval.allEntries(Gameval.CLIENTSCRIPT).entries.sortedBy { it.key }
            // `[proc,quicksort]` reads as `quicksort`, unless the bare name is used
            // in more than one category - then the category disambiguates it, the
            // rule the generated `Cs2` constants follow.
            val categories = HashMap<String, MutableSet<String>>()
            for ((id, name) in all) {
                if (Gameval.name(Gameval.CLIENTSCRIPT, id) == null) continue
                val match = CLIENTSCRIPT_NAME.matchEntire(name) ?: continue
                categories.getOrPut(match.groupValues[2]) { LinkedHashSet() }.add(match.groupValues[1])
            }
            val out = LinkedHashMap<Int, String>(all.size * 2)
            val taken = HashMap<String, Int>(all.size * 2)
            for ((id, raw) in all) {
                val named = Gameval.name(Gameval.CLIENTSCRIPT, id) != null
                val match = if (named) CLIENTSCRIPT_NAME.matchEntire(raw) else null
                val source = when {
                    match == null -> raw
                    match.groupValues[1].isEmpty() -> match.groupValues[2]
                    categories[match.groupValues[2]]?.size == 1 -> match.groupValues[2]
                    else -> "${match.groupValues[1]}_${match.groupValues[2]}"
                }
                var ident = Cs2Identifiers.escape(source, id.toString())
                if (shadowsGlobal(ident) || VAR_PREFIX.containsMatchIn(ident)) {
                    current.warnings.add("clientscript $id: '$ident' already names something else, using '${ident}_$id'")
                    ident = "${ident}_$id"
                }
                out[id] = unique(taken, ident, id, "clientscript $id", current)
            }
            current.scripts = out
            current.scriptsByName = taken
            return out
        }
    }

    private val CLIENTSCRIPT_NAME = Regex("\\[([^,\\]]*),([^\\]]*)\\]")

    // ----------------------------------------------------------------- layout

    /** id -> the path its source file lives at, relative to the folder. */
    fun scriptPaths(): Map<Int, String> {
        layout()
        return state().paths
    }

    /** The id a relative path names, or null when nothing derives that path. */
    fun scriptIdOfPath(relative: String): Int? {
        layout()
        return state().pathIds[relative.replace('\\', '/')]
    }

    /**
     * Where each clientscript's source file goes: `<category>/<name>.ts`.
     *
     * The catalog name is the whole of it. `[proc,quicksort]` is `proc/quicksort.ts`;
     * a name with no category - a placeholder, or one written bare by a rename -
     * goes under [UNCATEGORISED], which is the catalog's own placeholder prefix
     * rather than a folder this tool invented.
     */
    private fun layout() {
        val current = state()
        synchronized(current) {
            if (current.layoutBuilt) return
            current.layoutBuilt = true
            val paths = LinkedHashMap<Int, String>(Gameval.allEntries(Gameval.CLIENTSCRIPT).size * 2)
            val ids = HashMap<String, Int>(paths.size * 2)
            for ((id, raw) in Gameval.allEntries(Gameval.CLIENTSCRIPT).entries.sortedBy { it.key }) {
                val match = CLIENTSCRIPT_NAME.matchEntire(raw)
                val category = match?.groupValues?.get(1)?.takeIf { it.isNotEmpty() } ?: UNCATEGORISED
                val bare = match?.groupValues?.get(2) ?: raw
                var path = "${fileName(category, id)}/${fileName(bare, id)}.ts"
                if (ids.containsKey(path)) {
                    current.warnings.add("clientscript $id: '$path' is already taken, using an id suffix")
                    path = "${fileName(category, id)}/${fileName(bare, id)}_$id.ts"
                }
                paths[id] = path
                ids[path] = id
            }
            current.paths = paths
            current.pathIds = ids
        }
    }

    /**
     * The folder a clientscript with no `[category,...]` lives under. It is the
     * placeholder prefix the catalogs already use for unnamed scripts, so the
     * folder name is derived from the catalog like everything else.
     */
    val UNCATEGORISED: String = GamevalCatalog.placeholderPrefix(Gameval.CLIENTSCRIPT)

    /** A path segment for a catalog name: the identifier rules, which are already file-safe. */
    private fun fileName(raw: String, id: Int): String = Cs2Identifiers.escape(raw, id.toString())

    // -------------------------------------------------------------- variables

    /**
     * The global a variable is read and written through: `varbit_<name>` where the
     * catalog names it, `varbit_<id>` - the catalog's own placeholder - where it
     * does not. `Old` marks the `GET_VAR*_OLD` flavour, which addresses the same id.
     */
    fun variableIdentifier(reference: VarRef): String {
        val prefix = VAR_PREFIXES.getValue(reference.space)
        val suffix = if (reference.variant == "old") "Old" else ""
        val name = variables(reference.space)[reference.id] ?: reference.id.toString()
        return "${prefix}_$name$suffix"
    }

    /** The variable [identifier] names, or null when it is not one. */
    fun variableOf(identifier: String): VarRef? {
        for ((space, prefix) in VAR_PREFIXES) {
            if (!identifier.startsWith("${prefix}_")) continue
            val body = identifier.substring(prefix.length + 1)
            val old = body.endsWith("Old")
            val name = if (old) body.dropLast(3) else body
            val variant = if (old) "old" else null
            // A body that is only digits is the placeholder for an unnamed id; a
            // name of digits would have been escaped to `_<digits>` first, so the
            // two can never be read for each other.
            val id = name.toIntOrNull() ?: namedVariables(space)[name] ?: continue
            return VarRef(space, id, variableType(space), variant)
        }
        return null
    }

    /** id -> bare name for every named variable of [space]. */
    fun variables(space: VarSpace): Map<Int, String> {
        val type = catalogType(space) ?: return emptyMap()
        val current = state()
        synchronized(current) {
            current.vars[space]?.let { return it }
            val prefix = VAR_PREFIXES.getValue(space)
            val entries = Gameval.entries(type).entries.sortedBy { it.key }
            val out = LinkedHashMap<Int, String>(entries.size * 2)
            val taken = HashMap<String, Int>(entries.size * 2)
            for ((id, name) in entries) {
                var ident = Cs2Identifiers.escape(name, id.toString())
                if (shadowsGlobal("${prefix}_$ident")) {
                    current.warnings.add("$type $id: '${prefix}_$ident' already names something else, using '${prefix}_${ident}_$id'")
                    ident = "${ident}_$id"
                }
                out[id] = unique(taken, ident, id, "$type $id", current)
            }
            current.vars[space] = out
            current.varsByName[space] = taken
            return out
        }
    }

    private fun namedVariables(space: VarSpace): Map<String, Int> {
        variables(space)
        return state().varsByName[space].orEmpty()
    }

    fun variableType(space: VarSpace): Cs2Type =
        if (space == VarSpace.VARC_STRING) Cs2Type.STRING else Cs2Type.INT
}
