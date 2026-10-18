package org.drynor.core.cache

import world.gregs.voidps.cache.Cache

/** Revision 727's login cache table, including its absent-index sentinel. */
object LoginCacheChecksums {
    @JvmStatic fun get(): IntArray {
        val cache = Cache.get()
        val crcs = cache.indexCrcs()
        return IntArray(minOf(cache.indexCount(), 36)) { index ->
            if (cache.referenceTable(index) == null) -1011863738 else crcs[index]
        }
    }
}
