package org.darkan.core.update

/**
 * Semantic description of a player's appearance block.
 *
 * This is the entity-schema half of the appearance update: it carries *what* the client should
 * render, with none of the on-wire framing. A revision encoder turns a [PlayerAppearanceData]
 * into the byte block carried by [PlayerAppearance].
 *
 * Game modules decide the values (which equipment slot wins, whether a hat hides hair, which
 * title text applies); the revision encoder decides the bytes.
 */

/**
 * The value of a single body/equipment slot in the appearance block.
 *
 * The three cases map one-to-one onto the three ways the client reads a slot: a zero byte for an
 * empty slot, a body-style short, or a worn-item short.
 */
sealed interface AppearanceSlot {
    /** Nothing rendered in this slot. */
    data object Empty : AppearanceSlot

    /** An identikit body style (hair, beard, arms, torso, legs, hands, feet). */
    @JvmInline value class Body(val style: Int) : AppearanceSlot

    /** A worn equipment item. */
    @JvmInline value class Worn(val itemId: Int) : AppearanceSlot
}

/**
 * A title shown alongside the player's display name.
 *
 * @param text Fully formatted title text, including any colour tags.
 * @param after Whether the title renders after the name rather than before it.
 */
data class AppearanceTitle(val text: String, val after: Boolean)

/**
 * Render animations used while a player is transformed into an NPC.
 */
data class NpcRenderAnimations(
    val walk: Int,
    val rotate180: Int,
    val rotate90Right: Int,
    val rotate90Left: Int,
    val special: Int,
)

/**
 * Body model replacement for one equipment slot.
 *
 * @param male Three male body model ids (primary, secondary, tertiary).
 * @param female Three female body model ids (primary, secondary, tertiary).
 * @param includeSecondary Whether the item defines a secondary model, so the pair is transmitted.
 * @param includeTertiary Whether the item defines a tertiary model, so the pair is transmitted.
 */
