package world.gregs.voidps.cache.source

import org.darkan.core.EnvVars
import org.darkan.core.map.MapXteas
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.MapSquare
import world.gregs.voidps.cache.source.codec.SourceArchive
import world.gregs.voidps.cache.source.codec.SourceCodec
import world.gregs.voidps.cache.source.codec.SourceCodecs
import world.gregs.voidps.cache.store.ArchiveEntry
import world.gregs.voidps.cache.store.ArchiveGroup
import world.gregs.voidps.cache.store.BZip2Variant
import world.gregs.voidps.cache.store.Compression
import world.gregs.voidps.cache.store.Container
import world.gregs.voidps.cache.store.FileStore
import world.gregs.voidps.cache.store.ReferenceTable
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.name

/** What one [Unpacker.unpack] did. */
class UnpackReport(
    val indices: Int,
    val archives: Int,
    val files: Int,
    /** Files whose contents differed from what was on disk, so had to be written. */
    val written: Int,
    /** Files left over from archives that no longer exist. */
    val deleted: Int,
    /** Archives kept as container bytes because nothing could open them. */
    val opaque: Int,
    /** Archives whose codec did not reproduce the group and fell back to pristine bytes. */
    val inexact: Int,
    /** Editable files whose codec could not reproduce the shipped bytes, so a sidecar carries them. */
    val pristine: Int,
    /** Archives listed in a reference table with no container behind them. */
    val missing: Int,
    val bytes: Long,
    val millis: Long
) {
    override fun toString(): String =
        "$archives archives, $files files ($bytes bytes), $written written, $deleted deleted, " +
            "$opaque opaque, $pristine pristine, $inexact inexact, $missing missing, in ${millis}ms"
}

/**
 * Turns a packed cache into a source tree.
 *
 * Every index with a reference table gets a directory, an `index.json` holding what its files
 * cannot say, its archives laid out by the index's codec, and whatever that codec writes for the
 * index as a whole ([SourceCodec.unpackIndex]). Nothing derivable is written down:
 * CRCs, whirlpools, sizes and the file ids a codec spells out in its own layout are all recomputed
 * when the tree is packed, so they cannot rot.
 *
 * Unpacking the same cache twice writes nothing the second time. Files are compared before they
 * are written and files belonging to archives that no longer exist are deleted, so the tree is a
 * function of the cache and git sees a diff only where the cache actually changed.
 */
object Unpacker {

    /**
     * Unpack [cache] into [tree].
     *
     * @param xteas the map region keys, by region id (`regionX shl 8 or regionY`), which is how
     *   `data/map/xteaKeys.json` files them - see [mapKeys]. The `l` and `n` archives of index 5
     *   are stored decrypted with their key in the metadata; the eight whose key nobody has are
     *   kept as their container bytes instead.
     * @param threads how many archives to unpack at once. Every core by default, which is what a
     *   whole cache wants; a caller unpacking on a machine that is busy doing something else can
     *   ask for fewer.
     */
    fun unpack(
        cache: Cache,
        tree: SourceTree,
        xteas: Map<Int, IntArray> = emptyMap(),
        threads: Int = Runtime.getRuntime().availableProcessors(),
        report: (String) -> Unit = {}
    ): UnpackReport {
        val start = System.nanoTime()
        SourceFiles.write(tree.cacheFile, tree.describe().toByteArray(Charsets.UTF_8))
        val gamevals = copyGamevals(tree)
        if (gamevals > 0) {
            report("gamevals: $gamevals catalogs")
        }
        val counts = Counts()
        val pool = Executors.newFixedThreadPool(maxOf(threads, 1)) { runnable ->
            Thread(runnable, "cache-unpack").apply { isDaemon = true }
        }
        try {
            for (index in 0 until cache.indexCount()) {
                val table = cache.referenceTable(index) ?: continue
                unpackIndex(cache, tree, index, table, xteas, pool, counts, report)
                counts.indices.incrementAndGet()
            }
        } finally {
            pool.shutdown()
        }
        return counts.report(System.nanoTime() - start)
    }

    /** Every map region key `data/map/xteaKeys.json` holds, by region id, ready for [unpack]. */
    fun mapKeys(): Map<Int, IntArray> {
        if (!MapXteas.isLoaded()) {
            MapXteas.load()
        }
        val keys = HashMap<Int, IntArray>()
        for (region in MapXteas.getEncryptedRegionIds()) {
            keys[region] = MapXteas.getKeys(region) ?: continue
        }
        return keys
    }

