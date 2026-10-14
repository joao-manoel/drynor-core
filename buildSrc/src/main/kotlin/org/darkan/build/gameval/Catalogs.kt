package org.darkan.build.gameval

import groovy.json.JsonParserType
import groovy.json.JsonSlurper
import java.io.File

/**
 * The gameval catalogs (`gamevals/<type>.json`) and the classes generated from them.
 *
 * A catalog holds every revision-727 cache id of one asset type, mapped either to the Jagex dev
 * name proven for it or to the placeholder `<type>_<id>`. See `docs/gameval.md` and
 * `gamevals/CONTRACT.md`.
 */
const val COMPONENT = "component"
const val CLIENTSCRIPT = "clientscript"

/** Catalog type -> generated class name, in the order `Gameval.TYPES` lists them. */
val GAMEVAL_CLASSES: Map<String, String> = linkedMapOf(
    "obj" to "Obj", "npc" to "Npc", "loc" to "Loc", "seq" to "Seq", "spotanim" to "Spotanim",
    "enum" to "Enum", "struct" to "Struct", "param" to "Param", "inv" to "Inv",
    "interface" to "Interface", COMPONENT to "Component", CLIENTSCRIPT to "Cs2",
    "varp" to "Varp", "varbit" to "Varbit", "varc" to "Varc", "varcstr" to "Varcstr",
    "varclan" to "Varclan", "varbitclan" to "Varbitclan",
    "varclansetting" to "Varclansetting", "varbitclansetting" to "Varbitclansetting",
    "bas" to "Bas", "hitmark" to "Hitmark", "headbar" to "Headbar", "cursor" to "Cursor",
    "mapelement" to "Mapelement", "quest" to "Quest", "model" to "Model", "graphic" to "Graphic",
    "sound" to "Sound", "midi" to "Midi", "material" to "Material", "fontmetrics" to "Fontmetrics",
    "idk" to "Idk", "overlay" to "Overlay", "underlay" to "Underlay", "mapscene" to "Mapscene",
    "skybox" to "Skybox", "quickchatoption" to "Quickchatoption", "quickchatphrase" to "Quickchatphrase",
    "worldmap" to "Worldmap",
)

/** The placeholder an id carries when no name was ever proven for it. */
fun placeholderName(type: String, key: String): String {
    val prefix = if (type == CLIENTSCRIPT) "cs2" else type
    return prefix + "_" + key.replace(":", "_")
}

/** `coverage` counts a catalog states about itself; any of them may be absent. */
class Coverage(val cacheIds: Int?, val named: Int?, val placeholder: Int?)

/** One parsed catalog. [entries] keeps the file's own key order. */
class Catalog(
    val type: String,
    val source: String,
    val revision: Int,
    val coverage: Coverage,
    val entries: Map<String, String>,
)

/** A codegen problem that must stop the build, carrying every offending entry it found. */
class GamevalGenerationException(message: String) : RuntimeException(message)

private fun asInt(value: Any?): Int? = (value as? Number)?.toInt()

/**
 * Parses one catalog. Anything unexpected in the file is a build failure rather than a skipped
 * type: the catalogs are committed, so a shape the generator does not understand means the file is
 * damaged, and silently dropping a type would delete thousands of constants that code references.
 */
fun readCatalog(type: String, file: File): Catalog {
    if (!file.isFile) {
        throw GamevalGenerationException(
            "gameval catalog for type '$type' is missing: expected ${file.absolutePath}. " +
                "Every type in GAMEVAL_CLASSES must have a catalog; restore the file or drop the type.",
        )
    }
    // INDEX_OVERLAY rather than JsonSlurper's default CHAR_BUFFER parser: the default is quadratic
    // on a flat object this size (43 s for loc.json's 74k entries against 0.09 s here), and every
    // value is materialised into the map below before the parser's buffers go away.
    val root = try {
        JsonSlurper().apply { setType(JsonParserType.INDEX_OVERLAY) }.parseText(file.readText())
    } catch (e: Exception) {
        throw GamevalGenerationException("gameval catalog ${file.absolutePath} is not readable JSON: ${e.message}")
    }
    if (root !is Map<*, *>) {
        throw GamevalGenerationException("gameval catalog ${file.absolutePath} must be a JSON object, found ${root?.javaClass?.simpleName}")
    }
    val declared = root["type"]
    if (declared != null && declared != type) {
        throw GamevalGenerationException("gameval catalog ${file.absolutePath} declares type '$declared' but is named '$type.json'")
    }
    val rawEntries = root["entries"]
    if (rawEntries !is Map<*, *>) {
        throw GamevalGenerationException("gameval catalog ${file.absolutePath} has no 'entries' object")
    }
    val entries = LinkedHashMap<String, String>(rawEntries.size * 2)
    for ((key, value) in rawEntries) {
        val k = (key as? CharSequence)?.toString()
            ?: throw GamevalGenerationException("gameval catalog ${file.absolutePath} has a non-string entry key: $key")
        if (value !is CharSequence) {
            throw GamevalGenerationException(
                "$type $k: name must be a string, found ${if (value == null) "null" else value.javaClass.simpleName} ($value) in ${file.absolutePath}",
            )
        }
        entries[k] = value.toString()
    }
    val coverage = root["coverage"] as? Map<*, *>
    return Catalog(
        type = type,
        source = file.absolutePath,
        revision = asInt(root["revision"]) ?: 727,
        coverage = Coverage(asInt(coverage?.get("cache_ids")), asInt(coverage?.get("named")), asInt(coverage?.get("placeholder"))),
        entries = entries,
    )
}
