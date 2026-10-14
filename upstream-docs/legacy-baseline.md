# Legacy sync baseline

The modern Gradle modules were seeded from the legacy Java servers and are re-synced by
replaying legacy commits on top of the migrated copies. This file records where that
replay currently stands so the next batch can be found mechanically.

## Repos

| Legacy repo | Local path | Feeds |
|---|---|---|
| world-server | `~/darkan/world-server` (branch `dev`) | `world/src/main/java/com/rs/**` |
| lobby-server | `~/darkan/lobby-server` | `lobby/**` |
| core | `~/darkan/core` | `core/**` |

`world/` keeps the legacy package layout: Java *and* Kotlin both live under
`world/src/main/java/com/rs/`, path-for-path with legacy `src/main/java/com/rs/`.

## Synced to

Recorded 2026-08-28.

| Repo | Commit | Date |
|---|---|---|
| world-server | `59155b03389dc848502842de6a36fc396f9f6f8e` (`59155b033`) | 2026-07-19 |
| lobby-server | `861a2ad88c8639dbf0ee58e13bd5eb9f5b750123` (`861a2ad`) | 2026-07-27 |
| core | `0ba88ac` | 2026-07-19 |

The previous world-server baseline was `effa4b3ba` (2026-05-09).

## Finding the next batch

```bash
cd ~/darkan/world-server && git fetch && git log --oneline 59155b033..origin/dev
cd ~/darkan/world-server && git diff --name-only 59155b033..origin/dev -- src/

cd ~/darkan/lobby-server && git fetch && git diff --name-only 861a2ad..origin/HEAD -- src/
cd ~/darkan/core        && git fetch && git diff --name-only 0ba88ac..origin/HEAD -- src/
```

## Replay procedure

Because `world/` mirrors the legacy tree path-for-path, and the migrated copies differ
from legacy mostly by import rewrites, a three-way merge does the work:

```bash
BASE=59155b033   # the sha in the table above
HEAD=origin/dev
cd ~/darkan/world-server
for f in $(git diff --name-only $BASE..$HEAD -- src/); do
  MOD=~/darkan/server/world/$f
  [ -f "$MOD" ] || continue                 # new legacy file: port by hand
  git show $BASE:"$f" > /tmp/base; git show $HEAD:"$f" > /tmp/head
  git merge-file -p --diff3 "$MOD" /tmp/base /tmp/head > /tmp/out && cp /tmp/out "$MOD"
done
```

Conflicts are almost always the migrated import block (legacy `com.rs.lib.*` vs modern
`org.darkan.core.*`): keep the modern side and append the lines the legacy commit added.

Translation conventions when a hunk carries legacy API:

- `com.rs.lib.*` -> `org.darkan.core.*`; `com.rs.lib.util.Utils` -> `org.darkan.core.Utils`;
  `com.rs.lib.util.Logger` -> `org.darkan.core.Logger`; `com.rs.lib.game.Rights` -> `org.darkan.core.model.Rights`
- `com.rs.lib.game.Tile` / `WorldTile` -> `world.gregs.voidps.type.Tile`
- `.getPlane()` / `.plane` -> `.level`
- `player.getPackets().sendFoo(...)` -> `player.queuePacket(Foo(...))`
- legacy `net/decoders/**` -> `org.darkan.world.server.packet` Kotlin handlers;
  `net/encoders/WorldEncoder.java` -> `world/src/main/java/com/rs/net/encoders/WorldEncoder.kt`
- imports always go at the top of the file; never an inline fully-qualified reference
- Kotlin `shr`/`shl`/`and`/`or` bind *lower* than arithmetic - re-parenthesise ported
  bitwise expressions rather than transliterating them

See `tools/migration/migrate.sh` and `.claude/agents/legacy-migration-engineer.md`.

## Verify

```bash
cd ~/darkan/server && ./gradlew :world:compileJava :world:compileKotlin
```
