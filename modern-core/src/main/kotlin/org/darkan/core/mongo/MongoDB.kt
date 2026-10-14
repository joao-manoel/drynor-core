package org.darkan.core.mongo

import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.kotlin.client.coroutine.MongoClient
import org.bson.codecs.configuration.CodecRegistries
import org.bson.codecs.pojo.PojoCodecProvider
import org.darkan.core.EnvVars

object MongoDB {
    private val settings = MongoClientSettings.builder()
        .applyConnectionString(ConnectionString(EnvVars.mongoUri))
        .codecRegistry(CodecRegistries.fromRegistries(
            MongoClientSettings.getDefaultCodecRegistry(),
            CodecRegistries.fromProviders(PojoCodecProvider.builder()
                .automatic(true)
                .build())
        ))
        .build()

    val client = MongoClient.create(settings)
    val database = client.getDatabase(ConnectionString(EnvVars.mongoUri).database ?: "darkan-server")
}