    private fun unpackIndex(
        cache: Cache,
        tree: SourceTree,
        index: Int,
        table: ReferenceTable,
        xteas: Map<Int, IntArray>,
        pool: ExecutorService,
        counts: Counts,
        report: (String) -> Unit
    ) {
        val directory = tree.indexDirectory(index)
        Files.createDirectories(directory)
        val metadata = IndexMetadata(
            index = index,
            format = table.format,
            revision = table.revision,
            named = table.named,
            whirlpool = table.whirlpool
        )
        val tableBytes = cache.container(FileStore.REFERENCE_INDEX, index)
        if (tableBytes != null) {
            val container = Container.decode(tableBytes)
            metadata.compression = container.compression
            metadata.bzip2 = variant(container, container.data())
                ?: throw IllegalStateException("Index $index's reference table cannot be recompressed by any known bzip2.")
        }

        val names = if (index == Index.MAPS) mapNames(cache, table) else emptyMap()
        val codec = SourceCodecs.codec(index)
        val archives = ConcurrentHashMap<Int, ArchiveMetadata>(table.archiveCount)
        val expected = ConcurrentHashMap.newKeySet<String>()
        val futures = table.archives.map { entry ->
            pool.submit {
                val archive = unpackArchive(cache, directory, index, entry, names, xteas, codec, counts, expected)
                if (archive != null) {
                    archives[entry.id] = archive
                }
            }
        }
        for (future in futures) {
            future.get()
        }
        metadata.archives.putAll(archives)

        // The index's own files, if it has any: a corpus-wide answer no single archive can give.
        // They are claimed here exactly as an archive's files are, which is what keeps [prune] from
        // deleting them again a moment later.
        for (file in codec.unpackIndex(directory, index, archives.keys.sorted().toIntArray())) {
            expected.add(file.path)
            counts.files.incrementAndGet()
            counts.bytes.addAndGet(file.bytes.size.toLong())
            if (SourceFiles.write(directory.resolve(file.path), file.bytes)) {
                counts.written.incrementAndGet()
            }
        }

        expected.add(SourceTree.INDEX_FILE)
        if (tree.writeMetadata(metadata)) {
            counts.written.incrementAndGet()
        }
        counts.files.incrementAndGet()
        val deleted = prune(directory, expected)
        counts.deleted.addAndGet(deleted)
        report(
            "${IndexNames.name(index)}: ${archives.size} archives, ${expected.size - 1} files" +
                if (deleted > 0) ", $deleted deleted" else ""
        )
    }

    private fun unpackArchive(
        cache: Cache,
        directory: Path,
        index: Int,
        entry: ArchiveEntry,
        names: Map<Int, String>,
        xteas: Map<Int, IntArray>,
        codec: SourceCodec,
        counts: Counts,
        expected: MutableSet<String>
    ): ArchiveMetadata? {
        val bytes = cache.container(index, entry.id)
        if (bytes == null) {
            counts.missing.incrementAndGet()
            return null
        }
        counts.archives.incrementAndGet()
        val name = names[entry.id]
        val keys = if (name != null) xteas[regionOf(name)] else null
        val container = Container.decode(bytes, keys)
        val metadata = ArchiveMetadata(
            version = entry.version,
            compression = container.compression,
            gzipOs = container.gzipOs,
            nameHash = entry.nameHash
        )
        if (keys != null) {
            metadata.xtea = keys
        }
        val fileNames = entry.files.filter { it.nameHash != 0 }
        if (fileNames.isNotEmpty()) {
            metadata.fileNames = fileNames.associate { it.id to it.nameHash }
        }

        // A container that will not open at all, and one whose payload no encoder here can
        // reproduce, are the same problem: the bytes have to be kept as they are or parity goes.
        val group = try {
            container.data()
        } catch (e: Exception) {
            return opaque(directory, entry, bytes, metadata, counts, expected)
        }
        val bzip2 = variant(container, group)
            ?: return opaque(directory, entry, bytes, metadata, counts, expected)
        metadata.bzip2 = bzip2

        if (codec.fileIdsFromMetadata(entry.id)) {
            metadata.fileIds(entry.fileIds)
        }
        val split = ArchiveGroup.split(group, entry.fileIds)
        val source = SourceArchive(index, entry.id, name, entry.fileIds, group, split.files, split.chunks, metadata)
        val unpacked = codec.unpack(directory, source)
        if (unpacked.pristine.isNotEmpty()) {
            metadata.pristine = unpacked.pristine
            counts.pristine.addAndGet(unpacked.pristine.size)
        }
        for (file in unpacked.files) {
            expected.add(file.path)
            counts.files.incrementAndGet()
            counts.bytes.addAndGet(file.bytes.size.toLong())
            if (SourceFiles.write(directory.resolve(file.path), file.bytes)) {
                counts.written.incrementAndGet()
            }
        }
        if (!codec.exact(entry.id) && !codec.pack(directory, entry.id, metadata).group.contentEquals(group)) {
            counts.inexact.incrementAndGet()
        }
        return metadata
    }

