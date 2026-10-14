package org.darkan.core.mongo.collections

import com.mongodb.ErrorCategory
import com.mongodb.MongoWriteException
import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.Filters.`in`
import com.mongodb.client.model.Filters.or
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes.ascending
import com.mongodb.client.model.ReplaceOptions
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import org.darkan.core.crypt.Crypto
import org.darkan.core.formatPlayerNameForDisplay
import org.darkan.core.formatPlayerNameForProtocol
import org.darkan.core.model.Account
import org.darkan.core.mongo.MongoDB.database
import org.darkan.core.mongo.dropIndexIfPresent
import org.darkan.core.net.web.AccountCreateRequest
import org.darkan.core.net.web.ConflictException

/**
 * The account reads and writes the social layer performs, behind an interface so a test can put an
 * in-memory store in front of [Accounts] instead of a Mongo connection. Production code always goes
 * through [Accounts]; only the store underneath it is swappable.
 */
interface AccountStore {
    suspend fun find(usernameOrEmail: String): Account?
    suspend fun findByDisplayName(displayName: String): Account?
    suspend fun findByUsernames(usernames: Collection<String>): List<Account>
    suspend fun save(account: Account)
    suspend fun rename(previousUsername: String, account: Account)
    suspend fun findAll(): List<Account>
}

private val collection by lazy {
    val col = database.getCollection<Account>("accounts")
    runBlocking {
        col.createIndex(ascending(Account::username.name), IndexOptions().unique(true))
        col.createIndex(ascending(Account::email.name), IndexOptions().unique(true))
        col.createIndex(ascending(Account::displayName.name))
        col.dropIndexIfPresent("recoveryEmail_1")
    }
    col
}

/** The real store: the Mongo `accounts` collection. */
private object MongoAccounts : AccountStore {
    override suspend fun find(usernameOrEmail: String): Account? {
        val formatted = usernameOrEmail.formatPlayerNameForProtocol()
        return collection.find(
            or(
                eq(Account::username.name, formatted),
                eq(Account::email.name, formatted)
            )
        ).firstOrNull()
    }

    override suspend fun findByDisplayName(displayName: String): Account? {
        val byUsername = displayName.formatPlayerNameForProtocol()
        val byDisplay = byUsername.formatPlayerNameForDisplay()
        return collection.find(
            or(
                eq(Account::username.name, byUsername),
                eq(Account::displayName.name, displayName),
                eq(Account::displayName.name, byDisplay),
            )
        ).firstOrNull()
    }

    override suspend fun findByUsernames(usernames: Collection<String>): List<Account> {
        if (usernames.isEmpty()) return emptyList()
        val formatted = usernames.map { it.formatPlayerNameForProtocol() }
        return collection.find(`in`(Account::username.name, formatted)).toList()
    }

    override suspend fun save(account: Account) {
        collection.replaceOne(eq(Account::username.name, account.username), account, ReplaceOptions().upsert(true))
    }

    override suspend fun rename(previousUsername: String, account: Account) {
        collection.replaceOne(eq(Account::username.name, previousUsername), account)
    }

    override suspend fun findAll(): List<Account> = collection.find().toList()
}

object Accounts : AccountStore {

    /** Swapped by tests for an in-memory store; never reassigned by production code. */
    @Volatile
    var store: AccountStore = MongoAccounts

    override suspend fun find(usernameOrEmail: String): Account? = store.find(usernameOrEmail)

    override suspend fun findByDisplayName(displayName: String): Account? = store.findByDisplayName(displayName)

    override suspend fun findByUsernames(usernames: Collection<String>): List<Account> = store.findByUsernames(usernames)

    override suspend fun save(account: Account) = store.save(account)

    override suspend fun rename(previousUsername: String, account: Account) = store.rename(previousUsername, account)

    override suspend fun findAll(): List<Account> = store.findAll()

    suspend fun exists(usernameOrEmail: String): Boolean {
        val formatted = usernameOrEmail.formatPlayerNameForProtocol()
        return collection.find(
            or(
                eq(Account::username.name, formatted),
                eq(Account::email.name, formatted)
            )
        ).firstOrNull() != null
    }

    suspend fun createLobby(email: String, password: String): Account? {
        val formattedEmail = email.formatPlayerNameForProtocol()
        if (exists(formattedEmail)) return null
        return insertNew(Account(
            username = formattedEmail,
            email = formattedEmail,
            passwordHash = Crypto.hashPasswordArgon2(password)
        ))
    }

    suspend fun createBot(username: String): Account? {
        val formatted = username.formatPlayerNameForProtocol()
        if (exists(formatted)) return null
        return insertNew(Account(
            username = formatted,
            email = "$formatted@bots.darkan.org",
            passwordHash = "",
        ).apply {
            bot = true
            social.currentFriendsChat = null
        })
    }

    suspend fun create(req: AccountCreateRequest): Account? {
        val formattedUsername = req.username.formatPlayerNameForProtocol()
        val formattedEmail = req.email.formatPlayerNameForProtocol()

        val existingAccount = collection.find(
            or(
                eq(Account::username.name, formattedUsername),
                eq(Account::email.name, formattedEmail)
            )
        ).firstOrNull()
        if (existingAccount != null) throw ConflictException("Account already exists")

        return insertNew(Account(
            username = formattedUsername,
            email = formattedEmail,
            passwordHash = Crypto.hashPasswordArgon2(req.password)
        ))
    }

    private suspend fun insertNew(account: Account): Account? = try {
        collection.insertOne(account)
        account
    } catch (e: MongoWriteException) {
        if (e.error.category == ErrorCategory.DUPLICATE_KEY) null else throw e
    }
}