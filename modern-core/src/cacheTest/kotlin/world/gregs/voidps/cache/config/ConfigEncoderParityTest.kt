package world.gregs.voidps.cache.config

import org.darkan.core.EnvVars
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Definition
import world.gregs.voidps.cache.DefinitionDecoder
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.OpcodeEncoder
import world.gregs.voidps.cache.config.decoder.ClientVariableParameterDecoder
import world.gregs.voidps.cache.config.decoder.HitSplatDecoder
import world.gregs.voidps.cache.config.decoder.IdentityKitDecoder
import world.gregs.voidps.cache.config.decoder.InventoryDecoder
import world.gregs.voidps.cache.config.decoder.MapSceneDecoder
import world.gregs.voidps.cache.config.decoder.OverlayDecoder
import world.gregs.voidps.cache.config.decoder.PlayerVariableParameterDecoder
import world.gregs.voidps.cache.config.decoder.QuestDecoder
import world.gregs.voidps.cache.config.decoder.UnderlayDecoder
import world.gregs.voidps.cache.config.decoder.WorldMapInfoDecoder
import world.gregs.voidps.cache.config.encoder.ClientVariableParameterEncoder
import world.gregs.voidps.cache.config.encoder.HitSplatEncoder
import world.gregs.voidps.cache.config.encoder.IdentityKitEncoder
import world.gregs.voidps.cache.config.encoder.InventoryEncoder
import world.gregs.voidps.cache.config.encoder.MapSceneEncoder
import world.gregs.voidps.cache.config.encoder.OverlayEncoder
import world.gregs.voidps.cache.config.encoder.PlayerVariableParameterEncoder
import world.gregs.voidps.cache.config.encoder.QuestEncoder
import world.gregs.voidps.cache.config.encoder.UnderlayEncoder
import world.gregs.voidps.cache.config.encoder.WorldMapInfoEncoder
import world.gregs.voidps.cache.definition.OpcodeOrdered
import world.gregs.voidps.cache.definition.decoder.BASDecoder
import world.gregs.voidps.cache.definition.decoder.ClanVarDecoder
import world.gregs.voidps.cache.definition.decoder.ParamDecoder
import world.gregs.voidps.cache.definition.decoder.StructDecoder
import world.gregs.voidps.cache.definition.encoder.BASEncoder
import world.gregs.voidps.cache.definition.encoder.ClanVarEncoder
import world.gregs.voidps.cache.definition.encoder.ParamEncoder
import world.gregs.voidps.cache.definition.encoder.StructEncoder
import java.io.File
import kotlin.test.Test
import kotlin.test.fail

/**
 * Decodes every file of every decoded index 2 (`config`) archive, encodes the definition straight
 * back and compares the result to the shipped bytes.
 *
 * This is the bar the cache source tree's `DefinitionJsonCodec` needs: a type whose
 * `encode(decode(bytes)) == bytes` for every file can be stored as editable JSON with no pristine
 * copy beside it. Anything less and the source tree has to keep the original bytes, so byte
 * identity - not field identity - is what is asserted here.
 *
 * Files are enumerated through [Cache.files], not a decoder's `size`, so an archive with holes in
 * its id space is walked exactly as the cache holds it.
 */
class ConfigEncoderParityTest {

    @Test
    fun `every decoded index 2 config file re-encodes byte for byte`() {
        val cacheDir = File(EnvVars.cachePath)
        if (!cacheDir.isDirectory) {
            println("Skipping config encoder parity; no cache at ${cacheDir.absolutePath}")
            return
        }
        val cache = Cache.get()
        val results = listOf(
            parity(cache, "underlay", 1, UnderlayDecoder(), UnderlayEncoder()),
            parity(cache, "idk", 3, IdentityKitDecoder(), IdentityKitEncoder()),
            parity(cache, "overlay", 4, OverlayDecoder(), OverlayEncoder()),
            parity(cache, "inv", 5, InventoryDecoder(), InventoryEncoder()),
            parity(cache, "param", 11, ParamDecoder(), ParamEncoder()),
            parity(cache, "varp", 16, PlayerVariableParameterDecoder(), PlayerVariableParameterEncoder()),
            parity(cache, "varc", 19, ClientVariableParameterDecoder(), ClientVariableParameterEncoder()),
            parity(cache, "struct", 26, StructDecoder(), StructEncoder()),
            parity(cache, "bas", 32, BASDecoder(), BASEncoder()),
            parity(cache, "mapscene", 34, MapSceneDecoder(), MapSceneEncoder()),
            parity(cache, "quest", 35, QuestDecoder(), QuestEncoder()),
            parity(cache, "mapelement", 36, WorldMapInfoDecoder(), WorldMapInfoEncoder()),
            parity(cache, "hitmark", 46, HitSplatDecoder(), HitSplatEncoder()),
            parity(cache, "varclan", 47, ClanVarDecoder(), ClanVarEncoder()),
        )

        println("Config encoder parity (index 2):")
        for (result in results) {
            println("  ${result.name} (archive ${result.archive}): ${result.files} files, ${result.identical} identical, ${result.differing} differing, ${result.ordered} keep an opcode order, ${result.shadowed} keep a shadowed payload")
            println(result.examples)
        }

        val failed = results.filter { it.differing != 0 }
        if (failed.isNotEmpty()) {
            val message = StringBuilder("Config encoders are not byte exact:")
            for (result in failed) {
                message.append("\n  ${result.name}: ${result.differing} of ${result.files} files differ${result.examples}")
            }
            fail(message.toString())
        }
    }

