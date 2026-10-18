package org.drynor.core.cache

import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Config
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.config.data.InventoryDefinition
import world.gregs.voidps.cache.config.decoder.InventoryDecoder
import java.util.concurrent.ConcurrentHashMap

/** Inventory templates used by cache-driven selection interfaces. */
object InventoryDefinitions {
    private val definitions = ConcurrentHashMap<Int, InventoryDefinition>()

    @JvmStatic fun getDefs(id: Int): InventoryDefinition {
        require(id in 0..65535)
        return definitions.computeIfAbsent(id) {
            InventoryDefinition(id).also { definition ->
                Cache.get().data(Index.CONFIGS, Config.INVENTORIES, id)?.let { bytes ->
                    InventoryDecoder().readLoop(definition, BufferReader(bytes))
                }
            }
        }
    }
}
