package org.darkan.core.mongo.collections

import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes.ascending
import com.mongodb.client.model.ReplaceOptions
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.darkan.core.model.Clan
import org.darkan.core.mongo.MongoDB.database
import org.darkan.core.mongo.dropIndexIfPresent

/**
 * The clan reads and writes the lobby performs, behind an interface so a test can put an
 * in-memory store (`org.darkan.core.clan.InMemoryClanStore`) in front of [Clans] instead of a
 * Mongo connection. Production code always goes through [Clans]; only the store underneath it is
 * swappable.
 */
interface ClanStore {
    suspend fun find(name: String): Clan?
    suspend fun save(clan: Clan)
    suspend fun delete(name: String)
    suspend fun findAll(): List<Clan>
}

private val clanCollection by lazy {
    val col = database.getCollection<Clan>("clans")
    runBlocking {
        col.createIndex(ascending(Clan::name.name), IndexOptions().unique(true))
        col.dropIndexIfPresent("leaderUsername_1")
    }
    col
}

/** The real store: the Mongo `clans` collection. */
private object MongoClans : ClanStore {
    override suspend fun find(name: String): Clan? =
        clanCollection.find(eq(Clan::name.name, name)).firstOrNull()

    override suspend fun save(clan: Clan) {
        clanCollection.replaceOne(eq(Clan::name.name, clan.name), clan, ReplaceOptions().upsert(true))
    }

    override suspend fun delete(name: String) {
        clanCollection.deleteOne(eq(Clan::name.name, name))
    }

    override suspend fun findAll(): List<Clan> = clanCollection.find().toList()
}

object Clans : ClanStore {

    /** Swapped by tests for an in-memory store; never reassigned by production code. */
    @Volatile
    var store: ClanStore = MongoClans

    override suspend fun find(name: String): Clan? = store.find(name)

    override suspend fun save(clan: Clan) = store.save(clan)

    override suspend fun delete(name: String) = store.delete(name)

    override suspend fun findAll(): List<Clan> = store.findAll()
}
