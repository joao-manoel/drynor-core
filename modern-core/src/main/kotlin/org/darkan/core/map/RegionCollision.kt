package org.darkan.core.map

import org.darkan.core.cache.ObjectDefinitions
import org.darkan.core.map.pathfinder.getZoneIndex
import org.darkan.core.model.WorldObject
import world.gregs.voidps.cache.definition.data.ObjectDefinition

object RegionCollision {

    fun load(regionId: Int, clipObjects: Boolean = true): MapRegion {
        val region = MapRegion(regionId)
        region.loadRegionMap(false)
        val flags = region.getClipFlags() ?: return region
        for (plane in 0 until PLANES)
            for (x in 0 until MapRegion.WIDTH step ZONE_SIZE)
                for (y in 0 until MapRegion.HEIGHT step ZONE_SIZE)
                    WorldCollision.initZone(getZoneIndex(region.baseX + x, region.baseY + y, plane))
        for (plane in 0 until PLANES)
            for (x in 0 until MapRegion.WIDTH)
                for (y in 0 until MapRegion.HEIGHT)
                    WorldCollision.addFlag(region.baseX + x, region.baseY + y, plane, flags[plane][x][y])
        if (clipObjects) region.getObjects().forEach(::clip)
        return region
    }

    @JvmOverloads
    fun clip(obj: WorldObject, writer: CollisionWriter = WorldCollision.live) {
        if (obj.id == -1) return
        val defs = ObjectDefinitions.getDefs(obj.id)
        WorldCollision.clipObject(obj.tile, obj.id, obj.type, obj.rotation, defs.solid, defs.blocksProjectiles, defs.ignoreOnRoute, defs.sizeX, defs.sizeY, writer)
    }

    fun unclip(obj: WorldObject) {
        if (obj.id == -1) return
        val defs = ObjectDefinitions.getDefs(obj.id)
        WorldCollision.unclipObject(obj.tile, obj.id, obj.type, obj.rotation, defs.solid, defs.blocksProjectiles, defs.ignoreOnRoute, defs.sizeX, defs.sizeY)
    }

    private val ObjectDefinition.blocksProjectiles: Boolean get() = (block and ObjectDefinition.PROJECTILE) != 0

    private const val PLANES = 4
    private const val ZONE_SIZE = 8
}
