package org.drynor.tools.osrs

import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.cache.definition.data.ItemDefinition
import world.gregs.voidps.cache.definition.data.ModelDefinition
import world.gregs.voidps.cache.definition.decoder.ItemDecoder
import world.gregs.voidps.cache.definition.decoder.ModelDecoder
import world.gregs.voidps.cache.definition.encoder.ItemEncoder
import world.gregs.voidps.cache.definition.encoder.ModelEncoder
import kotlin.test.*

class ZulrahItemsPreviewTest {
    @Test fun nativeLinksAndHeadModelsRetainTheirPhysicalDonorAddresses() {
        val raw=byteArrayOf(90,0,12,91,0,13,92,0,14,93,0,15,97,0,21,98,3,31,100,0,22,0,5,139.toByte(),0,23,140.toByte(),0,24,0)
        val item=OsrsItemConverter.decode(30001,raw)
        assertEquals(200012,item.primaryMaleDialogueHead); assertEquals(200013,item.primaryFemaleDialogueHead)
        assertEquals(200014,item.secondaryMaleDialogueHead); assertEquals(200015,item.secondaryFemaleDialogueHead)
        assertEquals(30021,item.noteId); assertEquals(30799,item.notedTemplateId)
        assertEquals(30022,item.stackIds!![0]); assertEquals(5,item.stackAmounts!![0])
        assertEquals(30023,item.lendId); assertEquals(30024,item.lendTemplateId)
        val bytes=BufferWriter(2048).also { with(ItemEncoder()) { it.encode(item) } }.toArray()
        val decoded=ItemDefinition(item.id); ItemDecoder().readLoop(decoded,BufferReader(bytes)); assertEquals(item,decoded)
    }

    @Test fun shiftClickIndexDoesNotBecomeTeamAndDonorIgnoredOffsetsNeedAnExplicitRecord() {
        val raw=byteArrayOf(42,(-2).toByte(),25,0,12,6,0)
        assertFailsWith<IllegalArgumentException> { OsrsItemConverter.decode(1,raw) }
        val ignored=linkedMapOf<Int,Int>()
        val item=OsrsItemConverter.decode(1,raw,ignoredPresentation=ignored)
        assertEquals(0,item.team); assertEquals(mapOf(25 to 6),ignored)
        assertEquals(0,item.femaleWieldY)
    }

    @Test fun translatedEquipmentUsesNativeStyleSpecialStatsAndRequirements() {
        val pipe=ItemDefinition(30026,params=mapOf(451 to "Check"))
        ZulrahItemsPreview.applyNativeOverrides(42926,pipe)
        assertEquals(3,pipe.wearPos); assertEquals(5,pipe.wearPos2)
        assertEquals(13,pipe.getParamVal(686)); assertEquals(1,pipe.getParamVal(687))
        assertTrue(pipe.hasSpecialBar()); assertEquals("Check",pipe.params!![528])
        assertEquals(400,pipe.getParamVal(643)); assertEquals(60,pipe.getParamVal(4))
        assertEquals(mapOf(4 to 75),pipe.getWearingSkillRequiriments())
        val helm=ItemDefinition(30031); ZulrahItemsPreview.applyNativeOverrides(42931,helm)
        assertEquals(0,helm.wearPos); assertEquals(8,helm.wearPos2); assertEquals(11,helm.wearPos3)
        assertEquals(mapOf(1 to 75),helm.getWearingSkillRequiriments())
        val empty=ItemDefinition(30024); ZulrahItemsPreview.applyNativeOverrides(42924,empty)
        assertEquals(-1,empty.wearPos)
    }

    @Test fun staffAndTridentOverridesPreserveSourceCombatBonuses() {
        val trident=ItemDefinition(30045)
        ZulrahItemsPreview.applyNativeOverrides(42899,trident)
        assertEquals(3,trident.wearPos); assertEquals(25,trident.getParamVal(3))
        assertEquals(mapOf(6 to 75),trident.getWearingSkillRequiriments())
        val staff=ItemDefinition(30050)
        ZulrahItemsPreview.applyNativeOverrides(42904,staff)
        assertEquals(15,staff.getParamVal(685)); assertEquals(720,staff.getParamVal(641))
        assertEquals(55,staff.getParamVal(0)); assertEquals(70,staff.getParamVal(1))
        assertEquals(25,staff.getParamVal(3)); assertEquals(17,staff.getParamVal(8))
        assertEquals(mapOf(6 to 75,0 to 75),staff.getWearingSkillRequiriments())
    }

    @Test fun rareDropDragonHastaUsesDonorWieldBonusesAndRequirement() {
        val hasta=ItemDefinition(30054)
        ZulrahItemsPreview.applyNativeOverrides(52731,hasta)
        assertEquals(3,hasta.wearPos); assertEquals(55,hasta.getParamVal(0))
        assertEquals(55,hasta.getParamVal(1)); assertEquals(55,hasta.getParamVal(2))
        assertEquals(-15,hasta.getParamVal(5)); assertEquals(-12,hasta.getParamVal(7))
        assertEquals(600,hasta.getParamVal(641)); assertEquals(4,hasta.getParamVal(14))
        assertEquals(mapOf(0 to 60),hasta.getWearingSkillRequiriments()); assertTrue(hasta.hasSpecialBar())
    }

    @Test fun wornModelTranslationsRemainExactBelowNativeByteGranularityAndPreserveInput() {
        val source=ModelDefinition.EMPTY.copy(id=100,version=12,newFormat=false,vertexCount=1,
            vertexX=intArrayOf(4),vertexY=intArrayOf(8),vertexZ=intArrayOf(12))
        val converted=ZulrahItemsPreview.translateWorn(source,101,-10,35,-20)
        val bytes=ModelEncoder.encode(converted); val decoded=ModelDecoder.read(101,bytes)
        assertContentEquals(intArrayOf(-6),decoded.vertexX); assertContentEquals(intArrayOf(43),decoded.vertexY)
        assertContentEquals(intArrayOf(-8),decoded.vertexZ); assertEquals(13,decoded.version)
        assertContentEquals(intArrayOf(4),source.vertexX); assertContentEquals(intArrayOf(8),source.vertexY)
    }
}
