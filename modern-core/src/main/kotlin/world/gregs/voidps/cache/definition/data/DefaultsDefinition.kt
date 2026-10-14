package world.gregs.voidps.cache.definition.data

import world.gregs.voidps.cache.Definition
import world.gregs.voidps.cache.definition.DefinitionRecord
import world.gregs.voidps.cache.definition.OpcodeOrdered

/**
 * One of index 28's archives: the handful of settings the client reads before anything else exists.
 *
 * Six archives in the 727 cache, and the client names them itself
 * (`DefaultsFile`: 1 scene cube textures, 3 entity, 4 title screen music, 6 equipment,
 * 7 interactions, 8 cutscene). Five of them are here - equipment is [BodyDefinition], which already
 * existed - and which archive a definition is is its [id], because the same opcode means a different
 * thing in each.
 *
 * Every field is nullable and a null one is left out of both the file and its JSON, so an archive's
 * file carries only its own settings and reads as what it is.
 *
 * A `Boolean?` field is a bare flag opcode: non-null means the file carried the record, and the value
 * is what the record does.
 */
data class DefaultsDefinition(
    override var id: Int = -1,
    /** Archive 1 opcode 1: the six skybox cube face texture ids. */
    var cubeTextures: IntArray? = null,
    /** Archive 3 opcode 3: how many hitsplats stack on an entity. */
    var maximumHits: Int? = null,
    /** Archive 3 opcode 1: the offset of each stacked hitsplat, [maximumHits] of them. */
    var hitOffsetsX: IntArray? = null,
    var hitOffsetsY: IntArray? = null,
    /** Archive 3 opcode 2: the mesh the profiler draws. */
    var profilingMesh: Int? = null,
    /** Archive 3 opcode 4, a flag: the client stops always showing the context menu. */
    var alwaysShowContextMenu: Boolean? = null,
    /** Archive 3 opcode 5: the interface the login screen opens. */
    var loginInterface: Int? = null,
    /** Archive 3 opcode 6: the lobby's top level window. */
    var lobbyWindow: Int? = null,
    /** Archive 3 opcode 7: ten palettes of four recolours, flattened palette major. */
    var recolours: List<RecolourRecord>? = null,
    /** Archive 3 opcode 8, a flag: npc overhead messages off. */
    var npcMessages: Boolean? = null,
    /** Archive 3 opcode 9: how many ticks an npc overhead message lasts. */
    var npcMessageDuration: Int? = null,
    /** Archive 3 opcode 10, a flag: player overhead messages off. */
    var playerMessages: Boolean? = null,
    /** Archive 3 opcode 11: how many ticks a player overhead message lasts. */
    var playerMessageDuration: Int? = null,
    /** Archive 3 opcode 12: the default game view size. */
    var gameWidth: Int? = null,
    var gameHeight: Int? = null,
    /** Archive 3 opcodes 13, 14 and 15: hit bar stacking and height. */
    var maxHitbars: Int? = null,
    var maxVisibleHitbars: Int? = null,
    var hitbarHeight: Int? = null,
    /** Archive 4 opcode 1: the music archive the title screen plays. */
    var titleScreenMusic: Int? = null,
    /**
     * Archive 7 opcodes 1 to 10 and archive 8 opcode 1: the input bindings, in file order.
     *
     * Which binding a record is is its [InputSubscriberRecord.opcode]; archive 7's 8, 9 and 10 are read
     * and dropped by the client and are kept here so the file survives.
     */
    var inputs: MutableList<InputSubscriberRecord>? = null,
    /** Archive 7 opcode 11, a flag: the minimenu gets its own cursor. */
    var minimenuCursor: Boolean? = null,
    /** Archive 7 opcodes 12 and 13: the members and free tooltip colours. */
    var tooltipColourMembers: Int? = null,
    var tooltipColourFree: Int? = null,
    override var opcodeOrder: IntArray? = null,
    override var shadowedPayloads: Array<ByteArray?>? = null
) : Definition, OpcodeOrdered {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as DefaultsDefinition
        if (id != other.id) return false
        if (!contentEquals(cubeTextures, other.cubeTextures)) return false
        if (maximumHits != other.maximumHits) return false
        if (!contentEquals(hitOffsetsX, other.hitOffsetsX)) return false
        if (!contentEquals(hitOffsetsY, other.hitOffsetsY)) return false
        if (profilingMesh != other.profilingMesh) return false
        if (alwaysShowContextMenu != other.alwaysShowContextMenu) return false
        if (loginInterface != other.loginInterface) return false
        if (lobbyWindow != other.lobbyWindow) return false
        if (recolours != other.recolours) return false
        if (npcMessages != other.npcMessages) return false
        if (npcMessageDuration != other.npcMessageDuration) return false
        if (playerMessages != other.playerMessages) return false
        if (playerMessageDuration != other.playerMessageDuration) return false
        if (gameWidth != other.gameWidth) return false
        if (gameHeight != other.gameHeight) return false
        if (maxHitbars != other.maxHitbars) return false
        if (maxVisibleHitbars != other.maxVisibleHitbars) return false
        if (hitbarHeight != other.hitbarHeight) return false
        if (titleScreenMusic != other.titleScreenMusic) return false
        if (inputs != other.inputs) return false
        if (minimenuCursor != other.minimenuCursor) return false
        if (tooltipColourMembers != other.tooltipColourMembers) return false
        if (tooltipColourFree != other.tooltipColourFree) return false
        return true
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + (cubeTextures?.contentHashCode() ?: 0)
        result = 31 * result + (maximumHits ?: 0)
        result = 31 * result + (hitOffsetsX?.contentHashCode() ?: 0)
        result = 31 * result + (hitOffsetsY?.contentHashCode() ?: 0)
        result = 31 * result + (profilingMesh ?: 0)
        result = 31 * result + (recolours?.hashCode() ?: 0)
        result = 31 * result + (titleScreenMusic ?: 0)
        result = 31 * result + (inputs?.hashCode() ?: 0)
        return result
    }

    private fun contentEquals(left: IntArray?, right: IntArray?): Boolean {
        if (left == null) {
            return right == null
        }
        return right != null && left.contentEquals(right)
    }

    companion object {
        /** The archive ids index 28 holds in 727, which the client's `DefaultsFile` names. */
        const val SCENE_CUBE_TEXTURES = 1
        const val ENTITY = 3
        const val TITLE_SCREEN_MUSIC = 4
        const val EQUIPMENT = 6
        const val INTERACTIONS = 7
        const val CUTSCENE = 8

        /** How many hitsplats stack when the file does not say ([ENTITY] opcode 3). */
        const val DEFAULT_MAXIMUM_HITS = 4

        /** [ENTITY] opcode 7 is ten palettes of four colours, always, whatever they hold. */
        const val PALETTES = 10
        const val PALETTE_COLOURS = 4
    }
}

