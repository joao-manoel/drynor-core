package org.drynor.core.model

import world.gregs.voidps.type.Tile
import kotlin.math.roundToInt

data class Vec2(val x: Double, val y: Double) {
    constructor(x: Int, y: Int) : this(x.toDouble(), y.toDouble())
    constructor(x: Float, y: Float) : this(x.toDouble(), y.toDouble())
    constructor(tile: Tile) : this(tile.x.toDouble(), tile.y.toDouble())

    fun add(other: Vec2) = Vec2(x + other.x, y + other.y)
    fun sub(other: Vec2) = Vec2(x - other.x, y - other.y)
    fun mul(scalar: Double) = Vec2(x * scalar, y * scalar)
    fun length() = kotlin.math.sqrt(x * x + y * y)
    fun normalize(): Vec2 {
        val l = length()
        return if (l == 0.0) Vec2(0.0, 0.0) else Vec2(x / l, y / l)
    }

    /** Alias for [normalize] — legacy Java compatibility. */
    fun norm(): Vec2 = normalize()

    /** Converts this vector to a [Tile] by rounding to the nearest integer coordinates. */
    fun toTile(): Tile = Tile(x.roundToInt(), y.roundToInt())

    companion object {
        @JvmField val ZERO = Vec2(0.0, 0.0)
    }
}
