package org.drynor.tools.osrs

import kotlinx.serialization.json.*
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.cache.FileCache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.MapDefinition
import world.gregs.voidps.cache.definition.data.MapTile
import world.gregs.voidps.cache.definition.data.MapEnvironmentEffect
import world.gregs.voidps.cache.definition.decoder.MapEffectDecoder
import world.gregs.voidps.cache.definition.decoder.MapTileDecoder
import world.gregs.voidps.cache.definition.encoder.MapEffectEncoder.writeEffects
import world.gregs.voidps.cache.definition.encoder.MapTileEncoder
import world.gregs.voidps.cache.source.ArchiveMetadata
import world.gregs.voidps.cache.source.IndexNames
import world.gregs.voidps.cache.source.codec.SourceArchive
import world.gregs.voidps.cache.source.codec.SourceCodecs
import world.gregs.voidps.cache.store.ArchiveGroup
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.security.MessageDigest

/** Reproduces the donor's underwater floor fallback using native 727 assets, without client OSRS branches. */
object ZulrahWaterPreview {
    private val regions = listOf(8494,8495,8496,8750,8751,9006,9007,9008)
    private data class Asset(val region: Int,val archive: Int,val name: String,val bytes: ByteArray)
    private val swampRegions = setOf(8751,9007,9008)

    internal fun swampAtmosphere(surface: ByteArray,region: Int): ByteArray {
        require(region in swampRegions) { "No donor swamp atmosphere for $region" }
        require(LegacyTerrainScan.validate(surface) == surface.size) { "Atmosphere fallback only applies to maps without environment" }
        // Atmosphere.decodeGeneric maps flag2/4/8 to sunShineness/sunX/sunY, then divides by256.
        val effect = MapEnvironmentEffect(sunColour=13028260,sunAmbient=323,sunLight=261,sunBacklight=199,fogColour=5462846,fogDepth=1642)
        val tail = BufferWriter(64).also { with(it) { writeEffects(listOf(effect)) } }.toArray()
        val result = surface + tail
        val decoded = MapTileDecoder.decodeArchive(result,region)
        check(decoded.effects.size == 1)
        check(encode(decoded).contentEquals(result)) { "Surface atmosphere roundtrip changed terrain prefix" }
        check(result.copyOfRange(0,surface.size).contentEquals(surface))
        return result
    }

    internal fun underwater(surface: MapDefinition): MapDefinition {
        require(surface.id in regions && surface.levels == 4)
        val result = MapDefinition(surface.id,levels=1)
        for (i in 0 until 4096) {
            val tile = MapTile(surface.tiles[i])
            // Donor sets 1000 client units; the native underwater height byte encodes multiples of32.
            // 31*32=992 is the nearest representable depth (0.8% smaller); never silently round to0.
            result.tiles[i] = MapTile(31,tile.attrOpcode,tile.overlayId,tile.overlayPath,tile.overlayRotation,0,tile.underlayId).packed
        }
        return result
    }

    /** MapTileDecoder accepts incomplete streams; validate terminators and payload bounds first. */
    internal fun decodeStrict(bytes: ByteArray,region: Int): MapDefinition {
        var p = 0
        repeat(4096) {
            while (true) {
                require(p < bytes.size) { "Truncated underwater tile $it" }
                val opcode = bytes[p++].toInt() and 255
                if (opcode == 0) break
                if (opcode <= 49) { require(p < bytes.size); p++ }
                if (opcode == 1) break
            }
        }
        require(p == bytes.size) { "Unexpected underwater trailing bytes" }
        val result = MapDefinition(region,levels=1)
        check(MapTileDecoder.decode(bytes,result,1) == bytes.size)
        return result
    }

    internal fun encode(map: MapDefinition): ByteArray = BufferWriter(65536).also { writer -> with(MapTileEncoder()) { writer.encode(map) } }.toArray()

