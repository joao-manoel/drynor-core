package org.drynor.core.net.prot.revision.rev727

import com.rs.cache.loaders.ObjectType
import com.rs.cache.loaders.interfaces.IFEvents
import com.rs.cache.loaders.map.RegionSize as LegacyRegionSize
import com.rs.lib.game.Animation
import com.rs.lib.game.GroundItem
import com.rs.lib.game.HintIcon
import com.rs.lib.game.Item
import com.rs.lib.game.Projectile
import com.rs.lib.game.QuickChatMessage as LegacyQuickChatMessage
import com.rs.lib.game.SpotAnim as LegacySpotAnimModel
import com.rs.lib.game.Tile as LegacyTile
import com.rs.lib.game.WorldObject
import com.rs.lib.io.OutputStream as LegacyStream
import com.rs.lib.model.DisplayNamePair
import com.rs.lib.net.packets.PacketEncoder as LegacyEncoder
import com.rs.lib.net.packets.encoders.BlockMinimapState as LegacyBlockMinimapState
import com.rs.lib.net.packets.encoders.ChatFilterSettings as LegacyChatFilterSettings
import com.rs.lib.net.packets.encoders.ChatFilterSettingsPriv as LegacyChatFilterSettingsPriv
import com.rs.lib.net.packets.encoders.DrawOrder as LegacyDrawOrder
import com.rs.lib.net.packets.encoders.DynamicMapRegion as LegacyDynamicMapRegion
import com.rs.lib.net.packets.encoders.ExecuteCS2 as LegacyExecuteCS2
import com.rs.lib.net.packets.encoders.HintArrow as LegacyHintArrow
import com.rs.lib.net.packets.encoders.MapRegion as LegacyMapRegion
import com.rs.lib.net.packets.encoders.MinimapFlag as LegacyMinimapFlag
import com.rs.lib.net.packets.encoders.NPCUpdate as LegacyNpcUpdate
import com.rs.lib.net.packets.encoders.OpenURL as LegacyOpenUrl
import com.rs.lib.net.packets.encoders.PlayerOption as LegacyPlayerOption
import com.rs.lib.net.packets.encoders.PlayerUpdate as LegacyPlayerUpdate
import com.rs.lib.net.packets.encoders.ReflectionCheckRequest as LegacyReflectionCheckRequest
import com.rs.lib.net.packets.encoders.RunEnergy as LegacyRunEnergy
import com.rs.lib.net.packets.encoders.RunWeight as LegacyRunWeight
import com.rs.lib.net.packets.encoders.SendDevConsoleCommand as LegacySendDevConsoleCommand
import com.rs.lib.net.packets.encoders.SetCursor as LegacySetCursor
import com.rs.lib.net.packets.encoders.Sound as LegacySound
import com.rs.lib.net.packets.encoders.Sound.SoundType as LegacySoundType
import com.rs.lib.net.packets.encoders.SystemUpdateTimer as LegacySystemUpdateTimer
import com.rs.lib.net.packets.encoders.UpdateGESlot as LegacyUpdateGeSlot
import com.rs.lib.net.packets.encoders.UpdateItemContainer as LegacyUpdateItemContainer
import com.rs.lib.net.packets.encoders.UpdateStat as LegacyUpdateStat
import com.rs.lib.net.packets.encoders.WorldLoginDetails as LegacyWorldLoginDetails
import com.rs.lib.net.packets.encoders.accountcreation.CheckEmailResponse as LegacyCheckEmailResponse
import com.rs.lib.net.packets.encoders.accountcreation.CreateAccountResponse as LegacyCreateAccountResponse
import com.rs.lib.net.packets.encoders.camera.CamForceAngle as LegacyCamForceAngle
import com.rs.lib.net.packets.encoders.camera.CamLookAt as LegacyCamLookAt
import com.rs.lib.net.packets.encoders.camera.CamMoveTo as LegacyCamMoveTo
import com.rs.lib.net.packets.encoders.camera.CamRemoveRoof as LegacyCamRemoveRoof
import com.rs.lib.net.packets.encoders.camera.CamShake as LegacyCamShake
import com.rs.lib.net.packets.encoders.interfaces.IFCloseSub as LegacyIfCloseSub
import com.rs.lib.net.packets.encoders.interfaces.IFOpenTop as LegacyIfOpenTop
import com.rs.lib.net.packets.encoders.interfaces.IFSetAngle as LegacyIfSetAngle
import com.rs.lib.net.packets.encoders.interfaces.IFSetAnimation as LegacyIfSetAnimation
import com.rs.lib.net.packets.encoders.interfaces.IFSetClickMask as LegacyIfSetClickMask
import com.rs.lib.net.packets.encoders.interfaces.IFSetColor as LegacyIfSetColor
import com.rs.lib.net.packets.encoders.interfaces.IFSetEvents as LegacyIfSetEvents
import com.rs.lib.net.packets.encoders.interfaces.IFSetGraphic as LegacyIfSetGraphic
import com.rs.lib.net.packets.encoders.interfaces.IFSetHide as LegacyIfSetHide
import com.rs.lib.net.packets.encoders.interfaces.IFSetItem as LegacyIfSetItem
import com.rs.lib.net.packets.encoders.interfaces.IFSetModel as LegacyIfSetModel
import com.rs.lib.net.packets.encoders.interfaces.IFSetNPCHead as LegacyIfSetNpcHead
import com.rs.lib.net.packets.encoders.interfaces.IFSetPlayerHead as LegacyIfSetPlayerHead
import com.rs.lib.net.packets.encoders.interfaces.IFSetPlayerHeadIgnoreWorn as LegacyIfSetPlayerHeadIgnoreWorn
import com.rs.lib.net.packets.encoders.interfaces.IFSetPlayerHeadOther as LegacyIfSetPlayerHeadOther
import com.rs.lib.net.packets.encoders.interfaces.IFSetPlayerModel as LegacyIfSetPlayerModel
import com.rs.lib.net.packets.encoders.interfaces.IFSetPlayerModelOther as LegacyIfSetPlayerModelOther
import com.rs.lib.net.packets.encoders.interfaces.IFSetPosition as LegacyIfSetPosition
import com.rs.lib.net.packets.encoders.interfaces.IFSetScrollPos as LegacyIfSetScrollPos
import com.rs.lib.net.packets.encoders.interfaces.IFSetTargetParam as LegacyIfSetTargetParam
import com.rs.lib.net.packets.encoders.interfaces.IFSetText as LegacyIfSetText
import com.rs.lib.net.packets.encoders.interfaces.IFSetTextFont as LegacyIfSetTextFont
import com.rs.lib.net.packets.encoders.interfaces.opensub.IFOpenSub as LegacyIfOpenSub
import com.rs.lib.net.packets.encoders.interfaces.opensub.IFOpenSubActiveGroundItem as LegacyIfOpenSubActiveGroundItem
import com.rs.lib.net.packets.encoders.interfaces.opensub.IFOpenSubActiveNPC as LegacyIfOpenSubActiveNpc
import com.rs.lib.net.packets.encoders.interfaces.opensub.IFOpenSubActiveObject as LegacyIfOpenSubActiveObject
import com.rs.lib.net.packets.encoders.interfaces.opensub.IFOpenSubActivePlayer as LegacyIfOpenSubActivePlayer
import com.rs.lib.net.packets.encoders.social.IgnoreList as LegacyIgnoreList
import com.rs.lib.net.packets.encoders.social.MessageGame as LegacyMessageGame
import com.rs.lib.net.packets.encoders.social.QuickChatPrivate as LegacyQuickChatPrivate
import com.rs.lib.net.packets.encoders.updatezone.AddObject as LegacyAddObject
import com.rs.lib.net.packets.encoders.updatezone.CreateGroundItem as LegacyCreateGroundItem
import com.rs.lib.net.packets.encoders.updatezone.ObjectAnim as LegacyObjectAnim
import com.rs.lib.net.packets.encoders.updatezone.ObjectPrefetch as LegacyObjectPrefetch
import com.rs.lib.net.packets.encoders.updatezone.ProjAnim as LegacyProjAnim
import com.rs.lib.net.packets.encoders.updatezone.ProjAnimHalfSq as LegacyProjAnimHalfSq
import com.rs.lib.net.packets.encoders.updatezone.RemoveGroundItem as LegacyRemoveGroundItem
import com.rs.lib.net.packets.encoders.updatezone.RemoveObject as LegacyRemoveObject
import com.rs.lib.net.packets.encoders.updatezone.RevealGroundItem as LegacyRevealGroundItem
import com.rs.lib.net.packets.encoders.updatezone.SetGroundItemAmount as LegacySetGroundItemAmount
import com.rs.lib.net.packets.encoders.updatezone.TileMessage as LegacyTileMessage
import com.rs.lib.net.packets.encoders.updatezone.TileSound as LegacyTileSound
import com.rs.lib.net.packets.encoders.updatezone.TileSpotAnim as LegacyTileSpotAnim
import com.rs.lib.net.packets.encoders.updatezone.UpdateZoneFull as LegacyUpdateZoneFull
import com.rs.lib.net.packets.encoders.updatezone.UpdateZonePartial as LegacyUpdateZonePartial
import com.rs.lib.net.packets.encoders.updatezone.UpdateZonePartialEnclosed as LegacyUpdateZonePartialEnclosed
import com.rs.lib.net.packets.encoders.vars.SetVarc as LegacySetVarc
import com.rs.lib.net.packets.encoders.vars.SetVarcString as LegacySetVarcString
import com.rs.lib.net.packets.encoders.vars.SetVarp as LegacySetVarp
import com.rs.lib.net.packets.encoders.vars.SetVarpBit as LegacySetVarpBit
import com.rs.lib.net.packets.encoders.zonespecific.ObjectAnimSpecific as LegacyObjectAnimSpecific
import com.rs.lib.net.packets.encoders.zonespecific.ProjAnimSpecific as LegacyProjAnimSpecific
import com.rs.lib.net.packets.encoders.zonespecific.SpotAnimSpecific as LegacySpotAnimSpecific
import com.rs.lib.util.Utils
import com.rs.lib.util.reflect.ReflectionCheck as LegacyReflectionCheck
import com.rs.lib.util.reflect.ReflectionChecks as LegacyReflectionChecks
import io.ktor.utils.io.*
import kotlinx.coroutines.runBlocking
import org.drynor.core.clientwatch.ReflectionCheck
import org.drynor.core.clientwatch.ReflectionCheckType
import org.drynor.core.model.ChatMessageType
import org.drynor.core.model.RegionSize
import org.drynor.core.net.prot.Codec
import org.drynor.core.net.prot.ServerProt
import org.drynor.core.net.prot.ZonePacketEncoder
import org.drynor.core.net.prot.dto.CheckEmailReplyOpcode
import org.drynor.core.net.prot.dto.CreateAccountReplyOpcode
import org.drynor.core.net.prot.dto.GrandExchangeOffer
import org.drynor.core.net.prot.dto.IgnoreEntry
import org.drynor.core.net.prot.dto.InvSlotUpdate
import org.drynor.core.net.prot.packet.*
import org.drynor.core.social.QuickChatMessage
import world.gregs.voidps.type.Tile
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Differential test: every rewritten revision 727 server encoder is asked to produce exactly the
 * payload bytes the corresponding encoder in the legacy `rs.drynor:core` library produces, from
 * the same logical inputs.
 *
 * ### What is compared, and why that is like for like
 *
 * Only the packet **body**. The opcode and length prefix are framing, and the two stacks frame in
 * different places: legacy [LegacyEncoder.writeToStream] writes opcode + length itself, while the
 * modern stack does that in `Session.writePacket` and hands the codec a bare payload channel. So
 * this calls `encodeBody` directly on the legacy side and `Codec.encodeServerProt` on the modern
 * side. ISAAC never enters the picture: it only ever touches the opcode byte(s), which are
 * separately pinned by [Rev727ProtCoverageTest].
 *
 * ### Reading a failure
 *
 * A mismatch is not automatically a modern bug - both stacks are fallible, and where they disagree
 * the 727 client decides. Resolved disagreements are recorded in [KNOWN_DIVERGENCES] with the
 * client evidence. Do not make a case pass by nudging its inputs.
 *
 * ### Field names are not wire semantics
 *
 * Several packets carry the same bytes under different field names in the two stacks (legacy
 * `TileMessage.delay` is the modern `priority`, and so on). Those cases pin the *wire* value on
 * both sides and say so in a comment; they are still genuine byte-equality checks.
 *
 * ### What is not compared
 *
 * [UNCOVERED] lists every legacy encoder with no byte-equality case, and why.
 */
