# The headless client side of core

`core` serves both ends of a connection. A server encodes `ServerProt`s and decodes `ClientProt`s; a
headless client (the Drynor CLI client, test harnesses, load testers) does the reverse against the
very same registrations. Nothing about the wire format is written down twice.

## Codec

Each prot is registered once, with its opcode and size, and carries both directions:

```kotlin
serverProt<CamShake>(opcode = 71, size = 6) { out ->
    out.writeByteSubtract(v2)
    ...
}.decoder {
    CamShake(...)
}

clientProt(opcodes = playerOpOpcodes, size = 3) { opcode -> OpPlayer(...) }
    .encoder(opcode = { playerOpOpcodes[it.opNum - 1] }) { out -> ... }
```

- `Codec.serverProtsByOpcode` / `clientProts` are the client's lookups, next to the server's
  `serverProts` / `clientProtsByOpcode`.
- Payload-less prots need neither side; they are built by reflection.
- Two prots may share a server opcode (`ClanSettingsFull` / `ClanSettingsFullRaw`); the one with the
  decoder is what a client reads it as.
- `loginResponse` registers `LobbyLoginDetails` / `WorldLoginDetails`, which reuse the handshake's
  response code as their opcode and are decoded by type during the handshake.
- A few decoders need what only the client knows, passed in the coroutine context:
  `ProtDecodeContext` (the local player's index, for the login region packet's player block),
  `LocCustomisationSizing` (array lengths `CustomizeObject` leaves to the cache) and `ChatHuffman`.
  `ClientDefinitions` builds the cache-backed ones.
- `Rev727BidirectionalCoverageTest` fails when a prot is missing either direction; every codec
  file has a round-trip test (`ProtRoundTrip.kt`).

## Sessions

`ProtSession<I, O>` is the framed, ISAAC-ciphered connection with its single-writer outbound
queue. `Session` is the server end (`ClientProt` in, `ServerProt` out); `net.client.ClientSession`
is the client end. Opcodes of 128 and above take two bytes in both directions.

## Login

`net.login.LobbyLoginRequest` and `WorldLoginRequest` are the login blocks: the client `encode`s
them with the public RSA key, `LobbyServer` and `WorldServer` `decode` them with the private one.
`net.client.ClientConnection.lobby` / `world` run the handshake and return the connected
`ClientSession` with the decoded login details.

## JS5

`net.js5.Js5Client` speaks the JS5 protocol (version table, pipelined archive requests).
`Js5Cache` keeps a local `FileStore` in step with a server by CRC: reference tables when it opens,
archives on request. Install its `cache` with `Cache.install` and every definition decoder,
`RegionCollision` and the update decoders read from it.

## Entity updates and the map

`UpdateProtocol.decodePlayerUpdate` / `decodeNpcUpdate` invert the server's update encoding into
per-entity positions and decoded masks, against `PlayerUpdateClientState` / `NpcUpdateClientState`.
`ZonePacketDecoder` expands `UpdateZonePartialEnclosed` batches. `RegionCollision` loads a map
square's collision (given its xteas through `MapXteas.put`) the way a world does, so a client
routes with the server's own `PathFinder`.
