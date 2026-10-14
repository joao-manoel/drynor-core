package world.gregs.voidps.cache.definition

import org.darkan.core.EnvVars
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Definition
import world.gregs.voidps.cache.DefinitionDecoder
import world.gregs.voidps.cache.DefinitionEncoder
import world.gregs.voidps.cache.FileCache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.AnimationBaseDefinition
import world.gregs.voidps.cache.definition.data.AnimationFrameDefinition
import world.gregs.voidps.cache.definition.data.BodyDefinition
import world.gregs.voidps.cache.definition.data.CutsceneDefinition
import world.gregs.voidps.cache.definition.data.DefaultsDefinition
import world.gregs.voidps.cache.definition.data.GameTipDefinition
import world.gregs.voidps.cache.definition.data.HuffmanTableDefinition
import world.gregs.voidps.cache.definition.data.QuickChatOptionDefinition
import world.gregs.voidps.cache.definition.data.QuickChatPhraseDefinition
import world.gregs.voidps.cache.definition.data.WorldMapDefinition
import world.gregs.voidps.cache.definition.data.WorldMapStaticElementDefinition
import world.gregs.voidps.cache.definition.decoder.AnimationBaseDecoder
import world.gregs.voidps.cache.definition.decoder.AnimationFrameDecoder
import world.gregs.voidps.cache.definition.decoder.BodyDecoder
import world.gregs.voidps.cache.definition.decoder.CutsceneDecoder
import world.gregs.voidps.cache.definition.decoder.DefaultsDecoder
import world.gregs.voidps.cache.definition.decoder.GameTipDecoder
import world.gregs.voidps.cache.definition.decoder.HuffmanTableDecoder
import world.gregs.voidps.cache.definition.decoder.QuickChatOptionDecoder
import world.gregs.voidps.cache.definition.decoder.QuickChatPhraseDecoder
import world.gregs.voidps.cache.definition.decoder.WorldMapDetailsDecoder
import world.gregs.voidps.cache.definition.decoder.WorldMapStaticElementDecoder
import world.gregs.voidps.cache.definition.encoder.AnimationBaseEncoder
import world.gregs.voidps.cache.definition.encoder.AnimationFrameEncoder
import world.gregs.voidps.cache.definition.encoder.BodyEncoder
import world.gregs.voidps.cache.definition.encoder.CutsceneEncoder
import world.gregs.voidps.cache.definition.encoder.DefaultsEncoder
import world.gregs.voidps.cache.definition.encoder.GameTipEncoder
import world.gregs.voidps.cache.definition.encoder.HuffmanTableEncoder
import world.gregs.voidps.cache.definition.encoder.QuickChatOptionEncoder
import world.gregs.voidps.cache.definition.encoder.QuickChatPhraseEncoder
import world.gregs.voidps.cache.definition.encoder.WorldMapDetailsEncoder
import world.gregs.voidps.cache.definition.encoder.WorldMapStaticElementEncoder
import world.gregs.voidps.cache.source.codec.WorldMapCodec
import world.gregs.voidps.cache.store.ArchiveGroup
import world.gregs.voidps.cache.store.Container
import world.gregs.voidps.cache.store.FileStore
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `encode(decode(bytes)) == bytes` for every file of the eight indices `MiscCodecs` claims.
 *
 * The companion of `DefinitionEncoderParityTest`, `ConfigEncoderParityTest` and
 * `InterfaceEncoderParityTest` for animation frames and skeletons, the huffman table, the world map,
 * both quick chat indices, the client defaults, the loading screens and the cutscenes. It compares against
 * the shipped bytes rather than against a re-decode, and it enumerates files through the reference table
 * rather than through a decoder's `size`, which rounds up to whole archives and invents ids.
 *
 * Where `MiscCodecTest` proves the whole trip a source tree makes, this proves the encoder alone, so a
 * failure names the definition and the byte instead of the index. `order` and `shadow` are how many files
 * have to carry [OpcodeOrdered]'s two layout fidelity fields, and are the numbers
 * `docs/cache-source.md`'s parity table quotes.
 *
 * Skipped when there is no cache to test against, like the other whole-cache tests.
 */
