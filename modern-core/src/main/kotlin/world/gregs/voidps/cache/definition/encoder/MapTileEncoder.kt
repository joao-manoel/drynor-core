package world.gregs.voidps.cache.definition.encoder

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.DefinitionEncoder
import world.gregs.voidps.cache.definition.data.MapDefinition
import world.gregs.voidps.cache.definition.data.MapTile
import world.gregs.voidps.cache.definition.encoder.MapEffectEncoder.writeEffects

/**
 * Encodes the terrain block of a map archive (`m{regionX}_{regionY}`), the inverse of
 * `MapTileDecoder`.
 *
 * Opcode grammar, identical in the decoder and in the client (drynor-bot `MapLoader.kt`):
 *
 * ```
 * 0             end of tile, no explicit height
 * 1 <byte>      end of tile, explicit height byte
 * 2..49 <byte>  overlay: id byte follows, shape = (opcode - 2) / 4, rotation = (opcode - 2) and 3
 * 50..81        tile settings/flags = opcode - 49
 * 82..255       underlay id = opcode - 81
 * ```
 *
 * Opcode 1 **terminates** the tile record after its height byte - it is not a field opcode followed by a
 * separate 0 terminator. Emitting both desynchronises the whole `levels * 64 * 64` stream, because the
 * following tile consumes the stray 0 as its own terminator, so every later tile decodes as some other
 * tile's data.
 *
 * ## The record order is the shipped order
 *
 * A tile's records are written **overlay, settings, underlay, terminator**, which is the order every
 * tile of every archive in the 727 cache lists them in: 49,332,224 tiles across the 2,407 surface and
 * 1,208 underwater squares use one of 16 subsequences of that order and not one of them repeats an
 * opcode class (`MapCodecParityTest` measures it). So a decoded archive re-encodes byte for byte, and
 * that is the property the cache source tree's map codec rests on. Emitting the fields in any other
 * order would still decode to the same tiles - it is what this encoder used to do - but it would rewrite
 * every byte of a region nobody edited.
 *
 * ## Height, and the one value that is not what it looks like
 *
 * A tile with [MapTile.height] `0` encodes as opcode 0, meaning "no explicit height", and the client derives
 * the height procedurally (level 0) or from the level below (levels 1-3). The client also folds an explicit
 * height byte of `1` to `0` (`MapLoader.kt`: `if (heightDelta == 1) heightDelta = 0`), which is how the
 * shipped cache stores a deliberately flat tile - all 2,441,032 explicit-height tiles that use the value 0
 * are stored as byte `1`, and no shipped tile stores an explicit `0`. So:
 *
 * - `height = 0` means "let the client derive it" and writes no height record.
 * - `height = 1` means "explicitly flat" and is what an editor should write for a flat tile.
 *
 * Consequently a tile that carries an explicit height byte of `0` (which the shipped cache never does) loses
 * its height record here. That is the only tile field this encoder cannot represent.
 *
 * ## Levels and the environment block
 *
 * [MapDefinition.levels] levels are written, so an underwater square writes the single level its file
 * holds, and [MapDefinition.effects] follows the tiles verbatim, which is what makes this the whole
 * archive rather than only its terrain.
 */
class MapTileEncoder : DefinitionEncoder<MapDefinition> {

    override fun Writer.encode(definition: MapDefinition) {
        // An unset id means the caller never populated the definition; writing the resulting empty block
        // would silently produce a valid looking archive that decodes to a blank region.
        require(definition.id != -1) { "Map definition has no region id, refusing to encode a blank terrain block." }
        require(definition.levels in 0..MapDefinition.LEVELS) {
            "Map ${definition.id} claims ${definition.levels} levels, outside 0..${MapDefinition.LEVELS}."
        }
        for (level in 0 until definition.levels) {
            for (localX in 0 until MapDefinition.SIZE) {
                for (localY in 0 until MapDefinition.SIZE) {
                    val tile = definition.getTile(localX, localY, level)
                    require(tile.underlayId in 0..MAX_UNDERLAY) { describe(definition, localX, localY, level, "underlay ${tile.underlayId} is not in 0..$MAX_UNDERLAY") }
                    require(tile.settings in 0..MAX_SETTINGS) { describe(definition, localX, localY, level, "settings ${tile.settings} is not in 0..$MAX_SETTINGS") }
                    require(consistent(tile)) { describe(definition, localX, localY, level, "overlay opcode ${tile.attrOpcode} disagrees with shape ${tile.overlayPath} rotation ${tile.overlayRotation}") }
                    val opcode = overlayOpcode(tile)
                    if (opcode != 0) {
                        require(opcode in 2..49) { describe(definition, localX, localY, level, "overlay shape ${tile.overlayPath} rotation ${tile.overlayRotation} needs opcode $opcode, outside 2..49") }
                        writeByte(opcode)
                        writeByte(tile.overlayId)
                    }
                    if (tile.settings != 0) {
                        writeByte(tile.settings + 49)
                    }
                    if (tile.underlayId != 0) {
                        writeByte(tile.underlayId + 81)
                    }
                    // Opcode 1 is itself the terminator - no trailing 0 may follow it.
                    if (tile.height != 0) {
                        writeByte(1)
                        writeByte(tile.height)
                    } else {
                        writeByte(0)
                    }
                }
            }
        }
        writeEffects(definition.effects)
    }

    /**
     * The overlay opcode to emit, or 0 for no overlay record.
     *
     * [MapTile.attrOpcode] is the opcode the tile was decoded from and is preferred so decoded tiles
     * re-encode byte for byte. Tiles built elsewhere (or by hand) only carry the unpacked
     * [MapTile.overlayPath] / [MapTile.overlayRotation], so the opcode is rebuilt from those instead -
     * without this, an overlay authored outside the decoder would be dropped silently.
     *
     * Editing a decoded tile's shape or rotation therefore has to update [MapTile.attrOpcode] too (or clear
     * it and let it be rebuilt); a stale opcode would otherwise win and write back the old shape. Rather
     * than pick a winner, a disagreement is rejected.
     */
    private fun overlayOpcode(tile: MapTile): Int = when {
        tile.attrOpcode != 0 -> tile.attrOpcode
        tile.overlayId == 0 && tile.overlayPath == 0 && tile.overlayRotation == 0 -> 0
        else -> 2 + (tile.overlayPath * 4) + tile.overlayRotation
    }

    /** Whether [MapTile.attrOpcode] still describes the tile's current shape and rotation. */
    private fun consistent(tile: MapTile) = tile.attrOpcode == 0 ||
        ((tile.attrOpcode - 2) / 4 == tile.overlayPath && (tile.attrOpcode - 2) and 3 == tile.overlayRotation)

    private fun describe(definition: MapDefinition, localX: Int, localY: Int, level: Int, message: String) =
        "Map ${definition.id} tile ($localX, $localY, $level): $message"

    private companion object {
        /** Underlay ids are carried by opcodes 82..255. */
        private const val MAX_UNDERLAY = 174

        /** Settings are carried by opcodes 50..81. */
        private const val MAX_SETTINGS = 32
    }
}
