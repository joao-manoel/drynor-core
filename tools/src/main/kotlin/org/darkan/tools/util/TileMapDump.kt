package org.darkan.tools.util

import org.darkan.core.map.MapXteas
import org.darkan.tools.cache.terrain.TerrainLoader
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index

/**
 * Headless diagnostic that prints an ASCII map of a region's tile data so overlay
 * layout (water pools, mud borders, paths) can be inspected without launching the viewer.
 *
 * Usage: tools tilemap <regionX> <regionY> [plane] [x0] [y0] [x1] [y1]
 */
fun runTileMapDump(cache: Cache, args: List<String>) {
    val regionX = args.getOrNull(0)?.toIntOrNull() ?: 54
    val regionY = args.getOrNull(1)?.toIntOrNull() ?: 54
    val plane = args.getOrNull(2)?.toIntOrNull() ?: 0
    val x0 = args.getOrNull(3)?.toIntOrNull() ?: 0
    val y0 = args.getOrNull(4)?.toIntOrNull() ?: 0
    val x1 = args.getOrNull(5)?.toIntOrNull() ?: 63
    val y1 = args.getOrNull(6)?.toIntOrNull() ?: 63

    TerrainLoader.loadDefinitions(cache)
    if (!MapXteas.isLoaded()) MapXteas.load()

    val region = TerrainLoader.loadRegion(cache, regionX, regionY)
    if (region == null) {
        println("ERROR: could not load region ($regionX, $regionY)")
        return
    }
    val tiles = region.tiles[plane]

    val overlayIds = sortedSetOf<Int>()
    val underlayIds = sortedSetOf<Int>()
    for (x in x0..x1) for (y in y0..y1) {
        val t = tiles[x][y]
        if (t.overlayId > 0) overlayIds.add(t.overlayId)
        if (t.underlayId > 0) underlayIds.add(t.underlayId)
    }

    println("=== TILE MAP ($regionX,$regionY) plane $plane  x:$x0..$x1  y:$y0..$y1 ===")
    println()
    println("--- MAP ARCHIVES ---")
    for (prefix in listOf("m", "l", "um", "ul", "un", "uw")) {
        val name = "$prefix${regionX}_$regionY"
        println("  $name -> archiveId=${cache.archiveId(Index.MAPS, name)}")
    }
    println()
    println("--- OVERLAY LEGEND ---")
    for (id in overlayIds) {
        val d = TerrainLoader.getOverlayDefinition(id)
        println(
            "  overlay $id: texture=${d?.texture} scale=${d?.scale} colour=${d?.colour} " +
                "blendColour=${d?.blendColour} underlayOverrides=${d?.underlayOverrides} " +
                "slot=${d?.anInt3633} blockShadow=${d?.blockShadow} " +
                "waterColour=${d?.waterColour} waterScale=${d?.waterScale} " +
                "waterIntensity=${d?.waterIntensity} hideUnderlay=${d?.hideUnderlay}"
        )
    }
    println()
    println("--- UNDERLAY LEGEND ---")
    for (id in underlayIds) {
        val d = TerrainLoader.getUnderlayDefinition(id)
        println("  underlay $id: texture=${d?.texture} scale=${d?.scale} colour=${d?.colour}")
    }
    println()

    // Map each overlay id to a single printable char for the grid.
    val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
    val symbolFor = HashMap<Int, Char>()
    overlayIds.forEachIndexed { i, id -> symbolFor[id] = chars[i % chars.length] }
    println("--- OVERLAY SYMBOLS ---")
    symbolFor.entries.sortedBy { it.key }.forEach { println("  '${it.value}' = overlay ${it.key}") }
    println("  '.' = no overlay")
    println()

    fun grid(title: String, cell: (Int, Int) -> Char) {
        println("--- $title (north up, x increases right) ---")
        print("     ")
        for (x in x0..x1) print(x % 10)
        println()
        for (y in y1 downTo y0) {
            print(String.format("%3d  ", y))
            for (x in x0..x1) print(cell(x, y))
            println()
        }
        println()
    }

    grid("OVERLAY ID") { x, y ->
        val t = tiles[x][y]
        if (t.overlayId == 0) '.' else (symbolFor[t.overlayId] ?: '?')
    }
    grid("OVERLAY SHAPE (hex, '.' = no overlay)") { x, y ->
        val t = tiles[x][y]
        if (t.overlayId == 0) '.' else Integer.toHexString(t.overlayShape)[0]
    }
    grid("OVERLAY ROTATION") { x, y ->
        val t = tiles[x][y]
        if (t.overlayId == 0) '.' else ('0' + t.overlayRotation)
    }
    grid("UNDERLAY ID (mod 36)") { x, y ->
        val t = tiles[x][y]
        if (t.underlayId == 0) '.' else chars[t.underlayId % 36]
    }

    val bed = TerrainLoader.loadUnderwaterRegion(cache, regionX, regionY)
    if (bed == null) {
        println("--- NO UNDERWATER MAP ---")
        println()
    } else {
        val bedTiles = bed.tiles[plane]
        var maxDepth = 0
        for (x in x0..x1) for (y in y0..y1) {
            maxDepth = maxOf(maxDepth, tiles[x][y].height - bedTiles[x][y].height)
        }
        println("--- UNDERWATER MAP: max depth $maxDepth units over range ---")
        var waterWithDepth = 0
        var waterNoDepth = 0
        var depthNoWater = 0
        var dryNoDepth = 0
        val depthByOverlay = HashMap<Int, IntArray>()
        for (x in x0..x1) for (y in y0..y1) {
            val o = tiles[x][y].overlayId
            val d = tiles[x][y].height - bedTiles[x][y].height
            if (o > 0) {
                val acc = depthByOverlay.getOrPut(o) { IntArray(2) }
                if (d > 0) acc[0]++ else acc[1]++
            }
            when {
                o > 0 && d > 0 -> waterWithDepth++
                o > 0 && d <= 0 -> waterNoDepth++
                o == 0 && d > 0 -> depthNoWater++
                else -> dryNoDepth++
            }
        }
        println("  overlay+depth=$waterWithDepth  overlay only=$waterNoDepth  " +
            "depth only=$depthNoWater  neither=$dryNoDepth")
        for ((id, acc) in depthByOverlay.entries.sortedBy { it.key }) {
            println("  overlay $id: ${acc[0]} tiles with depth, ${acc[1]} without")
        }
        // Corner-level view: the shoreline band can only exist on tiles whose four corners
        // span the waterline, so count those specifically.
        var allZero = 0
        var mixed = 0
        var allDeep = 0
        for (x in x0 until minOf(x1, 62)) for (y in y0 until minOf(y1, 62)) {
            if (tiles[x][y].overlayId == 0) continue
            var zero = 0
            var pos = 0
            for (dx in 0..1) for (dy in 0..1) {
                val d = tiles[x + dx][y + dy].height - bedTiles[x + dx][y + dy].height
                if (d > 0) pos++ else zero++
            }
            when {
                pos == 0 -> allZero++
                zero == 0 -> allDeep++
                else -> mixed++
            }
        }
        println("  overlay tiles by corner depth: allZero=$allZero mixedWaterline=$mixed allDeep=$allDeep")
        println("  water overlay ids for this region: ${TerrainLoader.waterOverlayIdsFor(region.regionId)}")
        var shaded = 0
        var unshaded = 0
        val waterIds = TerrainLoader.waterOverlayIdsFor(region.regionId)
        for (x in x0..x1) for (y in y0..y1) {
            val t = tiles[x][y]
            if (t.overlayId == 0) continue
            val water = waterIds.contains(t.overlayId)
            val underlay = t.underlayId > 0
            if (water && underlay) shaded++ else if (water) unshaded++
        }
        println("  water tiles that qualify for the band: $shaded (blocked by missing underlay: $unshaded)")
        println()
        grid("WATER DEPTH (surface - bed, in steps of 32; '.' = 0)") { x, y ->
            val depth = (tiles[x][y].height - bedTiles[x][y].height) / 32
            if (depth <= 0) '.' else chars[depth.coerceAtMost(35)]
        }
        // The underwater archive carries its own overlays, and in the client those are what
        // lift bed vertices back up to the water surface. Whether this region uses that at
        // all decides how wide the mud band can legitimately be.
        var bedOverlays = 0
        for (x in x0..x1) for (y in y0..y1) if (bedTiles[x][y].overlayId > 0) bedOverlays++
        println("--- BED OVERLAY TILES: $bedOverlays over range ---")
        println()
        grid("BED OVERLAY ID (mod 36, '.' = none)") { x, y ->
            val o = bedTiles[x][y].overlayId
            if (o == 0) '.' else chars[o % 36]
        }
        grid("BED UNDERLAY ID (mod 36, '.' = none)") { x, y ->
            val u = bedTiles[x][y].underlayId
            if (u == 0) '.' else chars[u % 36]
        }
    }

    // Height field, relative to the minimum in range, in units of 32 (quarter tile step).
    var minH = Int.MAX_VALUE
    for (x in x0..x1) for (y in y0..y1) minH = minOf(minH, tiles[x][y].height)
    grid("HEIGHT (steps of 32 above min=$minH, '+' = >35 steps)") { x, y ->
        val step = (tiles[x][y].height - minH) / 32
        if (step > 35) '+' else chars[step.coerceAtLeast(0)]
    }

    // The bucketed grid above saturates wherever a region spans more than 35 quarter-tile
    // steps, which is most of any region with a cliff in it. For a small window print the
    // raw numbers too -- distinguishing genuinely stepped map data from a rendering fault
    // needs the actual values, not a bucket.
    if (x1 - x0 <= 24 && y1 - y0 <= 40) {
        println()
        println("--- RAW HEIGHT (RS units, north up, x increases right) ---")
        print("      ")
        for (x in x0..x1) print("%6d".format(x))
        println()
        for (y in y1 downTo y0) {
            print("%4d  ".format(y))
            for (x in x0..x1) print("%6d".format(tiles[x][y].height))
            println()
        }
    }
}
