/**
 * Interface and component packets.
 */
package org.darkan.core.net.prot.packet

import org.darkan.core.net.prot.ServerProt
import world.gregs.voidps.type.Tile

data class IfSetPlayerHead(val interfaceId: Int, val componentId: Int) : ServerProt

data class IfSetPlayerHeadIgnoreWorn(val interfaceId: Int, val componentId: Int, val identiKit1: Int, val identiKit2: Int, val identiKit3: Int) : ServerProt

data class IfSetPlayerModel(val interfaceId: Int, val componentId: Int) : ServerProt

data class IfSetTextFont(val interfaceId: Int, val componentId: Int, val fontId: Int) : ServerProt

data class IfMoveSub(val fromInterfaceId: Int, val fromComponentId: Int, val toInterfaceId: Int, val toComponentId: Int) : ServerProt

data class IfOpenSubActiveObject(
    val interfaceId: Int,
    val componentId: Int,
    val subInterfaceId: Int,
    val overlay: Boolean,
    val xteas: IntArray,
    val objectId: Int,
    val tile: Tile,
    val type: Int,
    val rotation: Int,
) : ServerProt {
    init {
        require(xteas.size == 4) { "xteas must be length 4" }
    }
}

data class IfSetHide(val interfaceId: Int, val componentId: Int, val hidden: Boolean) : ServerProt

data class IfOpenSubActivePlayer(
    val interfaceId: Int,
    val componentId: Int,
    val subInterfaceId: Int,
    val overlay: Boolean,
    val playerIndex: Int,
    val xteas: IntArray,
) : ServerProt {
    init {
        require(xteas.size == 4) { "xteas must be length 4" }
    }
}

data class IfSetGraphic(val interfaceId: Int, val componentId: Int, val graphicId: Int) : ServerProt

data class IfSetTextAntiMacro(val interfaceId: Int, val componentId: Int, val enabled: Boolean) : ServerProt

data class IfOpenTop(
    val interfaceId: Int,
    val type: Int,
    val xteas: IntArray,
) : ServerProt {
    init {
        require(xteas.size == 4) { "xteas must be length 4" }
    }
}

data class IfOpenSub(
    val interfaceId: Int,
    val componentId: Int,
    val subInterfaceId: Int,
    val overlay: Boolean,
    val xteas: IntArray,
) : ServerProt {
    init {
        require(xteas.size == 4) { "xteas must be length 4" }
    }
}

data class IfSetAngle(val interfaceId: Int, val componentId: Int, val pitch: Int, val roll: Int, val scale: Int) : ServerProt

data class IfSetPosition(val interfaceId: Int, val componentId: Int, val x: Int, val y: Int) : ServerProt

data class IfSetPlayerModelOther(val interfaceId: Int, val componentId: Int, val primaryModelId: Int, val secondaryModelId: Int) : ServerProt

data class IfSetScrollPos(val interfaceId: Int, val componentId: Int, val scrollPos: Int) : ServerProt

data class IfOpenSubActiveNpc(
    val interfaceId: Int,
    val componentId: Int,
    val subInterfaceId: Int,
    val overlay: Boolean,
    val npcIndex: Int,
    val xteas: IntArray,
) : ServerProt {
    init {
        require(xteas.size == 4) { "xteas must be length 4" }
    }
}

data class IfCloseSub(val interfaceId: Int, val componentId: Int) : ServerProt

data class IfSetAnim(val interfaceId: Int, val componentId: Int, val animationId: Int) : ServerProt

data class IfSetColor(val interfaceId: Int, val componentId: Int, val color: Int) : ServerProt

data class IfSetNpcHead(val interfaceId: Int, val componentId: Int, val npcId: Int) : ServerProt

data class IfSetItem(val interfaceId: Int, val componentId: Int, val itemId: Int, val amount: Int) : ServerProt

/**
 * Sets the event mask for a range of slots on a component (opcode 121).
 *
 * Naming note: the 727 client's `ServerProt` enum calls this `IF_SETTARGETPARAM` and calls opcode
 * 148 `IF_RESETTARGETPARAM`, which is the reverse of how [IfSetTargetParam] is named here. The
 * names here follow the packets' shape and conventional usage; the wire encoding is what matters
 * and is verified against the client, so do not "correct" the names without re-checking the
 * opcodes.
 */
data class IfSetEvents(
    val interfaceId: Int,
    val componentId: Int,
    val fromSlot: Int,
    val toSlot: Int,
    val settings: Int,
) : ServerProt

data class IfSetText(val interfaceId: Int, val componentId: Int, val text: String) : ServerProt

data class IfSetReTex(val interfaceId: Int, val componentId: Int, val index: Int, val fromTextureId: Int, val toTextureId: Int) : ServerProt

data class IfSetModel(val interfaceId: Int, val componentId: Int, val modelId: Int) : ServerProt

data class IfSetReCol(val interfaceId: Int, val componentId: Int, val index: Int, val fromColor: Int, val toColor: Int) : ServerProt

/**
 * Sets the target parameter for a range of slots on a component (opcode 148).
 *
 * See the naming note on [IfSetEvents]: the client enum labels this opcode `IF_RESETTARGETPARAM`.
 */
data class IfSetTargetParam(
    val interfaceId: Int,
    val componentId: Int,
    val targetInterfaceId: Int,
    val fromSlot: Int,
    val toSlot: Int,
) : ServerProt

data class IfSetClickMask(val interfaceId: Int, val componentId: Int, val enabled: Boolean) : ServerProt

data class IfOpenSubActiveGroundItem(
    val interfaceId: Int,
    val componentId: Int,
    val subInterfaceId: Int,
    val overlay: Boolean,
    val tile: Tile,
    val itemId: Int,
    val xteas: IntArray,
) : ServerProt {
    init {
        require(xteas.size == 4) { "xteas must be length 4" }
    }
}

data class IfSetPlayerHeadOther(
    val interfaceId: Int,
    val componentId: Int,
    val key: Int,
    val value: Int,
) : ServerProt

/**
 * Opcode 20, fixed size 6. Client enum `OUTDATED_SET_THEORA_STRING_SOMETHING`.
 *
 * Queues a high priority `T17` pulse event against the interface component
 * identified by [interfaceId]/[componentId], carrying a 16-bit [value].
 * The behaviour behind the `T17` pulse type is dead code in the 727 client.
 */
data class OutdatedSetTheoraString(
    val interfaceId: Int,
    val componentId: Int,
    val value: Int,
) : ServerProt

/**
 * Opcode 96, fixed size 8. Client enum `OUTDATED_INTERFACE_PULSE_EVENT`.
 *
 * Queues a high priority `T13` pulse event against the interface component
 * identified by [interfaceId]/[componentId], carrying a 32-bit [value] that the
 * client reassembles from a low short and a high short.
 * The behaviour behind the `T13` pulse type is dead code in the 727 client.
 */
data class OutdatedInterfacePulseEvent(
    val interfaceId: Int,
    val componentId: Int,
    val value: Int,
) : ServerProt
