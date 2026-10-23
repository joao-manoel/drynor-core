---
name: drynor-osrs-transfer
description: Import or port OSRS maps, assets and gameplay from Gielenor or another revision into Drynor, including native cache conversion and client/server integration.
---

# OSRS content transfer

Read the owning repository AGENTS.md and drynor-code-standards. Use drynor-content-development for asset/gameplay integration and drynor-kotlin-plugins for hooks. Resolve sibling repositories from the actual checkout. Source projects and reference caches are read-only inputs.

## Workflow

1. Identify what is actually OSRS: compare terrain/landscape archives and map-ID lists, not just region names. Record source revision, region/chunks/planes, XTEA policy and actual source gameplay handlers. A hybrid Gielenor region can already be byte-identical to native Drynor.
   For a large connected continent, derive its bounds from the cache and traversal links. Include subterranean regions, upper floors, interiors, instanced templates and the entrance/exit graph; screenshots and a named region list are only starting coordinates.
2. Inventory dependency closure before allocating IDs: floors, locations, object definitions, NPC/BAS, models, sequences/frames/bases, spots/projectiles, textures/materials/sprites, sounds, items and Java definition overrides. Explicitly record omissions and blockers.
3. Allocate IDs deterministically after inspecting physical destination archives/files and client limits. Import into a new packed output with the baseline preserved. Use strict source grammar and translate to native 727 representation; offsets alone do not convert formats. Refuse unknown payloads instead of silently skipping them.
4. Keep editable JSON/GLB/PNG or existing source representations and verify their binary round trip. Read every imported asset through native decoders and the actual client consumers. Compare all unaffected archive hashes, accounting for shared blobs such as materials.
5. Port source behavior through Drynor lifecycle, combat, pathfinding and persistence APIs. Preserve source mechanics and local gameplay customizations. Use Kotlin in org.drynor, compiled startup hooks and the dialogue DSL. Reference checkouts must not become runtime dependencies.
6. Keep diagnostic and normal encounter entry modes explicit: XP, drops, completion and persistence are separate gates. Diagnostic commands must not turn an existing session into a reward-bearing attempt.
7. Validate in stages: static map, instanced map, passive actors, animations/effects, collision/line of sight, combat, full encounter, logout/resume/death/exit and rewards. Distinguish automated checks from user-confirmed client tests. Build one Gradle project at a time when composite outputs are shared.
8. Promote only the validated cache with matching server/client artifacts. Keep portable runtime bindings, original cache and rollback artifacts outside Git. Disable diagnostic commands in production. Deployment and publishing require authorization from the current user task; the skill itself grants none.

For known Inferno pitfalls, conversion entry points and reusable evidence, read [references/inferno-lessons.md](references/inferno-lessons.md). Treat concrete IDs as historical evidence and recheck collisions for each import.

For animated imported scenery, native water/world-map conversion, equipment/pets and complete encounter rewards, read [references/zulrah-lessons.md](references/zulrah-lessons.md). These are observed pipeline constraints, not a mandate to copy source economic rates or historical IDs into a new feature.

The current Zeah/Kourend/Kebos/Wintertodt transfer is tracked in `A:/dev/drynor/drynor-world-server/docs/CONTENT_OSRS_ZEAH_ROADMAP.md`. Its inventory must cover object animations, NPCs, items and every linked cave before bulk conversion or a completeness claim.
The read-only `osrs inventory-zeah-regions <source-cache> <source-Settings.java> <source-packed.mcx> <target-cache> <target-xteaKeys.json> <new-report.json> <min-x> <max-x> <min-y> <max-y>` command compares candidate map archives, donor OSRS_MAP_IDS and raw landscape object IDs. Its rectangular input is an exploration bound, not the final geography. The current Gielenor `packed.mcx` is empty; report absent keys rather than inventing them. Load the target XTEAs when comparing existing Drynor landscapes, or encrypted archives appear unreadable. Unreadable source landscapes and raw IDs still need classification before conversion.
Gielenor `Region.java` adds `Settings.OSRS_OBJECTS_OFFSET` to raw placement IDs only for its OSRS_MAP_IDS. The Zeah inventory found destination IDs already occupied with different definition bytes, so map placements and definitions must be remapped together. Never overwrite a Drynor object merely because the donor runtime ID matches.
The inventory's proposed object remaps are provisional per rectangle. Union all selected surface, cave and instance IDs and run one global allocation before import; separate regional plans can assign the same replacement ID to different assets.
Use read-only `osrs plan-zeah-object-ids <target-cache> <new-plan.json> <region-report.json> [more-reports...]` to union matching inventories. It verifies the donor Settings hash and source object payload hashes before allocating. `scopeComplete=false` means the output is evidence for further planning, not a production ID manifest.
The planner also follows object morph references recursively; a plan based only on directly placed definitions is incomplete. Run `osrs audit-zeah-object-deps <source-cache> <object-id-plan.json> <new-audit.json>` to strictly parse the closure and check source model/sequence presence. Presence does not prove frames/bases, native conversion or client rendering.
