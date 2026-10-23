# Drynor core

Read .agents/skills/drynor-code-standards/SKILL.md before source edits. Shared workspace conventions and specialist routing are in .codex/workspace-AGENTS.md; sibling checkouts have their own AGENTS.md and retain their local requirements.

The native runtime lives in modern-core, with separate cs2 and tools modules. legacy-compat is temporary migration infrastructure, not a destination for new implementations. Production native code must not import compatibility classes. Keep current codecs/models and use the read-only reference/client sources for protocol evidence. Use `WORKSPACE_RUNBOOK.md` for current workspace operations; historical migration notes are not startup instructions.

Use the specialized Codex profiles in .codex/agents for substantial cache, network, migration or rendering tasks. For wire/transport changes, request a focused network correctness review when delegation is available. Inherit the parent model; do not hardcode an alternative model.

Run Gradle with the configured JDK 26. Avoid concurrent composite builds sharing this checkout's outputs. Use targeted core/CS2 tests and relevant cache probes; report client compatibility only after the actual flow is verified.

For OSRS map/content transfers, read .agents/skills/drynor-osrs-transfer/SKILL.md. The osrs-content-engineer profile is available for explicitly delegated transfer work.
