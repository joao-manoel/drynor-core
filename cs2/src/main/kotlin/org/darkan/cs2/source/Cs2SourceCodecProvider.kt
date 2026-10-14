package org.darkan.cs2.source

import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.source.codec.SourceCodec
import world.gregs.voidps.cache.source.codec.SourceCodecProvider

/**
 * Claims index 12 for [Cs2SourceCodec] wherever `:cs2` is on the classpath.
 *
 * Declared in `META-INF/services/world.gregs.voidps.cache.source.codec.SourceCodecProvider`, so
 * the servers, the tools CLI and every test read and write the same `clientscripts/` layout
 * without any of them having to remember to register it.
 */
class Cs2SourceCodecProvider : SourceCodecProvider {

    override fun codecs(): Map<Int, SourceCodec> = mapOf(Index.CLIENT_SCRIPTS to Cs2SourceCodec)
}
