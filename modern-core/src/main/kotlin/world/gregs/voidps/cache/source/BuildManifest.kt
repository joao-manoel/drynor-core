package world.gregs.voidps.cache.source

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** What one tree file looked like the last time the cache was built from it. */
class FileState(val size: Long, val mtimeNanos: Long, val sha256: String)

/** What one archive was built into: the container's CRC and a digest of its metadata line. */
class ArchiveState(val crc: Int, val metadata: String)

/** What a tree walk found against the previous build. */
class ScanResult(
    val files: MutableMap<String, FileState>,
    /** Paths that are new or whose contents differ from the manifest. */
    val changed: Set<String>,
    /** Paths the manifest knows and the tree no longer has. */
    val removed: Set<String>,
    /** How many files had to be hashed because their size or mtime moved. */
    val hashed: Int
)

/**
 * `manifest.json` beside a packed build: what the build was made from.
 *
 * It holds the tree it was built from, every input file's size, mtime and content hash, and per
 * archive the CRC of the container it produced together with a digest of its `index.json` line.
 * That is enough for [Builder] to answer "what changed" without opening the cache: files whose
 * size and mtime are unchanged are taken on trust, everything else is hashed, and an archive
 * whose metadata digest moved is repacked even though none of its files did.
 *
 * The two bulky tables are one newline separated string each rather than a quarter of a million
 * JSON objects, which keeps a no-op build's parse in the same order of magnitude as its stat walk.
 */
class BuildManifest(
    var tree: String,
    val files: MutableMap<String, FileState> = HashMap(),
    val archives: MutableMap<Int, MutableMap<Int, ArchiveState>> = HashMap()
) {

    fun archives(index: Int): MutableMap<Int, ArchiveState> = archives.getOrPut(index) { HashMap() }

    fun write(output: File) {
        val text = StringBuilder(files.size * 110 + 256)
        text.append("{\n")
        text.append("  \"format\": ").append(FORMAT).append(",\n")
        text.append("  \"builder\": ").append(BUILDER).append(",\n")
        text.append("  \"tree\": ").append(SourceJson.quote(tree)).append(",\n")
        text.append("  \"files\": ").append(SourceJson.quote(fileTable())).append(",\n")
        text.append("  \"archives\": ").append(SourceJson.quote(archiveTable())).append('\n')
        text.append("}\n")
        Files.write(File(output, FILE).toPath(), text.toString().toByteArray(Charsets.UTF_8))
    }

    private fun fileTable(): String {
        val out = StringBuilder(files.size * 110)
        for (path in files.keys.sorted()) {
            val state = files.getValue(path)
            out.append(state.size).append(' ').append(state.mtimeNanos).append(' ')
                .append(state.sha256).append(' ').append(path).append('\n')
        }
        return out.toString()
    }

    private fun archiveTable(): String {
        val out = StringBuilder(archives.size * 64)
        for (index in archives.keys.sorted()) {
            val entries = archives.getValue(index)
            for (archive in entries.keys.sorted()) {
                val state = entries.getValue(archive)
                out.append(index).append(' ').append(archive).append(' ')
                    .append(state.crc).append(' ').append(state.metadata).append('\n')
            }
        }
        return out.toString()
    }

    companion object {
        /** The manifest's own format. A manifest from an older one is thrown away and rebuilt. */
        const val FORMAT = 1

        /** The builder's version. Bumped when a change makes old builds wrong rather than stale. */
        const val BUILDER = 1

        const val FILE = "manifest.json"

        private val json = Json { ignoreUnknownKeys = true }

        /** Read [output]'s manifest, or null when there is none this builder can use. */
        fun read(output: File): BuildManifest? {
            val file = File(output, FILE)
            if (!file.isFile) {
                return null
            }
            return try {
                val root = json.parseToJsonElement(file.readText()).jsonObject
                if (root["format"]?.jsonPrimitive?.int != FORMAT || root["builder"]?.jsonPrimitive?.int != BUILDER) {
                    return null
                }
                val manifest = BuildManifest(root["tree"]?.jsonPrimitive?.content ?: "")
                for (line in (root["files"]?.jsonPrimitive?.content ?: "").lineSequence()) {
                    if (line.isEmpty()) {
                        continue
                    }
                    val parts = line.split(' ', limit = 4)
                    manifest.files[parts[3]] = FileState(parts[0].toLong(), parts[1].toLong(), parts[2])
                }
                for (line in (root["archives"]?.jsonPrimitive?.content ?: "").lineSequence()) {
                    if (line.isEmpty()) {
                        continue
                    }
                    val parts = line.split(' ', limit = 4)
                    manifest.archives(parts[0].toInt())[parts[1].toInt()] = ArchiveState(parts[2].toInt(), parts[3])
                }
                manifest
            } catch (e: Exception) {
                null
            }
        }

        /**
         * Stat every file in [tree] and work out what moved since [previous].
         *
         * The walk is one pass with the attributes the directory listing already carries, so it
         * costs a stat per file and nothing more; only files whose size or mtime disagree with
         * the manifest are opened and hashed, in parallel.
         */
        fun scan(tree: SourceTree, previous: Map<String, FileState>, pool: ExecutorService): ScanResult {
            val capacity = previous.size.coerceAtLeast(1024)
            val paths = ArrayList<Path>(capacity)
            val names = ArrayList<String>(capacity)
            val sizes = ArrayList<Long>(capacity)
            val times = ArrayList<Long>(capacity)
            val root = tree.directory
            Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                    if (attributes.isRegularFile) {
                        paths.add(file)
                        names.add(root.relativize(file).joinToString("/") { it.toString() })
                        sizes.add(attributes.size())
                        times.add(attributes.lastModifiedTime().to(TimeUnit.NANOSECONDS))
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exception: IOException): FileVisitResult = FileVisitResult.CONTINUE
            })

            val states = arrayOfNulls<FileState>(paths.size)
            val changed = HashSet<String>()
            val suspect = ArrayList<Int>()
            for (position in paths.indices) {
                val known = previous[names[position]]
                if (known != null && known.size == sizes[position] && known.mtimeNanos == times[position]) {
                    states[position] = known
                } else {
                    suspect.add(position)
                }
            }
            val futures = suspect.chunked(BATCH).map { batch ->
                pool.submit {
                    for (position in batch) {
                        states[position] = FileState(
                            sizes[position],
                            times[position],
                            SourceFiles.sha256(paths[position])
                        )
                    }
                }
            }
            for (future in futures) {
                future.get()
            }
            for (position in suspect) {
                val known = previous[names[position]]
                if (known == null || known.sha256 != states[position]!!.sha256) {
                    changed.add(names[position])
                }
            }

            val files = HashMap<String, FileState>(paths.size * 2)
            for (position in paths.indices) {
                files[names[position]] = states[position]!!
            }
            val removed = HashSet<String>()
            for (name in previous.keys) {
                if (!files.containsKey(name)) {
                    removed.add(name)
                }
            }
            return ScanResult(files, changed, removed, suspect.size)
        }

        /** Hashing a file at a time across a pool costs more in scheduling than it saves. */
        private const val BATCH = 64
    }
}
