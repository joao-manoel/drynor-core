package org.darkan.core.net.prot.update

/**
 * Data-driven configuration for player update bit-access encoding.
 *
 * These constants vary between protocol revisions but the encoding logic
 * (skip tiers, teleport thresholds, movement bits) follows the same
 * structural pattern.
 *
 * @property smallTeleportThreshold Max absolute offset (per axis) that qualifies as a "small" teleport.
 * @property smallTeleportBits Bit width for the compact teleport encoding.
 * @property largeTeleportBits Bit width for full-precision teleport encoding.
 * @property walkDirectionBits Bit width for a single walk direction.
 * @property runDirectionBits Bit width for the combined run direction/opcode.
 * @property skipThresholds Lower bounds for each skip-count tier (ascending). Tier 0 is always 0 (exact match).
 * @property skipBitWidths Extra data bits written after the 2-bit tier selector. 0 means no extra bits (tier 0).
 * @property addPositionBits Bit width for xInRegion/yInRegion when adding a new player.
 * @property maxAddPerTick Maximum number of new players added per tick.
 */
data class PlayerBitConfig(
    val smallTeleportThreshold: Int,
    val smallTeleportBits: Int,
    val largeTeleportBits: Int,
    val walkDirectionBits: Int,
    val runDirectionBits: Int,
    val skipThresholds: IntArray,
    val skipBitWidths: IntArray,
    val addPositionBits: Int,
    val maxAddPerTick: Int,
) {
    init {
        require(skipThresholds.size == skipBitWidths.size) {
            "skipThresholds and skipBitWidths must have the same length"
        }
        require(skipThresholds.isNotEmpty() && skipThresholds[0] == 0) {
            "skipThresholds[0] must be 0 (exact-match tier)"
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PlayerBitConfig) return false
        return smallTeleportThreshold == other.smallTeleportThreshold &&
            smallTeleportBits == other.smallTeleportBits &&
            largeTeleportBits == other.largeTeleportBits &&
            walkDirectionBits == other.walkDirectionBits &&
            runDirectionBits == other.runDirectionBits &&
            skipThresholds.contentEquals(other.skipThresholds) &&
            skipBitWidths.contentEquals(other.skipBitWidths) &&
            addPositionBits == other.addPositionBits &&
            maxAddPerTick == other.maxAddPerTick
    }

    override fun hashCode(): Int {
        var result = smallTeleportThreshold
        result = 31 * result + smallTeleportBits
        result = 31 * result + largeTeleportBits
        result = 31 * result + walkDirectionBits
        result = 31 * result + runDirectionBits
        result = 31 * result + skipThresholds.contentHashCode()
        result = 31 * result + skipBitWidths.contentHashCode()
        result = 31 * result + addPositionBits
        result = 31 * result + maxAddPerTick
        return result
    }
}

/**
 * Data-driven configuration for NPC update bit-access encoding.
 *
 * @property localCountBits Bit width for the local NPC count header.
 * @property indexBits Bit width for NPC server index.
 * @property normalPositionBits Bit width for position delta in normal (non-large) scenes.
 * @property largePositionBits Bit width for position delta in large scenes.
 * @property rotationBits Bit width for initial rotation when adding an NPC.
 * @property terminator Sentinel value written to mark end of NPC additions.
 * @property walkDirectionBits Bit width for a single walk direction.
 * @property idBits Bit width for NPC definition ID.
 * @property planeBits Bit width for plane/level.
 */
data class NpcBitConfig(
    val localCountBits: Int,
    val indexBits: Int,
    val normalPositionBits: Int,
    val largePositionBits: Int,
    val rotationBits: Int,
    val terminator: Int,
    val walkDirectionBits: Int,
    val idBits: Int,
    val planeBits: Int,
)
