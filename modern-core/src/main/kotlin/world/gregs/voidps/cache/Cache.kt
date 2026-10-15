package world.gregs.voidps.cache

import org.drynor.core.EnvVars
import org.drynor.core.Logger.logWarn
import org.drynor.core.cache.CacheSource
import world.gregs.voidps.cache.definition.data.AnimationDefinition
import world.gregs.voidps.cache.definition.data.BASDefinition
import world.gregs.voidps.cache.definition.data.ClanVarDefinition
import world.gregs.voidps.cache.definition.data.EnumDefinition
import world.gregs.voidps.cache.definition.data.FontDefinition
import world.gregs.voidps.cache.definition.data.InterfaceDefinition
import world.gregs.voidps.cache.definition.data.SpotAnimDefinition
import world.gregs.voidps.cache.definition.data.ItemDefinition
import world.gregs.voidps.cache.definition.data.NPCDefinition
import world.gregs.voidps.cache.definition.data.ObjectDefinition
import world.gregs.voidps.cache.definition.data.ParamDefinition
import world.gregs.voidps.cache.definition.data.StructDefinition
import world.gregs.voidps.cache.definition.data.VarBitDefinition
import world.gregs.voidps.cache.definition.decoder.AnimationDecoder
import world.gregs.voidps.cache.definition.decoder.BASDecoder
import world.gregs.voidps.cache.definition.decoder.ClanVarDecoder
import world.gregs.voidps.cache.definition.decoder.EnumDecoder
import world.gregs.voidps.cache.definition.decoder.FontDecoder
import world.gregs.voidps.cache.definition.decoder.SpotAnimDecoder
import world.gregs.voidps.cache.definition.decoder.InterfaceDecoder
import world.gregs.voidps.cache.definition.decoder.ItemDecoder
import world.gregs.voidps.cache.definition.decoder.NPCDecoder
import world.gregs.voidps.cache.definition.decoder.ObjectDecoder
import world.gregs.voidps.cache.definition.decoder.ParamDecoder
import world.gregs.voidps.cache.definition.decoder.StructDecoder
import world.gregs.voidps.cache.definition.decoder.VarBitDecoder
import world.gregs.voidps.cache.secure.Huffman
import world.gregs.voidps.cache.store.ReferenceTable
import java.io.Closeable

interface Cache : Closeable {

    val versionTable: ByteArray

    fun indexCount(): Int

    fun indices(): IntArray

    fun indexCrcs(): IntArray

    fun sector(index: Int, archive: Int): ByteArray?

    /**
     * [archive]'s container bytes, read straight through without being retained.
     *
     * [sector] memoises what it reads so JS5 can serve the same archive again for nothing; a
     * whole-cache walk - unpacking, verifying - reads every container exactly once and would
     * only be filling a cache with 876 MB it will never look at again.
     */
    fun container(index: Int, archive: Int): ByteArray? = sector(index, archive)

    fun archives(index: Int): IntArray

    fun archiveCount(index: Int): Int

    fun lastArchiveId(indexId: Int): Int

    fun archiveId(index: Int, hash: Int): Int

    fun archiveId(index: Int, name: String): Int = archiveId(index, name.hashCode())

    fun fileId(index: Int, archive: Int, hash: Int): Int

    fun fileId(index: Int, archive: Int, name: String): Int = fileId(index, archive, name.hashCode())

    fun hasNameFlag(index: Int): Boolean = false

    /**
     * Every archive id present in [index]'s reference table, ascending.
     * Empty when the index has no reference table.
     */
    fun archiveIds(index: Int): IntArray = IntArray(0)

    /**
     * Archive id to name hash for [index], for archives whose reference table entry carries a
     * name hash (the table's NAME_FLAG is set). Empty when the index stores no archive names.
     * A hash of 0 means the entry exists but has no real name.
     */
    fun archiveNameHashes(index: Int): Map<Int, Int> = emptyMap()

    fun archiveNameCount(index: Int): Int = 0

    fun fileNameCount(index: Int): Int = 0

    fun hasArchiveNames(index: Int): Boolean = archiveNameCount(index) > 0

    fun hasFileNames(index: Int): Boolean = fileNameCount(index) > 0

    /**
     * [index]'s decoded reference table, or null when the index has none.
     * The returned model is the cache's own; a writable cache mutates it as archives are written.
     */
    fun referenceTable(index: Int): ReferenceTable? = null

