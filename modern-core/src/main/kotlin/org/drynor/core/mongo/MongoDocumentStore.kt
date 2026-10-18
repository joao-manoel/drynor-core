package org.drynor.core.mongo

import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.client.MongoClients
import com.mongodb.client.MongoCollection
import org.bson.Document
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Bounded I/O admission with explicit ownership and a drain on shutdown. */
class PersistenceExecutor(capacity: Int = 64) : AutoCloseable {
    init { require(capacity > 0) }
    private val slots = Semaphore(capacity)
    private val workers = Executors.newVirtualThreadPerTaskExecutor()
    private val closed = AtomicBoolean()
    private val orderLock = Any()
    private val lanes = HashMap<String, ArrayDeque<Runnable>>()

    fun submitOrdered(key: String, task: Runnable): Boolean {
        if (closed.get() || !slots.tryAcquire()) return false
        synchronized(orderLock) {
            if (closed.get()) { slots.release(); return false }
            lanes[key]?.let { it.addLast(task); return true }
            lanes[key] = ArrayDeque<Runnable>().apply { addLast(task) }
            try {
                workers.execute {
                    while (true) {
                        val next = synchronized(orderLock) {
                            val lane = lanes.getValue(key)
                            if (lane.isEmpty()) { lanes.remove(key); null } else lane.removeFirst()
                        } ?: break
                        try { next.run() }
                        catch (failure: Exception) {
                            System.err.println("Ordered persistence task failed: ${failure.javaClass.simpleName}")
                        } finally { slots.release() }
                    }
                }
            } catch (_: RejectedExecutionException) {
                lanes.remove(key); slots.release(); return false
            }
        }
        return true
    }

    fun submit(task: Runnable): Boolean {
        if (closed.get() || !slots.tryAcquire()) return false
        try {
            workers.execute {
                try { task.run() }
                finally { slots.release() }
            }
            return true
        } catch (_: RejectedExecutionException) { slots.release(); return false }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(orderLock) { workers.shutdown() }
        if (!workers.awaitTermination(15, TimeUnit.SECONDS)) {
            workers.shutdownNow()
            workers.awaitTermination(5, TimeUnit.SECONDS)
        }
    }
}

/** Explicit database selection keeps gameplay and isolated spawn worlds on separate stores. */
class MongoDocumentStore(uri: String, databaseName: String, capacity: Int = 64) : AutoCloseable {
    init { require(uri.isNotBlank()); require(databaseName.isNotBlank()); require(capacity > 0) }
    private val settings = MongoClientSettings.builder().applyConnectionString(ConnectionString(uri))
        .timeout(10, TimeUnit.SECONDS)
        .applyToClusterSettings { it.serverSelectionTimeout(5, TimeUnit.SECONDS) }
        .applyToConnectionPoolSettings { it.maxWaitTime(5, TimeUnit.SECONDS) }
        .build()
    private val client = MongoClients.create(settings)
    val database = try { client.getDatabase(databaseName) }
        catch (failure: Exception) { client.close(); throw failure }
    private val executor = PersistenceExecutor(capacity)
    private val closed = AtomicBoolean()

    fun requireReady() { check(!closed.get()); database.runCommand(Document("ping", 1)) }
    fun bind(repository: MongoRepository) { check(!closed.get()); repository.attach(this) }
    internal fun submit(task: Runnable) = executor.submit(task)
    internal fun submitOrdered(key: String, task: Runnable) = executor.submitOrdered(key, task)
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try { executor.close() } finally { client.close() }
    }
}

/** Owns collection initialization; subclasses contain the domain queries and document codecs. */
abstract class MongoRepository(private val collectionName: String) {
    private lateinit var store: MongoDocumentStore
    lateinit var documents: MongoCollection<Document>
        private set

    internal fun attach(connection: MongoDocumentStore) {
        check(!this::store.isInitialized) { "Repository is already attached" }
        store = connection
        documents = connection.database.getCollection(collectionName)
        initializeIndexes()
    }

    abstract fun initializeIndexes()

    @JvmOverloads fun execute(task: Runnable, failed: java.util.function.Consumer<Throwable>? = null) =
        dispatch(null, task, failed)

    fun executeOrdered(key: String, task: Runnable, failed: java.util.function.Consumer<Throwable>? = null) =
        dispatch("$collectionName:$key", task, failed)

    private fun dispatch(key: String?, task: Runnable, failed: java.util.function.Consumer<Throwable>?) {
        check(this::store.isInitialized) { "Repository is not initialized" }
        fun failure(cause: Throwable) {
            System.err.println("Persistence operation failed: ${cause.javaClass.simpleName}")
            failed?.accept(cause)
        }
        val operation = Runnable { try { task.run() } catch (cause: Exception) { failure(cause) } }
        val accepted = if (key == null) store.submit(operation) else store.submitOrdered(key, operation)
        if (!accepted) failure(RejectedExecutionException("Persistence admission is closed or full"))
    }
}
