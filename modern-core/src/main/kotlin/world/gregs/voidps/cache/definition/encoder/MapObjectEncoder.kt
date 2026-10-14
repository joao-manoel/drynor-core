package world.gregs.voidps.cache.definition.encoder

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.DefinitionEncoder
import world.gregs.voidps.cache.definition.data.MapDefinition
import world.gregs.voidps.cache.definition.data.MapObject

/**
 * Encodes the object block of a map archive (`l{regionX}_{regionY}`), the inverse of `MapObjectDecoder`.
 *
 * The block is a list of object id groups, each group a list of positions:
 *
 * ```
 * <sum>                     id delta from the previous group, 0 ends the block
 *   <smart>                 position delta + 1 within the region, 0 ends the group
 *   <byte>                  shape shl 2 or rotation
 * ```
 *
 * `sum` is the client's `Packet.readSum`: unsigned smarts accumulated for as long as each part is 32767, so
 * deltas above 32766 are written as repeated 32767 parts followed by the remainder. Positions are the packed
 * `(level shl 12) or (localX shl 6) or localY` index and reset to 0 at the start of every group.
 *
 * ## Coordinates are region local
 *
 * [MapObject.x] and [MapObject.y] must be **local** 0..63 tile coordinates, not world coordinates. Several
 * callers in this repository (`RegionLoader`) hand back [MapObject] carrying world coordinates; encoding
 * those would pack garbage into the position index, so they are rejected here rather than silently written.
 *
 * ## Bridges
 *
 * The decoder stores objects at their original level, so an unmodified decode round trips. Anything that
 * shifts objects between levels for rendering (bridge / "link below" handling) must be undone before
 * encoding, or the shifted level is what gets written.
 *
 * ## Order, and why a decoded archive comes back byte for byte
 *
 * A group is a run of consecutive objects with the same id, so the list *is* the file: ids strictly
 * ascend from group to group (a delta of 0 ends the block, so they cannot repeat) and a position delta is
 * never negative, so a list that satisfies both spells exactly one byte sequence. Every one of the 727
 * cache's 2,407 location archives and 1,208 underwater ones does ([isOrdered]), so the list is written as
 * it stands and the bytes come back identical.
 *
 * A list that does not - objects an editor appended, or a caller that built them level by level - is
 * grouped by id and sorted into that shape first. The result decodes to the same objects either way; only
 * a list that was already in file order is guaranteed to re-encode to the same bytes.
 *
 * [MapDefinition.trailing] is written after the terminator, which is what the 48 zero padded archives
 * need to come back byte for byte.
 */
class MapObjectEncoder : DefinitionEncoder<MapDefinition> {

    override fun Writer.encode(definition: MapDefinition) {
        // An unset id means the caller never populated the definition; an empty block is a valid looking
        // archive that decodes to a region with no objects at all.
        require(definition.id != -1) { "Map definition has no region id, refusing to encode a blank object block." }
        val objects = definition.objects
        for (loc in objects) {
            require(loc.id >= 0) { "Map ${definition.id}: object id ${loc.id} is negative." }
            require(loc.shape in 0..31) {
                "Map ${definition.id}: object ${loc.id} at (${loc.x}, ${loc.y}, ${loc.level}) has shape " +
                    "${loc.shape}, outside 0..31."
            }
            index(definition, loc)
        }
        val groups = if (isOrdered(definition, objects)) runs(objects) else sorted(objects)
        var id = -1
        for (group in groups) {
            val objectId = group.first().id
            writeSum(objectId - id)
            id = objectId
            var location = 0
            for (loc in group) {
                val local = index(definition, loc)
                writeSmart(local - location + 1)
                location = local
                writeByte(loc.rotation or (loc.shape shl 2))
            }
            writeSmart(0)
        }
        writeSmart(0)
        // 48 archives are padded past their last record; the padding is inside the container the
        // client checks the CRC of, so it goes back out with them.
        definition.trailing?.let { writeBytes(it) }
    }

    /**
     * Whether [objects] is already a file: ids strictly ascending from run to run, and positions never
     * going backwards within a run.
     *
     * Those are exactly the two things the format cannot express - an id delta of 0 ends the block and a
     * position delta is unsigned - so a list that satisfies them is written verbatim and anything else is
     * regrouped.
     */
    private fun isOrdered(definition: MapDefinition, objects: List<MapObject>): Boolean {
        var id = -1
        var location = -1
        for (loc in objects) {
            if (loc.id != id) {
                if (loc.id < id) {
                    return false
                }
                id = loc.id
                location = -1
            }
            val local = index(definition, loc)
            if (local < location) {
                return false
            }
            location = local
        }
        return true
    }

    /** [objects] split into runs of consecutive objects sharing an id. */
    private fun runs(objects: List<MapObject>): List<List<MapObject>> {
        val groups = ArrayList<List<MapObject>>()
        var start = 0
        while (start < objects.size) {
            var end = start + 1
            while (end < objects.size && objects[end].id == objects[start].id) {
                end++
            }
            groups.add(objects.subList(start, end))
            start = end
        }
        return groups
    }

    /** [objects] grouped by id and sorted, for a list that was not built in file order. */
    private fun sorted(objects: List<MapObject>): List<List<MapObject>> = objects
        .groupBy { it.id }
        .toList()
        .sortedBy { it.first }
        .map { (_, group) -> group.sortedBy { MapDefinition.index(it.x, it.y, it.level) } }

    private fun index(definition: MapDefinition, loc: MapObject): Int {
        require(loc.x in 0..63 && loc.y in 0..63) {
            "Map ${definition.id}: object ${loc.id} at (${loc.x}, ${loc.y}) is not a region local coordinate, expected 0..63 - world coordinates cannot be encoded."
        }
        return MapDefinition.index(loc.x, loc.y, loc.level)
    }

    /**
     * Writes [value] the way the client's `Packet.readSum` reads it: 32767 parts until the remainder is
     * below 32767. A value that is an exact multiple of 32767 still needs the trailing remainder part,
     * otherwise the reader keeps chaining into the next field.
     */
    private fun Writer.writeSum(value: Int) {
        var remaining = value
        while (remaining >= 32767) {
            writeSmart(32767)
            remaining -= 32767
        }
        writeSmart(remaining)
    }
}
