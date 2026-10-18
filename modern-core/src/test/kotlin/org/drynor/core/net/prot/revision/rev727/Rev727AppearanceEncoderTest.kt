package org.drynor.core.net.prot.revision.rev727

import org.drynor.core.update.AppearanceSlot
import org.drynor.core.update.AppearanceTitle
import org.drynor.core.update.BodyModelOverride
import org.drynor.core.update.HeadModelOverride
import org.drynor.core.update.ItemMeshOverride
import org.drynor.core.update.NpcRenderAnimations
import org.drynor.core.update.PlayerAppearanceData
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Byte-level tests for the revision 727 appearance block.
 *
 * The appearance block has no length prefix of its own — it is embedded in the player update
 * stream — so a wrong byte count silently corrupts every player that follows in the same packet.
 * These expectations are written out literally rather than derived from the encoder.
 */
class Rev727AppearanceEncoderTest {

    private fun baseData(
        slots: List<AppearanceSlot> = List(13) { AppearanceSlot.Empty },
        meshOverrides: List<ItemMeshOverride> = emptyList(),
        male: Boolean = true,
        title: AppearanceTitle? = null,
        showSkillTotal: Boolean = false,
        transformedNpcId: Int = -1,
        npcRenderAnimations: NpcRenderAnimations? = null,
        npcTransformSizeFlag: Boolean = false,
    ) = PlayerAppearanceData(
        male = male,
        npcTransformSizeFlag = npcTransformSizeFlag,
        showSkillTotal = showSkillTotal,
        title = title,
        skullIcon = -1,
        headIcon = -1,
        hidden = false,
        transformedNpcId = transformedNpcId,
        slots = slots,
        meshOverrides = meshOverrides,
        colours = IntArray(10),
        renderEmote = 1426,
        displayName = "Test",
        combatLevel = 3,
        totalLevel = 32,
        summoningCombatLevel = 0,
        pvpCombatLevelThreshold = 0,
        npcRenderAnimations = npcRenderAnimations,
    )

    /** Trailer shared by every case: colours, BAS, name, then the level footer. */
    private fun trailer(showSkillTotal: Boolean = false, transformed: Boolean = false) = buildList {
        repeat(10) { add(0x00) }                       // colours
        addAll(listOf(0x05, 0x92))                     // renderEmote 1426
        addAll("Test".map { it.code })                 // display name
        add(0x00)                                      // string terminator
        add(0x03)                                      // combat level
        if (showSkillTotal) addAll(listOf(0x00, 0x20)) // total level 32
        else addAll(listOf(0x00, 0x00))                // summoning level, pvp threshold
        add(if (transformed) 0x01 else 0x00)
    }

    private fun ByteArray.toInts() = map { it.toInt() and 0xFF }

    @Test
    fun `an empty male appearance encodes header, slots, mesh bitmask and trailer`() {
        val encoded = Rev727AppearanceEncoder.encode(baseData())
        val expected = buildList {
            add(0x00)                 // flags: male, no npc flag, no skill total, no title
            add(0xFF)                 // skull icon -1
            add(0xFF)                 // head icon -1
            add(0x00)                 // not hidden
            repeat(13) { add(0x00) }  // 13 empty slots
            addAll(listOf(0x00, 0x00))// mesh override bitmask, no overrides
            addAll(trailer())
        }
        assertContentEquals(expected.map { it.toByte() }, encoded.toList())
    }

    @Test
    fun `body slots are offset by 0x100 and worn slots by 16384`() {
        val slots = listOf(AppearanceSlot.Body(5), AppearanceSlot.Worn(1038)) +
            List(11) { AppearanceSlot.Empty }
        val encoded = Rev727AppearanceEncoder.encode(baseData(slots = slots)).toInts()
        // after the 4 header bytes: 0x0105 then 16384 + 1038 = 17422 = 0x440E
        assertEquals(listOf(0x01, 0x05, 0x44, 0x0E), encoded.subList(4, 8))
        assertEquals(List(11) { 0x00 }, encoded.subList(8, 19))
    }

    @Test
    fun `the female flag is set and a title is length-prefixed after the flags byte`() {
        val encoded = Rev727AppearanceEncoder.encode(
            baseData(male = false, title = AppearanceTitle("Sir", after = false)),
        ).toInts()
        // 0x1 female | 0x40 title-before
        assertEquals(0x41, encoded[0])
        // writePrefixedString: leading 0, chars, trailing 0
        assertEquals(listOf(0x00, 'S'.code, 'i'.code, 'r'.code, 0x00), encoded.subList(1, 6))
    }

