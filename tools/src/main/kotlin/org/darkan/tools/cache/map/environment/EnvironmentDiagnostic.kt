package org.darkan.tools.cache.map.environment

import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.secure.CRC
import world.gregs.voidps.cache.store.Container

/**
 * `tools env <regionX> <regionY>` - dumps the decoded environment block of a region:
 * per-chunk environments, point lights, light grid summary and the referenced configs.
 */
fun runEnvironmentDiagnostic(cache: Cache, args: List<String>) {
    when (args.firstOrNull()?.lowercase()) {
        "scan" -> {
            runEnvironmentScan(cache)
            return
        }
        "roundtrip", "round-trip" -> {
            runEnvironmentRoundTrip(cache)
            return
        }
        "write", "save" -> {
            runEnvironmentWrite(cache, args.drop(1))
            return
        }
    }
    val regionX = args.getOrNull(0)?.toIntOrNull() ?: 50
    val regionY = args.getOrNull(1)?.toIntOrNull() ?: 50

    println("\n=== MAP ENVIRONMENT DIAGNOSTIC ===")
    println("Region: ($regionX, $regionY)  id=${(regionX shl 8) or regionY}  archive=m${regionX}_$regionY")

    printConfigAvailability(cache)

    val archive = cache.archiveId(Index.MAPS, "m${regionX}_$regionY")
    if (archive == -1) {
        println("\nNo map archive for region ($regionX, $regionY).")
        return
    }
    val data = cache.data(Index.MAPS, archive)
    if (data == null) {
        println("\nMap archive $archive has no data.")
        return
    }
    println("\nArchive id $archive, ${data.size} bytes total")

    val environment = MapEnvironmentLoader.load(cache, regionX, regionY)
    if (environment == null) {
        println("Failed to decode environment.")
        return
    }

    printChunkEnvironments(cache, environment)
    printLights(environment)
    printLightGrid(environment)
}

/**
 * Decodes every region in the cache. A desync in the tile skip or the opcode grammar shows
 * up immediately as a bad opcode or a buffer underrun, so this is the real correctness check.
 */
private fun runEnvironmentScan(cache: Cache) {
    println("\n=== MAP ENVIRONMENT SCAN ===")
    printConfigAvailability(cache)

    var regions = 0
    var failed = 0
    var withEnvironment = 0
    var withLights = 0
    var withSkybox = 0
    var withCubeTexture = 0
    var withGrid = 0
    var multipleBlocks = 0
    var totalLights = 0
    val failures = ArrayList<String>()
    val skyboxExamples = ArrayList<String>()
    val gridExamples = ArrayList<String>()
    val configLightIds = sortedSetOf<Int>()

    for (regionX in 0 until 256) {
        for (regionY in 0 until 256) {
            if (cache.archiveId(Index.MAPS, "m${regionX}_$regionY") == -1) continue
            regions++
            val decoded = try {
                MapEnvironmentLoader.load(cache, regionX, regionY)
            } catch (exception: Exception) {
                failed++
                if (failures.size < 20) failures.add("($regionX, $regionY): ${exception.message}")
                null
            } ?: continue

            val environment = decoded.environment(0, 0)
            if (environment != null) withEnvironment++
            if (environment?.skybox != null) {
                withSkybox++
                if (skyboxExamples.size < 5) skyboxExamples.add("($regionX, $regionY) ${environment.skybox}")
            }
            if (environment?.cubeTexture != null) withCubeTexture++
            if (decoded.environmentBlocks > 1) multipleBlocks++
            if (decoded.lights.isNotEmpty()) withLights++
            totalLights += decoded.lights.size
            for (light in decoded.lights) {
                if (light.lightTypeId != -1) configLightIds.add(light.lightTypeId)
            }
            if (decoded.lightGrid.any { it != null }) {
                withGrid++
                if (gridExamples.size < 5) {
                    val planes = decoded.lightGrid.indices.filter { decoded.lightGrid[it] != null }
                    gridExamples.add("($regionX, $regionY) planes=$planes")
                }
            }
        }
    }

    println("\n--- RESULTS ---")
    println("  regions with a map archive : $regions")
    println("  decode failures            : $failed")
    println("  with environment (op 0)    : $withEnvironment")
    println("  with >1 environment block  : $multipleBlocks")
    println("  with skybox (op 128)       : $withSkybox")
    println("  with explicit cube texture : $withCubeTexture")
    println("  with point lights (op 1)   : $withLights ($totalLights lights)")
    println("  with light grid (op 129)   : $withGrid")
    println("  light config ids in use    : ${if (configLightIds.isEmpty()) "none" else configLightIds.toString()}")
    for (id in configLightIds) {
        println("    light $id -> ${EnvironmentConfigs.light(cache, id)}")
    }
    if (skyboxExamples.isNotEmpty()) {
        println("\n--- SKYBOX EXAMPLES ---")
        skyboxExamples.forEach { println("  $it") }
    }
    if (gridExamples.isNotEmpty()) {
        println("\n--- LIGHT GRID EXAMPLES ---")
        gridExamples.forEach { println("  $it") }
    }
    if (failures.isNotEmpty()) {
        println("\n--- FAILURES (first ${failures.size}) ---")
        failures.forEach { println("  $it") }
    }
}

