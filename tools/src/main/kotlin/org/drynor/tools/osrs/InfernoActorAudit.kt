package org.drynor.tools.osrs

import kotlinx.serialization.json.*
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.FileCache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.Config
import world.gregs.voidps.cache.definition.data.*
import world.gregs.voidps.cache.definition.decoder.*
import world.gregs.voidps.cache.definition.encoder.*
import java.nio.file.Files
import java.nio.file.Path

/** Real-cache conversion audit. Partial results are diagnostics, never an installable cache. */
object InfernoActorAudit {
    fun build(sourcePath: Path, targetPath: Path, output: Path) {
        require(!Files.exists(output))
        FileCache(sourcePath.toString(), writable = false).use { source ->
            FileCache(targetPath.toString(), writable = false).use { target ->
                val beforeSource = InfernoDependencyPlan.snapshot(source)
                val beforeTarget = InfernoDependencyPlan.snapshot(target)
                val plan = InfernoDependencyPlan.inspect(source, target, InfernoInventory.inspect(source, target, sourcePath, targetPath))
                fun mapping(name: String) = plan["proposedIds"]!!.jsonObject[name]!!.jsonArray.associate {
                    it.jsonObject["source"]!!.jsonPrimitive.int to it.jsonObject["target"]!!.jsonPrimitive.int
                }
                Files.createDirectories(output)
                val results = mutableListOf<JsonObject>()
                fun attempt(kind: String, id: Int, block: () -> Unit): Boolean {
                    var failure: Exception? = null
                    try { block() } catch (error: Exception) { failure = error }
                    results.add(buildJsonObject {
                        put("kind", kind); put("sourceId", id)
                        put("status", if (failure == null) "converted-readback-verified" else "blocked")
                        failure?.let { put("reason", it.message ?: it.javaClass.simpleName) }
                    })
                    return failure == null
                }
                val baseMap = mapping("bases")
                val frameMap = mapping("frameSets")
                val animationMap = mapping("animations")
                val bases = mutableMapOf<Int, AnimationBaseDefinition>()
                for ((id, destination) in baseMap.toSortedMap()) attempt("base", id) {
                    val base = OsrsAnimationConverter.convertBase(destination, requireNotNull(source.data(Index.ANIMATION_SKELETONS, id)))
                    val bytes = encode { with(AnimationBaseEncoder()) { it.encode(base) } }
                    val decoded = AnimationBaseDefinition(destination)
                    AnimationBaseDecoder().readLoop(decoded, BufferReader(bytes))
                    check(encode { with(AnimationBaseEncoder()) { it.encode(decoded) } }.contentEquals(bytes))
                    write(output, "bases/$destination.bin", bytes)
                    bases[id] = base
                }
                val validFrames = mutableSetOf<Long>()
                for (record in plan["frames"]!!.jsonArray) {
                    val frame = record.jsonObject
                    val raw = frame["rawPackedFrame"]!!.jsonPrimitive.long
                    attempt("frame", raw.toInt()) {
                        val archive = frame["sourceArchive"]!!.jsonPrimitive.int
                        val file = frame["sourceFile"]!!.jsonPrimitive.int
                        val base = bases.getValue(frame["sourceBaseId"]!!.jsonPrimitive.int)
                        val converted = OsrsAnimationConverter.convertFrame((frameMap.getValue(archive) shl 16) or file,
                            requireNotNull(source.data(Index.ANIMATION_FRAMES, archive, file)), base)
                        val bytes = encode { with(AnimationFrameEncoder()) { it.encode(converted) } }
                        val decoded = AnimationFrameDefinition(converted.id)
                        AnimationFrameDecoder().readLoop(decoded, BufferReader(bytes))
                        check(encode { with(AnimationFrameEncoder()) { it.encode(decoded) } }.contentEquals(bytes))
                        write(output, "frames/${frameMap.getValue(archive)}/$file.bin", bytes)
                        validFrames.add(raw)
                    }
                }
                val validAnimations = mutableMapOf<Int, Int>()
                for ((id, destination) in animationMap.toSortedMap()) attempt("sequence", id) {
                    val data = requireNotNull(source.data(Index.ANIMATIONS, id ushr 7, id and 127))
                    val dependencies = OsrsSequenceDependencies.parse(data)
                    require(dependencies.frameIds.all { (it.toLong() and 0xffffffffL) in validFrames }) { "Sequence references an unconverted frame" }
                    val sequence = OsrsAnimationConverter.convertSequence(destination, data, frameMap, previewWithoutSoundsAndEquipment = true)
                    val bytes = encode { with(AnimationEncoder()) { it.encode(sequence) } }
                    val decoded = AnimationDefinition(destination)
                    AnimationDecoder().readLoop(decoded, BufferReader(bytes))
                    check(decoded.frames.contentEquals(sequence.frames) && decoded.durations.contentEquals(sequence.durations))
                    write(output, "sequences/$destination.bin", bytes)
                    validAnimations[id] = destination
                }
                for ((id, destination) in mapping("spots")) attempt("spot", id) {
                    val raw = OsrsSpotConverter.parse(id, requireNotNull(source.data(Index.SPOTANIMS, id ushr 8, id and 255)))
                    val spot = OsrsSpotConverter.convert(destination, raw, mapping("models"), validAnimations)
                    val bytes = encode { with(SpotAnimEncoder()) { it.encode(spot) } }
                    val decoded = SpotAnimDefinition(destination)
                    SpotAnimDecoder().readLoop(decoded, BufferReader(bytes))
                    check(decoded.modelId == spot.modelId && decoded.animationId == spot.animationId)
                    write(output, "spots/$destination.bin", bytes)
                }
                val occupiedBas = target.files(Index.CONFIGS, Config.RENDER_ANIMATIONS).toMutableSet()
                var nextBas = 0
                for ((id, destination) in mapping("npcs").toSortedMap()) attempt("npc", id) {
                    while (nextBas in occupiedBas) nextBas++
                    val converted = OsrsNpcConverter.convert(destination, requireNotNull(source.data(Index.NPCS, id ushr 7, id and 127)), mapping("models"), validAnimations, nextBas)
                    val bytes = encode { with(NPCEncoder()) { it.encode(converted.npc) } }
                    val npc = NPCDefinition(destination)
                    NPCDecoder().readLoop(npc, BufferReader(bytes))
                    check(npc.modelIds.contentEquals(converted.npc.modelIds) && npc.renderEmote == nextBas)
                    val basBytes = encode { with(BASEncoder()) { it.encode(converted.bas) } }
                    val bas = BASDefinition(nextBas)
                    BASDecoder().readLoop(bas, BufferReader(basBytes))
                    check(bas.standAnimation == converted.bas.standAnimation && bas.walkAnimation == converted.bas.walkAnimation)
                    write(output, "npcs/$destination.bin", bytes)
                    write(output, "bas/$nextBas.bin", basBytes)
                    occupiedBas.add(nextBas)
                }
                check(beforeSource == InfernoDependencyPlan.snapshot(source) && beforeTarget == InfernoDependencyPlan.snapshot(target))
                val report = buildJsonObject {
                    put("status", "actor-conversion-audit"); put("installable", false)
                    put("sourceCache", sourcePath.toString()); put("baselineCache", targetPath.toString())
                    put("writesCache", false); put("renderingValidated", false)
                    put("policy", "Binary diagnostics only; sequences omit sounds/equipment, NPCs omit preview-only icons. Model/material dependencies and gameplay remain separate gates.")
                    put("results", JsonArray(results))
                    put("counts", buildJsonObject { for ((kind, records) in results.groupBy { it["kind"]!!.jsonPrimitive.content }) {
                        put(kind, buildJsonObject { put("converted", records.count { it["status"]!!.jsonPrimitive.content == "converted-readback-verified" }); put("blocked", records.count { it["status"]!!.jsonPrimitive.content == "blocked" }) })
                    } })
                }
                Files.writeString(output.resolve("actor-audit.json"), Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), report))
                println("Inferno actor conversion audit: $output")
            }
        }
    }

    private fun encode(block: (Writer) -> Unit) = BufferWriter(1 shl 20).also(block).toArray()
    private fun write(root: Path, name: String, bytes: ByteArray) {
        val path = root.resolve(name)
        Files.createDirectories(path.parent)
        Files.write(path, bytes)
    }
}
