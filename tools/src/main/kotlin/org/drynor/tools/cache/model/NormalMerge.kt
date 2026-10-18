package org.drynor.tools.cache.model

import world.gregs.voidps.cache.definition.data.ModelDefinition

/**
 * The vertex normals an object instance ends up with after its neighbours' faces are folded in.
 *
 * RS scenery is authored as tile-sized pieces — a Canifis roof is 780 separate one-tile objects
 * drawn from seven models of two to seven faces each. Smoothing vertex normals *within* a model
 * therefore stops at every tile boundary, and a two-triangle roof slope ends up averaging its
 * shingle normals with the vertical fascia trim that shares the same corner. The client's fix is
 * `ModelGL.applyShading(other, dx, dy, dz)`: for every vertex an object shares with an adjacent
 * object it adds the neighbour's accumulated normal *and* the neighbour's contribution count into
 * a merged copy, so the final average spans both models' faces.
 *
 * This class carries the *outcome* of that exchange for one object, resolved by
 * [org.drynor.tools.cache.map.NormalMerger]:
 *
 *  - [vertexIndex] indexes the (possibly combined) [ModelDefinition] the mesh is built from,
 *    ascending, and lists only the vertices the merge actually moved.
 *  - [normalX]/[normalY]/[normalZ] are the finished unit normals in OpenGL space — exactly what
 *    the mesh's normal array should hold at every slot fed by that vertex.
 *
 * ## Why the finished direction and not the contribution
 *
 * The merge used to ship the neighbours' raw normal sums plus their face count, leaving
 * [ModelLoader] to re-derive the average. That meant every one of the ~9,000 distinct
 * neighbourhoods in a render-distance-3 town re-ran the whole face loop of
 * `accumulateVertexNormals` just to reach a direction the merge already knew. It also made the
 * cache key far finer than the thing it identifies: two neighbourhoods whose sums differ in the
 * last decimal place but whose final normals are visually the same forked into two meshes, two
 * decodes, and two indirect draw commands.
 *
 * Carrying the resolved direction fixes both. The identity is now the rendered normal itself,
 * quantised to the resolution the merge is willing to distinguish, so instances that would look
 * identical genuinely share one mesh.
 *
 * ## Signature
 *
 * [signature] identifies the contents exactly: it is a 128-bit hash over the quantised normals
 * and the vertices they belong to, which is what meshes are cached under (see
 * [ModelModifications.cacheKeyComponent]). A hash rather than the values themselves because this
 * string is concatenated into a cache key for every merged instance in the scene — the previous
 * literal encoding ran to hundreds of characters per object.
 */
class NormalMerge(
    val vertexIndex: IntArray,
    val normalX: FloatArray,
    val normalY: FloatArray,
    val normalZ: FloatArray,
    val signature: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NormalMerge) return false
        return signature == other.signature
    }

    override fun hashCode(): Int = signature.hashCode()
}
