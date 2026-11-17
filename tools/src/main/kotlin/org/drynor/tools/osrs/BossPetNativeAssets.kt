package org.drynor.tools.osrs

import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.cache.FileCache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.ItemDefinition
import world.gregs.voidps.cache.definition.data.NPCDefinition
import world.gregs.voidps.cache.definition.decoder.ItemDecoder
import world.gregs.voidps.cache.definition.decoder.NPCDecoder
import world.gregs.voidps.cache.definition.encoder.ItemEncoder
import world.gregs.voidps.cache.definition.encoder.NPCEncoder
import java.nio.file.Files
import java.nio.file.Path

internal object BossPetNativeAssets {
    fun run(cachePath: Path, output: Path, install: Boolean) {
        require(!Files.exists(output))
        val assets = FileCache(cachePath.toString(), writable = false).use { cache ->
            val assets = mutableListOf<InfernoAssetWriter.Asset>()
            // Donor CustomNPCs clones these native bosses and reduces their display scale.
            // Reuse Drynor's installed native rigs for the equivalent boss appearance.
            for ((origin, id, scale, name) in listOf(
                NativePet(13447, 16003, 48, "Nexterminator"),
                NativePet(15454, 16004, 8, "Queen Black Dragonling"),
                NativePet(8528, 16019, 64, "Nomad pet"),
            )) {
                check(cache.data(Index.NPCS, id ushr 7, id and 127) == null)
                val npc = NPCDefinition(id)
                NPCDecoder().readLoop(npc, BufferReader(requireNotNull(cache.data(Index.NPCS, origin ushr 7, origin and 127))))
                npc.id = id
                npc.name = name
                npc.size = if (id == 16004) 2 else 1
                npc.scaleXY = scale
                npc.scaleZ = scale
                npc.combat = 0
                npc.priorityRender = false
                npc.drawMinimapDot = false
                npc.options = arrayOf("Talk-to", "Pick-up", null, null, null, "Examine")
                npc.rawOptions = null
                npc.opcodeOrder = null
                npc.shadowedPayloads = null
                val writer = BufferWriter(65536)
                with(NPCEncoder()) { writer.encode(npc) }
                val bytes = writer.toArray()
                val read = NPCDefinition(id)
                NPCDecoder().readLoop(read, BufferReader(bytes))
                check(read.modelIds.contentEquals(npc.modelIds) && read.renderEmote == npc.renderEmote &&
                    read.scaleXY == scale && read.options.contentEquals(npc.options)) {
                    "NPC $id: models=${read.modelIds?.toList()}/${npc.modelIds?.toList()}, BAS=${read.renderEmote}/${npc.renderEmote}, scale=${read.scaleXY}/$scale, options=${read.options.toList()}/${npc.options.toList()}"
                }
                assets.add(InfernoAssetWriter.Asset(Index.NPCS, id ushr 7, id and 127, bytes))
            }
            val pets = listOf(16003, 16004, 16010, 16011, 16012, 16013, 16014, 16015, 16019)
            for ((offset, npcId) in pets.withIndex()) {
                val id = 30364 + offset
                check(cache.data(Index.ITEMS, id ushr 8, id and 255) == null)
                val npcBytes = assets.firstOrNull { it.index == Index.NPCS && it.archive == (npcId ushr 7) && it.file == (npcId and 127) }?.bytes
                    ?: requireNotNull(cache.data(Index.NPCS, npcId ushr 7, npcId and 127))
                val npc = NPCDefinition(npcId)
                NPCDecoder().readLoop(npc, BufferReader(npcBytes))
                val item = ItemDefinition(id)
                ItemDecoder().readLoop(item, BufferReader(requireNotNull(cache.data(Index.ITEMS, 21512 ushr 8, 21512 and 255))))
                item.id = id
                item.name = if (npcId in 16010..16015) npc.name.substringBefore(' ') + " pet" else npc.name
                item.modelId = npc.modelIds!!.first()
                item.options = arrayOf(null, null, null, null, "Drop")
                item.noteId = -1
                item.notedTemplateId = -1
                item.exchangeable = false
                item.opcodeOrder = null
                item.shadowedPayloads = null
                val writer = BufferWriter(65536)
                with(ItemEncoder()) { writer.encode(item) }
                val bytes = writer.toArray()
                val read = ItemDefinition(id)
                ItemDecoder().readLoop(read, BufferReader(bytes))
                check(read.name == item.name && read.modelId == item.modelId)
                assets.add(InfernoAssetWriter.Asset(Index.ITEMS, id ushr 8, id and 255, bytes))
            }
            for (id in listOf(22130, 22131, 22132, 26640, 20964, 27675, 28011)) {
                val npc = NPCDefinition(id)
                NPCDecoder().readLoop(npc, BufferReader(requireNotNull(cache.data(Index.NPCS, id ushr 7, id and 127))))
                val meta = npc.options.any { it.equals("Metamorphosis", true) }
                npc.options = arrayOf("Talk-to", "Pick-up", if (meta) "Metamorphosis" else null, null, null, "Examine")
                npc.rawOptions = null
                npc.opcodeOrder = null
                npc.shadowedPayloads = null
                val writer = BufferWriter(65536)
                with(NPCEncoder()) { writer.encode(npc) }
                val bytes = writer.toArray()
                val read = NPCDefinition(id)
                NPCDecoder().readLoop(read, BufferReader(bytes))
                check(read.options.contentEquals(npc.options))
                assets.add(InfernoAssetWriter.Asset(Index.NPCS, id ushr 7, id and 127, bytes, replaceExisting = true))
            }
            assets
        }
        Files.createDirectory(output)
        for (asset in assets) Files.write(output.resolve("${asset.index}-${asset.archive}-${asset.file}.bin"), asset.bytes)
        if (install) {
            InfernoAssetWriter.write(cachePath, assets)
            FileCache(cachePath.toString(), writable = false).use { cache ->
                for (asset in assets) check(asset.bytes.contentEquals(cache.data(asset.index, asset.archive, asset.file)))
            }
        }
        println("Verified ${assets.size} native boss-pet assets (installed=$install)")
    }

    private data class NativePet(val origin: Int, val id: Int, val scale: Int, val name: String)
}