class MiscEncoderParityTest {

    @Test
    fun `every file of every misc index re-encodes to its own bytes`() {
        val packed = cacheDirectory() ?: return
        val cache = FileCache.load(packed.absolutePath)
        var files = 0
        var identical = 0
        try {
            println("%-22s %-6s %9s %9s %9s %9s".format("type", "index", "files", "identical", "order", "shadow"))
            for (type in types()) {
                val counts = count(cache, type)
                files += counts.files
                identical += counts.identical
                println(
                    "%-22s %-6s %9d %9d %9d %9d".format(
                        type.name, type.where, counts.files, counts.identical, counts.order, counts.shadow
                    )
                )
                assertEquals(counts.files, counts.identical, "${type.name} did not re-encode exactly")
            }
            println("%-22s %-6s %9d %9d".format("total", "", files, identical))
            assertEquals(files, identical, "no file of any of these types may differ")
            assertTrue(files > 490_000, "only $files files were compared")
        } finally {
            cache.close()
        }
    }

    /** One type, where its files are, and how to decode and encode one. */
    private class Type(
        val name: String,
        val where: String,
        val index: Int,
        /** The archives to read, or null for every archive of the index. */
        val archives: (Cache) -> IntArray,
        /** The definition id of a file of an archive. */
        val id: (archive: Int, file: Int) -> Int,
        val roundTrip: (id: Int, data: ByteArray) -> Pair<ByteArray, Definition>
    )

    private fun types(): List<Type> = listOf(
        Type(
            "animation frame", "0", Index.ANIMATION_FRAMES, { it.archiveIds(Index.ANIMATION_FRAMES) },
            { archive, file -> (archive shl AnimationFrameDecoder.BITS) or file },
            trip(AnimationFrameDecoder(), AnimationFrameEncoder()) { AnimationFrameDefinition(it) }
        ),
        Type(
            "animation base", "1", Index.ANIMATION_SKELETONS, { it.archiveIds(Index.ANIMATION_SKELETONS) },
            { archive, _ -> archive },
            trip(AnimationBaseDecoder(), AnimationBaseEncoder()) { AnimationBaseDefinition(it) }
        ),
        Type(
            "huffman", "10", Index.HUFFMAN, { it.archiveIds(Index.HUFFMAN) }, { archive, _ -> archive },
            trip(HuffmanTableDecoder(), HuffmanTableEncoder()) { HuffmanTableDefinition(it) }
        ),
        Type(
            "world map area", "23", Index.WORLD_MAP,
            { cache -> intArrayOf(cache.archiveId(Index.WORLD_MAP, WorldMapCodec.DETAILS)) },
            { _, file -> file },
            trip(WorldMapDetailsDecoder(), WorldMapDetailsEncoder()) { WorldMapDefinition(it) }
        ),
        Type(
            "world map element", "23", Index.WORLD_MAP,
            { cache ->
                cache.archiveIds(Index.WORLD_MAP)
                    .filter { WorldMapCodec.name(it).endsWith(WorldMapCodec.STATIC_ELEMENTS) }
                    .toIntArray()
            },
            { _, file -> file },
            trip(WorldMapStaticElementDecoder(), WorldMapStaticElementEncoder()) {
                WorldMapStaticElementDefinition(it)
            }
        ),
        Type(
            "quick chat option", "24, 25", -1, { IntArray(0) }, { _, file -> file },
            trip(QuickChatOptionDecoder(), QuickChatOptionEncoder()) { QuickChatOptionDefinition(it) }
        ),
        Type(
            "quick chat phrase", "24, 25", -1, { IntArray(0) }, { _, file -> file },
            trip(QuickChatPhraseDecoder(), QuickChatPhraseEncoder()) { QuickChatPhraseDefinition(it) }
        ),
        Type(
            "defaults", "28", Index.DEFAULTS,
            { cache -> cache.archiveIds(Index.DEFAULTS).filter { it != DefaultsDefinition.EQUIPMENT }.toIntArray() },
            { archive, _ -> archive },
            trip(DefaultsDecoder(), DefaultsEncoder()) { DefaultsDefinition(it) }
        ),
        Type(
            "equipment defaults", "28/6", Index.DEFAULTS,
            { intArrayOf(DefaultsDefinition.EQUIPMENT) }, { archive, _ -> archive },
            trip(BodyDecoder(), BodyEncoder()) { BodyDefinition(it) }
        ),
        Type(
            "game tip", "33", Index.GAME_TIPS, { it.archiveIds(Index.GAME_TIPS) }, { archive, _ -> archive },
            trip(GameTipDecoder(), GameTipEncoder()) { GameTipDefinition(it) }
        ),
        Type(
            "cutscene", "35", Index.CUTSCENES, { it.archiveIds(Index.CUTSCENES) }, { archive, _ -> archive },
            trip(CutsceneDecoder(), CutsceneEncoder()) { CutsceneDefinition(it) }
        )
    )

