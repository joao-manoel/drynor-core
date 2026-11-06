package org.drynor.tools.tarnish

import kotlinx.serialization.json.*
import org.drynor.tools.osrs.InfernoAssetWriter
import org.drynor.tools.osrs.OsrsObjectDependencies
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.cache.FileCache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.*
import world.gregs.voidps.cache.definition.decoder.*
import world.gregs.voidps.cache.definition.encoder.*
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream

/** Stages the actual bark sprite, a native texture graph and a new material; never aliases material8. */
object TarnishSpiritTrees {
    fun prepare(source: Path, target: Path, output: Path) {
        require(!Files.exists(output)) { "Output exists: $output" }
        val assets = mutableListOf<InfernoAssetWriter.Asset>()
        val objectMap = sortedMapOf(29227 to 120011, 29422 to 120012)
        val modelMap = sortedMapOf<Int, Int>()
        val sources = sortedMapOf<String, String>()
        val itemMap = sortedMapOf<Int, Int>()
        val expectedItems = mutableListOf<JsonObject>()
        val expectedObjects = mutableListOf<JsonObject>()
        var targetMaterial = -1
        var targetSprite = -1
        var oldMaterialHash = ""
        var nativeTextureTemplateHash = ""
        lateinit var oldMaterialBytes: ByteArray
        TarnishCache(source).use { donor ->
            val config = donor.archive(2)
            val textureRecord = textureRecords(config.getValue(TarnishCache.hash("textures.dat"))).getValue(8)
            check(textureRecord.contentEquals("3212000101c7000000000000".chunked(2).map { it.toInt(16).toByte() }.toByteArray())) { "Unreviewed texture8 composition, tint or animation" }
            sources["texture/8"] = TarnishGarden.sha(textureRecord)
            val spriteBytes = GZIPInputStream(ByteArrayInputStream(donor.file(5, 455))).use { input ->
                input.readNBytes(1_048_577).also { require(it.size <= 1_048_576) }
            }
            sources["sprite/455"] = TarnishGarden.sha(spriteBytes)
            check(sources.getValue("sprite/455") == "bf8f870e2b30f3804527e6c4f233fb1904f08cfdb7eb28b8a4c29791632b24e4")
            val sourceGraphic = GraphicDefinition(455).also { GraphicDecoder().readLoop(it, BufferReader(spriteBytes)) }
            require(sourceGraphic.width == 128 && sourceGraphic.height == 128 && sourceGraphic.graphics?.size == 1)
            FileCache(target.toRealPath().toString(), writable = false).use { baseline ->
                objectMap.values.forEach { id -> check(baseline.data(Index.OBJECTS, id ushr 8, id and 255) == null) { "Tree destination occupied: $id" } }
                oldMaterialBytes = requireNotNull(baseline.data(Index.TEXTURE_DEFINITIONS, 0, 0))
                oldMaterialHash = TarnishGarden.sha(oldMaterialBytes)
                val oldMaterials = readMaterials(oldMaterialBytes)
                check(encode { with(MaterialEncoder()) { encode(oldMaterials) } }.contentEquals(oldMaterialBytes)) { "Existing material blob does not round trip" }
                val templateBytes = requireNotNull(baseline.data(Index.TEXTURES, 8))
                nativeTextureTemplateHash = TarnishGarden.sha(templateBytes)
                val template = TextureDefinition(8).also { TextureDecoder().readLoop(it, BufferReader(templateBytes)) }
                require(template.operations.size == 3 && template.opaque == 0 && template.opacity == 1 && template.hdr == 2)
                require(template.operations[0].type == 39 && template.operations[0].opcodes == listOf(TextureOpcode(0, 455)))
                require(template.operations[1].type == 0 && template.operations[1].opcodes.isEmpty())
                require(template.operations[2].type == 0 && template.operations[2].opcodes == listOf(TextureOpcode(0, 0)))
                targetMaterial = maxOf(oldMaterials.size, baseline.lastArchiveId(Index.TEXTURES) + 1)
                require(targetMaterial in 0..32766)
                check(baseline.data(Index.TEXTURES, targetMaterial) == null)
                targetSprite = baseline.lastArchiveId(Index.GRAPHICS) + 1
                require(targetSprite in 0..65534)
                check(baseline.data(Index.GRAPHICS, targetSprite) == null)
                val normalized = forNativeTexture(normalize(sourceGraphic, targetSprite))
                val graphicBytes = encode { with(GraphicEncoder()) { encode(normalized) } }
                val graphicReadback = GraphicDefinition(targetSprite).also { GraphicDecoder().readLoop(it, BufferReader(graphicBytes)) }
                check(graphicReadback == normalized)
                assets += InfernoAssetWriter.Asset(Index.GRAPHICS, targetSprite, 0, graphicBytes)
                val graph = template.copy(id = targetMaterial, operations = listOf(
                    template.operations[0].copy(opcodes = listOf(TextureOpcode(0, targetSprite))),
                    template.operations[1].copy(), template.operations[2].copy(),
                ))
                val graphBytes = encode { with(TextureEncoder()) { encode(graph) } }
                val graphReadback = TextureDefinition(targetMaterial).also { TextureDecoder().readLoop(it, BufferReader(graphBytes)) }
                check(graphReadback == graph)
                assets += InfernoAssetWriter.Asset(Index.TEXTURES, targetMaterial, 0, graphBytes)
                val materials = oldMaterials.toMutableList()
                while (materials.size < targetMaterial) materials += MaterialDefinition(materials.size, defined = false)
                materials += oldMaterials[8].copy(id = targetMaterial, colourHsl = 12818, repeatS = true, repeatT = true)
                val newMaterials = encode { with(MaterialEncoder()) { encode(materials) } }
                val materialReadback = readMaterials(newMaterials)
                check(materialReadback.take(oldMaterials.size) == oldMaterials)
                check(materialReadback[targetMaterial] == materials[targetMaterial])
                assets += InfernoAssetWriter.Asset(Index.TEXTURE_DEFINITIONS, 0, 0, newMaterials, replaceExisting = true)
                val objects = donor.objects()
                var nextModel = baseline.lastArchiveId(Index.MODELS) + 1
                for ((sourceId, destination) in objectMap) {
                    val raw = objects[sourceId]
                    sources["object/$sourceId"] = TarnishGarden.sha(raw)
                    val dependencies = OsrsObjectDependencies.parse(raw)
                    require(dependencies.models.size == 1 && dependencies.animations.isEmpty() && dependencies.morphs.isEmpty()
                        && dependencies.textures.isEmpty() && dependencies.sounds.isEmpty() && dependencies.sprites.isEmpty() && dependencies.mapIcons.isEmpty())
                    val sourceModel = dependencies.models.single()
                    require(sourceModel == if (sourceId == 29227) 32107 else 32111)
                    val modelRaw = donor.model(sourceModel)
                    sources["model/$sourceModel"] = TarnishGarden.sha(modelRaw)
                    val modelId = nextModel++
                    val decoded = ModelDecoder.read(modelId, TarnishModels.nativeBytes(modelRaw))
                    require(decoded.faceTextures?.filter { it.toInt() >= 0 }?.toSet() == setOf(8.toShort())) { "Unreviewed model material" }
                    val model = decoded.copy(faceTextures = decoded.faceTextures!!.map { if (it.toInt() == 8) targetMaterial.toShort() else it }.toShortArray())
                    val modelBytes = ModelEncoder.encode(model)
                    val modelReadback = ModelDecoder.read(modelId, modelBytes)
                    check(modelReadback.faceTextures.contentEquals(model.faceTextures))
                    check(modelReadback.vertexX.contentEquals(model.vertexX) && modelReadback.vertexY.contentEquals(model.vertexY) && modelReadback.vertexZ.contentEquals(model.vertexZ))
                    check(ModelEncoder.encode(modelReadback).contentEquals(modelBytes))
                    modelMap[sourceModel] = modelId
                    assets += InfernoAssetWriter.Asset(Index.MODELS, modelId, 0, modelBytes)
                    val objectDefinition = TarnishObjectConverter.convert(destination, raw, modelMap, emptyMap(), objectMap)
                    val actions=requireNotNull(objectDefinition.options)
                    actions.indices.forEach { index ->
                        if(actions[index]?.contains("Last-destination",true)==true)
                            actions[index]=actions[index]!!.substringBefore(" (")
                    }
                    val objectBytes = encode { with(ObjectEncoder()) { encode(objectDefinition) } }
                    val objectReadback = ObjectDefinition(destination).also { ObjectDecoder().readLoop(it, BufferReader(objectBytes)) }
                    check(objectReadback.modelIds.contentDeepEquals(objectDefinition.modelIds) && objectReadback.options.contentEquals(objectDefinition.options))
                    check(objectReadback.sizeX==objectDefinition.sizeX && objectReadback.sizeY==objectDefinition.sizeY && objectReadback.solid==objectDefinition.solid)
                    assets += InfernoAssetWriter.Asset(Index.OBJECTS, destination ushr 8, destination and 255, objectBytes)
                    expectedObjects+=buildJsonObject {
                        put("source",sourceId);put("id",destination);put("name",objectDefinition.name)
                        put("sizeX",objectDefinition.sizeX);put("sizeY",objectDefinition.sizeY);put("solid",objectDefinition.solid)
                        put("options",JsonArray(actions.map { it?.let(::JsonPrimitive) ?: JsonNull }))
                    }
                }
                val itemRecords=TarnishMaterials.itemRecords(donor)
                val saplingBytes=requireNotNull(baseline.data(Index.ITEMS,5375 ushr 8,5375 and 255))
                val sapling=ItemDefinition(5375).also { ItemDecoder().readLoop(it,BufferReader(saplingBytes)) }
                require(sapling.name=="Spirit sapling" && sapling.notedTemplateId==-1 && sapling.modelId>0)
                sources["native-item/5375"]=TarnishGarden.sha(saplingBytes)
                var nextItem=30320
                val cards=listOf(
                    TarnishConstructionCards.Card(20635,"Spirit tree",120011,75,listOf(5375 to 1),"Spirit sapling; Farming 83; watering can with water"),
                    TarnishConstructionCards.Card(20637,"Spirit tree & fairy ring",120012,95,listOf(5375 to 1,6004 to 10,30310 to 1),"Spirit sapling, 10 bittercap mushrooms, fairy enchantment; Farming 83; watering can with water")
                )
                for(card in cards) {
                    while(baseline.data(Index.ITEMS,nextItem ushr 8,nextItem and 255)!=null)nextItem++
                    require(nextItem<=32767)
                    val id=nextItem++
                    val raw=itemRecords[card.source]
                    val item=TarnishStamina.parse(id,raw).definition
                    require(item.name==card.name && item.noteId==-1 && item.notedTemplateId==-1)
                    require(item.originalTextureColours==null) { "Tree card item retexture needs explicit mapping" }
                    sources["item/${card.source}"]=TarnishGarden.sha(raw)
                    val sourceModel=item.modelId
                    item.modelId=modelMap.getOrPut(sourceModel) {
                        val destination=nextModel++
                        val rawModel=donor.model(sourceModel)
                        sources["model/$sourceModel"]=TarnishGarden.sha(rawModel)
                        val model=ModelDecoder.read(destination,TarnishLegacyModels.nativeBytes(rawModel))
                        require(model.faceTextures?.filter { it.toInt()>=0 }?.all { it.toInt()==8 }!=false) { "Tree card needs an unreviewed texture" }
                        val mapped=model.copy(faceTextures=model.faceTextures?.map { if(it.toInt()==8)targetMaterial.toShort() else it }?.toShortArray())
                        val bytes=ModelEncoder.encode(mapped)
                        check(ModelEncoder.encode(ModelDecoder.read(destination,bytes)).contentEquals(bytes))
                        assets+=InfernoAssetWriter.Asset(Index.MODELS,destination,0,bytes)
                        destination
                    }
                    card.materials.forEach { (material,_) -> require(baseline.data(Index.ITEMS,material ushr 8,material and 255)!=null) }
                    item.params=TarnishConstructionCards.parameters(card)
                    val bytes=encode { with(ItemEncoder()) { encode(item) } }
                    val decoded=ItemDefinition(id).also { ItemDecoder().readLoop(it,BufferReader(bytes)) }
                    check(decoded.params==item.params && decoded.modelId==item.modelId)
                    check(encode { with(ItemEncoder()) { encode(decoded) } }.contentEquals(bytes))
                    assets+=InfernoAssetWriter.Asset(Index.ITEMS,id ushr 8,id and 255,bytes)
                    itemMap[card.source]=id
                    expectedItems+=buildJsonObject {
                        put("source",card.source);put("id",id);put("name",item.name);put("modelId",item.modelId)
                        put("objectId",card.objectId);put("constructionLevel",card.level);put("farmingLevel",83)
                        put("noteId",-1);put("notedTemplateId",-1);put("requirements",card.requirements)
                        put("materials",JsonArray(card.materials.map { (material,count)->buildJsonObject { put("id",material);put("count",count) } }))
                    }
                }
            }
        }
        assets.forEach { a ->
            val file = output.resolve("assets/${a.index}/${a.archive}/${a.file}.bin")
            Files.createDirectories(file.parent); Files.write(file, a.bytes)
        }
        val manifest = buildJsonObject {
            put("schemaVersion", 1); put("status", "garden-spirit-trees-native-readback"); put("validated", false)
            put("source", source.toRealPath().toString()); put("target", target.toRealPath().toString())
            put("sourceObjectIds", buildJsonObject { objectMap.forEach { (a,b) -> put(a.toString(),b) } })
            put("sourceModelIds", buildJsonObject { modelMap.forEach { (a,b) -> put(a.toString(),b) } })
            put("sourceItemIds",buildJsonObject { itemMap.forEach { (a,b)->put(a.toString(),b) } })
            put("expectedItems",JsonArray(expectedItems))
            put("expectedObjects",JsonArray(expectedObjects))
            put("sourceMaterialIds", buildJsonObject { put("8",targetMaterial) })
            put("sourceSpriteIds", buildJsonObject { put("455",targetSprite) })
            put("sourceHashes", buildJsonObject { sources.forEach { (a,b) -> put(a,b) } })
            put("nativeTextureTemplate",8); put("nativeTextureTemplateSha256",nativeTextureTemplateHash)
            put("materialPolicy", "New material cloned from native bark8 with source averageHSL12818 and repeatS/repeatT; existing materials preserved. Source sprite normalized as Tarnish Texture.load requires. Transparency/rendering still needs actual client validation.")
            put("remaining", "Review cutout/alpha behavior in native client, actual client visuals/collision and teleport options")
            put("omissions", JsonArray(emptyList()))
            put("assets", JsonArray(assets.map { a -> buildJsonObject {
                put("index",a.index); put("archive",a.archive); put("file",a.file); put("sha256",TarnishGarden.sha(a.bytes))
                if (a.replaceExisting) { put("replaceExisting",true); put("previousSha256",oldMaterialHash) }
            } }))
        }
        Files.writeString(output.resolve("manifest.json"), Json { prettyPrint=true }.encodeToString(JsonObject.serializer(),manifest))
        println("Prepared spirit trees and native construction cards: ${assets.size} assets; new material$targetMaterial, sprite$targetSprite. No cache changed.")
    }

