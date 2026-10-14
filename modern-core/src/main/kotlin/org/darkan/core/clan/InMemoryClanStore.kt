package org.darkan.core.clan

import org.darkan.core.model.Clan
import org.darkan.core.mongo.collections.ClanStore
import java.util.concurrent.ConcurrentHashMap

/** A [ClanStore] held in a map, for tests that put it behind `Clans.store` instead of Mongo. */
class InMemoryClanStore : ClanStore {
    val clans = ConcurrentHashMap<String, Clan>()

    override suspend fun find(name: String): Clan? = clans[name]

    override suspend fun save(clan: Clan) {
        clans[clan.name] = clan
    }

    override suspend fun delete(name: String) {
        clans.remove(name)
    }

    override suspend fun findAll(): List<Clan> = clans.values.toList()
}
