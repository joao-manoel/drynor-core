package world.gregs.voidps.cache.definition

import org.darkan.core.EnvVars
import org.darkan.core.map.MapXteas
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.FileCache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.MapDefinition
import world.gregs.voidps.cache.definition.data.MapObject
import world.gregs.voidps.cache.definition.data.MapTile
import world.gregs.voidps.cache.definition.decoder.MapObjectDecoder
import world.gregs.voidps.cache.definition.decoder.MapTileDecoder
import world.gregs.voidps.cache.definition.encoder.MapObjectEncoder
import world.gregs.voidps.cache.definition.encoder.MapTileEncoder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Encodes every map archive in the cache with [MapTileEncoder] / [MapObjectEncoder], decodes the result
 * straight back and compares it to what the shipped bytes decoded to.
 *
 * A map write back path (the map editor has none yet) saves a whole region block at a time, so anything the
 * encoder cannot reproduce is terrain or objects silently corrupted on save. The tile block is especially
 * unforgiving: it has no per tile framing, so a single stray byte shifts every following tile in the region.
 *
 * The bar is byte identity: the encoders write a tile's records and a location group in the order the
 * shipped files use, so re-encoding a decoded archive reproduces it exactly. That is what
 * `MapCodecParityTest` and the cache source tree's map codec rest on, so it is asserted here as well as
 * the weaker property that decoding the output reproduces every field.
 */
class MapEncoderRoundTripTest {

    @Test
    fun `every map archive terrain block round trips through the encoder`() {
        val cache = cache() ?: return
        val encoder = MapTileEncoder()
        var archives = 0
        var byteIdentical = 0
        var mismatched = 0
        val examples = StringBuilder()

        for (region in regions(cache, "m")) {
            val data = cache.data(Index.MAPS, region.archive) ?: continue
            archives++
            // The whole archive, environment block included: encoding a definition decoded without
            // it would drop the tail of every region that has one.
            val definition = MapTileDecoder.decodeArchive(data, region.id)

            val writer = BufferWriter(TILE_CAPACITY)
            with(encoder) { writer.encode(definition) }
            val encoded = writer.toArray()
            if (encoded.contentEquals(data)) {
                byteIdentical++
            }

            val decoded = MapTileDecoder.decodeArchive(encoded, region.id)
            if (!decoded.tiles.contentEquals(definition.tiles)) {
                mismatched++
                if (mismatched <= EXAMPLE_LIMIT) {
                    val index = definition.tiles.indices.first { definition.tiles[it] != decoded.tiles[it] }
                    examples.append("\n  ${region.name}: ${definition.tiles.indices.count { definition.tiles[it] != decoded.tiles[it] }} tiles differ")
                    examples.append(", first at index $index expected ${show(MapTile(definition.tiles[index]))} was ${show(MapTile(decoded.tiles[index]))}")
                }
            }
        }

        println("Terrain round trip: $archives archives (${archives * 16384} tiles)")
        println("  archives whose tiles differ after re-decoding: $mismatched")
        println("  archives byte-identical to the cache: $byteIdentical")
        if (mismatched != 0) {
            fail("Terrain encoder does not round trip: $mismatched of $archives archives decode differently$examples")
        }
        assertEquals(archives, byteIdentical, "every terrain archive must re-encode byte for byte")
    }

    @Test
    fun `every map archive object block round trips through the encoder`() {
        val cache = cache() ?: return
        MapXteas.load()
        val encoder = MapObjectEncoder()
        var archives = 0
        var byteIdentical = 0
        var mismatched = 0
        val examples = StringBuilder()

        for (region in regions(cache, "l")) {
            val data = try {
                cache.data(Index.MAPS, region.archive, 0, MapXteas.getKeys(region.id))
            } catch (e: Exception) {
                null
            } ?: continue
            if (data.isEmpty()) {
                continue
            }
            // Archives we hold no key for decrypt into noise; skip them rather than assert against garbage.
            val definition = decodeObjects(data, region.id) ?: continue
            archives++

            val writer = BufferWriter(OBJECT_CAPACITY)
            with(encoder) { writer.encode(definition) }
            val encoded = writer.toArray()
            if (encoded.contentEquals(data)) {
                byteIdentical++
            }

            val decoded = decodeObjects(encoded, region.id)
            val expected = definition.objects.map { it.packed }.sorted()
            val actual = decoded?.objects?.map { it.packed }?.sorted()
            if (expected != actual) {
                mismatched++
                if (mismatched <= EXAMPLE_LIMIT) {
                    val lost = expected.toMutableList().apply { actual?.forEach { remove(it) } }
                    examples.append("\n  ${region.name}: ${expected.size} objects encoded, ${actual?.size ?: "none"} decoded back")
                    examples.append(", first lost ${lost.take(3).map { MapObject(it) }}")
                }
            }
        }

        println("Object round trip: $archives archives")
        println("  archives whose objects differ after re-decoding: $mismatched")
        println("  archives byte-identical to the cache: $byteIdentical")
        if (mismatched != 0) {
            fail("Object encoder does not round trip: $mismatched of $archives archives decode differently$examples")
        }
        assertEquals(archives, byteIdentical, "every object archive must re-encode byte for byte")
    }

