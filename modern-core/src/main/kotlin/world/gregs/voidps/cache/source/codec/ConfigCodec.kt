package world.gregs.voidps.cache.source.codec

import world.gregs.voidps.cache.Config
import world.gregs.voidps.cache.source.ArchiveMetadata
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Index 2, whose archives are 45 unrelated definition types rather than 45 more of the same
 * thing, so each of them gets a directory of its own and a codec of its own.
 *
 * The directory is the archive's canonical name - `struct`, `inv`, `varp` - in the gameval
 * vocabulary where the type has one, because those are the names everything else in the server
 * already calls them by, and the lower cased [Config] constant otherwise. An archive nothing has
 * a name for is `archive_<id>`, which keeps the tree complete without inventing a name; naming
 * one later is a directory rename and a line here.
 *
 * Sub-codecs are registered by archive id the same way index codecs are registered by index id -
 * see [SourceCodecs] - and are called with their own directory, so they lay files out inside it
 * without repeating the archive id ([RawFilesCodec.FLAT], [DefinitionLayout.Flat]). Fourteen of the
 * forty-five archives have a byte exact encoder and are editable JSON ([DefinitionCodecs]); the rest
 * have no decoder at all and stay raw bytes.
 */
object ConfigCodec : SourceCodec {

    /**
     * The canonical directory name of each config archive the 727 cache has a name for.
     *
     * Twenty-six of the forty-five archives in index 2 are named; the rest hold types nothing
     * has identified yet and are `archive_<id>`.
     */
    private val names = mapOf(
        Config.FLOOR_UNDERLAY to "underlay",
        Config.IDENTITY_KIT to "idk",
        Config.FLOOR_OVERLAY to "overlay",
        Config.INVENTORIES to "inv",
        Config.PARAMS to "param",
        Config.SEQUENCES to "seq",
        Config.VARC_STRINGS to "varcstr",
        Config.VARP to "varp",
        Config.VARC to "varc",
        Config.STRUCTS to "struct",
        Config.SKY_BOXES to "skybox",
        Config.LIGHT_INTENSITY to "light",
        Config.RENDER_ANIMATIONS to "bas",
        Config.CURSORS to "cursor",
        Config.MAP_SCENES to "mapscene",
        Config.QUESTS to "quest",
        Config.WORLD_MAP_INFO to "mapelement",
        Config.HIT_SPLATS to "hitmark",
        Config.VAR_CLAN to "varclan",
        Config.VAR_CLAN_SETTINGS to "varclansetting",
        Config.VAR_PLAYER to "varplayer",
        Config.VAR_NPC to "varnpc",
        Config.VAR_CLIENT to "varclient",
        Config.VAR_BIT to "varbit",
        Config.HIT_BARS to "headbar",
        Config.ANIM_FLOW_CONTROL to "animflowcontrol",
        Config.VAR_GROUP to "vargroup"
    )

    private val archives = HashMap<String, Int>(names.size * 2).also { map ->
        for ((archive, name) in names) {
            map[name] = archive
        }
    }

    private val codecs = ConcurrentHashMap<Int, SourceCodec>()

    override val id: String
        get() = "config"

    /** [archive]'s directory name under `config/`. */
    fun name(archive: Int): String = names[archive] ?: "$UNNAMED_PREFIX$archive"

    /** The archive [name] is the directory of, or -1. */
    fun archive(name: String): Int {
        archives[name]?.let { return it }
        if (!name.startsWith(UNNAMED_PREFIX)) {
            return -1
        }
        return name.substring(UNNAMED_PREFIX.length).toIntOrNull() ?: -1
    }

    /** Give [archive] its own codec. Call before a tree is read or written; see [SourceCodecs]. */
    fun register(archive: Int, codec: SourceCodec) {
        codecs[archive] = codec
    }

    /** [archive]'s codec, raw files by default. */
    fun codec(archive: Int): SourceCodec = codecs[archive] ?: RawFilesCodec.FLAT

    override fun exact(archive: Int): Boolean = codec(archive).exact(archive)

    override fun fileIdsFromMetadata(archive: Int): Boolean = codec(archive).fileIdsFromMetadata(archive)

    override fun unpack(directory: Path, archive: SourceArchive): UnpackedArchive {
        val name = name(archive.archive)
        val unpacked = codec(archive.archive).unpack(directory.resolve(name), archive)
        return UnpackedArchive(unpacked.files.map { SourceFile("$name/${it.path}", it.bytes) }, unpacked.pristine)
    }

    override fun pack(directory: Path, archive: Int, metadata: ArchiveMetadata): PackedArchive =
        codec(archive).pack(directory.resolve(name(archive)), archive, metadata)

    override fun archiveOf(path: String): Int? {
        val separator = path.indexOf('/')
        if (separator <= 0) {
            return null
        }
        val archive = archive(path.substring(0, separator))
        return if (archive < 0) null else archive
    }

    override fun files(directory: Path, archive: Int): List<Path> {
        val folder = directory.resolve(name(archive))
        if (!Files.isDirectory(folder)) {
            return emptyList()
        }
        return codec(archive).files(folder, archive)
    }

    /** The prefix a config archive nobody has named yet gets its directory from. */
    const val UNNAMED_PREFIX = "archive_"
}
