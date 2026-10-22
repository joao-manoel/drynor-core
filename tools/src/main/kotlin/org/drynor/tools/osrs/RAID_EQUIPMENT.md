# Raid equipment transfer

`osrs preview-raid-equipment <gielenor-packed-cache> <baseline-packed-cache> <new-output-root>` builds a new packed cache and portable `equipment-preview.json`. Inputs are read-only. The command refuses an existing output. Runtime bindings use relative `packedCache` and `rawFolder` paths.

The package imports the thirteen requested equipment families, charged/empty scythe and sanguinesti forms, recipe components, notes and the note template. Existing native Max cape/hood, Dragon defender, Master wand and imported Infernal cape are reused only after checking their definitions. Allocation inspects physical item files; it never assumes 30000 is vacant. Native 727 item addressing is index19/archive(id>>8)/file(id&255); BAS is index2/archive32; sequences use index20/archive(id>>7)/file(id&127), models are complete index7 archives.

## Cache reconciliation before allocation

The working cache on 2026-10-22 lacked the already published Inferno and Zulrah imports. The production cache was separately confirmed by the parent task to match all39 file hashes of `build/osrs-zulrah/live-preview-v2/packed-cache`.

`osrs audit-cache-imports <common-ancestor> <current> <published> <report.json>` compares containers first, then individual files where both sides changed. Unknown/undecodable data becomes a conflict, never an inferred import. `osrs merge-cache-imports <report.json> <new-packed-cache>` requires zero conflicts and whole-archive records. It copies current into a fresh output, then copies only selected published containers and their JS5 metadata (archive/file name hashes, CRC, Whirlpool and version). Each resulting container must exactly equal the selected current or published input. File-level conflicts require separate explicit reconciliation.

The audited common ancestor was `before-osrs-inferno-20261018-105403/cache`.531 imported archives were restored with zero conflicts; one current-only archive was preserved. The combined baseline is `build/osrs-raid-equipment/reconciled-baseline/packed-cache`. Neither original input was replaced.

## Source evidence and deliberate adaptations

- Gielenor `src/com/rs/game/player/content/custom/CustomItems.java`: item bonuses, requirements, speed and BAS overrides. Client `src/CustomItems.java`: weapon styles, native avatar translations and leg scale. Server `PlayerCombat.java` provides actual attack animations.
- Dragon hunter crossbow requires **95 Ranged in this donor**. Ancestral requires65 Magic/75 Defence. These values preserve this donor rather than silently substituting current OSRS balance.
- Infernal max cape copies Infernal cape parameters in the donor:8 Strength,2 Prayer,4 melee attack,1 magic/ranged attack,12 defence. The hood has no invented extra bonuses. Creation and all-max requirements belong to world gameplay.
- Donor Dinh's BAS19999 is missing from its packed cache and source marks it TODO. The package uses the donor's valid two-handed maul BAS1747 as a documented stance adaptation; the actual Dinh attack27516 is imported. This requires visual user validation.
- Donor native BAS sets are copied to new free IDs; their native animations are reused and exported with all referenced frames/bases for the real client probe. Foreign Twisted bow20426, Dinh27516 and sanguinesti effects6539..6542 use converted sequence/frame/base closure. Unknown sound/equipment dependencies fail closed.
- Infernal max cape material59 reuses the existing validated Infernal cape's material. Other unexpected material/particle/billboard dependencies fail closed. No global texture replacement occurs.
- Worn meshes preserve source primary-model avatar transforms: weapon/shield/cape/head translations and leg translate5 followed by Y scale135/128. Male/female models are independent. Source equipment parameters451..454 become native528..531.
- Note template30799 is imported and remapped. Native note composition still owns noted icon/name/stack behavior; raw noted records legitimately have the name `null`.

## Latest automated evidence

`preview-v3` has42 new item records,103 model binaries (including avatar variants),6 new BAS,4 spots,6 converted sequences/74 converted frames/5 converted bases. Raw native pose/attack closures are exported additionally; the parent task runs the actual client readers over `gear/`.

All90 tools tests pass. Every imported packed record and editable round trip passed;141776 untouched archives and all pre-existing sibling files in touched archives remain byte-identical to the reconciled baseline. Input archive snapshots are unchanged. A build/readback does not establish rendering, male/female wearing, crafting or combat correctness; those are validated separately by the world/client workflows.

No cache binaries or runtime bindings with absolute local paths belong in Git. Do not promote this package until matching world/client checks and user flows pass.

## Current OSRS Scythe and Twisted bow balance (preview-v4)

A later authorized balance pass intentionally overrides the donor for these two weapons. The current OSRS Wiki equipment tables were checked on2026-10-22:

- <https://oldschool.runescape.wiki/w/Scythe_of_Vitur>: both forms80 Attack/90 Strength. Charged attack70/125/30/-6, defence-2/8/10, Strength75. Empty attack50/75/10/-6, defence-2/6/0, Strength50. Native Strength parameter641 stores ten times the displayed bonus.
- <https://oldschool.runescape.wiki/w/Twisted_bow>:85 Ranged,0 Prayer (the donor still had75 Ranged/+4 Prayer). Ranged accuracy70 and Strength20 remain.
- <https://oldschool.runescape.wiki/w/2h_slot_table> corroborates the charged Scythe table. The wiki's prose currently says a+4 slash defence charging gain, while its explicit tables show+6 empty/+8 charged; this implementation follows the tables.

`preview-v4` retains exactly the same allocation and visual assets asv3. Hash comparison of every exported raw asset found only three changed definitions:30059,30084 and30095. No models, sequences, frames, bases, BAS or effects changed.91 tools tests pass, including a table-level regression covering both Scythe forms and the Twisted bow requirements/prayer. Full packed/editable checks and141776 untouched baseline archives remain verified. Client-reader and user-rendered verification remain separate gates.
