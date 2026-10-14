# Map environment, lighting, fog and skybox — appearance reference

Extracted from the deobfuscated reference client at `~/darkan/darkan-bot`. This
document is the **appearance** contract for the tools engine. The reference
client's *implementation* is fixed-function GL 1.x with per-frame CPU work and
must not be copied — only the visual result matters.

Reference sources:

| Concern | Reference file |
| --- | --- |
| Environment record + wire format | `scene/environment/Environment.kt` |
| Defaults, fog/sun application, skybox cache | `scene/environment/EnvironmentManager.kt` |
| Map-file opcode loop | `scene/SceneGraph.kt` → `decodeEffectsServer` |
| Fog + sun GL state | `toolkit/OpenGlToolkit.kt` |
| Skybox drawing | `graphics/light/Skybox.kt` |
| Point lights | `graphics/light/PointLight.kt`, `FlickeringEffect.kt` |

## Map file layout

Environment data lives in the **same** `m{regionX}_{regionY}` archive (index 5)
as the terrain tiles, immediately after the `4 * 64 * 64` tile records. The
client reads tiles and effects sequentially from one buffer.

There is **one effects block per map file, covering the whole 64×64 region** —
not one per 8×8 chunk. Two client paths consume it:

- `decodeTilesClient` → `decodeEffectsClient` (`MapRegion.decodeClientUnderlayMasks`):
  the normal, non-instanced path. Parses the file once and applies the single
  decoded `Environment` to **all 8×8 chunks** of the region.
- `decodeTilesServer` → `decodeEffectsServer` (`MapRegion.decodeServerUnderlayMasks`):
  the instanced/built-map path. Re-parses the *entire* file once per destination
  chunk and discards everything outside that chunk, applying the environment to
  the one chunk it is building. This is per-chunk *consumption* of a region-wide
  block, not a per-chunk encoding.

Verified against the cache at `../cache/`: all 2407 map archives decode cleanly,
and **no** archive contains more than one opcode-0 environment block.

Opcode loop:

| Opcode | Meaning |
| --- | --- |
| `0` | `Environment.decode` — sun/fog/cube-texture record |
| `1` | count-prefixed list of `FlickeringEffect` (point lights) |
| `2` | `Environment.decodeHdr` — bloom, brightpass, white point |
| `128` | `Environment.decodeSkybox` — skybox id + position + rotation |
| `129` | per-plane tile light-intensity grid, 4×4 tile blocks |
| other | malformed — the client throws |

### Opcode 0 — `Environment`

A flag byte, then optional fields. Defaults apply when the flag bit is clear.

| Bit | Field | Encoding | Default |
| --- | --- | --- | --- |
| `0x01` | `sunColour` | `int` RGB | `16777215` (white) |
| `0x02` | `sunAmbient` | `ushort / 256.0` | `1.1523438` |
| `0x04` | `sunLight` | `ushort / 256.0` | `0.69921875` |
| `0x08` | `sunBacklight` | `ushort / 256.0` | `1.2` |
| `0x10` | `sunPosition` | three `short` (x, y, z) | `(-50, -60, -50)` |
| `0x20` | `fogColour` | `int` RGB | `13156520` |
| `0x40` | `fogDepth` | `ushort` | `0` |
| `0x80` | cube texture | six `ushort` material ids (+X, −X, +Y, −Y, +Z, −Z) | scene default |

The cube-texture default is the scene-wide one from index 28 (`DEFAULTS`)
archive 1, itself an opcode stream where opcode `1` carries the six `ushort`
material ids. In this cache that is `682, 683, 684, 685, 686, 687`.

### Opcode 2 — HDR

Three unsigned bytes, each decoded as `value * 8 / 255.0`:
`bloom`, `brightpass`, `whitePoint`.

Quirk worth reproducing: the client only applies the `1.0 / 0.25 / 1.0` reset
defaults when the environment record was created by opcode `2` or `128`
(`Environment()` calls `reset()`). When opcode `0` comes first the record is
built by `Environment(buffer, manager)`, which skips `reset()`, so a file with
opcode `0` but no opcode `2` ends up with `bloom = brightpass = whitePoint = 0`.

### Opcode 128 — skybox

`ushort skyboxId`, `short x`, `short y`, `short z`, `ushort rotation`.

### Opcode 129 — light grid