    private fun <T> parity(
        cache: Cache,
        name: String,
        archive: Int,
        decoder: DefinitionDecoder<T>,
        encoder: OpcodeEncoder<T>,
    ): Parity where T : Definition, T : OpcodeOrdered {
        val files = cache.files(Index.CONFIGS, archive)
        val definitions = decoder.create((files.maxOrNull() ?: -1) + 2)
        var identical = 0
        var differing = 0
        var ordered = 0
        var shadowed = 0
        val examples = StringBuilder()
        for (file in files) {
            val data = cache.data(Index.CONFIGS, archive, file) ?: continue
            val definition = definitions[file]
            // readLoop, never load: a codec decodes with the read path alone, and changeValues derives
            // values that are not in the file. Everything the encoder needs is recorded by readLoop.
            decoder.readLoop(definition, BufferReader(data))
            if (definition.opcodeOrder != null) {
                ordered++
            }
            if (definition.shadowedPayloads != null) {
                shadowed++
            }
            val writer = BufferWriter(CAPACITY)
            with(encoder) { writer.encode(definition) }
            val encoded = writer.toArray()
            if (encoded.contentEquals(data)) {
                identical++
                continue
            }
            differing++
            if (differing <= EXAMPLE_LIMIT) {
                val offset = (0 until minOf(encoded.size, data.size)).firstOrNull { encoded[it] != data[it] }
                    ?: minOf(encoded.size, data.size)
                examples.append("\n    $name $file: ${data.size} cache bytes, ${encoded.size} encoded")
                examples.append(", first difference at $offset in opcode ${opcodeAt(decoder, decoder.create(file + 1)[file], data, offset)}")
                examples.append(", order ${definition.opcodeOrder?.toList()}")
                examples.append("\n      cache   ${hex(data)}")
                examples.append("\n      encoded ${hex(encoded)}")
            }
        }
        return Parity(name, archive, files.size, identical, differing, ordered, shadowed, examples.toString())
    }

    /**
     * The opcode of the shipped file that owns [offset], found by walking the file with the decoder
     * again and noting where each opcode's byte sat.
     */
    private fun <T : Definition> opcodeAt(
        decoder: DefinitionDecoder<T>,
        definition: T,
        data: ByteArray,
        offset: Int,
    ): String {
        var opcode = -1
        var start = -1
        decoder.readLoopTraced(definition, BufferReader(data)) { current, position ->
            if (position <= offset) {
                opcode = current
                start = position
            }
        }
        return if (opcode == 0) "the terminator" else "$opcode (from byte $start)"
    }

    private fun hex(data: ByteArray) = data.take(HEX_LIMIT).joinToString(" ") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private class Parity(
        val name: String,
        val archive: Int,
        val files: Int,
        val identical: Int,
        val differing: Int,
        /** Files that had to keep their opcode order because it was not the canonical one. */
        val ordered: Int,
        /** Files that had to keep a payload a repeated opcode overwrote. */
        val shadowed: Int,
        val examples: String,
    )

    private companion object {
        /** The largest shipped config file is a struct of a few kb; give the writer headroom. */
        private const val CAPACITY = 64 * 1024
        private const val EXAMPLE_LIMIT = 5
        private const val HEX_LIMIT = 64
    }
}
