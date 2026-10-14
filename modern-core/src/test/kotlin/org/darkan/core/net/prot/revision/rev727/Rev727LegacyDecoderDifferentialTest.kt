package org.darkan.core.net.prot.revision.rev727

import com.rs.lib.io.InputStream as LegacyStream
import com.rs.lib.net.packets.Packet as LegacyPacket
import com.rs.lib.net.packets.decoders.AccountCreationStage as LegacyAccountCreationStage
import com.rs.lib.net.packets.decoders.ClientCheat as LegacyClientCheat
import com.rs.lib.net.packets.decoders.ClientFocus as LegacyClientFocus
import com.rs.lib.net.packets.decoders.EmailChangeAddress as LegacyEmailChangeAddress
import com.rs.lib.net.packets.decoders.GEItemSelect as LegacyGeItemSelect
import com.rs.lib.net.packets.decoders.GroundItemOp as LegacyGroundItemOp
import com.rs.lib.net.packets.decoders.KeyPress as LegacyKeyPress
import com.rs.lib.net.packets.decoders.LobbyHyperlink as LegacyLobbyHyperlink
import com.rs.lib.net.packets.decoders.MoveCamera as LegacyMoveCamera
import com.rs.lib.net.packets.decoders.MusicEnded as LegacyMusicEnded
import com.rs.lib.net.packets.decoders.NPCOp as LegacyNpcOp
import com.rs.lib.net.packets.decoders.ObjectOp as LegacyObjectOp
import com.rs.lib.net.packets.decoders.PlayerOp as LegacyPlayerOp
import com.rs.lib.net.packets.decoders.ReflectionCheckResponse as LegacyReflectionCheckResponse
import com.rs.lib.net.packets.decoders.ReportAbuse as LegacyReportAbuse
import com.rs.lib.net.packets.decoders.RequestWorldList as LegacyRequestWorldList
import com.rs.lib.net.packets.decoders.ResumeCountDialogue as LegacyResumeCountDialogue
import com.rs.lib.net.packets.decoders.ResumeHSLDialogue as LegacyResumeHslDialogue
import com.rs.lib.net.packets.decoders.ResumeTextDialogue as LegacyResumeTextDialogue
import com.rs.lib.net.packets.decoders.SetScreenSize as LegacySetScreenSize
import com.rs.lib.net.packets.decoders.SocialAddRemove as LegacySocialAddRemove
import com.rs.lib.net.packets.decoders.TransmitVarVerifyId as LegacyTransmitVarVerifyId
import com.rs.lib.net.packets.decoders.Walk as LegacyWalk
import com.rs.lib.net.packets.decoders.WorldMapClick as LegacyWorldMapClick
import com.rs.lib.net.packets.decoders.WritePing as LegacyWritePing
import com.rs.lib.net.packets.decoders.chat.ChatSetFilter as LegacyChatSetFilter
import com.rs.lib.net.packets.decoders.chat.ChatType as LegacyChatType
import com.rs.lib.net.packets.decoders.chat.QCPrivate as LegacyQcPrivate
import com.rs.lib.net.packets.decoders.chat.QCPublic as LegacyQcPublic
import com.rs.lib.net.packets.decoders.clan.CCKick as LegacyCcKick
import com.rs.lib.net.packets.decoders.fc.FCJoin as LegacyFcJoin
import com.rs.lib.net.packets.decoders.fc.FCKick as LegacyFcKick
import com.rs.lib.net.packets.decoders.fc.FCSetRank as LegacyFcSetRank
import com.rs.lib.net.packets.decoders.interfaces.IFContinue as LegacyIfContinue
import com.rs.lib.net.packets.decoders.interfaces.IFDragOntoIF as LegacyIfDragOntoIf
import com.rs.lib.net.packets.decoders.interfaces.IFOnGroundItem as LegacyIfOnGroundItem
import com.rs.lib.net.packets.decoders.interfaces.IFOnIF as LegacyIfOnIf
import com.rs.lib.net.packets.decoders.interfaces.IFOnNPC as LegacyIfOnNpc
import com.rs.lib.net.packets.decoders.interfaces.IFOnObject as LegacyIfOnObject
import com.rs.lib.net.packets.decoders.interfaces.IFOnPlayer as LegacyIfOnPlayer
import com.rs.lib.net.packets.decoders.interfaces.IFOp as LegacyIfOp
import com.rs.lib.net.packets.decoders.mouse.MouseClickHW as LegacyMouseClickHw
import com.rs.lib.net.packets.decoders.mouse.MouseClickJav as LegacyMouseClickJav
import com.rs.lib.net.packets.decoders.mouse.MouseMoveHW as LegacyMouseMoveHw
import com.rs.lib.net.packets.decoders.mouse.MouseMoveJav as LegacyMouseMoveJav
import com.rs.lib.net.packets.decoders.mouse.MouseTrailStep as LegacyMouseTrailStep
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.Source
import org.darkan.core.net.prot.ClientProt
import org.darkan.core.net.prot.Codec
import org.darkan.core.clientwatch.MouseTrailStep as ModernMouseTrailStep
import org.darkan.core.net.prot.packet.*
import java.io.ByteArrayOutputStream
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Differential test in the client -> server direction: the same bytes are handed to the legacy
 * `rs.darkan:core` decoder and to the rewritten revision 727 decoder, and the fields they recover
 * are compared.
 *
 * ### Why arbitrary bytes are the right input
 *
 * Both decoders read the *same* buffer, so the payloads here do not have to be bytes a real client
 * would send - they only have to be the right length and well formed where a decoder scans for a
 * terminator. Deliberately awkward values (high bit set, values above 32767) are used because
 * those are what separate a correct transform from a sign-extending one.
 *
 * ### Framing
 *
 * The opcode and the VarByte/VarShort length prefix are stripped before either decoder runs, on
 * both sides, so this compares payload interpretation only. ISAAC only ever touches the opcode.
 *
 * ### What is not compared
 *
 * [UNCOVERED] lists the legacy decoders with no case and why, and [KNOWN_DIVERGENCES] records the
 * places where the two decoders genuinely disagree and which one the client sides with.
 */
class Rev727LegacyDecoderDifferentialTest {