/**
 * `tools env write <regionX> <regionY> [field=value ...] [--apply]`
 *
 * Dry run by default: it prints the field-level diff, the byte sizes and where the backup
 * would go, and writes nothing. `--apply` is the only thing that mutates the cache.
 *
 * Fields: `fogColour` / `sunColour` (hex or decimal), `fogDepth`, `sunAmbient`, `sunLight`,
 * `sunBacklight`, `sunPos=x,y,z`, `skybox`, `skyboxRotation`.
 *
 * The write goes through the shared cache library, which always brings the reference table and
 * the signed version table along with the archive; there is no way to write one without the
 * others, and nothing here wants one.
 */
private fun runEnvironmentWrite(cache: Cache, args: List<String>) {
    val regionX = args.getOrNull(0)?.toIntOrNull()
    val regionY = args.getOrNull(1)?.toIntOrNull()
    if (regionX == null || regionY == null) {
        println("usage: tools env write <regionX> <regionY> [field=value ...] [--apply]")
        return
    }
    val apply = args.any { it == "--apply" }

    val current = MapEnvironmentLoader.load(cache, regionX, regionY)?.environment(0, 0) ?: MapEnvironment.DEFAULT
    var edit = EnvironmentEdit(
        sunColour = current.sunColour,
        sunPositionX = current.sunPositionX,
        sunPositionY = current.sunPositionY,
        sunPositionZ = current.sunPositionZ,
        sunAmbient = current.sunAmbient,
        sunLight = current.sunLight,
        sunBacklight = current.sunBacklight,
        fogColour = current.fogColour,
        fogDepth = current.fogDepth,
        skyboxId = current.skybox?.id ?: -1,
        skyboxRotation = current.skybox?.rotation ?: 0,
    )

    for (argument in args.drop(2)) {
        if (!argument.contains('=')) continue
        val key = argument.substringBefore('=').lowercase()
        val value = argument.substringAfter('=')
        edit = when (key) {
            "suncolour", "suncolor" -> edit.copy(sunColour = parseColour(value) ?: continue)
            "fogcolour", "fogcolor" -> edit.copy(fogColour = parseColour(value) ?: continue)
            "fogdepth" -> edit.copy(fogDepth = value.toIntOrNull() ?: continue)
            "sunambient", "ambient" -> edit.copy(sunAmbient = value.toFloatOrNull() ?: continue)
            "sunlight", "keylight" -> edit.copy(sunLight = value.toFloatOrNull() ?: continue)
            "sunbacklight", "backlight" -> edit.copy(sunBacklight = value.toFloatOrNull() ?: continue)
            "sunpos", "sunposition" -> {
                val parts = value.split(',').mapNotNull { it.trim().toFloatOrNull() }
                if (parts.size != 3) continue
                edit.copy(sunPositionX = parts[0], sunPositionY = parts[1], sunPositionZ = parts[2])
            }
            "skybox" -> edit.copy(skyboxId = value.toIntOrNull() ?: continue)
            "skyboxrotation" -> edit.copy(skyboxRotation = value.toIntOrNull() ?: continue)
            else -> {
                println("unknown field '$key'")
                edit
            }
        }
    }

    println("\n=== MAP ENVIRONMENT WRITE (${if (apply) "APPLY" else "DRY RUN"}) ===")
    val plan = MapEnvironmentWriter.plan(cache, regionX, regionY, edit)
    println("  ${plan.summary()}")
    if (plan.blocked != null) return
    if (plan.changes.isEmpty()) {
        println("  no field changes")
    } else {
        println("\n--- CHANGES ---")
        plan.changes.forEach { println("  $it") }
    }
    println("\n  cache     : ${MapEnvironmentWriter.cacheDirectory().absolutePath}")
    println("  backups   : ${MapEnvironmentWriter.backupDirectory().absolutePath}")

    if (!plan.hasChanges) {
        println("\nNothing to write.")
        return
    }
    if (!apply) {
        println("\nDry run - nothing written. Re-run with --apply to commit.")
        return
    }
    val result = MapEnvironmentWriter.apply(plan)
    println("\n--- WRITE LOG ---")
    result.log.forEach { println("  $it") }
    println("\nWritten.")
}

