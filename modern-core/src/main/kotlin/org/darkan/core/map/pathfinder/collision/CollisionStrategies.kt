@file:Suppress("unused")

package org.darkan.core.map.pathfinder.collision

enum class CollisionStrategyType(val strategy: CollisionStrategy) {
    NORMAL(NormalBlockFlagCollision()),
    WATER(BlockedFlagCollision()),
    FLY(LineOfSightBlockFlagCollision()),
    INDOOR(IndoorsFlagCollision()),
    OUTDOOR(OutdoorsFlagCollision())
}
