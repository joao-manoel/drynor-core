# Clans

How clans work across the lobby, the worlds and the client, and the contract every piece is held to.
The social gateway transport itself is in `docs/social-gateway.md`; this document is the clan
protocol that rides on it.

## Ownership

| Concern | Owner |
|---|---|
| Clan documents (`clans` collection), membership, bans, settings, vars | **Lobby**, the only writer. Worlds never touch `Clans`. |
| Clan channels (who is in which channel, temp bans, chat routing) | **Lobby** |
| Buttons, dialogues, interfaces, and every packet the client sees | **World** for world players, lobby for lobby-screen players |
| Permission rules | `org.darkan.core.clan.ClanRules`, shared. The world checks first for immediate feedback; the lobby checks again and is the one that counts. |

Nothing clan-related is forwarded as a client packet. The world turns a button into a typed
`ClanAction`, the lobby answers with typed events, and each side builds its own client packets
from the shared state with `org.darkan.core.clan.ClanPackets`.

## Wire messages (`core/.../social/gateway/GatewayProtocol.kt`)

World → lobby:

| Message | Meaning |
|---|---|
| `ClanCommand(username, requestId, action: ClanAction)` | The player `username` asks for `action`. `requestId` is world-local and echoed in the result. |
| `ClanFetch(clanName)` | The world has no copy of that clan; the lobby answers with `ClanSnapshot` (or `ClanDeleted` if it does not exist). |

Lobby → world:

| Message | Meaning |
|---|---|
| `ClanCommandResult(username, requestId, action, ok, message?)` | Outcome of one command. `message` is shown to the player verbatim when present. `action` is the `ClanAction` type name so a world can act without keeping the request. |
| `ClanSnapshot(clan)` | The whole clan document. Broadcast to **every** connected world on every change and sent in answer to `ClanFetch`. Worlds replace their copy wholesale. |
| `ClanDeleted(clanName)` | The clan no longer exists. |
| `ClanChannelUpdate(state: ClanChannelState)` | The channel's chatter list. Broadcast to every connected world on every change. (Named `ClanChannelUpdate` because `ClanChannelState` is the shared model class it carries.) |
| `ClanMembership(username, clanName?, inChannel, guestChannel?)` | The authoritative social fields for one player, sent to that player's world whenever they change and on world connect. The world writes them into the player's `Social` so its own account saves never clobber them. |
| `ClanChatMessage(clanName, sender, rights, message)` / `ClanQuickChatMessage(...)` | One line said in a channel. `sender` is the display name, `rights` the crown, `message` a `ChatMessage` (`PublicChatMessage` / `QuickChatMessage`, polymorphic on the wire). Sent to every world holding a chatter of that channel; the world delivers it to its local chatters, marking it affined (`guest = false`) for members and listened (`guest = true`) for guests of that clan. |

`ClanCommandResult.action` is `ClanAction.serialName`, the `_type` the action was sent as. A chatter's
`worldId` is the world number, or `ClanChatter.LOBBY_WORLD_ID` (1115, the id the lobby registers
itself under) for a player on the lobby screen.

`ClanAction` is a sealed hierarchy, one class per user intent:

| Action | Rule (`ClanRules`) |
|---|---|
| `CheckName(name)` | actor has no clan; name valid and unused |
| `Create(name)` | as `CheckName`; the actor becomes OWNER |
| `Leave` | actor is a member. An owner leaving hands the clan to the deputy owner, else the highest rank; the last member deletes the clan |
| `AcceptInvite(clanName, inviter)` | actor has no clan and is not banned; inviter is a member with `INVITE`; room under `MAX_MEMBERS` |
| `RemoveMember(target)` | actor ADMIN+ and above the target; the owner cannot be removed |
| `UpdateMember(target, rank?, job?, banFromKeep?, banFromCitadel?, banFromIsland?)` | actor ADMIN+ and above the target; a rank must be below the actor's; OWNER/JMOD are never assigned; an owner may not demote themself without a deputy owner |
| `Ban(target)` / `Unban(target)` | actor's rank at or above the channel kick rank; never the actor themself or the owner, and a member target must be below the actor. A ban also removes membership and ejects the target from the channel |
| `UpdateSettings(settings: Map<ClanSetting name, value>)` | actor ADMIN+; unknown or wrongly typed entries are rejected as a whole |
| `SetChannelRanks(talkRank?, kickRank?)` | actor ADMIN+ |
| `SetVar(id, value)` | actor ADMIN+ |
| `JoinChannel` / `LeaveChannel` | member of a clan |
| `JoinGuestChannel(clanName)` / `LeaveGuestChannel` | clan exists, allows guests, actor not banned or temp-banned, not a member |
| `KickFromChannel(target, guest)` | actor and target both in the channel; actor's rank at or above the kick rank; a guest target is always kickable, a member target must be below the actor. Kicked guests are temp-banned from the channel for an hour |
| `Chat(guest, message)` / `QuickChat(guest, message)` | actor in the channel; member: rank at or above the talk rank; guest: `GUESTS_CAN_TALK_CC`; never while muted |

