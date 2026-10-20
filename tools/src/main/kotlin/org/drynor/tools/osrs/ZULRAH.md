# Zulrah — inventário e prévia estática

Use JDK26, a partir do drynor-core. Gielenor e baseline são somente leitura; as saídas devem ser novas e ficar fora dos caches. O cache baseline já contém Inferno. Não use o importador específico do Inferno para Zulrah.

```powershell
./gradlew.bat :tools:test :tools:installDist
& "$env:USERPROFILE/.jdks/temurin-26/jdk-26.0.2.1+1/bin/java.exe" -cp 'tools/build/install/tools/lib/*' org.drynor.tools.MainKt osrs inventory-zulrah A:/dev/gielenor/gielenor-source/data/cache A:/dev/drynor/cache A:/dev/drynor/drynor-core/build/osrs-zulrah/new-inventory.json
& "$env:USERPROFILE/.jdks/temurin-26/jdk-26.0.2.1+1/bin/java.exe" -cp 'tools/build/install/tools/lib/*' org.drynor.tools.MainKt osrs preview-zulrah-static A:/dev/gielenor/gielenor-source/data/cache A:/dev/drynor/cache A:/dev/drynor/drynor-core/build/osrs-zulrah/new-static-preview
```

O classpath wildcard evita o limite de linha de comando do launcher Gradle gerado no Windows. A leitura do destino consulta as XTEAs em `../drynor-world-server/data/map/xteaKeys.json`; executar do diretório core.

O inventário separa8751,9007,9008 (OSRS),9263/9264 (nativas) e259 (ausente nos caches, informado por engano). Fonte do encontro: ZulrahShrine copia8x8chunks desde281,379; o ID da instância em execução não é seu mapa físico.

A prévia importa apenas8751/9007/9008 em cache novo. Resolve pisos pelas regras comprovadas do client Gielenor, exige bytes iguais às definições nativas reutilizadas, normaliza somente modelos de18 bytes integralmente zerados com template1077 verificado e recusa materiais/attachments não calibrados. Morphs e objetos com modelos201077/201105 não vazios ainda sem interpretação são omitidos com contagens por região. Essas omissões precisam ser resolvidas para gameplay completo.

A representação em `source-package` é intermediária, recompilada e comparada durante a criação; não é uma SourceTree completa. O pacote final confere novos arquivos, archives anteriores, arquivos preexistentes dos grupos ampliados e hash da baseline. Apenas os mapas9008 existentes são substituídos na cópia, com XTEA original preservado.

No world, `start-zulrah-preview.ps1` usa `DRYNOR_ZULRAH_PREVIEW` e uma cópia dos bindings Inferno apontada ao cache novo; não altera configuração/cache padrão. O comando `zulrahtest` só existe nesse modo. A primeira validação é visual e de movimentação, sem NPCs, animações, itens, combate ou recompensas Zulrah. Consulte o roadmap em `drynor-world-server/docs/CONTENT_OSRS_ZULRAH_ROADMAP.md` para os gates seguintes.

## Native world map extension

The static importer also includes region8495 west of Zul-Andra. Build from the preserved original baseline into a fresh output. Then run:

    osrs preview-zulrah-worldmap <static-preview-root> <new-output-root>

The input manifest must include8495/8751/9007/9008. The world map tool generates native727 main/area records from the converted scene floors and level0 locations (including bridge adjustment), preserves palette bytes and all other region/chunk records, and verifies every other archive plus sibling main files. It never copies Gielenor runtime offsets or replaces details.

Local worldmap-preview-v1:64 tools tests passed;577 untouched records and141562 other archives verified. All four regions contain4096 painted tiles. Actual world-map appearance remains a user client validation gate. Static omissions remain listed in the manifest.

The surroundings extension now builds8OSRSregions (8494/8495/8496/8750/8751/9006/9007/9008), then osrs preview-zulrah-surroundings <gielenor-cache> <static-preview-root> <new-output-root> adapts native8752/9263/9264. Native terrain/environment/JSON round trips are exact; native object geometry and collision are compared while Drynor definitions are retained. The world map builder patches only the8identity-mapped regions and preserves the projected SoulWars overview over the three southern ocean squares. Local output worldmap-preview-v2 contains11scene regions;68toolstests and actualworld MapRegion readback of all11passed. Static object omissions are listed in the manifest.

## Water fallback and donor atmosphere

Run osrs preview-zulrah-water <input-preview-root> <new-output-root>. It replaces/adds the8OSRS underwater terrain archives with native single-plane maps derived from converted surface plane0; height31 means992engine units, nearest native representation to donor1000. It preserves underwater locations and native surrounding maps. The donor poison-swamp atmosphere is encoded only on surface8751/9007/9008, leaving allterrain tile bytes identical. Editable MapCodec round trips and full unaffected archive comparisons are mandatory. Index23 is unchanged.

Local water-preview-v2 passed73 tools tests and actualJava19clientMapLoader readback of32768 underwater tiles. Visual Low/High confirmation is still required; this is a static preview, with existing OSRS object omissions and no Zulrah encounter.

## Passive actor package

Run osrs preview-zulrah-actors <gielenor-cache> <input-preview-root> <new-output-root>. The package contains4NPCs/4BAS,7models,15sequences,178unique frames,5bases,3spots and1animatedgas object. IDs are collision-checked and recorded in zulrah-actor-preview.json. Actors raw bins accompany editableGLB/JSON for actualclientdecoder validation. Existing archive containers and sibling files are compared; maps, water, atmosphere and index23 are preserved.

