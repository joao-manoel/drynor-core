package world.gregs.voidps.cache.source.codec

import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.cache.definition.data.WorldMapDefinition
import world.gregs.voidps.cache.definition.data.WorldMapStaticElementDefinition
import world.gregs.voidps.cache.definition.decoder.WorldMapDetailsDecoder
import world.gregs.voidps.cache.definition.decoder.WorldMapStaticElementDecoder
import world.gregs.voidps.cache.definition.encoder.WorldMapDetailsEncoder
import world.gregs.voidps.cache.definition.encoder.WorldMapStaticElementEncoder
import world.gregs.voidps.cache.source.ArchiveMetadata
import java.nio.file.Path

/**
 * Index 23, whose 86 archives are three unrelated things: `details`, the areas, and their icons.
 *
 * ```
 * worldmapdata/details/29.json                 one world map area, file id = area id
 * worldmapdata/main_staticelements/0.json      one placed icon of that area
 * worldmapdata/main/4.dat                      the area's rendered image, bytes
 * ```
 *
 * Two of the three are editable JSON - a `details` file is 45 areas' worth of names, origins and
 * rectangles, and a static element is a coordinate, a map element id and the members flag - and the third,
 * the per-area image an area archive holds in its single file, has no decoder and stays bytes.
 *
 * **The directory names are the archives' real names, and only because they are certain.** Index 23 is the
 * one index in 727 whose archive names are neither a formula nor a catalog (`docs/cache-archive-names.md`),
 * so they were recovered once and are recorded here; every one of them re-hashes to the name hash the
 * reference table holds, which is what makes a name a fact rather than a guess. [NAMES] is checked against
 * [HASHES] when the class loads, and every unpack checks the cache's own hash against [HASHES] as well, so
 * a tree built from a cache whose index 23 has moved fails loudly instead of writing wrong directories. An
 * archive nothing names is its id, as it is everywhere else.
 *
 * The *file* names index 23 also carries - 731 of its 1,181 files have one - are not used for anything
 * here: a static element file is addressed by its JS5 file id like every other definition, and its name
 * hash rides along in `index.json` exactly as it does for index 31. See `docs/cache-archive-names.md`,
 * "Index 23", and `docs/reference/worldmap-cache-names.json`.
 */
object WorldMapCodec : SourceCodec {

    /** The archive `details` is in the 727 cache; the rest are areas and their static elements. */
    const val DETAILS = "details"

    /** What an area's icon archive is called: the area's name and this. */
    const val STATIC_ELEMENTS = "_staticelements"

    private val detailsCodec = DefinitionJsonCodec(
        id = "json-worldmaparea",
        layout = DefinitionLayout.Flat,
        create = { WorldMapDefinition(it) },
        decode = { definition, bytes -> WorldMapDetailsDecoder().readLoop(definition, BufferReader(bytes)) },
        encode = { definition, writer -> with(WorldMapDetailsEncoder()) { writer.encode(definition) } }
    )

    private val staticElementCodec = DefinitionJsonCodec(
        id = "json-worldmapelement",
        layout = DefinitionLayout.Flat,
        create = { WorldMapStaticElementDefinition(it) },
        decode = { definition, bytes ->
            WorldMapStaticElementDecoder().readLoop(definition, BufferReader(bytes))
        },
        encode = { definition, writer -> with(WorldMapStaticElementEncoder()) { writer.encode(definition) } }
    )

    private val delegate = ArchiveDirectoryCodec(
        id = "worldmap",
        name = { archive -> NAMES[archive] ?: archive.toString() },
        archive = { name -> ARCHIVES[name] ?: name.toIntOrNull() ?: -1 },
        codec = { archive -> codec(archive) }
    )

    override val id: String
        get() = delegate.id

    /** [archive]'s directory name: its recovered name, or its id when nothing names it. */
    fun name(archive: Int): String = NAMES[archive] ?: archive.toString()

    /** [archive]'s codec: JSON for `details` and the icon archives, bytes for an area's image. */
    fun codec(archive: Int): SourceCodec {
        val name = NAMES[archive] ?: return RawFilesCodec.FLAT
        return when {
            name == DETAILS -> detailsCodec
            name.endsWith(STATIC_ELEMENTS) -> staticElementCodec
            else -> RawFilesCodec.FLAT
        }
    }

    override fun exact(archive: Int): Boolean = delegate.exact(archive)

    override fun fileIdsFromMetadata(archive: Int): Boolean = delegate.fileIdsFromMetadata(archive)

    override fun unpack(directory: Path, archive: SourceArchive): UnpackedArchive {
        val expected = HASHES[archive.archive]
        // A name is only a directory name because it hashes to what the table says; check the cache too.
        check(expected == null || expected == archive.metadata.nameHash) {
            "Index 23 archive ${archive.archive} has name hash ${archive.metadata.nameHash}, not the " +
                "$expected that '${name(archive.archive)}' was recovered from; this is not a 727 cache."
        }
        return delegate.unpack(directory, archive)
    }

    override fun pack(directory: Path, archive: Int, metadata: ArchiveMetadata): PackedArchive =
        delegate.pack(directory, archive, metadata)

    override fun archiveOf(path: String): Int? = delegate.archiveOf(path)

