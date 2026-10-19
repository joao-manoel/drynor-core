# Inferno transfer evidence

## Existing tools and ownership

Core `tools/src/main/kotlin/org/drynor/tools/osrs/README.md` documents `osrs inventory-inferno`, `plan-inferno`, `preview-inferno`, `audit-actors-inferno`, `preview-actors-inferno` and `reward-inferno`. Check current CLI help/signatures before invoking; outputs must be new directories outside the source/baseline. These commands specialize in Inferno and are not a generic importer for arbitrary regions.

World evidence lives in `docs/CONTENT_OSRS_ROADMAP.md`, `docs/INFERNO_GIELENOR_PORT.md`, `docs/INFERNO_PREVIEW.md` and `src/main/kotlin/org/drynor/world/content/inferno`. Runtime mappings in `data/inferno` need no Gielenor checkout. Client probes and known rendering fixes live in `docs/OSRS_INFERNO_VALIDATION.md` and `src/test/kotlin/org/drynor/client/osrs`.

## Format and runtime traps

- Definition archive addressing varies: source Gielenor NPC groups use 128 files, objects/items 256; verify each decoder. Sparse native arrays must size from the last physical file ID, not file count.
- Packed IDs, runtime offsets and Java overrides are different layers. Preserve animation and object opcode payload boundaries, bigSmart/u16 differences, signed fields and texture trailers; opcode-order metadata may suppress newly added fields.
- Infernal cape source51295 could not be equipped under native ushort(16384+itemId). The validated import uses item30000 with inventory/model/material/texture dependencies, untradeable cape-slot settings and source gameplay bonuses. Never reuse that allocation without checking destination occupancy.
- Terrain floor limits require remapping rather than blindly adding1000. Client region underlay palettes are explicit and scoped; inspect scene loaders before changing global colors.
- Native MapRegion already lowers objects on bridge-flagged tiles. Raw map plane is not necessarily runtime object plane. Compare raw RegionLoader output, MapRegion transformation, ChunkManager insertion and client linkBelow before adding a packet adapter or alias.
- Source map region71_82 contains the native TzHaar Chasm68122; its map archives matched Drynor. The physical origin4571,5259,plane1 becomes logicalplane0. Exact static routing preserves copied Elder Kiln quest objects. Region names alone did not prove OSRS terrain.
- Pillars require3x3 collision on the server. Validate actual occupied tiles and projectile line of sight, not only visible meshes. Moving Zuk shields use native movement updates; repeatedly setting tiles can reset the path.
- Animated topology can change index counts/order. Client GPU batch buffers must refresh indices for the rebuilt pose; matching vertex counts alone did not prevent Jad deformation.
- Source defence bonuses are not native defence levels. Port the source accuracy equation through a scoped target hook and test melee/range/magic rather than applying enormous generated levels globally.
- Persistent controllers must reconstruct transient bindings, NPCs, collision and instance placement. Save only completed checkpoints; duplicate rewards need durable completion receipts and a full-container delivery strategy. Never save a dynamic coordinate without a restorable controller.

## Release evidence

In October2026 the user confirmed Inferno combat, animations,3x3 pillars, shield movement, logout/resume and native entrance. Historical asset-preview reports may predate gameplay completion; interpret their fields together with current world/client evidence. Every future import still needs its own client validation.
