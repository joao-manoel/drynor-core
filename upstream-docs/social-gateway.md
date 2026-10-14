## SocialGateway

### Overview

`SocialGateway` (`lobby/src/main/kotlin/org/darkan/lobby/social/SocialGateway.kt`) is the lobby's
social service. It is authoritative for everything social - friends, ignores, private status,
friends chat, private messages - and every client of it speaks one small, revision-free
vocabulary:

- `SocialAction` - what a player asks for: `AddFriend`, `RemoveFriend`, `AddIgnore`,
  `RemoveIgnore`, `SetPrivateStatus`, `JoinFriendsChat`, `LeaveFriendsChat`,
  `KickFromFriendsChat`, `SetFriendsChatRank`, `UpdateFriendsChatSettings`, `SayInFriendsChat`,
  `SendPrivateMessage`.
- `SocialEvent` - what a player is told: `Snapshot` (the whole state, always first),
  `FriendUpdated`, `FriendRemoved`, `IgnoresChanged`, `PrivateStatusChanged`,
  `FriendsChatChanged`, `FriendsChatLine`, `PrivateMessageReceived`, `PrivateMessageSent`,
  `Notice`.

Both live in `core/src/main/kotlin/org/darkan/core/social/SocialProtocol.kt`. Nothing about the
727 client crosses a socket: each side renders the events into client packets itself with
`SocialPackets` (`core/.../social/SocialPackets.kt`) - the lobby for players on the lobby screen,
a world for the players on it. Clans have their own typed protocol on the same socket, see
[Clans](#clans).

### A player has connections, not a location

The gateway keeps, per player, a list of **attachments** - every place that player's events are
delivered to and actions accepted from:

| Attachment | Carries presence | Made by |
|---|---|---|
| a world (one per world the player is on) | yes, that world's id | `PlayerOnline` / a `WorldPresenceSnapshot` on the world socket |
| a lobby-screen session | yes, world `1115` | the lobby's own login |
| a website socket (any number) | no | `WS /social/players/{username}` |

Every attachment gets the same events, starting with a `Snapshot` the moment it is made. A player
may hold any mix at once - two worlds, a world and the website, the lobby screen and the website -
and they all stay in step because there is exactly one copy of the state and one stream of events.

Presence is derived: a player is **online** while any attachment with a world exists, and their
friends see them on the newest game world they are attached to, else on the lobby screen (`1115`,
the id the legacy lobby registered itself under; labels are `World n` and `Lobby n` as legacy drew
them). A website socket alone is not presence - friends see the player offline - but it does
receive everything and may act, and a website-only member of a friends chat is listed with world
`0` and the label `Web`.

Per-player work is serialised (one mutex per username), and each world's messages are processed
one at a time in arrival order, so a world's late `PlayerOffline` can never undo a fresh
`PlayerOnline` from elsewhere: it only removes that world's own attachment.

**First attachment** (the player appears anywhere): register them in the watcher index, send the
`Snapshot`, re-join the friends chat they were last in, tell their watchers they are online.
**Last attachment gone**: tell the watchers, leave the watcher index, leave the friends chat
without forgetting it (so the next login re-joins), leave the clan channels. In between, only a
change of reported location - lobby screen to a world, one world to another - is pushed to the
watchers and to the friends chat member list.

### Friend statuses

A `FriendEntry` carries the friend's display name, world id (`0` = offline), world label and the
rank the *viewer* has given them in the viewer's own channel.

- Visibility is the friend's own private-chat setting, checked when the entry is built exactly as
  legacy's `FriendStatus.encodePlayer` did: `nobody` always reads offline, `friends` reads offline
  unless the friend has the viewer added back. The event is still sent - it just carries world `0`.
- Ignores play no part in presence. They only gate private-message delivery and friends chat entry.

**Who gets told.** Friendship is one-directional: B can have A on their list without A having B. So
the gateway keeps a reverse index (`friendWatchers`, mirroring legacy `FriendsToNotify`): for each
username, everyone online who has them listed. A status change is pushed to that set as a
`FriendUpdated`.

Statuses are pushed on: the first attachment, the last detachment, a change of reported location,
a world socket drop, presence reconciliation, a private-status change, and adding or removing a
friend.

### Rendering for the 727 client

`SocialPackets.render(viewer, event)`:

| Event | Packets |
|---|---|
| `Snapshot` | `FriendlistLoaded`, `FriendStatus` (the whole list, which is what takes the client out of "Loading Friends List"), `UpdateIgnoreList`, `ChatFilterSettingsPrivateChat`, `FriendsChatChannel` |
| `FriendUpdated` | one `FriendStatus` record |
| `FriendRemoved` | nothing - the client drops the row itself when it sent the removal; a removal made from another connection shows on the next snapshot |
| `IgnoresChanged` | `UpdateIgnoreList` |
| `PrivateStatusChanged` | `ChatFilterSettingsPrivateChat` |
| `FriendsChatChanged` | `FriendsChatChannel`, or the empty one for "not in a channel"; the "minimum rank to kick" byte is `0` for the owner's own copy, as legacy patched it |
| `FriendsChatLine`, `PrivateMessageReceived`, `PrivateMessageSent` | the text or quick-chat message packet |
| `Notice` | `GameMessage` of the friend, ignore or friends-chat notification type |

A world renders on the world thread (`WorldServer.onWorldThread`) and, in the same step, mirrors
the event into the player's `AccountView.social` (`world/.../social/SocialSync.kt`) so the world's
copy of friends, ignores, private status and current friends chat always says what the lobby says.

### World socket

- **Endpoint**: `GET /social/ws`, one multiplexed socket per world; every social message names the
  player it concerns.
- **Handshake**: world → `WorldHello(world: GatewayWorldInfo, token)`; lobby → `WorldHelloAck(ok,
  message?)`. The token decides the world's tier: the lobby's private `SOCIAL_GATEWAY_TOKEN` makes an
  official world, and the public `COMMUNITY_WORLD_TOKEN`, accepted only while the lobby runs with
  `DEBUG=true`, a community world (`docs/community-worlds.md`). A bad token, an unroutable world
  (blank host, port `<= 0`), or a community world on an official, local, lobby or already-online
  number is rejected with the reason and the socket closed.
- **Then**: world → `WorldPresenceSnapshot(players, reason = CONNECT)`, immediately.
- **Social traffic**: world → `SocialCommand(username, action)`; lobby → `SocialNotification(usernames,
  event)` (one event for many players is one message).
- **Ordering**: the world's `SocialClient` writes everything through one queue in the order it was
  asked to, and the lobby processes each world's messages one at a time; between them a player's
  online, actions and offline can never be applied out of order.
- A world announces a player only once that player can receive packets (`Player.setStarted`), so
  the snapshot the lobby answers with is never sent to a client that is still logging in.

### Presence: the world is the authority, and it says so repeatedly

- `PlayerOnline(username, displayName, rightsCrown, privateStatus)` - one player arrived: attach
  them through this world (re-announcing an already attached player just re-sends the snapshot).
- `PlayerOffline(username)` - one player left: detach this world's attachment and nothing else.
- `WorldPresenceSnapshot(players, reason)` - **everyone** the world currently has in game.
  - `reason = CONNECT`, sent the moment the socket comes up: anybody attached through this world
    but not listed is detached, and every listed player is (re)attached, which re-sends their
    whole state - friend list, ignores, chat filter, friends chat, clan channel. A world that
    outlived a lobby restart gets its players' clients repopulated without anybody relogging.
  - `reason = PERIODIC`, sent every `SOCIAL_PRESENCE_RESYNC_SECONDS` (default 120): only the
    difference is applied, so drift - a lost `PlayerOnline`, a socket that died mid-logout - cannot
    outlive one interval.
- **Socket drop**: every attachment made through that world is detached, with the watcher and
  channel updates that implies.
- **Lobby drop**: the world's `SocialClient` reconnects with backoff and re-sends `WorldHello` + a
  `CONNECT` snapshot, which is the recovery path above.

### Per-player socket (the website)

- **Endpoint**: `WS /social/players/{username}` (username or display name), authenticated with the
  lobby's `API_KEY` in `X-API-Key` (the website's backend holds the key; it authenticates the
  player itself and opens the socket on their behalf).
