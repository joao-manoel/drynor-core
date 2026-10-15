package org.drynor.tools.rs3port

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import world.gregs.voidps.cache.definition.data.ModelDefinition
import world.gregs.voidps.cache.source.codec.ModelGltf
import java.nio.file.Files
import java.nio.file.Path

class Projection(
    val mapping: Int,
    val triangle: IntArray,
    val scale: IntArray,
    val rotation: Int,
    val direction: Int,
    val speed: Int,
    val corners: IntArray
) {

    fun key(): String = listOf(
        mapping,
        triangle.joinToString("/"),
        scale.joinToString("/"),
        rotation,
        direction,
        speed,
        corners.joinToString("/")
    ).joinToString(",")

    fun json(): JsonObject = JsonObject(
        buildMap {
            put("mapping", JsonPrimitive(mapping))
            if (mapping == 0) {
                put("corners", ints(corners))
            } else {
                put("triangle", ints(triangle))
                put("scale", ints(scale))
                put("rotation", JsonPrimitive(rotation))
                put("direction", JsonPrimitive(direction))
                put("speed", JsonPrimitive(speed))
            }
        }
    )

    companion object {

        fun of(element: JsonObject): Projection {
            val mapping = element["mapping"]!!.jsonPrimitive.int
            return Projection(
                mapping = mapping,
                triangle = array(element["triangle"]),
                scale = array(element["scale"]),
                rotation = element["rotation"]?.jsonPrimitive?.int ?: 0,
                direction = element["direction"]?.jsonPrimitive?.int ?: 0,
                speed = element["speed"]?.jsonPrimitive?.int ?: 0,
                corners = array(element["corners"])
            )
        }

        private fun array(element: JsonElement?): IntArray {
            val values = element?.jsonArray ?: return IntArray(0)
            return IntArray(values.size) { values[it].jsonPrimitive.int }
        }

        private fun ints(values: IntArray): JsonArray = JsonArray(values.map { JsonPrimitive(it) })
    }
}

class Crosswalk(
    val materials: Map<Int, Int>,
    val counts: Map<Int, Map<Int, Int>>,
    val overrides: Map<Int, Int>,
    val projections: Map<String, Projection>
) {

    fun material(rs3: Int): Int = materials[rs3]
        ?: throw IllegalArgumentException("RS3 material $rs3 has no 727 mapping; add it to the overrides file.")

    fun with(extra: Map<Int, Int>): Crosswalk = Crosswalk(materials + extra, counts, overrides + extra, projections)

    fun projectionOrNull(worn: Boolean, material: Int): Projection? =
        projections["${if (worn) "worn" else "inventory"}:$material"]

    fun write(path: Path, corpusModels: Int, corpusFaces: Int) {
        val table = materials.keys.sorted().associate { rs3 ->
            rs3.toString() to JsonObject(
                mapOf(
                    "material" to JsonPrimitive(materials.getValue(rs3)),
                    "source" to JsonPrimitive(if (overrides.containsKey(rs3)) "override" else "learned"),
                    "counts" to JsonObject(
                        counts[rs3].orEmpty().entries.sortedByDescending { it.value }
                            .associate { it.key.toString() to JsonPrimitive(it.value) }
                    )
                )
            )
        }
        val root = JsonObject(
            mapOf(
                "source" to JsonPrimitive(SOURCE),
                "corpus" to JsonObject(
                    mapOf(
                        "models" to JsonPrimitive(corpusModels),
                        "matchedFaces" to JsonPrimitive(corpusFaces)
                    )
                ),
                "materials" to JsonObject(table),
                "projections" to JsonObject(projections.toSortedMap().mapValues { it.value.json() })
            )
        )
        Files.createDirectories(path.parent)
        Files.writeString(path, json.encodeToString(JsonObject.serializer(), root) + "\n")
    }

    companion object {

        private const val SOURCE =
            "learned by matching the faces of every urn model present in both the 727 and the RS3 949 tree; " +
                "keyed by RS3 material id, which is the glb submesh material field minus one"

        private val json = Json { prettyPrint = true; prettyPrintIndent = "  " }

        fun read(path: Path): Crosswalk {
            val root = Json.parseToJsonElement(Files.readString(path)).jsonObject
            val table = root["materials"]!!.jsonObject
            val materials = table.mapKeys { it.key.toInt() }
                .mapValues { it.value.jsonObject["material"]!!.jsonPrimitive.int }
            val counts = table.mapKeys { it.key.toInt() }.mapValues { entry ->
                entry.value.jsonObject["counts"]!!.jsonObject
                    .mapKeys { it.key.toInt() }.mapValues { it.value.jsonPrimitive.int }
            }
            val projections = root["projections"]!!.jsonObject.mapValues { Projection.of(it.value.jsonObject) }
            return Crosswalk(materials, counts, emptyMap(), projections)
        }

        fun overrides(path: Path): Map<Int, Int> {
            if (!Files.exists(path)) {
                return emptyMap()
            }
            val root = Json.parseToJsonElement(Files.readString(path)).jsonObject
            val table = root["materials"]?.jsonObject ?: return emptyMap()
            return table.mapKeys { it.key.toInt() }
                .mapValues { it.value.jsonObject["material"]!!.jsonPrimitive.int }
        }
    }
}