## State each side holds

**Lobby** (`lobby/.../social/ClanService.kt`): the clan cache (loaded from the store on first use,
written back after every mutation), the channels (`ClanChannelState` plus temp bans), and the
lobby-screen players' sessions. Every mutation ends with: persist, broadcast the snapshot, push
`ClanMembership` to everyone whose fields changed, and refresh the lobby-screen members' packets.
The lobby-screen interface handlers (`IfButton` on 1110/1096 etc. from lobby sessions) call the same
`ClanService.handle(username, action)` as the gateway does, so there is one rule set.

Notes on the lobby implementation, where it adds to or tightens the contract above:

- `Create` also puts the new owner in the channel (`connectedToClan = true`), as the legacy
  create flow did, so the client lands in its clan chat straight after creating it.
- `AcceptInvite` for a clan that no longer exists is denied with "That clan no longer exists."
  (a lobby-only check; `ClanRules` cannot see the store). Clan names are stored in display form
  (`formatPlayerNameForDisplay`, "Darkan Devs"); every lookup - name check, create, guest join,
  `ClanFetch` - goes through the same canonical form, so uniqueness is case-insensitive and a guest
  name typed in any casing resolves.
- On login the lobby also sends the **guest** clan's `ClanSnapshot` to the player's world when
  they are re-joined to a guest channel, so the world can build the `main = false` settings
  packet without a `ClanFetch`; and the channel state is re-sent to that world after every
  login even when the chatter list did not change (that is what makes a reconnect resync
  complete without a broadcast).
- Every clan mutation re-publishes the channel only if its chatter list, their ranks, or the
  talk/kick ranks actually changed; a rank change therefore shows up as a `ClanChannelUpdate`
  as well as a `ClanSnapshot`.
- `ClanCommandResult.username` is the protocol-formatted username, whatever form the command
  carried. `KickFromChannel.guest` is not trusted; membership decides, as the table says.
- Text for a player on the lobby screen (a refusal, "you have been kicked") goes out as
  `FC_NOTIFICATION`, the only chatbox type the lobby client renders.
- Reading a clan var's stack type needs the loaded cache; `ClanService.varKind` is the seam a
  test replaces with a value-typed resolver.

