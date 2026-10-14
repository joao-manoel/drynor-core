package org.darkan.core.cache

import org.darkan.core.EnvVars
import org.darkan.core.model.Bonus
import org.darkan.core.model.Item
import org.darkan.core.model.Vars
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.definition.data.ItemDefinition
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Guards the definition accessors the game content reads against the legacy Java they were ported from
 * (`com.rs.cache.loaders.ItemDefinitions`, `ObjectDefinitions`, `com.rs.lib.game.Item`).
 *
 * The cache-backed checks skip themselves when there is no cache to read, the same way
 * `DefinitionEncoderParityTest` does.
 */
class DefinitionLegacyParityTest {

    @Test
    fun `every item has one bonus per Bonus entry`() {
        cache() ?: return
        val items = Cache.items
        assertTrue(items.size > 1, "expected the cache to hold items")
        var checked = 0
        for (item in items) {
            assertEquals(
                Bonus.entries.size,
                item.getBonuses().size,
                "item ${item.id} (${item.name}) has the wrong number of bonuses"
            )
            checked++
        }
        // The crash this guards: Equipment.getBonus indexes getBonuses() by Bonus ordinal, and
        // MAGIC_STR is 17, so anything shorter than 18 threw on the first equipped item.
        for (bonus in Bonus.entries) {
            items[ABYSSAL_WHIP].getBonuses()[bonus.ordinal]
        }
        assertEquals(items.size, checked)
    }

    @Test
    fun `bonuses follow the legacy param mapping`() {
        cache() ?: return
        for (id in intArrayOf(ABYSSAL_WHIP, RUNE_SCIMITAR, TWO_HANDED_SWORD, GOLD_RING, BRONZE_ARROW)) {
            val definition = Cache.items[id]
            assertEquals(
                legacyBonuses(definition).toList(),
                definition.getBonuses().toList(),
                "item $id (${definition.name}) does not match the legacy bonus mapping"
            )
        }
    }

    @Test
    fun `abyssal whip has its legacy slash attack`() {
        cache() ?: return
        val whip = Cache.items[ABYSSAL_WHIP]
        assertEquals("Abyssal whip", whip.name)
        assertEquals(82, whip.getBonuses()[Bonus.SLASH_ATT.ordinal])
    }

    @Test
    fun `rune scimitar has melee attack and strength bonuses`() {
        cache() ?: return
        val scimitar = Cache.items[RUNE_SCIMITAR]
        assertEquals("Rune scimitar", scimitar.name)
        val bonuses = scimitar.getBonuses()
        assertEquals(scimitar.getParamVal(1), bonuses[Bonus.SLASH_ATT.ordinal])
        assertTrue(bonuses[Bonus.SLASH_ATT.ordinal] > 0, "expected a slash attack bonus")
        // Legacy divides params 641 and 643 by ten.
        assertEquals(scimitar.getParamVal(641) / 10, bonuses[Bonus.MELEE_STR.ordinal])
        assertTrue(bonuses[Bonus.MELEE_STR.ordinal] > 0, "expected a melee strength bonus")
    }

    @Test
    fun `a magic staff carries its magic strength`() {
        cache() ?: return
        val staff = Cache.items.firstOrNull {
            it.getParamVal(685) > 0 && it.name.contains("staff", ignoreCase = true)
        } ?: Cache.items.firstOrNull { it.getParamVal(685) > 0 }
        assertNotNull(staff, "expected at least one staff with a magic strength param")
        assertEquals(staff.getParamVal(685), staff.getBonuses()[Bonus.MAGIC_STR.ordinal])
        assertTrue(staff.getBonuses()[Bonus.MAGIC_STR.ordinal] > 0)
    }

    @Test
    fun `rings and ammo are wearable`() {
        cache() ?: return
        // Legacy only requires a worn model below slot 12, so rings, ammo and auras - which have
        // none in the cache - stay wearable.
        for (id in intArrayOf(GOLD_RING, BRONZE_ARROW)) {
            val definition = Cache.items[id]
            assertTrue(definition.wearPos >= 12, "item $id is not in a slot the exemption covers")
            assertTrue(definition.isWearItem(true), "item $id (${definition.name}) is not wearable by males")
            assertTrue(definition.isWearItem(false), "item $id (${definition.name}) is not wearable by females")
        }
    }

