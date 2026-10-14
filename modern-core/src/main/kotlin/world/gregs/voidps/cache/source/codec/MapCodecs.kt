package world.gregs.voidps.cache.source.codec

import world.gregs.voidps.cache.Index

/**
 * Index 5's claim on its codec, in a file of its own so [SourceCodecs] needs one line for it.
 *
 * The same shape as [DefinitionCodecs.register]: one call from [SourceCodecs.reset], and everything
 * about which codec the maps get lives here rather than in the registry.
 */
object MapCodecs {

    /** Claim index 5 for [MapCodec]. Called by [SourceCodecs.reset]. */
    fun register() {
        SourceCodecs.builtin(Index.MAPS, MapCodec)
    }
}
