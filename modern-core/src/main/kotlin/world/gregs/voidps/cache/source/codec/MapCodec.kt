package world.gregs.voidps.cache.source.codec

import it.unimi.dsi.fastutil.ints.Int2IntMap
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.cache.MapSquare
import world.gregs.voidps.cache.definition.data.MapDefinition
import world.gregs.voidps.cache.definition.data.MapObject
import world.gregs.voidps.cache.definition.decoder.MapNpcDecoder
import world.gregs.voidps.cache.definition.decoder.MapObjectDecoder
import world.gregs.voidps.cache.definition.decoder.MapTileDecoder
import world.gregs.voidps.cache.definition.encoder.MapNpcEncoder.writeSpawns
import world.gregs.voidps.cache.definition.encoder.MapObjectEncoder
import world.gregs.voidps.cache.definition.encoder.MapTileEncoder
import world.gregs.voidps.cache.source.ArchiveMetadata
import world.gregs.voidps.cache.source.IndexMetadata
import world.gregs.voidps.cache.source.SourceFiles
import world.gregs.voidps.cache.source.SourceTree
import java.io.IOException
import java.nio.BufferOverflowException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Index 5 - the maps - laid out by region as editable JSON.
 *
 * A map square is up to five archives and they are edited together, so the region is the directory
 * and each of the five is a file in it:
 *
 * ```
 * maps/50_50/terrain.json             m50_50   4 levels of tiles, then the environment block
 * maps/50_50/locs.json                l50_50   the placed locations (XTEA encrypted in the cache)
 * maps/50_50/npcs.json                n50_50   the npc spawns (XTEA encrypted)
 * maps/50_50/underwater_terrain.json  um50_50  one level of tiles
 * maps/50_50/underwater_locs.json     ul50_50  the underwater locations
 * maps/<archive>.container                     an archive nobody has the key for, framework owned
 * ```
 *
 * Only the files a region actually has are written: all 2,407 surface squares have terrain and
 * locations, 1,208 have the underwater pair and 38 have npc spawns. [MapJson] is the spelling of
 * each file, and `docs/cache-source.md` ("maps") the layout and the measured parity.
 *
 * ## Where the archive id comes from
 *
 * Index 5 is the one index whose archive names are a formula rather than a catalogue
 * ([MapSquare]), and its reference table stores name hashes and no names. A region directory
 * therefore cannot say which archive id it is: the way back is the hash, and the hashes live in
 * `index.json`. So this codec answers [archiveOf] with the tree to look in - the layout's binding
 * is in `index.json` the way index 12's is in a script header - and its per archive [unpack] and
 * [pack] take the name hash from the [ArchiveMetadata] they are handed, which needs no lookup at
 * all.
 *
 * ## Parity
 *
 * Byte exact for all 7,268 archives: the decoders keep every record the files hold, in order, and
 * the encoders replay them. A file is checked on the way out all the same - decoded, written as
 * JSON, read straight back and re-encoded - and one that does not come out identical keeps its
 * shipped bytes in a `<name>.pristine` sidecar, exactly as [DefinitionJsonCodec] does. The count is
 * zero for the 727 cache and `cache status` is where it would show up if an edit ever changed that.
 */
object MapCodec : SourceCodec {

    override val id: String
        get() = "map-json"

    /** One file per archive, and its id (always 0 in 727) is not in the path. */
    override fun fileIdsFromMetadata(archive: Int): Boolean = true

    /**
     * False: a JSON file is not the shipped bytes, so the unpacker packs the tree back and proves
     * it anyway. [unpack] has already fallen back to a pristine sidecar for anything that did not
     * survive the round trip, so this is a second, independent check rather than the only one.
     */
    override fun exact(archive: Int): Boolean = false

    override fun unpack(directory: Path, archive: SourceArchive): UnpackedArchive {
        val square = square(archive.metadata.nameHash, archive.archive)
        check(archive.files.size == 1) {
            "Map archive ${archive.archive} (${square.name}) has ${archive.files.size} files; " +
                "$id writes one file per archive."
        }
        val data = archive.files[0]
        val name = path(square)
        val text = write(square, data)
        val files = ArrayList<SourceFile>(2)
        files.add(SourceFile(name, text))
        if (!compile(square, text).contentEquals(data)) {
            // The JSON is still the editable form; the shipped bytes ride along beside it until
            // somebody edits it, which is what keeps parity independent of the encoders.
            files.add(SourceFile(SourceFiles.pristine(name), data))
            return UnpackedArchive(files, mapOf(archive.fileIds[0] to SourceFiles.sha256(text)))
        }
        return UnpackedArchive(files)
    }

