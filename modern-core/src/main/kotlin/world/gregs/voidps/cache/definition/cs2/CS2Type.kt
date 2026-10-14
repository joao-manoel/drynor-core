package world.gregs.voidps.cache.definition.cs2

/**
 * Represents a CS2 script type. Each type has a stack footprint (int/string/long),
 * a human-readable name, and a single-character Jagex descriptor used in cache
 * opcodes to identify the type.
 *
 * Ported from the legacy Java implementation; preserves the Java-facing API so
 * existing callers continue to work via [JvmField] and [JvmStatic].
 */
class CS2Type {

    @JvmField var intStackSize: Int = 0
    @JvmField var stringStackSize: Int = 0
    @JvmField var longStackSize: Int = 0
    private var nameValue: String = ""
    @JvmField var charDesc: Char = '\u0000'
    private var structure: Boolean = false

    @JvmField val composite: MutableList<CS2Type> = ArrayList()

    constructor(iss: Int, sss: Int, lss: Int, name: String, c: Char) {
        intStackSize = iss
        stringStackSize = sss
        longStackSize = lss
        nameValue = name
        charDesc = c
        structure = false
        composite.add(this)
    }

    private constructor(struct: List<CS2Type>) {
        for (t in struct) {
            intStackSize += t.intStackSize
            stringStackSize += t.stringStackSize
            longStackSize += t.longStackSize
            composite.addAll(t.composite)
        }
        structure = true
        nameValue = ""
        cache.add(this)
    }

    fun isStructure(): Boolean = structure

    fun getName(): String = nameValue

    fun getIntStackSize(): Int = intStackSize

    fun getStringStackSize(): Int = stringStackSize

    fun getLongStackSize(): Int = longStackSize

    override fun toString(): String {
        if (structure) {
            val s = StringBuilder()
            var first = true
            for (t in composite) {
                if (!first) {
                    s.append(", ")
                }
                first = false
                s.append(t)
            }
            return s.toString()
        }
        return nameValue
    }

    companion object {
        @JvmField val attrTypes: MutableMap<Int, CS2Type> = HashMap()

        // BASE TYPES
        @JvmField val VOID = CS2Type(0, 0, 0, "void", '\u0000')
        @JvmField val BOOLEAN = CS2Type(1, 0, 0, "boolean", '1')
        @JvmField val INT = CS2Type(1, 0, 0, "int", 'i')
        @JvmField val FONTMETRICS = CS2Type(1, 0, 0, "FontMetrics", 'f')
        @JvmField val GRAPHIC = CS2Type(1, 0, 0, "Graphic", 'd')
        @JvmField val MODEL = CS2Type(1, 0, 0, "Model", 'm')
        @JvmField val MIDI = CS2Type(1, 0, 0, "Midi", 'M')
        @JvmField val ENUM = CS2Type(1, 0, 0, "Enum", 'g')
        @JvmField val STRUCT = CS2Type(1, 0, 0, "Struct", 'J')
        @JvmField val CHAR = CS2Type(1, 0, 0, "char", 'z')
        @JvmField val CONTAINER = CS2Type(1, 0, 0, "Container", 'v')
        @JvmField val STRING = CS2Type(0, 1, 0, "string", 's')
        @JvmField val LONG = CS2Type(0, 0, 1, "long", 0xA7.toChar())
        @JvmField val ICOMPONENT = CS2Type(1, 0, 0, "IComponent", 'I')
        @JvmField val LOCATION = CS2Type(1, 0, 0, "Location", 'c')
        @JvmField val ITEM = CS2Type(1, 0, 0, "Item", 'o')
        @JvmField val COLOR = CS2Type(1, 0, 0, "Color", 'i')
        @JvmField val IDENTIKIT = CS2Type(1, 0, 0, "Identikit", 'K')
        @JvmField val ANIM = CS2Type(1, 0, 0, "Animation", 'A')
        @JvmField val MAPID = CS2Type(1, 0, 0, "Map", '`')
        @JvmField val SPOTANIM = CS2Type(1, 0, 0, "SpotAnim", 't')
        @JvmField val SKILL = CS2Type(1, 0, 0, "Skill", 'S')
        @JvmField val NPCDEF = CS2Type(1, 0, 0, "NpcDef", 'n')
        @JvmField val QCPHRASE = CS2Type(1, 0, 0, "QcPhrase", 'e')
        @JvmField val CHATCAT = CS2Type(1, 0, 0, "QcCat", 'k')
        @JvmField val TEXTURE = CS2Type(1, 0, 0, "Texture", 'x')
        @JvmField val STANCE = CS2Type(1, 0, 0, "Stance", 0x20AC.toChar())
        @JvmField val SPELL = CS2Type(1, 0, 0, "Spell", '@')
        @JvmField val CATEGORY = CS2Type(1, 0, 0, "Category", 'y')
        @JvmField val SOUNDEFFECT = CS2Type(1, 0, 0, "SoundEff", 0xAB.toChar())
        @JvmField val CALLBACK = CS2Type(0, 0, 0, "Callback", '\u0000')
        @JvmField val UNKNOWN = CS2Type(0, 0, 0, "??", '\u0000')

        @JvmField val TYPE_LIST: Array<CS2Type> = arrayOf(
            VOID, CALLBACK, BOOLEAN, INT, FONTMETRICS, GRAPHIC, MODEL, MIDI, LOCATION,
            CHAR, STRING, LONG, UNKNOWN, ICOMPONENT, ITEM, COLOR, CONTAINER, ENUM, STRUCT,
            IDENTIKIT, ANIM, MAPID, SPOTANIM, SKILL, NPCDEF, QCPHRASE, CHATCAT, TEXTURE,
            STANCE, SPELL, CATEGORY, SOUNDEFFECT
        )

        private val cache: MutableList<CS2Type> = ArrayList()

        @JvmStatic
        fun of(typeList: List<CS2Type>): CS2Type {
            if (typeList.size == 1) {
                return typeList[0]
            }
            outer@ for (other in cache) {
                if (other.composite.size != typeList.size) {
                    continue
                }
                for (i in 0 until other.composite.size) {
                    if (other.composite[i] !== typeList[i]) {
                        continue@outer
                    }
                }
                return other
            }
            return CS2Type(typeList)
        }

        @JvmStatic
        fun forJagexDesc(desc: Char): CS2Type {
            return when (desc.code) {
                0 -> VOID
                0xA7 -> LONG
                'i'.code -> INT
                'z'.code -> CHAR
                's'.code -> STRING
                '1'.code -> BOOLEAN
                'o'.code, 'O'.code -> ITEM
                'A'.code -> ANIM
                'S'.code -> SKILL
                't'.code -> SPOTANIM
                'c'.code -> LOCATION
                'n'.code -> NPCDEF
                'J'.code -> STRUCT
                'g'.code -> ENUM
                'f'.code -> FONTMETRICS
                'd'.code -> GRAPHIC
                'm'.code -> MODEL
                'M'.code -> MIDI
                'K'.code -> IDENTIKIT
                'v'.code -> CONTAINER
                'I'.code -> ICOMPONENT
                'e'.code -> QCPHRASE
                'k'.code -> CHATCAT
                0x20AC -> STANCE
                'x'.code -> TEXTURE
                '@'.code -> SPELL
                '`'.code -> MAPID
                'y'.code -> CATEGORY
                0xAB -> SOUNDEFFECT
                else -> INT
            }
        }
    }
}