    /**
     * Opcode 1 ends the tile record, so a tile with a height must not also emit a 0 terminator. Emitting
     * both shifts every following tile in the region by one record.
     */
    @Test
    fun `a tile with a height does not shift the tiles after it`() {
        val definition = MapDefinition(id = 0)
        definition.setTile(0, 0, 0, MapTile(height = 40, underlay = 7))
        definition.setTile(0, 1, 0, MapTile(underlay = 21, settings = 2))
        definition.setTile(0, 2, 0, MapTile(height = 3, opcode = 30, overlay = 55, path = 7, rotation = 0))

        val writer = BufferWriter(TILE_CAPACITY)
        with(MapTileEncoder()) { writer.encode(definition) }
        val decoded = MapTileDecoder.decode(writer.toArray(), definition.id)

        assertEquals(show(MapTile(definition.tiles[0])), show(MapTile(decoded.tiles[0])))
        assertEquals(show(MapTile(definition.tiles[1])), show(MapTile(decoded.tiles[1])))
        assertEquals(show(MapTile(definition.tiles[2])), show(MapTile(decoded.tiles[2])))
    }

    /**
     * Tiles built outside the decoder carry an unpacked overlay shape and rotation but no opcode; the
     * overlay still has to reach the stream.
     */
    @Test
    fun `an overlay authored without a raw opcode is still encoded`() {
        val definition = MapDefinition(id = 0)
        definition.setTile(0, 0, 0, MapTile(overlay = 44, path = 3, rotation = 2))

        val writer = BufferWriter(TILE_CAPACITY)
        with(MapTileEncoder()) { writer.encode(definition) }
        val decoded = MapTileDecoder.decode(writer.toArray(), definition.id).getTile(0, 0, 0)

        assertEquals(44, decoded.overlayId)
        assertEquals(3, decoded.overlayPath)
        assertEquals(2, decoded.overlayRotation)
    }

    /**
     * A decoded tile carries both the raw overlay opcode and the shape/rotation it encodes. Changing one
     * without the other would silently write back the old shape, so it is rejected instead.
     */
    @Test
    fun `a shape edited without updating the raw opcode is rejected`() {
        val definition = MapDefinition(id = 0)
        definition.setTile(0, 0, 0, MapTile(opcode = 30, overlay = 55, path = 4, rotation = 1))

        val writer = BufferWriter(TILE_CAPACITY)
        val failure = assertFailsWith<IllegalArgumentException> {
            with(MapTileEncoder()) { writer.encode(definition) }
        }
        assertTrue(failure.message!!.contains("disagrees"), failure.message!!)
    }

    private fun decodeObjects(data: ByteArray, id: Int): MapDefinition? {
        val definition = MapDefinition(id)
        val decoder = object : MapObjectDecoder() {
            override fun add(objectId: Int, localX: Int, localY: Int, level: Int, shape: Int, rotation: Int, regionTileX: Int, regionTileY: Int) {
                definition.objects.add(MapObject(objectId, localX, localY, level, shape, rotation))
            }
        }
        return try {
            // 48 archives are padded past their last record; the padding is part of the file, so it
            // is kept rather than dropped - without it those 48 re-encode two to twenty bytes short.
            val end = decoder.decode(data, ByteArray(64 * 64 * 4), id shr 8, id and 0xff)
            if (end < data.size) {
                definition.trailing = data.copyOfRange(end, data.size)
            }
            definition
        } catch (e: Exception) {
            null
        }
    }

    private data class Region(val id: Int, val name: String, val archive: Int)

    private fun regions(cache: Cache, prefix: String): List<Region> {
        val regions = mutableListOf<Region>()
        for (x in 0 until 256) {
            for (y in 0 until 256) {
                val name = "$prefix${x}_$y"
                val archive = cache.archiveId(Index.MAPS, name)
                if (archive != -1) {
                    regions.add(Region((x shl 8) or y, name, archive))
                }
            }
        }
        return regions
    }

    /**
     * The packed cache, opened directly rather than through [Cache.get].
     *
     * `Cache.get()` is an in memory cache by default (`MEM_CACHE`), and that one decrypts the map
     * archives at load time with the keys it was built with - none - so it answers null for every
     * encrypted `l<x>_<y>`. A [FileCache] takes the keys per call, which is what lets this test
     * cover all 2,399 keyed location archives instead of the 375 that are stored in the clear.
     */
    private fun cache(): Cache? {
        val directory = File(EnvVars.cachePath)
        if (!directory.isDirectory) {
            println("Skipping map round trip; no cache at ${directory.absolutePath}")
            return null
        }
        return FileCache.load(directory.absolutePath)
    }

    private fun show(tile: MapTile) =
        "height=${tile.height} opcode=${tile.attrOpcode} overlay=${tile.overlayId} path=${tile.overlayPath} rotation=${tile.overlayRotation} settings=${tile.settings} underlay=${tile.underlayId}"

    private companion object {
        /** 16384 tiles, at most 7 bytes each. */
        private const val TILE_CAPACITY = 128 * 1024
        private const val OBJECT_CAPACITY = 1024 * 1024
        private const val EXAMPLE_LIMIT = 5
    }
}