    override fun pack(directory: Path, archive: Int, metadata: ArchiveMetadata): PackedArchive {
        val square = square(metadata.nameHash, archive)
        val ids = metadata.fileIds()
        check(ids.size == 1) { "Map archive $archive (${square.name}) is one file on disk but its metadata lists ${ids.size}." }
        val file = directory.resolve(path(square))
        if (!Files.isRegularFile(file)) {
            throw IOException("Map archive $archive (${square.name}) has no JSON at ${file.toAbsolutePath()}.")
        }
        val text = Files.readAllBytes(file)
        val sidecar = SourceFiles.pristine(file)
        val hash = metadata.pristine[ids[0]]
        if (hash != null && Files.isRegularFile(sidecar)) {
            if (SourceFiles.sha256(text) == hash) {
                return PackedArchive(Files.readAllBytes(sidecar), ids)
            }
            // The edit is what the cache is built from now, so the sidecar is not waiting on
            // anything any more.
            Files.delete(sidecar)
        }
        return PackedArchive(compile(square, text), ids)
    }

    /**
     * Null: a map file's archive is not in its path, only its name is - see *Where the archive id
     * comes from*. [archiveOf] with the tree is the answer, and [owner] comes back through it.
     */
    override fun archiveOf(path: String): Int? = null

    override fun archiveOf(directory: Path, path: String): Int? {
        val square = square(SourceFiles.guarded(path) ?: path) ?: return null
        val archive = lookup(directory).get(square.name.hashCode())
        return if (archive == ABSENT) null else archive
    }

    override fun files(directory: Path, archive: Int): List<Path> {
        val hash = hashes(directory).get(archive)
        if (hash == ABSENT) {
            return emptyList()
        }
        val square = MapSquare.of(hash) ?: return emptyList()
        val file = directory.resolve(path(square))
        if (!Files.isRegularFile(file)) {
            return emptyList()
        }
        val sidecar = SourceFiles.pristine(file)
        return if (Files.isRegularFile(sidecar)) listOf(file, sidecar) else listOf(file)
    }

    /** `<x>_<y>/<shape>.json`, the file [square] is stored as. */
    fun path(square: MapSquare.Square): String = "${square.directory}/${square.shape.file}$EXTENSION"

    /** The archive [nameHash] names, or an error naming [archive] when it names no map square. */
    private fun square(nameHash: Int, archive: Int): MapSquare.Square {
        check(nameHash != 0) { "Map archive $archive has no name hash, so nothing says which map square it is." }
        return MapSquare.of(nameHash)
            ?: error("Map archive $archive's name hash $nameHash is no map square's; index 5 holds nothing else.")
    }

    /** The square a codec relative path names, or null when the path is not one of ours. */
    private fun square(path: String): MapSquare.Square? {
        if (!path.endsWith(EXTENSION)) {
            return null
        }
        val separator = path.indexOf('/')
        if (separator <= 0 || path.indexOf('/', separator + 1) >= 0) {
            return null
        }
        val shape = MapSquare.Shape.ofFile(path.substring(separator + 1, path.length - EXTENSION.length))
            ?: return null
        val region = path.substring(0, separator)
        val coordinates = region.indexOf('_')
        if (coordinates <= 0) {
            return null
        }
        val regionX = region.substring(0, coordinates).toIntOrNull() ?: return null
        val regionY = region.substring(coordinates + 1).toIntOrNull() ?: return null
        if (regionX !in 0..0xff || regionY !in 0..0xff) {
            return null
        }
        return MapSquare.Square(shape, regionX, regionY)
    }

    /** [data] as the text of [square]'s file. */
    private fun write(square: MapSquare.Square, data: ByteArray): ByteArray = when (square.shape) {
        MapSquare.Shape.TERRAIN, MapSquare.Shape.UNDERWATER_TERRAIN ->
            MapJson.writeTerrain(MapTileDecoder.decodeArchive(data, square.region, square.shape.levels), square)
        MapSquare.Shape.LOCATIONS, MapSquare.Shape.UNDERWATER_LOCATIONS ->
            MapJson.writeLocs(locations(data, square), square)
        MapSquare.Shape.NPCS -> MapJson.writeNpcs(MapNpcDecoder.decode(data, square.region), square)
    }

