# Zulrah transfer lessons

## Locate the real data

Resolve repositories from the owning checkout and keep donor caches/source read-only. Gielenor is hybrid: original OSRS Inferno9043 is stored as9042, while the Zulrah scene uses eight OSRS regions plus three native surroundings. Derive actual archive names, addressing bits, XTEA and native placement IDs from the loaders and packed cache. A new definition allocation does not move an existing landscape placement. Reuse the existing sacrificial boat only after checking its definition and canonical model geometry; allocating a different ID left an unused boat and broke entry.

Relevant core entry points are `ZulrahStaticPreview`, `ZulrahNativeSurroundings`, `ZulrahWorldMapPreview`, `ZulrahWaterPreview`, `ZulrahActorPreview`, `ZulrahItemsPreview` and `ZulrahLiveAssets`. These form additive immutable outputs, not an instruction to rebuild every stage for every change.

## Scene, overview and animation are separate

World-map index23 appearance does not establish scene terrain, underwater geometry or collision. Native overview coordinates can project another area: the three southern ocean squares overlap Soul Wars in this cache, so their physical maps were imported without replacing that overview. Native water height31 gives992 engine units, the nearest representation to donor1000; validate the actual MapLoader and Low/High water modes. Import regional atmosphere separately without rewriting unchanged tile bytes.

The static preview intentionally strips object animations, sounds and map icons. It is not a finished scene. Discover animated definitions from every imported landscape; match existing donor/native placements by coordinates, plane, shape and rotation. Restore native animation fields with full sequence/frame/base closure while retaining collision and other fields. Verify that reverting only the animation fields recovers the original canonical bytes. Native object decoding/re-encoding must actually emit opcode24.

Export unchanged baseline object meshes alongside new animation binaries for the real client probe. Compare canonical donor/native mesh geometry and skin groups: readable sequences alone do not establish moving scenery. Zulrah's final live package restored13 definitions, including Torch/Fire, without changing landscapes. Scene-only sequence21363 carries a player hand replacement; the scene ObjectType consumer does not use that field. Its deliberate omission is scoped and reported. Do not relax actor equipment/sound dependency checks globally.

## Equipment and pet closure

Import Java item overrides, not just raw definitions. Wield slot, bonuses, requirements, speed, native BAS and weapon style must be functional; a readable Dragon hasta with Wield but wearPos=-1 was still unusable loot. Native parameter686 selects weapon style;687 enables the special bar. Use verified native counterparts for style/BAS and apply source-specific bonuses separately. Polypore style28 fits powered tridents; blowpipe uses ranged style13. Preserve note/stack links, equipped options and saved charges. Translate donor equipment option parameters to native ones rather than treating them as interchangeable.

Player animation skeleton0 follows the donor's native humanoid grammar, whereas its frames still use the OSRS transforms. Use the audited base0 path and known source hand overrides; do not force all skeletons through one decoder. Bake donor avatar translations into independent worn meshes when native offset quantization loses precision. Validate male/female equipment and attack visuals in the client.

Pet import includes item icons, NPC/BAS, movement sequences, models and morph counterparts. Inferno's donor pair is Jal-Nib-Rek51291/NPC27675 and TzRek-Zuk52319/NPC28011; Zulrah has three forms. Reallocate from actual occupancy and publish mappings before world enum registrations. Source pet membership/skill requirements and morph behavior are gameplay, not cache effects.

## Full encounter and economy gates

Trace actual source action order, timings, prayer multipliers and hitpoint scale before tuning. Reuse native combat XP calculation and configured server rates. The controller XP hook permits finite positive combat XP only during an active fight; do not add a second XP award in NPC death or poison callbacks. Passive previews and diagnostic cancellation must not produce completion rewards.

Fix normal/reward mode at entry. Give normal attempts an idempotent completion receipt; do not let diagnostic commands toggle reward mode or reroll drops. Preserve owned ground loot after victory by retaining the cleared arena for collection, and accept at most one fight per boat entry. Check full inventory, leaving, death, logout, duplicate callbacks and restarting an already completed encounter.

Read global and per-NPC modifiers in addition to drop tables. This donor uses `LuckyPets.PET_DROP_RATE=2`: base Inferno100 and Zulrah4000 become denominators50 and2000. Zulrah also sets its NPC drop factor4. These are distinct factors; do not apply them twice, confuse them with `Settings.DROP_RATE`, or assume they match Drynor policy. Include rare-table foreign items and all usable equipment behaviors in dependency closure.

## Promotion evidence

Keep original cache, source snapshots and rollback outside Git. Compare untouched archive containers and sibling files, editable round trips and packed readback. Run actual client readers and label rendered user validation separately. Retain source provenance in development reports, but generate portable production bindings with relative paths resolved against their root; do not deploy local absolute Windows paths. Bind world/lobby/client to the same validated cache, expose normal entry, and keep diagnostic commands disabled in production. Publish/deploy only within current user authorization.
