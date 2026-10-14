package org.darkan.core.map

import org.darkan.core.model.ObjectShape
import org.darkan.core.model.WorldObject
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.definition.data.MapDefinition

class MapRegion(@JvmField protected var regionId: Int) {

    private var objects: MutableList<WorldObject> = mutableListOf()
    private var clipFlags: Array<Array<IntArray>>? = null
    private var renderFlags: Array<Array<ByteArray>>? = null
    private var hasData = false
    private var xtea = false

    val baseX: Int get() = (regionId shr 8 and 0xFF) shl 6
    val baseY: Int get() = (regionId and 0xFF) shl 6

    fun loadRegionMap(loadExtraData: Boolean) {
        val data = RegionLoader.loadRegion(Cache.get(), regionId)
        if (data == null) {
            if (clipFlags == null) clipFlags = Array(4) { Array(64) { IntArray(64) } }
            for (plane in 0 until 4)
                for (x in 0 until 64)
                    for (y in 0 until 64)
                        clipFlags!![plane][x][y] = clipFlags!![plane][x][y] or ClipFlag.PFBW_FLOOR.flag
            return
        }

        hasData = true
        if (clipFlags == null) clipFlags = Array(4) { Array(64) { IntArray(64) } }
        renderFlags = Array(4) { Array(64) { ByteArray(64) } }

        val settings = data.settings
        for (plane in 0 until 4) {
            for (x in 0 until 64) {
                for (y in 0 until 64) {
                    val idx = MapDefinition.index(x, y, plane)
                    val setting = settings[idx].toInt() and 0xff
                    renderFlags!![plane][x][y] = setting.toByte()
                    if (setting and RENDER_UNDER_ROOF != 0)
                        clipFlags!![plane][x][y] = clipFlags!![plane][x][y] or ClipFlag.UNDER_ROOF.flag
                }
            }
        }

        if (regionId != 11844) {
            for (plane in 0 until 4) {
                for (x in 0 until 64) {
                    for (y in 0 until 64) {
                        if (renderFlags!![plane][x][y].toInt() and RENDER_CLIPPED != 0) {
                            var finalPlane = plane
                            if (renderFlags!![1][x][y].toInt() and RENDER_BRIDGE != 0)
                                finalPlane--
                            if (finalPlane >= 0)
                                clipFlags!![finalPlane][x][y] = clipFlags!![finalPlane][x][y] or ClipFlag.PFBW_FLOOR.flag
                        }
                    }
                }
            }
        }

        val mapObjects = data.objects
        if (mapObjects.isNotEmpty()) {
            xtea = true
            for (obj in mapObjects) {
                val localX = obj.x - baseX
                val localY = obj.y - baseY
                if (localX < 0 || localX >= 64 || localY < 0 || localY >= 64) continue
                var objPlane = obj.level
                if (renderFlags != null && renderFlags!![1][localX][localY].toInt() and RENDER_BRIDGE != 0)
                    objPlane--
                if (objPlane < 0 || objPlane >= 4) continue
                val shape = ObjectShape.forId(obj.shape) ?: ObjectShape.SCENERY_INTERACT
                objects.add(WorldObject(obj.id, shape, obj.rotation, obj.x, obj.y, objPlane))
            }
        }
    }

    fun getRotation(plane: Int, localX: Int, localY: Int): Int = 0

    fun isLinkedBelow(z: Int, x: Int, y: Int): Boolean =
        renderFlags != null && renderFlags!![z][x][y].toInt() and RENDER_BRIDGE != 0

    fun isVisibleBelow(z: Int, x: Int, y: Int): Boolean =
        renderFlags != null && renderFlags!![z][x][y].toInt() and 0x8 != 0

    fun getRenderFlags(z: Int, x: Int, y: Int): Int =
        renderFlags?.get(z)?.get(x)?.get(y)?.toInt() ?: 0

    fun hasData(): Boolean = hasData
    fun hasXtea(): Boolean = xtea
    fun isMissingXtea(): Boolean = hasData && !xtea
    fun getClipFlags(): Array<Array<IntArray>>? = clipFlags
    fun getObjects(): List<WorldObject> = objects

    companion object {
        const val WIDTH = 64
        const val HEIGHT = 64
        private const val RENDER_CLIPPED = 0x1
        private const val RENDER_BRIDGE = 0x2
        private const val RENDER_UNDER_ROOF = 0x4
    }
}