    /** [text] read back into a fresh definition and encoded, which is what a pack emits. */
    private fun compile(square: MapSquare.Square, text: ByteArray): ByteArray {
        var capacity = capacity(square)
        while (true) {
            val writer = BufferWriter(capacity)
            try {
                when (square.shape) {
                    MapSquare.Shape.TERRAIN, MapSquare.Shape.UNDERWATER_TERRAIN ->
                        with(tileEncoder) { writer.encode(MapJson.readTerrain(text, square)) }
                    MapSquare.Shape.LOCATIONS, MapSquare.Shape.UNDERWATER_LOCATIONS ->
                        with(locationEncoder) { writer.encode(MapJson.readLocs(text, square)) }
                    MapSquare.Shape.NPCS -> writer.writeSpawns(MapJson.readNpcs(text, square))
                }
                return writer.toArray()
            } catch (e: BufferOverflowException) {
                capacity *= 2
                check(capacity <= LIMIT) { "${square.name} does not encode into $LIMIT bytes." }
            }
        }
    }

    /**
     * A locations archive: every object in file order, and whatever the file has after them.
     *
     * 48 archives are padded past their last record ([MapDefinition.trailing]); the padding is
     * inside the container the client checks the CRC of, so it is kept rather than dropped.
     */
    private fun locations(data: ByteArray, square: MapSquare.Square): MapDefinition {
        val definition = MapDefinition(square.region, levels = 0)
        val decoder = object : MapObjectDecoder() {
            override fun add(
                objectId: Int,
                localX: Int,
                localY: Int,
                level: Int,
                shape: Int,
                rotation: Int,
                regionTileX: Int,
                regionTileY: Int
            ) {
                definition.objects.add(MapObject(objectId, localX, localY, level, shape, rotation))
            }
        }
        val end = decoder.decode(data, ByteArray(0), square.regionX, square.regionY)
        if (end < data.size) {
            definition.trailing = data.copyOfRange(end, data.size)
        }
        return definition
    }

    /** A first guess big enough for every archive of [square]'s shape; doubled on overflow. */
    private fun capacity(square: MapSquare.Square): Int = when (square.shape) {
        MapSquare.Shape.TERRAIN -> 128 * 1024
        MapSquare.Shape.UNDERWATER_TERRAIN -> 32 * 1024
        else -> 64 * 1024
    }

    /** Name hash to archive id, from [directory]'s `index.json`. */
    private fun lookup(directory: Path): Int2IntMap = cached(directory).names

    /** Archive id to name hash, from [directory]'s `index.json`. */
    private fun hashes(directory: Path): Int2IntMap = cached(directory).archives

    /**
     * [directory]'s `index.json`, parsed once and re-read when the file moves.
     *
     * `archiveOf` is asked once per changed path and `files` once per archive, so parsing a 7,268
     * line metadata file every time would make a status walk quadratic. The guard is the file's own
     * size and modification time, which is the same test the incremental build trusts for every
     * other input.
     */
    private fun cached(directory: Path): Lookup {
        val file = directory.resolve(SourceTree.INDEX_FILE)
        val size: Long
        val modified: Long
        try {
            size = Files.size(file)
            modified = Files.getLastModifiedTime(file).toMillis()
        } catch (e: IOException) {
            return Lookup.EMPTY
        }
        val existing = lookups[directory]
        if (existing != null && existing.size == size && existing.modified == modified) {
            return existing
        }
        val built = read(file, size, modified) ?: return Lookup.EMPTY
        lookups[directory] = built
        return built
    }

    private fun read(file: Path, size: Long, modified: Long): Lookup? {
        val metadata = try {
            IndexMetadata.read(Files.readString(file))
        } catch (e: Exception) {
            return null
        }
        val names = Int2IntOpenHashMap(metadata.archives.size)
        names.defaultReturnValue(ABSENT)
        val archives = Int2IntOpenHashMap(metadata.archives.size)
        archives.defaultReturnValue(ABSENT)
        for ((archive, entry) in metadata.archives) {
            if (entry.nameHash == 0) {
                continue
            }
            names.put(entry.nameHash, archive)
            archives.put(archive, entry.nameHash)
        }
        return Lookup(size, modified, names, archives)
    }

    /** One `index.json`'s two directions, and the stat it was read at. */
    private class Lookup(
        val size: Long,
        val modified: Long,
        val names: Int2IntMap,
        val archives: Int2IntMap
    ) {
        companion object {
            val EMPTY = Lookup(-1, -1, empty(), empty())

            private fun empty(): Int2IntMap {
                val map = Int2IntOpenHashMap(0)
                map.defaultReturnValue(ABSENT)
                return map
            }
        }
    }

    private val lookups = ConcurrentHashMap<Path, Lookup>()

    private val tileEncoder = MapTileEncoder()

    private val locationEncoder = MapObjectEncoder()

    /** What an [Int2IntMap] here answers for a key it does not hold; no archive id is negative. */
    private const val ABSENT = -1

    /** Every map file is JSON. */
    private const val EXTENSION = ".json"

    /** No map archive in 727 is anywhere near this; the biggest terrain block is 78 KB. */
    private const val LIMIT = 8 * 1024 * 1024
}
