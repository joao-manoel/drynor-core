package org.drynor.tools.rs3port

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import world.gregs.voidps.cache.source.codec.Gltf
import java.lang.Float.float16ToFloat
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.roundToInt

class Rs3Submesh(val flags: Int, val priority: Int, val material: Int, val indices: IntArray)

class Rs3Point(val x: Int, val y: Int, val z: Int, val label: Int)

class Rs3Emitter(val producer: Int, val points: List<Rs3Point>)

class Rs3Effector(val effector: Int, val point: Rs3Point)

class Rs3Billboard(
    val priority: Int,
    val material: Int,
    val x: Float,
    val y: Float,
    val z: Float,
    val size2d: Int,
    val size3d: Int,
    val colour: Int,
    val opacity: Int,
    val faceLabel: Int,
    val vertexLabel: Int,
    val depth: Int,
    val distance: Int
)

class Rs3Model(val id: Int, private val root: JsonObject, private val binary: ByteArray) {

    private val rs: JsonObject = root["nodes"]?.jsonArray?.firstOrNull()?.jsonObject
        ?.get("extras")?.jsonObject?.get("rs")?.jsonObject
        ?: throw IllegalArgumentException("RS3 model $id carries no rs extras.")

    val positions: IntArray = run {
        val raw = stream("positions") ?: IntArray(0)
        IntArray(raw.size) { if (it % 3 == 1) -raw[it] else raw[it] }
    }

    val vertexCount: Int = positions.size / 3

    val colours: IntArray? = stream("streamB")

    val vertexSkins: IntArray? = stream("streamA")

    val faceSkins: IntArray? = stream("streamC")

    val opacities: IntArray? = stream("byteStream")

    val normals: IntArray? = stream("normals")

    val uvs: FloatArray? = stream("textures")?.let { raw -> FloatArray(raw.size) { float16ToFloat(raw[it].toShort()) } }

    val submeshes: List<Rs3Submesh> = rs["submeshes"]?.jsonArray.orEmpty().map { entry ->
        val parts = entry.jsonArray
        Rs3Submesh(
            flags = parts[0].jsonPrimitive.int,
            priority = parts[1].jsonPrimitive.int,
            material = parts[2].jsonPrimitive.int - 1,
            indices = accessor(parts[4].jsonPrimitive.int)
        )
    }

    val faceCount: Int = submeshes.sumOf { it.indices.size } / 3

    val emitters: List<Rs3Emitter> = rs["attachmentsB"]?.jsonArray.orEmpty().map { entry ->
        val emitter = entry.jsonObject
        Rs3Emitter(emitter.int("id"), emitter["points"]!!.jsonArray.map { point(it) })
    }

    val effectors: List<Rs3Effector> = rs["attachmentsC"]?.jsonArray.orEmpty().map { entry ->
        val effector = entry.jsonObject
        Rs3Effector(effector.int("id"), point(effector["point"]!!))
    }

    val billboards: List<Rs3Billboard> = rs["attachmentsA"]?.jsonArray.orEmpty().flatMap { entry ->
        val group = entry.jsonObject
        group["records"]!!.jsonArray.map { element ->
            val record = element.jsonObject
            val ids = record["ids"]!!.jsonArray.map { it.jsonPrimitive.int }
            Rs3Billboard(
                priority = group.int("a"),
                material = group.int("b") - 1,
                x = record.float("x"),
                y = -record.float("y"),
                z = record.float("z"),
                size2d = record.float("d").roundToInt(),
                size3d = record.float("e").roundToInt(),
                colour = record.int("f"),
                opacity = record.int("g"),
                faceLabel = ids[0],
                vertexLabel = ids[1],
                depth = ids[3] and 0xff,
                distance = record.int("h")
            )
        }
    }

    fun vertexX(vertex: Int): Int = positions[vertex * 3]

    fun vertexY(vertex: Int): Int = positions[vertex * 3 + 1]

    fun vertexZ(vertex: Int): Int = positions[vertex * 3 + 2]

    private fun point(element: JsonElement): Rs3Point {
        val point = element.jsonObject
        return Rs3Point(
            point.float("x").roundToInt(),
            -point.float("y").roundToInt(),
            point.float("z").roundToInt(),
            point.int("a")
        )
    }

    private fun JsonObject.int(key: String): Int = this[key]!!.jsonPrimitive.int

    private fun JsonObject.float(key: String): Float = this[key]!!.jsonPrimitive.float

    private fun stream(name: String): IntArray? {
        val index = rs[name]?.jsonPrimitive?.int ?: return null
        return accessor(index)
    }

    private fun accessor(index: Int): IntArray {
        val type = root["accessors"]!!.jsonArray[index].jsonObject["type"]!!.jsonPrimitive.content
        return Gltf.accessor(root, binary, index, components(type))
    }

    private fun components(type: String): Int = when (type) {
        "SCALAR" -> 1
        "VEC2" -> 2
        "VEC3" -> 3
        "VEC4" -> 4
        else -> throw IllegalArgumentException("RS3 model $id has an accessor of type $type.")
    }

    companion object {

        fun path(tree: Path, id: Int): Path = tree.resolve("models_rt7").resolve("$id.glb")

        fun exists(tree: Path, id: Int): Boolean = Files.exists(path(tree, id))

        fun read(tree: Path, id: Int): Rs3Model {
            val bytes = Files.readAllBytes(path(tree, id))
            val chunks = Gltf.chunks(bytes)
            return Rs3Model(id, Gltf.root(chunks.first), chunks.second)
        }
    }
}