    /**
     * An archive nothing can open - eight `l<x>_<y>` map archives are encrypted with keys that
     * were never published - is kept as the container itself, minus the version trailer the
     * metadata already holds, and packed straight back out.
     */
    private fun opaque(
        directory: Path,
        entry: ArchiveEntry,
        bytes: ByteArray,
        metadata: ArchiveMetadata,
        counts: Counts,
        expected: MutableSet<String>
    ): ArchiveMetadata {
        metadata.opaque = true
        // The container was never opened, so everything read out of its payload - the gzip OS
        // byte sits inside the encrypted region - is noise, and the key is not ours to record.
        metadata.xtea = null
        metadata.gzipOs = 0
        metadata.fileIds(entry.fileIds)
        val trailer = if (bytes.size >= 2 && (entry.version and 0xffff) ==
            (((bytes[bytes.size - 2].toInt() and 0xff) shl 8) or (bytes[bytes.size - 1].toInt() and 0xff))
        ) 2 else 0
        val path = "${entry.id}${SourceTree.OPAQUE_EXTENSION}"
        val payload = bytes.copyOf(bytes.size - trailer)
        expected.add(path)
        counts.opaque.incrementAndGet()
        counts.files.incrementAndGet()
        counts.bytes.addAndGet(payload.size.toLong())
        if (SourceFiles.write(directory.resolve(path), payload)) {
            counts.written.incrementAndGet()
        }
        return metadata
    }

    /** Which bzip2 encoder reproduces [container]'s payload, or null when neither does. */
    private fun variant(container: Container, data: ByteArray): BZip2Variant? {
        if (container.compression != Compression.BZIP2) {
            return BZip2Variant.LIBBZIP2
        }
        for (variant in BZip2Variant.entries) {
            val payload = Container.compress(data, Compression.BZIP2, container.version, 0, variant).payload
            if (payload.contentEquals(container.payload)) {
                return variant
            }
        }
        return null
    }

    /**
     * Index 5's archive names, which are the only ones in the cache that are a formula rather
     * than a catalogue - see [MapSquare] - and the only way back from an archive to the region
     * whose XTEA key opens it.
     */
    private fun mapNames(cache: Cache, table: ReferenceTable): Map<Int, String> {
        val byHash = HashMap<Int, Int>(table.archiveCount * 2)
        for (entry in table.archives) {
            byHash[entry.nameHash] = entry.id
        }
        val names = HashMap<Int, String>(table.archiveCount * 2)
        for (regionX in 0 until REGIONS) {
            for (regionY in 0 until REGIONS) {
                for (name in listOf(MapSquare.locations(regionX, regionY), MapSquare.npcs(regionX, regionY))) {
                    val archive = byHash[name.hashCode()] ?: continue
                    names[archive] = name
                }
            }
        }
        return names
    }

    /** `l73_72` and `n50_50` back to the region id their key is filed under. */
    private fun regionOf(name: String): Int {
        val separator = name.indexOf('_')
        val regionX = name.substring(1, separator).toInt()
        val regionY = name.substring(separator + 1).toInt()
        return (regionX shl 8) or regionY
    }

    /** Delete everything under [directory] that is not in [expected], and any directory it empties. */
    private fun prune(directory: Path, expected: Set<String>): Int {
        var deleted = 0
        val stale = ArrayList<Path>()
        Files.walk(directory).use { stream ->
            for (path in stream) {
                if (!Files.isRegularFile(path)) {
                    continue
                }
                val relative = directory.relativize(path).joinToString("/") { it.name }
                if (!expected.contains(relative)) {
                    stale.add(path)
                }
            }
        }
        for (path in stale) {
            SourceFiles.delete(path, directory)
            deleted++
        }
        return deleted
    }

    /** Copy the gameval catalogs in; they are cache assets and belong in the tree, not beside it. */
    private fun copyGamevals(tree: SourceTree): Int {
        val source = File(EnvVars.gamevalPath)
        if (!source.isDirectory) {
            return 0
        }
        var copied = 0
        for (file in source.listFiles() ?: emptyArray()) {
            if (!file.isFile || (!file.name.endsWith(".json") && file.name != CONTRACT)) {
                continue
            }
            SourceFiles.write(tree.gamevals.resolve(file.name), file.readBytes())
            copied++
        }
        return copied
    }

    private const val CONTRACT = "CONTRACT.md"

    /** Region coordinates are a byte each. */
    private const val REGIONS = 256

    private class Counts {
        val indices = AtomicInteger()
        val archives = AtomicInteger()
        val files = AtomicInteger()
        val written = AtomicInteger()
        val deleted = AtomicInteger()
        val opaque = AtomicInteger()
        val inexact = AtomicInteger()
        val pristine = AtomicInteger()
        val missing = AtomicInteger()
        val bytes = AtomicLong()

        fun report(nanos: Long) = UnpackReport(
            indices.get(), archives.get(), files.get(), written.get(), deleted.get(),
            opaque.get(), inexact.get(), pristine.get(), missing.get(), bytes.get(), nanos / 1_000_000
        )
    }
}
