package org.drynor.core

import io.github.cdimascio.dotenv.Dotenv
import io.github.cdimascio.dotenv.dotenv

object EnvVars {
    private val dotenv: Dotenv = dotenv {
        ignoreIfMissing = true
    }

    private const val CACHE_SOURCE_SUBMODULE = "../cache-unpacked"

    //server settings
    val debug: Boolean = dotenv.get("DEBUG", "false").toBooleanStrict()
    val members: Boolean = dotenv.get("MEMBERS", "true").toBooleanStrict()
    val serverName: String = dotenv.get("SERVER_NAME", "Drynor")
    val multiLogWhitelist: String = dotenv.get("MULTILOG_WHITELIST", "localhost,127.0.0.1,::1,0:0:0:0:0:0:0:1")
    val multiLogLimit: Int = dotenv.get("MULTILOG_LIMIT", "3").toInt()
    val botIpWhitelist: String = dotenv.get("BOT_IP_WHITELIST", "")
    val trustedProxies: String = dotenv.get("TRUSTED_PROXIES", "")
    val js5ServerToken: String = dotenv.get("JS5_SERVER_TOKEN", "ev9+VAp5/tMKeNR/7MOuH6lKWS+rGkHK")
    val loginServerToken: String = dotenv.get("LOGIN_SERVER_TOKEN", "wwGlrZHF5gKN6D3mDdihco3oPeYN2KFybL9hUUFqOvk")
    val clientKey: String = dotenv.get("CLIENT_KEY", "29EDD9FDC775629058FBBF106C5B0E0A3A8028FE0037D1737B8EC3EA2F4E8B8FD6F54EF2F4E65862")
    val js5RsaModulus: String = dotenv.get("RSA_JS5_MODULUS", "117525752735533423040644219776209926525585489242340044375332234679786347045466594509203355398209678968096551043842518449703703964361320462967286756268851663407950384008240524570966471744081769815157355561961607944067477858512067883877129283799853947605780903005188603658779539811385137666347647991072028080201")
    val js5RsaExponent: String = dotenv.get("RSA_JS5_EXPONENT", "45769714620275867926001532284788836149236590657678028481492967724067121406860916606777808563536714166085238449913676219414798301454048585933351540049893959827785868628572203706265915752274580525376826724019249600701154664022299724373133271944352291456503171589594996734220177420375212353960806722706846977073")
    val worldRsaModulus: String = dotenv.get("RSA_WORLD_MODULUS", js5RsaModulus)
    val worldRsaExponent: String = dotenv.get("RSA_WORLD_EXPONENT", js5RsaExponent)
    val worldLoginTokenSecret: String = dotenv.get("WORLD_LOGIN_TOKEN_SECRET", loginServerToken)
    val worldLoginTokenTtlHours: Long = dotenv.get("WORLD_LOGIN_TOKEN_TTL_HOURS", "12").toLong()
    //val js5PrefetchKeys: String = dotenv.get("JS5_PREFETCH_KEYS", "1441,78700,44880,39771,363186,44375,0,16140,7316,271148,810710,216189,379672,454149,933950,21006,25367,17247,1244,1,14856,1494,119,882901,1818764,3963,3618")
    val lobbyPort: Int = dotenv.get("LOBBY_PORT", "43594").toInt()
    val worldPort: Int = dotenv.get("WORLD_PORT", "43595").toInt()
    val lobbyApiPort: Int = dotenv.get("LOBBY_API_PORT", "4040").toInt()
    //World number to list as "localhost" so developers can reach a world running on their own machine (0 = none)
    val localWorldId: Int = dotenv.get("LOCAL_WORLD_ID", "0").toInt()
    val apiKey: String = dotenv.get("API_KEY", "TEST_API_KEY")
    // The one account allowed to run the lobby's owner-tier commands (password recovery and the like),
    // and, while DEBUG is on, the only account allowed any administrative lobby feature at all: a dev
    // environment lets people grant themselves rights on their own worlds, so rights alone are no gate.
    val lobbyOwner: String = dotenv.get("LOBBY_OWNER", "trent").lowercase().replace(" ", "_")
    // The prepacked cache named in the environment; only served when no source tree is configured.
    val prepackedCachePath: String = dotenv.get("CACHE_PATH", "../cache/")
    // A source tree is only used when explicitly configured; an unpacked checkout must not
    // silently replace the packed cache currently served to clients.
    val cacheSourcePath: String? = dotenv.get("CACHE_SOURCE_PATH", null)?.takeIf { it.isNotBlank() }
    val cacheBuildPath: String = dotenv.get("CACHE_BUILD_PATH", "./data/cache")
    // The packed cache the server actually opens: the build of the source tree when one is configured
    // (org.drynor.core.cache.CacheSource builds it before Cache.get() opens it), else the prepacked one.
    val cachePath: String = if (cacheSourcePath != null) cacheBuildPath else prepackedCachePath
    val memCache: Boolean = dotenv.get("MEM_CACHE", "false").toBooleanStrict()
    // Folder of gamevals/<type>.json name catalogs (see docs/gameval.md): the source tree's gamevals/, which
    // is the only copy. Defaults to the tree named by CACHE_SOURCE_PATH, else the cache-source submodule.
    val gamevalPath: String = dotenv.get("GAMEVAL_PATH", (cacheSourcePath ?: CACHE_SOURCE_SUBMODULE) + "/gamevals")
    val majorVersion: Int = dotenv.get("MAJOR_VERSION", "727").toInt()
    val minorVersion: Int = dotenv.get("MINOR_VERSION", "1").toInt()
    val packetSizeLimit: Int = dotenv.get("PACKET_SIZE_LIMIT", "7500").toInt()
    val packetQueueCapacity: Int = dotenv.get("PACKET_QUEUE_CAPACITY", "50").toInt()
    // Per-session outbound backlog. A client that lets this many packets pile up unsent is dropped
    // rather than buffered forever. Needs headroom for the login burst, so it is far larger than
    // the inbound queue.
    val outboundQueueCapacity: Int = dotenv.get("OUTBOUND_QUEUE_CAPACITY", "4096").toInt()
    val sessionIdleTimeoutMillis: Long = dotenv.get("SESSION_IDLE_TIMEOUT_MILLIS", "12000").toLong()
    val packetValidateSizes: Boolean = dotenv.get("PACKET_VALIDATE_SIZES", debug.toString()).toBooleanStrict()
    val worldCycleNanos: Long = dotenv.get("WORLD_CYCLE_NANOS", "600000000").toLong()
    val worldCycleMillis: Long = worldCycleNanos / 1000000L
    val worldCycleWarnMillis: Long = dotenv.get("WORLD_CYCLE_WARN_MILLIS", "300").toLong()
    val staffWebhookUrl: String? = dotenv.get("STAFF_WEBHOOK_URL", null)
    val cacheThreadUsage: Double = dotenv.get("CACHE_THREAD_USAGE", "1.0").toDouble()

