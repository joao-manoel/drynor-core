package world.gregs.voidps.cache.definition.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Config.RENDER_ANIMATIONS
import world.gregs.voidps.cache.DefinitionDecoder
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.BASDefinition
import world.gregs.voidps.cache.definition.encoder.BASEncoder

/**
 * Decodes Base Animation Set (BAS / Render Animations) definitions from the
 * CONFIGS index (2), archive 32.
 *
 * BAS definitions store movement animation sets for players and NPCs,
 * including stand, walk, run, and teleport animations with directional
 * variants and turning animations.
 *
 * Archive 32 is the one and only render animation archive; the `bas` and "render animation" names
 * are the same type. Every opcode's payload is kept on the definition so the file can be written
 * back byte for byte.
 */
class BASDecoder : DefinitionDecoder<BASDefinition>(Index.CONFIGS) {

    private val encoder = BASEncoder()

    override fun create(size: Int) = Array(size) { BASDefinition(it) }

    override fun canonicalOpcodes(definition: BASDefinition): IntArray = encoder.opcodes(definition)

    override fun getArchive(id: Int) = RENDER_ANIMATIONS

    override fun size(cache: Cache): Int {
        return cache.lastFileId(Index.CONFIGS, RENDER_ANIMATIONS)
    }

    override fun BASDefinition.read(opcode: Int, buffer: Reader) {
        when (opcode) {
            // Opcode 1: stand + walk animations (two bigSmarts)
            1 -> {
                standAnimation = buffer.readBigSmart()
                walkAnimation = buffer.readBigSmart()
            }
            // Opcode 2-9: teleport and run animations
            2 -> teleportAnimation = buffer.readBigSmart()
            3 -> teleDir3 = buffer.readBigSmart()
            4 -> teleDir2 = buffer.readBigSmart()
            5 -> teleDir1 = buffer.readBigSmart()
            6 -> runAnimation = buffer.readBigSmart()
            7 -> runDir3 = buffer.readBigSmart()
            8 -> runDir2 = buffer.readBigSmart()
            9 -> runDir1 = buffer.readBigSmart()
            // Opcode 26: modelWidth and modelLength (2 unsigned bytes, each * 4)
            26 -> {
                modelWidth = (4 * buffer.readUnsignedByte()).toShort().toInt()
                modelLength = (4 * buffer.readUnsignedByte()).toShort().toInt()
            }
            // Opcode 27: per-slot vertex transformations (slot index + 6 shorts)
            27 -> {
                val slots = objVerticeTransformations ?: arrayOfNulls<IntArray>(BASDefinition.SLOTS).also { objVerticeTransformations = it }
                val slot = buffer.readUnsignedByte()
                slots[slot] = IntArray(6) { buffer.readShort() }
            }
            // Opcode 28: per-slot obj visibility array
            28 -> {
                val count = buffer.readUnsignedByte()
                objVisibility = IntArray(count) {
                    val value = buffer.readUnsignedByte()
                    if (value == 255) -1 else value
                }
            }
            29 -> yawAcceleration = buffer.readUnsignedByte()
            30 -> yawMaxVelocity = buffer.readUnsignedShort()
            31 -> rollAcceleration = buffer.readUnsignedByte()
            32 -> rollMaxVelocity = buffer.readUnsignedShort()
            33 -> rollTargetAngle = buffer.readShort()
            34 -> pitchAcceleration = buffer.readUnsignedByte()
            35 -> pitchMaxVelocity = buffer.readUnsignedShort()
            36 -> pitchTargetAngle = buffer.readShort()
            37 -> unused = buffer.readUnsignedByte()
            // Opcodes 38-42: stand turn and walk directional animations
            38 -> standTurn1 = buffer.readBigSmart()
            39 -> standTurn2 = buffer.readBigSmart()
            40 -> walkDir3 = buffer.readBigSmart()
            41 -> walkDir2 = buffer.readBigSmart()
            42 -> walkDir1 = buffer.readBigSmart()
            43 -> unused43 = buffer.readUnsignedShort()
            44 -> unused44 = buffer.readUnsignedShort()
            45 -> iconHeightOverride = buffer.readUnsignedShort()
            // Opcodes 46-51: turn animations for teleport, run, and walk
            46 -> teleTurn1 = buffer.readBigSmart()
            47 -> teleTurn2 = buffer.readBigSmart()
            48 -> runTurn1 = buffer.readBigSmart()
            49 -> runTurn2 = buffer.readBigSmart()
            50 -> walkTurn1 = buffer.readBigSmart()
            51 -> walkTurn2 = buffer.readBigSmart()
            // Opcode 52: random stand sequences + chances
            52 -> {
                val count = buffer.readUnsignedByte()
                val sequences = IntArray(count)
                val chances = IntArray(count)
                for (index in 0 until count) {
                    sequences[index] = buffer.readBigSmart()
                    chances[index] = buffer.readUnsignedByte()
                }
                randomStandSequences = sequences
                randomStandSequenceChances = chances
            }
            53 -> rendersShadow = false
            // Opcode 54: hillRotateX and hillRotateZ (2 unsigned bytes, each shl 6)
            54 -> {
                hillRotateX = buffer.readUnsignedByte() shl 6
                hillRotateZ = buffer.readUnsignedByte() shl 6
            }
            // Opcode 55: per-slot turn angle adjustment (slot + unsigned short)
            55 -> {
                val slots = turnAngleAdjustment ?: IntArray(BASDefinition.SLOTS).also { turnAngleAdjustment = it }
                slots[buffer.readUnsignedByte()] = buffer.readUnsignedShort()
            }
            // Opcode 56: per-slot projection offset (slot + 3 shorts)
            56 -> {
                val slots = projectionOffset ?: arrayOfNulls<IntArray>(BASDefinition.SLOTS).also { projectionOffset = it }
                val slot = buffer.readUnsignedByte()
                slots[slot] = IntArray(3) { buffer.readShort() }
            }
            else -> error("Unhandled BAS definition opcode: $opcode for def $id")
        }
    }

}
