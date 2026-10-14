package world.gregs.voidps.cache.source.codec

import world.gregs.voidps.cache.Index
import java.util.ServiceLoader
import java.util.concurrent.ConcurrentHashMap

/**
 * Which codec owns which index.
 *
 * Three layers, in order of increasing authority:
 *
 * 1. **Built ins.** The raw codecs for the indices nothing better exists for, [ConfigCodec] for index 2, a
 *    [DefinitionJsonCodec] for each of the twenty-two definition types that have a byte exact encoder
 *    ([DefinitionCodecs]), and [MiscCodecs] for the eight other indices that hold a decodable type.
 * 2. **Providers.** A module whose codec cannot live in core - the `cs2` module owns index 12, and core
 *    must not depend on `cs2` - declares it through [SourceCodecProvider] and `META-INF/services`. They
 *    are loaded once, the first time any codec is looked up, so a module is claimed by being on the
 *    classpath and nothing has to call anything.
 * 3. **Explicit registrations.** [register] replaces whatever an index had, providers included, for a
 *    test or a tool that wants a particular codec.
 *
 * Registration is a process-wide decision, so it belongs in a module's initialisation and not in the
 * middle of a build.
 */
object SourceCodecs {

    private val codecs = ConcurrentHashMap<Int, SourceCodec>()

    /** Indices somebody registered by hand, which no provider may take back. */
    private val explicit = ConcurrentHashMap.newKeySet<Int>()

    @Volatile
    private var loaded = false

    /**
     * The indices with multi-file archives in 727 that no typed codec claims. Everything else holds one
     * file per archive and gets [RawArchiveCodec], which says so loudly if that ever stops being true.
     */
    private val MULTI_FILE = intArrayOf(
        Index.WORLD_MAP,
        Index.QUICK_CHAT_MESSAGES,
        Index.QUICK_CHAT_MENUS,
        Index.PARTICLES,
        Index.BILLBOARDS,
        Index.SHADERS
    )

    init {
        reset()
    }

    /** [index]'s codec. */
    fun codec(index: Int): SourceCodec {
        if (!loaded) {
            load()
        }
        return codecs[index] ?: RawArchiveCodec
    }

    /** Give [index] a codec, replacing whatever it had - a built in or a provider's. */
    fun register(index: Int, codec: SourceCodec) {
        codecs[index] = codec
        explicit.add(index)
    }

    /** Every index that has a codec of its own, ascending. */
    fun registered(): List<Int> {
        if (!loaded) {
            load()
        }
        return codecs.keys.sorted()
    }

    /**
     * Put every index back to the codec it starts with and let the providers be discovered again. Only
     * tests need this; a module that registered a codec by hand has to register it again afterwards.
     */
    @Synchronized
    fun reset() {
        codecs.clear()
        explicit.clear()
        loaded = false
        // Forty-five unrelated definition types, one directory each, fourteen of them typed.
        codecs[Index.CONFIGS] = ConfigCodec
        // Indices whose archives hold more than one file and nothing better than bytes to say about it.
        for (index in MULTI_FILE) {
            codecs[index] = RawFilesCodec.NESTED
        }
        // The definition indices, as editable JSON.
        DefinitionCodecs.register()
        // Index 5, laid out by map square as editable JSON.
        MapCodecs.register()
        // The 2D art indices, as standard images and JSON.
        ImageCodecs.register()
        // Animation frames and skeletons, huffman, the world map, quick chat, defaults, tips, cutscenes.
        MiscCodecs.register()
        // The audio and native asset indices: synth sounds as JSON, the rest as named bytes.
        AudioCodecs.register()
        // Models, and the particle and billboard configs their footers name.
        ModelCodecs.register()
    }

    /** A built in codec: a provider may still replace it, an explicit [register] always does. */
    internal fun builtin(index: Int, codec: SourceCodec) {
        codecs[index] = codec
    }

    /**
     * Load every [SourceCodecProvider] on the classpath, once.
     *
     * After the built ins, so a provider's entry replaces one, and before the first lookup, so no build
     * ever sees half a registry. An index somebody registered by hand is left alone.
     */
    @Synchronized
    private fun load() {
        if (loaded) {
            return
        }
        for (provider in ServiceLoader.load(SourceCodecProvider::class.java)) {
            for ((index, codec) in provider.codecs()) {
                if (!explicit.contains(index)) {
                    codecs[index] = codec
                }
            }
        }
        loaded = true
    }
}