private fun parseColour(value: String): Int? {
    val trimmed = value.removePrefix("0x").removePrefix("#")
    return if (trimmed == value) value.toIntOrNull() else trimmed.toIntOrNull(16)
}

/**
 * Decodes every map archive's effects block, re-encodes it and compares the bytes.
 *
 * This is the gate on write-back: until every archive in the cache survives a decode/encode
 * cycle unchanged, nothing may be written back, because a mismatch means the encoder is
 * inventing or dropping bytes and would corrupt whatever it touches.
 *
 * The comparison is done twice per archive - once on the effects block alone, once on the
 * whole archive rebuilt by [MapEnvironmentEncoder.encodeArchive] - so a tile-splice bug
 * cannot hide behind a correct effects encoder.
 */
private fun runEnvironmentRoundTrip(cache: Cache) {
    println("\n=== MAP ENVIRONMENT ROUND TRIP ===")

    var regions = 0
    var decodeFailures = 0
    var identical = 0
    var mismatched = 0
    var archiveMismatched = 0
    var emptyEffects = 0
    var totalEffectBytes = 0L
    var crcMatched = 0
    var crcMismatched = 0
    val failures = ArrayList<String>()
    val mismatches = ArrayList<String>()
    val crcFailures = ArrayList<String>()
    val opcodeOrders = sortedMapOf<String, Int>()
    val opcodeOrderExamples = sortedMapOf<String, String>()
    val flagCounts = sortedMapOf<Int, Int>()
    val gridModes = IntArray(8)

    // The archive CRCs in index 255 are what a JS5 client validates a download against, so
    // the same walk checks that every stored CRC still matches the bytes on disk. Untouched
    // archives prove the convention (container minus the trailing version); a rewritten one
    // proves the write path kept the table honest.
    val table = cache.referenceTable(Index.MAPS)

    for (regionX in 0 until 256) {
        for (regionY in 0 until 256) {
            val archiveId = cache.archiveId(Index.MAPS, "m${regionX}_$regionY")
            if (archiveId == -1) continue
            val data = cache.data(Index.MAPS, archiveId) ?: continue
            regions++

            if (table != null) {
                val container = cache.sector(Index.MAPS, archiveId)
                val stored = table.archive(archiveId)?.crc
                if (container != null && stored != null) {
                    // The CRC covers the container without its two byte version trailer, which
                    // is the range JS5 sends and the client hashes.
                    val trailing = if (Container.decode(container).version == null) 0 else 2
                    if (CRC.calculate(container, 0, container.size - trailing) == stored) {
                        crcMatched++
                    } else {
                        crcMismatched++
                        if (crcFailures.size < 10) crcFailures.add("m${regionX}_$regionY (archive $archiveId)")
                    }
                }
            }

            val layout = try {
                MapEnvironmentDecoder.readLayout(data, regionX, regionY)
            } catch (exception: Exception) {
                decodeFailures++
                if (failures.size < 20) failures.add("($regionX, $regionY): ${exception.message}")
                continue
            }

            val order = layout.blocks.joinToString("-") { it.opcode.toString() }.ifEmpty { "(none)" }
            opcodeOrders[order] = (opcodeOrders[order] ?: 0) + 1
            opcodeOrderExamples.putIfAbsent(order, "m${regionX}_$regionY")
            for (block in layout.blocks) {
                if (block is EnvironmentRecord) flagCounts[block.flags()] = (flagCounts[block.flags()] ?: 0) + 1
                if (block is LightGridRecord) {
                    for (plane in block.planes) {
                        val mode = plane.mode
                        if (mode in gridModes.indices) gridModes[mode]++
                    }
                }
            }

            val original = data.copyOfRange(layout.tileSectionLength, data.size)
            totalEffectBytes += original.size
            if (original.isEmpty()) emptyEffects++

            val encoded = MapEnvironmentEncoder.encodeEffects(layout)
            val rebuilt = MapEnvironmentEncoder.encodeArchive(data, layout)

            val effectsMatch = encoded.contentEquals(original)
            val archiveMatch = rebuilt.contentEquals(data)
            if (effectsMatch && archiveMatch) {
                identical++
                continue
            }
            if (!effectsMatch) mismatched++
            if (!archiveMatch) archiveMismatched++
            if (mismatches.size < 20) {
                mismatches.add(describeMismatch(regionX, regionY, original, encoded, data, rebuilt))
            }
        }
    }

    println("\n--- RESULTS ---")
    println("  map archives scanned       : $regions")
    println("  decode failures            : $decodeFailures")
    println("  effects blocks byte-identical after re-encode : $identical / $regions")
    println("  effects block mismatches   : $mismatched")
    println("  full archive mismatches    : $archiveMismatched")
    println("  archives with no effects   : $emptyEffects")
    println("  effects bytes round-tripped: $totalEffectBytes")
    if (table == null) {
        println("  index 255 crc check        : unavailable (reference table could not be read)")
    } else {
        println("  index 255 crc matches      : $crcMatched, mismatches: $crcMismatched")
    }
    if (crcFailures.isNotEmpty()) {
        println("  crc mismatches (first ${crcFailures.size}): ${crcFailures.joinToString(", ")}")
    }

    println("\n--- OPCODE ORDERS ---")
    for ((order, count) in opcodeOrders.entries.sortedByDescending { it.value }) {
        println("  $order : $count  e.g. ${opcodeOrderExamples[order]}")
    }
    println("\n--- OPCODE 0 FLAG BYTES ---")
    for ((flags, count) in flagCounts) {
        println("  0x${flags.toString(16).padStart(2, '0')} (${flagNames(flags)}) : $count")
    }
    println("\n--- LIGHT GRID PLANE MODES ---")
    for (mode in gridModes.indices) {
        if (gridModes[mode] != 0) println("  mode $mode : ${gridModes[mode]}")
    }

    if (failures.isNotEmpty()) {
        println("\n--- DECODE FAILURES (first ${failures.size}) ---")
        failures.forEach { println("  $it") }
    }
    if (mismatches.isNotEmpty()) {
        println("\n--- MISMATCHES (first ${mismatches.size}) ---")
        mismatches.forEach { println(it) }
    }
    if (decodeFailures == 0 && mismatched == 0 && archiveMismatched == 0) {
        println("\nAll $regions map archives round-trip byte-identically.")
    } else {
        println("\nROUND TRIP FAILED - write-back must stay disabled until this is clean.")
    }
}