    val cryptoArgon2Memory: Int = dotenv.get("CRYPTO_ARGON2_MEMORY", "65536").toInt() // 64mb (1048576) for 1GB in KB
    val cryptoArgon2Iterations: Int = dotenv.get("CRYPTO_ARGON2_ITERATIONS", "5").toInt()
    val cryptoArgon2HashLength: Int = dotenv.get("CRYPTO_ARGON2_HASH_LENGTH", "32").toInt()
    val cryptoArgon2SaltLength: Int = dotenv.get("CRYPTO_ARGON2_SALT_LENGTH", "16").toInt()
    val cryptoArgon2Parallelism: Int = dotenv.get("CRYPTO_ARGON2_PARALLELISM", "4").toInt()

    val legacyCryptoSecret: String = dotenv.get("LEGACY_CRYPTO_SECRET", "secrettt")
    val legacyCryptoSalt: ByteArray = dotenv.get("LEGACY_CRYPTO_SALT", "0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0").split(",").map { it.toByte() }.toByteArray()

    //mongo settings
    val mongoUri: String = dotenv.get("MONGO_URI", "mongodb://localhost:27017/drynor-server?retryWrites=true&w=majority")

    // SocialGateway (world <-> lobby) settings. The defaults join the dev lobby as a community world
    // (docs/community-worlds.md); the lobby and official worlds set SOCIAL_GATEWAY_TOKEN to their private one.
    val socialGatewayUrl: String = dotenv.get("SOCIAL_GATEWAY_URL", "ws://127.0.0.1:4040/social/ws")
    val communityWorldToken: String = dotenv.get("COMMUNITY_WORLD_TOKEN", "drynor-community-world")
    val socialGatewayToken: String = dotenv.get("SOCIAL_GATEWAY_TOKEN", communityWorldToken)
    val communityWorld: Boolean = socialGatewayToken == communityWorldToken
    // Worlds 1 to OFFICIAL_WORLD_MAX are official; the lobby refuses them to community worlds.
    val officialWorldMax: Int = dotenv.get("OFFICIAL_WORLD_MAX", "99").toInt()
    // How often a world re-announces everyone it has in game, so lobby-side presence cannot drift
    // for longer than one interval after a dropped message or a restart on either side.
    val socialPresenceResyncSeconds: Long = dotenv.get("SOCIAL_PRESENCE_RESYNC_SECONDS", "120").toLong()
    val worldId: Int = dotenv.get("WORLD_ID", (officialWorldMax + 1).toString()).toInt()

