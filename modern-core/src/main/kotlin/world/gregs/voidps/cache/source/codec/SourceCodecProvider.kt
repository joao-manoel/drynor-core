package world.gregs.voidps.cache.source.codec

/**
 * A module's offer of codecs for indices core cannot write one for.
 *
 * Core owns the cache format and every codec that only needs it, but not every codec can live here: the
 * client script codec is the `cs2` module's, and core must not depend on `cs2`. So a module declares its
 * codecs by implementing this and naming the implementation in
 * `META-INF/services/world.gregs.voidps.cache.source.codec.SourceCodecProvider`; [SourceCodecs] loads
 * every provider on the classpath the first time any codec is looked up, and a provider's entry wins over
 * the built-in codec for that index. An explicit [SourceCodecs.register] still wins over both, so a test
 * or a tool can override anything.
 *
 * ```
 * class Cs2SourceCodecProvider : SourceCodecProvider {
 *     override fun codecs() = mapOf(Index.CLIENT_SCRIPTS to Cs2SourceCodec)
 * }
 * ```
 *
 * The codecs must be stateless and safe to call from many threads, like every [SourceCodec], and [codecs]
 * is called once per process.
 */
interface SourceCodecProvider {

    /** The codecs this module offers, by index. */
    fun codecs(): Map<Int, SourceCodec>
}