    @Test
    fun `worn items still need a worn model`() {
        cache() ?: return
        val whip = Cache.items[ABYSSAL_WHIP]
        assertTrue(whip.wearPos in 0 until 12)
        assertTrue(whip.isWearItem(true))
        assertFalse(Cache.items[COINS].isWearItem(true), "coins are not wearable")
    }

    @Test
    fun `two handed weapons report the shield equip type`() {
        cache() ?: return
        val sword = Cache.items[TWO_HANDED_SWORD]
        // Legacy isEquipType reads wearPos2/wearPos3, the slots a worn item also occupies; 5 is the
        // shield slot, which is what makes a weapon two-handed.
        assertTrue(sword.isEquipType(5), "${sword.name} is not detected as two-handed")
        assertFalse(Cache.items[ABYSSAL_WHIP].isEquipType(5), "the whip is not two-handed")
    }

    @Test
    fun `merging a stack with no metadata does not throw`() {
        val coins = Item(COINS, 100)
        val more = Item(COINS, 50)
        // ItemsContainer.add passes the incoming item's metadata straight into the merged item, and
        // it is null for every ordinary stack.
        assertNull(more.getMetaData())
        val merged = Item(coins.id, coins.amount + more.amount, more.metadata)
        assertEquals(COINS, merged.id)
        assertEquals(150, merged.amount)
        assertNull(merged.getMetaData())
        assertFalse(merged.containsMetaData())
        // Metadata is still allocated on demand afterwards.
        merged.addMetaData("charges", 3)
        assertEquals(3, merged.getMetaDataI("charges", 0))
        assertTrue(merged.containsMetaData())
        merged.deleteMetaData()
        assertNull(merged.getMetaData())
    }

    @Test
    fun `object definitions resolve varbit transforms`() {
        cache() ?: return
        val objects = Cache.objects
        val varbits = Cache.varbits
        var tested = 0
        for (definition in objects) {
            if (tested >= TRANSFORM_SAMPLE) {
                break
            }
            val transforms = definition.transforms ?: continue
            val varbit = definition.varbit
            if (varbit < 0 || varbit >= varbits.size || transforms.size < 3) {
                continue
            }
            val bits = varbits[varbit]
            val capacity = 1 shl (bits.endBit - bits.startBit + 1)
            val fallback = transforms[transforms.size - 1]
            // Index 1 upwards, so the value differs from the 0 an unset var reads as.
            val index = (1 until transforms.size - 1).firstOrNull {
                it < capacity && transforms[it] >= 0 && transforms[it] < objects.size &&
                    transforms[it] != transforms[0] && transforms[it] != fallback
            } ?: continue

            val unset = Vars().initWithoutSession()
            val set = Vars().initWithoutSession()
            if (runCatching { set.setVarBit(varbit, index) }.isFailure) {
                continue
            }
            if (set.getVarBit(varbit) != index) {
                continue
            }

            val resolved = ObjectDefinitions.getDefs(definition.id, set)
            assertEquals(
                transforms[index],
                resolved.id,
                "object ${definition.id} did not transform through varbit $varbit = $index"
            )
            val base = ObjectDefinitions.getDefs(definition.id, unset)
            assertTrue(
                base.id != resolved.id,
                "object ${definition.id} resolved to the same definition for both var values"
            )
            // Without vars, legacy falls back to the last transform entry.
            assertEquals(fallback, definition.getIdForPlayer(null))
            // A fallback of -1 means "not shown"; getDefs keeps the base definition rather than
            // dereferencing it, since every caller uses the result unconditionally.
            val expected = if (fallback in objects.indices) fallback else definition.id
            assertEquals(expected, ObjectDefinitions.getDefs(definition.id, null).id)
            tested++
        }
        assertTrue(tested > 0, "found no varbit-transformed object to test")
    }

