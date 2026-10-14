# Community worlds

Anyone can run their own world against the dev lobby (`dev.darkan.org`) and have it appear in the
dev lobby's world list. It is a dev-lobby feature only: the lobby accepts community worlds while it
runs with `DEBUG=true`, and the production lobby never does.

## Running one

1. Clone the server repository with its `cache-source` submodule. The world has to serve the same
   cache as the dev lobby, so stay on the same `dev` commit it runs; with no prepacked cache at
   `CACHE_PATH` the world builds and serves the submodule by itself (about a minute the first time).
2. Run a MongoDB on `localhost:27017`, e.g. `docker run -d -p 27017:27017 mongo:7`. The world keeps
   its own accounts and player saves there; it never touches the dev database.
3. Create `.env` in the repository root:

   ```env
   WORLD_ID=123                  # 100-999, and not the lobby's local world (300)
   WORLD_NAME=My World
   WORLD_PUBLIC_HOST=203.0.113.7 # the address players' clients connect to
   WORLD_PORT=43595              # open it to the internet
   ```

4. `./gradlew :world:run`.

Everything else defaults to the dev lobby: `SOCIAL_GATEWAY_URL=ws://dev.darkan.org:4040/social/ws`
and `SOCIAL_GATEWAY_TOKEN=darkan-community-world`, the public community token. A world whose
gateway token is that public one is a community world.

## What the lobby enforces

- A community world may not use an official number (`1`-`OFFICIAL_WORLD_MAX`, 99 by default), the
  lobby's `LOCAL_WORLD_ID`, a lobby number (1000+), or a number that is already online. The
  handshake is refused with the reason, and the world logs it.
- No world holds the login-token secret or reads the accounts collection. For each login a world
  sends the player's token to the lobby (`VerifyLoginToken`); the lobby checks it and answers with a
  credential-free `AccountView` - username, display name, rights, ban and mute expiry and the social
  settings (`LoginTokenVerdict`). That view is all the world ever holds about the account; passwords
  and emails never leave the lobby.
- The signed token, not the name the player typed, decides which account is admitted. The world
  passes the typed name along only as a hint, and everything after admission uses the username the
  lobby returned. A sign-up starts life under its email address and is renamed by the name screen
  after its token was issued, so on a new account the two disagree for that whole first session.
- A refusal says which of `TOKEN`, `NO_ACCOUNT`, `BANNED` or `NAME_REQUIRED` it was
  (`LoginTokenVerdict.refusal`), and the world turns each into its own client response instead of
  reporting every one of them as "your password has been updated".
- The lobby only believes a community world about players it admitted there: presence,
  friends/ignores, private and friends chat, friends chat settings and clan commands for anyone
  else are dropped, and the display name and crown always come from the lobby's own account, not
  from the world. Community worlds cannot send announcements.
- Official worlds use the private `SOCIAL_GATEWAY_TOKEN`; the presence and admission rules above do
  not apply to them. `WORLD_LOGIN_TOKEN_SECRET` is a lobby-only secret: every world, official or not,
  has its logins verified by the lobby.

## Limits

- A player's login token is not tied to a world and is not single use: the lobby issues one at lobby
  login, before the player picks a world, the RSA login block has no room for more, and the client
  re-presents that same token on every world it enters. It lasts `WORLD_LOGIN_TOKEN_TTL_HOURS`
  (default 12) - long enough to cover a whole play session, because a token that expires under a
  player mid-session locks them out of every world until they leave the lobby and log in again.
  A modified community world can therefore replay the token of a player
  who joins it, on another world, for the rest of that token's life. Only join community worlds you
  trust; staff accounts should not join them at all.
- A community world's admissions outlive its gateway socket, so its players keep their friends and
  chat across a world restart or a dropped gateway connection; an admission ends when the player
  goes offline or is admitted to another world. A world id that never comes back leaves its
  admissions behind until then.
