---
name: drynor-osrs-transfer
description: Import or port OSRS maps, assets and gameplay from Gielenor or another revision into Drynor, including native cache conversion and client/server integration.
---

# OSRS content transfer

Read the owning repository AGENTS.md and drynor-code-standards. Use drynor-content-development for asset/gameplay integration and drynor-kotlin-plugins for hooks. Resolve sibling repositories from the actual checkout. Source projects and reference caches are read-only inputs.

## Workflow

1. Identify what is actually OSRS: compare terrain/landscape archives and map-ID lists, not just region names. Record source revision, region/chunks/planes, XTEA policy and actual source gameplay handlers. A hybrid Gielenor region can already be byte-identical to native Drynor.
2. Inventory dependency closure before allocating IDs: floors, locations, object definitions, NPC/BAS, models, sequences/frames/bases, spots/projectiles, textures/materials/sprites, sounds, items and Java definition overrides. Explicitly record omissions and blockers.
3. Allocate IDs deterministically after inspecting physical destination archives/files and client limits. Import into a new packed output with the baseline preserved. Use strict source grammar and translate to native 727 representation; offsets alone do not convert formats. Refuse unknown payloads instead of silently skipping them.
4. Keep editable JSON/GLB/PNG or existing source representations and verify their binary round trip. Read every imported asset through native decoders and the actual client consumers. Compare all unaffected archive hashes, accounting for shared blobs such as materials.
5. Port source behavior through Drynor lifecycle, combat, pathfinding and persistence APIs. Preserve source mechanics and local gameplay customizations. Use Kotlin in org.drynor, compiled startup hooks and the dialogue DSL. Reference checkouts must not become runtime dependencies.
6. Validate in stages: static map, instanced map, passive actors, animations/effects, collision/line of sight, combat, full encounter, logout/resume/death/exit and rewards. Distinguish automated checks from user-confirmed client tests. Build one Gradle project at a time when composite outputs are shared.
7. Promote only the validated cache with matching server/client artifacts. Keep portable runtime bindings, original cache and rollback artifacts outside Git. Disable diagnostic commands in production. Deployment and publishing require authorization from the current user task; the skill itself grants none.

For known Inferno pitfalls, conversion entry points and reusable evidence, read [references/inferno-lessons.md](references/inferno-lessons.md). Treat concrete IDs as historical evidence and recheck collisions for each import.
