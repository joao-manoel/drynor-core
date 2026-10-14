package world.gregs.voidps.cache.definition.data

import world.gregs.voidps.cache.Definition

/**
 * One material of index 26 - the client's `MaterialType`.
 *
 * The whole index is a single blob holding every material as parallel columns, so a material is a
 * position in that blob rather than a file of its own; [defined] is the presence flag the blob
 * leads with, and an undefined material contributes its flag and nothing else. All 2,591
 * materials in the 727 cache are defined, and they are one to one with index 9's textures.
 *
 * The names are the client's (`MaterialTypeList`), not the obfuscated ones this used to carry.
 */
data class MaterialDefinition(
    override var id: Int = -1,
    /** Whether the blob's presence flag says this material exists at all. */
    var defined: Boolean = true,
    /** The client's `detailsOnly`: the presence byte is 0 rather than 1. */
    var detailsOnly: Boolean = false,
    /** Generate the texture at 64x64 rather than 128x128. */
    var isSmall: Boolean = false,
    /** Faces using this material are not drawn. */
    var skipTriangles: Boolean = false,
    var brightness: Byte = 0,
    var alpha: Byte = 0,
    var effectId: Byte = 0,
    var effectParam1: Byte = 0,
    /** The material's flat colour, packed hsl. */
    var colourHsl: Int = 0,
    var textureSpeedU: Byte = 0,
    var textureSpeedV: Byte = 0,
    /** The client keeps it as `aBool2087` and never reads it. */
    var unusedBoolean: Boolean = false,
    var isBrickTile: Boolean = false,
    var mipmapping: Byte = 0,
    var repeatS: Boolean = false,
    var repeatT: Boolean = false,
    var hdr: Boolean = false,
    var combineMode: Int = 0,
    var effectParam2: Int = 0,
    var effectCombiner: Int = 0
) : Definition
