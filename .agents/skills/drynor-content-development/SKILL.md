---
name: drynor-content-development
description: Develop or port Drynor gameplay and cache assets while preserving custom content. Use for items, NPCs, quests, animations, asset imports and gameplay/cache integration.
---

# Content and cache integration

Establish the actual cache source path from DRYNOR_CACHE_SOURCE, build properties and the checkout. Do not assume the reference tree's custom assets or paths exist in Drynor. Read definitions, gameval catalogs and client scripts before allocating or changing IDs.

1. Trace the feature through the world handler, cache definition and client consumer. Record the behavior that must survive migration.
2. Reuse an existing asset only when the definitions and client behavior support it. New IDs need collision checks, deterministic allocation and meaningful catalog names.
3. Edit the source representation, build into a separate packed output and read it back through the current decoder. Compare intended fields and unaffected archive signatures against a baseline captured before editing.
4. Connect the world logic using current model/codec APIs. Preserve Drynor-specific rewards, rates, permissions and controller behavior.
5. Validate the affected engine invariants and user flow. Report which checks were automated and which the user confirmed. Never claim a live client test that was not performed.

When cloning a source definition, inspect opcodeOrder and shadowedPayloads: preserved opcode order may suppress a newly added field. Verify the decoder/encoder round trip rather than removing metadata blindly. New archives may also need index metadata; files inside an existing archive use that archive's addressing scheme.

For cross-revision imports, enumerate the dependency closure: models, frames/bases, textures/sprites/materials, particles and billboards. Calibrate conversion against assets shared by both revisions before translating new ones. Source bytes and the 727 client's reader establish signedness, coordinate conventions and unsupported opcodes; another server's decoder alone is insufficient evidence.

For UI state, decide whether it belongs in a server save, a client var or a transient packet. Use var hooks for state the client must redraw itself, without changing an existing custom setting's ownership. Check varbit overlap before allocating.

Load drynor-kotlin-plugins for registrations, drynor-cs2-engineer for UI/scripts, and drynor-shops for shop inventory/NPC wiring. Build and restart the affected local services only as authorized; no Linux-only helper or remote build host is assumed.