For each of the 4 planes: a signed type byte.
- `0` — plane is unlit (the client zeroes any existing grid for the plane)
- `1` — read `(64/4) * (64/4)` = 256 signed bytes, each an intensity applied to
  a 4×4 tile block, row-major over x then y
- `2` — **copy the plane below** (`lighting[plane] = lighting[plane - 1]`), a
  no-op on plane 0. Only `decodeEffectsServer` treats this as a no-op; the
  normal `decodeEffectsClient` path performs the copy.

Note the block is per **plane**, not per chunk: each `1` entry always carries a
full 64×64-tile region worth of samples. The per-chunk filtering in
`decodeEffectsServer` happens after the read, not in the encoding.

## Writing it back

`MapEnvironmentEncoder` is the inverse of the decoder and is what the Map Editor's
"Save environment to cache" button and `tools env write` go through.

The decoded `MapEnvironment` cannot be encoded from directly, because the decode
step is lossy in ways that all change the *length* of the file:

- absent fields are indistinguishable from fields that happen to hold the default
- the opcode order and any repeated blocks are gone
- light ranges have been clamped, HDR bytes scaled, the 16×16 light samples
  expanded to 64×64, and light-grid mode `2` resolved into a copy

So `MapEnvironmentDecoder.readLayout` first produces an `EnvironmentLayout`, a
byte-faithful echo of the block with presence recorded as nullable fields, and
`buildRegion` derives the renderable view from that. Edits are *merged* into a
layout decoded from the cache: a field whose new value still encodes to what the
file already held is left alone, including staying absent. Only a real change
flips a flag bit.

Verified with `tools env roundtrip`: all **2407** map archives in `../cache/`
decode, re-encode and compare byte-identical, both for the effects block alone and
for the whole archive rebuilt around it. The same command checks every archive
CRC in the index 255 reference table (2407/2407 match), which is what makes the
write path's CRC update trustworthy.

Write-back rules:

- the `4 * 64 * 64` tile records are **spliced verbatim**; only the effects block
  at the tail is regenerated
- the write itself is `Cache.write` on a cache opened writable
  (`FileCache.load(path, writable = true)`, see `docs/cache-source.md`): the group
  is repacked, **recompressed with the compression the archive already used**, its
  version bumped, and its sector chain reused where the new container still fits
  and appended past the end of `main_file_cache.dat2` otherwise, with the sectors
  `fsync`ed before the index entry that points at them
- the archive's CRC, version and file list in the index 255 reference table, and
  the RSA-signed version table derived from it, are updated by the same call; the
  table's own `revision` is left alone, because the version table the client checks
  the signature of is derived from the table containers as they actually are
- the archive is then read straight back off the store, decompressed and compared
  with what was meant to be written, and the reference table is re-read and
  re-decoded from disk to check the CRC, version and file list it now carries
- the original container and the index 5 reference table are backed up before the
  first write of a session
- a plan is refused if the container it was computed from has moved on disk since

## Lighting model

The client runs **two directional lights** plus a global ambient.

```
sunBrightness = (0.7 + brightnessPref * 0.1 + randomColorOffset) * sunAmbient
ambient       = sunBrightness * sunColourRGB          // GL_LIGHT_MODEL_AMBIENT
sunDir        = normalize(sunPosition * 4)            // sunPosition shl 2
light0        = +sunLight     * sunColourRGB  at  sunDir     // key light
light1        = -sunBacklight * sunColourRGB  at -sunDir     // negative backlight
```

`light1` has a **negative** diffuse colour: it darkens surfaces facing away from
the sun rather than lighting them. Modern single-pass equivalent:

```glsl
float ndl  = dot(N, sunDir);
vec3  lit  = ambient
           + max(ndl, 0.0)  * sunLight     * sunColour
           - max(-ndl, 0.0) * sunBacklight * sunColour;
```

`sunColourRGB` unpacks as `r = (c & 0xff0000) / 1.671168e7`, `g = (c & 0xff00) /
65280.0`, `b = (c & 0xff) / 255.0` — note the red divisor is `0xff0000 / 255`,
i.e. plain 0..1 normalisation.

## Fog

Linear fog, computed from the environment record:

```
fogDensity = (fogDepth + 256) << 2        // -1 when the fog preference is off
fogEnd     = farClipDistance - fogOffset - fogMin
fogStart   = max(fogEnd - fogDensity * densityFactor, nearClipDistance)
factor     = clamp((fogEnd - viewDistance) / (fogEnd - fogStart), 0, 1)
colour     = mix(fogColour, colour, factor)
```