data class BodyModelOverride(
    val male: IntArray,
    val female: IntArray,
    val includeSecondary: Boolean,
    val includeTertiary: Boolean,
) {
    init {
        require(male.size == 3 && female.size == 3) { "BodyModelOverride requires 3 male and 3 female model ids" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as BodyModelOverride
        if (includeSecondary != other.includeSecondary) return false
        if (includeTertiary != other.includeTertiary) return false
        if (!male.contentEquals(other.male)) return false
        return female.contentEquals(other.female)
    }

    override fun hashCode(): Int {
        var result = male.contentHashCode()
        result = 31 * result + female.contentHashCode()
        result = 31 * result + includeSecondary.hashCode()
        result = 31 * result + includeTertiary.hashCode()
        return result
    }
}

/**
 * Dialogue-head model replacement for one equipment slot.
 *
 * @param male Two male head model ids (primary, secondary).
 * @param female Two female head model ids (primary, secondary).
 * @param includeSecondary Whether the item defines a secondary head, so the pair is transmitted.
 */
data class HeadModelOverride(
    val male: IntArray,
    val female: IntArray,
    val includeSecondary: Boolean,
) {
    init {
        require(male.size == 2 && female.size == 2) { "HeadModelOverride requires 2 male and 2 female model ids" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as HeadModelOverride
        if (includeSecondary != other.includeSecondary) return false
        if (!male.contentEquals(other.male)) return false
        return female.contentEquals(other.female)
    }

    override fun hashCode(): Int {
        var result = male.contentHashCode()
        result = 31 * result + female.contentHashCode()
        result = 31 * result + includeSecondary.hashCode()
        return result
    }
}

/**
 * A per-slot override of an item's models, colours or textures.
 *
 * An override with every field null contributes nothing and is skipped by the encoder.
 *
 * @param slot Index into the transmitted-slot ordering (not the raw equipment slot id).
 */
data class ItemMeshOverride(
    val slot: Int,
    val bodyModels: BodyModelOverride? = null,
    val headModels: HeadModelOverride? = null,
    val colours: IntArray? = null,
    val textures: IntArray? = null,
) {
    /** True when this override changes nothing and should not be transmitted. */
    val isEmpty: Boolean
        get() = bodyModels == null && headModels == null && colours == null && textures == null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ItemMeshOverride
        if (slot != other.slot) return false
        if (bodyModels != other.bodyModels) return false
        if (headModels != other.headModels) return false
        if (!colours.contentEquals(other.colours)) return false
        return textures.contentEquals(other.textures)
    }

    override fun hashCode(): Int {
        var result = slot
        result = 31 * result + (bodyModels?.hashCode() ?: 0)
        result = 31 * result + (headModels?.hashCode() ?: 0)
        result = 31 * result + (colours?.contentHashCode() ?: 0)
        result = 31 * result + (textures?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * Everything the client needs to render a player, expressed semantically.
 *
 * @param male Whether the base body is male.
 * @param npcTransformSizeFlag Set when the transformed NPC definition requests the alternate size handling.
 * @param showSkillTotal Whether the total level replaces the summoning combat level in the footer.
 * @param title Optional title rendered with the display name.
 * @param skullIcon Skull icon id, or -1 for none.
 * @param headIcon Prayer/overhead icon id, or -1 for none.
 * @param hidden Whether the player is hidden from other clients.
 * @param transformedNpcId NPC id the player is transformed into, or -1 when not transformed.
 * @param slots Body/equipment slots in transmission order. Ignored while transformed.
 * @param meshOverrides Per-slot model/colour/texture overrides. Ignored while transformed.
 * @param colours Ten body recolour indices.
 * @param renderEmote Body animation set (BAS) id.
 * @param displayName Name rendered above the player.
 * @param combatLevel Combat level shown in the footer.
 * @param totalLevel Total skill level; transmitted only when [showSkillTotal].
 * @param summoningCombatLevel Combat level including summoning; transmitted only when not [showSkillTotal].
 * @param pvpCombatLevelThreshold PvP level-range threshold; transmitted only when not [showSkillTotal].
 * @param npcRenderAnimations Render animations for the transformed NPC, or null when not transformed.
 */
data class PlayerAppearanceData(
    val male: Boolean,
    val npcTransformSizeFlag: Boolean,
    val showSkillTotal: Boolean,
    val title: AppearanceTitle?,
    val skullIcon: Int,
    val headIcon: Int,
    val hidden: Boolean,
    val transformedNpcId: Int,
    val slots: List<AppearanceSlot>,
    val meshOverrides: List<ItemMeshOverride>,
    val colours: IntArray,
    val renderEmote: Int,
    val displayName: String,
    val combatLevel: Int,
    val totalLevel: Int,
    val summoningCombatLevel: Int,
    val pvpCombatLevelThreshold: Int,
    val npcRenderAnimations: NpcRenderAnimations?,
) {
    init {
        require(colours.size == COLOUR_COUNT) { "PlayerAppearanceData.colours must be length $COLOUR_COUNT" }
        require(transformedNpcId < 0 || npcRenderAnimations != null) {
            "npcRenderAnimations are required while transformed into an NPC"
        }
    }

    /** True when the player is rendered as an NPC rather than from equipment and body styles. */
    val transformed: Boolean
        get() = transformedNpcId >= 0

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as PlayerAppearanceData
        if (male != other.male) return false
        if (npcTransformSizeFlag != other.npcTransformSizeFlag) return false
        if (showSkillTotal != other.showSkillTotal) return false
        if (title != other.title) return false
        if (skullIcon != other.skullIcon) return false
        if (headIcon != other.headIcon) return false
        if (hidden != other.hidden) return false
        if (transformedNpcId != other.transformedNpcId) return false
        if (slots != other.slots) return false
        if (meshOverrides != other.meshOverrides) return false
        if (!colours.contentEquals(other.colours)) return false
        if (renderEmote != other.renderEmote) return false
        if (displayName != other.displayName) return false
        if (combatLevel != other.combatLevel) return false
        if (totalLevel != other.totalLevel) return false
        if (summoningCombatLevel != other.summoningCombatLevel) return false
        if (pvpCombatLevelThreshold != other.pvpCombatLevelThreshold) return false
        return npcRenderAnimations == other.npcRenderAnimations
    }

    override fun hashCode(): Int {
        var result = male.hashCode()
        result = 31 * result + npcTransformSizeFlag.hashCode()
        result = 31 * result + showSkillTotal.hashCode()
        result = 31 * result + (title?.hashCode() ?: 0)
        result = 31 * result + skullIcon
        result = 31 * result + headIcon
        result = 31 * result + hidden.hashCode()
        result = 31 * result + transformedNpcId
        result = 31 * result + slots.hashCode()
        result = 31 * result + meshOverrides.hashCode()
        result = 31 * result + colours.contentHashCode()
        result = 31 * result + renderEmote
        result = 31 * result + displayName.hashCode()
        result = 31 * result + combatLevel
        result = 31 * result + totalLevel
        result = 31 * result + summoningCombatLevel
        result = 31 * result + pvpCombatLevelThreshold
        result = 31 * result + (npcRenderAnimations?.hashCode() ?: 0)
        return result
    }

    companion object {
        /** Number of body recolour indices carried in the appearance block. */
        const val COLOUR_COUNT = 10
    }
}
