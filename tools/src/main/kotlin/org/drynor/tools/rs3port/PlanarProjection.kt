package org.drynor.tools.rs3port

import kotlin.math.abs
import kotlin.math.roundToInt

object PlanarProjection {

    private const val DEGENERATE = 1e-9

    fun solve(rs3: Rs3Model, a: Int, b: Int, c: Int): List<Int> {
        val uvs = rs3.uvs ?: throw IllegalStateException("RS3 model ${rs3.id} has textured faces but no UVs.")
        val origin = position(rs3, a)
        val edgeB = subtract(position(rs3, b), origin)
        val edgeC = subtract(position(rs3, c), origin)
        val ua = uvs[a * 2].toDouble()
        val va = uvs[a * 2 + 1].toDouble()
        val du1 = uvs[b * 2] - ua
        val dv1 = uvs[b * 2 + 1] - va
        val du2 = uvs[c * 2] - ua
        val dv2 = uvs[c * 2 + 1] - va
        val determinant = du1 * dv2 - du2 * dv1
        check(abs(determinant) > DEGENERATE) {
            "RS3 model ${rs3.id} has a textured face ($a, $b, $c) whose UVs have no area; no planar triangle maps it."
        }
        val u = DoubleArray(3) { (dv2 * edgeB[it] - dv1 * edgeC[it]) / determinant }
        val v = DoubleArray(3) { (du1 * edgeC[it] - du2 * edgeB[it]) / determinant }
        val p = DoubleArray(3) { origin[it] - ua * u[it] - va * v[it] }
        val out = ArrayList<Int>(9)
        for (axis in 0 until 3) {
            out.add(p[axis].roundToInt())
        }
        for (axis in 0 until 3) {
            out.add((p[axis] + u[axis]).roundToInt())
        }
        for (axis in 0 until 3) {
            out.add((p[axis] + v[axis]).roundToInt())
        }
        return out
    }

    fun uv(p: IntArray, m: IntArray, n: IntArray, point: IntArray): DoubleArray {
        val mx = subtract(m, p)
        val nx = subtract(n, p)
        val ax = subtract(point, p)
        val normal = cross(mx, nx)
        val alongU = cross(nx, normal)
        val alongV = cross(mx, normal)
        return doubleArrayOf(dot(alongU, ax) / dot(alongU, mx), dot(alongV, ax) / dot(alongV, nx))
    }

    private fun position(rs3: Rs3Model, vertex: Int): DoubleArray =
        doubleArrayOf(rs3.vertexX(vertex).toDouble(), rs3.vertexY(vertex).toDouble(), rs3.vertexZ(vertex).toDouble())

    private fun subtract(a: DoubleArray, b: DoubleArray): DoubleArray = DoubleArray(3) { a[it] - b[it] }

    private fun subtract(a: IntArray, b: IntArray): DoubleArray = DoubleArray(3) { (a[it] - b[it]).toDouble() }

    private fun cross(a: DoubleArray, b: DoubleArray): DoubleArray = doubleArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0]
    )

    private fun dot(a: DoubleArray, b: DoubleArray): Double = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
}
