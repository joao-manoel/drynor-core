package org.drynor.tools.rs3port

import world.gregs.voidps.cache.definition.data.ModelDefinition

class MatchedFace(val source: Int, val submesh: Int, val target: Int)

class FaceMatch(private val bySubmesh: Map<Int, List<MatchedFace>>, val ambiguous: Int) {

    fun faces(submesh: Int): List<MatchedFace> = bySubmesh[submesh].orEmpty()

    fun all(): List<MatchedFace> = bySubmesh.values.flatten()
}

object FaceMatcher {

    fun match(rs3: Rs3Model, target: ModelDefinition): FaceMatch {
        val lookup = HashMap<List<Int>, MutableList<Int>>(target.faceCount * 2)
        for (face in 0 until target.faceCount) {
            val key = key(
                intArrayOf(
                    target.vertexX[target.faceA[face]], target.vertexY[target.faceA[face]], target.vertexZ[target.faceA[face]],
                    target.vertexX[target.faceB[face]], target.vertexY[target.faceB[face]], target.vertexZ[target.faceB[face]],
                    target.vertexX[target.faceC[face]], target.vertexY[target.faceC[face]], target.vertexZ[target.faceC[face]]
                )
            )
            lookup.getOrPut(key) { ArrayList(1) }.add(face)
        }
        val matches = HashMap<Int, MutableList<MatchedFace>>()
        var ambiguous = 0
        var source = 0
        for ((submesh, part) in rs3.submeshes.withIndex()) {
            val list = matches.getOrPut(submesh) { ArrayList() }
            for (corner in part.indices.indices step 3) {
                val current = source++
                val a = part.indices[corner]
                val b = part.indices[corner + 1]
                val c = part.indices[corner + 2]
                val key = key(
                    intArrayOf(
                        rs3.vertexX(a), rs3.vertexY(a), rs3.vertexZ(a),
                        rs3.vertexX(b), rs3.vertexY(b), rs3.vertexZ(b),
                        rs3.vertexX(c), rs3.vertexY(c), rs3.vertexZ(c)
                    )
                )
                val candidates = lookup[key]
                if (candidates == null || candidates.size != 1) {
                    ambiguous++
                    continue
                }
                list.add(MatchedFace(current, submesh, candidates[0]))
            }
        }
        return FaceMatch(matches, ambiguous)
    }

    private fun key(corners: IntArray): List<Int> {
        val triples = listOf(
            listOf(corners[0], corners[1], corners[2]),
            listOf(corners[3], corners[4], corners[5]),
            listOf(corners[6], corners[7], corners[8])
        ).sortedWith(compareBy({ it[0] }, { it[1] }, { it[2] }))
        return triples.flatten()
    }
}
