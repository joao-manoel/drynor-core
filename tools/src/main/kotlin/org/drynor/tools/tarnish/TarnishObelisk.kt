package org.drynor.tools.tarnish

import kotlinx.serialization.json.*
import org.drynor.tools.osrs.InfernoAssetWriter
import org.drynor.tools.osrs.OsrsObjectDependencies
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.cache.FileCache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.ObjectDefinition
import world.gregs.voidps.cache.definition.decoder.ModelDecoder
import world.gregs.voidps.cache.definition.decoder.ObjectDecoder
import world.gregs.voidps.cache.definition.encoder.ModelEncoder
import world.gregs.voidps.cache.definition.encoder.ObjectEncoder
import java.nio.file.Files
import java.nio.file.Path

/** Bounded, untextured Superior Garden obelisk; no animation or morph substitution is permitted. */
object TarnishObelisk {
    private const val SOURCE_OBJECT = 31554
    private const val SOURCE_MODEL = 34164
    private const val TARGET_OBJECT = 120009
    private const val OBJECT_HASH = "55968b33e248cab5df6544cedf86c6b94f7026bd911cbc36865df4003e47f777"
    private const val MODEL_HASH = "0d049d8bf56306bbaf078d9a5ebf39efbc80d25d42e372f1042705c31b850645"

    fun prepare(source: Path, target: Path, output: Path) {
        require(!Files.exists(output)) { "Output exists: $output" }
        val assets = mutableListOf<InfernoAssetWriter.Asset>()
        var targetModel = -1
        TarnishCache(source).use { donor ->
            val rawObject = donor.objects()[SOURCE_OBJECT]
            check(TarnishGarden.sha(rawObject) == OBJECT_HASH) { "Obelisk source definition changed" }
            val dependencies = OsrsObjectDependencies.parse(rawObject)
            require(dependencies.name == "Obelisk" && dependencies.models == setOf(SOURCE_MODEL))
            require(dependencies.animations.isEmpty() && dependencies.morphs.isEmpty() && dependencies.textures.isEmpty()
                && dependencies.sounds.isEmpty() && dependencies.sprites.isEmpty() && dependencies.mapIcons.isEmpty()
                && dependencies.varbits.isEmpty() && dependencies.varps.isEmpty()) { "Obelisk dependency closure changed" }
            val rawModel = donor.model(SOURCE_MODEL)
            check(TarnishGarden.sha(rawModel) == MODEL_HASH) { "Obelisk source model changed" }
            FileCache(target.toRealPath().toString(), writable = false).use { baseline ->
                check(baseline.data(Index.OBJECTS, TARGET_OBJECT ushr 8, TARGET_OBJECT and 255) == null) { "Obelisk destination occupied: $TARGET_OBJECT" }
                targetModel = baseline.lastArchiveId(Index.MODELS) + 1
                check(baseline.data(Index.MODELS, targetModel) == null)
                val modelBytes = TarnishModels.nativeBytes(rawModel)
                val model = ModelDecoder.read(targetModel, modelBytes)
                require((model.faceTextures ?: shortArrayOf()).none { it.toInt() >= 0 }) { "Obelisk model requires materials" }
                check(ModelEncoder.encode(model).contentEquals(modelBytes))
                assets += InfernoAssetWriter.Asset(Index.MODELS, targetModel, 0, modelBytes)
                val definition = TarnishObjectConverter.convert(TARGET_OBJECT, rawObject, mapOf(SOURCE_MODEL to targetModel), emptyMap(), mapOf(SOURCE_OBJECT to TARGET_OBJECT))
                val encoded = BufferWriter(65536).apply { with(ObjectEncoder()) { encode(definition) } }.toArray()
                val decoded = ObjectDefinition(TARGET_OBJECT).also { ObjectDecoder().readLoop(it, BufferReader(encoded)) }
                check(decoded.name == definition.name && decoded.modelIds.contentDeepEquals(definition.modelIds))
                check(decoded.sizeX == definition.sizeX && decoded.sizeY == definition.sizeY && decoded.solid == definition.solid)
                check(decoded.options.contentEquals(definition.options))
                assets += InfernoAssetWriter.Asset(Index.OBJECTS, TARGET_OBJECT ushr 8, TARGET_OBJECT and 255, encoded)
            }
        }
        assets.forEach { asset ->
            val file = output.resolve("assets/${asset.index}/${asset.archive}/${asset.file}.bin")
            Files.createDirectories(file.parent)
            Files.write(file, asset.bytes)
        }
        val manifest = buildJsonObject {
            put("schemaVersion", 1); put("status", "obelisk-native-readback"); put("validated", false)
            put("source", source.toRealPath().toString()); put("target", target.toRealPath().toString())
            put("sourceObjectIds", buildJsonObject { put(SOURCE_OBJECT.toString(), TARGET_OBJECT) })
            put("sourceModelIds", buildJsonObject { put(SOURCE_MODEL.toString(), targetModel) })
            put("sourceObjectSha256", OBJECT_HASH); put("sourceModelSha256", MODEL_HASH)
            put("omissions", JsonArray(emptyList()))
            put("remaining", "Client visual, collision and gameplay interaction validation")
            put("assets", JsonArray(assets.map { asset -> buildJsonObject {
                put("index", asset.index); put("archive", asset.archive); put("file", asset.file); put("sha256", TarnishGarden.sha(asset.bytes))
            } }))
        }
        Files.writeString(output.resolve("manifest.json"), Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), manifest))
        println("Prepared obelisk $SOURCE_OBJECT -> $TARGET_OBJECT, model $SOURCE_MODEL -> $targetModel; no cache changed.")
    }

    /** Caller must stop consumers and supply every applied package since the original rollback. */
    fun apply(output: Path, backup: Path, priorPackages: List<Path>) =
        TarnishIncrementalImport.apply(output, backup, priorPackages, "obelisk-native-readback")
}

