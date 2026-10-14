package world.gregs.voidps.cache.config.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.Config.HIT_SPLATS
import world.gregs.voidps.cache.config.ConfigDecoder
import world.gregs.voidps.cache.config.data.HitSplatDefinition
import world.gregs.voidps.cache.config.encoder.HitSplatEncoder

class HitSplatDecoder : ConfigDecoder<HitSplatDefinition>(HIT_SPLATS) {

    private val encoder = HitSplatEncoder()

    override fun create(size: Int) = Array(size) { HitSplatDefinition(it) }

    override fun canonicalOpcodes(definition: HitSplatDefinition): IntArray = encoder.opcodes(definition)

    override fun HitSplatDefinition.read(opcode: Int, buffer: Reader) {
        when (opcode) {
            // The font and the four graphics are big smarts, not shorts (client HitSplatType).
            // Read as shorts, 47 of the 48 shipped files desynced and left trailing bytes.
            1 -> font = buffer.readBigSmart()
            2 -> textColour = buffer.readUnsignedMedium()
            3 -> icon = buffer.readBigSmart()
            4 -> left = buffer.readBigSmart()
            5 -> middle = buffer.readBigSmart()
            6 -> right = buffer.readBigSmart()
            7 -> offsetX = buffer.readUnsignedShort()
            // gjstr2 (a 0 version byte first), not a plain string: read as one the "%1"
            // placement string swallowed its own terminator and desynced every following opcode.
            8 -> amount = buffer.readPrefixedString()
            9 -> duration = buffer.readShort()
            10 -> offsetY = buffer.readUnsignedShort()
            11 -> fade = 0
            12 -> comparisonType = buffer.readUnsignedByte()
            13 -> anInt3214 = buffer.readUnsignedShort()
            14 -> fade = buffer.readShort()
            else -> error("Unhandled hit mark definition opcode: $opcode for def $id")
        }
    }

}