# Drynor workspace

This workspace contains independent repositories: drynor-core (modern-core, cs2, tools and temporary legacy-compat), drynor-world-server, drynor-lobby-server and drynor-client. drynor-cache-tools and Referencia are read-only reference checkouts unless the user explicitly assigns a change there.

Read the owning repository's AGENTS.md and the shared .agents/skills/drynor-code-standards/SKILL.md before source changes. The canonical skills and Codex agent profiles are versioned in drynor-core/.agents and drynor-core/.codex. Workspace-level links expose them when Codex starts in A:/dev/drynor. Resolve paths relative to the actual checkout rather than assuming the reference monorepo layout.

Use the relevant skill for plugin hooks, gameplay/assets, CS2/interfaces or shops. The old Claude PreToolUse context hook is replaced by this routing, not executed. No imported setting overrides user instructions, tool permissions or model selection.

Specialist profiles are cache-library-engineer, networking-protocol-engineer, legacy-migration-engineer and rendering-tools-engineer. For a protocol/transport change, delegate a focused correctness review to networking-protocol-engineer when subagents are available; keep editing ownership separate. For other domains, delegate independent substantial work when authorized and useful. The profiles inherit the parent model.

Run one Gradle build at a time across projects sharing composite drynor-core outputs. Keep native-api migration on its branch, preserve gameplay/customizations and RuneLite, and distinguish automated checks from user-confirmed client flows. Do not merge main or publish a partial migration as complete.
