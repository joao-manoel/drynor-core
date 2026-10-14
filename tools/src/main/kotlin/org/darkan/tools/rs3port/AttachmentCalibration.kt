package org.darkan.tools.rs3port

import world.gregs.voidps.cache.definition.data.ModelDefinition
import world.gregs.voidps.cache.source.codec.Gltf
import java.nio.file.Files
import java.nio.file.Path
import java.util.TreeMap
import kotlin.math.abs
import kotlin.math.max

object AttachmentCalibration {

    private val MARKERS = listOf("\"particleEmitters\"", "\"particleEffectors\"", "\"billboards\"")

    private const val UV_TOLERANCE = 1.0 / 64

    private class Counter {
        var hits = 0
        var total = 0

        fun add(hit: Boolean) {
            total++
            if (hit) {
                hits++
            }
        }

        override fun toString(): String = "$hits / $total ${percent(hits, total)}"
    }

    fun corpus(rs3Tree: Path, sourceTree: Path): List<Int> =
        Files.list(sourceTree.resolve("models")).use { stream -> stream.toList() }
            .mapNotNull { path -> path.fileName.toString().removeSuffix(".glb").toIntOrNull()?.let { it to path } }
            .filter { (id, path) -> Rs3Model.exists(rs3Tree, id) && attached(path) }
            .map { it.first }
            .sorted()

    private fun attached(path: Path): Boolean {
        val text = String(Gltf.chunks(Files.readAllBytes(path)).first, Charsets.UTF_8)
        return MARKERS.any { text.contains(it) }
    }