private fun describeMismatch(
    regionX: Int,
    regionY: Int,
    original: ByteArray,
    encoded: ByteArray,
    archive: ByteArray,
    rebuilt: ByteArray,
): String {
    val builder = StringBuilder()
    builder.append("  ($regionX, $regionY) effects ${original.size} -> ${encoded.size} bytes")
    builder.append(", archive ${archive.size} -> ${rebuilt.size} bytes")
    val limit = minOf(original.size, encoded.size)
    var firstDiff = -1
    for (index in 0 until limit) {
        if (original[index] != encoded[index]) {
            firstDiff = index
            break
        }
    }
    if (firstDiff == -1 && original.size != encoded.size) firstDiff = limit
    if (firstDiff >= 0) {
        builder.append("\n    first difference at effects byte $firstDiff")
        builder.append("\n    original: ${hexWindow(original, firstDiff)}")
        builder.append("\n    encoded : ${hexWindow(encoded, firstDiff)}")
    }
    return builder.toString()
}

private fun hexWindow(data: ByteArray, around: Int): String {
    val start = (around - 8).coerceAtLeast(0)
    val end = (around + 8).coerceAtMost(data.size)
    if (start >= end) return "(past end)"
    return (start until end).joinToString(" ") { index ->
        val value = (data[index].toInt() and 0xff).toString(16).padStart(2, '0')
        if (index == around) "[$value]" else value
    }
}

