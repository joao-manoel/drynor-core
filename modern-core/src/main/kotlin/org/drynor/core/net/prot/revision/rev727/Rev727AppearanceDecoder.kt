package org.drynor.core.net.prot.revision.rev727

import org.drynor.core.net.prot.update.AppearanceLayout
import org.drynor.core.net.prot.update.WornModelLayout
import org.drynor.core.update.AppearanceSlot
import org.drynor.core.update.AppearanceTitle
import org.drynor.core.update.BodyModelOverride
import org.drynor.core.update.HeadModelOverride
import org.drynor.core.update.ItemMeshOverride
import org.drynor.core.update.NpcRenderAnimations
import org.drynor.core.update.PlayerAppearanceData
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.read.Reader

object Rev727AppearanceDecoder {

    private const val BODY_STYLE_OFFSET = 0x100
    private const val WORN_ITEM_OFFSET = 16384
    private const val TRANSFORM_MARKER = 0xffff
    private const val NOT_TRANSMITTED = 1
    private const val CUSTOMISABLE = 0

    private const val FLAG_FEMALE = 0x1
    private const val FLAG_NPC_TRANSFORM_SIZE = 0x2
    private const val FLAG_SHOW_SKILL_TOTAL = 0x4
    private const val FLAG_TITLE_BEFORE = 0x40
    private const val FLAG_TITLE_AFTER = 0x80

    private const val MESH_BODY_MODEL_FLAG = 0x1
    private const val MESH_HEAD_MODEL_FLAG = 0x2
    private const val MESH_COLOUR_FLAG = 0x4
    private const val MESH_TEXTURE_FLAG = 0x8
    private const val MESH_EMPTY_SLOT = 0xF
    private const val MESH_COLOUR_SLOTS = 4
    private const val MESH_TEXTURE_SLOTS = 2
    private const val UNSENT_MODEL = -1

