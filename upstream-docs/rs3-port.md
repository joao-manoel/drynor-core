# Porting RS3 assets into the 727 cache

The skilling urns (`docs/reference/rs3-urns.json`) were the first assets brought across from the
RS3 revision-949 tree, and the golden loot beam spotanims (`docs/reference/rs3-loot-beams.json`)
the second. The RS3 tree is `~/drynor/drynor-3-undercut/unpacked-cache`, read-only; everything
written lands in `cache-source/` under next-free ids.

## What crosses, and what does not

Same-id assets exist in both caches, and they are the calibration corpus: converting them from the
RS3 side and diffing against the shipped 727 files is how every converter is proven. Face order
differs between the two (RS3 concatenates submeshes), so faces are paired by their vertex-position
triple.

### Models (`models_rt7/<id>.glb`)

Measured on the 135 shared urn models and on the 1,858 shared models that carry particle or
billboard attachments (1.37 million matched faces):

| RS3 | 727 | measured |
|---|---|---|
| `POSITION` integers, split per normal and per UV | the same integers with `y` negated, welded by position and skin label; every face's two trailing indices swapped (the mirror reverses winding) | exact on 134/135 urns |
| `extras.rs.streamB` (ushort per vertex) | the HSL16 face colour | 100% |
| `submeshes[i][1]` | the face priority | 96.65% (RS3 "all 0" is 727's uniform 4) |
| `submeshes[i][2]` | the RS3 material id **plus one**, 0 = none; the id is crosswalked, never copied | 100% |
| `streamA` / `streamC` | `vertexSkins` / `faceSkins` (65535 is 255) | 98.35% of faces |
| `byteStream` (ubyte per vertex) | opacity: the 727 face alpha is `255 - byteStream` | 99.97% |
| `NORMAL` | render type 1 (flat) when a face's three normals are equal and its submesh flags have neither 0x10 nor 0x20; otherwise 0 | 96.65%; the rest are faces whose RS3 normals are equal but 727 smooths, which RS3 cannot tell apart |
| `TEXCOORD_0` | half floats stored as ushort; the same UV the 727 planar projection gives, same orientation | 99.64% of 1,026,168 corners within 1/64 |
| `COLOR_0`, `bones`, `weights` | nothing (a tangent frame and the RT7 skeleton) | |
| `submeshes[i][0]` flags | 0x02 = the submesh has transparent faces; 0x10 = attachment helper faces (billboards 157, emitters 159); 0x20 = textured | |

**Texture projection.** Where the crosswalk has a template learned on shared models (the urns),
the template is used. Otherwise the converter solves one planar triangle (P, M, N) per textured
face from its RS3 UVs, `P = A - uA*U - vA*V`, `M = P + U`, `N = P + V` with `U`, `V` the in-plane
basis the UVs imply, rounds the corners to synthetic vertices and shares identical triangles. On
the attachment corpus the solved triangles reproduce the RS3 UV with a mean error of 0.0019 (2,070
faces have zero UV area and are refused); on the loot beam rings the worst corner is 0.0017.

**Attachments.** RS3 keeps a model's particle and billboard attachments as extras, and the faces
they sit on are real faces of its geometry, which is what 727 needs:

| RS3 | 727 | measured on the 1,858 shared models |
|---|---|---|
| `attachmentsB[]`: `id` = producer id, three `points` (y negated) = an RS3 face, `a` = skin label | `particleEmitters: [[producer, face]]`, footer flag 0x2 | producer 98.41%, face 94.25% of the 2,520 emitters both hold; of the 145 face misses only 23 have the shipped face in the RS3 geometry at all |
| `attachmentsC[]`: `id` = effector id, one `point` (y negated), `a` = skin label | `particleEffectors: [[effector, vertex]]`, the vertex of a hidden (alpha 255) triangle | 10 of 11 |
| `attachmentsA[].records[]`: `x/y/z` = centroid of the billboard face (y negated), `f` = its colour, `a` (group) = its priority, `b` = material + 1, `d/e` = the 727 billboard size2d/size3d, `ids[3]` = depth, `h` = distance | `billboards: [[billboard, face, depth, distance]]`, footer flag 0x4 | face 94.97% (every face whose centroid is the record's is picked, 9,090 of 9,091), depth (`ids[3] and 0xff`) 98.11%, distance 99.49% |

The billboard's config is inlined in RS3 and has no id; the 727 id is the lowest billboard with the
crosswalked material and the same sizes, which is the shipped id on 70.06% and a config identical
in every field on 95.88%. RS3's own billboard configs are 727's with both sizes one smaller. 727
keeps the attachments RS3 later dropped (401 emitters, 756 billboards past RS3's last).

### Animations (`anims_rt7/<archive>.json`, `bases/<id>.json`)

Frame archives are renumbered between the revisions and paired through seqs whose durations and
frame file ids are identical: 100 seqs give 74 (727 archive, RS3 archive) pairs.

- An RS3 version 2 frame's `time` is its JS5 file id, and its `masks` are the 727 flag bytes as
  they stand, mode bits included.
- Values: a rotation (type 2) is two values per set axis, `(sin, cos) * 16384`; the 727 value is
  `round(atan2(sin, cos) * 4096 / 2pi)`, **negated on x only**. Type 7 is two values per axis, the
  first kept. Every other type, translation and scale included, is the same integer.
- Of the 57 pairs whose bases have the same types, 49 are exact on every component of every frame.
  The other 8 differ wholesale, in the translations and alphas that need no conversion as much as
  in the rotations: RS3 re-authored them. 17 pairs are incomparable (the base's types changed).
  Rotations agree modulo 4096 and as integers on 2,817 of 2,898; the rest spell the angle 4096
  apart, and the converter writes the nearest signed value.
- Bases: version 4 label bases become 727 `types`, `shadowed` = `unusedA`, `submeshes` =
  `unusedB`, `labels`. Of the 3,379 bases whose types agree, shadowed and submeshes are exact on
  all and labels on 3,346. Skeletal bases (bones, remaps, extras) have no 727 form.

### Seqs and spotanims

- Seq fields copy by name; frames are remapped `(727 archive << 16) | file`. RS3 `unusedRecords`
  13 is byte for byte 727's sound grammar (`[0,3,0,1,25,31,47,0]` = sound 6431 on frame 1), 14,
  15, 16 and 18 are 727's flags, and 119 and 120 are RS3-only. A held obj is refused: RS3 obj ids
  are not 727's.
- Spotanim: `scaleX/scaleY * 128` = sizeXY/sizeZ, `unknown10` = 727 opcode 10 (`aBoolean2402`),
  opcode 46 dropped; any other non-default field is refused until it is calibrated.

### Particles (`config_particle/producer`, `config_particle/particle`)

Producers and effectors keep their ids across revisions, so every shared id is a calibration pair.

- Producers: RS3 `unknownN[a-d]` is 727 opcode N field by field (`1a-d` angles, `3` speeds, `4`
  speed update, `5` size, `6` start colours, `7` lifetimes, `8` rates, `9`/`10`/`25` id lists,
  `12`/`13` display planes as signed bytes, `14` updates, `15` material, `16a-d`, `17`-`23`, `27`,
  `28`, `31a/b` sizes); the flags are presences (`26` -> `unusedOpcode26 = false`, `30` ->
  `textured`, `32` -> `adjustsLightIntensity = false`, `33` -> `killOverlapping`, `34` ->
  `killAboveSurface = false`). RS3 reads opcode 24 with a byte, so `unknown24: N` is 727's bare
  opcode 24 followed by bare opcode N (24, 26 or 30), which RS3 swallowed. `29`, `35` and `36` have
  no 727 form and are stripped. 716 of the 741 shared producers convert to every field
  (`materialId` aside); the 25 others are RS3 content changes: 729-740 are different producers
  recycled under the same ids, 30/34/38/42/140/203 changed the start colours' alpha byte, 169/499/652
  dropped their secondary particles.
- Effectors: RS3 reads 727's `6, 2` (opcode 6 with its byte) as two flags, so `unknown6` with
  `unknown2` is `particleHandlingType = 2`; `unknown1` = rotation, `unknown4Type/Value` = type and
  sizeMultiplier, `vector` = offsets, `unknown8/9/10` = the three flags. 15 of 15 shared effectors
  are exact. **An imported effector never carries opcode 4.** Jagex's files hold opcode 4 as a byte
  and an int, and the server codec reads it so, but the 727 client reads a bare int
  (`ParticleArchive1Def.java:96-99` in the original decompile; drynor-bot `ParticleType.kt:48`):
  the type is never read and the multiplier's low byte becomes the next opcode. RS3 effector 21
  (type 2, multiplier 1) packed `4, 02, 00 00 00 01, 8, 9, ...`, which the client read as opcode 1
  and a rotation of 0x0809 = 2057, overrunning `COSINE[rotation shl 3]` and crashing tiers 3 and 4.
  Since the client's type is always 0 and the multiplier only matters to types 1 and 2, the
  importer writes type 0 and multiplier 0 and loses nothing the client can draw. The codec writes a particle file's `id` as `1 | file` (archive 1 OR'd in); that is what
  `18.json` holds.
- Producer materials crosswalk by vote over the shared producers (3075 -> 857 on 13, 2914 -> 1498
  on 6).

### Materials, textures, sprites

- The material field map, measured on the 217 RS3/727 material pairs that shared producers,
  billboards and the urn crosswalk agree on unanimously: `isSmall` = `textureSizeCode` 0 (99.54%),
  `detailsOnly` = `unknown30` (98.62%), `brightness` = `unknown31` as a signed byte (100%; 255 is
  -1 on 64 pairs), `alpha` = `unknown32` signed (99.08%), `effectId`/`effectParam1` =
  `unknown23`/`unknown24` (98.62%/98.16%), `hdr` = `unknown13` (97.70%), `effectCombiner` =
  `unknown15` (99.54%), `mipmapping` = `unknown28` (99.08%), `repeatS`/`repeatT` = `packed` bits
  0x1/0x8 (99.54%; the bits co-occur on every pair, so which is S and which T is not proven). RS3
  recomputed the average `colour` (18.89% exact, always close) and it is carried as is. `unknown3`
  is a normal map 727 has no use for. A scrolling texture is refused: the two pairs that scroll do
  not share a unit.
- A 727 texture is an op graph; its sprite op (type 39) hands the texture **RGB only**, the alpha
  comes from the texture's opacity output (its red channel), and a black texel is transparent
  whatever the opacity says (`TextureType.getPixelsArgb`). So a partly transparent RS3 texture
  becomes two sprites, the colour and a grey `<material>_alpha` (named like `xmas09_snow_alpha`),
  and a texture copied from plain sprite texture 6 with its opacity op pointed at the alpha sprite.
- RS3 `textures_png` images are the left-right mirror of the 727 sprites they came from (RS3 16113
  `roof` against 727 graphic 453: zero difference once flipped), so imported PNGs are mirrored.

## The tools

All under `tools rs3port` (headless, never opens the packed cache):

```
rs3port materials <rs3-tree> <727-tree> [spec.json]
rs3port model <rs3-tree> <727-tree> <rs3Id> --id <727Id> --role <role> [--out <dir>]
rs3port calibrate <rs3-tree> <727-tree> [spec.json] [--out <dir>]
rs3port calibrate-frames <rs3-tree> <727-tree>
rs3port calibrate-particles <rs3-tree> <727-tree>
rs3port calibrate-materials <rs3-tree> <727-tree>
rs3port calibrate-attachments <rs3-tree> <727-tree> [--ids <file>] [--limit <n>] [--urns]
rs3port urns <rs3-tree> <727-tree> [spec.json]
rs3port spotanims <rs3-tree> <727-tree> <spec.json>
icon <objId>[,<objId>...] <out.png> [--sheet] [--amount n] [--outline n] [--wiki <dir>] [--glb <dir>]
model <id> --glb <dir>
```

- `materials` learns `docs/reference/rs3-material-crosswalk.json` from the urn corpus: per-face
  (RS3 material id -> 727 material) counts, keyed by the true RS3 id (the glb field minus one),
  unanimous on every urn material, plus the projection template per role and material.
  `docs/reference/rs3-material-overrides.json` wins over the table and is where a material with no
  precedent is decided by hand, with its reason.
- `calibrate` is the urn acceptance gate. On the 135 shared models: geometry exact on 134 (the
  other has one vertex RS3 re-rounded), colours, materials, skins and mapping types 100%,
  priorities 96.65%, and the full projection block exact on 19 models - the rest split one
  material across two cylindrical pointers with different rotations that nothing in RS3
  distinguishes.
- `calibrate-frames`, `calibrate-particles`, `calibrate-materials` and `calibrate-attachments` are
  the gates for everything above, each printing the rates this page quotes and every residual.
- `urns` is the urn importer driven by `docs/reference/rs3-urns.json`.
- `spotanims` is the spotanim importer, driven by a spec such as
  `docs/reference/rs3-loot-beams.json`: the ceilings; the new materials (RS3 id, texture, colour and
  opacity graphic, texture, material); the reused materials and billboards with their reasons; the
  bases, producers and effectors; and per spotanim the RS3 spotanim, model, seq and anim with their
  727 ids and names. It converts the sprites, textures, materials, bases, frame sets, seqs,
  producers, effectors, models and spotanims, and registers the index lines, the catalogs (the
  spotanims under channel `derived`) and `gamevals.d.ts`. Every file goes through its index's own
  codec (encoded, then `unpack`ed), so it is byte for byte what unpacking a cache holding it would
  write, and a file the codec could not round trip is refused. The server codecs are not the
  client, so every packed record is also re-read with the 727 client's own grammar
  (`ClientGrammar`, transcribed from drynor-bot: `ParticleType`, `ParticleProducerType`, `SeqType`,
  `SpotAnimType`, `AnimBase`, `AnimFrame`, `TextureType`, `MaterialTypeList`, `SpriteType`,
  `Mesh.decodeNewFormat`): a record that desyncs, leaves bytes over, reads back a value other than
  the definition's, names an id that does not exist or indexes past a client table fails the
  whole import before anything is written. It refuses to run unless every
  allocated id is exactly next-free from the spec's ceiling in spec order and the tree's highest
  id is either that ceiling or the last allocated one, and a re-run rewrites nothing.
- `opacity` repairs an import that gave a texture no alpha source, driven by a spec such as
  `docs/reference/master-cape-opacity.json`: for each material it takes the colour sprite's own
  alpha channel as a new grey sprite, rewires the texture to the three op shape above with
  `opacity` pointing at it, and registers the sprite like any other new id. The master capes
  needed it - each was a lone sprite op with all three outputs on it, so the client's alpha was the
  colour's red channel (dark where the cape was coloured). A partly transparent RS3 texture is not
  imported until its material is `effectCombiner` 2 and its texture has a real opacity output; the
  two halves are useless apart. It keeps the texture's own trailer - the capes carry none, the only
  29 textures in the tree that do not, and the client never reads those bytes.
- `icon` renders inventory icons; `model <id> --glb <dir>` opens the model viewer on a `.glb` from
  a directory before the cache, which is how a converted model is looked at before packing.

## Porting the next asset

1. Find the whole dependency closure (model, seq, frame sets, bases, materials, textures,
   producers, effectors, billboards) and write a spec with the 727 ids allocated next-free in a
   fixed order. RS3 ids are references only.
2. Reuse an existing 727 asset only on proof (every shared asset agrees), and record the reason in
   the spec; append everything else. A material the crosswalk has not seen is decided in the spec or
   the overrides file after looking at both textures.
3. Run the calibrations the asset depends on and read every residual before trusting a rule.
4. Run the importer, look at the models with `tools model <id> --glb cache-source/models`, pack,
   `cache verify`, and compare the per-index lines against the tree's baseline: the only lines that
   may change are the archives the new ids land in.
5. Register the server side against the generated constants and hand it to the user to check in
   the client; content gets no tests.
