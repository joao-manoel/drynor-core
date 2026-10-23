---
name: drynor-cs2-engineer
description: Author and debug revision 727 CS2 scripts, interface components and graphics in Drynor's unpacked cache. Use for clientscript compilation, UI hook failures and packed-cache readback.
---

# CS2 and interfaces

Resolve the real cache source and read the current cs2/tools command help plus docs/cs2-editor.md and docs/cache-source.md when present. The headless CS2 module depends on core, not rendering tools. Preserve the RuneLite integration; cache UI work is not permission to replace client code.

Before changing a script, compare the active packed cache mounted on the VPS with the local `cache-unpacked` source. The source checkout can be older than production. Compile and verify in a separate packed output; never replace `A:/dev/drynor/cache` through an automatic startup build.

Trace the script, component hooks, var triggers and the actual client reader. Use drynor-client-v2/src/main/kotlin/com/jagex/game/runetek5 and the read-only Referencia/client-reference when applicable. Reference-only custom scripts and IDs are leads, not proof that Drynor contains them.

Compile the affected script, pack to a controlled output, decompile/read back and compare the actual hook signatures and bytecode. For visual changes inspect the packed result at relevant fixed/resizable layouts. Keep source and catalogs synchronized. Do not mark a UI flow validated merely because the compiler passed.

Read [client-rules.md](references/client-rules.md) for common interface failure modes. Verify each rule at the current client's call site before relying on it. Add newly confirmed facts with their source path; do not import reference-only custom opcode numbers as universal facts.

Register one script/function per source file according to the current compiler's grammar. Confirm supported constructs instead of assuming full TypeScript support. Allocate component children contiguously when the client requires it, including hidden per-row placeholders. Resolve composite interface/component IDs correctly and preserve event masks.

Do not execute the reference's client-shot.sh, dev-world.sh or remote-build helpers: they assume another host/display and may restart the wrong services. Use local documented launchers and authorized UI tools. Report rendering evidence separately from a real user interaction test.
