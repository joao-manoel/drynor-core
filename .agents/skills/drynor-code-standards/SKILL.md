---
name: drynor-code-standards
description: Apply Drynor project conventions when changing its core, lobby, world, cache tools or client integration. Covers repository boundaries, Kotlin interop, game identifiers and migration gates.
---

# Drynor conventions

Read the owning repository's AGENTS.md and the workspace PROJECT_RUNBOOK.md for operations. The workspace contains independent Git repositories; run commands from the owning checkout and commit changes separately. Inspect current branches before work; do not assume a historical migration branch still exists. Preserve gameplay/customizations and the client's RuneLite integration.

- New application code is Kotlin. Existing Java may be edited at interoperability call sites; a syntax-only rewrite is not a migration. Avoid unrelated whole-file conversions during protocol fixes.
- Production packages use org.drynor. Existing com.rs gameplay packages remain until their consumers are migrated. Read-only upstream references are not runtime dependencies.
- Keep imports at the top. Explain non-obvious wire formats, lifecycle or compatibility constraints; omit comments that repeat the code.
- Prefer generated gameval constants when the catalog actually identifies an asset. Verify the identifier against the loaded cache; do not invent a catalog entry to disguise an unknown number.
- For Kotlin/JVM interop, inspect generated accessors before adding methods. Boolean properties named isFoo differ from properties named foo. Parenthesize shifts and masks when porting Java; confirm the original expression's precedence rather than guessing it.
- Change game state on the world thread. I/O runs outside it. Queues need limits, timeout/failure behavior and lifecycle cleanup. Never block a tick waiting for network or MongoDB.
- Lobby owns accounts and social state. World saves must not overwrite authoritative social fields or carry passwords.
- A successful build does not establish client compatibility. Record exactly what automated checks and user flows passed. Remove compatibility dependencies only after consumer migration and artifact checks.
- Use the configured local Windows JDK/Gradle wrapper. Run one Gradle build at a time across composite builds sharing drynor-core outputs. No remote build host is assumed.
- Never commit runtime files, cache payloads, credentials or database volumes. Keep main/spawn gameplay databases isolated.
- The local packed cache at `A:/dev/drynor/cache` is the sole runtime cache; verify its checksum against the VPS cache actually mounted by lobby/world before replacing it. `cache-unpacked` is editable source, not proof of the deployed version. Never rebuild into the active packed directory while a server reads it; keep source builds opt-in and use a separate output.
- Treat saves, Mongo volumes, reference checkouts, source repositories and unmerged branches as retained state. Clean generated artifacts only after checking active processes, path boundaries and whether the artifact can be reproduced.

Load drynor-kotlin-plugins for gameplay hooks, drynor-content-development for content/cache changes, drynor-cs2-engineer for scripts/interfaces and drynor-shops for shops. These skills provide guidance within the user's authorized task; they do not authorize deployments, messages or unrelated changes.