private fun flagNames(flags: Int): String {
    val names = ArrayList<String>()
    if (flags and EnvironmentRecord.FLAG_SUN_COLOUR != 0) names.add("sunColour")
    if (flags and EnvironmentRecord.FLAG_SUN_AMBIENT != 0) names.add("ambient")
    if (flags and EnvironmentRecord.FLAG_SUN_LIGHT != 0) names.add("light")
    if (flags and EnvironmentRecord.FLAG_SUN_BACKLIGHT != 0) names.add("backlight")
    if (flags and EnvironmentRecord.FLAG_SUN_POSITION != 0) names.add("sunPos")
    if (flags and EnvironmentRecord.FLAG_FOG_COLOUR != 0) names.add("fogColour")
    if (flags and EnvironmentRecord.FLAG_FOG_DEPTH != 0) names.add("fogDepth")
    if (flags and EnvironmentRecord.FLAG_CUBE_TEXTURE != 0) names.add("cubeTexture")
    return if (names.isEmpty()) "none" else names.joinToString(",")
}

private fun printConfigAvailability(cache: Cache) {
    println("\n--- CONFIG ARCHIVES (index ${Index.CONFIGS}) ---")
    val archives = listOf(
        "skybox" to EnvironmentConfigs.SKYBOX_ARCHIVE,
        "sun" to EnvironmentConfigs.SUN_ARCHIVE,
        "light" to EnvironmentConfigs.LIGHT_ARCHIVE,
    )
    for ((name, id) in archives) {
        val count = EnvironmentConfigs.fileCount(cache, id)
        println("  $name (archive $id): ${if (count == 0) "MISSING" else "$count files"}")
    }
    val cube = EnvironmentConfigs.defaultCubeTexture(cache)
    println("  scene default cube texture (index ${Index.DEFAULTS} archive ${EnvironmentConfigs.SCENE_CUBE_TEXTURES_ARCHIVE}): ${cube ?: "MISSING"}")
}

private fun printChunkEnvironments(cache: Cache, region: RegionEnvironment) {
    println("\n--- ENVIRONMENT (${region.environmentBlocks} opcode-0 block(s)) ---")
    val distinct = region.chunkEnvironments.filterNotNull().distinct()
    if (distinct.isEmpty()) {
        println("  none - all 8x8 chunks fall back to defaults")
        printEnvironment(cache, MapEnvironment.DEFAULT, "  [default] ")
        return
    }
    for (env in distinct) {
        val chunks = (0 until RegionEnvironment.CHUNKS).flatMap { cx ->
            (0 until RegionEnvironment.CHUNKS).mapNotNull { cy ->
                if (region.environment(cx, cy) === env) "$cx,$cy" else null
            }
        }
        println("  chunks (${chunks.size}): ${if (chunks.size == 64) "all" else chunks.joinToString(" ")}")
        printEnvironment(cache, env, "    ")
    }
}