    fun run(rs3Tree: Path, sourceTree: Path, ids: List<Int>) {
        val billboardMaterials = MaterialCalibration.billboardVotes(rs3Tree, sourceTree)
            .mapValues { entry -> entry.value.maxByOrNull { it.value }!!.key }
        val table = BillboardTable.read(sourceTree)
        val tables = ModelTables(
            material = { -1 },
            template = { _, _ -> null },
            producer = { it },
            effector = { it },
            billboard = { material, size2d, size3d -> billboardMaterials[material]?.let { table.lowest(it, size2d, size3d) } ?: -1 },
            shading = true
        )
        val failures = TreeMap<String, Int>()
        var models = 0
        var counts = 0
        var emitterExtras = 0
        var billboardExtras = 0
        var emitterMisses = 0
        var billboardMisses = 0
        val emitterIds = Counter()
        val emitters = Counter()
        val effectors = Counter()
        val billboardIds = Counter()
        val billboardEquivalent = Counter()
        val billboardFaces = Counter()
        val billboardDepths = Counter()
        val billboardDistances = Counter()
        val attachmentAlphas = Counter()
        val attachmentPriorities = Counter()
        val alphas = Counter()
        val skins = Counter()
        val shading = Counter()
        val shadingExamples = TreeMap<String, Int>()
        val uvs = Counter()
        var solverError = 0.0
        var solverSum = 0.0
        var solverCorners = 0
        var degenerate = 0
        for (id in ids) {
            val shipped = SourceModels.read(sourceTree, id) ?: continue
            val rs3 = Rs3Model.read(rs3Tree, id)
            val converted = try {
                ModelConverter.convert(rs3, id, false, tables)
            } catch (e: IllegalStateException) {
                failures.merge(e.message?.substringAfter(" has ")?.take(48) ?: "unknown", 1, Int::plus)
                continue
            }
            models++
            val shippedEmitters = shipped.particleEmitters.orEmpty()
            val convertedEmitters = converted.particleEmitters.orEmpty()
            val shippedEffectors = shipped.particleEffectors.orEmpty()
            val convertedEffectors = converted.particleEffectors.orEmpty()
            val shippedBillboards = shipped.billboards.orEmpty()
            val convertedBillboards = converted.billboards.orEmpty()
            if (shippedEmitters.size == convertedEmitters.size && shippedEffectors.size == convertedEffectors.size &&
                shippedBillboards.size == convertedBillboards.size
            ) {
                counts++
            }
            val corners = corners(rs3)
            val geometry = corners.map { source ->
                source.map { listOf(rs3.vertexX(it), rs3.vertexY(it), rs3.vertexZ(it)) }.sortedWith(compareBy({ it[0] }, { it[1] }, { it[2] }))
            }.toHashSet()
            for (index in shippedEmitters.indices) {
                val theirs = shippedEmitters[index]
                val mine = convertedEmitters.getOrNull(index)
                if (mine == null) {
                    emitterExtras++
                    continue
                }
                emitterIds.add(mine.particleId == theirs.particleId)
                val same = key(converted, mine.appliesOnFace) == key(shipped, theirs.appliesOnFace)
                emitters.add(same)
                if (!same && corners(shipped, theirs.appliesOnFace) in geometry) {
                    emitterMisses++
                }
                attachmentAlphas.add(alpha(converted, mine.appliesOnFace) == alpha(shipped, theirs.appliesOnFace))
                attachmentPriorities.add(priority(converted, mine.appliesOnFace) == priority(shipped, theirs.appliesOnFace))
            }
            for (index in shippedEffectors.indices) {
                val mine = convertedEffectors.getOrNull(index)
                val theirs = shippedEffectors[index]
                effectors.add(mine != null && mine.effectorId == theirs.effectorId && position(converted, mine.appliesOnVertex) == position(shipped, theirs.appliesOnVertex))
            }
            for (index in shippedBillboards.indices) {
                val theirs = shippedBillboards[index]
                val mine = convertedBillboards.getOrNull(index)
                if (mine == null) {
                    billboardExtras++
                    continue
                }
                billboardIds.add(mine.billboardId == theirs.billboardId)
                billboardEquivalent.add(table.equivalent(mine.billboardId, theirs.billboardId))
                val same = key(converted, mine.appliesOnFace) == key(shipped, theirs.appliesOnFace)
                billboardFaces.add(same)
                if (!same && corners(shipped, theirs.appliesOnFace) in geometry) {
                    billboardMisses++
                }
                billboardDepths.add(mine.depth == theirs.depth)
                billboardDistances.add(mine.distance == theirs.distance)
                attachmentAlphas.add(alpha(converted, mine.appliesOnFace) == alpha(shipped, theirs.appliesOnFace))
                attachmentPriorities.add(priority(converted, mine.appliesOnFace) == priority(shipped, theirs.appliesOnFace))
            }
            for (face in FaceMatcher.match(rs3, shipped).all()) {
                alphas.add(alpha(converted, face.source) == alpha(shipped, face.target))
                skins.add(((converted.faceSkins?.get(face.source) ?: 0) and 0xff) == (shipped.faceSkins?.get(face.target) ?: 0))
                val mine = converted.faceRenderTypes?.get(face.source)?.toInt() ?: 0
                val theirs = shipped.faceRenderTypes?.get(face.target)?.toInt() ?: 0
                shading.add(mine == theirs)
                if (mine != theirs) {
                    shadingExamples.merge("shipped $theirs converted $mine submesh flags ${rs3.submeshes[face.submesh].flags}", 1, Int::plus)
                }
                val pointer = shipped.faceTexturePointers?.get(face.target)?.toInt() ?: -1
                val uv = rs3.uvs
                if (pointer < 0 || uv == null || (shipped.texturesMapping?.get(pointer)?.toInt() ?: -1) != 0) {
                    continue
                }
                val p = vertex(shipped, shipped.textureFaceX!![pointer].toInt())
                val m = vertex(shipped, shipped.textureFaceY!![pointer].toInt())
                val n = vertex(shipped, shipped.textureFaceZ!![pointer].toInt())
                val source = corners[face.source]
                for (corner in source) {
                    val at = intArrayOf(rs3.vertexX(corner), rs3.vertexY(corner), rs3.vertexZ(corner))
                    val theirUv = PlanarProjection.uv(p, m, n, at)
                    uvs.add(abs(theirUv[0] - uv[corner * 2]) <= UV_TOLERANCE && abs(theirUv[1] - uv[corner * 2 + 1]) <= UV_TOLERANCE)
                }
                val solved = try {
                    PlanarProjection.solve(rs3, source[0], source[1], source[2])
                } catch (e: IllegalStateException) {
                    degenerate++
                    continue
                }
                val sp = solved.subList(0, 3).toIntArray()
                val sm = solved.subList(3, 6).toIntArray()
                val sn = solved.subList(6, 9).toIntArray()
                for (corner in source) {
                    val mineUv = PlanarProjection.uv(sp, sm, sn, intArrayOf(rs3.vertexX(corner), rs3.vertexY(corner), rs3.vertexZ(corner)))
                    val error = max(abs(mineUv[0] - uv[corner * 2]), abs(mineUv[1] - uv[corner * 2 + 1]))
                    solverError = max(solverError, error)
                    solverSum += error
                    solverCorners++
                }
            }
        }
        println("models with attachments in both trees ${ids.size}, converted $models, refused ${ids.size - models}")
        failures.forEach { (reason, count) -> println("  refused $count: ...has $reason") }
        println("attachment counts equal on $counts of $models models")
        println("emitters 727 holds past RS3's last: $emitterExtras, billboards: $billboardExtras")
        println("emitters producer id           $emitterIds")
        println("emitters face                  $emitters, of the misses $emitterMisses have the shipped face in the RS3 geometry")
        println("effectors (effector and vertex) $effectors")
        println("billboards id                  $billboardIds")
        println("billboards id or same config   $billboardEquivalent")
        println("billboards face                $billboardFaces, of the misses $billboardMisses have the shipped face in the RS3 geometry")
        println("billboards depth               $billboardDepths")
        println("billboards distance            $billboardDistances")
        println("attachment faces alpha         $attachmentAlphas")
        println("attachment faces priority      $attachmentPriorities")
        println("all matched faces alpha        $alphas")
        println("all matched faces face skin    $skins")
        println("all matched faces render type  $shading")
        shadingExamples.entries.sortedByDescending { it.value }.take(12).forEach { println("    ${it.value} ${it.key}") }
        println("planar uv, shipped triangle vs RS3 uv within 1/64: $uvs")
        println("planar uv, solved triangle vs RS3 uv: max error %.5f, mean %.6f over $solverCorners corners, degenerate faces $degenerate"
            .format(solverError, if (solverCorners == 0) 0.0 else solverSum / solverCorners))
    }

