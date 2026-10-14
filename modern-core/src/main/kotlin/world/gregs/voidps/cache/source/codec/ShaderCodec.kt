package world.gregs.voidps.cache.source.codec

import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.cache.definition.data.ShaderProgramDefinition
import world.gregs.voidps.cache.definition.decoder.ShaderProgramDecoder
import world.gregs.voidps.cache.definition.encoder.ShaderProgramEncoder
import world.gregs.voidps.cache.source.ArchiveMetadata
import world.gregs.voidps.cache.source.SourceFiles
import world.gregs.voidps.cache.store.ArchiveGroup
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

/**
 * Index 31 as two directories of real files: `shaders/gl/basic.vertex.glsl`,
 * `shaders/dx/basic.vertex.vs_2_0.1`, and each program definition as
 * `shaders/gl/basic.json`.
 *
 * **Where the names come from.** The index's reference table names both archives and all 112 files,
 * but a hash is one way, so the names have to be recovered - and index 31 is the one index that
 * describes itself. Each archive holds a dozen *program definitions*
 * ([ShaderProgramDefinition]) which spell out a program's name and the source files its techniques
 * compile; the table hashes the **lower case** form of exactly those strings, so reading the
 * definitions of an archive recovers the names of every file in it. Nothing is stored for it: the
 * recovery runs while the archive is unpacked, from the archive's own bytes.
 *
 * The hash stays the truth. `index.json` keeps `fileNames` as the numbers, packing hashes a file's
 * path back and looks the hash up there, and a file whose hash no definition accounts for is written
 * as `<file>.dat` rather than guessed at. So a renamed file is a file the pack does not recognise,
 * never a silently re-hashed one.
 *
 * **What is stored as what.** A GLSL source is text and a Direct3D source is compiled `vs_2_0` /
 * `ps_2_0` bytecode; both are the payload verbatim under their own name, because there is nothing
 * to decode. A program definition is a small binary structure and is stored as JSON, re-encoded on
 * the way out and guarded by a `.pristine` sidecar for any file that does not come back byte for
 * byte - the same contract [DefinitionJsonCodec] makes.
 */
object ShaderCodec : SourceCodec {

    private val decoder = ShaderProgramDecoder()
    private val encoder = ShaderProgramEncoder()
    private val json by lazy { DefinitionJson.of(ShaderProgramDefinition::class.java) }

    /** The two archive names the client asks index 31 for, by hash. */
    private val archives = listOf("gl", "dx").associateBy { it.hashCode() }

    override val id: String
        get() = "shaders"

    /** JSON is not the shipped bytes, so the unpacker packs the archive back and proves it. */
    override fun exact(archive: Int): Boolean = false

    override fun unpack(directory: Path, archive: SourceArchive): UnpackedArchive {
        val folder = folder(archive.metadata.nameHash, archive.archive)
        val names = recover(archive.files)
        val files = ArrayList<SourceFile>(archive.files.size + 1)
        val pristine = HashMap<Int, String>(0)
        for ((position, file) in archive.fileIds.withIndex()) {
            val data = archive.files[position]
            val name = names[archive.metadata.fileNames[file] ?: 0]
            if (name == null) {
                files.add(SourceFile("$folder/$file${RawArchiveCodec.EXTENSION}", data))
                continue
            }
            if (!decoder.isProgram(data)) {
                files.add(SourceFile("$folder/$name", data))
                continue
            }
            val path = "$folder/$name${DefinitionLayout.EXTENSION}"
            val definition = ShaderProgramDefinition()
            decoder.read(definition, data)
            val text = json.write(definition)
            files.add(SourceFile(path, text))
            if (!compile(text).contentEquals(data)) {
                files.add(SourceFile(SourceFiles.pristine(path), data))
                pristine[file] = SourceFiles.sha256(text)
            }
        }
        return UnpackedArchive(files, pristine)
    }