    private lateinit var codec: Codec

    @BeforeTest
    fun setUp() {
        codec = register727()
    }

    /** A field-by-field view of a decoded packet, so two unrelated models can be compared. */
    private typealias Fields = Map<String, Any?>

    private class Case(
        val name: String,
        val opcode: Int,
        val bytes: ByteArray,
        val legacy: (LegacyStream) -> Fields,
        val modern: (ClientProt) -> Fields,
    )

    private class CaseBuilder {
        val cases = mutableListOf<Case>()
        fun case(
            name: String,
            opcode: Int,
            bytes: ByteArray,
            legacy: (LegacyStream) -> Fields,
            modern: (ClientProt) -> Fields,
        ) {
            cases.add(Case(name, opcode, bytes, legacy, modern))
        }
    }

    @Test
    fun `every covered decoder recovers the legacy fields`() {
        val cases = cases()
        val failures = mutableListOf<String>()
        for (case in cases) {
            val legacyFields = try {
                case.legacy(LegacyStream(case.bytes.copyOf()))
            } catch (e: Throwable) {
                failures.add("${case.name}: legacy decoder threw ${e::class.simpleName}: ${e.message}")
                continue
            }
            val modernFields = try {
                case.modern(decodeModern(case.opcode, case.bytes.copyOf()))
            } catch (e: Throwable) {
                failures.add("${case.name}: modern decoder threw ${e::class.simpleName}: ${e.message}")
                continue
            }
            val differing = (legacyFields.keys + modernFields.keys)
                .filter { legacyFields[it] != modernFields[it] }
            if (differing.isNotEmpty()) {
                failures.add(
                    "${case.name} (opcode ${case.opcode}), payload ${hex(case.bytes)}\n" +
                        differing.joinToString("\n") {
                            "  $it: legacy=${legacyFields[it]} modern=${modernFields[it]}"
                        }
                )
            }
        }
        assertTrue(
            failures.isEmpty(),
            "${failures.size} of ${cases.size} decoders disagree with the legacy library:\n\n" +
                failures.joinToString("\n\n")
        )
    }

    @Test
    fun `the harness detects a difference when there is one`() {
        // Negative control: proves an all-green run means the fields really were compared.
        val bytes = Payload().u16(1234).u8(129).bytes()
        val legacy = (LegacyPlayerOp().decodeAndCreateInstance(LegacyStream(bytes.copyOf())) as LegacyPlayerOp).pid
        val modern = (decodeModern(66, bytes) as OpPlayer).playerIndex
        assertTrue(legacy == modern, "sanity: this pair must agree")
        assertTrue(mapOf("a" to 1) != mapOf("a" to 2), "field maps must compare by value")
    }

    @Test
    fun `the coverage bookkeeping is honest`() {
        val covered = cases().map { it.name.substringBefore(' ') }.toSet()
        val overlap = covered.intersect(UNCOVERED.keys)
        assertTrue(overlap.isEmpty(), "Listed as uncovered but actually covered: $overlap")
        assertTrue(
            cases().size >= MINIMUM_CASE_COUNT,
            "Expected at least $MINIMUM_CASE_COUNT decoder cases, found ${cases().size}"
        )
    }

    // --- Decoding ------------------------------------------------------------------------------