Source sequence opcode4 is a no-payload local flag discarded by the Gielenor client and is accepted as such; unknown opcodes still fail. Sound/equipment/gameplay overrides are outside this passive stage. Local actors-preview-v1 passed78tools tests and client verifyZulrahAssets; rendered visual validation remains manual.


## Native equipment package

Run `osrs preview-zulrah-items <gielenor-cache> <actors-preview-root> <new-output-root>`. Local output `items-preview-v1` preserves actors/maps/water/Inferno and adds37 item definitions,34 meshes,2 sequences,16 frames,2 bases and1 spot (92 assets). Manifest `zulrah-item-preview.json` contains deterministic native mappings and raw binaries under `items/{items,models,sequences,frames,bases,spots}`. Editable JSON/GLB round trips and141653 unaffected archives were verified.83 tools tests plus the focused core special-parameter regression passed.

Player sequence25061 uses donor native skeleton0, copied into a fresh base6701 with donor type6 normalization; OSRS frame translation/rotation scaling remains applied. `CustomAnims` explicitly clears its hand replacements, which is recorded instead of importing unrelated equipment. Nonzero raw equipment offsets discarded by the donor are recorded; explicit donor worn translations are baked into four independent meshes for the native727 avatar, avoiding quantization by native quarter-unit offset fields. Native style686=13 and special_attack687=1 are separate. Placeholder links are deliberately omitted as in the donor; notes and scale-stack dependencies remain closed.

The actual client probe passed all37 item records and their mesh/animation dependencies. This establishes decoder compatibility, not rendered equipment/combat validation. See the world roadmap for the local kit and manual acceptance flows.

## Full encounter equipment and reward closure

Run `osrs preview-zulrah-full <gielenor-cache> <items-preview-root> <new-output-root>`. This additive build retains all previous native item mappings and adds sea/swamp tridents, staff of the dead/toxic staff, three pet snakeling items/NPCs/BAS, their model/animation closure, trident cast/projectile/impact spots, and the dragon hasta used by the donor rare drop table. Linked notes remain closed. The three pet NPCs keep native IDs22130/22131/22132; encounter actor bindings remain separate.

Local `full-preview-v5` adds178 assets and verifies141689 untouched archive containers plus unchanged sibling files, editable round trips, packed readback and both read-only inputs.85 tools tests passed. A read-only native decoder probe checked all86 distinct native item IDs referenced by the donor Zulrah table and its rare drop table. Runtime gameplay, actual client decoding and rendered acceptance are separate gates owned by the world/client integration.

The full marker `zulrah-full-content.json` explicitly binds the existing sacrificial boat110068. The importer checks its name, Board option, size, collision, model types/IDs and canonical source/native mesh equality before reusing it; no unused replacement object is allocated. The native landscape placement is preserved. Weapon style/BAS come from existing native polypore22494 and staff15486 definitions, avoiding donor enum assumptions. Source staff bonuses and special-bar parameter are applied separately, and source native-avatar weapon translations are baked into independent worn meshes. The original default cache and the accepted equipment preview remain unchanged.

Dragon hasta52731 maps to30054 and is wearable in slot3. Its explicit donor overrides (`CustomItems.java:3803`) preserve speed4, stab/slash/crush55, negative defences, strength60 and Attack60; native bronze-hasta11367 supplies only BAS28/style14. Its special spot6369 has a closed native model/sequence/frame/base import. The world implements the separate all-energy special policy from donor `PlayerCombat.java:3177`; an asset import alone does not provide that behavior.


## Animated scene locations and Inferno pets

Run `osrs preview-zulrah-live <gielenor-cache> <full-preview-root> <new-output-root>`. It matches existing native placements against donor positions, plane, shape and rotation across all eight imported Zulrah regions and the hybrid Inferno9042 scene. Native surrounding maps already retain their native animation references. Source Inferno9043 is relocated to9042 in this donor; the tool follows the actual landscape archive.

`live-preview-v2` adds/replaces230 assets: restores13 animated object definitions with zero unmatched placements and imports two Inferno pet item/NPC/BAS pairs,13 sequences,183 frames,11 bases and four new meshes. All141736 other archive containers and untouched sibling files compare exactly. No landscape or world-map archive changes. Every object's non-animation fields recover byte-for-byte after restoring the old animation fields; reused object meshes match donor canonical geometry and skinning. Eleven unchanged baseline object meshes are exported separately under `live/object-models` for client scene validation. New raw assets are under `live`, with editable JSON/GLB in `live-source-package` and mappings in `zulrah-live-assets.json`; old manifests stay intact.

Jal-Nib-Rek donor51291 maps to item30056/NPC27675/BAS2601; TzRek-Zuk52319 maps to item30057/NPC28011/BAS2602. Gameplay pet registration/drop/morph is implemented separately by the world. Torch/fire assets retain source frame durations and looping behavior. Scene-only sequence21363 contains a player hand replacement819; native ObjectType animation never consumes player equipment, so this field is deliberately cleared only in its scene context and reported. Sound dependencies are absent. Actor imports retain strict equipment dependency checks. Existing ambient sounds, map icons and previously omitted morph/model objects remain unchanged.

Animated-object opcode24 restoration has a focused regression test, including exact recovery of all previous scene fields and rejection of reserved animation65535.86 tools tests passed. The real Java19 client probe read all13 objects/13 sequences/183 frames/11 bases and both pet NPC/BAS pairs in live-preview-v1. Final live-preview-v2 has SHA256-identical packed-cache files, with its manifest corrected to explicitly mark the scene-only equipment omission. Rendered torch/fire and pet behavior remain user validation gates.
