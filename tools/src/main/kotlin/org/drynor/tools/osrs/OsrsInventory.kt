package org.drynor.tools.osrs

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.FileCache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.MapDefinition
import world.gregs.voidps.cache.definition.data.MapTile
import world.gregs.voidps.cache.definition.decoder.MapObjectDecoder
import world.gregs.voidps.cache.definition.decoder.MapTileDecoder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest

/** Read-only discovery for Gielenor's hybrid cache, not a generic OSRS definition decoder. */
fun runOsrs(args: List<String>) {
    if (args.firstOrNull() == "preview-zeah-shaman-combat-assets") {
        require(args.size == 5) { "osrs preview-zeah-shaman-combat-assets <gielenor-cache> <zeah-npc-preview-root> <shaman-asset-audit.json> <new-output-root>" }
        ZeahShamanCombatPreview.build(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]), Path.of(args[4]))
        return
    }
    if (args.firstOrNull() == "audit-zeah-shaman-combat-assets") {
        require(args.size == 4) { "osrs audit-zeah-shaman-combat-assets <gielenor-cache> <preview-cache> <new-report.json>" }
        ZeahShamanCombatAudit.audit(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]))
        return
    }
    if (args.firstOrNull() == "preview-zeah-icon-objects") {
        require(args.size == 11) { "osrs preview-zeah-icon-objects <icon-remap-preview-root> <animated-objects-preview-root> <landscape-preview-root> <bounded-object-plan.json> <static-audit.json> <material-audit.json> <sound-audit.json> <icon-audit.json> <animation-audit.json> <new-output-root>" }
        ZeahIconObjectPreview.build(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]), Path.of(args[4]), Path.of(args[5]), Path.of(args[6]), Path.of(args[7]), Path.of(args[8]), Path.of(args[9]), Path.of(args[10]))
        return
    }
    if (args.firstOrNull() == "preview-zeah-icon-remaps") {
        require(args.size == 6) { "osrs preview-zeah-icon-remaps <base-preview-root> <bounded-object-plan.json> <object-icons-audit.json> <icon-alias-audit.json> <new-output-root>" }
        ZeahIconRemapPreview.build(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]), Path.of(args[4]), Path.of(args[5]))
        return
    }
    if (args.firstOrNull() == "audit-zeah-remaining-objects") {
        require(args.size == 6) { "osrs audit-zeah-remaining-objects <preview-root> <landscape-preview-root> <bounded-object-plan.json> <static-audit.json> <new-report.json>" }
        ZeahRemainingObjectAudit.audit(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]), Path.of(args[4]), Path.of(args[5]))
        return
    }
    if (args.firstOrNull() == "preview-zeah-animated-objects") {
        require(args.size == 10) { "osrs preview-zeah-animated-objects <animation-assets-preview-root> <landscape-preview-root> <bounded-object-plan.json> <static-audit.json> <material-audit.json> <sound-audit.json> <icon-audit.json> <animation-audit.json> <new-output-root>" }
        ZeahAnimatedObjectPreview.build(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]), Path.of(args[4]), Path.of(args[5]), Path.of(args[6]), Path.of(args[7]), Path.of(args[8]), Path.of(args[9]))
        return
    }
    if (args.firstOrNull() == "preview-zeah-object-animation-assets") {
        require(args.size == 5) { "osrs preview-zeah-object-animation-assets <static-preview-root> <bounded-object-plan.json> <object-animation-audit.json> <new-output-root>" }
        ZeahObjectAnimationPreview.build(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]), Path.of(args[4]))
        return
    }
    if (args.firstOrNull() == "audit-zeah-object-animations") {
        require(args.size == 3) { "osrs audit-zeah-object-animations <bounded-object-plan.json> <new-report.json>" }
        ZeahObjectAnimationAudit.audit(Path.of(args[1]), Path.of(args[2]))
        return
    }
    if (args.firstOrNull() == "audit-zeah-icon-aliases") {
        require(args.size == 4) { "osrs audit-zeah-icon-aliases <bounded-object-plan.json> <object-icon-audit.json> <new-report.json>" }
        ZeahIconAliasAudit.audit(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]))
        return
    }
    if (args.firstOrNull() == "audit-zeah-object-icons") {
        require(args.size == 4) { "osrs audit-zeah-object-icons <bounded-object-plan.json> <donor-client-ObjectConfig.java> <new-report.json>" }
        ZeahObjectIconAudit.audit(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]))
        return
    }
    if (args.firstOrNull() == "audit-zeah-object-sounds") {
        require(args.size == 3) { "osrs audit-zeah-object-sounds <bounded-object-plan.json> <new-report.json>" }
        ZeahObjectSoundAudit.audit(Path.of(args[1]), Path.of(args[2]))
        return
    }
    if (args.firstOrNull() == "preview-zeah-morph-visuals") {
        require(args.size == 6) { "osrs preview-zeah-morph-visuals <static-preview-root> <landscape-preview-root> <bounded-object-plan.json> <morph-audit.json> <new-output-root>" }
        ZeahMorphVisualPreview.build(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]), Path.of(args[4]), Path.of(args[5]))
        return
    }
    if (args.firstOrNull() == "audit-zeah-morphs") {
        require(args.size == 3) { "osrs audit-zeah-morphs <bounded-object-plan.json> <new-report.json>" }
        ZeahMorphAudit.audit(Path.of(args[1]), Path.of(args[2]))
        return
    }
    if (args.firstOrNull() == "audit-zeah-materials") {
        require(args.size == 4) { "osrs audit-zeah-materials <bounded-object-plan.json> <static-object-audit.json> <new-report.json>" }
        ZeahMaterialAudit.audit(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]))
        return
    }
    if (args.firstOrNull() == "preview-zeah-static-objects") {
        require(args.size in 5..8) { "osrs preview-zeah-static-objects <landscape-preview-root> <bounded-object-plan.json> <static-audit.json> <new-output-root> [material-closure-audit.json] [object-sound-audit.json] [object-icon-audit.json]" }
        ZeahStaticObjectPreview.build(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]), Path.of(args[4]), args.getOrNull(5)?.let(Path::of), args.getOrNull(6)?.let(Path::of), args.getOrNull(7)?.let(Path::of))
        return
    }
    if (args.firstOrNull() == "audit-zeah-static-objects") {
        require(args.size == 3) { "osrs audit-zeah-static-objects <bounded-object-plan.json> <new-report.json>" }
        ZeahStaticObjectAudit.audit(Path.of(args[1]), Path.of(args[2]))
        return
    }
    if (args.firstOrNull() == "preview-zeah-landscapes") {
        require(args.size == 4) { "osrs preview-zeah-landscapes <terrain-preview-root> <bounded-object-plan.json> <new-output-root>" }
        ZeahLandscapePreview.build(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]))
        return
    }
    if (args.firstOrNull() == "preview-zeah-terrain-set") {
        require(args.size == 5) { "osrs preview-zeah-terrain-set <floor-definitions-preview-root> <terrain-audit.json> <floor-plan.json> <new-output-root>" }
        ZeahTerrainSetPreview.build(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]), Path.of(args[4]))
        return
    }
    if (args.firstOrNull() == "preview-zeah-floor-definitions") {
        require(args.size == 5) { "osrs preview-zeah-floor-definitions <gielenor-cache> <drynor-cache> <floor-plan.json> <new-output-root>" }
        ZeahFloorDefinitionsPreview.build(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]), Path.of(args[4]))
        return
    }
    if (args.firstOrNull() == "plan-zeah-floor-palettes") {
        require(args.size == 3) { "osrs plan-zeah-floor-palettes <terrain-audit.json> <new-plan.json>" }
        ZeahFloorPalettePlan.plan(Path.of(args[1]), Path.of(args[2]))
        return
    }
    if (args.firstOrNull() == "preview-zeah-floor") {
        require(args.size == 5) { "osrs preview-zeah-floor <gielenor-cache> <drynor-cache> <terrain-audit.json> <new-output-root>" }
        ZeahFloorPreview.build(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]), Path.of(args[4]))
        return
    }
    if (args.firstOrNull() == "audit-zeah-item-deps") {
        require(args.size == 4) { "osrs audit-zeah-item-deps <gielenor-cache> <npc-drop-report.json> <new-report.json>" }
        ZeahItemDependencyAudit.audit(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]))
        return
    }
    if (args.firstOrNull() == "inventory-zeah-npc-drops") {
        require(args.size == 5) { "osrs inventory-zeah-npc-drops <gielenor-root> <npc-spawn-report.json> <npc-deps-report.json> <new-report.json>" }
        ZeahNpcDropInventory.inventory(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]), Path.of(args[4]))
        return
    }
    if (args.firstOrNull() == "audit-zeah-terrain") {
        require(args.size == 4) { "osrs audit-zeah-terrain <gielenor-cache> <object-id-plan.json> <new-report.json>" }
        ZeahTerrainAudit.audit(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]))
        return
    }
    if (args.firstOrNull() == "audit-zeah-npc-deps") {
        require(args.size == 4 || args.size == 5) { "osrs audit-zeah-npc-deps <gielenor-cache> <npc-spawn-report.json> <new-report.json> [drynor-cache]" }
        ZeahNpcDependencyAudit.audit(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]), args.getOrNull(4)?.let(Path::of))
        return
    }
    if (args.firstOrNull() == "plan-zeah-npc-ids") {
        require(args.size == 6) { "osrs plan-zeah-npc-ids <gielenor-cache> <drynor-cache> <npc-spawn-report.json> <npc-deps-report.json> <new-plan.json>" }
        ZeahNpcIdPlan.plan(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]), Path.of(args[4]), Path.of(args[5]))
        return
    }
    if (args.firstOrNull() == "audit-zeah-npc-conversion") {
        require(args.size == 5) { "osrs audit-zeah-npc-conversion <gielenor-cache> <npc-deps-report.json> <npc-id-plan.json> <new-report.json>" }
        ZeahNpcConversionAudit.audit(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]), Path.of(args[4]))
        return
    }
    if (args.firstOrNull() == "preview-zeah-passive-npcs") {
        require(args.size == 6) { "osrs preview-zeah-passive-npcs <gielenor-cache> <zeah-worldmap-preview-root> <npc-id-plan.json> <npc-conversion-audit.json> <new-output-root>" }
        ZeahNpcAssetPreview.build(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]), Path.of(args[4]), Path.of(args[5]))
        return
    }
    if (args.firstOrNull() == "preview-zeah-npc-spawns") {
        require(args.size == 6) { "osrs preview-zeah-npc-spawns <zeah-worldmap-preview-root> <zeah-npc-preview-root> <npc-spawn-report.json> <npc-id-plan.json> <new-output-root>" }
        ZeahNpcSpawnPreview.build(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]), Path.of(args[4]), Path.of(args[5]))
        return
    }
    if (args.firstOrNull() == "inventory-zeah-npc-spawns") {
        require(args.size == 4) { "osrs inventory-zeah-npc-spawns <gielenor-root> <object-id-plan.json> <new-report.json>" }
        ZeahNpcSpawnInventory.inventory(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]))
        return
    }
    if (args.firstOrNull() == "audit-zeah-object-deps") {
        require(args.size == 4 || args.size == 5) { "osrs audit-zeah-object-deps <gielenor-cache> <object-id-plan.json> <new-audit.json> [drynor-cache]" }
        ZeahObjectDependencyAudit.audit(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]), args.getOrNull(4)?.let(Path::of))
        return
    }
    if (args.firstOrNull() == "plan-zeah-object-ids") {
        require(args.size >= 4) { "osrs plan-zeah-object-ids <drynor-cache> <new-plan.json> <region-report.json> [more-region-reports.json...]" }
        ZeahObjectIdPlan.build(Path.of(args[1]), Path.of(args[2]), args.drop(3).map(Path::of))
        return
    }
    if (args.firstOrNull() == "inventory-zeah-regions") {
        require(args.size == 11) { "osrs inventory-zeah-regions <gielenor-cache> <gielenor-Settings.java> <gielenor-packed.mcx> <drynor-cache> <drynor-xteaKeys.json> <new-report.json> <min-x> <max-x> <min-y> <max-y>" }
        ZeahRegionInventory.inspect(
            Path.of(args[1]), Path.of(args[2]), Path.of(args[3]), Path.of(args[4]), Path.of(args[5]), Path.of(args[6]),
            args[7].toInt(), args[8].toInt(), args[9].toInt(), args[10].toInt(),
        )
        return
    }
    if (args.firstOrNull() == "preview-raid-equipment") {
        RaidEquipmentPreview.build(Path.of(args[1]),Path.of(args[2]),Path.of(args[3])); return
    }
    if (args.firstOrNull() == "merge-cache-imports") {
        CacheImportReconciliation.merge(Path.of(args[1]),Path.of(args[2])); return
    }
    if (args.firstOrNull() == "audit-cache-imports") {
        CacheImportReconciliation.audit(Path.of(args[1]),Path.of(args[2]),Path.of(args[3]),Path.of(args[4])); return
    }
    if (args.firstOrNull() == "audit-raid-equipment") {
        RaidEquipmentPreview.audit(Path.of(args[1]),Path.of(args[2])); return
    }
    if (args.firstOrNull() == "preview-zulrah-live") {
        require(args.size == 4) { "osrs preview-zulrah-live <gielenor-cache> <full-preview-root> <new-output-root>" }
        ZulrahLiveAssets.build(Path.of(args[1]),Path.of(args[2]),Path.of(args[3]))
        return
    }
    if (args.firstOrNull() == "preview-zulrah-full") {
        require(args.size == 4) { "osrs preview-zulrah-full <gielenor-cache> <item-preview-root> <new-output-root>" }
        ZulrahItemsPreview.build(Path.of(args[1]),Path.of(args[2]),Path.of(args[3]),full=true)
        return
    }
    if (args.firstOrNull() == "preview-zulrah-items") {
        require(args.size == 4) { "osrs preview-zulrah-items <gielenor-cache> <actor-preview-root> <new-output-root>" }
        ZulrahItemsPreview.build(Path.of(args[1]),Path.of(args[2]),Path.of(args[3]))
        return
    }
    if (args.firstOrNull() == "preview-zulrah-actors") {
        require(args.size == 4) { "osrs preview-zulrah-actors <gielenor-cache> <static-preview-root> <new-output-root>" }
        ZulrahActorPreview.build(Path.of(args[1]),Path.of(args[2]),Path.of(args[3]))
        return
    }
    if (args.firstOrNull() == "preview-zulrah-water") {
        require(args.size == 3) { "osrs preview-zulrah-water <static-preview-root> <new-output-root>" }
        ZulrahWaterPreview.build(Path.of(args[1]),Path.of(args[2]))
        return
    }
    if (args.firstOrNull() == "preview-zulrah-surroundings") {
        require(args.size == 4) { "osrs preview-zulrah-surroundings <gielenor-cache> <static-preview-root> <new-output-root>" }
        ZulrahNativeSurroundings.build(Path.of(args[1]),Path.of(args[2]),Path.of(args[3]))
        return
    }
    if (args.firstOrNull() == "preview-zulrah-worldmap") {
        require(args.size == 3) { "osrs preview-zulrah-worldmap <static-preview-root> <new-output-root>" }
        ZulrahWorldMapPreview.build(Path.of(args[1]),Path.of(args[2]))
        return
    }
    if (args.firstOrNull() == "preview-zeah-worldmap") {
        require(args.size == 4) { "osrs preview-zeah-worldmap <visual-preview-root> <bounded-object-plan.json> <new-output-root>" }
        ZeahWorldMapPreview.build(Path.of(args[1]),Path.of(args[2]),Path.of(args[3]))
        return
    }
    if (args.firstOrNull() == "probe-zeah-floor") {
        require(args.size == 6) { "osrs probe-zeah-floor <source-cache> <preview-cache> <palette.json> <x> <y>" }
        ZeahFloorProbe.run(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]), args[4].toInt(), args[5].toInt())
        return
    }
    if (args.firstOrNull() == "audit-zeah-object-model-offsets") {
        require(args.size == 4) { "osrs audit-zeah-object-model-offsets <bounded-object-plan.json> <gielenor-client-ObjectConfig.java> <new-report.json>" }
        ZeahObjectModelOffsets.audit(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]))
        return
    }
    require(args.size == 4 && args[0] in setOf("preview-zulrah-static", "inventory-zulrah", "inventory-inferno", "plan-inferno", "preview-inferno", "audit-actors-inferno", "preview-actors-inferno", "reward-inferno")) {
        "osrs <preview-zulrah-static|inventory-zulrah|inventory-inferno|plan-inferno|preview-inferno|audit-actors-inferno|preview-actors-inferno|reward-inferno> <gielenor-packed-cache> <drynor-packed-cache> <new-output>"
    }
    val source = Path.of(args[1]).toRealPath()
    val target = Path.of(args[2]).toRealPath()
    val output = Path.of(args[3]).toAbsolutePath().normalize()
    require(source != target) { "Source and target caches must differ." }
    require(!output.startsWith(source) && !output.startsWith(target)) { "Report must be outside both caches." }
    require(!Files.exists(output)) { "Report already exists: $output" }
    for (directory in listOf(source, target)) {
        require(Files.isRegularFile(directory.resolve("main_file_cache.dat2"))) { "Missing packed cache: $directory" }
    }
    if (args[0] == "preview-zulrah-static") {
        ZulrahStaticPreview.build(source, target, output)
        return
    }
    if (args[0] == "preview-inferno") {
        StaticInfernoPreview.build(source, target, output)
        return
    }
    if (args[0] == "audit-actors-inferno") {
        InfernoActorAudit.build(source, target, output)
        return
    }
    if (args[0] == "preview-actors-inferno") {
        InfernoActorPreview.build(source, target, output)
        return
    }
    if (args[0] == "reward-inferno") {
        InfernalCapePreview.build(source, target, output)
        return
    }
    FileCache(source.toString(), writable = false).use { origin ->
        FileCache(target.toString(), writable = false).use { destination ->
            val sourceBefore = InfernoDependencyPlan.snapshot(origin)
            val targetBefore = InfernoDependencyPlan.snapshot(destination)
            val inventory = if (args[0] == "inventory-zulrah") ZulrahInventory.inspect(origin, destination, source, target) else InfernoInventory.inspect(origin, destination, source, target)
            val report = if (args[0] == "plan-inferno") InfernoDependencyPlan.inspect(origin, destination, inventory) else inventory
            check(sourceBefore == InfernoDependencyPlan.snapshot(origin)) { "Source reference tables changed during inspection; retry with a stable cache." }
            check(targetBefore == InfernoDependencyPlan.snapshot(destination)) { "Target reference tables changed during inspection; retry with a stable cache." }
            Files.createDirectories(output.parent)
            Files.writeString(output, Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), report) + "\n", CREATE_NEW, WRITE)
        }
    }
    println("Read-only OSRS inventory: $output")
}

