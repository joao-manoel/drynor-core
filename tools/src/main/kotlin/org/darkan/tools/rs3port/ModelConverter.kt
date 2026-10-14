package org.darkan.tools.rs3port

import world.gregs.voidps.cache.definition.data.BillboardData
import world.gregs.voidps.cache.definition.data.ModelDefinition
import world.gregs.voidps.cache.definition.data.ParticleEffectorAttachment
import world.gregs.voidps.cache.definition.data.ParticleEmitterAttachment
import world.gregs.voidps.cache.definition.decoder.ModelDecoder
import kotlin.math.abs

class ModelTables(
    val material: (Int) -> Int,
    val template: (Boolean, Int) -> Projection?,
    val producer: (Int) -> Int,
    val effector: (Int) -> Int,
    val billboard: (Int, Int, Int) -> Int,
    val shading: Boolean
) {
    companion object {

        fun of(crosswalk: Crosswalk): ModelTables = ModelTables(
            material = crosswalk::material,
            template = crosswalk::projectionOrNull,
            producer = { throw IllegalArgumentException("RS3 producer $it has no 727 id here.") },
            effector = { throw IllegalArgumentException("RS3 effector $it has no 727 id here.") },
            billboard = { material, _, _ -> throw IllegalArgumentException("No billboard table for material $material.") },
            shading = false
        )
    }
}

object ModelConverter {

    private const val VERSION = 15

    private const val UNIFORM_PRIORITY_DEFAULT = 4

    private const val OPAQUE = 255

    private const val UNSHADED_SUBMESH = 0x30

    private const val MAX_TEXTURE_POINTERS = 255

    private const val CENTROID_TOLERANCE = 0.01f

    fun convert(rs3: Rs3Model, id: Int, worn: Boolean, crosswalk: Crosswalk): ModelDefinition =
        convert(rs3, id, worn, ModelTables.of(crosswalk))

