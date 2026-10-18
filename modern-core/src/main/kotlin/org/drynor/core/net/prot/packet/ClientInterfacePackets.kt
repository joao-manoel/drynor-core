/**
 * Interface, component and dialog packets sent by the client.
 */
package org.drynor.core.net.prot.packet

import org.drynor.core.net.prot.ClientProt

// Interface Interactions
data class IfButton(val opNum: Int, val interfaceId: Int, val componentId: Int, val slotId: Int, val itemId: Int) : ClientProt

data class IfOnIf(val fromInter: Int, val fromComp: Int, val fromSlot: Int, val fromItemId: Int, val toInter: Int, val toComp: Int, val toSlot: Int, val toItemId: Int) : ClientProt

data class IfOnObject(val interfaceId: Int, val componentId: Int, val slotId: Int, val itemId: Int, val objectId: Int, val x: Int, val y: Int, val forceRun: Boolean) : ClientProt

data class IfOnGroundItem(val interfaceId: Int, val componentId: Int, val slotId: Int, val itemIdContainer: Int, val itemId: Int, val x: Int, val y: Int, val forceRun: Boolean) : ClientProt

data class IfOnNpc(val interfaceId: Int, val componentId: Int, val slotId: Int, val itemId: Int, val npcIndex: Int, val forceRun: Boolean) : ClientProt

data class IfOnPlayer(val interfaceId: Int, val componentId: Int, val slotId: Int, val itemId: Int, val playerIndex: Int, val forceRun: Boolean) : ClientProt

data class IfOnTile(val interfaceId: Int, val componentId: Int, val slotId: Int, val itemId: Int, val x: Int, val y: Int) : ClientProt

data class IfContinue(val interfaceId: Int, val componentId: Int, val slotId: Int) : ClientProt

data class IfDragOntoIf(val fromInter: Int, val fromComp: Int, val fromSlot: Int, val fromItemId: Int, val toInter: Int, val toComp: Int, val toSlot: Int, val toItemId: Int) : ClientProt

data class CloseInterface(val dummy: Int = 0) : ClientProt

// Dialog Interactions
@JvmInline value class ResumeItemSelect(val itemId: Int) : ClientProt

@JvmInline value class ResumeTextDialog(val text: String) : ClientProt

@JvmInline value class ResumeNameDialog(val name: String) : ClientProt

@JvmInline value class ResumeHSLDialog(val data: Int) : ClientProt

@JvmInline value class ResumeCountDialog(val count: Int) : ClientProt

@JvmInline value class ResumeClanForumQFCDialog(val forumQfc: String) : ClientProt

@JvmInline value class UnkCs2StringResponse(val str: String) : ClientProt