    fun apply(output:Path,backup:Path,priorPackages:List<Path>) {
        val manifest=Json.parseToJsonElement(Files.readString(output.resolve("manifest.json"))).jsonObject
        val entries=manifest.getValue("assets").jsonArray.map { it.jsonObject }
        require(entries.all { it.getValue("index").jsonPrimitive.int in setOf(Index.MODELS,Index.OBJECTS,Index.ITEMS,Index.TEXTURES,Index.GRAPHICS,Index.TEXTURE_DEFINITIONS) })
        val replacements=entries.filter { it["replaceExisting"]?.jsonPrimitive?.boolean==true }
        require(replacements.size==1 && replacements.single().getValue("index").jsonPrimitive.int==Index.TEXTURE_DEFINITIONS && replacements.single().getValue("archive").jsonPrimitive.int==0 && replacements.single().getValue("file").jsonPrimitive.int==0)
        TarnishReviewedPatch.apply(output,backup,priorPackages,"garden-spirit-trees-native-readback") { cache ->
            val materialId=manifest.getValue("sourceMaterialIds").jsonObject.getValue("8").jsonPrimitive.int
            val spriteId=manifest.getValue("sourceSpriteIds").jsonObject.getValue("455").jsonPrimitive.int
            val materials=readMaterials(requireNotNull(cache.data(Index.TEXTURE_DEFINITIONS,0,0)))
            check(materials[materialId].defined && materials[materialId].colourHsl==12818)
            val texture=TextureDefinition(materialId).also { TextureDecoder().readLoop(it,BufferReader(requireNotNull(cache.data(Index.TEXTURES,materialId)))) }
            check(texture.operations.first().opcodes==listOf(TextureOpcode(0,spriteId)))
            val sprite=GraphicDefinition(spriteId).also { GraphicDecoder().readLoop(it,BufferReader(requireNotNull(cache.data(Index.GRAPHICS,spriteId)))) }
            check(sprite.width==128 && sprite.height==128 && sprite.graphics?.size==1)
            manifest.getValue("expectedItems").jsonArray.forEach { value ->
                val item=value.jsonObject;val id=item.getValue("id").jsonPrimitive.int
                val readback=ItemDefinition(id).also { ItemDecoder().readLoop(it,BufferReader(requireNotNull(cache.data(Index.ITEMS,id ushr 8,id and 255)))) }
                check(readback.getParamVal(23)==item.getValue("constructionLevel").jsonPrimitive.int)
            }
        }
    }