object InfernoInventory {
    // Evidence: Gielenor Inferno.java, MapInstance.java, Region.java and Settings.OSRS_MAP_IDS.
    private val regions = listOf(35 to 82, 71 to 82)
    private const val OBJECT_OFFSET = 100000

    fun inspect(source: Cache, target: Cache, sourcePath: Path, targetPath: Path): JsonObject {
        val objects = sortedSetOf<Int>()
        val regionReports = regions.map { (x, y) ->
            inspectRegion(source, target, x, y, objects)
        }
        // Pillars are spawned by gameplay rather than placed in the landscape.
        objects.addAll(130283..130287)
        return buildJsonObject {
            put("schemaVersion", 1)
            put("package", "inferno")
            put("sourceProfile", "gielenor-718-hybrid-osrs")
            put("originalOsrsArenaRegion", 9043)
            put("sourceCache", sourcePath.toString())
            put("targetCache", targetPath.toString())
            put("writesCache", false)
            put("dependencyClosureComplete", false)
            put("regions", JsonArray(regionReports))
            put("objectDefinitions", definitions(source, target, Index.OBJECTS, objects, OBJECT_OFFSET))
            put("npcDefinitions", definitions(source, target, Index.NPCS, (27690..27709).toList(), 20000, sourceShift = 7))
            put("itemDefinitions", definitions(source, target, Index.ITEMS, listOf(51295), 30000))
            put("limitations", buildJsonArray {
                add(JsonPrimitive("NPC IDs 27690..27709 are candidates; presence does not prove usage by gameplay."))
                add(JsonPrimitive("Definition bytes are fingerprinted, not decoded with 727 grammar; transitive dependencies remain unresolved."))
                add(JsonPrimitive("Terrain parsing covers legacy tile records only; trailing environment bytes require separate analysis."))
                add(JsonPrimitive("Landscape reads try plaintext only; encrypted or incompatible archives are reported as errors, never treated as empty."))
                add(JsonPrimitive("Target ID occupancy is not an ID allocation plan and does not establish compatibility."))
                add(JsonPrimitive("Source asset provenance and permission must be confirmed before copying assets."))
            })
        }
    }