- **Out**: one `SocialEvent` per frame as JSON, tagged by `type`, starting with the `Snapshot`.
- **In**: one `SocialAction` per frame as JSON, tagged by `type`. A frame that is not a valid action
  closes the socket with a protocol error naming the problem.
- Any number of these may be open on one player, alongside the game.

```json
{"type":"Snapshot","friends":[{"username":"trent","displayName":"Trent","previousDisplayName":"","worldId":1,"worldName":"World 1","rank":"UNRANKED"}],"ignores":[],"privateStatus":0,"friendsChat":null}
{"type":"FriendUpdated","friend":{"username":"trent","displayName":"Trent","previousDisplayName":"","worldId":0,"worldName":"","rank":"UNRANKED"}}
{"type":"JoinFriendsChat","owner":"Trent"}
{"type":"SayInFriendsChat","message":{"type":"PublicChat","message":"hello","effects":0}}
{"type":"SendPrivateMessage","to":"Trent","message":{"type":"PublicChat","message":"hi","effects":0}}
```

The older website endpoints still work: `WS /social/subscribe` pushes every friends-chat line
said anywhere as `{"type": "FriendsChatMessage", "data": {fcOwner, srcName, srcRights, message}}`,
`POST /social/friends-chat {username, chatOwner, message}` says a line into a channel without the
speaker being a member of it, and `POST /social/broadcast {username?, message}` announces on every
world.

### Friends chat

Membership lives in the lobby, keyed by the owner's username. The remembered channel is
`Account.social.currentFriendsChat`, holding the **owner's display name**:

- set when a player joins a channel,
- cleared when they leave on purpose, when they are kicked, and when the channel is disabled,
- **kept** when they log out - that is the auto-rejoin mechanism,
- `null` means "in no channel".

