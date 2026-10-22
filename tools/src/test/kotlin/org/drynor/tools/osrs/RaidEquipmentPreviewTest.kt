package org.drynor.tools.osrs

import kotlin.test.*
import world.gregs.voidps.cache.definition.data.ItemDefinition

class RaidEquipmentPreviewTest {
    private val bas=mapOf(50997 to 100,51003 to 101,51006 to 102,51012 to 103,51015 to 104,52323 to 105,52324 to 106,52325 to 107)
    @Test fun equipmentSlotsAndNativeBonuses() {
        val weapon=ItemDefinition(1)
        RaidEquipmentPreview.applyOverrides(52325,weapon,bas)
        assertEquals(3,weapon.wearPos);assertEquals(5,weapon.wearPos2)
        assertEquals(750,weapon.params!![641]);assertEquals(125,weapon.params!![1]);assertEquals(107,weapon.params!![644]);assertEquals(22,weapon.params!![686])
        val defender=ItemDefinition(2)
        RaidEquipmentPreview.applyOverrides(52322,defender,bas)
        assertEquals(5,defender.wearPos);assertEquals(80,defender.params!![641]);assertEquals(70,defender.params!![750])
    }
    @Test fun chargedAndEmptyFormsKeepSameEquipmentRequirements() {
        for((full,empty) in listOf(52323 to 52481,52325 to 52486)) {
            val a=ItemDefinition(1);val b=ItemDefinition(2)
            RaidEquipmentPreview.applyOverrides(full,a,bas);RaidEquipmentPreview.applyOverrides(empty,b,bas)
            assertEquals(a.wearPos,b.wearPos);assertEquals(a.wearPos2,b.wearPos2)
            for(key in listOf(644,686,749,750,751,752))assertEquals(a.params?.get(key),b.params?.get(key))
            assertEquals("Charge",b.options[3]);assertTrue("Check" in b.options)
        }
    }
    @Test fun currentOsrsScytheFormsAndTwistedBowRebalance() {
        val charged=ItemDefinition(1);val empty=ItemDefinition(2)
        RaidEquipmentPreview.applyOverrides(52325,charged,bas);RaidEquipmentPreview.applyOverrides(52486,empty,bas)
        for((key,value) in mapOf(0 to 70,1 to 125,2 to 30,3 to -6,5 to -2,6 to 8,7 to 10,641 to 750,749 to 0,750 to 80,751 to 2,752 to 90))assertEquals(value,charged.params!![key],"charged param $key")
        for((key,value) in mapOf(0 to 50,1 to 75,2 to 10,3 to -6,5 to -2,6 to 6,7 to 0,641 to 500,750 to 80,752 to 90))assertEquals(value,empty.params!![key],"empty param $key")
        val bow=ItemDefinition(3);RaidEquipmentPreview.applyOverrides(50997,bow,bas)
        assertEquals(4,bow.params!![749]);assertEquals(85,bow.params!![750]);assertEquals(0,bow.params!![11])
    }
    @Test fun infernalMaxKeepsInfernalStatsAndCapeSlot() {
        val item=ItemDefinition(1);RaidEquipmentPreview.applyOverrides(51285,item,bas)
        assertEquals(1,item.wearPos);assertEquals(80,item.params!![641]);assertEquals(2,item.params!![11]);assertFalse(item.exchangeable)
    }
    @Test fun armourHidesOnlyRequiredAvatarParts() {
        val helmet=ItemDefinition(1);RaidEquipmentPreview.applyOverrides(52326,helmet,bas)
        assertEquals(0,helmet.wearPos);assertEquals(8,helmet.wearPos2);assertEquals(11,helmet.wearPos3)
        val body=ItemDefinition(2);RaidEquipmentPreview.applyOverrides(52327,body,bas)
        assertEquals(4,body.wearPos);assertEquals(6,body.wearPos2)
        val resource=ItemDefinition(3);RaidEquipmentPreview.applyOverrides(52446,resource,bas)
        assertEquals(-1,resource.wearPos)
    }
}
