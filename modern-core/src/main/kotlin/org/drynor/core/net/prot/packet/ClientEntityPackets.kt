/**
 * Movement and entity interaction packets sent by the client.
 */
package org.drynor.core.net.prot.packet

import org.drynor.core.net.prot.ClientProt
import world.gregs.voidps.type.Tile

// Entity Interactions
data class OpPlayer(val opNum: Int, val playerIndex: Int, val forceRun: Boolean) : ClientProt

data class OpNpc(val opNum: Int, val npcIndex: Int, val forceRun: Boolean) : ClientProt

data class OpObj(val opNum: Int, val objectId: Int, val x: Int, val y: Int, val forceRun: Boolean) : ClientProt

data class OpGroundItem(val opNum: Int, val itemId: Int, val x: Int, val y: Int, val forceRun: Boolean) : ClientProt

data class Walk(val x: Int, val y: Int, val forceRun: Boolean, val minimap: Boolean) : ClientProt

@JvmInline value class WorldMapClick(val tile: Tile) : ClientProt

data class AltWalk(val x: Int, val y: Int) : ClientProt
