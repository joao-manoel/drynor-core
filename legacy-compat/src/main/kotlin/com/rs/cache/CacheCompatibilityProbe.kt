package com.rs.cache

import com.rs.cache.loaders.ItemDefinitions

/** Explicit smoke gate for source-built caches consumed by the legacy core. */
object CacheCompatibilityProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        Cache.init(args.firstOrNull() ?: "../cache/")
        val store = Cache.STORE
        check(store.indices.size >= 36) { "Missing revision-727 cache indices" }
        for (index in listOf(IndexType.INTERFACES, IndexType.CS2_SCRIPTS, IndexType.MAPS, IndexType.ITEMS)) {
            val table = requireNotNull(store.getIndex(index)) { "Missing index $index" }
            check(table.validArchivesCount > 0) { "Empty index $index" }
            println("$index: ${table.validArchivesCount} archives")
        }
        val script = store.getIndex(IndexType.CS2_SCRIPTS).getFile(3940)
        check(script != null && script.isNotEmpty()) { "Missing name-selection CS2 3940" }
        val item = ItemDefinitions.getDefs(1351)
        check(!item.name.isNullOrBlank() && item.name != "null") { "Failed to decode item 1351" }
        println("Legacy item decoder: ${item.name}")
        println("Legacy cache compatibility smoke passed")
    }
}
