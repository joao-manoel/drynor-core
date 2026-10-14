package org.darkan.core.map

import org.darkan.core.map.pathfinder.getIndexInZone
import org.darkan.core.map.pathfinder.getZoneIndex

class CollisionBuffer(zoneIds: Collection<Int>) : CollisionWriter {
    private val zones = zoneIds.associateWith { IntArray(ZONE_TILES) }
    private val overflow = ArrayList<IntArray>()

    override fun add(x: Int, y: Int, level: Int, flag: Int) {
        val zone = zones[getZoneIndex(x, y, level)]
        if (zone == null) {
            overflow += intArrayOf(x, y, level, flag)
            return
        }
        val index = getIndexInZone(x, y)
        zone[index] = zone[index] or flag
    }

    fun flags(zoneId: Int): IntArray? = zones[zoneId]

    fun writeOverflow(writer: CollisionWriter) = overflow.forEach { (x, y, level, flag) -> writer.add(x, y, level, flag) }

    private companion object {
        const val ZONE_TILES = 64
    }
}
