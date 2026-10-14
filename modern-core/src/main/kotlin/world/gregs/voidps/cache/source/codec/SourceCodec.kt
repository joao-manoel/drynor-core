package world.gregs.voidps.cache.source.codec

import world.gregs.voidps.cache.source.ArchiveMetadata
import world.gregs.voidps.cache.source.SourceFiles
import java.nio.file.Path

/**
 * One file a codec puts in a source tree.
 *
 * [path] is relative to the index directory, always `/` separated so a tree written on one
 * operating system reads the same on another.
 */
class SourceFile(val path: String, val bytes: ByteArray)

/**
 * An archive on its way out of a packed cache, as a codec is given it.
 *
 * [group] is the decompressed archive - decrypted, and with the container's framing gone - and
 * [files] is that group already split by [world.gregs.voidps.cache.store.ArchiveGroup], parallel
 * to [fileIds]. A codec that has no use for the split can ignore it; one that has no use for the
 * chunk layout can ignore that too, which is every codec but [AnimationFrameCodec] - index 0's groups are
 * three chunks and it is the only index whose split has to come back.
 */
class SourceArchive(
    val index: Int,
    val archive: Int,
    /** The archive's name where the index has one, for codecs that lay files out by name. */
    val name: String?,
    val fileIds: IntArray,
    val group: ByteArray,
    val files: List<ByteArray>,
    val chunks: List<IntArray>,
    /** The metadata being recorded for this archive, already filled in by the unpacker. */
    val metadata: ArchiveMetadata
)

/**
 * What a codec produced for one archive.
 *
 * [pristine] names, per file id, the SHA-256 of the editable file whose original bytes the codec
 * kept beside it because it could not reproduce them - see `docs/cache-source.md`. An exact codec
 * leaves it empty.
 */
class UnpackedArchive(
    val files: List<SourceFile>,
    val pristine: Map<Int, String> = emptyMap()
)

/** The decompressed archive a codec rebuilt, and the file ids the reference table has to list. */
class PackedArchive(
    val group: ByteArray,
    val fileIds: IntArray
)

/**
 * Who a tree file belongs to, as the index's codec answers it - see [SourceCodec.owner].
 *
 * Three answers rather than two: an archive, whose rebuild the file forces; the index itself, for a
 * file that is a property of the whole corpus rather than of any one archive (the clientscript
 * project files, `cs2.d.ts` and friends); or nothing here, which is what an incremental build
 * reports as belonging to no codec.
 */
sealed interface Owner {

    /** One of archive [id]'s files: a change to it repacks that archive. */
    data class Archive(val id: Int) : Owner

    /**
     * The codec's file, but the index's rather than any archive's.
     *
     * [Unpacker][world.gregs.voidps.cache.source.Unpacker] keeps it, the build manifest tracks it
     * like any other input, and a change to it is handed to
     * [SourceCodec.packIndex] instead of repacking an archive.
     */
    data object Index : Owner

    /** Not this codec's file at all: an editor's scratch file, a stray sidecar, a typo. */
    data object None : Owner
}

/**
 * The two directions between one index's archives and the files a source tree holds them as.
 *
 * A codec owns the whole layout under its index directory: what the files are called, how many
 * there are per archive, and how a path maps back to what it belongs to. Everything it is told
 * about an archive arrives in [SourceArchive]; everything it needs at pack time it either reads
 * back off disk or takes from [ArchiveMetadata], which the unpacker filled in.
 *
 * The members come in two halves. Six are about one archive - [unpack], [pack], [files],
 * [pristineFiles], [exact], [fileIdsFromMetadata] - and the rest are about the index as a whole:
 * [unpackIndex] and [packIndex], the two hooks a codec whose files are not a function of one
 * archive needs, and [archiveOf] and [owner], which map a changed path back onto what has to be
 * rebuilt.
 *
 * Implementations must be safe to call from many threads at once: unpacking and packing both run
 * an archive per task across every core. Per-archive state is therefore not allowed, but shared
 * state a [packIndex] set up before the loop started is - it is called from one thread, before any
 * of them.
 */
interface SourceCodec {

    /** A short name for `cache status` and build reports, like `raw-archive`. */
    val id: String

    /**
     * Whether the codec reproduces its index functionally rather than byte for byte: an archive
     * whose packed bytes differ from the shipped ones is recompiled, not wrong.
     */
    val functional: Boolean
        get() = false

    /**
     * Whether packing what [unpack] wrote for [archive] reproduces the group byte for byte.
     *
     * True for the raw codecs, which keep the bytes. A codec that normalises - a decompiler that
     * drops dead code, an encoder that is not yet exact - says false, and the unpacker checks it
     * archive by archive and falls back to the pristine sidecar where it has to. It is per
     * archive because [ConfigCodec] dispatches to a different codec for each of its archives.
     */
    fun exact(archive: Int): Boolean = true

