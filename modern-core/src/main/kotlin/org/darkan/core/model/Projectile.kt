package org.darkan.core.model

import world.gregs.voidps.type.Tile

open class Projectile(
    val from: Tile,
    val sourceId: Int = -1,
    val to: Tile,
    val lockOnId: Int = -1,
    val spotAnimId: Int,
    val startHeight: Int,
    val endHeight: Int,
    val startDelayClientCycles: Int,
    val inAirClientCycles: Int,
    val offset: Int,
    val angle: Int
) {
    var useTerrainHeight: Boolean = false
        private set
    var halfSqStartOffsetX: Int = 0
        private set
    var halfSqStartOffsetY: Int = 0
        private set
    var halfSqEndOffsetX: Int = 0
        private set
    var halfSqEndOffsetY: Int = 0
        private set
    var basFrameHeightAdjust: Int = -1
        private set

    fun setBASFrameHeightAdjust(frameIndex: Int): Projectile {
        basFrameHeightAdjust = frameIndex
        return this
    }

    fun setUseTerrainHeight(): Projectile {
        useTerrainHeight = true
        return this
    }

    fun setHalfSqStartOffsets(xy: Pair<Int, Int>): Projectile {
        halfSqStartOffsetX = xy.first
        halfSqStartOffsetY = xy.second
        return this
    }

    fun setHalfSqEndOffsets(xy: Pair<Int, Int>): Projectile {
        halfSqEndOffsetX = xy.first
        halfSqEndOffsetY = xy.second
        return this
    }

    val source: Tile get() = from
    val destination: Tile get() = to
}
