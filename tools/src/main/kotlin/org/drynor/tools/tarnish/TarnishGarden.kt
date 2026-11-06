package org.drynor.tools.tarnish

import kotlinx.serialization.json.*
import org.drynor.tools.osrs.OsrsObjectDependencies
import world.gregs.voidps.cache.definition.decoder.ModelDecoder
import world.gregs.voidps.cache.definition.encoder.ModelEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Inventory is deliberately read-only; unknown grammar and modern models remain explicit blockers. */
object TarnishGarden {
    val sourceObjects = listOf(29120, 29122, 29227, 29228, 29229, 29237, 29238, 29239, 29240, 29241, 29422, 31554)

    fun inventory(source: Path, output: Path) {
        require(!Files.exists(output)) { "Report already exists: $output" }
        TarnishCache(source).use { cache ->
            val objects = cache.objects()
            val queue = ArrayDeque(sourceObjects)
            val seen = sortedSetOf<Int>()
            val models = sortedSetOf<Int>()
            val definitions = mutableListOf<JsonObject>()
            while (queue.isNotEmpty()) {
                val id = queue.removeFirst()
                if (!seen.add(id)) continue
                definitions += buildJsonObject {
                    put("sourceId", id)
                    try {
                        val bytes = objects[id]
                        val deps = OsrsObjectDependencies.parse(bytes)
                        put("sha256", sha(bytes))
                        put("name", deps.name)
                        put("models", ints(deps.models))
                        put("animations", ints(deps.animations))
                        put("textures", ints(deps.textures))
                        put("morphs", ints(deps.morphs))
                        put("varbits", ints(deps.varbits))
                        put("varps", ints(deps.varps))
                        put("sounds", ints(deps.sounds))
                        put("mapscene", ints(deps.sprites))
                        put("mapIcons", ints(deps.mapIcons))
                        models += deps.models
                        queue.addAll(deps.morphs)
                        deps.morphSelector?.let { selector ->
                            put("morphSelector", buildJsonObject {
                                put("opcode", selector.opcode); put("varbit", selector.rawVarbit); put("varp", selector.rawVarp)
                                put("variants", ints(selector.variants)); put("default", selector.default)
                            })
                        }
                        put("definitionParsed", true)
                    } catch (error: Exception) {
                        put("definitionParsed", false)
                        put("blocked", error.message ?: error.javaClass.simpleName)
                    }
                }
            }
            val modelReports = models.map { id -> buildJsonObject {
                put("sourceId", id)
                try {
                    val bytes = cache.model(id)
                    put("sha256", sha(bytes)); put("bytes", bytes.size)
                    val nativeBytes = TarnishModels.nativeBytes(bytes)
                    val model = ModelDecoder.read(id, nativeBytes)
                    val encoded = ModelEncoder.encode(model)
                    check(encoded.contentEquals(nativeBytes)) { "Native model round trip changed converted bytes" }
                    put("vertices", model.vertexCount); put("faces", model.faceCount)
                    put("materials", ints((model.faceTextures ?: shortArrayOf()).map { it.toInt() }.filter { it >= 0 }.toSet()))
                    put("nativeRoundTrip", true)
                } catch (error: Exception) {
                    put("nativeRoundTrip", false)
                    put("blocked", error.message ?: error.javaClass.simpleName)
                }
            } }
            val report = buildJsonObject {
                put("schemaVersion", 1)
                put("source", source.toRealPath().toString())
                put("readOnly", true)
                put("scope", "Superior Garden objects and recursive morph dependencies; not a complete room/map inventory")
                put("sourceObjectCount", objects.size)
                put("objects", JsonArray(definitions))
                put("models", JsonArray(modelReports))
                put("readyForImport", false)
                put("remaining", JsonArray(listOf("Sequence/frame/base conversion", "Source material/texture mapping", "Native object field translation and collision-safe destination allocation", "Client visual validation").map(::JsonPrimitive)))
            }
            output.toAbsolutePath().parent?.let(Files::createDirectories)
            Files.writeString(output, Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), report))
            println("Tarnish garden: ${seen.size} definitions, ${models.size} models; report=$output")
        }
    }

    private fun ints(values: Iterable<Int>) = JsonArray(values.map(::JsonPrimitive))
    internal fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

