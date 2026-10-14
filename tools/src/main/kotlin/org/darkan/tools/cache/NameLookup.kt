package org.darkan.tools.cache

import world.gregs.voidps.cache.Cache

/**
 * Thin façade over [Cache] for resolving archives and files by their RS "named hash".
 *
 * The named hash used by the RS cache is exactly the standard Kotlin/Java
 * [String.hashCode] (djb2 with a 31 multiplier), so [nameHash] simply delegates
 * to it. All lookup helpers return `-1` (for ids) or `null` (for data) when the
 * requested name/id is not present, mirroring the underlying [Cache] contract.
 */
object NameLookup {

    /** Compute the RS named hash for [name] (identical to [String.hashCode]). */
    fun nameHash(name: String): Int = name.hashCode()

    /**
     * Resolve an archive id from its [name] within [index].
     * Returns `-1` if no archive with that name hash exists.
     */
    fun archiveId(cache: Cache, index: Int, name: String): Int = cache.archiveId(index, name)

    /**
     * Resolve a file id from its [name] within [archive] of [index].
     * Returns `-1` if no file with that name hash exists.
     */
    fun fileId(cache: Cache, index: Int, archive: Int, name: String): Int = cache.fileId(index, archive, name)

    /** Get an archive's bytes by [id], or `null` if it does not exist. */
    fun archive(cache: Cache, index: Int, id: Int): ByteArray? = cache.data(index, id)

    /** Get an archive's bytes by [name], or `null` if it does not exist. */
    fun archive(cache: Cache, index: Int, name: String): ByteArray? = cache.data(index, name)

    /** Get a file's bytes by [archive] and [file] ids, or `null` if either does not exist. */
    fun file(cache: Cache, index: Int, archive: Int, file: Int): ByteArray? = cache.data(index, archive, file)

    /** Get a file's bytes by [archive] and [file] names, or `null` if either does not exist. */
    fun file(cache: Cache, index: Int, archive: String, file: String): ByteArray? = cache.data(index, archive, file)

    /**
     * Name-hash introspection for a single cache index.
     *
     * @property index the cache index id.
     * @property hasNameFlag whether the reference table's NAME_FLAG was set for this index.
     * @property archiveNameCount number of non-zero archive name hashes in the index.
     * @property fileNameCount number of non-zero file name hashes across all archives in the index.
     */
    data class IndexNames(
        val index: Int,
        val hasNameFlag: Boolean,
        val archiveNameCount: Int,
        val fileNameCount: Int
    ) {
        /** True when the index carries at least one archive name hash. */
        val hasArchiveNames get() = archiveNameCount > 0

        /** True when the index carries at least one file name hash. */
        val hasFileNames get() = fileNameCount > 0
    }

    /**
     * Scan every index in [cache] and report its name-hash support as [IndexNames].
     * Iterates [Cache.indices] so the result reflects exactly the indices the cache exposes.
     */
    fun scanNames(cache: Cache): List<IndexNames> = cache.indices().map { index ->
        IndexNames(
            index = index,
            hasNameFlag = cache.hasNameFlag(index),
            archiveNameCount = cache.archiveNameCount(index),
            fileNameCount = cache.fileNameCount(index)
        )
    }
}