    //Automatic updates from the CI package registry (dev servers only). BUILD_VERSION is exported by the
    //run scripts from the package they downloaded; a blank version means "not a CI build" and updates
    //to the first published one.
    val autoUpdate: Boolean = dotenv.get("AUTO_UPDATE", "false").toBooleanStrict()
    val buildVersion: String = dotenv.get("BUILD_VERSION", "")
    val autoUpdateChannel: String = dotenv.get("AUTO_UPDATE_CHANNEL", "dev")
    val autoUpdatePollSeconds: Int = dotenv.get("AUTO_UPDATE_POLL_SECONDS", "60").toInt()
    val autoUpdateCountdownMinutes: Int = dotenv.get("AUTO_UPDATE_COUNTDOWN_MINUTES", "5").toInt()
    val checkoutCommit: String = dotenv.get("CHECKOUT_COMMIT", "")
    val gitlabAccessToken: String = dotenv.get("GITLAB_ACCESS_TOKEN", "")
    val gitlabProjectId: String = dotenv.get("DRYNOR_GITLAB_PROJECT_ID", "67255206")
    val worldName: String = dotenv.get("WORLD_NAME", serverName)
    val worldHost: String = dotenv.get("WORLD_HOST", "localhost")
    val worldPublicHost: String = dotenv.get("WORLD_PUBLIC_HOST", worldHost)
    val worldActivity: String = dotenv.get("WORLD_ACTIVITY", "")
    val worldMembers: Boolean = dotenv.get("WORLD_MEMBERS", members.toString()).toBooleanStrict()
    val worldQuickChat: Boolean = dotenv.get("WORLD_QUICKCHAT", "false").toBooleanStrict()
    val worldBotsAllowed: Boolean = dotenv.get("WORLD_BOTS_ALLOWED", "false").toBooleanStrict()
    val worldLootShare: Boolean = dotenv.get("WORLD_LOOTSHARE", "true").toBooleanStrict()
    val worldCountry: String = dotenv.get("WORLD_COUNTRY", "USA")
    val worldHighlighted: Boolean = dotenv.get("WORLD_HIGHLIGHTED", "false").toBooleanStrict()
    val worldXpRate: Int = dotenv.get("WORLD_XP_RATE", "1").toInt()
    val worldDropModifier: Double = dotenv.get("WORLD_DROP_MODIFIER", "1.0").toDouble()
    val worldToken: String = dotenv.get("WORLD_TOKEN", js5ServerToken)
}
