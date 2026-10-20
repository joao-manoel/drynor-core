package org.drynor.tools.osrs

import org.drynor.tools.rs3port.SkeletonCache
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.cache.definition.data.NPCDefinition
import world.gregs.voidps.cache.definition.decoder.NPCDecoder
import world.gregs.voidps.cache.definition.encoder.NPCEncoder
import kotlin.test.*

class ZulrahActorPreviewTest {
    @Test fun existingOrDuplicateAssetAddressesAreRejected() {
        val occupied = object : Cache by SkeletonCache(emptyMap()) {
            override fun files(index: Int,archive: Int)=if(index==Index.NPCS && archive==172) intArrayOf(27) else intArrayOf()
        }
        val existing=InfernoAssetWriter.Asset(Index.NPCS,172,27,byteArrayOf(0))
        assertFailsWith<IllegalArgumentException> { ZulrahActorPreview.validateFreshAssets(occupied,listOf(existing)) }
        val free=existing.copy(file=28)
        ZulrahActorPreview.validateFreshAssets(occupied,listOf(free))
        assertFailsWith<IllegalArgumentException> { ZulrahActorPreview.validateFreshAssets(occupied,listOf(free,free)) }
        assertFailsWith<IllegalArgumentException> { ZulrahActorPreview.validateFreshAssets(occupied,listOf(existing.copy(replaceExisting=true))) }
    }

    @Test fun formModelAndIdleReferencesSurviveNativeNpcEncoding() {
        val raw=byteArrayOf(1,1,56,72,2,90,117,108,114,97,104,0,12,5,13,19,206.toByte(),0)
        val converted=OsrsNpcConverter.convert(29000,raw,mapOf(214408 to 240001),mapOf(25070 to 29001),6000)
        assertEquals(29001,converted.bas.standAnimation)
        val bytes=BufferWriter(1024).also { with(NPCEncoder()) { it.encode(converted.npc) } }.toArray()
        val native=NPCDefinition(29000)
        NPCDecoder().readLoop(native,BufferReader(bytes))
        assertEquals("Zulrah",native.name);assertEquals(5,native.size);assertEquals(6000,native.renderEmote)
        assertContentEquals(intArrayOf(240001),native.modelIds)
    }
}