/**
 * One entry of the entity defaults' recolour palette: a source colour and what it may become.
 *
 * `-1` is the file's `65535`, which is what the client turns it into and what it means: no colour.
 */
data class RecolourRecord(
    var source: Int = -1,
    var destinations: IntArray = IntArray(0)
) : DefinitionRecord {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as RecolourRecord
        return source == other.source && destinations.contentEquals(other.destinations)
    }

    override fun hashCode(): Int = 31 * source + destinations.contentHashCode()
}

/**
 * One input binding, as index 28's archives 7 and 8 hold them.
 *
 * The client has three shapes and they share this record; which one a binding is is [type], except for
 * archive 7's opcodes 5, 6 and 7, which are a key hold body with no type byte in front of it and so
 * leave [type] null.
 *
 * @param opcode which binding this is - the opcode the file wrote it under
 * @param type 0 combined, 1 key press, 2 key hold; null for the three bare key hold records
 * @param clickType combined only: which mouse button
 * @param minimumClicks combined only
 * @param code key press only: the key code
 * @param flags key press only: the modifier flags
 * @param keys combined and key hold: the keys held with it
 */
data class InputSubscriberRecord(
    var opcode: Int = -1,
    var type: Int? = null,
    var clickType: Int? = null,
    var minimumClicks: Int? = null,
    var code: Int? = null,
    var flags: Int? = null,
    var keys: IntArray? = null
) : DefinitionRecord {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as InputSubscriberRecord
        if (opcode != other.opcode || type != other.type) return false
        if (clickType != other.clickType || minimumClicks != other.minimumClicks) return false
        if (code != other.code || flags != other.flags) return false
        val left = keys
        val right = other.keys
        if (left == null) {
            return right == null
        }
        return right != null && left.contentEquals(right)
    }

    override fun hashCode(): Int {
        var result = opcode
        result = 31 * result + (type ?: -1)
        result = 31 * result + (code ?: -1)
        result = 31 * result + (keys?.contentHashCode() ?: 0)
        return result
    }

    companion object {
        const val COMBINED = 0
        const val KEY_PRESS = 1
        const val KEY_HOLD = 2
    }
}
