package world.gregs.voidps.cache.source

/**
 * Jagex's own name for each of the 37 indices, which is what the index's directory is called in
 * a source tree.
 *
 * The names come from the client's index constants rather than from anything in the cache - no
 * index carries its own name - so they are a fixed table, and a tree written by one build reads
 * back the same way in the next. [world.gregs.voidps.cache.Index] holds the same ids under the
 * names the server code uses; this is the on-disk spelling and only that.
 */
object IndexNames {

    private val names = arrayOf(
        "anims",
        "bases",
        "config",
        "interfaces",
        "synth_sounds",
        "maps",
        "midi_songs",
        "models",
        "graphics",
        "textures",
        "binary",
        "midi_jingles",
        "clientscripts",
        "fontmetrics",
        "vorbis",
        "midi_instruments",
        "config_loc",
        "config_enum",
        "config_npc",
        "config_obj",
        "config_seq",
        "config_spotanim",
        "config_varbit",
        "worldmapdata",
        "quickchat",
        "quickchat_global",
        "materials",
        "config_particle",
        "defaults",
        "config_billboard",
        "dlls",
        "shaders",
        "loading_graphics",
        "loading_screens",
        "loading_graphics_raw",
        "cutscenes",
        "index36"
    )

    private val indices = HashMap<String, Int>(names.size * 2).also { map ->
        for (index in names.indices) {
            map[names[index]] = index
        }
    }

    /** The number of indices 727 has. A tree may hold fewer; it never holds more with names. */
    const val COUNT = 37

    /** [index]'s directory name. An index past the table falls back to `index<id>`. */
    fun name(index: Int): String = names.getOrNull(index) ?: "index$index"

    /** The index [name] belongs to, or -1 when it names nothing. */
    fun index(name: String): Int = indices[name] ?: fallback(name)

    private fun fallback(name: String): Int {
        if (!name.startsWith("index")) {
            return -1
        }
        return name.substring("index".length).toIntOrNull() ?: -1
    }
}