class Rev727LegacyEncoderDifferentialTest {

    private lateinit var codec: Codec

    @BeforeTest
    fun setUp() {
        codec = register727()
    }

    /** One packet, encoded by both stacks from the same logical inputs. */
    private class Case(val name: String, val legacy: LegacyEncoder, val modern: ServerProt)

    private class CaseBuilder {
        val cases = mutableListOf<Case>()
        fun case(name: String, legacy: LegacyEncoder, modern: ServerProt) {
            cases.add(Case(name, legacy, modern))
        }
    }

    @Test
    fun `every covered encoder produces the legacy bytes`() {
        val cases = cases()
        val failures = mutableListOf<String>()
        for (case in cases) {
            val legacyBytes = try {
                encodeLegacy(case.legacy)
            } catch (e: Throwable) {
                failures.add("${case.name}: legacy encoder threw ${e::class.simpleName}: ${e.message}")
                continue
            }
            val modernBytes = try {
                encodeModern(case.modern)
            } catch (e: Throwable) {
                failures.add("${case.name}: modern encoder threw ${e::class.simpleName}: ${e.message}")
                continue
            }
            if (legacyBytes.isEmpty()) {
                // A case that encodes to nothing on both sides would "pass" without comparing
                // anything, so an empty payload is itself a failure: every packet here has a body.
                failures.add("${case.name}: legacy encoder produced an empty payload")
                continue
            }
            if (!legacyBytes.contentEquals(modernBytes)) {
                failures.add(describeMismatch(case, legacyBytes, modernBytes))
            }
        }
        assertTrue(
            failures.isEmpty(),
            "${failures.size} of ${cases.size} encoders disagree with the legacy library:\n\n" +
                failures.joinToString("\n\n")
        )
    }

    @Test
    fun `the harness detects a difference when there is one`() {
        // Negative control. Everything else in this class asserts that two encoders agree, which
        // is also what a harness that silently compares nothing would report, so pin that a real
        // difference is actually caught.
        val legacy = encodeLegacy(LegacyRunEnergy(97))
        val modern = encodeModern(RunEnergy(98))
        assertTrue(legacy.isNotEmpty() && modern.isNotEmpty(), "both sides must produce bytes")
        assertTrue(!legacy.contentEquals(modern), "a one-byte difference must not compare equal")
    }

    @Test
    fun `the coverage bookkeeping is honest`() {
        // If a packet gains a case, it must leave the uncovered list, and vice versa. Without this
        // the documented "not covered, because..." list quietly rots into a lie.
        val covered = cases().map { it.modern::class.simpleName }.toSet()
        val overlap = covered.intersect(UNCOVERED.keys)
        assertTrue(overlap.isEmpty(), "Listed as uncovered but actually covered: $overlap")
        assertTrue(
            cases().size >= MINIMUM_CASE_COUNT,
            "Expected at least $MINIMUM_CASE_COUNT byte-equality cases, found ${cases().size}"
        )
    }

