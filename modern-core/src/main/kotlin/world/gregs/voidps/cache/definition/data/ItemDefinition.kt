package world.gregs.voidps.cache.definition.data

import org.drynor.core.Constants
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Definition
import world.gregs.voidps.cache.definition.ColourPalette
import world.gregs.voidps.cache.definition.Extra
import world.gregs.voidps.cache.definition.KeyedValue
import world.gregs.voidps.cache.definition.OpcodeOrdered
import world.gregs.voidps.cache.definition.Parameterized
import world.gregs.voidps.cache.definition.Recolourable

data class ItemDefinition(
    override var id: Int = -1,
    var modelId: Int = 0,
    var name: String = "null",
    var graphicScale: Int = 2000,
    var graphicPitch: Int = 0,
    var graphicCameraRoll: Int = 0,
    var graphicTranslateX: Int = 0,
    var graphicTranslateY: Int = 0,
    var stackable: Int = 0,
    var cost: Int = 1,
    var members: Boolean = false,
    var wearPos: Int = -1,       // Primary equipment slot (-1 = not equippable)
    var wearPos2: Int = -1,      // Secondary equipment slot
    var wearPos3: Int = -1,      // Tertiary equipment slot
    var multiStackSize: Int = -1,
    var primaryMaleModel: Int = -1,
    var secondaryMaleModel: Int = -1,
    var primaryFemaleModel: Int = -1,
    var secondaryFemaleModel: Int = -1,
    var floorOptions: Array<String?> = arrayOf(null, null, "Take", null, null, "Examine"),
    var options: Array<String?> = arrayOf(null, null, null, null, "Drop"),
    override var originalColours: ShortArray? = null,
    override var modifiedColours: ShortArray? = null,
    override var originalTextureColours: ShortArray? = null,
    override var modifiedTextureColours: ShortArray? = null,
    override var recolourPalette: ByteArray? = null,
    var exchangeable: Boolean = false,
    var tertiaryMaleModel: Int = -1,
    var tertiaryFemaleModel: Int = -1,
    var primaryMaleDialogueHead: Int = -1,
    var primaryFemaleDialogueHead: Int = -1,
    var secondaryMaleDialogueHead: Int = -1,
    var secondaryFemaleDialogueHead: Int = -1,
    var graphicCameraYaw: Int = 0,
    var dummyItem: Int = 0,
    var noteId: Int = -1,
    var notedTemplateId: Int = -1,
    var stackIds: IntArray? = null,
    var stackAmounts: IntArray? = null,
    var floorScaleX: Int = 128,
    var floorScaleZ: Int = 128,
    var floorScaleY: Int = 128,
    var ambience: Int = 0,
    var diffusion: Int = 0,
    var team: Int = 0,
    var lendId: Int = -1,
    var lendTemplateId: Int = -1,
    var maleWieldX: Int = 0,
    var maleWieldZ: Int = 0,
    var maleWieldY: Int = 0,
    var femaleWieldX: Int = 0,
    var femaleWieldZ: Int = 0,
    var femaleWieldY: Int = 0,
    var primaryCursorOpcode: Int = -1,
    var primaryCursor: Int = -1,
    var secondaryCursorOpcode: Int = -1,
    var secondaryCursor: Int = -1,
    var primaryInterfaceCursorOpcode: Int = -1,
    var primaryInterfaceCursor: Int = -1,
    var secondaryInterfaceCursorOpcode: Int = -1,
    var secondaryInterfaceCursor: Int = -1,
    var campaigns: IntArray? = null,
    var pickSizeShift: Int = 0,
    var singleNoteId: Int = -1,
    var singleNoteTemplateId: Int = -1,
    override var params: Map<Int, Any>? = null,
    override var stringId: String = "",
    override var extras: Map<String, Any>? = null,
    /**
     * The opcode 249 parameter records in file order, kept only when the file listed an id more than once -
     * [params] keeps the last value of each. Null when [params] holds every record.
     */
    var paramRecords: List<KeyedValue>? = null
) : Definition, Recolourable, ColourPalette, Parameterized, Extra, OpcodeOrdered {

    /**
     * @see OpcodeOrdered
     */
    override var opcodeOrder: IntArray? = null

    /**
     * @see OpcodeOrdered
     */
    override var shadowedPayloads: Array<ByteArray?>? = null

    val noted: Boolean
        get() = notedTemplateId != -1

    val lent: Boolean
        get() = lendTemplateId != -1

    val singleNote: Boolean
        get() = singleNoteTemplateId != -1

    // --- Java interop convenience methods ---

    /**
     * Equipment bonuses, built on first access.
     *
     * Legacy `ItemDefinitions.parseBonuses()` fills the `bonuses` field once at the end of decoding and
     * `getBonuses()` hands the same array back on every call.
     */
    private var bonusValues: IntArray? = null

    /** Wield requirements, built on first access. Legacy `ItemDefinitions.wieldRequirements`. */
    private var wieldRequirements: Map<Int, Int>? = null

    /**
     * Legacy `ItemDefinitions.getValue()` - [cost] with a handful of hardcoded overrides.
     */
    fun getValue(): Int = when (id) {
        18744, 18745, 18746, 18747 -> 5
        10551, 10548, 2952, 2890, 10499 -> 1
        else -> cost
    }

    /**
     * Whether this item is stackable.
     *
     * Legacy `isStackable()` is `stackable == 1`; opcode 165 sets [stackable] to 2 for coinshare shards,
     * which are not stackable.
     */
    fun isStackable() = stackable == 1

    /** Whether this item is lent (lendTemplateId != -1). Legacy `isLended()`. */
    fun isLended() = lent

    /** Whether this item is noted (notedTemplateId != -1). Legacy `isNoted()`. */
    fun isNoted() = noted

    /** Legacy alias for [noteId]. Legacy `getCertId()`. */
    fun getCertId() = noteId

    /**
     * Whether this item can be exchanged on the GE.
     *
     * Legacy `canExchange()` reads the unnoted item's flag when this definition is a note, because
     * `toNote()` does not copy the exchangeable flag onto the note.
     */
    fun canExchange(): Boolean {
        if (noted && noteId != -1) {
            return Cache.items[noteId].exchangeable
        }
        return exchangeable
    }

    /** Legacy alias for [wearPos]. Legacy `getEquipSlot()`. */
    fun getEquipSlot() = wearPos

    /**
     * Whether either secondary equipment slot is [type].
     *
     * Legacy `isEquipType()` is `wearPos3 == type || wearPos2 == type` - the slots a worn item also
     * occupies. 5 is two-handed (takes the shield slot), 6 hides arms, 8 hides hair, 11 hides the beard.
     * Param 528 is the *string* equipment option (see [getEquipmentOption]), not an equip type.
     */
    fun isEquipType(type: Int): Boolean = wearPos3 == type || wearPos2 == type

    /** Legacy `containsOption(String)` - an exact-match scan of the inventory options. */
    fun containsOption(option: String): Boolean = options.any { it == option }

    /** [op] is the op number, 1-based - op1 is 1. */
    fun hasOption(op: Int, option: String): Boolean = if (op < 1) false else options.getOrNull(op - 1) == option

    /** [op] is the op number, 1-based - op1 is 1. */
    fun hasInventoryOption(op: Int, option: String): Boolean =
        if (op < 1) false else options.getOrNull(op - 1)?.equals(option, ignoreCase = true) == true

    /** Whether this item can be worn at all. Legacy `isWearItem()`. */
    fun isWearItem(): Boolean = wearPos != -1

    /**
     * Whether this item can be worn by the given gender.
     *
     * Legacy `isWearItem(boolean male)` only requires a worn model for slots below 12, so rings (12),
     * ammo (13) and auras (14) are exempt - they have no worn model in the cache.
     */
    fun isWearItem(isMale: Boolean): Boolean {
        if (id == 4285) {
            return true
        }
        if (wearPos < 12 && (if (isMale) primaryMaleModel == -1 else primaryFemaleModel == -1)) {
            return false
        }
        return wearPos != -1
    }

    /** Legacy `isDestroyItem()` - an inventory option of "destroy". */
    fun isDestroyItem(): Boolean = options.any { it != null && it.equals("destroy", ignoreCase = true) }

    /** Legacy `isBindItem()` - an inventory option of "bind". */
    fun isBindItem(): Boolean = options.any { it != null && it.equals("bind", ignoreCase = true) }

    /** Whether this item is a dungeoneering bound item. Legacy `isBinded()` - hardcoded id ranges. */
    fun isBinded(): Boolean = id in 15775..16272 || id in 19865..19866

    /** Legacy `getParam(int defaultVal, int id)`. */
    fun getParam(defaultVal: Int, id: Int): Int = params?.get(id) as? Int ?: defaultVal

    /** Legacy `getParamVal(int id)` - [getParam] with a default of 0. */
    fun getParamVal(id: Int): Int = getParam(0, id)

    /**
     * The render animation (BAS) id. Legacy `getRenderAnimId()` - param 644, defaulting to the
     * unarmed BAS 1426 rather than -1.
     */
    fun getRenderAnimId(): Int = getParam(1426, 644)

    /** Native CS2 special_attack parameter; 686 selects the weapon attack styles. */
    fun hasSpecialBar(): Boolean = getParamVal(687) == 1

    /** The quest this item belongs to. Legacy `getQuestId()` - param 861. */
    fun getQuestId(): Int = getParam(-1, 861)

    /**
     * Equipment bonuses, indexed by [org.drynor.core.model.Bonus] ordinal.
     *
     * Legacy `ItemDefinitions.parseBonuses()`: 18 entries, with summoning defence at param 417, the
     * absorb bonuses at 967/968/969, melee and ranged strength at 641/643 (tenths) and magic damage
     * at 685.
     */
    fun getBonuses(): IntArray {
        bonusValues?.let { return it }
        val bonuses = IntArray(18)
        bonuses[STAB_ATTACK] = getParamVal(0)
        bonuses[SLASH_ATTACK] = getParamVal(1)
        bonuses[CRUSH_ATTACK] = getParamVal(2)
        bonuses[MAGIC_ATTACK] = getParamVal(3)
        bonuses[RANGE_ATTACK] = getParamVal(4)
        bonuses[STAB_DEF] = getParamVal(5)
        bonuses[SLASH_DEF] = getParamVal(6)
        bonuses[CRUSH_DEF] = getParamVal(7)
        bonuses[MAGIC_DEF] = getParamVal(8)
        bonuses[RANGE_DEF] = getParamVal(9)
        bonuses[SUMMONING_DEF] = getParamVal(417)
        bonuses[PRAYER_BONUS] = getParamVal(11)
        bonuses[ABSORB_MELEE_BONUS] = getParamVal(967)
        bonuses[ABSORB_RANGE_BONUS] = getParamVal(968)
        bonuses[ABSORB_MAGE_BONUS] = getParamVal(969)
        bonuses[STRENGTH_BONUS] = getParamVal(641) / 10
        bonuses[RANGED_STR_BONUS] = getParamVal(643) / 10
        bonuses[MAGIC_DAMAGE] = getParamVal(685)
        if (id == 25349) {
            bonuses.fill(10000)
        }
        bonusValues = bonuses
        return bonuses
    }

    /**
     * The worn ("equipment") option at the given index.
     *
     * Legacy `getEquipmentOption(int)` - these are string params at 528 + optionId, with option 4
     * living at param 1211. Returns the string "null" when absent, as legacy does.
     */
    fun getEquipmentOption(optionId: Int): String {
        val p = params ?: return "null"
        return p[if (optionId == 4) 1211 else 528 + optionId] as? String ?: "null"
    }

    /** Legacy `containsEquipmentOption(int, String)` - the string param at 528 + optionId. */
    fun containsEquipmentOption(optionId: Int, option: String): Boolean =
        params?.get(528 + optionId) as? String == option

    /**
     * The inventory option at the given index.
     *
     * Legacy `getInventoryOption(int)`, including its hardcoded overrides, returning the string "null"
     * when absent.
     */
    fun getInventoryOption(optionId: Int): String {
        when (id) {
            6099, 6100, 6101, 6102 -> if (optionId == 2) {
                return "Temple"
            }
            19760, 13561, 13562 -> {
                if (optionId == 0) {
                    return options.getOrNull(1) ?: "null"
                }
                if (optionId == 1) {
                    return options.getOrNull(0) ?: "null"
                }
            }
        }
        return options.getOrNull(optionId) ?: "null"
    }

    /**
     * The dungeoneering resource shop price multiplier.
     *
     * Legacy `getDungShopValueMultiplier()` - param 1046 as a percentage, defaulting to 1.0.
     */
    fun getDungShopValueMultiplier(): Double {
        val value = params?.get(1046) as? Int ?: return 1.0
        return value.toDouble() / 100
    }

    /**
     * Original model colours as unsigned shorts, matching legacy's `originalModelColors` int array -
     * [originalColours] holds them as signed shorts.
     */
    fun getOriginalModelColors(): IntArray {
        val colours = originalColours ?: return IntArray(0)
        return IntArray(colours.size) { colours[it].toInt() and 0xffff }
    }

    /** Gets the primary male worn model id. Legacy `getMaleWornModelId1()`. */
    fun getMaleWornModelId1(): Int = primaryMaleModel

    /** Gets the secondary male worn model id. Legacy `getMaleWornModelId2()`. */
    fun getMaleWornModelId2(): Int = secondaryMaleModel

    /** Gets the primary female worn model id. Legacy `getFemaleWornModelId1()`. */
    fun getFemaleWornModelId1(): Int = primaryFemaleModel

    /** Gets the secondary female worn model id. Legacy `getFemaleWornModelId2()`. */
    fun getFemaleWornModelId2(): Int = secondaryFemaleModel

    /** Legacy `isOverSized()` - a graphic scale above 5000. */
    fun isOverSized(): Boolean = graphicScale > 5000

    /** The general store sell price. Legacy `getSellPrice()` - three tenths of [cost]. */
    fun getSellPrice(): Int = (cost / (10.0 / 3.0)).toInt()

    /** The high alchemy price. Legacy `getHighAlchPrice()` - six tenths of [cost]. */
    fun getHighAlchPrice(): Int = (cost / (10.0 / 6.0)).toInt()

    /** Whether the item covers the face, hiding the head. Legacy `faceMask()` - param 625. */
    fun faceMask(): Boolean {
        if (id == 4168) {
            return true
        }
        return getParamVal(625) == 1
    }

    /**
     * Skill requirements for wearing this item, keyed by skill id.
     *
     * Legacy `getWearingSkillRequiriments()` - ten (skill, level) param pairs from 749/750 upwards,
     * param 277 for "maxed skill", then a table of hardcoded requirements.
     */
    fun getWearingSkillRequiriments(): Map<Int, Int> {
        wieldRequirements?.let { return it }
        val skills = HashMap<Int, Int>()
        val p = params
        if (p != null) {
            for (i in 0 until 10) {
                val skill = p[749 + i * 2] as? Int ?: continue
                val level = p[750 + i * 2] as? Int ?: continue
                skills[skill] = level
            }
            val maxedSkill = p[277] as? Int
            if (maxedSkill != null) {
                skills[maxedSkill] = if (id == 19709) 120 else 99
            }
        }
        when (id) {
            8846 -> {
                skills[Constants.ATTACK] = 5
                skills[Constants.DEFENSE] = 5
            }
            8847 -> {
                skills[Constants.ATTACK] = 10
                skills[Constants.DEFENSE] = 10
            }
            8848 -> {
                skills[Constants.ATTACK] = 20
                skills[Constants.DEFENSE] = 20
            }
            8849 -> {
                skills[Constants.ATTACK] = 30
                skills[Constants.DEFENSE] = 30
            }
            8850 -> {
                skills[Constants.ATTACK] = 40
                skills[Constants.DEFENSE] = 40
            }
            20072 -> {
                skills[Constants.ATTACK] = 60
                skills[Constants.DEFENSE] = 60
            }
            10498 -> skills[Constants.RANGE] = 30
            10499, 20068 -> skills[Constants.RANGE] = 50
            22358, 22359, 22360, 22361 -> skills[Constants.ATTACK] = 80
            22362, 22363, 22364, 22365 -> skills[Constants.RANGE] = 80
            22366, 22367, 22368, 22369 -> skills[Constants.MAGIC] = 80
            21371, 21372, 21373, 21374, 21375 -> {
                skills[Constants.ATTACK] = 85
                skills[Constants.SLAYER] = 80
            }
            10887 -> {
                skills[Constants.ATTACK] = 60
                skills[Constants.STRENGTH] = 40
            }
            7459 -> skills[Constants.DEFENSE] = 13
            7460 -> skills[Constants.DEFENSE] = 34
            7461 -> skills[Constants.DEFENSE] = 41
            7462 -> {
                skills[Constants.DEFENSE] = 41
                skills[Constants.COOKING] = 64
                skills[Constants.AGILITY] = 48
                skills[Constants.MINING] = 44
                skills[Constants.FISHING] = 47
                skills[Constants.THIEVING] = 53
                skills[Constants.HERBLORE] = 19
                skills[Constants.MAGIC] = 50
                skills[Constants.SMITHING] = 34
                skills[Constants.FIREMAKING] = 50
                skills[Constants.RANGE] = 40
            }
        }
        wieldRequirements = skills
        return skills
    }

    /**
     * How this item behaves on death: 1 always kept, -1 always lost, 0 normal.
     *
     * Legacy `getStageOnDeath()` - param 1397.
     */
    fun getStageOnDeath(): Int = getParam(0, 1397)

    /**
     * The quest slot that must be complete to wield this item, or -1.
     *
     * Legacy `getWieldQuestReq()` - param 743, with two hardcoded overrides.
     */
    fun getWieldQuestReqId(): Int = when (id) {
        19784 -> 174
        10887 -> 120
        else -> getParam(-1, 743)
    }

    /** [getWieldQuestReqId] as a map, for the callers that iterate quest slots. */
    fun getWieldQuestReq(): Map<Int, Int> {
        val quest = getWieldQuestReqId()
        return if (quest == -1) emptyMap() else mapOf(quest to 1)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ItemDefinition

        if (id != other.id) return false
        if (modelId != other.modelId) return false
        if (name != other.name) return false
        if (graphicScale != other.graphicScale) return false
        if (graphicPitch != other.graphicPitch) return false
        if (graphicCameraRoll != other.graphicCameraRoll) return false
        if (graphicTranslateX != other.graphicTranslateX) return false
        if (graphicTranslateY != other.graphicTranslateY) return false
        if (stackable != other.stackable) return false
        if (cost != other.cost) return false
        if (members != other.members) return false
        if (wearPos != other.wearPos) return false
        if (wearPos2 != other.wearPos2) return false
        if (wearPos3 != other.wearPos3) return false
        if (multiStackSize != other.multiStackSize) return false
        if (primaryMaleModel != other.primaryMaleModel) return false
        if (secondaryMaleModel != other.secondaryMaleModel) return false
        if (primaryFemaleModel != other.primaryFemaleModel) return false
        if (secondaryFemaleModel != other.secondaryFemaleModel) return false
        if (!floorOptions.contentEquals(other.floorOptions)) return false
        if (!options.contentEquals(other.options)) return false
        if (originalColours != null) {
            if (other.originalColours == null) return false
            if (!originalColours.contentEquals(other.originalColours)) return false
        } else if (other.originalColours != null) return false
        if (modifiedColours != null) {
            if (other.modifiedColours == null) return false
            if (!modifiedColours.contentEquals(other.modifiedColours)) return false
        } else if (other.modifiedColours != null) return false
        if (originalTextureColours != null) {
            if (other.originalTextureColours == null) return false
            if (!originalTextureColours.contentEquals(other.originalTextureColours)) return false
        } else if (other.originalTextureColours != null) return false
        if (modifiedTextureColours != null) {
            if (other.modifiedTextureColours == null) return false
            if (!modifiedTextureColours.contentEquals(other.modifiedTextureColours)) return false
        } else if (other.modifiedTextureColours != null) return false
        if (recolourPalette != null) {
            if (other.recolourPalette == null) return false
            if (!recolourPalette.contentEquals(other.recolourPalette)) return false
        } else if (other.recolourPalette != null) return false
        if (exchangeable != other.exchangeable) return false
        if (tertiaryMaleModel != other.tertiaryMaleModel) return false
        if (tertiaryFemaleModel != other.tertiaryFemaleModel) return false
        if (primaryMaleDialogueHead != other.primaryMaleDialogueHead) return false
        if (primaryFemaleDialogueHead != other.primaryFemaleDialogueHead) return false
        if (secondaryMaleDialogueHead != other.secondaryMaleDialogueHead) return false
        if (secondaryFemaleDialogueHead != other.secondaryFemaleDialogueHead) return false
        if (graphicCameraYaw != other.graphicCameraYaw) return false
        if (dummyItem != other.dummyItem) return false
        if (noteId != other.noteId) return false
        if (notedTemplateId != other.notedTemplateId) return false
        if (stackIds != null) {
            if (other.stackIds == null) return false
            if (!stackIds.contentEquals(other.stackIds)) return false
        } else if (other.stackIds != null) return false
        if (stackAmounts != null) {
            if (other.stackAmounts == null) return false
            if (!stackAmounts.contentEquals(other.stackAmounts)) return false
        } else if (other.stackAmounts != null) return false
        if (floorScaleX != other.floorScaleX) return false
        if (floorScaleZ != other.floorScaleZ) return false
        if (floorScaleY != other.floorScaleY) return false
        if (ambience != other.ambience) return false
        if (diffusion != other.diffusion) return false
        if (team != other.team) return false
        if (lendId != other.lendId) return false
        if (lendTemplateId != other.lendTemplateId) return false
        if (maleWieldX != other.maleWieldX) return false
        if (maleWieldZ != other.maleWieldZ) return false
        if (maleWieldY != other.maleWieldY) return false
        if (femaleWieldX != other.femaleWieldX) return false
        if (femaleWieldZ != other.femaleWieldZ) return false
        if (femaleWieldY != other.femaleWieldY) return false
        if (primaryCursorOpcode != other.primaryCursorOpcode) return false
        if (primaryCursor != other.primaryCursor) return false
        if (secondaryCursorOpcode != other.secondaryCursorOpcode) return false
        if (secondaryCursor != other.secondaryCursor) return false
        if (primaryInterfaceCursorOpcode != other.primaryInterfaceCursorOpcode) return false
        if (primaryInterfaceCursor != other.primaryInterfaceCursor) return false
        if (secondaryInterfaceCursorOpcode != other.secondaryInterfaceCursorOpcode) return false
        if (secondaryInterfaceCursor != other.secondaryInterfaceCursor) return false
        if (campaigns != null) {
            if (other.campaigns == null) return false
            if (!campaigns.contentEquals(other.campaigns)) return false
        } else if (other.campaigns != null) return false
        if (pickSizeShift != other.pickSizeShift) return false
        if (singleNoteId != other.singleNoteId) return false
        if (singleNoteTemplateId != other.singleNoteTemplateId) return false
        if (params != other.params) return false
        if (stringId != other.stringId) return false
        if (extras != other.extras) return false
        if (paramRecords != other.paramRecords) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + modelId
        result = 31 * result + name.hashCode()
        result = 31 * result + graphicScale
        result = 31 * result + graphicPitch
        result = 31 * result + graphicCameraRoll
        result = 31 * result + graphicTranslateX
        result = 31 * result + graphicTranslateY
        result = 31 * result + stackable
        result = 31 * result + cost
        result = 31 * result + members.hashCode()
        result = 31 * result + wearPos
        result = 31 * result + wearPos2
        result = 31 * result + wearPos3
        result = 31 * result + multiStackSize
        result = 31 * result + primaryMaleModel
        result = 31 * result + secondaryMaleModel
        result = 31 * result + primaryFemaleModel
        result = 31 * result + secondaryFemaleModel
        result = 31 * result + floorOptions.contentHashCode()
        result = 31 * result + options.contentHashCode()
        result = 31 * result + (originalColours?.contentHashCode() ?: 0)
        result = 31 * result + (modifiedColours?.contentHashCode() ?: 0)
        result = 31 * result + (originalTextureColours?.contentHashCode() ?: 0)
        result = 31 * result + (modifiedTextureColours?.contentHashCode() ?: 0)
        result = 31 * result + (recolourPalette?.contentHashCode() ?: 0)
        result = 31 * result + exchangeable.hashCode()
        result = 31 * result + tertiaryMaleModel
        result = 31 * result + tertiaryFemaleModel
        result = 31 * result + primaryMaleDialogueHead
        result = 31 * result + primaryFemaleDialogueHead
        result = 31 * result + secondaryMaleDialogueHead
        result = 31 * result + secondaryFemaleDialogueHead
        result = 31 * result + graphicCameraYaw
        result = 31 * result + dummyItem
        result = 31 * result + noteId
        result = 31 * result + notedTemplateId
        result = 31 * result + (stackIds?.contentHashCode() ?: 0)
        result = 31 * result + (stackAmounts?.contentHashCode() ?: 0)
        result = 31 * result + floorScaleX
        result = 31 * result + floorScaleZ
        result = 31 * result + floorScaleY
        result = 31 * result + ambience
        result = 31 * result + diffusion
        result = 31 * result + team
        result = 31 * result + lendId
        result = 31 * result + lendTemplateId
        result = 31 * result + maleWieldX
        result = 31 * result + maleWieldZ
        result = 31 * result + maleWieldY
        result = 31 * result + femaleWieldX
        result = 31 * result + femaleWieldZ
        result = 31 * result + femaleWieldY
        result = 31 * result + primaryCursorOpcode
        result = 31 * result + primaryCursor
        result = 31 * result + secondaryCursorOpcode
        result = 31 * result + secondaryCursor
        result = 31 * result + primaryInterfaceCursorOpcode
        result = 31 * result + primaryInterfaceCursor
        result = 31 * result + secondaryInterfaceCursorOpcode
        result = 31 * result + secondaryInterfaceCursor
        result = 31 * result + (campaigns?.contentHashCode() ?: 0)
        result = 31 * result + pickSizeShift
        result = 31 * result + singleNoteId
        result = 31 * result + singleNoteTemplateId
        result = 31 * result + (params?.hashCode() ?: 0)
        result = 31 * result + stringId.hashCode()
        result = 31 * result + extras.hashCode()
        result = 31 * result + (paramRecords?.hashCode() ?: 0)
        return result
    }

    fun toLend(item: ItemDefinition?, template: ItemDefinition?) {
        if (item == null || template == null) {
            return
        }
        modifiedColours = item.modifiedColours
        primaryMaleDialogueHead = item.primaryMaleDialogueHead
        secondaryMaleDialogueHead = item.secondaryMaleDialogueHead
        tertiaryMaleModel = item.tertiaryMaleModel
        team = item.team
        params = item.params
        members = item.members
        wearPos = item.wearPos
        wearPos2 = item.wearPos2
        wearPos3 = item.wearPos3
        modifiedTextureColours = item.modifiedTextureColours
        maleWieldZ = item.maleWieldZ
        secondaryFemaleModel = item.secondaryFemaleModel
        graphicCameraYaw = template.graphicCameraYaw
        floorOptions = item.floorOptions
        secondaryFemaleDialogueHead = item.secondaryFemaleDialogueHead
        recolourPalette = item.recolourPalette
        femaleWieldZ = item.femaleWieldZ
        graphicPitch = template.graphicPitch
        primaryFemaleModel = item.primaryFemaleModel
        modelId = template.modelId
        options = arrayOfNulls(5)
        graphicCameraRoll = template.graphicCameraRoll
        graphicTranslateY = template.graphicTranslateY
        originalTextureColours = item.originalTextureColours
        femaleWieldX = item.femaleWieldX
        secondaryMaleModel = item.secondaryMaleModel
        cost = 0
        maleWieldY = item.maleWieldY
        originalColours = item.originalColours
        graphicTranslateX = template.graphicTranslateX
        femaleWieldY = item.femaleWieldY
        primaryFemaleDialogueHead = item.primaryFemaleDialogueHead
        graphicScale = template.graphicScale
        name = item.name
        tertiaryFemaleModel = item.tertiaryFemaleModel
        primaryMaleModel = item.primaryMaleModel
        maleWieldX = item.maleWieldX
        System.arraycopy(item.options, 0, options, 0, 4)
        options[4] = "Discard"
    }

    fun toNote(template: ItemDefinition?, item: ItemDefinition?) {
        if (item == null || template == null) {
            return
        }
        graphicTranslateY = template.graphicTranslateY
        originalColours = template.originalColours
        cost = item.cost
        name = item.name
        modifiedTextureColours = template.modifiedTextureColours
        graphicCameraRoll = template.graphicCameraRoll
        graphicCameraYaw = template.graphicCameraYaw
        originalTextureColours = template.originalTextureColours
        modelId = template.modelId
        graphicScale = template.graphicScale
        recolourPalette = template.recolourPalette
        stackable = 1
        graphicPitch = template.graphicPitch
        graphicTranslateX = template.graphicTranslateX
        members = item.members
        modifiedColours = template.modifiedColours
    }

    fun toSingleNote(template: ItemDefinition?, item: ItemDefinition?) {
        if (item == null || template == null) {
            return
        }
        cost = 0
        tertiaryMaleModel = item.tertiaryMaleModel
        stackable = item.stackable
        members = item.members
        wearPos = item.wearPos
        wearPos2 = item.wearPos2
        wearPos3 = item.wearPos3
        recolourPalette = item.recolourPalette
        graphicTranslateY = template.graphicTranslateY
        team = item.team
        secondaryMaleModel = item.secondaryMaleModel
        options = arrayOfNulls(5)
        floorOptions = item.floorOptions
        maleWieldZ = item.maleWieldZ
        primaryMaleDialogueHead = item.primaryMaleDialogueHead
        femaleWieldZ = item.femaleWieldZ
        name = item.name
        graphicScale = template.graphicScale
        originalColours = item.originalColours
        secondaryFemaleDialogueHead = item.secondaryFemaleDialogueHead
        params = item.params
        primaryFemaleModel = item.primaryFemaleModel
        graphicPitch = template.graphicPitch
        graphicCameraRoll = template.graphicCameraRoll
        femaleWieldX = item.femaleWieldX
        secondaryMaleDialogueHead = item.secondaryMaleDialogueHead
        tertiaryFemaleModel = item.tertiaryFemaleModel
        modifiedTextureColours = item.modifiedTextureColours
        maleWieldX = item.maleWieldX
        primaryFemaleDialogueHead = item.primaryFemaleDialogueHead
        modelId = template.modelId
        modifiedColours = item.modifiedColours
        secondaryFemaleModel = item.secondaryFemaleModel
        graphicTranslateX = template.graphicTranslateX
        graphicCameraYaw = template.graphicCameraYaw
        primaryMaleModel = item.primaryMaleModel
        femaleWieldY = item.femaleWieldY
        maleWieldY = item.maleWieldY
        originalTextureColours = item.originalTextureColours
        System.arraycopy(item.options, 0, options, 0, 4)
        options[4] = "Discard"
    }

    companion object {
        val EMPTY = ItemDefinition()

        /**
         * Indices into [getBonuses], matching [org.drynor.core.model.Bonus] ordinals and the
         * constants of the same name on legacy `ItemDefinitions`.
         */
        const val STAB_ATTACK = 0
        const val SLASH_ATTACK = 1
        const val CRUSH_ATTACK = 2
        const val MAGIC_ATTACK = 3
        const val RANGE_ATTACK = 4
        const val STAB_DEF = 5
        const val SLASH_DEF = 6
        const val CRUSH_DEF = 7
        const val MAGIC_DEF = 8
        const val RANGE_DEF = 9
        const val SUMMONING_DEF = 10
        const val ABSORB_MELEE_BONUS = 11
        const val ABSORB_MAGE_BONUS = 12
        const val ABSORB_RANGE_BONUS = 13
        const val STRENGTH_BONUS = 14
        const val RANGED_STR_BONUS = 15
        const val PRAYER_BONUS = 16
        const val MAGIC_DAMAGE = 17
    }
}