    @Test
    fun `a title rendered after the name uses the high flag bit`() {
        val encoded = Rev727AppearanceEncoder.encode(
            baseData(title = AppearanceTitle("the Brave", after = true)),
        ).toInts()
        assertEquals(0x80, encoded[0])
    }

    @Test
    fun `a transformed player replaces the slots with the npc id and appends render animations`() {
        val anims = NpcRenderAnimations(walk = 819, rotate180 = 820, rotate90Right = 821, rotate90Left = 822, special = 5)
        val encoded = Rev727AppearanceEncoder.encode(
            baseData(transformedNpcId = 2234, npcRenderAnimations = anims, npcTransformSizeFlag = true),
        ).toInts()
        val expected = buildList {
            add(0x02)                       // npc transform size flag
            add(0xFF)                       // skull icon -1
            add(0xFF)                       // head icon -1
            add(0x00)                       // not hidden
            addAll(listOf(0xFF, 0xFF))      // -1 marker
            addAll(listOf(0x08, 0xBA))      // npc id 2234
            add(0x00)
            addAll(trailer(transformed = true))
            addAll(listOf(0x03, 0x33))      // walk 819
            addAll(listOf(0x03, 0x34))      // rotate180 820
            addAll(listOf(0x03, 0x35))      // rotate90Right 821
            addAll(listOf(0x03, 0x36))      // rotate90Left 822
            add(0x05)                       // special byte
        }
        assertEquals(expected, encoded)
    }

    @Test
    fun `showing the skill total swaps the level footer for the total level`() {
        val encoded = Rev727AppearanceEncoder.encode(baseData(showSkillTotal = true)).toInts()
        assertEquals(0x04, encoded[0])
        val expected = buildList {
            add(0x00); add(0xFF); add(0xFF); add(0x00)
            repeat(13) { add(0x00) }
            addAll(listOf(0x00, 0x00))
            addAll(trailer(showSkillTotal = true))
        }
        // only the flags byte differs from the plain trailer layout
        assertEquals(expected.drop(1), encoded.drop(1))
    }

    @Test
    fun `mesh overrides back-patch a slot bitmask and are skipped when empty`() {
        val overrides = listOf(
            ItemMeshOverride(slot = 3),                                  // contributes nothing
            ItemMeshOverride(slot = 5, colours = intArrayOf(11, 22)),
        )
        val encoded = Rev727AppearanceEncoder.encode(baseData(meshOverrides = overrides)).toInts()
        val meshStart = 4 + 13
        // only slot 5 contributed, so the bitmask is 1 shl 5
        assertEquals(listOf(0x00, 0x20), encoded.subList(meshStart, meshStart + 2))
        val body = encoded.subList(meshStart + 2, meshStart + 2 + 7)
        assertEquals(
            listOf(
                0x04,        // colour flag only
                0xFF, 0x10,  // nibble map: slot 1 present (1), slots 2 and 3 empty (0xF each)
                0x00, 0x0B,  // colour 11
                0x00, 0x16,  // colour 22
            ),
            body,
        )
    }

    @Test
    fun `model overrides emit secondary and tertiary pairs only when the item defines them`() {
        val override = ItemMeshOverride(
            slot = 0,
            bodyModels = BodyModelOverride(
                male = intArrayOf(1, 2, 3),
                female = intArrayOf(4, 5, 6),
                includeSecondary = true,
                includeTertiary = false,
            ),
            headModels = HeadModelOverride(
                male = intArrayOf(7, 8),
                female = intArrayOf(9, 10),
                includeSecondary = false,
            ),
        )
        val encoded = Rev727AppearanceEncoder.encode(baseData(meshOverrides = listOf(override))).toInts()
        val meshStart = 4 + 13
        assertEquals(listOf(0x00, 0x01), encoded.subList(meshStart, meshStart + 2))
        assertEquals(
            listOf(
                0x03,        // body + head flags
                0x00, 0x01,  // male primary body
                0x00, 0x04,  // female primary body
                0x00, 0x02,  // male secondary body
                0x00, 0x05,  // female secondary body
                0x00, 0x07,  // male primary head
                0x00, 0x09,  // female primary head
            ),
            encoded.subList(meshStart + 2, meshStart + 2 + 13),
        )
    }

    @Test
    fun `encoding is deterministic for equal input`() {
        assertContentEquals(
            Rev727AppearanceEncoder.encode(baseData()),
            Rev727AppearanceEncoder.encode(baseData()),
        )
    }
}