class CorpusModel(val rs3Id: Int, val sourceId: Int, val worn: Boolean)

class CrosswalkLearner(private val rs3Tree: Path, private val sourceTree: Path) {

    var models: Int = 0
        private set

    var matched: Int = 0
        private set

    fun learn(corpus: List<CorpusModel>, overrides: Map<Int, Int>): Crosswalk {
        val counts = HashMap<Int, HashMap<Int, Int>>()
        val projections = HashMap<String, HashMap<String, Pair<Projection, Int>>>()
        for (entry in corpus) {
            val rs3 = Rs3Model.read(rs3Tree, entry.rs3Id)
            val target = SourceModels.read(sourceTree, entry.sourceId) ?: continue
            if (rs3.faceCount != target.faceCount) {
                continue
            }
            models++
            val pairs = FaceMatcher.match(rs3, target)
            for (submesh in rs3.submeshes.indices) {
                for (face in pairs.faces(submesh)) {
                    matched++
                    val material = target.faceTextures?.get(face.target)?.toInt() ?: -1
                    val rs3Material = rs3.submeshes[submesh].material
                    if (rs3Material >= 0) {
                        counts.getOrPut(rs3Material) { HashMap() }.merge(material, 1, Int::plus)
                    }
                    val projection = projectionOf(target, face.target) ?: continue
                    val scope = if (entry.worn) "worn" else "inventory"
                    val bucket = projections.getOrPut("$scope:$material") { HashMap() }
                    val existing = bucket[projection.key()]
                    bucket[projection.key()] = projection to (existing?.second ?: 0) + 1
                }
            }
        }
        val materials = HashMap<Int, Int>()
        for ((rs3Material, tally) in counts) {
            materials[rs3Material] = tally.maxByOrNull { it.value }!!.key
        }
        materials.putAll(overrides)
        val templates = projections.mapValues { entry -> entry.value.values.maxByOrNull { it.second }!!.first }
        return Crosswalk(materials, counts.mapValues { it.value.toMap() }, overrides, templates)
    }

    private fun projectionOf(model: ModelDefinition, face: Int): Projection? {
        val pointer = model.faceTexturePointers?.get(face)?.toInt() ?: return null
        if (pointer < 0) {
            return null
        }
        val mapping = model.texturesMapping?.get(pointer)?.toInt() ?: return null
        if (mapping == 0) {
            val corners = IntArray(9)
            val triangle = intArrayOf(
                model.textureFaceX!![pointer].toInt(),
                model.textureFaceY!![pointer].toInt(),
                model.textureFaceZ!![pointer].toInt()
            )
            for (corner in 0 until 3) {
                val vertex = triangle[corner]
                corners[corner * 3] = model.vertexX[vertex]
                corners[corner * 3 + 1] = model.vertexY[vertex]
                corners[corner * 3 + 2] = model.vertexZ[vertex]
            }
            return Projection(0, IntArray(0), IntArray(0), 0, 0, 0, corners)
        }
        return Projection(
            mapping = mapping,
            triangle = intArrayOf(
                model.textureFaceX!![pointer].toInt() and 0xffff,
                model.textureFaceY!![pointer].toInt() and 0xffff,
                model.textureFaceZ!![pointer].toInt() and 0xffff
            ),
            scale = intArrayOf(
                model.texturesScaleX!![pointer],
                model.texturesScaleY!![pointer],
                model.texturesScaleZ!![pointer]
            ),
            rotation = model.texturesRotation?.get(pointer)?.toInt() ?: 0,
            direction = model.texturesDirection?.get(pointer)?.toInt() ?: 0,
            speed = model.texturesSpeed?.get(pointer) ?: 0,
            corners = IntArray(0)
        )
    }
}

object SourceModels {

    fun path(tree: Path, id: Int): Path = tree.resolve("models").resolve("$id.glb")

    fun read(tree: Path, id: Int): ModelDefinition? {
        val file = path(tree, id)
        if (!Files.exists(file)) {
            return null
        }
        return ModelGltf.read(Files.readAllBytes(file), id)
    }
}