    override fun pack(directory: Path, archive: Int, metadata: ArchiveMetadata): PackedArchive {
        val folder = directory.resolve(folder(metadata.nameHash, archive))
        val ids = metadata.fileNames.entries.associate { (file, hash) -> hash to file }
        val contents = HashMap<Int, ByteArray>()
        for (path in list(folder)) {
            val name = path.name
            if (SourceFiles.isPristine(name)) {
                continue
            }
            val program = name.endsWith(DefinitionLayout.EXTENSION)
            val bare = if (program) name.dropLast(DefinitionLayout.EXTENSION.length) else name
            val file = fallback(bare)
                ?: ids[bare.lowercase().hashCode()]
                ?: throw IOException(
                    "No file of archive $archive is named '$bare': ${path.toAbsolutePath()} hashes to " +
                        "${bare.lowercase().hashCode()}, which index.json's fileNames does not list."
                )
            val bytes = if (program) bytes(path, file, metadata) else Files.readAllBytes(path)
            check(contents.put(file, bytes) == null) { "Archive $archive has two files named $file." }
        }
        check(contents.isNotEmpty()) { "Archive $archive has no files in ${folder.toAbsolutePath()}." }
        val order = contents.keys.sorted()
        return PackedArchive(ArchiveGroup.join(order.map { contents.getValue(it) }), order.toIntArray())
    }

    override fun archiveOf(path: String): Int? = null

    override fun archiveOf(directory: Path, path: String): Int? {
        val separator = path.indexOf('/')
        if (separator <= 0 || path.indexOf('/', separator + 1) >= 0) {
            return null
        }
        val folder = path.substring(0, separator)
        return folder.toIntOrNull() ?: ArchiveNames.archive(directory, folder.hashCode())
    }

    override fun files(directory: Path, archive: Int): List<Path> {
        val hash = ArchiveNames.nameHash(directory, archive) ?: return emptyList()
        return list(directory.resolve(folder(hash, archive)))
    }

    /**
     * Every name the archive's own program definitions spell out, by the hash of its lower case
     * form - a program's name, its techniques' names, and each technique's two source file names.
     */
    private fun recover(files: List<ByteArray>): Map<Int, String> {
        val names = HashMap<Int, String>()
        for (data in files) {
            if (!decoder.isProgram(data)) {
                continue
            }
            val definition = ShaderProgramDefinition()
            try {
                decoder.read(definition, data)
            } catch (e: IllegalStateException) {
                // A source file whose first two bytes happen to read as 4; it names nothing.
                continue
            }
            add(names, definition.name)
            for (mapping in definition.mappings) {
                add(names, mapping.name)
                add(names, mapping.vertexSource)
                add(names, mapping.fragmentSource)
            }
        }
        return names
    }

    private fun add(names: MutableMap<Int, String>, name: String?) {
        if (name.isNullOrEmpty()) {
            return
        }
        val lower = name.lowercase()
        names[lower.hashCode()] = lower
    }

    /** One program definition's bytes: its pristine copy while the guard holds, its JSON otherwise. */
    private fun bytes(path: Path, file: Int, metadata: ArchiveMetadata): ByteArray {
        val text = Files.readAllBytes(path)
        val sidecar = SourceFiles.pristine(path)
        val hash = metadata.pristine[file]
        if (hash != null && Files.isRegularFile(sidecar)) {
            if (SourceFiles.sha256(text) == hash) {
                return Files.readAllBytes(sidecar)
            }
            Files.delete(sidecar)
        }
        return compile(text)
    }

    /** [text] read back into a fresh definition and re-encoded, which is what a pack emits. */
    private fun compile(text: ByteArray): ByteArray {
        val definition = ShaderProgramDefinition()
        json.read(text, definition)
        val writer = BufferWriter(CAPACITY)
        with(encoder) { writer.encode(definition) }
        return writer.toArray()
    }

    /** The archive's directory: its name where one is known, its id otherwise. */
    private fun folder(hash: Int, archive: Int): String = archives[hash] ?: archive.toString()

    /** The file a `<file>.dat` fallback path names, or null when the path is a real name. */
    private fun fallback(name: String): Int? {
        if (!name.endsWith(RawArchiveCodec.EXTENSION)) {
            return null
        }
        return name.dropLast(RawArchiveCodec.EXTENSION.length).toIntOrNull()
    }

    private fun list(folder: Path): List<Path> {
        if (!Files.isDirectory(folder)) {
            return emptyList()
        }
        Files.list(folder).use { stream ->
            return stream.filter { Files.isRegularFile(it) }.toList().sortedBy { it.name }
        }
    }

    /** The largest program definition in 727 is 5 KB; the writer grows nothing, so leave room. */
    private const val CAPACITY = 64 * 1024
}
