---
name: drynor-item-creation
description: Create custom Drynor items from Blender models or visual references, convert them to revision 727, integrate their cache definitions and gameplay, and validate delivery in the client. Use for new custom items, vouchers and completion tickets.
---

# Custom item creation

Read `drynor-code-standards` and `drynor-content-development` first. Use the original project checkouts. Documentation and delivery evidence belong in `A:/dev/drynor/DOCS/world/`.

## Establish the item contract

Identify the intended effect before wiring gameplay. A ticket named "unlock" may complete content and award its reward rather than merely permit entry. Establish consumption, completion state, rewards, already-completed behavior, trade/stack/note settings, Destroy/Drop, and death policy from the request and existing content. Do not silently replace one effect with another.

Use existing completion ledgers and reward delivery when available. Do not fabricate a played run, waves, kill times, or pet rolls to grant a voucher reward. Prepare and validate the entitlement before consuming; persist consumption, completion and pending delivery in the same character snapshot. Keep rewards pending when containers are full; avoid ground drops as a delivery fallback unless requested. Revalidate item identity, slot, amount and activity restrictions at confirmation; detect controller-refused removal before granting anything.

## Blender and model conversion

Identify the installed Blender and preserve the open scene unless the user authorizes editing it. Blender background execution with a reproducible `bpy` script is an alternative when window capture fails; disclose that it does not edit the open scene. Keep editable `.blend` sources and generation scripts under the owning project's assets, not in alternate project directories.

The established example is `tools/assets/inferno-ticket/create_prototype.py`; adapt geometry and palette for the new item rather than reusing its IDs or semantics. The revision-727 conversion APIs are:

- `world.gregs.voidps.cache.source.codec.ModelGltf.read/write`
- `world.gregs.voidps.cache.definition.encoder.ModelEncoder.encode`
- `world.gregs.voidps.cache.definition.decoder.ModelDecoder.read`

A standard Blender GLB is not directly an RS model. `ModelGltf.read` requires `nodes[0].extras.rs`, one mesh primitive and RS per-triangle colours in `rs.colours`. Native export splits materials into primitives. Bake transforms, triangulate and preserve the final triangle order before building RS metadata; the reader does not apply node transforms or turn PBR materials into RS face colours.

For a flat ticket, the proven mapping is Blender `(x,y,z)` to RS `(x,-z,y)`, using integer positions and model version 15 (`footer.flags=8`). This is a calibration example, not an assumption for worn or animated models. Pre-13 codecs rescale coordinates; compare against an actual item/model from the active cache. Remove triangles collapsed by quantization from both Blender geometry and RS export. Keep low geometry counts and respect unsigned-short counts, index and delta encoding limits.

Start with untextured face colours. Textures, particles, bones and equipped models introduce additional dependencies; do not assume Blender emission/shaders survive. Keep the native visual GLB separate from the RS-aware GLB. Packed `.dat` output is generated and must not be committed as cache payload.

## Cache and visual verification

Verify free item/model IDs against the packed runtime cache, source catalogs and gameplay bindings immediately before allocating. IDs in item, scenery and model namespaces are independent. Use named constants for deliberately allocated custom IDs; prefer generated gamevals when the actual catalog contains the entry.

Stop the affected cache consumers, create one identified rollback backup, then change the canonical cache. Compare local hashes with the cache actually mounted by the active VPS containers before replacing production data. Never rebuild stale `cache-unpacked` over the live-matched packed cache.

`tools/src/main/kotlin/org/drynor/tools/cache/InfernoTicketAssets.kt` demonstrates a bounded importer: audit vacant IDs; verify backup equality; write model index 7 and item index 19 (`archive=id ushr 8`, `file=id and 255`); reopen; compare every unrelated archive and sibling item definition against the backup. Its constants and CLI are specific to the Inferno ticket; author a separate bounded plan for another item.

Author a fresh `ItemDefinition` where practical. A cloned definition's `opcodeOrder`/`shadowedPayloads` may suppress added fields. Prove that names, options, model and camera fields survive encode/decode. `FileCache.write` maintains archive reference metadata; do not patch only raw bytes and ignore JS5 tables. Restore the backup on failure before restarting consumers.

Validate coordinates, face indices, colours, nondegenerate triangles and stable re-encoding. A probe using the actual client's compiled `Mesh` reader provides additional compatibility evidence. Render inventory framing with `ObjIcons` and `OffscreenContext` at 36x32; use zoom comparisons and pixel bounds to detect clipping. Blender renders and tool icons are not substitutes for an actual client test.

## Gameplay and delivery

Load `drynor-kotlin-plugins` before adding handlers. Register through a discovered compiled startup function; current startup discovery may require a `com.rs` bridge invoking implementation in `org.drynor`.

Destroy is a server behavior, not only a label: `InventoryOptionsHandler.handleItemOption7` routes destroy definitions before the drop path. The normal confirmation uses interface 1183 (`DestroyItemStatement`, confirm component 9). A custom confirmation must also revalidate the item; do not consume twice via both a destroy hook and the generic continuation. Cancel/Destroy must never trigger the redemption reward.

Run relevant module tests and builds sequentially with the configured JDK. Test duplicate confirmation, stale slots, refused removal, serialization, full containers and delayed reward claims where applicable. Start local services with `DRYNOR_CACHE_BUILD=false`; let the user test the actual item. Record precisely what passed automatically and what the user confirmed.

The implemented reference is item 30261/model 239026: `InfernoUnlockTicket`, `InfernoRewardLedger.recordTicketCompletion` and `InfernoRewards.redeemTicket`. It records one completion and one Infernal cape, uses the existing pending reward ledger, and preserves a ticket on an already-completed character. See `DOCS/world/ROADMAP-TICKET-INFERNO.md` for the user-confirmed flow and rollback evidence.

Deployment/release needs the user's authorization. Coordinate a cache change across both lobby and world, preserve remote rollback data and saves, validate startup and the published artifacts, and report any remaining client validation. Do not publish a client binary merely to distribute server-side cache data unless the task includes that release.