    fun convert(rs3: Rs3Model, id: Int, worn: Boolean, tables: ModelTables): ModelDefinition {
        val skins = rs3.vertexSkins
        val welded = HashMap<Long, Int>(rs3.vertexCount * 2)
        val order = ArrayList<Int>(rs3.vertexCount)
        val index = IntArray(rs3.vertexCount)
        for (vertex in 0 until rs3.vertexCount) {
            val key = weld(rs3.vertexX(vertex), rs3.vertexY(vertex), rs3.vertexZ(vertex), skins?.get(vertex) ?: 0)
            index[vertex] = welded.getOrPut(key) {
                order.add(vertex)
                order.size - 1
            }
        }

        val faces = rs3.faceCount
        val faceA = IntArray(faces)
        val faceB = IntArray(faces)
        val faceC = IntArray(faces)
        val corners = IntArray(faces * 3)
        val colours = ShortArray(faces)
        val priorities = ByteArray(faces)
        val materials = ShortArray(faces)
        val faceSkins = IntArray(faces)
        val alphas = ByteArray(faces)
        val shading = ByteArray(faces)
        var face = 0
        for (submesh in rs3.submeshes) {
            val material = if (submesh.material < 0) -1 else tables.material(submesh.material)
            for (corner in submesh.indices.indices step 3) {
                val a = submesh.indices[corner]
                val b = submesh.indices[corner + 1]
                val c = submesh.indices[corner + 2]
                faceA[face] = index[a]
                faceB[face] = index[c]
                faceC[face] = index[b]
                corners[face * 3] = a
                corners[face * 3 + 1] = b
                corners[face * 3 + 2] = c
                colours[face] = (rs3.colours?.get(a) ?: 0).toShort()
                priorities[face] = submesh.priority.toByte()
                materials[face] = material.toShort()
                faceSkins[face] = rs3.faceSkins?.get(a) ?: 0
                alphas[face] = (OPAQUE - (rs3.opacities?.get(a) ?: OPAQUE)).toByte()
                if (tables.shading && flat(rs3, submesh.flags, a, b, c)) {
                    shading[face] = 1
                }
                face++
            }
        }

        val pointers = pointers(rs3, materials, corners, order.size, worn, tables)
        val textured = materials.any { it >= 0 }

        val extra = pointers.corners
        val vertices = order.size + extra.size / 3
        val vertexX = IntArray(vertices)
        val vertexY = IntArray(vertices)
        val vertexZ = IntArray(vertices)
        val vertexSkins = IntArray(vertices)
        for (vertex in order.indices) {
            val source = order[vertex]
            vertexX[vertex] = rs3.vertexX(source)
            vertexY[vertex] = rs3.vertexY(source)
            vertexZ[vertex] = rs3.vertexZ(source)
            vertexSkins[vertex] = skins?.get(source) ?: 0
        }
        for (corner in extra.indices step 3) {
            val vertex = order.size + corner / 3
            vertexX[vertex] = extra[corner]
            vertexY[vertex] = extra[corner + 1]
            vertexZ[vertex] = extra[corner + 2]
        }

        val uniform = priorities.all { it == priorities.firstOrNull() }
        val modelPriority = when {
            !uniform -> ModelDecoder.PER_FACE_PRIORITY
            priorities.isEmpty() -> 0
            priorities[0].toInt() == 0 -> UNIFORM_PRIORITY_DEFAULT
            else -> priorities[0].toInt()
        }

        val lookup = FaceLookup(rs3, corners, faces)
        val emitters = rs3.emitters.map { emitter ->
            ParticleEmitterAttachment(tables.producer(emitter.producer), lookup.emitter(emitter))
        }
        val effectors = rs3.effectors.map { effector ->
            ParticleEffectorAttachment(tables.effector(effector.effector), vertex(rs3, welded, effector.point))
        }
        val billboards = rs3.billboards.map { billboard ->
            BillboardData(
                tables.billboard(billboard.material, billboard.size2d, billboard.size3d),
                lookup.billboard(billboard, colours),
                billboard.depth,
                billboard.distance
            )
        }

        val translucent = alphas.any { it.toInt() != 0 }
        val shaded = shading.any { it.toInt() != 0 }
        var footer = ModelDecoder.FLAG_VERSION
        if (shaded) {
            footer = footer or ModelDecoder.FLAG_FACE_TYPES
        }
        if (emitters.isNotEmpty() || effectors.isNotEmpty()) {
            footer = footer or ModelDecoder.FLAG_PARTICLES
        }
        if (billboards.isNotEmpty()) {
            footer = footer or ModelDecoder.FLAG_BILLBOARDS
        }

        val count = pointers.mappings.size
        return ModelDefinition(
            id = id,
            version = VERSION,
            vertexCount = vertices,
            faceCount = faces,
            vertexX = vertexX,
            vertexY = vertexY,
            vertexZ = vertexZ,
            faceA = faceA,
            faceB = faceB,
            faceC = faceC,
            faceColors = colours,
            faceAlphas = if (translucent) alphas else null,
            faceRenderTypes = if (shaded) shading else null,
            facePriorities = if (uniform) null else priorities,
            faceTextures = if (textured) materials else null,
            faceTexturePointers = if (textured) pointers.faces else null,
            textureTriangleCount = count,
            texturesMapping = if (count == 0) null else pointers.mappings,
            textureFaceX = if (count == 0) null else pointers.triangleX,
            textureFaceY = if (count == 0) null else pointers.triangleY,
            textureFaceZ = if (count == 0) null else pointers.triangleZ,
            texturesScaleX = if (pointers.cylindrical == 0) null else pointers.scaleX,
            texturesScaleY = if (pointers.cylindrical == 0) null else pointers.scaleY,
            texturesScaleZ = if (pointers.cylindrical == 0) null else pointers.scaleZ,
            texturesRotation = if (pointers.cylindrical == 0) null else pointers.rotations,
            texturesDirection = if (pointers.cylindrical == 0) null else pointers.directions,
            texturesSpeed = if (pointers.cylindrical == 0) null else pointers.speeds,
            texturesUtrans = null,
            texturesVtrans = null,
            textureCoordU = null,
            textureCoordV = null,
            vertexNormalsX = null,
            vertexNormalsY = null,
            vertexNormalsZ = null,
            billboards = if (billboards.isEmpty()) null else billboards.toTypedArray(),
            newFormat = true,
            footerFlags = footer,
            modelPriority = modelPriority,
            alphaFlag = if (translucent) 1 else 0,
            faceSkinFlag = if (rs3.faceSkins == null) 0 else 1,
            textureFlag = if (textured) 1 else 0,
            vertexSkinFlag = if (skins == null) 0 else 1,
            faceSkins = if (rs3.faceSkins == null) null else faceSkins,
            vertexSkins = if (skins == null) null else vertexSkins,
            particleEmitters = if (emitters.isEmpty() && effectors.isEmpty()) null else emitters.toTypedArray(),
            particleEffectors = if (emitters.isEmpty() && effectors.isEmpty()) null else effectors.toTypedArray()
        )
    }