    /**
     * Whether [archive]'s file ids have to be carried in [ArchiveMetadata] because the layout
     * does not spell them out. True for the codecs that write a whole group to one file.
     */
    fun fileIdsFromMetadata(archive: Int): Boolean = false

    /**
     * Turn [archive] into files. [directory] is the index's directory; the returned paths are
     * relative to it and the caller does the writing, so nothing here touches the disk.
     */
    fun unpack(directory: Path, archive: SourceArchive): UnpackedArchive

    /**
     * The index's own files, written once after every archive of it has been unpacked. Empty by
     * default.
     *
     * For a file that is a property of the whole index rather than of any one archive: index 12's
     * five project files are like this - `vars.d.ts` only knows a variable exists because some
     * script uses it, and `cs2.d.ts` carries argument kinds learned from all 6,567 scripts at once
     * - so no archive can produce them and none should be made to.
     *
     * The paths are relative to [directory] like every other [SourceFile], and returning a file
     * here is also how a codec *claims* it: the unpacker deletes everything under the index
     * directory that neither an archive nor this call accounted for. A change to one is reported to
     * [packIndex] rather than repacking an archive, and [owner] must answer [Owner.Index] for it so
     * an incremental build files it the same way.
     *
     * Called after the loop rather than before it because that is when a corpus-wide answer is
     * available; [archives] is what was actually unpacked, ascending.
     */
    fun unpackIndex(directory: Path, index: Int, archives: IntArray): List<SourceFile> = emptyList()

    /**
     * Rebuild [archive]'s group from what is under [directory].
     *
     * [metadata] is the archive's entry in `index.json`, which is where a codec that cannot see
     * the file ids in its own layout gets them ([ArchiveMetadata.fileIds]).
     */
    fun pack(directory: Path, archive: Int, metadata: ArchiveMetadata): PackedArchive

    /**
     * Called once before [index]'s archives are packed, for a codec with shared state to set up or
     * index level files to react to. Does nothing by default.
     *
     * The CS2 compiler is why it exists: compiling anything at all needs the whole corpus analysed
     * first, which takes seconds, so the analysis has to happen once per index and not once per
     * archive - and must not happen at all for a build that packs no clientscript. Nothing calls
     * this when there is nothing to do: [archives] and [changedIndexFiles] are never both empty.
     *
     * @param archives the archives about to be packed, ascending. Every one of the index's for a
     *   full pack or a verify; only those an incremental build found changed otherwise.
     * @param changedIndexFiles the [Owner.Index] paths, relative to [directory], that an
     *   incremental build found changed or removed since the last build. Always empty for a full
     *   pack, which has nothing to compare against.
     */
    fun packIndex(directory: Path, index: Int, archives: IntArray, changedIndexFiles: List<String>) {}

    /**
     * The archive [path] - relative to the index directory, `/` separated - belongs to, or null
     * when the path is not one of this codec's. This is how an incremental build turns a changed
     * file back into the archive it has to repack.
     *
     * The path alone is all most layouts need, which is why this is the member every codec
     * implements; [archiveOf] with a directory and [owner] are the fuller questions the framework
     * actually asks, and both come back here by default.
     */
    fun archiveOf(path: String): Int?

    /**
     * The same question with the tree to look in, for a layout whose binding is inside the file.
     *
     * Index 12's is: a script's header is what binds its source to a clientscript, and it survives
     * the file being copied or moved. Delegates to [archiveOf] by default, which is right for every
     * layout that keys on the path.
     */
    fun archiveOf(directory: Path, path: String): Int? = archiveOf(path)

    /**
     * Who [path] belongs to: one of the index's archives, the index itself, or nothing here.
     *
     * The three-valued form of [archiveOf], and what [Builder][world.gregs.voidps.cache.source.Builder]
     * asks. A codec with index level files ([unpackIndex]) overrides this to claim them as
     * [Owner.Index]; everything else inherits the archive-or-nothing answer.
     */
    fun owner(directory: Path, path: String): Owner {
        val archive = archiveOf(directory, path)
        return if (archive == null) Owner.None else Owner.Archive(archive)
    }

    /** Every file under [directory] that makes up [archive], in no particular order. */
    fun files(directory: Path, archive: Int): List<Path>

    /**
     * [archive]'s pristine sidecars - the original bytes a codec kept beside a file it could not
     * reproduce, `<name>.pristine`.
     *
     * The sidecars are files of the archive like any other, so the default picks them out of
     * [files] by name; a codec that keeps them somewhere its [files] does not reach says so here.
     * For a caller that wants one archive's sidecars without walking the index - a whole tree's
     * worth, with the guards checked, is [world.gregs.voidps.cache.source.SourceStatus].
     */
    fun pristineFiles(directory: Path, archive: Int): List<Path> =
        files(directory, archive).filter { SourceFiles.isPristine(it.fileName.toString()) }
}
