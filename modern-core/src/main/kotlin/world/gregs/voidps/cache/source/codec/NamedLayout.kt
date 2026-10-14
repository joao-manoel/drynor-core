package world.gregs.voidps.cache.source.codec

import java.nio.file.Files
import java.nio.file.Path

/**
 * One file per archive, named by the archive rather than by its id: `defaults/equipment.json`.
 *
 * The fourth [DefinitionLayout], for an index whose archives are each a thing with a name of its own -
 * index 28's six are the client's `DefaultsFile` constants - so the tree reads as what it holds instead
 * of as six numbers. Only the archive's owner knows which name belongs to which id, so [archiveOf]
 * answers null and the dispatching codec ([ArchiveDirectoryCodec]) resolves it.
 *
 * The archive holds exactly one file, id 0, and the definition id is the archive id.
 */
class NamedLayout(private val name: String) : DefinitionLayout(0) {

    override fun path(archive: Int, file: Int): String = name

    override fun archiveOf(path: String): Int? = null

    override fun fileIds(directory: Path, archive: Int): IntArray =
        if (Files.isRegularFile(directory.resolve(file(archive, 0)))) intArrayOf(0) else IntArray(0)
}
