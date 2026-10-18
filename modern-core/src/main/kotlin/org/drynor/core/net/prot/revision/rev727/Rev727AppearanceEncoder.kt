package org.drynor.core.net.prot.revision.rev727

import org.drynor.core.update.AppearanceSlot
import org.drynor.core.update.ItemMeshOverride
import org.drynor.core.update.PlayerAppearanceData
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.buffer.write.Writer

/**
 * Revision 727 encoder for the player appearance block.
 *
 * Consumes the semantic [PlayerAppearanceData] entity schema and produces the byte block carried
 * by the appearance update mask. All framing decisions (empty slot as a zero byte, body styles
 * offset by [BODY_STYLE_OFFSET], worn items offset by [WORN_ITEM_OFFSET]) live here rather than in
 * the game modules that populate the schema.
 */
object Rev727AppearanceEncoder {

    /** Body style values are transmitted offset by this so they cannot collide with a zero byte. */
    private const val BODY_STYLE_OFFSET = 0x100

    /** Worn item ids are transmitted offset by this to distinguish them from body styles. */
    private const val WORN_ITEM_OFFSET = 16384

    private const val FLAG_FEMALE = 0x1
    private const val FLAG_NPC_TRANSFORM_SIZE = 0x2
    private const val FLAG_SHOW_SKILL_TOTAL = 0x4
    private const val FLAG_TITLE_BEFORE = 0x40
    private const val FLAG_TITLE_AFTER = 0x80

    private const val MESH_BODY_MODEL_FLAG = 0x1
    private const val MESH_HEAD_MODEL_FLAG = 0x2
    private const val MESH_COLOUR_FLAG = 0x4
    private const val MESH_TEXTURE_FLAG = 0x8

    /** Written into a colour/texture slot nibble when the item defines no value for it. */
    private const val MESH_EMPTY_SLOT = 0xF

    private const val MESH_COLOUR_SLOTS = 4
    private const val MESH_TEXTURE_SLOTS = 2

    private const val INITIAL_BUFFER_SIZE = 512

    /** Encodes [data] into the on-wire appearance block. */
    fun encode(data: PlayerAppearanceData): ByteArray {
        val out = BufferWriter(INITIAL_BUFFER_SIZE)
        out.writeByte(flags(data))
        data.title?.let { out.writePrefixedString(it.text) }
        out.writeByte(data.skullIcon)
        out.writeByte(data.headIcon)
        out.writeByte(if (data.hidden) 1 else 0)

        if (data.transformed) {
            out.writeShort(-1)
            out.writeShort(data.transformedNpcId)
            out.writeByte(0)
        } else {
            for (slot in data.slots) writeSlot(out, slot)
            writeMeshOverrides(out, data.meshOverrides)
        }

        for (colour in data.colours) out.writeByte(colour)
        out.writeShort(data.renderEmote)
        out.writeString(data.displayName)
        out.writeByte(data.combatLevel)
        if (data.showSkillTotal) {
            out.writeShort(data.totalLevel)
        } else {
            out.writeByte(data.summoningCombatLevel)
            out.writeByte(data.pvpCombatLevelThreshold)
        }

        out.writeByte(if (data.transformed) 1 else 0)
        data.npcRenderAnimations?.let { anims ->
            out.writeShort(anims.walk)
            out.writeShort(anims.rotate180)
            out.writeShort(anims.rotate90Right)
            out.writeShort(anims.rotate90Left)
            out.writeByte(anims.special)
        }

        return out.toArray()
    }

    private fun flags(data: PlayerAppearanceData): Int {
        var flags = 0
        if (!data.male) flags = flags or FLAG_FEMALE
        if (data.npcTransformSizeFlag) flags = flags or FLAG_NPC_TRANSFORM_SIZE
        if (data.showSkillTotal) flags = flags or FLAG_SHOW_SKILL_TOTAL
        data.title?.let { flags = flags or if (it.after) FLAG_TITLE_AFTER else FLAG_TITLE_BEFORE }
        return flags
    }

    private fun writeSlot(out: Writer, slot: AppearanceSlot) {
        when (slot) {
            is AppearanceSlot.Empty -> out.writeByte(0)
            is AppearanceSlot.Body -> out.writeShort(BODY_STYLE_OFFSET + slot.style)
            is AppearanceSlot.Worn -> out.writeShort(WORN_ITEM_OFFSET + slot.itemId)
        }
    }

    /**
     * Writes the mesh-override section, back-patching the leading slot bitmask once the set of
     * slots that actually contributed data is known.
     */
    private fun writeMeshOverrides(out: BufferWriter, overrides: List<ItemMeshOverride>) {
        val start = out.position()
        out.writeShort(0)
        var slotHash = 0
        for (override in overrides) {
            if (override.isEmpty) continue
            writeMeshOverride(out, override)
            slotHash = slotHash or (1 shl override.slot)
        }
        val end = out.position()
        out.position(start)
        out.writeShort(slotHash)
        out.position(end)
    }

    private fun writeMeshOverride(out: Writer, override: ItemMeshOverride) {
        var flags = 0
        if (override.bodyModels != null) flags = flags or MESH_BODY_MODEL_FLAG
        if (override.headModels != null) flags = flags or MESH_HEAD_MODEL_FLAG
        if (override.colours != null) flags = flags or MESH_COLOUR_FLAG
        if (override.textures != null) flags = flags or MESH_TEXTURE_FLAG
        out.writeByte(flags)

        override.bodyModels?.let { models ->
            out.writeBigSmart(models.male[0])
            out.writeBigSmart(models.female[0])
            if (models.includeSecondary) {
                out.writeBigSmart(models.male[1])
                out.writeBigSmart(models.female[1])
            }
            if (models.includeTertiary) {
                out.writeBigSmart(models.male[2])
                out.writeBigSmart(models.female[2])
            }
        }

        override.headModels?.let { models ->
            out.writeBigSmart(models.male[0])
            out.writeBigSmart(models.female[0])
            if (models.includeSecondary) {
                out.writeBigSmart(models.male[1])
                out.writeBigSmart(models.female[1])
            }
        }

        override.colours?.let { colours ->
            out.writeShort(slotNibbles(colours.size, MESH_COLOUR_SLOTS))
            for (i in 0 until MESH_COLOUR_SLOTS) {
                if (i < colours.size) out.writeShort(colours[i])
            }
        }

        override.textures?.let { textures ->
            out.writeByte(slotNibbles(textures.size, MESH_TEXTURE_SLOTS))
            for (i in 0 until MESH_TEXTURE_SLOTS) {
                if (i < textures.size) out.writeShort(textures[i])
            }
        }
    }

    /**
     * Packs the per-slot nibble map that precedes a colour or texture list.
     *
     * Slot 0 is implicit, so nibbles are emitted for indices `1 until [slotCount]`: each carries
     * its own index when the item supplies a value there, or [MESH_EMPTY_SLOT] when it does not.
     */
    private fun slotNibbles(valueCount: Int, slotCount: Int): Int {
        var slots = 0
        for (i in 1 until slotCount) {
            val nibble = if (i < valueCount) i else MESH_EMPTY_SLOT
            slots = slots or (nibble shl (i * 4))
        }
        return slots
    }
}
