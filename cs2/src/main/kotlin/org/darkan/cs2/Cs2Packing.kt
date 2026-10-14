package org.darkan.cs2

/**
 * Packs and unpacks the composite integers CS2 passes around.
 *
 * These are the values that otherwise read as magic numbers: an interface
 * component arrives as one int holding both the interface and the component
 * index, and a world position as one int holding plane, x and y.
 */
object Cs2Packing {

    fun interfaceOf(hash: Int): Int = hash ushr 16

    fun componentOf(hash: Int): Int = hash and 0xFFFF

    fun packComponent(interfaceId: Int, component: Int): Int =
        (interfaceId shl 16) or (component and 0xFFFF)

    /** True when the value carries an interface part worth spelling out. */
    fun isPackedComponent(hash: Int): Boolean = hash > 0xFFFF

    fun planeOf(coord: Int): Int = (coord ushr 28) and 0x3

    fun xOf(coord: Int): Int = (coord ushr 14) and 0x3FFF

    fun yOf(coord: Int): Int = coord and 0x3FFF

    fun packCoord(x: Int, y: Int, plane: Int): Int =
        ((plane and 0x3) shl 28) or ((x and 0x3FFF) shl 14) or (y and 0x3FFF)

    /** True when the value looks like a real position rather than a sentinel. */
    fun isPackedCoord(coord: Int): Boolean = coord > 0
}