    private fun inspectRegion(source: Cache, target: Cache, x: Int, y: Int, objects: MutableSet<Int>): JsonObject = buildJsonObject {
        put("regionId", (x shl 8) or y)
        put("regionX", x)
        put("regionY", y)
        put("role", if (x == 35) "arena" else "entrance")
        for (prefix in listOf("m", "l")) {
            val name = "$prefix${x}_$y"
            put(if (prefix == "m") "terrain" else "landscape", buildJsonObject {
                put("name", name)
                val archive = source.archiveId(Index.MAPS, name)
                put("sourceArchive", archive)
                put("targetArchive", target.archiveId(Index.MAPS, name))
                if (archive < 0) {
                    put("status", "missing")
                } else {
                    try {
                        val data = requireNotNull(source.data(Index.MAPS, archive)) { "Unreadable archive (possibly encrypted)." }
                        put("bytes", data.size)
                        put("sha256", sha256(data))
                        if (prefix == "m") {
                            val strictEnd = LegacyTerrainScan.validate(data)
                            val map = MapDefinition()
                            val end = MapTileDecoder.decode(data, map)
                            check(end == strictEnd) { "Tile decoder disagrees with strict scan." }
                            val overlays = map.tiles.map { MapTile(it).overlayId }.filter { it != 0 }.toSortedSet()
                            val underlays = map.tiles.map { MapTile(it).underlayId }.filter { it != 0 }.toSortedSet()
                            put("tileBytesConsumed", end)
                            put("trailingBytes", data.size - end)
                            put("overlayIdsRaw", numbers(overlays))
                            put("underlayIdsRaw", numbers(underlays))
                        } else {
                            var count = 0
                            val localObjects = sortedSetOf<Int>()
                            val decoder = object : MapObjectDecoder() {
                                override fun add(objectId: Int, localX: Int, localY: Int, level: Int, shape: Int, rotation: Int, regionTileX: Int, regionTileY: Int) {
                                    require(level in 0..3 && shape in 0..22) { "Invalid landscape placement: plane=$level shape=$shape" }
                                    localObjects.add(objectId + if (x == 35) OBJECT_OFFSET else 0)
                                    count++
                                }
                            }
                            val end = decoder.decode(data, ByteArray(16384), x, y)
                            require(data.drop(end).all { it == 0.toByte() }) { "Unrecognized landscape trailing bytes." }
                            objects.addAll(localObjects)
                            put("placements", count)
                            put("objectDefinitionIds", numbers(localObjects))
                            put("objectIdOffsetApplied", if (x == 35) OBJECT_OFFSET else 0)
                            put("trailingBytes", data.size - end)
                        }
                        put("status", "inspected")
                    } catch (error: Exception) {
                        put("status", "error")
                        put("error", "${error.javaClass.simpleName}: ${error.message}")
                    }
                }
            })
        }
    }