    // --- Encoding ------------------------------------------------------------------------------

    private fun encodeLegacy(encoder: LegacyEncoder): ByteArray {
        val stream = LegacyStream(BUFFER_CAPACITY)
        encoder.encodeBody(stream)
        return stream.toByteArray()
    }

    private fun encodeModern(prot: ServerProt): ByteArray = runBlocking {
        val channel = ByteChannel(autoFlush = true)
        codec.encodeServerProt(prot, channel)
        channel.flush()
        val bytes = ByteArray(channel.availableForRead)
        if (bytes.isNotEmpty()) channel.readFully(bytes)
        channel.close()
        bytes
    }

    private fun describeMismatch(case: Case, legacy: ByteArray, modern: ByteArray): String {
        val opcode = codec.serverProts[case.modern::class]?.opcode ?: -1
        val firstDiff = (0 until maxOf(legacy.size, modern.size))
            .firstOrNull { legacy.getOrNull(it) != modern.getOrNull(it) } ?: -1
        return buildString {
            append("${case.name} (opcode $opcode, ${case.modern::class.simpleName})")
            append("\n  first difference at byte $firstDiff")
            append("\n  legacy (${legacy.size} bytes): ${hex(legacy)}")
            append("\n  modern (${modern.size} bytes): ${hex(modern)}")
        }
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString(" ") { "%02x".format(it) }

    // --- Fixtures ------------------------------------------------------------------------------

    /** Legacy chunk id packing, from `MapUtils.Structure.CHUNK`. Pure arithmetic, no cache. */
    private fun chunkId(x: Int, y: Int, plane: Int) = (x shl 11) or y or (plane shl 22)

    /** The zone-local coordinate byte both stacks put on the wire. */
    private fun chunkLocalHash(localX: Int, localY: Int) = ((localX and 0x7) shl 4) or (localY and 0x7)

    private fun legacyTile(x: Int, y: Int, plane: Int = 0): LegacyTile = LegacyTile.of(x, y, plane)

    private fun tile(x: Int, y: Int, plane: Int = 0) = Tile(x, y, plane)

    private fun legacyObject(id: Int, type: ObjectType, rotation: Int, x: Int, y: Int, plane: Int = 0) =
        WorldObject(id, type, rotation, legacyTile(x, y, plane))

    private fun legacyGroundItem(id: Int, amount: Int, x: Int, y: Int, plane: Int = 0) =
        GroundItem(Item(id, amount), legacyTile(x, y, plane))

    private fun zeroXteas() = intArrayOf(0, 0, 0, 0)

    private fun legacyChunkBits(chunks: IntArray): ByteArray {
        val stream = LegacyStream(BUFFER_CAPACITY)
        stream.initBitAccess()
        for (chunk in chunks) {
            if (chunk == BuildInstancedRegion.ABSENT_CHUNK) {
                stream.writeBits(1, 0)
            } else {
                stream.writeBits(1, 1)
                stream.writeBits(26, chunk)
            }
        }
        stream.finishBitAccess()
        return stream.toByteArray()
    }

    private fun cases(): List<Case> = CaseBuilder().apply {
        accountCases()
        cameraCases()
        interfaceCases()
        regionCases()
        soundCases()
        varCases()
        zoneCases()
        miscCases()
        socialCases()
    }.cases

    // --- Account, session and stats ------------------------------------------------------------

    private fun CaseBuilder.accountCases() {
        // Legacy takes the raw code the client switches on; the modern packets name it, so each
        // case pairs the enum with its value to pin the byte the client sees.
        case(
            "CREATE_CHECK_EMAIL_REPLY",
            LegacyCheckEmailResponse(CheckEmailReplyOpcode.EMAIL_ALREADY_IN_USE.value),
            CreateCheckEmailReply(CheckEmailReplyOpcode.EMAIL_ALREADY_IN_USE),
        )
        case(
            "CREATE_ACCOUNT_REPLY",
            LegacyCreateAccountResponse(CreateAccountReplyOpcode.SERVER_ERROR.value),
            CreateAccountReply(CreateAccountReplyOpcode.SERVER_ERROR),
        )
        // Legacy hard-codes the quick-chat, "members" and date-of-birth fields; the modern packet
        // takes them as parameters, so they are pinned to the legacy constants here.
        case(
            "WORLD_LOGIN_DETAILS",
            LegacyWorldLoginDetails(2, 1337, "Zezima", 3, true, true),
            WorldLoginDetails(
                rights = 2,
                modLevel = 3,
                quickChat = false,
                verifiedEmail = true,
                aBool7322 = true,
                quickChatOnly = false,
                playerIndex = 1337,
                members = true,
                dob = 0,
                memberWorld = true,
                worldName = "Zezima",
            ),
        )
        case("UPDATE_REBOOT_TIMER", LegacySystemUpdateTimer(50), UpdateRebootTimer(50))
        case("RUN_ENERGY", LegacyRunEnergy(97), RunEnergy(97))
        case("PLAYER_WEIGHT", LegacyRunWeight(-40), PlayerWeight(-40))
        case("UPDATE_STAT", LegacyUpdateStat(3, 13_034_431, 99), UpdateStat(xp = 13_034_431, skillId = 3, level = 99))
        case(
            "UPDATE_GE_SLOT",
            LegacyUpdateGeSlot(2, 1, 4151, 250_000, 10, 4, 1_000_000),
            UpdateGESlot(
                slot = 2,
                offer = GrandExchangeOffer(
                    progress = 1, itemId = 4151, price = 250_000,
                    amount = 10, currentAmount = 4, totalPrice = 1_000_000,
                ),
            ),
        )
    }

    // --- Camera --------------------------------------------------------------------------------

    private fun CaseBuilder.cameraCases() {
        case("CAM_FORCEANGLE", LegacyCamForceAngle(40_000, 50_000), CamForceAngle(40_000, 50_000))
        case("CAM_LOOKAT", LegacyCamLookAt(30, 45, 3000, 7, 9), CamLookAt(30, 45, 3000, 7, 9))
        case("CAM_MOVETO", LegacyCamMoveTo(30, 45, 3000, 7, 9), CamMoveTo(30, 45, 3000, 7, 9))
        // Legacy packs the tile inline as `localY + (localX shl 14)`, which is a plane-0 tile hash.
        case("CAM_REMOVEROOF", LegacyCamRemoveRoof(3222, 3218), CamRemoveRoof(tile(3222, 3218)))
        case("CAM_SHAKE", LegacyCamShake(1, 2, 3, 4, 5000), CamShake(slotId = 1, v1 = 2, v2 = 3, v3 = 4, v4 = 5000))
    }

    // --- Interfaces ----------------------------------------------------------------------------

    private fun CaseBuilder.interfaceCases() {
        case("IF_SETPLAYERHEAD", LegacyIfSetPlayerHead(750, 31), IfSetPlayerHead(750, 31))
        // Wire order is kit1, kit3, kit2: the client packs shorts 1 and 3 into one media id and
        // short 2 into the other. Legacy names the wire slots 1, 2, 3 positionally, so its second
        // argument is the modern `identiKit3` and its third is `identiKit2`.
        case(
            "IF_SETPLAYERHEAD_IGNOREWORN",
            LegacyIfSetPlayerHeadIgnoreWorn(750, 31, 100, 200, 300),
            IfSetPlayerHeadIgnoreWorn(750, 31, identiKit1 = 100, identiKit2 = 300, identiKit3 = 200),
        )
        case("IF_SETTEXTFONT", LegacyIfSetTextFont(750, 31, 495), IfSetTextFont(750, 31, 495))
        // Legacy hard-codes all four xtea words to zero, so the case pins zeros on both sides.
        // See KNOWN_DIVERGENCES: legacy writes the last two little-endian, the client reads big.
        case("IF_OPENTOP", LegacyIfOpenTop(548, 2), IfOpenTop(548, 2, zeroXteas()))
        case("IF_SETHIDE", LegacyIfSetHide(750, 31, true), IfSetHide(750, 31, true))
        case("IF_SETGRAPHIC", LegacyIfSetGraphic(750, 31, 0x0BADF00D), IfSetGraphic(750, 31, 0x0BADF00D))
        case("IF_OPENSUB", LegacyIfOpenSub(548, 23, 750, true), IfOpenSub(548, 23, 750, true, zeroXteas()))
        case("IF_SETANGLE", LegacyIfSetAngle(750, 31, 100, 200, 40_000), IfSetAngle(750, 31, 100, 200, 40_000))
        case("IF_SETPOSITION", LegacyIfSetPosition(750, 31, 40, 50), IfSetPosition(750, 31, 40, 50))
        // Legacy sends a player id and a hashed display name; the modern packet carries the two
        // model ids the client actually reads, in the same wire slots.
        case(
            "IF_SETPLAYERMODEL_OTHER",
            LegacyIfSetPlayerModelOther(750, 31, "Zezima", 1337),
            IfSetPlayerModelOther(750, 31, primaryModelId = 1337, secondaryModelId = Utils.stringToInt("Zezima")),
        )
        case("IF_SETSCROLLPOS", LegacyIfSetScrollPos(750, 31, 512), IfSetScrollPos(750, 31, 512))
        case("IF_SETPLAYERMODEL", LegacyIfSetPlayerModel(750, 31), IfSetPlayerModel(750, 31))
        case("IF_CLOSESUB", LegacyIfCloseSub(750, 31), IfCloseSub(750, 31))
        case("IF_SETANIM", LegacyIfSetAnimation(750, 31, 0x0BADF00D), IfSetAnim(750, 31, 0x0BADF00D))
        case("IF_SETCOLOR", LegacyIfSetColor(750, 31, 40_000), IfSetColor(750, 31, 40_000))
        case("IF_SETNPCHEAD", LegacyIfSetNpcHead(750, 31, 1234), IfSetNpcHead(750, 31, 1234))
        case("IF_SETITEM", LegacyIfSetItem(750, 31, 4151, 1_000_000), IfSetItem(750, 31, 4151, 1_000_000))
        case(
            "IF_SETEVENTS (opcode 121, client enum `IF_SETTARGETPARAM`)",
            LegacyIfSetEvents(IFEvents(750, 31, 0, 27, 0x7654321)),
            IfSetEvents(750, 31, fromSlot = 0, toSlot = 27, settings = 0x7654321),
        )
        case("IF_SETTEXT", LegacyIfSetText(750, 31, "Hello, world!"), IfSetText(750, 31, "Hello, world!"))
        case("IF_SETMODEL", LegacyIfSetModel(750, 31, 0x0BADF00D), IfSetModel(750, 31, 0x0BADF00D))
        case(
            "IF_SETTARGETPARAM (opcode 148, client enum `IF_RESETTARGETPARAM`)",
            LegacyIfSetTargetParam(750, 31, 0, 27, 1000),
            IfSetTargetParam(750, 31, targetInterfaceId = 1000, fromSlot = 0, toSlot = 27),
        )
        case("IF_SETCLICKMASK", LegacyIfSetClickMask(750, 31, true), IfSetClickMask(750, 31, true))
        // Legacy hashes a display name into the first int; the modern packet carries the already
        // hashed key, so the same 4 bytes land on the wire.
        case(
            "IF_SETPLAYERHEAD_OTHER",
            LegacyIfSetPlayerHeadOther(750, 31, "Zezima", 1337),
            IfSetPlayerHeadOther(750, 31, key = Utils.stringToInt("Zezima"), value = 1337),
        )
        case(
            "IF_OPENSUB_ACTIVE_OBJECT",
            LegacyIfOpenSubActiveObject(548, 23, 750, true, legacyObject(1276, ObjectType.SCENERY_INTERACT, 2, 3222, 3218)),
            IfOpenSubActiveObject(
                interfaceId = 548,
                componentId = 23,
                subInterfaceId = 750,
                overlay = true,
                xteas = zeroXteas(),
                objectId = 1276,
                tile = tile(3222, 3218),
                type = ObjectType.SCENERY_INTERACT.id,
                rotation = 2,
            ),
        )
        case(
            "IF_OPENSUB_ACTIVE_NPC",
            LegacyIfOpenSubActiveNpc(548, 23, 750, true, 4321),
            IfOpenSubActiveNpc(548, 23, 750, true, npcIndex = 4321, xteas = zeroXteas()),
        )
        case(
            "IF_OPENSUB_ACTIVE_PLAYER",
            LegacyIfOpenSubActivePlayer(548, 23, 750, true, 1337),
            IfOpenSubActivePlayer(548, 23, 750, true, playerIndex = 1337, xteas = zeroXteas()),
        )
        case(
            "IF_OPENSUB_ACTIVE_GROUNDITEM",
            LegacyIfOpenSubActiveGroundItem(548, 23, 750, true, legacyGroundItem(4151, 1, 3222, 3218)),
            IfOpenSubActiveGroundItem(
                interfaceId = 548,
                componentId = 23,
                subInterfaceId = 750,
                overlay = true,
                tile = tile(3222, 3218),
                itemId = 4151,
                xteas = zeroXteas(),
            ),
        )
    }

    // --- Region, map and hint arrows -----------------------------------------------------------

    private fun CaseBuilder.regionCases() {
        // `MapXTEAs` is unpopulated in a test JVM, so the legacy encoder writes zeroed keys for
        // every region; the modern packet is handed the same zeros.
        val regionIds = BuildRegion.regionIds(400, 402, RegionSize.SIZE_104)
        case(
            "REGION",
            LegacyMapRegion(null, LegacyRegionSize.SIZE_104, 400, 402, true, regionIds.toSortedSet()),
            BuildRegion(
                regionIds = regionIds,
                mapSize = RegionSize.SIZE_104,
                xteas = Array(regionIds.size) { zeroXteas() },
                chunkX = 400,
                chunkY = 402,
                forceMapRefresh = true,
            ),
        )
        val instancedChunks = intArrayOf(
            BuildInstancedRegion.chunkDescriptor(416, 392, 0, 0),
            BuildInstancedRegion.ABSENT_CHUNK,
            BuildInstancedRegion.chunkDescriptor(400, 400, 1, 3),
            BuildInstancedRegion.chunkDescriptor(400, 424, 2, 1),
            BuildInstancedRegion.chunkDescriptor(401, 401, 3, 2),
        )
        val instancedRegionIds = BuildInstancedRegion.regionIds(instancedChunks)
        case(
            "DYNAMIC_MAP_REGION",
            LegacyDynamicMapRegion(null, LegacyRegionSize.SIZE_104, 400, 402, true, legacyChunkBits(instancedChunks), instancedRegionIds),
            BuildInstancedRegion(
                mapSize = RegionSize.SIZE_104,
                chunks = instancedChunks,
                xteas = Array(instancedRegionIds.size) { zeroXteas() },
                chunkX = 400,
                chunkY = 402,
                forceMapRefresh = true,
            ),
        )
        case("MINIMAP_FLAG", LegacyMinimapFlag(40, 55), MinimapFlag(40, 55))
        case(
            "HINT_ARROW (cleared)",
            LegacyHintArrow(HintIcon().apply { targetType = 0; index = 3 }),
            HintArrow(iconIndex = 3, targetType = 0),
        )
        // Legacy hard-codes the denominator to 2500 for the entity forms.
        case(
            "HINT_ARROW (player)",
            LegacyHintArrow(
                HintIcon().apply {
                    targetType = 1; targetIndex = 1337; arrowType = 2; modelId = 0x0BADF00D; index = 3
                },
            ),
            HintArrow(
                iconIndex = 3, targetType = 1, arrowGraphic = 2,
                targetIndex = 1337, denominator = 2500, modelId = 0x0BADF00D,
            ),
        )
        case(
            "HINT_ARROW (npc)",
            LegacyHintArrow(
                HintIcon().apply {
                    targetType = 10; targetIndex = 4321; arrowType = 2; modelId = 0x0BADF00D; index = 3
                },
            ),
            HintArrow(
                iconIndex = 3, targetType = 10, arrowGraphic = 2,
                targetIndex = 4321, denominator = 2500, modelId = 0x0BADF00D,
            ),
        )
        // Legacy writes `distanceFromFloor * 4 shr 2` for the height byte and hard-codes the
        // trailing "distance to show" short to -1.
        case(
            "HINT_ARROW (tile)",
            LegacyHintArrow(
                HintIcon().apply {
                    targetType = 2; arrowType = 2; coordX = 3222; coordY = 3218
                    plane = 0; distanceFromFloor = 32; modelId = 0x0BADF00D; index = 3
                },
            ),
            HintArrow(
                iconIndex = 3, targetType = 2, arrowGraphic = 2, plane = 0,
                x = 3222, y = 3218, height = 32 * 4, distance = -1, modelId = 0x0BADF00D,
            ),
        )
    }

    // --- Sound ---------------------------------------------------------------------------------

    private fun CaseBuilder.soundCases() {
        // The legacy `Sound` object serves five opcodes and reuses its fields differently in each;
        // the modern packets name each wire slot for what that opcode actually reads.
        case(
            "SOUND_SYNTH",
            LegacySound(2677, 40, 200, 8192, LegacySoundType.EFFECT).repeat(3),
            SoundSynth(soundId = 2677, repeat = 3, delay = 40, volume = 200, sampleRate = 8192),
        )
        case(
            "VORBIS_SOUND",
            LegacySound(2677, 40, 200, 8192, LegacySoundType.VORB_EFFECT).repeat(3),
            VorbisSound(soundId = 2677, repeat = 3, delay = 40, volume = 200, sampleRate = 8192),
        )
        case(
            "VORBIS_SPEECH_SOUND",
            LegacySound(2677, 40, 200, 8192, LegacySoundType.VOICE).repeat(3),
            VorbisSpeechSound(soundId = 2677, repeat = 3, delay = 40, volume = 200),
        )
        // The client reads the leading 3 bytes and discards them; legacy hard-codes them to zero.
        case(
            "MUSIC_EFFECT",
            LegacySound(1234, 40, 200, 8192, LegacySoundType.JINGLE),
            MusicEffect(unused = 0, volume = 200, musicId = 1234),
        )
        case(
            "MUSIC_TRACK",
            LegacySound(1234, 40, 200, 8192, LegacySoundType.MUSIC),
            MusicTrack(delay = 40, volume = 200, musicId = 1234),
        )
    }

    // --- Vars ----------------------------------------------------------------------------------

    private fun CaseBuilder.varCases() {
        case("VARP_SMALL", LegacySetVarp(1234, 100), VarpSmall(1234, 100))
        case("VARP_LARGE", LegacySetVarp(1234, 0x0BADF00D), VarpLarge(1234, 0x0BADF00D))
        case("CLIENT_SETVARC_SMALL", LegacySetVarc(1234, 100), ClientSetVarcSmall(1234, 100))
        case("CLIENT_SETVARC_LARGE", LegacySetVarc(1234, 0x0BADF00D), ClientSetVarcLarge(1234, 0x0BADF00D))
        case("VARBIT_SMALL", LegacySetVarpBit(1234, 100), VarbitSmall(1234, 100))
        case("VARBIT_LARGE", LegacySetVarpBit(1234, 0x0BADF00D), VarbitLarge(1234, 0x0BADF00D))
        case("CLIENT_SETVARCSTR_SMALL", LegacySetVarcString(1234, "short"), ClientSetVarcStrSmall(1234, "short"))
        case("CLIENT_SETVARCSTR_LARGE", LegacySetVarcString(1234, LONG_STRING), ClientSetVarcStrLarge(1234, LONG_STRING))
    }

    // --- Zone ----------------------------------------------------------------------------------

    private fun CaseBuilder.zoneCases() {
        val hash = chunkLocalHash(5, 6)
        case(
            "UPDATE_ZONE_FULL_FOLLOWS",
            LegacyUpdateZoneFull(chunkId(400, 402, 0), chunkId(404, 405, 1)),
            UpdateZoneFullFollows(chunkX = 4, chunkY = 3, plane = 1),
        )
        case(
            "UPDATE_ZONE_PARTIAL_FOLLOWS",
            LegacyUpdateZonePartial(chunkId(400, 402, 0), chunkId(404, 405, 1)),
            UpdateZonePartialFollows(chunkX = 4, chunkY = 3, plane = 1),
        )
        // Also pins the sub-packet ordinals, which are the client's `UpdateZonePacket` enum
        // ordinals rather than the standalone opcodes.
        case(
            "UPDATE_ZONE_PARTIAL_ENCLOSED",
            LegacyUpdateZonePartialEnclosed(
                chunkId(400, 402, 0),
                chunkId(404, 405, 1),
                LegacyRemoveGroundItem(hash, legacyGroundItem(4151, 1, 3222, 3218)),
                LegacyAddObject(hash, legacyObject(1276, ObjectType.SCENERY_INTERACT, 2, 3222, 3218)),
            ),
            UpdateZonePartialEnclosed(
                chunkX = 4,
                chunkY = 3,
                plane = 1,
                data = ZonePacketEncoder.encodeZoneSubPackets(
                    listOf(
                        RemoveGroundItem(localX = 5, localY = 6, itemId = 4151),
                        CreateObject(
                            localX = 5, localY = 6, objectId = 1276,
                            shape = ObjectType.SCENERY_INTERACT.id, rotation = 2,
                        ),
                    ),
                ),
            ),
        )
        case(
            "CREATE_OBJECT",
            LegacyAddObject(hash, legacyObject(1276, ObjectType.SCENERY_INTERACT, 2, 3222, 3218)),
            CreateObject(localX = 5, localY = 6, objectId = 1276, shape = ObjectType.SCENERY_INTERACT.id, rotation = 2),
        )
        case(
            "DESTROY_OBJECT",
            LegacyRemoveObject(hash, legacyObject(1276, ObjectType.SCENERY_INTERACT, 2, 3222, 3218)),
            DestroyObject(localX = 5, localY = 6, shape = ObjectType.SCENERY_INTERACT.id, rotation = 2),
        )
        case(
            "OBJ_ANIM",
            LegacyObjectAnim(hash, legacyObject(1276, ObjectType.SCENERY_INTERACT, 2, 3222, 3218), Animation(0x0BADF00D)),
            ObjAnim(localX = 5, localY = 6, shape = ObjectType.SCENERY_INTERACT.id, rotation = 2, animationId = 0x0BADF00D),
        )
        case(
            "OBJ_ANIM_SPECIFIC",
            LegacyObjectAnimSpecific(legacyObject(1276, ObjectType.SCENERY_INTERACT, 2, 3222, 3218), Animation(0x0BADF00D)),
            ObjAnimSpecific(tile = tile(3222, 3218), shape = ObjectType.SCENERY_INTERACT.id, rotation = 2, animationId = 0x0BADF00D),
        )
        case(
            "OBJECT_PREFETCH",
            LegacyObjectPrefetch(1276, ObjectType.SCENERY_INTERACT),
            ObjectPrefetch(objectId = 1276, typeIndex = ObjectType.SCENERY_INTERACT.id),
        )
        case(
            "CREATE_GROUND_ITEM",
            LegacyCreateGroundItem(hash, legacyGroundItem(4151, 40_000, 3222, 3218)),
            CreateGroundItem(localX = 5, localY = 6, itemId = 4151, amount = 40_000),
        )
        case(
            "REMOVE_GROUND_ITEM",
            LegacyRemoveGroundItem(hash, legacyGroundItem(4151, 1, 3222, 3218)),
            RemoveGroundItem(localX = 5, localY = 6, itemId = 4151),
        )
        case(
            "GROUND_ITEM_REVEAL",
            LegacyRevealGroundItem(hash, legacyGroundItem(4151, 40_000, 3222, 3218), 1337),
            GroundItemReveal(playerId = 1337, localX = 5, localY = 6, itemId = 4151, amount = 40_000),
        )
        case(
            "GROUND_ITEM_COUNT",
            LegacySetGroundItemAmount(hash, legacyGroundItem(4151, 40_000, 3222, 3218), 20_000),
            GroundItemCount(localX = 5, localY = 6, itemId = 4151, oldAmount = 20_000, newAmount = 40_000),
        )
        // Legacy's `delay` is divided by 30 into the wire short the modern packet calls `priority`,
        // and legacy's `height` is the modern `duration`.
        case(
            "TILE_MESSAGE",
            LegacyTileMessage(hash, "Hello, world!", 900, 40, 0xFF0000),
            TileMessage(localX = 5, localY = 6, priority = 900 / 30, duration = 40, color = 0xFF0000, message = "Hello, world!"),
        )
        case(
            "SPOT_ANIM",
            LegacyTileSpotAnim(hash, LegacySpotAnimModel(1234, 30, 100, 2)),
            SpotAnim(localX = 5, localY = 6, spotAnimId = 1234, height = 100, speed = 30, rotation = 2),
        )
        case(
            "SPOT_ANIM_SPECIFIC",
            LegacySpotAnimSpecific(LegacySpotAnimModel(1234, 30, 100, 2), tile(3222, 3218).id),
            SpotAnimSpecific(
                slot = 0,
                spotAnimId = 1234,
                settings = LegacySpotAnimModel(1234, 30, 100, 2).settings2Hash,
                speed = 30,
                targetHash = tile(3222, 3218).id,
                height = 100,
            ),
        )
        // Legacy's `Sound.radius`/`repeat` are the modern `range`/`mode` nibbles.
        case(
            "MIDI_SONG_LOCATION (zone sound effect)",
            LegacyTileSound(hash, LegacySound(2677, 40, 200, 8192, LegacySoundType.EFFECT).radius(3).repeat(2)),
            SoundEffectTile(
                localX = 5, localY = 6, soundId = 2677, range = 3, mode = 2,
                delay = 40, volume = 200, sampleRate = 8192,
            ),
        )
        // SOUND_AREA has no standalone opcode in either stack, so it is pinned enclosed;
        // the trailing byte is the client's voice-over flag.
        case(
            "SOUND_AREA (zone vorbis sound)",
            LegacyUpdateZonePartialEnclosed(
                chunkId(400, 402, 0),
                chunkId(404, 405, 1),
                LegacyTileSound(hash, LegacySound(2677, 40, 200, 8192, LegacySoundType.VORB_EFFECT).radius(3).repeat(2)),
                LegacyTileSound(hash, LegacySound(2677, 40, 200, 8192, LegacySoundType.VOICE).radius(3).repeat(2)),
            ),
            UpdateZonePartialEnclosed(
                chunkX = 4,
                chunkY = 3,
                plane = 1,
                data = ZonePacketEncoder.encodeZoneSubPackets(
                    listOf(
                        SoundAreaTile(
                            localX = 5, localY = 6, soundId = 2677, range = 3, mode = 2,
                            delay = 40, volume = 200, sampleRate = 8192, voice = false,
                        ),
                        SoundAreaTile(
                            localX = 5, localY = 6, soundId = 2677, range = 3, mode = 2,
                            delay = 40, volume = 200, sampleRate = 8192, voice = true,
                        ),
                    ),
                ),
            ),
        )
        // Height units differ by design: the legacy `Projectile` carries the raw wire byte, the
        // modern packet carries world height and divides by 4, which is exactly the multiply the
        // client applies on the way back in. See KNOWN_DIVERGENCES.
        case(
            "MAP_PROJANIM",
            LegacyProjAnim(hash, projectile()),
            MapProjAnim(
                localX = 6, localY = 2,
                xOffset = 4, yOffset = 3,
                lockOn = 4321, spotAnimId = 1234,
                startHeight = WIRE_START_HEIGHT * 4, endHeight = WIRE_END_HEIGHT * 4,
                startTime = 30, endTime = 30 + 60,
                angle = 15, slope = 12,
                useFloorHeight = false,
            ),
        )
        case(
            "MAP_PROJANIM_HALFSQ",
            LegacyProjAnimHalfSq(hash, projectile()),
            MapProjAnimHalfSq(
                localX = 12, localY = 4,
                xOffset = 8, yOffset = 6,
                source = 1337, lockOn = 4321, spotAnimId = 1234,
                startHeight = WIRE_START_HEIGHT * 4, endHeight = WIRE_END_HEIGHT * 4,
                startTime = 30, endTime = 30 + 60,
                angle = 15, slope = 12,
                basOffIdx = -1, useFloorHeight = false,
            ),
        )
        // Legacy sends the projectile's in-air duration in the slot the client reads as end time,
        // rather than start + in-air; the modern packet takes the wire value directly.
        case(
            "PROJANIM_SPECIFIC",
            LegacyProjAnimSpecific(projectile()),
            ProjAnimSpecific(
                endHeight = WIRE_END_HEIGHT * 4, source = 1337, endTime = 60, lockOn = 4321,
                flags = 0, y = 3218, slope = 12, startTime = 30, spotAnimId = 1234,
                angle = 15, xDist = 4, yDist = 3, x = 3222, startHeight = WIRE_START_HEIGHT * 4,
            ),
        )
    }

    /** A fixed projectile both stacks describe: (3222, 3218) -> (3226, 3221). */
    private fun projectile() = Projectile(
        legacyTile(3222, 3218), 1337, legacyTile(3226, 3221),
        4321, 1234, WIRE_START_HEIGHT, WIRE_END_HEIGHT, 30, 60, 12, 15,
    )

    // --- Everything else -----------------------------------------------------------------------

    private fun CaseBuilder.miscCases() {
        case("PROCESS_DEV_CONSOLE_COMMAND", LegacySendDevConsoleCommand("::reload"), ProcessDevConsoleCommand("::reload"))
        case("OPEN_URL", LegacyOpenUrl("https://drynor.org"), OpenUrl("https://drynor.org"))
        case("SET_CURSOR", LegacySetCursor("Attack", 42), SetCursor(cursorId = 42, uptext = "Attack"))
        case("SET_DRAW_ORDER", LegacyDrawOrder(true), SetDrawOrder(true))
        case("BLOCK_MINIMAP_STATE", LegacyBlockMinimapState(2), BlockMinimapState(2))
        case("CHAT_FILTER_SETTINGS", LegacyChatFilterSettings(1, 2), ChatFilterSettings(tradeFilter = 1, publicFilter = 2))
        case("CHAT_FILTER_SETTINGS_PRIVATECHAT", LegacyChatFilterSettingsPriv(1), ChatFilterSettingsPrivateChat(1))
        case(
            "PLAYER_OPTION",
            LegacyPlayerOption("Attack", 3, true, 40_000),
            PlayerOption("Attack", slot = 3, top = true, cursor = 40_000),
        )
        case("PLAYER_UPDATE", LegacyPlayerUpdate(BLOB), PlayerUpdate(BLOB))
        case("NPC_UPDATE", LegacyNpcUpdate(false, BLOB), NpcUpdate(BLOB))
        case("NPC_UPDATE_LARGE", LegacyNpcUpdate(true, BLOB), NpcUpdateLarge(BLOB))
        case(
            "RUN_CS2_SCRIPT",
            LegacyExecuteCS2(1234, "text", 42, "more"),
            RunScript(1234, arrayOf("text", 42, "more")),
        )
        val legacyChecks = LegacyReflectionChecks()
            .setReflectionChecks(arrayOf(LegacyReflectionCheck("java.lang.System", "out")))
        case(
            "REFLECTION_CHECK",
            LegacyReflectionCheckRequest(legacyChecks),
            ReflectionRequest(
                // The legacy holder seeds its id randomly, so the modern packet is given whatever
                // this run produced rather than a literal.
                id = legacyChecks.id,
                checks = arrayOf(ReflectionCheck(ReflectionCheckType.GET_FIELD_MODIFIERS, "java.lang.System", "out")),
            ),
        )
        case(
            "UPDATE_INV_FULL",
            LegacyUpdateItemContainer(93, false, arrayOf(Item(4151, 1), Item(995, 1_000_000), null)),
            UpdateInvFull(
                containerId = 93,
                negativeKey = false,
                itemIds = intArrayOf(4151, 995, -1),
                amounts = intArrayOf(1, 1_000_000, 0),
            ),
        )
        case(
            "UPDATE_INV_PARTIAL",
            LegacyUpdateItemContainer(93, false, arrayOf(Item(4151, 1), Item(995, 1_000_000), null), 0, 1, 2),
            UpdateInvPartial(
                containerId = 93,
                negativeKey = false,
                updates = arrayOf(
                    InvSlotUpdate(0, 4151, 1),
                    InvSlotUpdate(1, 995, 1_000_000),
                    InvSlotUpdate(2, -1, 0),
                ),
            ),
        )
    }

    // --- Social --------------------------------------------------------------------------------

    private fun CaseBuilder.socialCases() {
        // Legacy writes each entry's current then previous display name; the modern packet calls
        // those two wire slots `username` and `displayName`.
        case(
            "UPDATE_IGNORE_LIST",
            LegacyIgnoreList(linkedMapOf("zezima" to DisplayNamePair("Zezima", "Zezima_old"))),
            UpdateIgnoreList(arrayOf(IgnoreEntry(username = "Zezima", displayName = "Zezima_old"))),
        )
        case(
            "GAME_MESSAGE",
            LegacyMessageGame(LegacyMessageGame.MessageType.GAME, "Hello, world!"),
            GameMessage(type = ChatMessageType.GAME, message = "Hello, world!"),
        )
        // Opcode 22. The legacy `ServerPacket` enum has this pair the wrong way round relative to
        // the client, so the legacy class named `QuickChatPrivate` is the echo. See
        // KNOWN_DIVERGENCES.
        case(
            "MESSAGE_QUICKCHAT_PRIVATE_ECHO",
            LegacyQuickChatPrivate("Zezima", legacyQuickChat()),
            MessageQuickChatPrivateEcho(
                senderDisplayName = "Zezima",
                message = QuickChatMessage(message = "", fileId = 1234, data = QC_DATA),
            ),
        )
    }

    private fun legacyQuickChat() = LegacyQuickChatMessage(1234, QC_DATA)

    private companion object {
        /** Big enough for every case here; the legacy stream grows by doubling if it is not. */
        const val BUFFER_CAPACITY = 8192

        /**
         * Projectile heights as the client reads them off the wire, before its `* 4` scale. The
         * legacy `Projectile` stores this raw byte; the modern packet stores `wire * 4` and
         * divides on the way out.
         */
        const val WIRE_START_HEIGHT = 10
        const val WIRE_END_HEIGHT = 5

        /** Long enough that `SetVarcString` picks the VarShort ("large") packet on both sides. */
        val LONG_STRING = "x".repeat(200)

        /** Opaque pre-encoded payload for the packets that carry one. */
        val BLOB = byteArrayOf(0x00, 0x7f, -0x80, -0x01, 0x2a)

        /** Quick-chat dynamic-value payload; opaque to both encoders. */
        val QC_DATA = byteArrayOf(0x01, 0x02, 0x03)

        const val MINIMUM_CASE_COUNT = 95

        /**
         * Legacy encoders with no byte-equality case, and why. This list is the deliverable half
         * of the coverage story: anything here is *not* verified against the legacy library.
         *
         * Keyed by modern packet class where one exists, or by the legacy class name where the
         * modern side has no counterpart at all.
         */
        val UNCOVERED: Map<String, String> = mapOf(
            // -- Non-deterministic on one or both sides -----------------------------------------
            "MessagePublic" to
                "Legacy compresses the body with the cache Huffman table; the modern encoder " +
                "stamps a live tick counter (`writeHashedMessageTimestamp`). Neither side is " +
                "reproducible without freezing the clock and loading the cache.",
            "MessageFriendsChat" to
                "Legacy pads the header with five `Utils.getRandomInclusive(255)` junk bytes and " +
                "Huffman-compresses the body; modern stamps a live timestamp.",
            "MessageClanChannel" to "Legacy random junk bytes + Huffman; modern live timestamp.",
            "MessagePrivate" to "Legacy random junk bytes + Huffman; modern live timestamp.",
            "MessagePrivateEcho" to "Legacy Huffman-compresses the body, so it needs an initialised cache.",
            "MessageQuickChatFriendsChat" to "Legacy random junk bytes; modern live timestamp.",
            "MessageQuickChatClanChannel" to "Legacy random junk bytes; modern live timestamp.",
            "MessageQuickChatPrivate" to
                "Modern stamps a live tick counter into the five bytes where legacy writes zeros; " +
                "the value is not derivable from the packet's own fields.",

            // -- Legacy needs an initialised cache ----------------------------------------------
            "Cutscene" to
                "Legacy resolves `CutsceneDefinitions.getDefs(id)` and `MapXTEAs` to build the " +
                "region key block. The modern packet takes the keys as data, so there is no " +
                "shared seam without loading the cache.",
            "CustomizeObject" to
                "Legacy reads `ObjectDefinitions` to decide how many model/colour/texture entries " +
                "to write; the modern packet writes the arrays it is handed.",
            "VarclanSetString" to "Legacy `SetVarClan` picks its opcode from `ClanVarDefinitions`, which needs the cache.",
            "VarclanSetLong" to "Legacy `SetVarClan` picks its opcode from `ClanVarDefinitions`, which needs the cache.",
            "VarclanSetInt" to "Legacy `SetVarClan` picks its opcode from `ClanVarDefinitions`, which needs the cache.",
            "VarclanSetByte" to "Legacy `SetVarClan` picks its opcode from `ClanVarDefinitions`, which needs the cache.",

            // -- No structural counterpart on the legacy side -----------------------------------
            "ClanChannelFull" to
                "Legacy takes the channel block pre-encoded, built by a static helper that returns " +
                "`OutputStream.getBuffer()` - the whole doubling-grown backing array rather than " +
                "`toByteArray()` - so its output carries trailing zero padding and is not a " +
                "well-formed payload to diff against.",
            "ClanSettingsFull" to "Same `getBuffer()` padding defect in the legacy static block builder.",
            "ClanChannelDelta" to "Legacy has no delta encoder; the lobby built these blocks by hand.",
            "ClanSettingsDelta" to "Legacy has no delta encoder.",
            "ClanSettingsFullRaw" to "Raw passthrough of an externally built block; nothing to differ on.",
            "FriendsChatChannel" to
                "Legacy takes the whole channel block pre-encoded from the lobby server; the " +
                "modern packet builds it from typed fields. No shared seam inside core.",
            "UpdateFriendchatChannelSingleUser" to "No legacy encoder; the lobby wrote this block by hand.",
            "FriendStatus" to
                "Legacy encodes from a live `Account`/`Friend`/`WorldInfo` object graph and reads " +
                "friends-chat rank off the account's social state; the modern packet carries flat " +
                "DTOs. Constructing an equivalent legacy graph would test the account model, not " +
                "the encoder.",
            "HintTrail" to
                "Legacy walks a route-finder step array to build the trail; the modern packet " +
                "carries it pre-encoded, so only the shared 3-byte header would be comparable.",
            "AddIgnore" to
                "Legacy encodes from a live `Account`, hard-codes its flag byte to 0x2 and writes " +
                "display name then previous display name where the modern packet writes username " +
                "then display name. No shared seam.",

            // -- No legacy encoder at all -------------------------------------------------------
            "IfSetReTex" to "No legacy encoder (`IF_SETRETEX`, opcode 132).",
            "IfSetReCol" to "No legacy encoder (`IF_SETRECOL`, opcode 144).",
            "IfMoveSub" to "No legacy encoder (`IF_MOVESUB`, opcode 13).",
            "IfSetTextAntiMacro" to "No legacy encoder (`IF_SETTEXTANTIMACRO`, opcode 36).",
            "OutdatedSetTheoraString" to "No legacy encoder (opcode 20).",
            "OutdatedInterfacePulseEvent" to "No legacy encoder (opcode 96).",
            "AnimateNpc" to "No legacy encoder (`ANIMATE_NPC`, opcode 145).",
            "MusicTrackTile" to "No legacy encoder (`PLAY_SONG_AT_POSITION`, opcode 93).",
            "UpdateInvStopTransmit" to "No legacy encoder (`UPDATE_INV_STOP_TRANSMIT`, opcode 42).",
            "UpdateUid192" to "No legacy encoder (`UPDATE_UID192`, opcode 90).",
            "UpdateDob" to "No legacy encoder (`UPDATE_DOB`, opcode 157).",
            "SetTarget" to "No legacy encoder (`SET_TARGET`, opcode 129).",
            "ReduceAttackPriority" to "No legacy encoder (`REDUCE_ATTACK_PRIORITY`, opcode 61).",
            "ShowFaceHere" to "No legacy encoder (`SHOW_FACE_HERE`, opcode 21).",
            "DebugServerTriggers" to "No legacy encoder (`DEBUG_SERVER_TRIGGERS`, opcode 73).",
            "QuickHopWorlds" to "No legacy encoder (`QUICK_HOP_WORLDS`, opcode 58).",
            "IdentifyHostName" to "No legacy encoder (`IDENTIFY_HOST_NAME`, opcode 147).",
            "RequestFps" to "No legacy encoder (`REQUEST_FPS`, opcode 159).",
            "UpdateSitesettingsCookie" to "No legacy encoder (`UPDATE_SITESETTINGS_COOKIE`, opcode 91).",
            "OpenSocialNetworkUrl" to "No legacy encoder (`OPEN_SOCIAL_NETWORK_URL`, opcode 86).",
            "WindowCallWithArgs" to "No legacy encoder (`WINDOW_CALL_WITH_ARGS`, opcode 156).",
            "DisplayNameChangeState" to "No legacy encoder (opcode 52).",
            "LoyaltyUpdate" to "No legacy encoder (`LOYALTY_UPDATE`, opcode 32).",
            "JCoinsUpdate" to "No legacy encoder (`JCOINS_UPDATE`, opcode 75).",
            "ApplyDebug" to "No legacy encoder (`APPLY_DEBUG`, opcode 76).",
            "StopVorbisStream" to "No legacy encoder (opcode 11).",
            "VorbisPreloadSound" to "No legacy encoder (opcode 17).",
            "SetVorbisSound" to "No legacy encoder (opcode 106).",
            "PreloadSong" to "No legacy encoder (`PRELOAD_SONG`, opcode 77).",
            "SoundMixbussSetLevel" to "No legacy encoder (`SET_SONG_VOLUME`, opcode 134).",
            "MessageQuickChatPlayerGroup" to "No legacy encoder (opcode 83).",
            "MessagePlayerGroup" to "No legacy encoder (opcode 133).",
            "WorldListPacket" to "No legacy encoder in `rs.drynor:core`; the lobby server built the list itself.",
            "LobbyLoginDetails" to "No legacy encoder in `rs.drynor:core`; the lobby server wrote this blob inline.",

            // -- Empty payloads -----------------------------------------------------------------
            "LogoutLobby" to "Zero-length payload; nothing to compare.",
            "LogoutFull" to "Zero-length payload; nothing to compare.",
            "CamResetHard" to "Zero-length payload; nothing to compare.",
            "CamResetSmooth" to "Zero-length payload; nothing to compare.",
            "ClearVarps" to "Zero-length payload; nothing to compare.",
            "VarclanEnable" to "Zero-length payload; nothing to compare.",
            "VarclanDisable" to "Zero-length payload; nothing to compare.",
            "ResetSounds" to "Zero-length payload; nothing to compare.",
            "ResetAllAnimations" to "Zero-length payload; nothing to compare.",
            "TriggerOnDialogAbort" to "Zero-length payload; nothing to compare.",
            "KeepAlive" to "Zero-length payload; nothing to compare.",
            "Pong" to "Zero-length payload; nothing to compare.",
            "FriendlistLoaded" to "Zero-length payload; nothing to compare.",
        )

        /**
         * Places where the two stacks genuinely disagree, and which one the 727 client sides with.
         * Every entry here has been checked against the decompiled client.
         */
        @Suppress("unused")
        val KNOWN_DIVERGENCES: Map<String, String> = mapOf(
            "IF_OPENTOP" to
                "Legacy writes the last two xtea words with `writeIntLittle`; the client reads " +
                "them with a plain big-endian `readInt()` (`PacketDecoder.IF_OPENTOP`), which is " +
                "what the modern encoder does. Legacy hard-codes all four words to zero, so its " +
                "bug was never observable.",
            "Projectile heights" to
                "The client multiplies the start- and end-height bytes by 4 on the way in " +
                "(`startHeight = readUnsignedByte() * 4`), except that MAP_PROJANIM_HALFSQ and " +
                "PROJANIM_SPECIFIC reinterpret start height as a signed byte when the 0x2 " +
                "BAS-offset flag is set. The modern encoders implement exactly that; the legacy " +
                "`Projectile` simply stores the raw wire byte, so its callers pre-divide.",
            "MESSAGE_QUICKCHAT_PRIVATE / _ECHO" to
                "The legacy `ServerPacket` enum maps opcode 22 to MESSAGE_QUICKCHAT_PRIVATE and " +
                "31 to MESSAGE_QUICKCHAT_PRIVATE_ECHO. The client has it the other way round: " +
                "opcode 22 is name + file id with no crown and no ignore check (an echo), opcode " +
                "31 carries the name block, five hash bytes, a crown byte and the ignore check. " +
                "The modern codec follows the client.",
            "MAP_PROJANIM_HALFSQ size" to
                "The client's own tables contradict each other - `ServerProt` says 17, " +
                "`UpdateZonePacket` says 19, and the decoder consumes 19. Both stacks send 19 " +
                "bytes; only the standalone framing length differs, which is why this packet must " +
                "be sent enclosed.",
        )
    }
}