    val DEFAULT_LAYOUT = AppearanceLayout(
        customizableObjSlots = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 0),
        wornModels = { WornModelLayout.PRIMARY_ONLY },
    )

    fun decode(data: ByteArray, layout: AppearanceLayout = DEFAULT_LAYOUT): PlayerAppearanceData {
        val reader = BufferReader(data)
        val flags = reader.readUnsignedByte()
        val title = when {
            (flags and FLAG_TITLE_BEFORE) != 0 -> AppearanceTitle(reader.readPrefixedString(), after = false)
            (flags and FLAG_TITLE_AFTER) != 0 -> AppearanceTitle(reader.readPrefixedString(), after = true)
            else -> null
        }
        val skullIcon = reader.readByte()
        val headIcon = reader.readByte()
        val hidden = reader.readUnsignedByte() == 1

        val slotLayout = layout.customizableObjSlots
        val slots = ArrayList<AppearanceSlot>(slotLayout.size)
        val wornItems = arrayOfNulls<Int>(slotLayout.size)
        var transformedNpcId = -1
        for (slot in slotLayout.indices) {
            if (slotLayout[slot] == NOT_TRANSMITTED) continue
            val high = reader.readUnsignedByte()
            if (high == 0) {
                slots += AppearanceSlot.Empty
                continue
            }
            val value = (high shl 8) or reader.readUnsignedByte()
            if (slot == 0 && value == TRANSFORM_MARKER) {
                transformedNpcId = reader.readUnsignedShort()
                reader.readUnsignedByte()
                break
            }
            if (value >= WORN_ITEM_OFFSET) {
                val itemId = value - WORN_ITEM_OFFSET
                wornItems[slot] = itemId
                slots += AppearanceSlot.Worn(itemId)
            } else {
                slots += AppearanceSlot.Body(value - BODY_STYLE_OFFSET)
            }
        }

        val meshOverrides = ArrayList<ItemMeshOverride>()
        if (transformedNpcId == -1) {
            val overrideMask = reader.readUnsignedShort()
            var transmittedSlot = 0
            for (slot in slotLayout.indices) {
                if (slotLayout[slot] != CUSTOMISABLE) continue
                if ((overrideMask and (1 shl transmittedSlot)) != 0) {
                    val itemId = wornItems[slot] ?: error("Mesh override for empty appearance slot $slot")
                    meshOverrides += readMeshOverride(reader, transmittedSlot, layout.wornModels(itemId))
                }
                transmittedSlot++
            }
        }

        val colours = IntArray(PlayerAppearanceData.COLOUR_COUNT) { reader.readUnsignedByte() }
        val renderEmote = reader.readShort()
        val displayName = reader.readString()
        val combatLevel = reader.readUnsignedByte()
        val showSkillTotal = (flags and FLAG_SHOW_SKILL_TOTAL) != 0
        var totalLevel = 0
        var summoningCombatLevel = 0
        var pvpCombatLevelThreshold = 0
        if (showSkillTotal) {
            totalLevel = reader.readUnsignedShort()
        } else {
            summoningCombatLevel = reader.readUnsignedByte()
            pvpCombatLevelThreshold = reader.readUnsignedByte().let { if (it == 0xff) -1 else it }
        }

        val npcRenderAnimations = if (reader.readUnsignedByte() == 1) {
            NpcRenderAnimations(
                walk = reader.readShort(),
                rotate180 = reader.readShort(),
                rotate90Right = reader.readShort(),
                rotate90Left = reader.readShort(),
                special = reader.readUnsignedByte(),
            )
        } else {
            null
        }
        check(reader.remaining == 0) { "Appearance block has ${reader.remaining} trailing bytes" }

        return PlayerAppearanceData(
            male = (flags and FLAG_FEMALE) == 0,
            npcTransformSizeFlag = (flags and FLAG_NPC_TRANSFORM_SIZE) != 0,
            showSkillTotal = showSkillTotal,
            title = title,
            skullIcon = skullIcon,
            headIcon = headIcon,
            hidden = hidden,
            transformedNpcId = transformedNpcId,
            slots = slots,
            meshOverrides = meshOverrides,
            colours = colours,
            renderEmote = renderEmote,
            displayName = displayName,
            combatLevel = combatLevel,
            totalLevel = totalLevel,
            summoningCombatLevel = summoningCombatLevel,
            pvpCombatLevelThreshold = pvpCombatLevelThreshold,
            npcRenderAnimations = npcRenderAnimations,
        )
    }

    private fun readMeshOverride(reader: Reader, slot: Int, models: WornModelLayout): ItemMeshOverride {
        val flags = reader.readUnsignedByte()
        val body = if ((flags and MESH_BODY_MODEL_FLAG) != 0) {
            val male = IntArray(3) { UNSENT_MODEL }
            val female = IntArray(3) { UNSENT_MODEL }
            readModelPair(reader, male, female, 0)
            if (models.secondaryBody) readModelPair(reader, male, female, 1)
            if (models.tertiaryBody) readModelPair(reader, male, female, 2)
            BodyModelOverride(male, female, includeSecondary = models.secondaryBody, includeTertiary = models.tertiaryBody)
        } else {
            null
        }
        val head = if ((flags and MESH_HEAD_MODEL_FLAG) != 0) {
            val male = IntArray(2) { UNSENT_MODEL }
            val female = IntArray(2) { UNSENT_MODEL }
            readModelPair(reader, male, female, 0)
            if (models.secondaryHead) readModelPair(reader, male, female, 1)
            HeadModelOverride(male, female, includeSecondary = models.secondaryHead)
        } else {
            null
        }
        val colours = if ((flags and MESH_COLOUR_FLAG) != 0) {
            readSlotValues(reader, reader.readUnsignedShort(), MESH_COLOUR_SLOTS)
        } else {
            null
        }
        val textures = if ((flags and MESH_TEXTURE_FLAG) != 0) {
            readSlotValues(reader, reader.readUnsignedByte(), MESH_TEXTURE_SLOTS)
        } else {
            null
        }
        return ItemMeshOverride(slot = slot, bodyModels = body, headModels = head, colours = colours, textures = textures)
    }

    private fun readModelPair(reader: Reader, male: IntArray, female: IntArray, index: Int) {
        male[index] = reader.readBigSmart()
        female[index] = reader.readBigSmart()
    }

    private fun readSlotValues(reader: Reader, nibbles: Int, slotCount: Int): IntArray {
        val values = ArrayList<Int>(slotCount)
        for (i in 0 until slotCount) {
            if (((nibbles shr (i * 4)) and 0xf) != MESH_EMPTY_SLOT) values += reader.readUnsignedShort()
        }
        return values.toIntArray()
    }
}