    @Test
    fun `npc bonuses follow the legacy param mapping`() {
        cache() ?: return
        var strengths = 0
        var delays = 0
        for (definition in Cache.npcs) {
            for (index in 0..9) {
                assertEquals(
                    definition.getParamVal(index),
                    definition.getBonus(Bonus.entries[index]),
                    "npc ${definition.id} attack/defence bonus $index"
                )
            }
            // The strength bonuses are their own params in tenths, not the ordinal-numbered ones -
            // MELEE_STR's ordinal 14 is the attack delay param, which is what the port used to read.
            assertEquals(definition.getParamVal(641) / 10, definition.getBonus(Bonus.MELEE_STR))
            assertEquals(definition.getParamVal(643) / 10, definition.getBonus(Bonus.RANGE_STR))
            assertEquals(definition.getParamVal(965) / 10, definition.getBonus(Bonus.MAGIC_STR))
            // NPCs have no summoning defence, absorb or prayer bonuses at all.
            for (bonus in listOf(Bonus.SUMM_DEF, Bonus.ABSORB_MELEE, Bonus.ABSORB_MAGIC, Bonus.ABSORB_RANGE, Bonus.PRAYER)) {
                assertEquals(0, definition.getBonus(bonus), "npc ${definition.id} $bonus")
            }
            if (definition.getBonus(Bonus.MELEE_STR) > 0) {
                strengths++
            }
            val delay = definition.getParamVal(14)
            if (delay > 0) {
                assertEquals(delay, definition.getAttackDelay())
                delays++
            } else {
                assertEquals(4, definition.getAttackDelay())
            }
        }
        assertTrue(strengths > 0, "expected some npc to have a melee strength bonus")
        assertTrue(delays > 0, "expected some npc to declare an attack delay")
    }

    /** The legacy `ItemDefinitions.parseBonuses()` mapping, written out independently. */
    private fun legacyBonuses(definition: ItemDefinition): IntArray {
        val bonuses = IntArray(18)
        bonuses[Bonus.STAB_ATT.ordinal] = definition.getParamVal(0)
        bonuses[Bonus.SLASH_ATT.ordinal] = definition.getParamVal(1)
        bonuses[Bonus.CRUSH_ATT.ordinal] = definition.getParamVal(2)
        bonuses[Bonus.MAGIC_ATT.ordinal] = definition.getParamVal(3)
        bonuses[Bonus.RANGE_ATT.ordinal] = definition.getParamVal(4)
        bonuses[Bonus.STAB_DEF.ordinal] = definition.getParamVal(5)
        bonuses[Bonus.SLASH_DEF.ordinal] = definition.getParamVal(6)
        bonuses[Bonus.CRUSH_DEF.ordinal] = definition.getParamVal(7)
        bonuses[Bonus.MAGIC_DEF.ordinal] = definition.getParamVal(8)
        bonuses[Bonus.RANGE_DEF.ordinal] = definition.getParamVal(9)
        bonuses[Bonus.SUMM_DEF.ordinal] = definition.getParamVal(417)
        bonuses[Bonus.PRAYER.ordinal] = definition.getParamVal(11)
        bonuses[Bonus.ABSORB_MELEE.ordinal] = definition.getParamVal(967)
        bonuses[Bonus.ABSORB_RANGE.ordinal] = definition.getParamVal(968)
        bonuses[Bonus.ABSORB_MAGIC.ordinal] = definition.getParamVal(969)
        bonuses[Bonus.MELEE_STR.ordinal] = definition.getParamVal(641) / 10
        bonuses[Bonus.RANGE_STR.ordinal] = definition.getParamVal(643) / 10
        bonuses[Bonus.MAGIC_STR.ordinal] = definition.getParamVal(685)
        return bonuses
    }

    private fun cache(): Cache? {
        val directory = File(EnvVars.cachePath)
        if (!directory.isDirectory) {
            println("Skipping definition legacy parity; no cache at ${directory.absolutePath}")
            return null
        }
        return Cache.get()
    }

    private companion object {
        private const val COINS = 995
        private const val TWO_HANDED_SWORD = 1307
        private const val RUNE_SCIMITAR = 1333
        private const val GOLD_RING = 1635
        private const val BRONZE_ARROW = 882
        private const val ABYSSAL_WHIP = 4151
        private const val TRANSFORM_SAMPLE = 5
    }
}