    override fun files(directory: Path, archive: Int): List<Path> = delegate.files(directory, archive)

    /**
     * Every index 23 archive of the 727 cache and its recovered name.
     *
     * Verbatim from `docs/reference/worldmap-cache-names.json`, which records how each was found. All 86
     * came from the `world_map_area` rule, whose whole candidate space is small enough to be proof rather
     * than corroboration; `cache-source/gamevals/CONTRACT.md` sets that bar.
     */
    private val NAMES = mapOf(
        0 to "ardougne_underground",
        1 to DETAILS,
        2 to "zanaris",
        3 to "troll_stronghold",
        4 to "yanille_dungeon",
        5 to "tolna",
        6 to "slayer_cave",
        7 to "wilderness_dungeons",
        8 to "mole",
        9 to "white_wolf_mountain_underground",
        10 to "sops",
        11 to "null",
        12 to "soul_wars_tutorial",
        13 to "tzhaar_area",
        14 to "karamja_underground",
        15 to "miscellania_underground",
        16 to "godwars",
        17 to "waterbirth",
        18 to "taverley_dungeon",
        19 to "dwarven_mines",
        20 to "braindeath_island",
        21 to "kalphite",
        22 to "keldagrim",
        23 to "pollnivneach_well",
        24 to "chaos_tunnels",
        25 to "sos",
        26 to "asgarnia_ice_dungeon",
        27 to "dorgeshkaan",
        28 to "mosleharmless_cave",
        29 to "main",
        30 to "ourania",
        31 to "undead_dungeon",
        32 to "misthalin_underground",
        33 to "tutorial3_1",
        34 to "tutorial3_2",
        35 to "tutorial3_3",
        36 to "living_rock_caverns",
        37 to "living_rock_caverns$STATIC_ELEMENTS",
        38 to "tutorial3_1$STATIC_ELEMENTS",
        39 to "tutorial3_2$STATIC_ELEMENTS",
        40 to "tutorial3_3$STATIC_ELEMENTS",
        41 to "ancient_cavern",
        42 to "ancient_cavern$STATIC_ELEMENTS",
        43 to "spirit_plane",
        44 to "ft3_zanaris_hq",
        45 to "lfm_worldmap",
        46 to "lfm_worldmap$STATIC_ELEMENTS",
        47 to "jaro_jade_roots",
        48 to "jaro_jade_roots$STATIC_ELEMENTS",
        49 to "morytania_underground",
        50 to "morytania_underground$STATIC_ELEMENTS",
        51 to "runecrafting_guild_area",
        52 to "qbd2_area",
        53 to "qbd2_area$STATIC_ELEMENTS",
        54 to "carni_sewervariants",
        64 to "zanaris$STATIC_ELEMENTS",
        65 to "troll_stronghold$STATIC_ELEMENTS",
        66 to "white_wolf_mountain_underground$STATIC_ELEMENTS",
        67 to "tolna$STATIC_ELEMENTS",
        68 to "ardougne_underground$STATIC_ELEMENTS",
        69 to "taverley_dungeon$STATIC_ELEMENTS",
        70 to "soul_wars_tutorial$STATIC_ELEMENTS",
        71 to "asgarnia_ice_dungeon$STATIC_ELEMENTS",
        72 to "godwars$STATIC_ELEMENTS",
        73 to "waterbirth$STATIC_ELEMENTS",
        74 to "wilderness_dungeons$STATIC_ELEMENTS",
        75 to "kalphite$STATIC_ELEMENTS",
        76 to "chaos_tunnels$STATIC_ELEMENTS",
        77 to "slayer_cave$STATIC_ELEMENTS",
        78 to "ourania$STATIC_ELEMENTS",
        79 to "mosleharmless_cave$STATIC_ELEMENTS",
        80 to "braindeath_island$STATIC_ELEMENTS",
        81 to "sos$STATIC_ELEMENTS",
        82 to "undead_dungeon$STATIC_ELEMENTS",
        83 to "main$STATIC_ELEMENTS",
        84 to "pollnivneach_well$STATIC_ELEMENTS",
        85 to "misthalin_underground$STATIC_ELEMENTS",
        86 to "mole$STATIC_ELEMENTS",
        87 to "dwarven_mines$STATIC_ELEMENTS",
        88 to "tzhaar_area$STATIC_ELEMENTS",
        89 to "dorgeshkaan$STATIC_ELEMENTS",
        90 to "yanille_dungeon$STATIC_ELEMENTS",
        91 to "keldagrim$STATIC_ELEMENTS",
        92 to "miscellania_underground$STATIC_ELEMENTS",
        93 to "karamja_underground$STATIC_ELEMENTS",
        94 to "sops$STATIC_ELEMENTS"
    )

    /**
     * The name hash each of those names was recovered from, which is the only thing that makes it a name.
     *
     * Derived rather than listed: a name *is* its hash, `String.hashCode()` is what the reference table
     * stores, and a table of 86 numbers nobody can check by eye is worth less than the one line that
     * computes them.
     */
    private val HASHES = NAMES.mapValues { (_, name) -> name.hashCode() }

    private val ARCHIVES = NAMES.entries.associate { (archive, name) -> name to archive }
}
