---
name: drynor-kotlin-plugins
description: Create or migrate Drynor world gameplay hooks using compiled Kotlin startup functions and the com.rs.plugin.kts DSL. Use for plugin registrations and their event dispatch behavior.
---

# Gameplay plugins

Resolve the world checkout beside drynor-core. Read its PluginManager, PluginMethodRepository, plugin/kts/PluginGlobals and the relevant event/handler before translating registrations. Some DSL sources are under src/main/kotlin while others live in src/main/java.

Register new hooks from a top-level @ServerStartupEvent function in a compiled .kt file. It generates the static zero-argument method expected by discovery. Use the appropriate startup priority only when there is an actual dependency. New shipped content does not need an external .plugin.kts script.

Use onNpcClick/onObjectClick/onItemClick/onButtonClick and other existing DSL functions. Confirm the exact event signature, option filtering, cancellation and dispatch keys. Do not assume a name registration runs alongside an ID registration. Decode network packets separately from gameplay behavior.

When converting a static Handler field, replace the registration itself; object plus @JvmField preserves the old reflection pattern. Search callers before removing public fields or helpers. Keep the conversion within the requested feature; protocol-only edits do not require rewriting unrelated content.

Use the dialogue DSL and ordinary Player extension functions for conversations without independent state. Prefer existing actions and entity-bound scheduling for timed gameplay. World timers need a termination condition. Confirm whether reset/logout cancels a pending entity task before scheduling work after it.

Native definition option numbers are 1-based; old array indexes are not. Verify every translated call. Preserve op filters, item quantities, controller restrictions, cancellation and tick delays. A Kotlin port must preserve behavior, not merely compile.

Validate registration at startup and the affected gameplay flow. Add focused engine/dispatch tests when behavior warrants them, following the repository's testing gates; do not create tests that only mirror handler tables.