    /** The reference table's version for [archive], or -1 when the archive is not in the table. */
    fun archiveVersion(index: Int, archive: Int): Int = -1

    /** The reference table's CRC for [archive]'s container, or 0 when the archive is not in the table. */
    fun archiveCrc(index: Int, archive: Int): Int = 0

    fun files(index: Int, archive: Int): IntArray

    fun fileCount(indexId: Int, archiveId: Int): Int

    fun lastFileId(indexId: Int, archive: Int): Int

    fun data(index: Int, archive: Int, file: Int = 0, xtea: IntArray? = null): ByteArray?

    fun data(index: Int, name: String, xtea: IntArray? = null) = data(index, archiveId(index, name), xtea = xtea)

    fun data(index: Int, archive: String, file: String, xtea: IntArray? = null): ByteArray? {
        val archiveId = archiveId(index, archive)
        return data(index, archiveId, fileId(index, archiveId, file), xtea)
    }

    fun write(index: Int, archive: Int, file: Int, data: ByteArray, xteas: IntArray? = null)

    fun write(index: Int, archive: String, data: ByteArray, xteas: IntArray? = null)

    fun update(): Boolean

    override fun close()

    companion object {
        private const val HUFFMAN_ARCHIVE = 1
        private const val OPTIONAL_CACHE_PROPERTY = "drynor.cache.optional"

        @Volatile
        private var installed: Cache? = null

        @JvmStatic
        fun install(cache: Cache) {
            installed = cache
        }

        private val singleton: Cache get() = installed ?: configured

        private val configured: Cache by lazy {
            try {
                CacheSource.ensureBuilt()
                if (EnvVars.memCache) MemoryCache.load() else FileCache.load()
            } catch (e: Exception) {
                if (System.getProperty(OPTIONAL_CACHE_PROPERTY) != "true") {
                    throw e
                }
                logWarn("No cache data at '${EnvVars.cachePath}'; running without cache data - every definition table will be empty.", e)
                MemoryCache(Index.COUNT).apply { versionTable = ByteArray(0) }
            }
        }

        private val _huffman: Huffman by lazy {
            val data = singleton.data(Index.HUFFMAN, HUFFMAN_ARCHIVE)
            if (data == null) Huffman() else Huffman().load(data)
        }

        @JvmStatic val varbits: Array<VarBitDefinition> by lazy {
            VarBitDecoder().load(singleton)
        }

        @JvmStatic val objects: Array<ObjectDefinition> by lazy {
            ObjectDecoder(EnvVars.members).load(singleton)
        }

        @JvmStatic val items: Array<ItemDefinition> by lazy {
            ItemDecoder().load(singleton)
        }

        @JvmStatic val npcs: Array<NPCDefinition> by lazy {
            NPCDecoder(EnvVars.members).load(singleton)
        }

        @JvmStatic val animations: Array<AnimationDefinition> by lazy {
            AnimationDecoder().load(singleton)
        }

        @JvmStatic val enums: Array<EnumDefinition> by lazy {
            EnumDecoder().load(singleton)
        }

        @JvmStatic val structs: Array<StructDefinition> by lazy {
            StructDecoder().load(singleton)
        }

        @JvmStatic val params: Array<ParamDefinition> by lazy {
            ParamDecoder().load(singleton)
        }

        @JvmStatic val spotAnims: Array<SpotAnimDefinition> by lazy {
            SpotAnimDecoder().load(singleton)
        }

        @JvmStatic val bas: Array<BASDefinition> by lazy {
            BASDecoder().load(singleton)
        }

        @JvmStatic val clanVars: Array<ClanVarDefinition> by lazy {
            ClanVarDecoder().load(singleton)
        }

        @JvmStatic val clanVarSettings: Array<ClanVarDefinition> by lazy {
            ClanVarDecoder(Config.VAR_CLAN_SETTINGS, 2).load(singleton)
        }

        @JvmStatic val interfaces: Array<InterfaceDefinition> by lazy {
            InterfaceDecoder().load(singleton)
        }

        @JvmStatic val fonts: Array<FontDefinition> by lazy {
            FontDecoder().load(singleton)
        }

        @JvmStatic fun get() = singleton

        /** Legacy init method. Triggers lazy initialization of the cache. */
        @JvmStatic
        fun init(cachePath: String) {
            // The cachePath is already set via EnvVars.cachePath
            // Just trigger lazy initialization
            singleton
        }

        @JvmStatic val huffman: Huffman get() = _huffman
    }
}