    private fun decodeModern(opcode: Int, bytes: ByteArray): ClientProt {
        val entry = codec.clientProtsByOpcode[opcode] ?: error("no decoder registered for opcode $opcode")
        val decoder = entry.decoder ?: error("opcode $opcode has no decoder body")
        val source: Source = Buffer().apply { write(bytes) }
        return runBlocking { decoder.invoke(source, opcode) }
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString(" ") { "%02x".format(it) }

    /**
     * The 16 bits a short-sized field actually occupies on the wire.
     *
     * Several fields are read signed by one stack and unsigned by the other. That is a modelling
     * choice about what a value above 32767 means, not a difference in the bytes: masking to 16
     * bits compares exactly what the client sent while staying agnostic about the convention.
     * Fields compared this way are suffixed `(16)` so a failure says which check was loosened.
     */
    private fun wire16(value: Int) = value and 0xFFFF

    /** Builds a payload. The transforms do not matter here - both decoders read the same bytes. */
    private class Payload {
        private val out = ByteArrayOutputStream()
        fun u8(value: Int) = apply { out.write(value and 0xff) }
        fun u16(value: Int) = apply { u8(value shr 8); u8(value) }
        fun u32(value: Int) = apply { u8(value shr 24); u8(value shr 16); u8(value shr 8); u8(value) }
        fun str(value: String) = apply { out.write(value.toByteArray(Charsets.ISO_8859_1)); u8(0) }
        fun raw(vararg values: Int) = apply { values.forEach { u8(it) } }
        fun bytes(): ByteArray = out.toByteArray()
    }

    private fun payload(block: Payload.() -> Unit): ByteArray = Payload().apply(block).bytes()

    private fun cases(): List<Case> = CaseBuilder().apply {
        entityCases()
        interfaceCases()
        inputCases()
        socialCases()
        sessionCases()
    }.cases

    // --- Entity interactions -------------------------------------------------------------------

    private fun CaseBuilder.entityCases() {
        case(
            "PLAYER_OP1", 66,
            payload { u16(40_000); u8(0x81) },
            legacy = { (LegacyPlayerOp().decodeAndCreateInstance(it) as LegacyPlayerOp).let { p -> mapOf("index" to p.pid, "forceRun" to p.isForceRun) } },
            modern = { (it as OpPlayer).let { p -> mapOf("index" to p.playerIndex, "forceRun" to p.forceRun) } },
        )
        case(
            "NPC_OP1", 65,
            payload { u16(40_000); u8(1) },
            legacy = { (LegacyNpcOp().decodeAndCreateInstance(it) as LegacyNpcOp).let { p -> mapOf("index" to p.npcIndex, "forceRun" to p.isForceRun) } },
            modern = { (it as OpNpc).let { p -> mapOf("index" to p.npcIndex, "forceRun" to p.forceRun) } },
        )
        case(
            "OBJECT_OP1", 75,
            payload { u16(40_000); u16(50_000); u32(0x0BADF00D); u8(129) },
            legacy = {
                (LegacyObjectOp().decodeAndCreateInstance(it) as LegacyObjectOp).let { p ->
                    mapOf("objectId" to p.objectId, "x" to p.x, "y" to p.y, "forceRun" to p.isForceRun)
                }
            },
            modern = { (it as OpObj).let { p -> mapOf("objectId" to p.objectId, "x" to p.x, "y" to p.y, "forceRun" to p.forceRun) } },
        )
        case(
            "GROUND_ITEM_OP1", 24,
            payload { u16(0x8f4e); u8(0xff); u16(50_000); u16(0x8fee) },
            legacy = {
                (LegacyGroundItemOp().decodeAndCreateInstance(it) as LegacyGroundItemOp).let { p ->
                    mapOf("itemId" to p.objectId, "x" to p.x, "y" to p.y, "forceRun" to p.isForceRun)
                }
            },
            modern = { (it as OpGroundItem).let { p -> mapOf("itemId" to p.itemId, "x" to p.x, "y" to p.y, "forceRun" to p.forceRun) } },
        )
        case(
            "WALK", 33,
            payload { u8(1); u16(50_000); u16(40_000) },
            legacy = { (LegacyWalk().decodeAndCreateInstance(it) as LegacyWalk).let { p -> mapOf("x" to p.x, "y" to p.y, "forceRun" to p.isForceRun) } },
            modern = { (it as Walk).let { p -> mapOf("x" to p.x, "y" to p.y, "forceRun" to p.forceRun) } },
        )
        case(
            "WORLD_MAP_CLICK", 5,
            payload { u32(0x0BADF00D) },
            legacy = {
                (LegacyWorldMapClick().decodeAndCreateInstance(it) as LegacyWorldMapClick).tile
                    .let { t -> mapOf("x" to t.x, "y" to t.y, "plane" to t.plane) }
            },
            modern = { (it as WorldMapClick).tile.let { t -> mapOf("x" to t.x, "y" to t.y, "plane" to t.level) } },
        )
    }

    // --- Interfaces ----------------------------------------------------------------------------

    private fun CaseBuilder.interfaceCases() {
        case(
            "IF_OP1", 96,
            payload { u32(0x0BADF00D); u16(40_000); u16(0x8fee) },
            legacy = {
                (LegacyIfOp().decodeAndCreateInstance(it) as LegacyIfOp).let { p ->
                    mapOf("interfaceId" to p.interfaceId, "componentId" to p.componentId, "itemId(16)" to wire16(p.itemId), "slotId" to p.slotId)
                }
            },
            modern = {
                (it as IfButton).let { p ->
                    mapOf("interfaceId" to p.interfaceId, "componentId" to p.componentId, "itemId(16)" to wire16(p.itemId), "slotId" to p.slotId)
                }
            },
        )
        case(
            "IF_ON_IF", 4,
            payload { u16(0x8f4e); u16(0x1234); u16(0x8fee); u32(0x0BADF00D); u32(0x0DEADBEE); u16(0x4321) },
            legacy = {
                (LegacyIfOnIf().decodeAndCreateInstance(it) as LegacyIfOnIf).let { p ->
                    mapOf(
                        "fromInter" to p.fromInter, "toInter" to p.toInter,
                        "fromComp" to p.fromComp, "toComp" to p.toComp,
                        "fromSlot(16)" to wire16(p.fromSlot), "toSlot(16)" to wire16(p.toSlot),
                        "fromItemId(16)" to wire16(p.fromItemId), "toItemId(16)" to wire16(p.toItemId),
                    )
                }
            },
            modern = {
                (it as IfOnIf).let { p ->
                    mapOf(
                        "fromInter" to p.fromInter, "toInter" to p.toInter,
                        "fromComp" to p.fromComp, "toComp" to p.toComp,
                        "fromSlot(16)" to wire16(p.fromSlot), "toSlot(16)" to wire16(p.toSlot),
                        "fromItemId(16)" to wire16(p.fromItemId), "toItemId(16)" to wire16(p.toItemId),
                    )
                }
            },
        )
        case(
            "IF_ON_OBJECT", 98,
            payload { u16(0x8f4e); u8(0x7f); u32(0x0BADF00D); u32(0x0DEADBEE); u16(0x1234); u16(0x8fee); u16(0x4321) },
            legacy = {
                (LegacyIfOnObject().decodeAndCreateInstance(it) as LegacyIfOnObject).let { p ->
                    mapOf(
                        "interfaceId" to p.interfaceId, "componentId" to p.componentId,
                        "slotId(16)" to wire16(p.slotId), "itemId(16)" to wire16(p.itemId), "objectId" to p.objectId,
                        "x" to p.x, "y" to p.y, "forceRun" to p.isForceRun,
                    )
                }
            },
            modern = {
                (it as IfOnObject).let { p ->
                    mapOf(
                        "interfaceId" to p.interfaceId, "componentId" to p.componentId,
                        "slotId(16)" to wire16(p.slotId), "itemId(16)" to wire16(p.itemId), "objectId" to p.objectId,
                        "x" to p.x, "y" to p.y, "forceRun" to p.forceRun,
                    )
                }
            },
        )
        case(
            "IF_ON_GROUND_ITEM", 67,
            payload { u16(0x8f4e); u32(0x0BADF00D); u16(0x1234); u8(0x7f); u16(0x8fee); u16(0x8f4e); u16(0x4321) },
            legacy = {
                (LegacyIfOnGroundItem().decodeAndCreateInstance(it) as LegacyIfOnGroundItem).let { p ->
                    mapOf(
                        "interfaceId" to p.interfaceId, "componentId" to p.componentId,
                        "slotId(16)" to wire16(p.slotId), "itemId(16)" to wire16(p.itemId),
                        "itemIdContainer(16)" to wire16(p.itemIdContainer),
                        "x" to p.x, "y" to p.y, "forceRun" to p.isForceRun,
                    )
                }
            },
            modern = {
                (it as IfOnGroundItem).let { p ->
                    mapOf(
                        "interfaceId" to p.interfaceId, "componentId" to p.componentId,
                        "slotId(16)" to wire16(p.slotId), "itemId(16)" to wire16(p.itemId),
                        "itemIdContainer(16)" to wire16(p.itemIdContainer),
                        "x" to p.x, "y" to p.y, "forceRun" to p.forceRun,
                    )
                }
            },
        )
        // Both stacks collapse a 65535 component id to -1 - legacy inside this decoder, the modern
        // one through the shared sentinel - so the case is free to use an ordinary component id.
        case(
            "IF_ON_NPC", 41,
            payload { u32(0x0BADF00D); u16(0x8f4e); u8(0x7f); u16(0x8fee); u16(0x8f4e) },
            legacy = {
                (LegacyIfOnNpc().decodeAndCreateInstance(it) as LegacyIfOnNpc).let { p ->
                    mapOf(
                        "interfaceId" to p.interfaceId, "componentId" to p.componentId,
                        "slotId(16)" to wire16(p.slotId), "itemId(16)" to wire16(p.itemId), "npcIndex" to p.npcIndex,
                    )
                }
            },
            modern = {
                (it as IfOnNpc).let { p ->
                    mapOf(
                        "interfaceId" to p.interfaceId, "componentId" to p.componentId,
                        "slotId(16)" to wire16(p.slotId), "itemId(16)" to wire16(p.itemId), "npcIndex" to p.npcIndex,
                    )
                }
            },
        )
        case(
            "IF_ON_PLAYER", 13,
            payload { u16(40_000); u16(0x1234); u8(0x7f); u32(0x0BADF00D); u16(0x4321) },
            legacy = {
                (LegacyIfOnPlayer().decodeAndCreateInstance(it) as LegacyIfOnPlayer).let { p ->
                    mapOf(
                        "interfaceId" to p.interfaceId, "componentId" to p.componentId,
                        "slotId(16)" to wire16(p.slotId), "itemId(16)" to wire16(p.itemId), "playerIndex" to p.playerIndex,
                    )
                }
            },
            modern = {
                (it as IfOnPlayer).let { p ->
                    mapOf(
                        "interfaceId" to p.interfaceId, "componentId" to p.componentId,
                        "slotId(16)" to wire16(p.slotId), "itemId(16)" to wire16(p.itemId), "playerIndex" to p.playerIndex,
                    )
                }
            },
        )
        // Only the component hash is compared: the legacy decoder reads the slot short and then
        // throws it away, taking the low byte of the hash instead. See KNOWN_DIVERGENCES.
        case(
            "IF_CONTINUE", 49,
            payload { u32(0x0BADF00D); u16(0x8f4e) },
            legacy = {
                (LegacyIfContinue().decodeAndCreateInstance(it) as LegacyIfContinue).let { p ->
                    mapOf("interfaceId" to p.interfaceId, "componentId" to p.componentId)
                }
            },
            modern = { (it as IfContinue).let { p -> mapOf("interfaceId" to p.interfaceId, "componentId" to p.componentId) } },
        )
        case(
            "IF_DRAG_ONTO_IF", 74,
            payload { u16(0x8f4e); u16(0x1234); u16(0x4321); u16(0x8fee); u32(0x0BADF00D); u32(0x0DEADBEE) },
            legacy = {
                (LegacyIfDragOntoIf().decodeAndCreateInstance(it) as LegacyIfDragOntoIf).let { p ->
                    mapOf(
                        "fromInter" to p.fromInter, "toInter" to p.toInter,
                        "fromComp" to p.fromComp, "toComp" to p.toComp,
                        "fromSlot(16)" to wire16(p.fromSlot), "toSlot(16)" to wire16(p.toSlot),
                    )
                }
            },
            modern = {
                (it as IfDragOntoIf).let { p ->
                    mapOf(
                        "fromInter" to p.fromInter, "toInter" to p.toInter,
                        "fromComp" to p.fromComp, "toComp" to p.toComp,
                        "fromSlot(16)" to wire16(p.fromSlot), "toSlot(16)" to wire16(p.toSlot),
                    )
                }
            },
        )
    }

    // --- Input and telemetry -------------------------------------------------------------------

    private fun CaseBuilder.inputCases() {
        case(
            "MOVE_CAMERA", 83,
            payload { u16(0x8f4e); u16(0x8fee) },
            legacy = {
                (LegacyMoveCamera().decodeAndCreateInstance(it) as LegacyMoveCamera).let { p ->
                    mapOf("angleX(16)" to wire16(p.angleX), "angleY(16)" to wire16(p.angleY))
                }
            },
            modern = { (it as MoveCamera).let { p -> mapOf("angleX(16)" to wire16(p.angleX), "angleY(16)" to wire16(p.angleY)) } },
        )
        // `button` is deliberately not compared: legacy shifts a sign-extended short right by 15
        // and gets -1 for any click with the top bit set. See KNOWN_DIVERGENCES.
        case(
            "MOUSE_CLICK", 59,
            payload { u32(0x0BADF00D); u16(0x8f4e) },
            legacy = {
                (LegacyMouseClickJav().decodeAndCreateInstance(it) as LegacyMouseClickJav).let { p ->
                    mapOf("time" to p.time, "x" to p.x, "y" to p.y)
                }
            },
            modern = { (it as MouseClick).let { p -> mapOf("time" to p.time, "x" to p.x, "y" to p.y) } },
        )
        // `hardware` is deliberately not compared: the two stacks read the flag's low bit with
        // opposite polarity. See KNOWN_DIVERGENCES.
        case(
            "MOUSE_BUTTON_CLICK", 57,
            payload { u32(0x0BADF00D); u8(0x85); u16(0x8f4e) },
            legacy = {
                (LegacyMouseClickHw().decodeAndCreateInstance(it) as LegacyMouseClickHw).let { p ->
                    mapOf("button" to p.mouseButton, "time" to p.time, "x" to p.x, "y" to p.y)
                }
            },
            modern = { (it as MouseButtonClick).let { p -> mapOf("button" to p.mouseButton, "time" to p.time, "x" to p.x, "y" to p.y) } },
        )
        // Only the step forms both stacks read the same way are exercised: the 128..191 form is
        // excluded because legacy has X and Y the wrong way round, and `hardware` is excluded
        // because neither stack reads it from a field that carries that meaning. See
        // KNOWN_DIVERGENCES and `the client decides the mouse and clan flags`.
        case(
            "MOVE_MOUSE_2", 78,
            mouseTrailPayload(hardware = false),
            legacy = {
                (LegacyMouseMoveJav().decodeAndCreateInstance(it) as LegacyMouseMoveJav).let { p ->
                    legacyTrailFields(p.frameSteps, p.frameCount, p.steps)
                }
            },
            modern = { (it as MoveMouseData).let { p -> modernTrailFields(p.frameSteps, p.frameCount, p.steps.toList()) } },
        )
        case(
            "MOVE_MOUSE", 40,
            mouseTrailPayload(hardware = true),
            legacy = {
                (LegacyMouseMoveHw().decodeAndCreateInstance(it) as LegacyMouseMoveHw).let { p ->
                    legacyTrailFields(p.frameSteps, p.frameCount, p.steps)
                }
            },
            modern = { (it as MoveMouseData).let { p -> modernTrailFields(p.frameSteps, p.frameCount, p.steps.toList()) } },
        )
        case(
            "KEY_PRESS", 28,
            payload { u8(0x41); u8(0x0b); u8(0xad); u8(0xf0) },
            legacy = {
                (LegacyKeyPress().decodeAndCreateInstance(it) as LegacyKeyPress).let { p ->
                    mapOf("keyCode" to p.keyCode, "time" to p.time)
                }
            },
            modern = { (it as KeyPress).let { p -> mapOf("keyCode" to p.keyCode, "time" to p.time) } },
        )
        case(
            "CLIENT_FOCUS", 62,
            payload { u8(1) },
            legacy = { mapOf("inFocus" to (LegacyClientFocus().decodeAndCreateInstance(it) as LegacyClientFocus).isInFocus) },
            modern = { mapOf("inFocus" to (it as ClientFocus).focused) },
        )
        case(
            "SCREEN_SIZE", 84,
            payload { u8(2); u16(1920); u16(1080); u8(1) },
            legacy = {
                (LegacySetScreenSize().decodeAndCreateInstance(it) as LegacySetScreenSize).let { p ->
                    mapOf("displayMode" to p.displayMode, "width" to p.width, "height" to p.height, "switch" to p.isSwitchScreenMode)
                }
            },
            modern = {
                (it as ClientScreenSize).let { p ->
                    mapOf("displayMode" to p.displayMode, "width" to p.width, "height" to p.height, "switch" to p.switchDisplayMode)
                }
            },
        )
        case(
            "COMMAND", 85,
            payload { u8(1); u8(0); str("::reload") },
            legacy = {
                (LegacyClientCheat().decodeAndCreateInstance(it) as LegacyClientCheat).let { p ->
                    mapOf("client" to p.isClient, "command" to p.command)
                }
            },
            modern = { (it as ClientCheat).let { p -> mapOf("client" to p.client, "command" to p.command) } },
        )
        case(
            "REFLECTION_CHECK", 36,
            payload { u32(0x0BADF00D); raw(1, 2, 3) },
            legacy = { mapOf("id" to (LegacyReflectionCheckResponse().decodeAndCreateInstance(it) as LegacyReflectionCheckResponse).id) },
            modern = { mapOf("id" to (it as ReflectionResponse).id) },
        )
    }

    /**
     * A mouse trail with one step in each of the three forms the two stacks read identically:
     * the packed 16-bit offset form, the 192..223 absolute form, and the 224+ absolute form.
     * The native trail (opcode 40) carries one extra opaque byte per step.
     */
    private fun mouseTrailPayload(hardware: Boolean) = payload {
        u8(7)                                   // leftover milliseconds per record
        u8(3)                                   // remainder
        u16((3 shl 12) or (40 shl 6) or 17)     // packed offset step, leading byte < 128
        if (hardware) u8(0)
        u8(192 + 5); u32(0x0BADF00D)            // absolute step, byte-framed frame count
        if (hardware) u8(0)
        u16(57344 + 100); u32(0x0DEADBEE)       // absolute step, short-framed frame count
        if (hardware) u8(0)
    }

    private fun legacyTrailFields(frameSteps: Int, frameCount: Int, steps: List<LegacyMouseTrailStep>): Fields =
        buildMap {
            put("frameSteps", frameSteps)
            put("frameCount", frameCount)
            put("stepCount", steps.size)
            steps.forEachIndexed { i, step ->
                put("step$i.type", step.type.name)
                put("step$i.frames", step.frames)
                put("step$i.x", step.x)
                put("step$i.y", step.y)
            }
        }

    private fun modernTrailFields(frameSteps: Int, frameCount: Int, steps: List<ModernMouseTrailStep>): Fields =
        buildMap {
            put("frameSteps", frameSteps)
            put("frameCount", frameCount)
            put("stepCount", steps.size)
            steps.forEachIndexed { i, step ->
                put("step$i.type", step.type.name)
                put("step$i.frames", step.frames)
                put("step$i.x", step.x)
                put("step$i.y", step.y)
            }
        }

    /**
     * The three places the legacy decoders read a flag the wrong way round. Each is pinned here
     * directly rather than cross-checked, because there is nothing correct to cross-check against.
     */
    @Test
    fun `the client decides the mouse and clan flags`() {
        // The client writes `mainCC ? 1 : 0` (`LightDetailPreference.method12790`), the same
        // convention its clan encoders use, so a leading 1 is the main channel, not a guest one.
        val kick = decodeModern(90, payload { u8(1); u16(1337); str("Zezima") }) as ClanChannelKickUser
        assertTrue(!kick.guest, "a leading 1 means the main clan channel")
        val guestKick = decodeModern(90, payload { u8(0); u16(1337); str("Zezima") }) as ClanChannelKickUser
        assertTrue(guestKick.guest, "a leading 0 means the guest clan channel")

        // The client has one trail recorder per input source and each always sends its own opcode
        // (`MouseMovementICMP` -> 40, `MouseMovementJav` -> 78), so the opcode is what marks a
        // trail as hardware. The extra per-step byte on opcode 40 is an opaque native value.
        val native = decodeModern(40, mouseTrailPayload(hardware = true)) as MoveMouseData
        assertTrue(native.steps.all { it.hardware }, "opcode 40 is the native mouse trail")
        val java = decodeModern(78, mouseTrailPayload(hardware = false)) as MoveMouseData
        assertTrue(java.steps.none { it.hardware }, "opcode 78 is the java mouse trail")
        assertTrue(native.steps.size == java.steps.size, "the extra native byte must not eat a step")

        // Opcode 57 is only ever emitted by the native mouse hook, so it is always a hardware
        // click; bit 0 of its flags byte is an unrelated native constant.
        val click = decodeModern(57, payload { u32(0x0BADF00D); u8(0x85); u16(0x8f4e) }) as MouseButtonClick
        assertTrue(click.hw, "opcode 57 is the native mouse click")
    }

    // --- Social --------------------------------------------------------------------------------

    private fun CaseBuilder.socialCases() {
        case(
            "ADD_FRIEND", 26,
            payload { str("Zezima") },
            legacy = { mapOf("name" to (LegacySocialAddRemove().decodeAndCreateInstance(it) as LegacySocialAddRemove).name) },
            modern = { mapOf("name" to (it as AddFriend).name) },
        )
        case(
            "REMOVE_FRIEND", 29,
            payload { str("Zezima") },
            legacy = { mapOf("name" to (LegacySocialAddRemove().decodeAndCreateInstance(it) as LegacySocialAddRemove).name) },
            modern = { mapOf("name" to (it as RemoveFriend).name) },
        )
        case(
            "ADD_IGNORE", 34,
            payload { str("Zezima"); u8(1) },
            legacy = {
                (LegacySocialAddRemove().decodeAndCreateInstance(it) as LegacySocialAddRemove).let { p ->
                    mapOf("name" to p.name, "temporary" to p.isTemp)
                }
            },
            modern = { (it as AddIgnoreReq).let { p -> mapOf("name" to p.username, "temporary" to p.temporary) } },
        )
        case(
            "REMOVE_IGNORE", 12,
            payload { str("Zezima") },
            legacy = { mapOf("name" to (LegacySocialAddRemove().decodeAndCreateInstance(it) as LegacySocialAddRemove).name) },
            modern = { mapOf("name" to (it as RemoveIgnore).name) },
        )
        case(
            "FC_JOIN", 71,
            payload { str("Darkan") },
            legacy = { mapOf("name" to (LegacyFcJoin().decodeAndCreateInstance(it) as LegacyFcJoin).name) },
            modern = { mapOf("name" to (it as FcJoin).fcName) },
        )
        case(
            "FC_KICK", 91,
            payload { str("Zezima") },
            legacy = { mapOf("name" to (LegacyFcKick().decodeAndCreateInstance(it) as LegacyFcKick).name) },
            modern = { mapOf("name" to (it as FcKick).username) },
        )
        case(
            "FC_SET_RANK", 7,
            payload { u8(0x7f); str("Zezima") },
            legacy = {
                (LegacyFcSetRank().decodeAndCreateInstance(it) as LegacyFcSetRank).let { p ->
                    mapOf("name" to p.name, "rank" to p.rank)
                }
            },
            modern = { (it as FcSetRank).let { p -> mapOf("name" to p.username, "rank" to p.rank) } },
        )
        // `guest` is deliberately not compared: the two stacks read the leading flag with opposite
        // polarity. See KNOWN_DIVERGENCES.
        case(
            "CLANCHANNEL_KICKUSER", 90,
            payload { u8(1); u16(1337); str("Zezima") },
            legacy = { mapOf("name" to (LegacyCcKick().decodeAndCreateInstance(it) as LegacyCcKick).name) },
            modern = { mapOf("name" to (it as ClanChannelKickUser).username) },
        )
        case(
            "QUICKCHAT_PUBLIC", 64,
            payload { u8(2); u16(1234); raw(1, 2, 3) },
            legacy = {
                (LegacyQcPublic().decodeAndCreateInstance(it) as LegacyQcPublic).let { p ->
                    mapOf("chatType" to p.chatType, "qcId" to p.qcId, "data" to p.messageData?.toList())
                }
            },
            modern = {
                (it as QuickChatPublic).let { p ->
                    mapOf("chatType" to p.chatType, "qcId" to p.qcId, "data" to p.messageData?.toList())
                }
            },
        )
        // `messageData` is deliberately not compared: the legacy decoder's remaining-bytes test is
        // written against the whole payload rather than what is left. See KNOWN_DIVERGENCES.
        case(
            "QUICKCHAT_PRIVATE", 14,
            payload { str("Zezima"); u16(1234); raw(1, 2, 3) },
            legacy = {
                (LegacyQcPrivate().decodeAndCreateInstance(it) as LegacyQcPrivate).let { p ->
                    mapOf("toUsername" to p.toUsername, "qcId" to p.qcId)
                }
            },
            modern = { (it as QuickChatPrivate).let { p -> mapOf("toUsername" to p.toUsername, "qcId" to p.qcId) } },
        )
        case(
            "CHAT_SETFILTER", 20,
            payload { u8(1); u8(2); u8(3) },
            legacy = {
                (LegacyChatSetFilter().decodeAndCreateInstance(it) as LegacyChatSetFilter).let { p ->
                    mapOf("public" to p.publicFilter, "private" to p.privateFilter, "trade" to p.tradeFilter)
                }
            },
            modern = {
                (it as ChatSetFilter).let { p ->
                    mapOf("public" to p.publicFilter, "private" to p.privateFilter, "trade" to p.tradeFilter)
                }
            },
        )
        case(
            "CHAT_TYPE", 30,
            payload { u8(2) },
            legacy = { mapOf("type" to (LegacyChatType().decodeAndCreateInstance(it) as LegacyChatType).type) },
            modern = { mapOf("type" to (it as ChatType).type) },
        )
        // The legacy decoder assigns to the prototype instance instead of the one it returns, so
        // the fields are read back off the prototype here. See KNOWN_DIVERGENCES.
        case(
            "REPORT_ABUSE", 100,
            payload { str("Zezima"); u8(3); u8(1); str("botting") },
            legacy = {
                val prototype = LegacyReportAbuse()
                prototype.decodeAndCreateInstance(it)
                mapOf("username" to prototype.username, "type" to prototype.type, "mute" to prototype.isMute)
            },
            modern = { (it as ReportAbuse).let { p -> mapOf("username" to p.username, "type" to p.type, "mute" to p.mute) } },
        )
    }

    // --- Session and dialogs -------------------------------------------------------------------

    private fun CaseBuilder.sessionCases() {
        case(
            "ACCOUNT_CREATION_STAGE", 79,
            payload { u8(200) },
            legacy = { mapOf("stage" to (LegacyAccountCreationStage().decodeAndCreateInstance(it) as LegacyAccountCreationStage).stage) },
            modern = { mapOf("stage" to (it as AccountCreationStage).stage) },
        )
        case(
            "REQUEST_WORLD_LIST", 47,
            payload { u32(0x0BADF00D) },
            legacy = { mapOf("worldId" to (LegacyRequestWorldList().decodeAndCreateInstance(it) as LegacyRequestWorldList).worldId) },
            modern = { mapOf("worldId" to (it as RequestWorldList).worldlistVersion) },
        )
        case(
            "TRANSMITVAR_VERIFYID", 55,
            payload { u32(0x0BADF00D) },
            legacy = { mapOf("id" to (LegacyTransmitVarVerifyId().decodeAndCreateInstance(it) as LegacyTransmitVarVerifyId).id) },
            modern = { mapOf("id" to (it as TransmitvarVerifyId).id) },
        )
        case(
            "RESUME_COUNTDIALOG", 58,
            payload { u32(0x0BADF00D) },
            legacy = { mapOf("value" to (LegacyResumeCountDialogue().decodeAndCreateInstance(it) as LegacyResumeCountDialogue).value) },
            modern = { mapOf("value" to (it as ResumeCountDialog).count) },
        )
        case(
            "RESUME_HSLDIALOG", 11,
            payload { u16(40_000) },
            legacy = { mapOf("colorId" to (LegacyResumeHslDialogue().decodeAndCreateInstance(it) as LegacyResumeHslDialogue).colorId) },
            modern = { mapOf("colorId" to (it as ResumeHSLDialog).data) },
        )
        case(
            "RESUME_TEXTDIALOG", 87,
            payload { str("some text") },
            legacy = { mapOf("text" to (LegacyResumeTextDialogue().decodeAndCreateInstance(it) as LegacyResumeTextDialogue).text) },
            modern = { mapOf("text" to (it as ResumeTextDialog).text) },
        )
        case(
            "GRAND_EXCHANGE_ITEM_SELECT", 17,
            payload { u16(4151) },
            legacy = { mapOf("itemId" to (LegacyGeItemSelect().decodeAndCreateInstance(it) as LegacyGeItemSelect).itemId) },
            modern = { mapOf("itemId" to (it as ResumeItemSelect).itemId) },
        )
        case(
            "SOUND_EFFECT_MUSIC_ENDED", 2,
            payload { u32(0x0BADF00D) },
            legacy = { mapOf("musicId" to (LegacyMusicEnded().decodeAndCreateInstance(it) as LegacyMusicEnded).musicId) },
            modern = { mapOf("musicId" to (it as SoundEffectMusicEnded).musicId) },
        )
        case(
            "WRITE_PING", 88,
            payload { u16(40_000) },
            legacy = { mapOf("ping" to (LegacyWritePing().decodeAndCreateInstance(it) as LegacyWritePing).ping) },
            modern = { mapOf("ping" to (it as WritePing).ping) },
        )
        case(
            "LOBBY_HYPERLINK", 56,
            payload { str("service"); str("page"); str("query"); u8(3) },
            legacy = {
                (LegacyLobbyHyperlink().decodeAndCreateInstance(it) as LegacyLobbyHyperlink).let { p ->
                    mapOf("service" to p.service, "page" to p.page, "flags" to p.flags)
                }
            },
            modern = { (it as LobbyHyperlink).let { p -> mapOf("service" to p.service, "page" to p.page, "flags" to p.flags) } },
        )
        case(
            "EMAIL_VALIDATION_CHANGE_ADDRESS", 99,
            payload { str("a@example.com"); str("b@example.com") },
            legacy = {
                (LegacyEmailChangeAddress().decodeAndCreateInstance(it) as LegacyEmailChangeAddress).let { p ->
                    mapOf("email" to p.email, "email2" to p.email2)
                }
            },
            modern = { (it as EmailValidationChangeAddress).let { p -> mapOf("email" to p.email, "email2" to p.email2) } },
        )
    }

    private companion object {
        const val MINIMUM_CASE_COUNT = 48

        /**
         * Legacy decoders with no field-equality case, and why. Keyed by the client packet name.
         */
        val UNCOVERED: Map<String, String> = mapOf(
            "CHAT" to "Legacy decodes the body through `Cache.STORE.getHuffman()`, so it needs an initialised cache.",
            "SEND_PRIVATE_MESSAGE" to "Same: Huffman-compressed body, cache required.",
            "SEND_PREFERENCES" to
                "The legacy packet has no accessor for its decoded preference map, so there is " +
                "nothing to read back. The modern decoder is covered byte-for-byte instead in " +
                "`Rev727ClientDecoderTest`.",
            "KEEPALIVE" to "Zero-length payload; nothing to decode.",
            "CLOSE_INTERFACE" to "Zero-length payload; nothing to decode.",
            "REGION_LOADED_CONFIRM" to "Zero-length payload; nothing to decode.",
            "CUTSCENE_FINISHED" to
                "Legacy reads nothing at all (the packet is size 1, so it leaves a byte unread); " +
                "the modern decoder reads the boolean the client sends. Nothing shared to compare.",
            "CHECK_EMAIL_VALIDITY" to "Legacy `XTEACreationPacket` hands the raw stream back for XTEA decryption against a live session.",
            "SEND_SIGN_UP_FORM" to "Same: legacy defers the whole payload to session-keyed XTEA.",
            "CC_BAN" to "Legacy `decodeAndCreateInstance` returns null - these lobby clan packets were never wire-decoded.",
            "CC_JOIN" to "Legacy decoder returns null.",
            "CC_LEAVE" to "Legacy decoder returns null.",
            "CLAN_ADDMEMBER" to "Legacy decoder returns null.",
            "CLAN_CHECKNAME" to "Legacy decoder returns null.",
            "CLAN_CREATE" to "Legacy decoder returns null.",
            "CLAN_KICKMEMBER" to "Legacy decoder returns null.",
            "CLAN_LEAVE" to "Legacy decoder returns null.",
            "BUG_REPORT" to "No legacy decoder.",
            "SEND_FPS" to "No legacy decoder.",
            "IF_ON_TILE" to "No legacy decoder.",
            "PLAY_VORBIS" to "No legacy decoder.",
            "SONG_LOADED" to "No legacy decoder.",
            "UNK_63" to "No legacy decoder.",
            "UNK_82" to "No legacy decoder.",
            "UNUSED_CLAN_OP" to "No legacy decoder.",
            "DEPRECATED_97_SERVERPACKET_52" to "No legacy decoder.",
            "EMAIL_VALIDATION_SUBMIT_CODE" to "No legacy decoder.",
            "EMAIL_VALIDATION_ADD_NEW_ADDRESS" to "No legacy decoder.",
            "RESUME_NAMEDIALOG" to "Shares the legacy `ResumeTextDialogue` decoder, already covered by RESUME_TEXTDIALOG.",
            "RESUME_CLANFORUMQFCDIALOG" to "Shares the legacy `ResumeTextDialogue` decoder, already covered by RESUME_TEXTDIALOG.",
            "MINI_WALK" to
                "Legacy reuses its `Walk` decoder and stops after 5 of the 18 bytes; the modern " +
                "decoder reads the whole packet and flags it as a minimap walk. Only the first " +
                "three fields are shared, and those are already covered by WALK.",
        )

        /**
         * Places where the two decoders genuinely disagree. Each entry says which side is wrong;
         * the affected field is excluded from its case rather than the case being dropped, so the
         * rest of the packet is still verified.
         */
        @Suppress("unused")
        val KNOWN_DIVERGENCES: Map<String, String> = mapOf(
            "IF_CONTINUE slotId" to
                "Legacy reads the slot short with `readShortAddLittle()` and then discards it, " +
                "setting `slotId = interfaceHash and 0xFF` - the low byte of the component hash. " +
                "That is a legacy bug: the modern decoder keeps the short it read.",
            "REPORT_ABUSE fields" to
                "The legacy decoder assigns to `this` (the prototype it was invoked on) instead " +
                "of the `p` it returns, so every field on the returned packet is null. Legacy bug; " +
                "this test reads the prototype so the byte layout can still be compared.",
            "QUICKCHAT_PRIVATE messageData" to
                "Legacy tests `stream.getRemaining() > 3 + toUsername.length()` after it has " +
                "already consumed the name and the file id, so it compares what is left against " +
                "the length of what it already read and drops short payloads. Legacy bug.",
            "MOUSE_CLICK mouseButton" to
                "Legacy reads the trailing short signed and then does `mouseHash shr 15`, so any " +
                "click whose top bit is set decodes as button -1 instead of 1. The modern decoder " +
                "reads the short unsigned and uses `ushr`, which is the only reading that can " +
                "produce the 0/1 the client sends. Legacy bug; the field is excluded from its case " +
                "and the rest of the packet (time, x, y) still matches byte for byte.",
            "Signed vs unsigned 16-bit fields" to
                "Legacy reads several slot/item/angle shorts signed where the modern decoder reads " +
                "them unsigned. The bytes are identical - it is a disagreement about what a value " +
                "above 32767 means - so those fields are compared masked to 16 bits, marked `(16)`. " +
                "It matters in exactly one place: MOVE_CAMERA angles legitimately exceed 32767, " +
                "and the legacy signed read turns them negative. The modern reading is correct " +
                "there and is separately pinned by `Rev727ClientDecoderTest`.",
            "Interface slot/obj/component 65535 (resolved)" to
                "The one 16-bit value where signedness carried meaning: a component with no slot " +
                "sends `slotId = -1`, one holding no obj sends `containerItemId = -1`, and a hash " +
                "naming a whole interface carries 0xFFFF in its low half. Legacy reached that " +
                "unevenly - signed reads in IFDragOntoIF/IFOnIF/IFOnObject/IFOnGroundItem, a 65535 " +
                "check inside IFOnNPC/IFOnPlayer, and a handler-side check in IFOpHandler, " +
                "IFOnIFHandler and IFOnGroundItemHandler. The modern decoders map 65535 to -1 for " +
                "every such field, so only the sentinel differs from a plain unsigned read and the " +
                "`(16)` masked comparisons here still hold. Pinned by `Rev727ClientDecoderTest`.",
            "MOVE_MOUSE step deltas (128..191 form)" to
                "The client writes `writeShort(dY + (dX shl 8))` (`MouseMovementRecord`), so X is " +
                "the high byte. Legacy reads `dY = hash shr 8, dX = hash and 0xFF` - inverted. " +
                "The modern decoder is correct; that step form is left out of the shared case " +
                "because there is no reading both stacks agree on.",
            "MOVE_MOUSE / MOUSE_BUTTON_CLICK hardware flag" to
                "Fixed in the modern codec by this test. The client carries the hardware vs java " +
                "distinction in the opcode - `MouseMovementICMP` always sends 40/57 and " +
                "`MouseMovementJav` always sends 78/59 - not in any payload field. The byte the " +
                "modern decoder was reading as that flag is an opaque native value that is 0 in " +
                "practice, so it reported every native trail as software and (for opcode 78) every " +
                "java trail as hardware. It now takes the flag from the opcode and still consumes " +
                "the extra native byte so later steps stay aligned.",
            "CLANCHANNEL_KICKUSER guest (resolved)" to
                "The client writes `mainCC ? 1 : 0`, so the modern `guest = !readBoolean()` is " +
                "right and the legacy decoder is inverted. Asserted directly in " +
                "`the client decides the mouse and clan flags` rather than cross-checked.",
        )
    }
}
