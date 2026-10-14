# Legacy → modern database migration

The modern Kotlin classes (`org.darkan.core.model.Account`, `Clan`, and the world's `Player` save
graph) are the canonical schema. Legacy databases written by `../world-server` and `../lobby-server`
are transformed to fit them; the application code carries no legacy-compatibility paths. The one
exception is password verification, which falls back from Argon2 to the older SHA-1/AES credentials
because a hash cannot be converted without the plaintext, and upgrades the account on a successful
login.

The tooling lives in the `migration/` module.

## What changes per collection

| Collection | Transform |
|---|---|
| `accounts` | `prevPasswords` → `previousPasswords` (non-string history entries dropped); explicit nulls in non-null fields unset so the constructor defaults apply; emails lowercased |
| `clans` | `clanLeaderUsername` → `leaderUsername`; `settings` keys remapped from client var ids to `ClanSetting` names; motto surfaced to `motto`; `updateBlock` removed; numbers Gson turned into doubles restored to ints/longs |
| `players` | `dateJoined` legacy string → BSON date (unreadable dates are unset, the save is kept) |
| `logs` | `date` legacy string → BSON date, which makes the 180 day TTL index effective (unreadable entries dropped) |
| `highscores`, `grandexchange` | copied verbatim |

## Running it

Every URI must name a database. The source is only ever read.

```bash
# 1. Survey the legacy database: counts, duplicate/null emails, date shapes, unknown clan settings.
./gradlew :migration:run --args="inspect --source '$SRC'"

# 2. Rehearse. Full transform, full report, nothing written.
./gradlew :migration:run --args="migrate --source '$SRC' --target '$DST' --dry-run"

# 3. Write into the (new) target database. --replace drops target collections that already have data.
./gradlew :migration:run --args="migrate --source '$SRC' --target '$DST'"

# 4. Prove every document decodes through the code the servers use.
MONGO_URI="$DST" ./gradlew :migration:run --args="verify --target '$DST'"
```

`verify` opens the modern collections, which build their indexes on first use - including the unique
index on `accounts.email`. Duplicate emails therefore surface here as a collection init failure and
must be resolved in the data before the lobby can start against it.

The exit code is non-zero whenever the report lists a problem. Read the report; `migrate` writes
whatever it could, and the problems section is the list of documents that need a decision.

## After migrating

Point `MONGO_URI` in `.env` at the target. Both servers and the migration verifier read that one
variable; `data/worldConfig.json` no longer carries database settings.
