# Cache archive and file names (727)

Some revision-727 JS5 indices store a 32-bit hash of an archive's (or file's) name in their
reference table, so the client can ask for `"m50_50"` instead of an id. The hash is one-way, so a
name is only "known" when a string is found that re-hashes to the recorded one.

**These are cache-layout names, not gameval asset names.** They address a JS5 `(archive, file)`
coordinate rather than a type id, so they do not belong in `gamevals/<type>.json` and there is no
catalog for them - see `gamevals/CONTRACT.md`, "Index 5" and "Index 23". The names below are not
worth storing either, because they are *built*: this file records the formula and a couple of
checked examples so anyone can regenerate the whole set from the cache at any time.

Every count and example on this page was re-checked against the cache through
`world.gregs.voidps.cache.Cache.archiveNameHashes` / `fileId`, which recompute
`String.hashCode()` against the index's own reference table.

## Index 5 - map squares

The name is a pure function of the region coordinates. There is **no separator** between the
letter and the first coordinate; only the two coordinates are separated by `_`.

    m<regionX>_<regionY>     terrain, height, overlay/underlay
    l<regionX>_<regionY>     placed locations (XTEA encrypted)
    n<regionX>_<regionY>     npc spawns
    um<regionX>_<regionY>    underwater terrain
    ul<regionX>_<regionY>    underwater locations

The client composes the first two in `MapRegion.getArchiveName(floorData, underwater, regionX,
regionY)` - `"m"`/`"l"` + regionX + `'_'` + regionY, prefixed with `"u"` when underwater - and its
callers ask for the npc archive as `"n" + regionX + '_' + regionY`.

The server composes them through `world.gregs.voidps.cache.MapSquare`, which takes either
`(regionX, regionY)` or a packed region id. `MapSquareTest` asserts the spelling and the recall
below.

Verified: all **7,268** named index-5 archives are accounted for by these five shapes and nothing
else - 2,407 `m`, 2,407 `l`, 1,208 `um`, 1,208 `ul`, 38 `n`. Examples: `m50_50` is archive 1585,
`l50_50` is 1586.

Mapsquare names are never renamed, only added to as the map grows, so a new region needs no
recovery work: build the name and ask the cache for it.

## Index 30 - native libraries

    <os>/<arch>/<library file name>

- `os` is `windows`, `linux` or `macos`.
- `arch` is `x86`, `x86_64`, `ppc` or `universal` (the client's `ppc` and `universal` branches are
  macOS-only in practice).
- the library file name is the platform spelling of one of `hw3d`, `sw3d`, `jaggl`, `jaclib`,
  `jagdx`: `<name>.dll` on Windows, `lib<name>.so` on Linux, `lib<name>.dylib` on macOS.

The client builds the same string in `NativeLibraryLoader`, appending `"windows/"` / `"linux/"` /
`"macos/"`, then `"x86_64/"` / `"x86/"` / `"ppc/"` / `"universal/"`, and takes the file name from
its `getCompatibleDll` spelling rules.

Verified: the 60 strings this formula generates cover **all 33** named index-30 archives, with none
left over. Examples: archive 1 is `windows/x86/hw3d.dll`, archive 12 is
`macos/universal/libhw3d.dylib`, archive 24 is `linux/x86/libjaclib.so`.

`world.gregs.voidps.cache.NativeLibrary` is the formula in code, and the cache source tree lays the
index out at exactly these paths - `dlls/windows/x86/hw3d.dll` (`docs/cache-source.md`, *native
libraries*). The name is display only: the tree records the hash, and packing hashes the path back
and looks it up, so the recovered names never become a source of hashes.

## Index 31 - shaders

Two archives, one per renderer: **`gl`** (archive 1) and **`dx`** (archive 3). The client asks for
`"gl"` by name at its shader fetch site.

The 112 file names do not need recovering either, because **the archives name their own files**.
Each renderer's program-definition file is a short payload that spells out the program name and the
source files it references, and the reference table hashes the **lowercased** form of exactly those
strings:

| archive | file | payload spells | file names it resolves |
|---|---|---|---|
| `gl` | 3 | `Basic`, `Basic.vertex.glsl`, `Basic.fragment.glsl` | `basic` = 3, `basic.vertex.glsl` = 7, `basic.fragment.glsl` = 6 |
| `dx` | 10 | `Basic`, `Basic.vertex.vs_2_0`, `Basic.fragment.ps_2_0` | `basic.vertex.vs_2_0` = 25, `basic.fragment.ps_2_0` = 23 |

The case matters: `fileId(31, gl, "Basic.fragment.glsl")` is `-1` and
`fileId(31, gl, "basic.fragment.glsl")` is `6`. So to recover the whole index: read the
program-definition payloads, lowercase every printable string in them, and hash.

The GLSL suffixes are `.vertex.glsl` / `.fragment.glsl` (plus `.filter.glsl`, `.fragment2d.glsl`,
`.fragment3d.glsl`, `.passthrough.glsl`, `.blur.glsl`, `.composite.glsl`, `.brightpass.glsl`); the
Direct3D side uses the shader-model spellings `.vs_2_0`, `.ps_2_0`, `.ps_3_0`, sometimes with a
trailing permutation number.

`ShaderCodec` does exactly that recovery while it unpacks - it reads the program definitions of the
archive it was handed and hashes the lower case of every string they spell - and lays the index out
at `shaders/gl/basic.vertex.glsl` (`docs/cache-source.md`, *shaders*). All **112** file names and
both archive names come back; nothing is stored for it, and the hashes in `index.json` stay the
truth.

## Index 10 - huffman

One archive, named `huffman` (archive 1) - the name the client's `CacheableHuffman` asks for.

## Index 23 - world map

Index 23 is the one index here whose names are **not** a formula. Its `details` archive names the
45 world-map **areas**, and those are a real 727 type with a catalog: `gamevals/worldmap.json`. The
rest of the index is `<area>_staticelements` archives whose files are individual placed map icons,
addressed by their JS5 coordinate, so they get no catalog for the same reason map squares do not.

Those file names were recovered once and cannot be rebuilt from a rule - 130 of them are
`quest_<questname>` icon names that only a lexicon search found - so they are kept verbatim in
`docs/reference/worldmap-cache-names.json`: every index-23 archive and file with its recorded name
hash, the recovered name where there is one, the channel and rule that found it, and each
static-element file's decoded payload. It covers 86 of 86 archives and 731 of 1,181 files. It is
reference data, not a catalog: nothing loads it, and `core` does not bundle it.

`gamevals/CONTRACT.md`, "Index 23 (world_map)", explains why none of those names may move into
`mapelement.json`.

## Indices with names still unrecovered

`loading_graphics` (32), `loading_graphics_raw` (34) and `game_tips` (33) carry archive name hashes
that no rule and no search has resolved - 29, 29 and 31 archives respectively. They are recorded
here as a known gap, not an oversight. Nothing in the server needs them; both loading-graphic
indices are addressed by id.
