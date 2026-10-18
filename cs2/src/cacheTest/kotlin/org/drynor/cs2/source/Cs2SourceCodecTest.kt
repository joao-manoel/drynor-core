package org.drynor.cs2.source

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.drynor.core.EnvVars
import org.drynor.cs2.Cs2Corpus
import org.drynor.cs2.Cs2Declarations
import org.drynor.cs2.Cs2Layout
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.FileCache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.source.ArchiveMetadata
import world.gregs.voidps.cache.source.SourceFiles
import world.gregs.voidps.cache.source.codec.Owner
import world.gregs.voidps.cache.source.codec.SourceArchive
import world.gregs.voidps.cache.source.codec.SourceCodecs
import world.gregs.voidps.cache.store.ArchiveGroup

/**
 * The parity proof for index 12 as TypeScript.
 *
 * Every one of the 6,569 clientscripts is unpacked into a temporary directory through
 * [Cs2SourceCodec] - one `.ts` each, no sidecar and no guard - and compiled straight back out of
 * it. Every one has to compile: 5,504 back to the cache's own bytes and the 1,065 the decompiler
 * normalises back to equivalent bytecode, which is what [Cs2SourceCodec.functional] declares and
 * what `cache verify` counts as recompiled. An archive the compiler cannot produce bytecode for
 * is a script lost. That is the contract the cache source tree rests on for this index
 * (`docs/cache-source.md`).
 *
 * Skips with a message when there is no cache to read.
 */
class Cs2SourceCodecTest {

    @Test
    fun `every clientscript compiles back to bytecode equivalent to what shipped`() {
        val cache = cache() ?: return
        try {
            Cs2SourceCodec.cache = { cache }
            val directory = Files.createTempDirectory("clientscripts")
            try {
                run(cache, directory)
            } finally {
                directory.toFile().deleteRecursively()
            }
        } finally {
            Cs2SourceCodec.reset()
            cache.close()
        }
    }

    private fun run(cache: Cache, directory: Path) {
        val ids = cache.archives(Index.CLIENT_SCRIPTS).sortedArray()
        assertTrue(ids.isNotEmpty(), "index ${Index.CLIENT_SCRIPTS} has no archives")

        // --- unpack -------------------------------------------------------------------------
        val metadata = HashMap<Int, ArchiveMetadata>(ids.size * 2)
        val groups = HashMap<Int, ByteArray>(ids.size * 2)
        val unpackStart = System.nanoTime()
        for (id in ids) {
            val fileIds = cache.files(Index.CLIENT_SCRIPTS, id)
            val group = cache.data(Index.CLIENT_SCRIPTS, id, fileIds[0]) ?: continue
            groups[id] = group
            val split = ArchiveGroup.split(group, fileIds)
            val entry = ArchiveMetadata()
            entry.fileIds(fileIds)
            val unpacked = Cs2SourceCodec.unpack(
                directory,
                SourceArchive(
                    Index.CLIENT_SCRIPTS, id, null, fileIds, group, split.files, split.chunks, entry
                )
            )
            assertEquals(
                listOf(Cs2Layout.pathOf(id)),
                unpacked.files.map { it.path },
                "clientscript $id is its source and nothing else"
            )
            assertEquals(emptyMap(), unpacked.pristine, "clientscript $id may record no pristine guard")
            for (file in unpacked.files) {
                val path = directory.resolve(file.path)
                Files.createDirectories(path.parent)
                Files.write(path, file.bytes)
            }
            metadata[id] = entry
        }
        val unpackMillis = (System.nanoTime() - unpackStart) / 1_000_000

        // --- the index's own files ------------------------------------------------------------
        // The project files belong to the index, not to any archive, so they come out of
        // unpackIndex - which is also what claims them, since the unpacker deletes everything
        // under the directory that neither an archive nor this call accounted for.
        val projectFiles = Cs2SourceCodec.unpackIndex(directory, Index.CLIENT_SCRIPTS, ids)
        for (file in projectFiles) {
            Files.write(directory.resolve(file.path), file.bytes)
        }
        assertEquals(
            listOf(
                Cs2Declarations.OPCODES_FILE,
                Cs2Declarations.GAMEVALS_FILE,
                Cs2Declarations.VARS_FILE,
                Cs2Corpus.TSCONFIG_FILE,
                Cs2Corpus.DEAD_CODE_FILE
            ),
            projectFiles.map { it.path },
            "unpackIndex has to write the five project files, in a fixed order"
        )
        for (file in projectFiles) {
            assertEquals(
                Owner.Index,
                Cs2SourceCodec.owner(directory, file.path),
                "${file.path} has to be claimed as the index's"
            )
        }

        // --- the layout ---------------------------------------------------------------------
        for (name in listOf(
            Cs2Declarations.OPCODES_FILE,
            Cs2Declarations.GAMEVALS_FILE,
            Cs2Declarations.VARS_FILE,
            Cs2Corpus.TSCONFIG_FILE,
            Cs2Corpus.DEAD_CODE_FILE
        )) {
            assertTrue(Files.isRegularFile(directory.resolve(name)), "$name was not written")
        }
        for (id in ids) {
            val path = Cs2Layout.pathOf(id)
            assertTrue(Files.isRegularFile(directory.resolve(path)), "clientscript $id has no $path")
            assertEquals(id, Cs2SourceCodec.archiveOf(path), "$path does not map back to $id")
            assertEquals(id, Cs2SourceCodec.archiveOf(directory, path), "$path's header does not say $id")
            assertEquals(
                listOf(directory.resolve(path)),
                Cs2SourceCodec.files(directory, id),
                "clientscript $id is made of its source alone"
            )
        }
        assertEquals(emptyList(), sidecars(directory), "unpacking writes no pristine sidecar")

        // --- a sidecar left over from an older tree is inert ----------------------------------
        val stray = SourceFiles.pristine(Cs2Layout.pathOf(ids[0]))
        Files.write(directory.resolve(stray), byteArrayOf(1, 2, 3))
        assertEquals(null, Cs2SourceCodec.archiveOf(directory, stray), "$stray belongs to no archive")
        assertEquals(Owner.None, Cs2SourceCodec.owner(directory, stray), "$stray owns nothing")
        assertEquals(
            listOf(directory.resolve(Cs2Layout.pathOf(ids[0]))),
            Cs2SourceCodec.files(directory, ids[0]),
            "$stray is not an input of clientscript ${ids[0]}"
        )

        // --- pack ---------------------------------------------------------------------------
        // Nothing in the pack may touch a cache: this is the configuration a server building a
        // tree is in, and the corpus the analysis would read is the cache the build is producing.
        Cs2SourceCodec.cache = {
            throw IllegalStateException("pack must not open a cache; the tree is the only input")
        }
        Cs2SourceCodec.packIndex(directory, Index.CLIENT_SCRIPTS, ids, emptyList())
        val packStart = System.nanoTime()
        var identical = 0
        var recompiled = 0
        val failures = ArrayList<String>()
        for (id in ids) {
            val group = groups[id] ?: continue
            val packed = try {
                Cs2SourceCodec.pack(directory, id, metadata.getValue(id))
            } catch (e: Exception) {
                failures.add("$id: ${e.message}")
                continue
            }
            if (packed.group.contentEquals(group)) identical++ else recompiled++
        }
        val packMillis = (System.nanoTime() - packStart) / 1_000_000

        println("clientscripts: ${groups.size} archives")
        println("  identical   : $identical compiled back to the cache's own bytes")
        println("  recompiled  : $recompiled compiled back to equivalent bytes")
        println("  unpack      : ${unpackMillis}ms (decompile and the declarations)")
        println("  pack        : ${packMillis}ms (compile ${groups.size})")
        assertEquals(emptyList(), failures.take(20), "clientscripts the compiler could not produce")
        assertEquals(groups.size, identical + recompiled, "every archive has to compile back")
    }