    /** Decode with the decoder's read path alone and encode; never `load`, which derives values. */
    private fun <T : Definition> trip(
        decoder: DefinitionDecoder<T>,
        encoder: DefinitionEncoder<T>,
        create: (Int) -> T
    ): (Int, ByteArray) -> Pair<ByteArray, Definition> = { id, data ->
        val definition = create(id)
        decoder.readLoop(definition, BufferReader(data))
        val writer = BufferWriter(data.size * 2 + CAPACITY)
        with(encoder) { writer.encode(definition) }
        writer.toArray() to definition
    }

    private fun count(cache: Cache, type: Type): Counts {
        val counts = Counts()
        // Both quick chat indices hold the same two archives, and the types are told apart by archive.
        val places = if (type.index >= 0) {
            type.archives(cache).map { type.index to it }
        } else {
            val archive = if (type.name.endsWith("phrase")) 1 else 0
            listOf(Index.QUICK_CHAT_MESSAGES to archive, Index.QUICK_CHAT_MENUS to archive)
        }
        for ((index, archive) in places) {
            val container = cache.container(index, archive) ?: continue
            val ids = cache.files(index, archive)
            val group = Container.decode(container).data()
            val files = ArchiveGroup.split(group, ids).files
            for (position in ids.indices) {
                val data = files[position]
                counts.files++
                val (encoded, definition) = type.roundTrip(type.id(archive, ids[position]), data)
                if (encoded.contentEquals(data)) {
                    counts.identical++
                } else {
                    val offset = (0 until minOf(data.size, encoded.size))
                        .firstOrNull { data[it] != encoded[it] } ?: -1
                    println(
                        "  ${type.name} $index/$archive/${ids[position]} differs: ${data.size} bytes " +
                            "became ${encoded.size}, first difference at $offset"
                    )
                }
                val ordered = definition as? OpcodeOrdered ?: continue
                if (ordered.opcodeOrder != null) {
                    counts.order++
                }
                if (ordered.shadowedPayloads != null) {
                    counts.shadow++
                }
            }
        }
        return counts
    }

    private class Counts {
        var files = 0
        var identical = 0
        var order = 0
        var shadow = 0
    }

    private fun cacheDirectory(): File? {
        val directory = File(EnvVars.cachePath)
        if (!File(directory, "${FileStore.CACHE_FILE_NAME}.dat2").exists()) {
            println("Skipping the misc encoder parity check; no cache at ${directory.absolutePath}")
            return null
        }
        return directory
    }

    private companion object {
        /** Headroom over the file's own size, for a writer that has to grow past it. */
        private const val CAPACITY = 64
    }
}
