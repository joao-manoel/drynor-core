# Environment Variables

Every setting is read in `core/src/main/kotlin/org/darkan/core/EnvVars.kt`, which holds the full list and the defaults. Set them in a `.env` file in the project root. The commonly tuned ones:

## Server Configuration
```env
DEBUG=false
MEMBERS=true
SERVER_NAME=Darkan
```

## Network Ports
```env
LOBBY_PORT=43594
WORLD_PORT=43595
LOBBY_API_PORT=4040
LOCAL_WORLD_ID=0   # >0 lists that world as localhost:WORLD_PORT for local development
```

## Security
```env
JS5_SERVER_TOKEN=<hex>
CLIENT_KEY=<hex>
RSA_JS5_MODULUS=<bigint>
RSA_JS5_EXPONENT=<bigint>
RSA_WORLD_MODULUS=<bigint>
RSA_WORLD_EXPONENT=<bigint>
WORLD_LOGIN_TOKEN_SECRET=<secret>
WORLD_LOGIN_TOKEN_TTL_HOURS=12   # the lobby issues one reusable token per lobby session; it must outlast a play session
API_KEY=<admin-api-key>
LOBBY_OWNER=trent           # the only account allowed owner-tier lobby commands (and, with DEBUG=true, any administrative lobby command)
```

## Cryptography (Argon2id)
```env
CRYPTO_ARGON2_MEMORY=65536
CRYPTO_ARGON2_ITERATIONS=5
CRYPTO_ARGON2_HASH_LENGTH=32
CRYPTO_ARGON2_SALT_LENGTH=16
CRYPTO_ARGON2_PARALLELISM=4
```

## Database
```env
MONGO_URI=mongodb://localhost:27017/darkan-server
MEM_CACHE=false            # true only on a lobby: holds the packed cache in memory for JS5 serving; worlds read it from disk
CACHE_PATH=../cache/
```

## Gamevals
```env
CACHE_SOURCE_PATH=        # unset: serve the prepacked CACHE_PATH, or the cache-source submodule when CACHE_PATH does not exist. Set to a cache source tree: build it into CACHE_BUILD_PATH on startup and serve that
CACHE_BUILD_PATH=./data/cache
GAMEVAL_PATH=             # folder of <type>.json name catalogs; default <CACHE_SOURCE_PATH>/gamevals, else ./cache-source/gamevals (the only copy)
```

## Social Gateway (World Server)
```env
SOCIAL_GATEWAY_URL=ws://dev.darkan.org:4040/social/ws   # default: the dev lobby
SOCIAL_GATEWAY_TOKEN=<shared-secret>   # default: COMMUNITY_WORLD_TOKEN, i.e. a community world (docs/community-worlds.md)
COMMUNITY_WORLD_TOKEN=darkan-community-world   # public; the lobby accepts it only with DEBUG=true
OFFICIAL_WORLD_MAX=99         # worlds 1..99 are official; the lobby refuses them to community worlds
WORLD_ID=100
WORLD_NAME=Darkan
WORLD_HOST=localhost
WORLD_PUBLIC_HOST=localhost
WORLD_MEMBERS=true
WORLD_BOTS_ALLOWED=false      # the world list's flag 0x4 (PvP in Jagex's client): worlds that let bot clients in
WORLD_LOOTSHARE=true
WORLD_XP_RATE=1               # skill xp multiplier: 5 on the 5x remake world, 25 on the RSPS world
WORLD_DROP_MODIFIER=1.0
```

## Rate Limiting
```env
MULTILOG_WHITELIST=localhost,127.0.0.1,::1
MULTILOG_LIMIT=3
BOT_IP_WHITELIST=             # IPs/CIDRs whose headless-client " Bot" logins get the passwordless bot profile; empty = none
TRUSTED_PROXIES=              # peers that must open every connection with a PROXY v1/v2 header naming the real client IP
```

## Protocol
```env
MAJOR_VERSION=727
MINOR_VERSION=1
PACKET_SIZE_LIMIT=7500
PACKET_QUEUE_CAPACITY=50
OUTBOUND_QUEUE_CAPACITY=4096
SESSION_IDLE_TIMEOUT_MILLIS=12000   # a logged-in session that receives nothing (the client pings every second) for this long is treated as a lost connection
AUTO_UPDATE=false              # dev only: restart onto new CI builds via the ::update countdown
AUTO_UPDATE_POLL_SECONDS=60
AUTO_UPDATE_COUNTDOWN_MINUTES=5
WORLD_CYCLE_NANOS=600000000
```