    fun flat(rs3: Rs3Model, flags: Int, a: Int, b: Int, c: Int): Boolean {
        val normals = rs3.normals ?: return false
        if (flags and UNSHADED_SUBMESH != 0) {
            return false
        }
        for (axis in 0 until 3) {
            val value = normals[a * 3 + axis]
            if (normals[b * 3 + axis] != value || normals[c * 3 + axis] != value) {
                return false
            }
        }
        return true
    }

    private fun weld(x: Int, y: Int, z: Int, skin: Int): Long {
        val packed = ((x + 0x8000).toLong() shl 48) or ((y + 0x8000).toLong() shl 32)
        return packed or ((z + 0x8000).toLong() shl 16) or (skin.toLong() and 0xffff)
    }

    private fun vertex(rs3: Rs3Model, welded: Map<Long, Int>, point: Rs3Point): Int {
        welded[weld(point.x, point.y, point.z, point.label)]?.let { return it }
        val skins = rs3.vertexSkins
        for (vertex in 0 until rs3.vertexCount) {
            if (rs3.vertexX(vertex) == point.x && rs3.vertexY(vertex) == point.y && rs3.vertexZ(vertex) == point.z) {
                return welded.getValue(weld(point.x, point.y, point.z, skins?.get(vertex) ?: 0))
            }
        }
        throw IllegalStateException("RS3 model ${rs3.id} has no vertex at the effector ${point.x}, ${point.y}, ${point.z}.")
    }

    private class FaceLookup(private val rs3: Rs3Model, private val corners: IntArray, faces: Int) {

        private val byPositions = HashMap<List<Int>, MutableList<Int>>(faces * 2)

        private val used = HashSet<Int>()

        init {
            for (face in 0 until faces) {
                byPositions.getOrPut(key(corners[face * 3], corners[face * 3 + 1], corners[face * 3 + 2])) { ArrayList(1) }
                    .add(face)
            }
        }

        fun emitter(emitter: Rs3Emitter): Int {
            val key = sorted(emitter.points.map { listOf(it.x, it.y, it.z) })
            val faces = byPositions[key]
                ?: throw IllegalStateException("RS3 model ${rs3.id} has no face under producer ${emitter.producer}.")
            return take(faces)
        }

        fun billboard(billboard: Rs3Billboard, colours: ShortArray): Int {
            val candidates = ArrayList<Int>()
            for (face in 0 until corners.size / 3) {
                if (centred(face, billboard)) {
                    candidates.add(face)
                }
            }
            val coloured = candidates.filter { (colours[it].toInt() and 0xffff) == billboard.colour }
            val faces = coloured.ifEmpty { candidates }
            check(faces.isNotEmpty()) {
                "RS3 model ${rs3.id} has no face centred on the billboard at ${billboard.x}, ${billboard.y}, ${billboard.z}."
            }
            return take(faces)
        }

        private fun take(faces: List<Int>): Int {
            val face = faces.firstOrNull { it !in used } ?: faces.first()
            used.add(face)
            return face
        }

        private fun centred(face: Int, billboard: Rs3Billboard): Boolean {
            var x = 0f
            var y = 0f
            var z = 0f
            for (corner in 0 until 3) {
                val vertex = corners[face * 3 + corner]
                x += rs3.vertexX(vertex)
                y += rs3.vertexY(vertex)
                z += rs3.vertexZ(vertex)
            }
            return abs(x / 3 - billboard.x) < CENTROID_TOLERANCE &&
                abs(y / 3 - billboard.y) < CENTROID_TOLERANCE &&
                abs(z / 3 - billboard.z) < CENTROID_TOLERANCE
        }

        private fun key(a: Int, b: Int, c: Int): List<Int> = sorted(
            listOf(a, b, c).map { listOf(rs3.vertexX(it), rs3.vertexY(it), rs3.vertexZ(it)) }
        )