private fun printEnvironment(cache: Cache, env: MapEnvironment, indent: String) {
    println("${indent}sun colour   : ${rgb(env.sunColour)}")
    println("${indent}sun position : (${env.sunPositionX}, ${env.sunPositionY}, ${env.sunPositionZ})")
    println("${indent}sun ambient  : ${env.sunAmbient}")
    println("${indent}sun light    : ${env.sunLight}")
    println("${indent}sun backlight: ${env.sunBacklight}")
    println("${indent}fog colour   : ${rgb(env.fogColour)}")
    println("${indent}fog depth    : ${env.fogDepth} (density ${env.fogDensity})")
    println("${indent}hdr          : bloom=${env.bloom} brightpass=${env.brightpass} whitePoint=${env.whitePoint}")
    val cube = env.cubeTexture
    println("${indent}cube texture : ${cube ?: "default (${EnvironmentConfigs.defaultCubeTexture(cache) ?: "none"})"}")
    val skybox = env.skybox
    if (skybox == null) {
        println("${indent}skybox       : none")
    } else {
        println("${indent}skybox       : id=${skybox.id} pos=(${skybox.x}, ${skybox.y}, ${skybox.z}) rotation=${skybox.rotation}")
        val type = EnvironmentConfigs.skybox(cache, skybox.id)
        println("$indent               config: material=${type.materialId} suns=${type.sunIds?.toList()} defaultSun=${type.defaultSunIndex} background=${type.backgroundMode} archive=${type.archiveId}")
        type.sunIds?.forEach { sunId ->
            val sun = EnvironmentConfigs.sun(cache, sunId)
            println("$indent               sun $sunId: pos=(${sun.positionX}, ${sun.positionY}, ${sun.positionZ}) colour=${rgb(sun.displayColour)} material=${sun.materialId} action=${sun.actionType} step=${sun.positionOffsetStep} invert=${sun.invertPosition} rot=(${sun.rotationX}, ${sun.rotationY}, ${sun.rotationZ})")
        }
    }
}

private fun printLights(region: RegionEnvironment) {
    println("\n--- POINT LIGHTS (${region.lights.size}) ---")
    if (region.lights.isEmpty()) {
        println("  none")
        return
    }
    val perPlane = IntArray(RegionEnvironment.PLANES)
    for (light in region.lights) {
        if (light.plane in perPlane.indices) perPlane[light.plane]++
    }
    println("  per plane: ${perPlane.withIndex().joinToString(" ") { "p${it.index}=${it.value}" }}")
    for ((index, light) in region.lights.withIndex()) {
        println("  [$index] $light")
    }
}

private fun printLightGrid(region: RegionEnvironment) {
    println("\n--- LIGHT GRID (opcode 129) ---")
    var any = false
    for (plane in 0 until RegionEnvironment.PLANES) {
        val grid = region.lightGrid[plane]
        if (grid == null) {
            println("  plane $plane: unlit")
            continue
        }
        any = true
        var min = Int.MAX_VALUE
        var max = Int.MIN_VALUE
        var sum = 0L
        var nonZero = 0
        for (value in grid) {
            val intensity = value.toInt()
            if (intensity < min) min = intensity
            if (intensity > max) max = intensity
            sum += intensity
            if (intensity != 0) nonZero++
        }
        val mean = sum.toDouble() / grid.size
        println("  plane $plane: min=$min max=$max mean=${"%.2f".format(mean)} nonZero=$nonZero/${grid.size}")
    }
    if (!any) println("  (region has no light grid)")
}

private fun rgb(colour: Int): String {
    val r = colour shr 16 and 0xff
    val g = colour shr 8 and 0xff
    val b = colour and 0xff
    return "0x${(colour and 0xffffff).toString(16).padStart(6, '0')} rgb($r, $g, $b)"
}