**World** (`world/.../clan/ClanRegistry.kt`): a read-only replica keyed by clan name, the channel
states, and the in-flight `requestId → callback` table. A clan the world has never seen is fetched
with `ClanFetch` and the callback runs when the snapshot lands; the copy is dropped on every change
of the gateway socket's state, so a reconnect starts clean and the lobby's connect-time resync
refills it (`WorldServer.start` wires `SocialClient`'s connection changes to `ClanRegistry.clear`).
`ClansManager` (Java) keeps its public surface for the content that uses it (vexillum, clan camp,
appearance) but every mutation is `ClanRegistry.send(player, action) { result -> ... }`, which
pre-checks `ClanRules` and refuses locally - with the shared wording and no traffic - before the
`ClanCommand` is built. Gateway events arrive on the social client's coroutine, so everything that
touches a player is handed to the world thread first.

Three world-side details the contract above does not spell out:

- **Every command is answered.** A command the gateway would not take, or that the lobby has not
  answered within `ClanRegistry.REQUEST_TIMEOUT_TICKS` (17, ~10s), is answered locally with a
  failed `ClanCommandResult`, so a caller holding a dialogue lock (`ccCreateLock`, `ccJoinLock`,
  `ccLeaveLock`) always gets its callback and cannot leave the player locked out until they relog.
- **Interface rows are indexed against the settings packet.** The clanmate rows of 1096 and the ban
  list of 1110 are in the order `ClanPackets.settingsFull` sent them, not the order the document's
  maps iterate in, so a slot id is resolved through `ClanRegistry.orderedMembers` /
  `orderedBans`. Reading it against `Clan.members` would edit the wrong player.
- **The clan is never edited in place, so editors buffer.** Settings go out one
  `UpdateSettings` per change instead of the legacy "mutate the cached clan, save the whole thing
  when the interface closes"; the motif editor (1105) and the keyword editor (1097) hold their
  pending values in the player's own temp attribs and varbits and send one `UpdateSettings` when
  their save button is pressed. A clan name typed for a guest channel is sent as typed (trimmed) -
  normalising and looking it up is the lobby's job, since the lobby owns the documents.

## Client packets

Built by `ClanPackets` from the shared state, identically on both servers:

- `ClanSettingsFull(main)` from a `Clan`; the one-byte form (no clan) tells the client the player
  left. Sent to members (`main = true`) on every snapshot, to guests of the channel (`main = false`),
  and with `main = false` for an invite (interface 1095) or clan details (1107). The world builds
  those last two from its replica (`ClanRegistry.settingsFull`); the legacy path read a cached
  update block that was never filled in, so neither interface opened at all.
- A snapshot also re-sends the clan's `VarclanSet*` values (the values only - `VarclanEnable`
  belongs to a channel join) to every local member, because a var the lobby changed reaches the
  client no other way. That is what `syncClanFromLobby` did, and the same push regenerates the
  members' appearance (the motif colours ride on the appearance block) and re-runs script 4295 for
  anyone sitting in interface 1096.
- `ClanChannelFull(main)` from a `ClanChannelState`; one-byte form on leaving.
- `VarclanEnable` + one `VarclanSet*` per var on channel join, `VarclanDisable` on leave.
- `MessageClanChannel` / `MessageQuickChatClanChannel`, `guest` per recipient as above.

The 727 client never asks for clan state, so the servers push it on login, on channel join, on
every change and when an interface that reads it opens. Deltas (`ClanSettingsDelta`,
`ClanChannelDelta`) are not used; every change is a full resend, which is what the client expects
from this server family.

## Login, logout, restarts

- Login (`PlayerOnline` → `loginSocial`): the lobby sends `ClanMembership`, the clan's
  `ClanSnapshot`, and re-joins the channel the player was in (`connectedToClan`) and the guest
  channel they were listening to (`guestedClanChat`), which produces the `ClanChannelState` push.
- Logout: the player leaves the channels but keeps `connectedToClan` / `guestedClanChat`, so the next
  login rejoins.
- World socket drop: the lobby removes that world's chatters from every channel.
- World reconnect: the CONNECT presence snapshot resyncs every online player (membership, snapshot,
  channels) exactly as a login does.
- Lobby restart: channels are rebuilt from the worlds' CONNECT snapshots.

## Testing

- `core`: `ClanRulesTest` (every rule above), `ClanPacketsTest` (wire shape against the client
  contract: version byte 3, filler rows, the one-byte leave form), `ClanGatewayProtocolTest` (every
  `ClanAction` and event round-trips through the wire JSON).
- `lobby`: `ClanServiceTest` on `LobbyHarness` with the in-memory clan store (`Clans.store` seam,
  `org.darkan.core.clan.InMemoryClanStore`):
  every action's allow and deny path, succession, bans, channel routing to lobby and world
  recipients with the right `guest` flag, temp bans, world detach and reconnect resync.
- `world`: `ClanFlowTest` on `WorldHarness` with the recording gateway seam
  (`ClanRegistry.transport`, driven on an immediate `ClanRegistry.scope` so a send is visible by
  the time the button handler returns): each button produces the intended `ClanAction`, a denial
  the world can already see produces the message and nothing on the wire, snapshots, channel
  updates and channel lines produce the right client packets with the right `guest` flag per
  recipient, a membership push updates the player's `Social`, a result's message is shown, a
  reconnect drops the replica, and no clan traffic goes out as a `SocialCommand` - with
  friends chat (chat type 1), which does still forward, as the control that keeps that last
  assertion honest.