        private fun sorted(points: List<List<Int>>): List<Int> =
            points.sortedWith(compareBy({ it[0] }, { it[1] }, { it[2] })).flatten()
    }

    private class Pointers(
        val faces: ByteArray,
        val mappings: ByteArray,
        val triangleX: ShortArray,
        val triangleY: ShortArray,
        val triangleZ: ShortArray,
        val scaleX: IntArray,
        val scaleY: IntArray,
        val scaleZ: IntArray,
        val rotations: ByteArray,
        val directions: ByteArray,
        val speeds: IntArray,
        val corners: IntArray,
        val cylindrical: Int
    )

    private fun pointers(
        rs3: Rs3Model,
        materials: ShortArray,
        sources: IntArray,
        vertices: Int,
        worn: Boolean,
        tables: ModelTables
    ): Pointers {
        val order = LinkedHashSet<Int>()
        for (material in materials) {
            if (material >= 0) {
                order.add(material.toInt())
            }
        }
        val templates = HashMap<Int, Projection>()
        for (material in order) {
            tables.template(worn, material)?.let { templates[material] = it }
        }
        val sorted = order.filter { templates.containsKey(it) }.sortedBy { if (templates.getValue(it).mapping == 0) 1 else 0 }
        val solved = LinkedHashMap<List<Int>, Int>()
        val faces = ByteArray(materials.size) { -1 }
        for (face in materials.indices) {
            val material = materials[face].toInt()
            if (material < 0 || templates.containsKey(material)) {
                continue
            }
            val triangle = PlanarProjection.solve(rs3, sources[face * 3], sources[face * 3 + 1], sources[face * 3 + 2])
            faces[face] = (sorted.size + solved.getOrPut(triangle) { solved.size }).toByte()
        }
        val total = sorted.size + solved.size
        check(total <= MAX_TEXTURE_POINTERS) { "RS3 model ${rs3.id} needs $total texture triangles; a face holds a byte." }
        val forMaterial = HashMap<Int, Int>()
        val mappings = ByteArray(total)
        val triangleX = ShortArray(total)
        val triangleY = ShortArray(total)
        val triangleZ = ShortArray(total)
        val cylindrical = sorted.count { templates.getValue(it).mapping != 0 }
        val scaleX = IntArray(cylindrical)
        val scaleY = IntArray(cylindrical)
        val scaleZ = IntArray(cylindrical)
        val rotations = ByteArray(cylindrical)
        val directions = ByteArray(cylindrical)
        val speeds = IntArray(cylindrical)
        val corners = ArrayList<Int>()
        for ((pointer, material) in sorted.withIndex()) {
            forMaterial[material] = pointer
            val template = templates.getValue(material)
            mappings[pointer] = template.mapping.toByte()
            if (template.mapping == 0) {
                planar(pointer, vertices, corners, template.corners, triangleX, triangleY, triangleZ)
            } else {
                triangleX[pointer] = template.triangle[0].toShort()
                triangleY[pointer] = template.triangle[1].toShort()
                triangleZ[pointer] = template.triangle[2].toShort()
                scaleX[pointer] = template.scale[0]
                scaleY[pointer] = template.scale[1]
                scaleZ[pointer] = template.scale[2]
                rotations[pointer] = template.rotation.toByte()
                directions[pointer] = template.direction.toByte()
                speeds[pointer] = template.speed
            }
        }
        for ((triangle, position) in solved) {
            planar(sorted.size + position, vertices, corners, triangle.toIntArray(), triangleX, triangleY, triangleZ)
        }
        for (face in materials.indices) {
            val pointer = forMaterial[materials[face].toInt()] ?: continue
            faces[face] = pointer.toByte()
        }
        return Pointers(
            faces, mappings, triangleX, triangleY, triangleZ,
            scaleX, scaleY, scaleZ, rotations, directions, speeds,
            corners.toIntArray(), cylindrical
        )
    }

    private fun planar(
        pointer: Int,
        vertices: Int,
        corners: MutableList<Int>,
        triangle: IntArray,
        triangleX: ShortArray,
        triangleY: ShortArray,
        triangleZ: ShortArray
    ) {
        val corner = vertices + corners.size / 3
        triangleX[pointer] = corner.toShort()
        triangleY[pointer] = (corner + 1).toShort()
        triangleZ[pointer] = (corner + 2).toShort()
        triangle.forEach { corners.add(it) }
    }
}