    internal fun normalize(source: GraphicDefinition, targetId: Int): GraphicDefinition {
        val frame = requireNotNull(source.graphics).single()
        require(frame.alpha == null && frame.offsetX >= 0 && frame.offsetY >= 0)
        require(frame.offsetX + frame.width <= source.width && frame.offsetY + frame.height <= source.height)
        val raster = ByteArray(source.width * source.height)
        for (y in 0 until frame.height) frame.raster.copyInto(raster, (y + frame.offsetY) * source.width + frame.offsetX, y * frame.width, (y + 1) * frame.width)
        val normalized = IndexedGraphic(width=source.width,height=source.height,flags=0).apply { this.raster=raster; palette=frame.palette.copyOf() }
        return GraphicDefinition(targetId,arrayOf(normalized),source.rawPalette.copyOf(),false,source.width,source.height)
    }

    /** 727 ARGB texture rasterization emits each row right-to-left; OSRS texture pixels are left-to-right. */
    internal fun forNativeTexture(normalized:GraphicDefinition):GraphicDefinition {
        val frame=requireNotNull(normalized.graphics).single()
        require(frame.flags==0 && frame.alpha==null && frame.offsetX==0 && frame.offsetY==0)
        require(frame.width==normalized.width && frame.height==normalized.height && frame.raster.size==frame.width*frame.height)
        val reversed=ByteArray(frame.raster.size)
        for(y in 0 until frame.height) for(x in 0 until frame.width)
            reversed[y*frame.width+x]=frame.raster[y*frame.width+frame.width-1-x]
        return normalized.copy(graphics=arrayOf(frame.copy().also { it.raster=reversed;it.palette=frame.palette.copyOf() }),rawPalette=normalized.rawPalette.copyOf())
    }

    /** TextureProvider stops at the highest ID; the physical texture IDs are sparse. */
    internal fun textureRecords(bytes:ByteArray):Map<Int,ByteArray> {
        val reader=ByteBuffer.wrap(bytes)
        require(reader.remaining()>=2)
        val highest=reader.short.toInt() and 65535
        val result=linkedMapOf<Int,ByteArray>()
        while(reader.hasRemaining()) {
            require(reader.remaining()>=4)
            val id=reader.short.toInt() and 65535
            val size=reader.short.toInt() and 65535
            require(id<=highest && id !in result && size<=reader.remaining())
            result[id]=ByteArray(size).also(reader::get)
            if(id==highest)break
        }
        require(result.keys.lastOrNull()==highest && !reader.hasRemaining())
        return result
    }

    private fun readMaterials(bytes: ByteArray): List<MaterialDefinition> {
        val reader = BufferReader(bytes)
        val decoder = MaterialDecoder()
        return decoder.create(reader.readUnsignedShort()).also { decoder.load(it,reader); check(reader.readableBytes()==0) }.toList()
    }
    private fun encode(block: BufferWriter.() -> Unit) = BufferWriter(1 shl 20).apply(block).toArray()
}