    private fun corners(rs3: Rs3Model): List<IntArray> {
        val out = ArrayList<IntArray>(rs3.faceCount)
        for (submesh in rs3.submeshes) {
            for (corner in submesh.indices.indices step 3) {
                out.add(intArrayOf(submesh.indices[corner], submesh.indices[corner + 1], submesh.indices[corner + 2]))
            }
        }
        return out
    }

    private fun corners(model: ModelDefinition, face: Int): List<List<Int>> =
        listOf(model.faceA[face], model.faceB[face], model.faceC[face]).map { position(model, it) }.sortedWith(compareBy({ it[0] }, { it[1] }, { it[2] }))

    private fun alpha(model: ModelDefinition, face: Int): Int = (model.faceAlphas?.get(face)?.toInt() ?: 0) and 0xff

    private fun priority(model: ModelDefinition, face: Int): Int = model.facePriorities?.get(face)?.toInt() ?: model.modelPriority

    private fun vertex(model: ModelDefinition, vertex: Int): IntArray = intArrayOf(model.vertexX[vertex], model.vertexY[vertex], model.vertexZ[vertex])

    private fun position(model: ModelDefinition, vertex: Int): List<Int> = vertex(model, vertex).toList()

    private fun key(model: ModelDefinition, face: Int): List<Int> =
        listOf(model.faceA[face], model.faceB[face], model.faceC[face])
            .map { position(model, it) }
            .sortedWith(compareBy({ it[0] }, { it[1] }, { it[2] }))
            .flatten()
}
