package org.drynor.core.gameval

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.drynor.core.Logger.logWarn
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * One set of `gamevals/<type>.json` catalogs: id -> Jagex dev-name for every revision-727 asset type.
 *
 * Catalogs are read from [folder] - the `gamevals/` directory of the cache source tree
 * (`docs/cache-source.md`), which is the only copy there is; nothing is bundled into a jar. Each
 * is parsed lazily on first use and cached per type, thread-safely.
 *
 * Every id the 727 cache has is present in a catalog, so a *missing* id means the type file is
 * absent. Ids with no proven name carry a placeholder (`<type>_<id>`, `cs2_<id>` for
 * clientscripts, `component_<iface>_<comp>` for components). A placeholder is not a name: the
 * name-returning API hides it and reports `null`; [rawName] and [allEntries] expose it.
 *
 * A catalog can be edited by hand while a server is running, so a file that is missing or
 * half-written is treated as empty (one warning per type) and retried after [retryMillis] rather
 * than cached as broken for the life of the process.
 *
 * [Gameval] is the process-wide instance; construct this class directly only to point at a
 * different folder (tests do).
 */
open class GamevalCatalog(
    private val folder: File?,
    private val retryMillis: Long = DEFAULT_RETRY_MILLIS,
) {
    @Serializable
    private data class CatalogFile(val entries: Map<String, String> = emptyMap())

    /** Where a type's catalog was read from. */
    enum class Source { FOLDER, MISSING }

    private class Loaded(
        /** Raw `entries` exactly as stored: `"<iface>:<comp>"` keys for `component`, stringified ints otherwise. */
        val raw: Map<String, String>,
        val source: Source,
        val loadedAt: Long,
    ) {
        val ok: Boolean get() = source != Source.MISSING

        /** Int-keyed id -> raw name view; composite keys are skipped. */
        val all: Map<Int, String> by lazy {
            if (raw.isEmpty()) emptyMap() else HashMap<Int, String>(raw.size).also { out ->
                for ((key, name) in raw) out[key.toIntOrNull() ?: continue] = name
            }
        }

        /** [all] without placeholders. */
        fun named(type: String): Map<Int, String> = namedCache ?: buildNamed(type).also { namedCache = it }

        /** Reverse of [named]. */
        fun reverse(type: String): Map<String, Int> = reverseCache ?: buildReverse(type).also { reverseCache = it }

        @Volatile private var namedCache: Map<Int, String>? = null
        @Volatile private var reverseCache: Map<String, Int>? = null

        private fun buildNamed(type: String): Map<Int, String> {
            if (all.isEmpty()) return emptyMap()
            val out = HashMap<Int, String>(all.size)
            for ((id, name) in all) if (!isPlaceholder(type, id.toString(), name)) out[id] = name
            return out
        }

        private fun buildReverse(type: String): Map<String, Int> {
            val named = named(type)
            if (named.isEmpty()) return emptyMap()
            val out = HashMap<String, Int>(named.size)
            for ((id, name) in named) out[name] = id
            return out
        }
    }

    private val cache = ConcurrentHashMap<String, Loaded>()
    private val warned = ConcurrentHashMap.newKeySet<String>()

    /** Reverse of the composite [COMPONENT] catalog: `"<iface>:<comp>"` name -> packed `(iface shl 16) or comp`. */
    @Volatile private var componentReverse: Pair<Loaded, Map<String, Int>>? = null

    // ---- loading -------------------------------------------------------------------------------

    private fun loaded(type: String): Loaded {
        val now = System.currentTimeMillis()
        val current = cache[type]
        if (current != null && (current.ok || now - current.loadedAt < retryMillis)) return current
        return cache.compute(type) { t, existing ->
            if (existing != null && existing !== current && (existing.ok || now - existing.loadedAt < retryMillis)) existing
            else load(t, now)
        }!!
    }

    private fun load(type: String, now: Long): Loaded {
        val file = folder?.let { File(it, "$type.json") }
        if (file != null && file.isFile) {
            val parsed = parse(type, file.absolutePath) { file.readText() }
            if (parsed != null) return Loaded(parsed, Source.FOLDER, now).also { warned.remove(type) }
        }
        if (file == null || !file.isFile) {
            warnOnce(type, "no $type.json catalog in ${folder?.absolutePath ?: "(no folder)"} (GAMEVAL_PATH, normally <cache source tree>/gamevals); $type names will not resolve")
        }
        return Loaded(emptyMap(), Source.MISSING, now)
    }

    private inline fun parse(type: String, where: String, text: () -> String): Map<String, String>? = try {
        json.decodeFromString(CatalogFile.serializer(), text()).entries
    } catch (e: Exception) {
        warnOnce(type, "$where is present but unreadable (${e.message?.lineSequence()?.firstOrNull()}); $type names will not resolve until it is rewritten", e)
        null
    }

    private fun warnOnce(type: String, message: String, e: Throwable? = null) {
        if (warned.add(type)) logWarn("Gameval: $message", e)
    }

    // ---- id -> name ----------------------------------------------------------------------------

    /** The dev-name for [id] in [type], or `null` when the id is unknown or only has a placeholder. */
    fun name(type: String, id: Int): String? = loaded(type).named(type)[id]

    /** The catalog string for [id] in [type] as stored — a real name or a placeholder — or `null` if the id is absent. */
    fun rawName(type: String, id: Int): String? = loaded(type).all[id]

    /** True when [id] is present in the [type] catalog but has no proven name. */
    fun isPlaceholder(type: String, id: Int): Boolean {
        val raw = rawName(type, id) ?: return false
        return isPlaceholder(type, id.toString(), raw)
    }

    /** Display label: `"name (id)"` when a name exists, else just `"id"`. */
    fun label(type: String, id: Int): String = name(type, id)?.let { "$it ($id)" } ?: id.toString()

    // ---- name -> id ----------------------------------------------------------------------------

    /** The id whose dev-name is [name] in [type], or `null`. Names are unique per type; placeholders never match. */
    fun id(type: String, name: String): Int? = loaded(type).reverse(type)[name]

    /**
     * Like [id] but throws when [name] is unknown, so a typo'd or renamed name fails loudly the
     * first time the constant is resolved instead of silently becoming a wrong id.
     */
    fun requireId(type: String, name: String): Int = id(type, name) ?: error("Gameval: no $type named '$name'")

    // ---- enumeration ---------------------------------------------------------------------------

    /** True if a [type] catalog was read and has at least one entry (placeholders count). */
    fun has(type: String): Boolean = loaded(type).raw.isNotEmpty()

    /** Where the [type] catalog was read from. */
    fun source(type: String): Source = loaded(type).source

    /** id -> dev-name for every *named* id of [type]; empty when the catalog is absent. */
    fun entries(type: String): Map<Int, String> = loaded(type).named(type)

    /** id -> catalog string for every id of [type], placeholders included; empty when the catalog is absent. */
    fun allEntries(type: String): Map<Int, String> = loaded(type).all

    /** Raw `entries` as stored, including composite keys — the only int-free view of [COMPONENT]. */
    fun rawEntries(type: String): Map<String, String> = loaded(type).raw

    /** Forget every cached catalog so the next lookup re-reads the files. */
    fun reload() {
        cache.clear()
        componentReverse = null
        warned.clear()
    }

    // ---- components ----------------------------------------------------------------------------

    /** The dev-name (`"<iface>:<comp>"`) of component [componentId] of interface [interfaceId], or `null` if unknown or a placeholder. */
    fun component(interfaceId: Int, componentId: Int): String? {
        val key = componentKey(interfaceId, componentId)
        val raw = loaded(COMPONENT).raw[key] ?: return null
        return if (isPlaceholder(COMPONENT, key, raw)) null else raw
    }

    /** [component] for a packed `(iface shl 16) or comp` id. */
    fun component(packed: Int): String? = component(ifaceOf(packed), compOf(packed))

    /** Display label: `"name (iface:comp)"` when a name exists, else just `"iface:comp"`. */
    fun componentLabel(interfaceId: Int, componentId: Int): String {
        val key = componentKey(interfaceId, componentId)
        return component(interfaceId, componentId)?.let { "$it ($key)" } ?: key
    }

    /**
     * Reverse of [component]: the packed id `(iface shl 16) or comp` for the full component
     * dev-name [name] (`"<ifacename>:<compname>"`), or `null`. The packed value is what the
     * interface packets address components by.
     */
    fun componentId(name: String): Int? = componentReverseMap()[name]

    /** Like [componentId] but throws when [name] is unknown. */
    fun requireComponentId(name: String): Int = componentId(name) ?: error("Gameval: no component named '$name'")

    private fun componentReverseMap(): Map<String, Int> {
        val current = loaded(COMPONENT)
        componentReverse?.let { (from, map) -> if (from === current) return map }
        val map = if (current.raw.isEmpty()) emptyMap() else HashMap<String, Int>(current.raw.size).also { out ->
            for ((key, name) in current.raw) {
                val colon = key.indexOf(':')
                if (colon < 0) continue
                val iface = key.substring(0, colon).toIntOrNull() ?: continue
                val comp = key.substring(colon + 1).toIntOrNull() ?: continue
                if (isPlaceholder(COMPONENT, key, name)) continue
                out[name] = pack(iface, comp)
            }
        }
        componentReverse = current to map
        return map
    }

    companion object {
        const val COMPONENT = "component"
        const val CLIENTSCRIPT = "clientscript"

        /** How long a missing or unreadable catalog stays "empty" before the file is looked at again. */
        const val DEFAULT_RETRY_MILLIS = 30_000L

        private val json = Json { ignoreUnknownKeys = true }

        /** Packs an interface id and component id the way interface packets address components. */
        fun pack(interfaceId: Int, componentId: Int): Int = (interfaceId shl 16) or (componentId and 0xFFFF)

        /** The interface id of a packed component id. */
        fun ifaceOf(packed: Int): Int = packed ushr 16

        /** The component id (low 16 bits) of a packed component id. */
        fun compOf(packed: Int): Int = packed and 0xFFFF

        /** The `component.json` key for [interfaceId]:[componentId]. */
        fun componentKey(interfaceId: Int, componentId: Int): String = "$interfaceId:$componentId"

        /** Placeholder prefix for [type]: the type name, except clientscripts use `cs2`. */
        fun placeholderPrefix(type: String): String = if (type == CLIENTSCRIPT) "cs2" else type

        /**
         * The placeholder string the catalogs use for an unnamed id: `<type>_<id>`, `cs2_<id>` for
         * clientscripts, `component_<iface>_<comp>` for components (whose [key] is `"<iface>:<comp>"`).
         */
        fun placeholder(type: String, key: String): String = "${placeholderPrefix(type)}_${key.replace(':', '_')}"

        internal fun isPlaceholder(type: String, key: String, name: String): Boolean = name == placeholder(type, key)
    }
}
