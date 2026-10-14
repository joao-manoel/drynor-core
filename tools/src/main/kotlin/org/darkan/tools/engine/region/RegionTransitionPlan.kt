package org.darkan.tools.engine.region

/**
 * Unique key for a region by its coordinates.
 */
data class RegionKey(val regionX: Int, val regionY: Int) {
    fun toLong(): Long = (regionX.toLong() shl 32) or (regionY.toLong() and 0xFFFFFFFFL)
}

/**
 * Diff between current and desired region sets.
 * Used by AsyncRegionManager to determine which regions to load/unload
 * when the render distance or center changes.
 */
data class RegionTransitionPlan(
    val regionsToKeep: Set<RegionKey>,
    val regionsToLoad: Set<RegionKey>,
    val regionsToUnload: Set<RegionKey>,
    val centerChanged: Boolean
) {
    companion object {
        /**
         * Compute the transition plan from a current set of loaded regions
         * to a new desired set.
         */
        fun compute(
            currentRegions: Set<RegionKey>,
            centerRegionX: Int,
            centerRegionY: Int,
            renderDistance: Int
        ): RegionTransitionPlan {
            val desired = mutableSetOf<RegionKey>()
            for (dx in -renderDistance..renderDistance) {
                for (dy in -renderDistance..renderDistance) {
                    val rx = centerRegionX + dx
                    val ry = centerRegionY + dy
                    if (rx in 0..255 && ry in 0..255) {
                        desired.add(RegionKey(rx, ry))
                    }
                }
            }

            val toKeep = currentRegions.intersect(desired)
            val toLoad = desired - currentRegions
            val toUnload = currentRegions - desired

            val centerChanged = toLoad.isNotEmpty() || toUnload.isNotEmpty()

            return RegionTransitionPlan(
                regionsToKeep = toKeep,
                regionsToLoad = toLoad,
                regionsToUnload = toUnload,
                centerChanged = centerChanged
            )
        }
    }
}