    fun build(inputPreviewRoot: Path,outputRoot: Path) {
        val root = inputPreviewRoot.toRealPath()
        val output = outputRoot.toAbsolutePath().normalize()
        require(!output.startsWith(root) && !Files.exists(output)) { "Output must be new and outside input preview" }
        val manifest = Json.parseToJsonElement(Files.readString(root.resolve("zulrah-static-preview.json"))).jsonObject
        require(manifest["status"]?.jsonPrimitive?.content == "experimental-zulrah-static")
        val cachePath = root.resolve(manifest.getValue("packedCache").jsonPrimitive.content).normalize().toRealPath()
        require(cachePath.startsWith(root))
        val imported = manifest.getValue("regions").jsonArray.map { it.jsonObject.getValue("regionId").jsonPrimitive.int }
        require(imported.containsAll(regions))
        FileCache(cachePath.toString(),writable=false).use { baseline ->
            val snapshot = InfernoDependencyPlan.snapshot(baseline)
            val assets = mutableListOf<Asset>()
            val regionReports = mutableListOf<JsonObject>()
            val atmosphereReports = mutableListOf<JsonObject>()
            var nextArchive = baseline.lastArchiveId(Index.MAPS)+1
            for (region in regions) {
                val suffix = "${region ushr 8}_${region and 255}"
                val name = "um$suffix"
                val existing = baseline.archiveId(Index.MAPS,name)
                val archive = if(existing >= 0) existing else nextArchive++
                val original = if(existing >= 0) requireNotNull(baseline.data(Index.MAPS,existing)) else null
                val old = original?.let { decodeStrict(it,region) }
                val terrainBytes = requireNotNull(baseline.data(Index.MAPS,"m$suffix"))
                require(LegacyTerrainScan.validate(terrainBytes) == terrainBytes.size) { "Unverified surface environment in $region" }
                val surface = MapDefinition(region)
                check(MapTileDecoder.decode(terrainBytes,surface) == terrainBytes.size)
                if (region in swampRegions) {
                    val surfaceName = "m$suffix"
                    val withAtmosphere = swampAtmosphere(terrainBytes,region)
                    assets.add(Asset(region,baseline.archiveId(Index.MAPS,surfaceName),surfaceName,withAtmosphere))
                    atmosphereReports.add(buildJsonObject {
                        put("regionId",region); put("terrainPrefixBytesPreserved",terrainBytes.size)
                        put("beforeSha256",hash(terrainBytes)); put("afterSha256",hash(withAtmosphere))
                        put("sunColour",13028260); put("sunAmbient",323); put("sunLight",261); put("sunBacklight",199)
                        put("fogColour",5462846); put("fogDepth",1642)
                    })
                }
                val converted = underwater(surface)
                val encoded = encode(converted)
                val decoded = decodeStrict(encoded,region)
                check(decoded.tiles.contentEquals(converted.tiles))
                check(encode(decoded).contentEquals(encoded))
                assets.add(Asset(region,archive,name,encoded))
                regionReports.add(buildJsonObject {
                    put("regionId",region); put("archive",archive); put("replaced",existing >= 0)
                    put("oldBytes",original?.size ?: 0); put("newBytes",encoded.size)
                    put("oldSha256",original?.let(::hash)?.let(::JsonPrimitive) ?: JsonNull)
                    put("newSha256",hash(encoded)); put("tiles",4096); put("nativeDepthUnits",992)
                    put("oldHeightCounts",buildJsonObject { old?.tiles?.take(4096)?.map { MapTile(it).height }?.groupingBy { it }?.eachCount()?.toSortedMap()?.forEach { (h,count) -> put(h.toString(),count) } })
                    put("overlayTiles",(0 until 4096).count { MapTile(converted.tiles[it]).overlayId != 0 })
                    put("underlayTiles",(0 until 4096).count { MapTile(converted.tiles[it]).underlayId != 0 })
                    put("underwaterLocArchivePreserved",baseline.archiveId(Index.MAPS,"ul$suffix"))
                })
            }
            Files.createDirectories(output)
            val editable = output.resolve("source-package").resolve(IndexNames.name(Index.MAPS))
            val codec = SourceCodecs.codec(Index.MAPS)
            for (asset in assets) {
                val metadata = ArchiveMetadata(nameHash=asset.name.hashCode())
                metadata.fileIds(intArrayOf(0))
                val unpacked = codec.unpack(editable,SourceArchive(Index.MAPS,asset.archive,asset.name,intArrayOf(0),asset.bytes,listOf(asset.bytes),emptyList(),metadata))
                check(unpacked.pristine.isEmpty()) { "Underwater map requires pristine fallback: ${asset.name}" }
                for(file in unpacked.files) { val path=editable.resolve(file.path); Files.createDirectories(path.parent); Files.write(path,file.bytes,CREATE_NEW) }
                val recompiled = codec.pack(editable,asset.archive,metadata)
                val files = ArchiveGroup.split(recompiled.group,recompiled.fileIds).files
                check(recompiled.fileIds.contentEquals(intArrayOf(0)) && files.single().contentEquals(asset.bytes))
                Files.createDirectories(editable)
                Files.writeString(editable.resolve("${asset.archive}.metadata.json"),metadata.write(),CREATE_NEW)
            }
            Files.writeString(output.resolve("source-package/README.txt"),"Partial editable underwater package; do not pack as a complete cache. These JSON sources were recompiled and checked byte-for-byte.\n",CREATE_NEW)
            val packed = output.resolve("packed-cache")
            Files.createDirectory(packed)
            Files.list(cachePath).use { files -> files.filter { it.fileName.toString().startsWith("main_file_cache.") }.forEach { Files.copy(it,packed.resolve(it.fileName)) } }
            FileCache(packed.toString(),writable=true).use { destination ->
                for(asset in assets) {
                    requireNotNull(destination.referenceTable(Index.MAPS)).archiveOrCreate(asset.archive).nameHash=asset.name.hashCode()
                    destination.write(Index.MAPS,asset.archive,0,asset.bytes)
                }
            }
            var unchanged = 0
            FileCache(packed.toString(),writable=false).use { result ->
                for(asset in assets) {
                    check(result.archiveId(Index.MAPS,asset.name) == asset.archive)
                    val bytes = requireNotNull(result.data(Index.MAPS,asset.archive))
                    check(bytes.contentEquals(asset.bytes))
                    if(asset.name.startsWith("um")) decodeStrict(bytes,asset.region)
                    else {
                        val decoded = MapTileDecoder.decodeArchive(bytes,asset.region)
                        check(decoded.effects.single() is MapEnvironmentEffect)
                        check(encode(decoded).contentEquals(bytes))
                    }
                }
                val touched = assets.map { it.archive }.toSet()
                for(index in baseline.indices()) for(archive in baseline.archiveIds(index)) {
                    if(index != Index.MAPS || archive !in touched) {
                        check(baseline.container(index,archive).contentEquals(result.container(index,archive))) { "Unrelated archive changed: $index/$archive" }; unchanged++
                    } else for(file in baseline.files(index,archive)) if(file != 0) check(baseline.data(index,archive,file).contentEquals(result.data(index,archive,file)))
                }
            }
            check(snapshot == InfernoDependencyPlan.snapshot(baseline))
            val report = JsonObject(manifest.toMutableMap().apply {
                put("packedCache",JsonPrimitive("packed-cache")); put("renderingValidated",JsonPrimitive(false))
                put("water",buildJsonObject {
                    put("status","native-underwater-fallback"); put("regions",JsonArray(regionReports))
                    put("sourceDepthUnits",1000); put("nativeDepthUnits",992); put("relativeDepthError",0.008)
                    put("unchangedArchivesVerified",unchanged); put("worldMapPreserved",true)
                    put("nativeUnderwaterRegionsPreserved",true); put("underwaterLocationsPreserved",true)
                    put("clientValidated",false)
                    put("swampAtmosphere",JsonArray(atmosphereReports))
                })
            })
            Files.writeString(output.resolve("zulrah-static-preview.json"),Json { prettyPrint=true }.encodeToString(JsonObject.serializer(),report)+"\n",CREATE_NEW)
            println("Native underwater preview verified: $output")
        }
    }
    private fun hash(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
