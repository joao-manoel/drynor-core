package org.drynor.core.mongo

import com.mongodb.kotlin.client.coroutine.MongoCollection
import kotlinx.coroutines.flow.toList

suspend fun MongoCollection<*>.dropIndexIfPresent(name: String) {
    if (listIndexes().toList().any { it.getString("name") == name }) dropIndex(name)
}
