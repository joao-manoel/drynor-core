package world.gregs.voidps.cache.definition.decoder

import world.gregs.voidps.cache.definition.data.MapDefinition
import world.gregs.voidps.cache.definition.data.MapTile

/**
 * Decodes the terrain block of a map archive (`m{regionX}_{regionY}` in index MAPS).
 *
 * The block is a flat sequence of `levels * 64 * 64` tile records in level, localX, localY order.
 * Each record is a list of opcodes terminated by opcode 0 or 1:
 *
 * ```
 * 0             end of tile, no explicit height
 * 1 <byte>      end of tile, explicit height byte
 * 2..49 <byte>  overlay: id byte follows, shape = (opcode - 2) / 4, rotation = (opcode - 2) and 3
 * 50..81        tile settings/flags = opcode - 49
 * 82..255       underlay id = opcode - 81
 * ```
 *
 * Both 0 and 1 terminate the record - 1 is *not* a plain field opcode. See darkan-bot
 * `MapLoader.kt` (the `opcode == 1` branch reads one byte and breaks) and `MapTileEncoder` for the inverse.
 *
 * A surface square has four levels; an underwater one (`um{regionX}_{regionY}`) has a single level,
 * because the client builds the underwater scene with one plane. The count is not in the file, so
 * the caller says which shape it is holding ([world.gregs.voidps.cache.MapSquare.Shape.levels]).
 *
 * ## What the records look like in the 727 cache
 *
 * Measured over all 2,407 surface and 1,208 underwater archives (`MapCodecParityTest`): every tile
 * lists its records in the order **overlay, settings, underlay, terminator**, and not one tile of
 * the 49,332,224 repeats an opcode class. `MapTileEncoder` writes that order, which is what makes
 * re-encoding a decoded archive byte identical rather than merely equivalent.
 */
object MapTileDecoder {

    private val indices = IntArray(MapDefinition.LEVELS * MapDefinition.SIZE * MapDefinition.SIZE)

    init {
        var i = 0
        for (level in 0 until MapDefinition.LEVELS) {
            for (localX in 0 until MapDefinition.SIZE) {
                for (localY in 0 until MapDefinition.SIZE) {
                    indices[i++] = MapDefinition.index(localX, localY, level)
                }
            }
        }
    }

    /**
     * Decodes every field of every tile into a [MapDefinition].
     *
     * The raw overlay opcode is kept in [MapTile.attrOpcode] so `MapTileEncoder` can reproduce the exact
     * byte it was decoded from; [MapTile.overlayPath] and [MapTile.overlayRotation] hold the same
     * information already unpacked.
     */
    fun decode(data: ByteArray, id: Int = -1): MapDefinition {
        val definition = MapDefinition(id)
        decode(data, definition)
        return definition
    }

    /**
     * The whole archive: its tiles and, for a terrain archive, the environment records behind them.
     *
     * This is the lossless read - nothing in the file is skipped and nothing is derived - and the
     * one [world.gregs.voidps.cache.source.codec.MapCodec] uses.
     */
    fun decodeArchive(data: ByteArray, id: Int = -1, levels: Int = MapDefinition.LEVELS): MapDefinition {
        val definition = MapDefinition(id, levels = levels)
        val end = decode(data, definition, levels)
        definition.effects.addAll(MapEffectDecoder.decode(data, end, id))
        return definition
    }

    /**
     * Decodes into an existing [definition], overwriting the tiles of every level it holds.
     *
     * Returns the offset the tile records ended at, which is where a terrain archive's environment
     * block starts and where every other shape's file ends.
     */
    fun decode(data: ByteArray, definition: MapDefinition, levels: Int = MapDefinition.LEVELS): Int {
        require(levels in 0..MapDefinition.LEVELS) { "A map square has 0 to ${MapDefinition.LEVELS} levels, not $levels." }
        definition.levels = levels
        var position = 0
        val size = data.size
        val tiles = levels * MapDefinition.SIZE * MapDefinition.SIZE
        for (tile in 0 until tiles) {
            val index = indices[tile]
            var height = 0
            var attrOpcode = 0
            var overlay = 0
            var path = 0
            var rotation = 0
            var settings = 0
            var underlay = 0
            while (position < size) {
                val opcode = data[position++].toInt() and 0xff
                if (opcode == 0) {
                    break
                }
                if (opcode == 1) {
                    height = data[position++].toInt() and 0xff
                    break
                }
                when {
                    opcode <= 49 -> {
                        attrOpcode = opcode
                        overlay = data[position++].toInt() and 0xff
                        path = (opcode - 2) / 4
                        rotation = (opcode - 2) and 3
                    }
                    opcode <= 81 -> settings = opcode - 49
                    else -> underlay = opcode - 81
                }
            }
            definition.tiles[index] = MapTile.pack(height, attrOpcode, overlay, path, rotation, settings, underlay)
        }
        return position
    }

    /**
     * Decodes only the tile settings (collision flags, bridges) into [settings], skipping every other field.
     */
    fun loadTiles(data: ByteArray, settings: ByteArray) {
        var position = 0
        val size = data.size
        for (index in indices) {
            var setting = 0
            while (position < size) {
                val config = data[position++].toInt() and 0xff
                when {
                    config == 0 -> {
                        settings[index] = setting.toByte()
                        break
                    }
                    config == 1 -> {
                        position++
                        settings[index] = setting.toByte()
                        break
                    }
                    config <= 49 -> position++
                    config <= 81 -> setting = config - 49
                }
            }
        }
    }
}
