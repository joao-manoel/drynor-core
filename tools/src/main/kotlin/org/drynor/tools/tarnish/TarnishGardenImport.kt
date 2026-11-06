package org.drynor.tools.tarnish

import kotlinx.serialization.json.*
import org.drynor.tools.osrs.InfernoAssetWriter
import org.drynor.tools.osrs.OsrsObjectDependencies
import world.gregs.voidps.cache.FileCache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.decoder.ModelDecoder
import world.gregs.voidps.cache.definition.encoder.ModelEncoder
import world.gregs.voidps.cache.definition.encoder.ObjectEncoder
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.cache.definition.data.ObjectDefinition
import world.gregs.voidps.cache.definition.decoder.ObjectDecoder
import java.nio.file.Files
import java.nio.file.Path

/** Explicit static diagnostic stage. Runtime building is blocked until animated assets are validated. */
object TarnishGardenImport {
    fun prepare(source: Path, target: Path, output: Path) {
        require(!Files.exists(output)) { "Output already exists" }
        val assets = mutableListOf<InfernoAssetWriter.Asset>()
        val selected = listOf(29120, 29122, 29237, 29238, 29239, 29240, 29241)
        val objectMap = sortedMapOf<Int, Int>()
        val modelMap = sortedMapOf<Int, Int>()
        val omissions = mutableListOf<JsonObject>()
        TarnishCache(source).use { donor ->
            val objects = donor.objects()
            FileCache(target.toString(), writable = false).use { baseline ->
                fun nextObject(): Int {
                    var id = (objectMap.values.maxOrNull() ?: 119999) + 1
                    while (id in objectMap.values || baseline.data(Index.OBJECTS, id ushr 8, id and 255) != null) id++
                    require(id <= 131071)
                    return id
                }
                var nextModel = baseline.lastArchiveId(Index.MODELS) + 1
                for (id in selected) objectMap[id] = nextObject()
                for (id in selected) {
                    val deps = OsrsObjectDependencies.parse(objects[id])
                    require(deps.morphs.isEmpty() && deps.textures.isEmpty() && deps.sprites.isEmpty() && deps.mapIcons.isEmpty())
                    for (modelId in deps.models) {
                        if (modelId in modelMap) continue
                        val destination = nextModel++
                        val bytes = TarnishModels.nativeBytes(donor.model(modelId))
                        val decoded = ModelDecoder.read(destination, bytes)
                        require((decoded.faceTextures ?: shortArrayOf()).none { it.toInt() >= 0 }) { "Material mapping required" }
                        check(ModelEncoder.encode(decoded).contentEquals(bytes))
                        modelMap[modelId] = destination
                        assets += InfernoAssetWriter.Asset(Index.MODELS, destination, 0, bytes)
                    }
                    // The object converter requires physical source IDs; Tarnish uses no Gielenor offsets.
                    val definition = TarnishObjectConverter.convert(objectMap.getValue(id), objects[id], modelMap, emptyMap(), objectMap, staticPreview = true)
                    val bytes = BufferWriter(65536).apply { with(ObjectEncoder()) { encode(definition) } }.toArray()
                    val decoded = ObjectDefinition(definition.id)
                    ObjectDecoder().readLoop(decoded, BufferReader(bytes))
                    check(decoded.name == definition.name && decoded.modelIds.contentDeepEquals(definition.modelIds))
                    val destination = objectMap.getValue(id)
                    assets += InfernoAssetWriter.Asset(Index.OBJECTS, destination ushr 8, destination and 255, bytes)
                    if (deps.animations.isNotEmpty() || deps.sounds.isNotEmpty()) omissions += buildJsonObject {
                        put("sourceId", id)
                        put("animations", JsonArray(deps.animations.map(::JsonPrimitive)))
                        put("sounds", JsonArray(deps.sounds.map(::JsonPrimitive)))
                    }
                }
            }
        }
        for (asset in assets) {
            val path = output.resolve("assets/${asset.index}/${asset.archive}/${asset.file}.bin")
            Files.createDirectories(path.parent); Files.write(path, asset.bytes)
        }
        val manifest = buildJsonObject {
            put("schemaVersion", 1); put("status", "static-diagnostic-only"); put("validated", false)
            put("source", source.toRealPath().toString()); put("target", target.toRealPath().toString())
            put("sourceObjectIds", buildJsonObject { objectMap.forEach { (a,b) -> put(a.toString(), b) } })
            put("sourceModelIds", buildJsonObject { modelMap.forEach { (a,b) -> put(a.toString(), b) } })
            put("omissions", JsonArray(omissions))
            put("assets", JsonArray(assets.map { a -> buildJsonObject {
                put("index", a.index); put("archive", a.archive); put("file", a.file); put("sha256", TarnishGarden.sha(a.bytes))
            } }))
        }
        Files.writeString(output.resolve("manifest.json"), Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), manifest))
        println("Prepared ${objectMap.size} static diagnostic objects, ${modelMap.size} models. No cache changed.")
    }

    fun apply(output: Path, backup: Path) {
        val manifest = Json.parseToJsonElement(Files.readString(output.resolve("manifest.json"))).jsonObject
        require(manifest.getValue("status").jsonPrimitive.content == "static-diagnostic-only")
        val target = Path.of(manifest.getValue("target").jsonPrimitive.content)
        val baseline = Json.parseToJsonElement(Files.readString(backup.resolve("cache-baseline.json"))).jsonArray
        baseline.forEach { record ->
            val entry = record.jsonObject
            val name = entry.getValue("name").jsonPrimitive.content
            val expected = entry.getValue("sha256").jsonPrimitive.content
            check(TarnishGarden.sha(Files.readAllBytes(target.resolve(name))) == expected) { "Cache changed since backup: $name" }
            check(TarnishGarden.sha(Files.readAllBytes(backup.resolve("cache").resolve(name))) == expected) { "Backup mismatch: $name" }
        }
        val assets = manifest.getValue("assets").jsonArray.map { record ->
            val r = record.jsonObject
            val index=r.getValue("index").jsonPrimitive.int; val archive=r.getValue("archive").jsonPrimitive.int; val file=r.getValue("file").jsonPrimitive.int
            val bytes=Files.readAllBytes(output.resolve("assets/$index/$archive/$file.bin"))
            check(TarnishGarden.sha(bytes)==r.getValue("sha256").jsonPrimitive.content)
            InfernoAssetWriter.Asset(index,archive,file,bytes)
        }
        val changedGroups=assets.map { it.index to it.archive }.toSet()
        val unchanged=mutableMapOf<Pair<Int,Int>,String>()
        val siblings=mutableMapOf<Triple<Int,Int,Int>,String>()
        FileCache(target.toString(), writable=false).use { cache ->
            assets.forEach { check(cache.data(it.index,it.archive,it.file)==null) { "Destination occupied" } }
            for(index in cache.indices()) for(archive in cache.archiveIds(index)) {
                if(index to archive !in changedGroups) unchanged[index to archive]=TarnishGarden.sha(requireNotNull(cache.container(index,archive)))
                else for(file in cache.files(index,archive)) siblings[Triple(index,archive,file)]=TarnishGarden.sha(requireNotNull(cache.data(index,archive,file)))
            }
        }
        try {
            InfernoAssetWriter.write(target,assets)
            FileCache(target.toString(), writable=false).use { cache ->
                assets.forEach { check(cache.data(it.index,it.archive,it.file)!!.contentEquals(it.bytes)) }
                unchanged.forEach { (address,hash) -> check(TarnishGarden.sha(requireNotNull(cache.container(address.first,address.second)))==hash) { "Unrelated archive changed: $address" } }
                siblings.forEach { (address,hash) -> check(TarnishGarden.sha(requireNotNull(cache.data(address.first,address.second,address.third)))==hash) { "Existing object file changed: $address" } }
            }
        } catch(failure:Exception) {
            baseline.forEach { record ->
                val name=record.jsonObject.getValue("name").jsonPrimitive.content
                Files.copy(backup.resolve("cache").resolve(name),target.resolve(name),java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
            throw IllegalStateException("Diagnostic import failed; baseline cache restored",failure)
        }
        Files.writeString(output.resolve("applied.json"),buildJsonObject {
            put("assetsReadBack",assets.size);put("unchangedArchives",unchanged.size);put("preservedSiblingFiles",siblings.size)
            put("backup",backup.toRealPath().toString());put("status","static-diagnostic-only")
        }.toString())
        println("Applied static diagnostic resources; verified ${unchanged.size} unrelated archives and ${siblings.size} sibling files. Not gameplay-ready.")
    }
}