    /** Every `.pristine` file under [directory], as tree relative paths. */
    private fun sidecars(directory: Path): List<String> =
        Files.walk(directory).use { stream ->
            stream.filter { Files.isRegularFile(it) && SourceFiles.isPristine(it.fileName.toString()) }
                .map { directory.relativize(it).toString() }
                .toList()
        }

    /** Index 12 claims [Cs2SourceCodec] by being on the classpath, with nothing registering it. */
    @Test
    fun `the provider claims index 12`() {
        assertEquals(Cs2SourceCodec, SourceCodecs.codec(Index.CLIENT_SCRIPTS))
        assertTrue(SourceCodecs.registered().contains(Index.CLIENT_SCRIPTS))
    }

    /** Index 12's parity is functional, which is what lets a verify count a recompile as parity. */
    @Test
    fun `the codec declares itself functional`() {
        assertTrue(Cs2SourceCodec.functional, "index ${Index.CLIENT_SCRIPTS} is compiled on every pack")
    }

    /** Project files belong to no archive, and a script's path resolves to its id. */
    @Test
    fun `paths map to the archives they belong to`() {
        for (name in listOf("cs2.d.ts", "gamevals.d.ts", "vars.d.ts", "tsconfig.json", "dead-code.md")) {
            assertEquals(null, Cs2SourceCodec.archiveOf(name), "$name should belong to no archive")
        }
        assertEquals(null, Cs2SourceCodec.archiveOf("proc/nothing_is_called_this.ts"))
        val known = Cs2Layout.pathOf(0)
        assertEquals(0, Cs2SourceCodec.archiveOf(known), "$known is clientscript 0")
        assertEquals(0, Cs2SourceCodec.archiveOf("${Cs2Layout.LEGACY_PREFIX}0.ts"))
    }

    /** The prepacked cache, opened directly, or null with a message when it is not there. */
    private fun cache(): Cache? {
        val path = File(EnvVars.prepackedCachePath)
        if (!File(path, "main_file_cache.dat2").isFile) {
            println("Skipping: no cache at ${path.absolutePath}")
            return null
        }
        return FileCache.load(path.path)
    }
}