    private fun definitions(source: Cache, target: Cache, index: Int, ids: Collection<Int>, offset: Int, sourceShift: Int = 8): JsonArray = buildJsonArray {
        for (id in ids.sorted()) {
            add(buildJsonObject {
                put("id", id)
                if (id >= offset) put("osrsIdCandidate", id - offset)
                put("index", index)
                // Gielenor NPC definitions use 128-file groups; objects/items use 256.
                val archive = id ushr sourceShift
                val file = id and ((1 shl sourceShift) - 1)
                put("archive", archive)
                put("file", file)
                val targetShift = if (index == Index.NPCS) 7 else 8
                put("targetIdOccupied", target.files(index, id ushr targetShift).contains(id and ((1 shl targetShift) - 1)))
                try {
                    val data = source.data(index, archive, file)
                    put("status", if (data == null) "missing" else "present")
                    if (data != null) {
                        put("bytes", data.size)
                        put("sha256", sha256(data))
                    }
                } catch (error: Exception) {
                    put("status", "error")
                    put("error", "${error.javaClass.simpleName}: ${error.message}")
                }
            })
        }
    }

    private fun numbers(values: Collection<Int>) = JsonArray(values.map(::JsonPrimitive))

    private fun sha256(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
}

/** Check every tile terminator before using the permissive 727 terrain decoder. */
internal object LegacyTerrainScan {
    fun validate(data: ByteArray): Int {
        var position = 0
        repeat(4 * 64 * 64) { tile ->
            while (true) {
                require(position < data.size) { "Truncated terrain at tile $tile, byte $position." }
                val opcode = data[position++].toInt() and 255
                if (opcode == 0) break
                if (opcode <= 49) {
                    require(position < data.size) { "Missing terrain payload at tile $tile, byte $position." }
                    position++
                }
                if (opcode == 1) break
            }
        }
        return position
    }
}