Scene defaults are `fogOffset = 0`, `fogMin = 0`, `densityFactor = 1.0`. The
rippling-water effect temporarily switches to `densityFactor = 0.5`.

Fog colour also becomes the background clear colour when no skybox is present.

## Skybox

Two forms, selected by whether the skybox type has a model:

1. **Panoramic material** — a square material graphic scrolled by camera yaw and
   pitch and tiled across the viewport:
   `yOffset = rotationY * height / -4096`, `xOffset = (rotationX + skyboxAngle) *
   height / 4096 + (width - height) / 2`, both taken `% height`. Either repeated
   (`drawRepeatingBackground`) or vertically interpolated between the material's
   first and last pixel colour (`BackgroundMode.COLOR_TRANSITION`).
2. **Sky model** — a mesh drawn around the camera, plus zero or more `Sun`
   billboard projections (lens-flare style) composited on top.

Modern equivalent: one full-screen or cube draw sampling a panoramic/cubemap
texture built once from the material, with the sun billboards as a small
instanced pass. No per-frame CPU blitting.

## Point lights

`FlickeringEffect` wraps a `PointLight` (position, 2D size, colour) with an
animation type. Wire format, in order:

| Field | Encoding |
| --- | --- |
| packed level | `ubyte` — `& 0x7` plane, `& 0x8` grows upwards, `& 0x10` grows downwards |
| x | `ushort` |
| z | `ushort` |
| y (height offset) | `ushort` |
| packed radius | `ubyte` — slice count is `radius * 2 + 1` |
| ranges | `ushort` × slice count — high byte upper height, low byte lower height, both clamped to the slice count |
| colour | `ushort` packed RS HSL |
| packed type | `ubyte` — `& 0x1f` type, `(& 0xe0) shl 3` rotation offset |
| light config id | `ushort`, **only when type == 31** |

Positions are stored in **1/128 tile units**; the client shifts them left by 2
into the scene's 512-units-per-tile space, so `raw shl 2 shr 9` gives the region
tile. The radius expands the same way: `(packedRadius shl 9) + 256` world units.

The stored y is a *downward* offset because RS heights are negative-up: the
client computes `worldY = tileHeight[plane][x][z] - y`. With positive-up heights
(as the tools terrain stores them) that becomes `worldHeight = terrainHeight + y`.

Type `31` pulls its effect/duration/ticker/surrounding fields from the `LIGHT`
config list; all other types map to fixed constants in
`FlickeringEffect.updateConstants()`. In the instanced path lights are rotated
into place with the chunk rotation (`EnvironmentManager.rotatedX/rotatedZ`)
before the tile height is applied.

## Config lists

| List | Index | Archive | Present in `../cache/` |
| --- | --- | --- | --- |
| Skybox | 2 (`CONFIGS`) | 29 | yes — 10 files |
| Sun | 2 (`CONFIGS`) | 30 | **no — archive absent** |
| Light | 2 (`CONFIGS`) | 31 | yes — 34 files |
| Scene default cube texture | 28 (`DEFAULTS`) | 1 | yes |

The sun archive being absent is consistent with the skybox configs themselves:
every skybox type in this cache decodes with `sunIds == null`, so nothing ever
resolves a sun id. Sun/lens-flare billboards are therefore not renderable from
this cache, and `SunType` exists only so the decoder is complete.

`SkyboxType` (archive 29) opcodes, terminated by `0`:
`1` materialId `ushort`, `2` sun id list (`ubyte` count then `ushort`s),
`3` defaultSunIndex `ubyte`, `4` backgroundMode `ubyte` (0 single colour,
1 colour transition), `5` model archiveId `bigsmart`.

`LightType` (archive 31) opcodes, terminated by `0`:
`1` effect `ubyte`, `2` duration `ushort`, `3` ticker `ushort`,
`4` surrounding **signed** `short`. Defaults `0 / 2048 / 2048 / 0`.

`SunType` (archive 30) opcodes, terminated by `0`:
`1` positionOffsetStep `ushort`, `2` invertPosition (flag, no payload),
`3` position x/y/z `short`, `4` actionType `ubyte`, `5` materialId `bigsmart`,
`6` displayColour `umedium`, `7` rotation x/y/z `short`.
