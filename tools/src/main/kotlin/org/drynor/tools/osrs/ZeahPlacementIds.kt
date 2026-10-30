package org.drynor.tools.osrs

/** Allocate cache object IDs that can also be written to native 727 landscape placements. */
internal object ZeahPlacementIds {
    const val MAX_ID = 0x1ffff

    fun plan(sourceIds: Set<Int>, occupiedIds: Set<Int>): Map<Int, Int> {
        require(sourceIds.all { it >= 0 } && occupiedIds.all { it >= 0 })
        val reserved = occupiedIds.filterTo(mutableSetOf()) { it <= MAX_ID }
        val result = sortedMapOf<Int, Int>()
        for (id in sourceIds.sorted()) {
            if (id <= MAX_ID && id !in reserved) {
                result[id] = id
                reserved += id
            }
        }
        var candidate = MAX_ID
        for (id in sourceIds.sorted()) {
            if (id in result) continue
            while (candidate in reserved) candidate--
            require(candidate >= 0) { "No free landscape object ID remains" }
            result[id] = candidate
            reserved += candidate
            candidate--
        }
        check(result.size == sourceIds.size && result.values.toSet().size == sourceIds.size)
        return result
    }
}