fun runTarnish(args: List<String>) {
    if(args.firstOrNull()=="prepare-jewellery-frame" && args.size==4) {
        TarnishJewelleryFrame.prepare(Path.of(args[1]),Path.of(args[2]),Path.of(args[3]));return
    }
    if(args.firstOrNull()=="apply-jewellery-frame" && args.size>=4) {
        TarnishJewelleryFrame.apply(Path.of(args[1]),Path.of(args[2]),args.drop(3).map { Path.of(it) });return
    }
    if(args.firstOrNull()=="prepare-jewellery-polish" && args.size==4) {
        TarnishJewelleryPolish.prepare(Path.of(args[1]),Path.of(args[2]),Path.of(args[3]));return
    }
    if(args.firstOrNull()=="apply-jewellery-polish" && args.size>=4) {
        TarnishJewelleryPolish.apply(Path.of(args[1]),Path.of(args[2]),args.drop(3).map { Path.of(it) });return
    }
    if(args.firstOrNull()=="audit-jewellery-polish" && args.size==2) {
        TarnishJewelleryPolish.audit(Path.of(args[1]));return
    }
    if(args.firstOrNull()=="prepare-jewellery-ui" && args.size==4) {
        TarnishJewelleryUi.prepare(Path.of(args[1]),Path.of(args[2]),Path.of(args[3]));return
    }
    if(args.firstOrNull()=="apply-jewellery-ui" && args.size>=4) {
        TarnishJewelleryUi.apply(Path.of(args[1]),Path.of(args[2]),args.drop(3).map { Path.of(it) });return
    }
    if(args.firstOrNull()=="prepare-gallery" && args.size==4) {
        TarnishGallery.prepare(Path.of(args[1]),Path.of(args[2]),Path.of(args[3]));return
    }
    if(args.firstOrNull()=="apply-gallery" && args.size>=4) {
        TarnishGallery.apply(Path.of(args[1]),Path.of(args[2]),args.drop(3).map { Path.of(it) });return
    }
    if(args.firstOrNull()=="prepare-gallery-room-ui" && args.size==4) {
        TarnishGalleryRoomUi.prepare(Path.of(args[1]),Path.of(args[2]),Path.of(args[3]));return
    }
    if(args.firstOrNull()=="apply-gallery-room-ui" && args.size>=4) {
        TarnishGalleryRoomUi.apply(Path.of(args[1]),Path.of(args[2]),args.drop(3).map { Path.of(it) });return
    }
    if(args.firstOrNull()=="prepare-canopy-orientation" && args.size==4) {
        TarnishCanopyOrientation.prepare(Path.of(args[1]),Path.of(args[2]),Path.of(args[3]));return
    }
    if(args.firstOrNull()=="apply-canopy-orientation" && args.size>=4) {
        TarnishCanopyOrientation.apply(Path.of(args[1]),Path.of(args[2]),args.drop(3).map { Path.of(it) });return
    }
    if (args.firstOrNull() == "prepare-spirit-trees" && args.size == 4) {
        TarnishSpiritTrees.prepare(Path.of(args[1]), Path.of(args[2]), Path.of(args[3])); return
    }
    if (args.firstOrNull() == "apply-spirit-trees" && args.size >= 4) {
        TarnishSpiritTrees.apply(Path.of(args[1]), Path.of(args[2]), args.drop(3).map { Path.of(it) }); return
    }
    if (args.firstOrNull() == "prepare-garden-room-order" && args.size == 3) {
        TarnishGardenRoomOrder.prepare(Path.of(args[1]),Path.of(args[2])); return
    }
    if (args.firstOrNull() == "apply-garden-room-order" && args.size >= 4) {
        TarnishGardenRoomOrder.apply(Path.of(args[1]),Path.of(args[2]),args.drop(3).map { Path.of(it) }); return
    }
    if (args.firstOrNull() == "prepare-construction-cards" && args.size == 4) {
        TarnishConstructionCards.prepare(Path.of(args[1]), Path.of(args[2]), Path.of(args[3])); return
    }
    if (args.firstOrNull() == "apply-construction-cards" && args.size >= 4) {
        TarnishConstructionCards.apply(Path.of(args[1]), Path.of(args[2]), args.drop(3).map { Path.of(it) }); return
    }
    if (args.firstOrNull() == "prepare-garden-room-ui" && args.size == 3) {
        TarnishGardenRoomUi.prepare(Path.of(args[1]), Path.of(args[2])); return
    }
    if (args.firstOrNull() == "apply-garden-room-ui" && args.size >= 4) {
        TarnishGardenRoomUi.apply(Path.of(args[1]), Path.of(args[2]), args.drop(3).map { Path.of(it) }); return
    }
    if (args.firstOrNull() == "prepare-fairy-ring" && args.size == 4) {
        TarnishFairyRing.prepare(Path.of(args[1]), Path.of(args[2]), Path.of(args[3])); return
    }
    if (args.firstOrNull() == "apply-fairy-ring" && args.size >= 4) {
        TarnishIncrementalImport.apply(Path.of(args[1]), Path.of(args[2]), args.drop(3).map { Path.of(it) }, "garden-fairy-ring-native-readback"); return
    }
    if (args.firstOrNull() == "prepare-house-materials" && args.size == 4) {
        TarnishHouseMaterials.prepare(Path.of(args[1]), Path.of(args[2]), Path.of(args[3])); return
    }
    if (args.firstOrNull() == "apply-house-materials" && args.size >= 4) {
        TarnishHouseMaterials.apply(Path.of(args[1]), Path.of(args[2]), args.drop(3).map { Path.of(it) }); return
    }
    if (args.firstOrNull() == "prepare-obelisk" && args.size == 4) {
        TarnishObelisk.prepare(Path.of(args[1]), Path.of(args[2]), Path.of(args[3])); return
    }
    if (args.firstOrNull() == "apply-obelisk" && args.size >= 4) {
        TarnishObelisk.apply(Path.of(args[1]), Path.of(args[2]), args.drop(3).map { Path.of(it) }); return
    }
    if (args.firstOrNull() == "render-stamina-icons" && args.size == 3) {
        StaminaIcons.render(Path.of(args[1]), Path.of(args[2])); return
    }
    if (args.firstOrNull() == "prepare-stamina" && args.size == 4) {
        TarnishStamina.prepare(Path.of(args[1]), Path.of(args[2]), Path.of(args[3])); return
    }
    if (args.firstOrNull() == "apply-stamina" && args.size == 4) {
        TarnishStamina.apply(Path.of(args[1]), Path.of(args[2]), Path.of(args[3])); return
    }
    if (args.firstOrNull() == "audit-materials" && args.size == 4) {
        TarnishMaterials.audit(Path.of(args[1]), Path.of(args[2]), Path.of(args[3])); return
    }
    if (args.firstOrNull() == "prepare-garden" && args.size == 4) {
        TarnishGardenImport.prepare(Path.of(args[1]), Path.of(args[2]), Path.of(args[3])); return
    }
    if (args.firstOrNull() == "apply-garden-diagnostic" && args.size == 3) {
        TarnishGardenImport.apply(Path.of(args[1]), Path.of(args[2])); return
    }
    require(args.size == 3 && args[0] == "inventory-garden") { "Usage: tarnish inventory-garden <source-cache> <new-report.json>" }
    TarnishGarden.inventory(Path.of(args[1]), Path.of(args[2]))
}
