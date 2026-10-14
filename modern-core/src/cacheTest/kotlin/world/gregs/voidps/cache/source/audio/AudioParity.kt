package world.gregs.voidps.cache.source.audio

import org.darkan.core.EnvVars
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.FileCache
import world.gregs.voidps.cache.source.ArchiveMetadata
import world.gregs.voidps.cache.source.IndexNames
import world.gregs.voidps.cache.source.SourceFiles
import world.gregs.voidps.cache.source.codec.SourceArchive
import world.gregs.voidps.cache.source.codec.SourceCodec
import world.gregs.voidps.cache.store.ArchiveGroup
import world.gregs.voidps.cache.store.FileStore
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * The whole-cache measurement every audio and native asset index's parity test makes.
 *
 * One index at a time: unpack every archive with the index's own codec into a scratch directory,
 * pack it straight back out of that directory and compare the group with the one the cache holds.
 * That is exactly what [world.gregs.voidps.cache.source.Unpacker] does, minus the reference table,
 * so a codec that passes here passes a real unpack, and the counts are the ones
 * `docs/cache-source.md` publishes.
 *
 * Shared rather than repeated per index because the six codecs differ only in what they write.
 */
object AudioParity {

    /** What one index cost: archives, files written, how many packed back identically, and sidecars. */
    class Report(
        val index: Int,
        val archives: Int,
        val files: Int,
        val identical: Int,
        val pristine: Int,
        val bytes: Long,
        val millis: Long,
        /** The first few archives whose group did not come back, for a failure message. */
        val different: List<Int>
    ) {
        override fun toString(): String =
            "%-18s %6d archives %7d files %7d identical %5d pristine %9d bytes %6dms%s".format(
                IndexNames.name(index), archives, files, identical, pristine, bytes, millis,
                if (different.isEmpty()) "" else " different=$different"
            )
    }

    /** The packed cache, or null when there is none to test against. */
    fun cache(): Cache? {
        val directory = File(EnvVars.cachePath)
        if (!File(directory, "${FileStore.CACHE_FILE_NAME}.dat2").exists()) {
            println("Skipping; no cache at ${directory.absolutePath}")
            return null
        }
        return FileCache.load(directory.absolutePath)
    }

    /** A scratch directory under the build folder; a few hundred megabytes do not belong in a tmpfs. */
    fun scratch(name: String): Path {
        val directory = File("core/build/tmp").absoluteFile
        directory.mkdirs()
        return Files.createTempDirectory(directory.toPath(), name)
    }

    /**
     * Round trip every archive of [index] through [codec] under [directory].
     *
     * The archive's metadata is filled in the way the unpacker fills it - file ids where the codec
     * cannot see them, name hashes, the pristine hashes the codec asked for - so `pack` is handed
     * exactly what it would be handed by a real build.
     */
    fun roundTrip(cache: Cache, index: Int, codec: SourceCodec, directory: Path): Report {
        val start = System.nanoTime()
        val table = cache.referenceTable(index) ?: error("Index $index has no reference table.")
        var files = 0
        var identical = 0
        var pristine = 0
        var bytes = 0L
        val different = ArrayList<Int>()
        for (entry in table.archives) {
            val whole = wholeGroup(cache, index, entry.id, entry.fileIds) ?: continue
            val metadata = ArchiveMetadata(version = entry.version, nameHash = entry.nameHash)
            val fileNames = entry.files.filter { it.nameHash != 0 }
            if (fileNames.isNotEmpty()) {
                metadata.fileNames = fileNames.associate { it.id to it.nameHash }
            }
            if (codec.fileIdsFromMetadata(entry.id)) {
                metadata.fileIds(entry.fileIds)
            }
            val split = ArchiveGroup.split(whole, entry.fileIds)
            val source = SourceArchive(
                index, entry.id, null, entry.fileIds, whole, split.files, split.chunks, metadata
            )
            val unpacked = codec.unpack(directory, source)
            if (unpacked.pristine.isNotEmpty()) {
                metadata.pristine = unpacked.pristine
                pristine += unpacked.pristine.size
            }
            for (file in unpacked.files) {
                files++
                bytes += file.bytes.size.toLong()
                SourceFiles.write(directory.resolve(file.path), file.bytes)
            }
            if (codec.pack(directory, entry.id, metadata).group.contentEquals(whole)) {
                identical++
            } else if (different.size < LIMIT) {
                different.add(entry.id)
            }
        }
        return Report(
            index, table.archives.size, files, identical, pristine, bytes,
            (System.nanoTime() - start) / 1_000_000, different
        )
    }

    /** The archive's decompressed group, which for a single file archive is that file. */
    private fun wholeGroup(cache: Cache, index: Int, archive: Int, fileIds: IntArray): ByteArray? {
        if (fileIds.size == 1) {
            return cache.data(index, archive, fileIds[0])
        }
        val files = ArrayList<ByteArray>(fileIds.size)
        for (file in fileIds) {
            files.add(cache.data(index, archive, file) ?: return null)
        }
        return ArchiveGroup.join(files)
    }

    /** How many differing archives a report names. */
    private const val LIMIT = 8
}