Join enforces, in order: channel exists and is set up, owner has not ignored the joiner, rank to
enter, 100-member cap, and a one-hour temp ban from a previous kick. A refused join is a `Notice`
plus a `FriendsChatChanged(null)`. Ranks come from the owner's rank map, with staff (`ADMIN`+)
treated as `JMOD` and the owner always `OWNER`. Changing a friend's rank refreshes the channel and
sends the owner a `FriendUpdated` so their own list shows the new rank.

`UpdateFriendsChatSettings` comes from the world's FC setup interface
(`world/.../social/FriendsChatSetup.kt`); it applies the new settings and refreshes the channel,
or dissolves it when the name is cleared.

### Account state

Worlds have no access to the accounts collection. At login the lobby hands the world an
`AccountView` (username, display name, rights, ban and mute expiry, social settings) inside the
`LoginTokenVerdict`, and the world keeps it in memory on the player for the session. Anything a
world wants changed on an account is an `AccountCommand` carrying an `AccountAction` - `SetRights`,
`Ban`, `Mute` (`until` in epoch millis, `PERMANENT` or `LIFTED`) or `SetFcStatus` - which the lobby
judges against the actor's own account (`MOD` for bans and mutes, `ADMIN` for rights, never above the
actor's own rights or against someone who out-ranks them), persists, and answers every world the
target is on with an `AccountUpdate`. Community worlds may not send account commands.
`DisplayNameLookup` / `DisplayNameResult` fetch another player's display name for interfaces such
as the clan member list.

### Private messages

`SendPrivateMessage(to, message)`: the lobby resolves the target by display name, enforces mute,
`Account.onlineTo` and that the target is online anywhere, echoes `PrivateMessageSent` to the
sender, and delivers `PrivateMessageReceived` to every connection of the target unless they have
the sender ignored.

### Server text to a lobby player

A player on the lobby screen has no game chatbox. The lobby's friends-chat window is the only thing
that renders server text, so chatbox text for a lobby player must be
`GameMessage(ChatMessageType.FC_NOTIFICATION, …)` - a `GAME` message is encoded, sent, and silently
dropped by the client. Use `Session.sendLobbyMessage` / `LobbyPlayer.message`.

### Announcements

`POST /admin/announcements` takes an `AnnouncementBroadcast(channel, message, target)`.

- Lobby-resident players are messaged directly (as `FC_NOTIFICATION`, per above) - they never reach
  a world, so fanning out to the worlds alone would skip them.
- The message is then fanned out to every connected world, which shows it to everyone in game.

### Clans

Clans do not ride on `SocialCommand` / `SocialNotification`. A world turns a button into a typed
`ClanAction` and sends `ClanCommand(username, requestId, action)`; the lobby's `ClanService`
(`lobby/.../social/ClanService.kt`) judges it with the shared `ClanRules`, applies it, and answers
with `ClanCommandResult` plus the events every world replicates from: `ClanSnapshot` /
`ClanDeleted`, `ClanChannelUpdate`, `ClanMembership` and `ClanChatMessage` / `ClanQuickChatMessage`.
`ClanFetch(clanName)` asks for a snapshot the world does not have. Each side builds its own client
packets from that state with `org.darkan.core.clan.ClanPackets`. The whole protocol is specified in
[`docs/clans.md`](clans.md).

### Configuration

| Variable | Default | Meaning |
|---|---|---|
| `SOCIAL_GATEWAY_URL` | `ws://dev.darkan.org:4040/social/ws` | Where a world connects |
| `SOCIAL_GATEWAY_TOKEN` | `COMMUNITY_WORLD_TOKEN` | Private secret of the lobby and its official worlds; a world left on the default joins as a community world |
| `COMMUNITY_WORLD_TOKEN` | `darkan-community-world` | Public token of community worlds, accepted only while the lobby runs with `DEBUG=true` |
| `OFFICIAL_WORLD_MAX` | `99` | Worlds `1`-`OFFICIAL_WORLD_MAX` are refused to community worlds |
| `SOCIAL_PRESENCE_RESYNC_SECONDS` | `120` | How often a world re-announces its online list |
| `API_KEY` | | The website's key for `/social/players/{username}` and the other website endpoints |

### Testing

`lobby/src/test/kotlin/org/darkan/lobby/harness/` drives the gateway with no Mongo, no socket and no
client: `InMemoryAccounts` is installed as `Accounts.store`, `LobbyHarness.FakeWorld` builds a
`WorldSession` over a lambda and feeds it `SocialGateway.handleWorldMessage` - the same entry point
the real socket's inbox calls - and `LobbyHarness.listen(account)` attaches a recording website-style
sink. `RecordingSession` is a real `Session` with its writer cancelled, so what a lobby-screen player
would receive can be read back as `ServerProt` objects; `FakeWorld.eventsFor(account)` and
`RecordingSink.events` read back the `SocialEvent`s. `LobbyHarness.reset()` empties the gateway and
`ClanService` between tests. See `SocialGatewayTest.kt` and, for the clan half, `ClanServiceTest.kt`.
