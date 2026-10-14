package org.darkan.core.net.prot.revision.rev727

import org.darkan.core.net.prot.Codec
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards that the revision 727 codec covers the whole protocol.
 *
 * The expected tables below are transcribed from the authoritative 727 client's `ServerProt` and
 * `ClientProt` enums (`darkan-bot`). A packet the client knows about but we do not register is a
 * hole in the protocol; a size we declare that disagrees with the client's is a desync waiting to
 * happen, because the size drives the length prefix the client reads.
 */
class Rev727ProtCoverageTest {

    /** One row of the client's prot enum: its name, opcode, and declared size. */
    private data class ProtSpec(val name: String, val opcode: Int, val size: Int)

    private lateinit var codec: Codec

    @BeforeTest
    fun setUp() {
        codec = register727()
    }

    @Test
    fun `every server packet the client understands has an encoder`() {
        val registered = codec.serverProts.values.map { it.opcode }.toSet()
        val missing = SERVER_PROTS.filter { it.opcode !in registered }
        assertTrue(missing.isEmpty(), "No encoder registered for: ${describe(missing)}")
    }

    @Test
    fun `every server encoder declares the size the client expects`() {
        val expectedByOpcode = SERVER_PROTS.associateBy { it.opcode }
        val mismatches = codec.serverProts.entries.mapNotNull { (type, entry) ->
            if (entry.opcode in CLIENT_SIZE_CONTRADICTIONS || type in codec.loginResponses) return@mapNotNull null
            val expected = expectedByOpcode[entry.opcode] ?: return@mapNotNull null
            val actual = entry.size.toInt()
            if (actual == expected.size) null
            else "${type.simpleName} (opcode ${entry.opcode}, ${expected.name}): expected ${expected.size}, got $actual"
        }
        assertTrue(mismatches.isEmpty(), "Server packet size mismatches:\n${mismatches.joinToString("\n")}")
    }

    @Test
    fun `no server encoder uses an opcode the client does not know`() {
        val known = SERVER_PROTS.map { it.opcode }.toSet()
        val unknown = codec.serverProts.entries
            .filter { it.value.opcode !in known }
            .map { "${it.key.simpleName} -> opcode ${it.value.opcode}" }
        assertTrue(unknown.isEmpty(), "Unknown server opcodes:\n${unknown.joinToString("\n")}")
    }

    @Test
    fun `every client packet has a decoder`() {
        val missing = CLIENT_PROTS.filter { it.opcode !in codec.clientProtsByOpcode }
        assertTrue(missing.isEmpty(), "No decoder registered for: ${describe(missing)}")
    }

    @Test
    fun `every client decoder declares the size the client sends`() {
        val expectedByOpcode = CLIENT_PROTS.associateBy { it.opcode }
        val mismatches = codec.clientProtsByOpcode.mapNotNull { (opcode, entry) ->
            val expected = expectedByOpcode[opcode] ?: return@mapNotNull null
            val actual = entry.size.toInt()
            if (actual == expected.size) null
            else "${entry.protClass.simpleName} (opcode $opcode, ${expected.name}): expected ${expected.size}, got $actual"
        }
        assertTrue(mismatches.isEmpty(), "Client packet size mismatches:\n${mismatches.joinToString("\n")}")
    }

    @Test
    fun `no client decoder uses an opcode the client does not send`() {
        val known = CLIENT_PROTS.map { it.opcode }.toSet()
        val unknown = codec.clientProtsByOpcode
            .filterKeys { it !in known }
            .map { "${it.value.protClass.simpleName} -> opcode ${it.key}" }
        assertTrue(unknown.isEmpty(), "Unknown client opcodes:\n${unknown.joinToString("\n")}")
    }

    @Test
    fun `the expected tables match the client enums`() {
        assertEquals(161, SERVER_PROTS.size, "727 declares 161 server packets")
        assertEquals(104, CLIENT_PROTS.size, "727 declares 104 client packets")
    }

    private fun describe(specs: List<ProtSpec>) =
        specs.joinToString { "${it.name}(opcode=${it.opcode}, size=${it.size})" }

    private companion object {
        /**
         * Opcodes where the 727 client contradicts itself, so no single declared size can be
         * "right".
         *
         * Both are zone packets. The client frames a standalone packet using its `ServerProt`
         * size (`currentPacketSize = currentPacket.size`), but decodes the body with the shared
         * `UpdateZonePacket` routine, whose declared size — and actual byte consumption —
         * disagree:
         *
         * | opcode | `ServerProt` (framing) | `UpdateZonePacket` (body) | bytes the decoder reads |
         * |--------|------------------------|---------------------------|-------------------------|
         * | 95 `MAP_PROJANIM_HALFSQ` | 17 | 19 | 19 |
         * | 128 `MIDI_SONG_LOCATION` | 6 | 8 | 8 |
         *
         * We declare the body size (19 / 8), because that is the length the decoder actually
         * consumes and the only length that is correct inside `UpdateZonePartialEnclosed`, where
         * the sub-packet stream is self-delimiting. Sending either opcode standalone is broken in
         * the client regardless of what the server does, so send them enclosed.
         */
        val CLIENT_SIZE_CONTRADICTIONS = setOf(95, 128)

        /** Server -> client packets, transcribed from the 727 client's `ServerProt` enum. */
        val SERVER_PROTS = listOf(
            ProtSpec("IF_SETPLAYERHEAD", 0, 4),
            ProtSpec("CREATE_CHECK_EMAIL_REPLY", 1, 1),
            ProtSpec("PROCESS_DEV_CONSOLE_COMMAND", 2, -1),
            ProtSpec("GROUND_ITEM_COUNT", 3, 7),
            ProtSpec("UPDATE_INV_PARTIAL", 4, -2),
            ProtSpec("UPDATE_INV_FULL", 5, -2),
            ProtSpec("NPC_UPDATE", 6, -2),
            ProtSpec("IF_SETTEXTFONT", 7, 8),
            ProtSpec("VARP_LARGE", 8, 6),
            ProtSpec("LOGOUT_LOBBY", 9, 0),
            ProtSpec("MESSAGE_PRIVATE", 10, -2),
            ProtSpec("STOP_VORBIS_STREAM", 11, 2),
            ProtSpec("CLIENT_SETVARC_LARGE", 12, 6),
            ProtSpec("IF_MOVESUB", 13, 8),
            ProtSpec("PLAYER_WEIGHT", 14, 2),
            ProtSpec("UPDATE_ZONE_FULL_FOLLOWS", 15, 3),
            ProtSpec("OPEN_URL", 16, -2),
            ProtSpec("VORBIS_PRELOAD_SOUND", 17, 3),
            ProtSpec("VORBIS_SOUND", 18, 8),
            ProtSpec("UPDATE_FRIENDCHAT_CHANNEL_SINGLEUSER", 19, -1),
            ProtSpec("OUTDATED_SET_THEORA_STRING_SOMETHING", 20, 6),
            ProtSpec("SHOW_FACE_HERE", 21, 1),
            ProtSpec("MESSAGE_QUICKCHAT_PRIVATE_ECHO", 22, -1),
            ProtSpec("IF_OPENSUB_ACTIVE_OBJECT", 23, 32),
            ProtSpec("CAM_LOOKAT", 24, 6),
            ProtSpec("MESSAGE_FRIENDS_CHAT", 25, -1),
            ProtSpec("VARCLAN_SET_LONG", 26, 10),
            ProtSpec("CREATE_GROUND_ITEM", 27, 5),
            ProtSpec("CLANCHANNEL_DELTA", 28, -2),
            ProtSpec("PING", 29, 0),
            ProtSpec("CHAT_FILTER_SETTINGS", 30, 2),
            ProtSpec("MESSAGE_QUICKCHAT_PRIVATE", 31, -1),
            ProtSpec("LOYALTY_UPDATE", 32, 5),
            ProtSpec("IF_SETHIDE", 33, 5),
            ProtSpec("IF_OPENSUB_ACTIVE_NPC", 34, 25),
            ProtSpec("IF_SETGRAPHIC", 35, 8),
            ProtSpec("IF_SETTEXTANTIMACRO", 36, 5),
            ProtSpec("IF_OPENTOP", 37, 19),
            ProtSpec("IF_OPENSUB", 38, 23),
            ProtSpec("CAM_MOVETO", 39, 6),
            ProtSpec("SET_CAMERA_TARGET", 40, 4),
            ProtSpec("UPDATE_ZONE_PARTIAL_FOLLOWS", 41, 3),
            ProtSpec("UPDATE_INV_STOP_TRANSMIT", 42, 3),
            ProtSpec("PLAYER_UPDATE", 43, -2),
            ProtSpec("IF_SETANGLE", 44, 10),
            ProtSpec("VARCLAN_ENABLE", 45, 0),
            ProtSpec("CLANSETTINGS_DELTA", 46, -2),
            ProtSpec("NPC_UPDATE_LARGE", 47, -2),
            ProtSpec("IF_SETPOSITION", 48, 8),
            ProtSpec("CLANCHANNEL_FULL", 49, -2),
            ProtSpec("SET_CLAN_STRING", 50, -1),
            ProtSpec("DYNAMIC_MAP_REGION", 51, -2),
            ProtSpec("DEPRECATED_52_CLIENTPACKET_97", 52, 1),
            ProtSpec("IF_SETPLAYERMODEL_OTHER", 53, 10),
            ProtSpec("CLIENT_SETVARCSTR_SMALL", 54, -1),
            ProtSpec("IF_SETSCROLLPOS", 55, 6),
            ProtSpec("PROJANIM_SPECIFIC", 56, 22),
            ProtSpec("UPDATE_GE_SLOT", 57, 20),
            ProtSpec("QUICK_HOP_WORLDS", 58, -1),
            ProtSpec("IF_OPENSUB_ACTIVE_PLAYER", 59, 25),
            ProtSpec("MUSIC_EFFECT", 60, 6),
            ProtSpec("REDUCE_ATTACK_PRIORITY", 61, 1),
            ProtSpec("LOGOUT_FULL", 62, 0),
            ProtSpec("MUSIC_TRACK", 63, 4),
            ProtSpec("RUN_ENERGY", 64, 1),
            ProtSpec("UPDATE_ZONE_PARTIAL_ENCLOSED", 65, -2),
            ProtSpec("CAM_SMOOTHRESET", 66, 0),
            ProtSpec("CHAT_FILTER_SETTINGS_PRIVATECHAT", 67, 1),
            ProtSpec("VARBIT_SMALL", 68, 3),
            ProtSpec("DESTROY_OBJECT", 69, 2),
            ProtSpec("MINIMAP_FLAG", 70, 2),
            ProtSpec("CAM_SHAKE", 71, 6),
            ProtSpec("IF_SETPLAYERMODEL", 72, 4),
            ProtSpec("DEBUG_SERVER_TRIGGERS", 73, -1),
            ProtSpec("FRIEND_STATUS", 74, -2),
            ProtSpec("JCOINS_UPDATE", 75, 4),
            ProtSpec("APPLY_DEBUG", 76, 2),
            ProtSpec("PRELOAD_SONG", 77, 2),
            ProtSpec("IF_CLOSESUB", 78, 4),
            ProtSpec("HINT_ARROW", 79, 14),
            ProtSpec("OBJ_ANIM_SPECIFIC", 80, 9),
            ProtSpec("MESSAGE_CLANCHANNEL", 81, -1),
            ProtSpec("IF_SETANIM", 82, 8),
            ProtSpec("MESSAGE_QUICKCHAT_PLAYER_GROUP", 83, -1),
            ProtSpec("CUSTOMIZE_OBJECT", 84, -1),
            ProtSpec("REGION", 85, -2),
            ProtSpec("OPEN_SOCIAL_NETWORK_URL", 86, -2),
            ProtSpec("CREATE_ACCOUNT_REPLY", 87, 1),
            ProtSpec("OBJECT_PREFETCH", 88, 5),
            ProtSpec("CAM_RESET", 89, 0),
            ProtSpec("UPDATE_UID192", 90, 28),
            ProtSpec("UPDATE_SITESETTINGS_COOKIE", 91, -1),
            ProtSpec("MESSAGE_PRIVATE_ECHO", 92, -2),
            ProtSpec("PLAY_SONG_AT_POSITION", 93, 11),
            ProtSpec("VARCLAN_DISABLE", 94, 0),
            ProtSpec("MAP_PROJANIM_HALFSQ", 95, 17),
            ProtSpec("OUTDATED_INTERFACE_PULSE_EVENT", 96, 8),
            ProtSpec("UPDATE_IGNORE_LIST", 97, -2),
            ProtSpec("REFLECTION_CHECK", 98, -2),
            ProtSpec("RUN_CS2_SCRIPT", 99, -2),
            ProtSpec("CLEAR_VARPS", 100, 0),
            ProtSpec("FRIENDLIST_LOADED", 101, 0),
            ProtSpec("UPDATE_REBOOT_TIMER", 102, 2),
            ProtSpec("WORLD_LIST", 103, -2),
            ProtSpec("VORBIS_SPEECH_SOUND", 104, 6),
            ProtSpec("IF_SETCOLOR", 105, 6),
            ProtSpec("SET_VORBIS_SOUND", 106, 2),
            ProtSpec("GROUND_ITEM_REVEAL", 107, 7),
            ProtSpec("VARBIT_LARGE", 108, 6),
            ProtSpec("IF_SETNPCHEAD", 109, 8),
            ProtSpec("CUTSCENE", 110, -2),
            ProtSpec("PLAYER_OPTION", 111, -1),
            ProtSpec("IF_SETITEM", 112, 10),
            ProtSpec("OBJ_ANIM", 113, 6),
            ProtSpec("TILE_MESSAGE", 114, -1),
            ProtSpec("VARP_SMALL", 115, 3),
            ProtSpec("CLIENT_SETVARC_SMALL", 116, 3),
            ProtSpec("CREATE_OBJECT", 117, 6),
            ProtSpec("CAM_FORCEANGLE", 118, 4),
            ProtSpec("CLIENT_SETVARCSTR_LARGE", 119, -2),
            ProtSpec("RESET_SOUNDS", 120, 0),
            ProtSpec("IF_SETTARGETPARAM", 121, 12),
            ProtSpec("RESET_ALL_ANIMATIONS", 122, 0),
            ProtSpec("VARCLAN_SET_BYTE", 123, 3),
            ProtSpec("IF_SETTEXT", 124, -2),
            ProtSpec("REMOVE_GROUND_ITEM", 125, 3),
            ProtSpec("SPOT_ANIM_SPECIFIC", 126, 12),
            ProtSpec("FRIENDS_CHAT_CHANNEL", 127, -2),
            ProtSpec("MIDI_SONG_LOCATION", 128, 6),
            ProtSpec("SET_TARGET", 129, 2),
            ProtSpec("IF_SETPLAYERHEAD_IGNOREWORN", 130, 10),
            ProtSpec("MESSAGE_QUICKCHAT_CLANCHANNEL", 131, -1),
            ProtSpec("IF_SETRETEX", 132, 9),
            ProtSpec("MESSAGE_PLAYER_GROUP", 133, -1),
            ProtSpec("SET_SONG_VOLUME", 134, 2),
            ProtSpec("IF_SETMODEL", 135, 8),
            ProtSpec("SOUND_SYNTH", 136, 8),
            ProtSpec("CLANSETTINGS_FULL", 137, -2),
            ProtSpec("ADD_IGNORE", 138, -1),
            ProtSpec("SPOT_ANIM", 139, 8),
            ProtSpec("UPDATE_STAT", 140, 6),
            ProtSpec("VARCLAN_SET_INT", 141, 6),
            ProtSpec("MESSAGE_QUICKCHAT_FRIENDS_CHAT", 142, -1),
            ProtSpec("MAP_PROJANIM", 143, 16),
            ProtSpec("IF_SETRECOL", 144, 9),
            ProtSpec("ANIMATE_NPC", 145, 19),
            ProtSpec("TRIGGER_ONDIALOGABORT", 146, 0),
            ProtSpec("IDENTIFY_HOST_NAME", 147, 4),
            ProtSpec("IF_RESETTARGETPARAM", 148, 10),
            ProtSpec("HINT_TRAIL", 149, -2),
            ProtSpec("IF_SETCLICKMASK", 150, 5),
            ProtSpec("SET_DRAW_ORDER", 151, 1),
            ProtSpec("MESSAGE_PUBLIC", 152, -1),
            ProtSpec("SET_CURSOR", 153, -1),
            ProtSpec("BLOCK_MINIMAP_STATE", 154, 1),
            ProtSpec("IF_OPENSUB_ACTIVE_GROUNDITEM", 155, 29),
            ProtSpec("WINDOW_CALL_WITH_ARGS", 156, -2),
            ProtSpec("UPDATE_DOB", 157, 4),
            ProtSpec("IF_SETPLAYERHEAD_OTHER", 158, 10),
            ProtSpec("REQUEST_FPS", 159, 8),
            ProtSpec("GAME_MESSAGE", 160, -1),
        )

        /** Client -> server packets, transcribed from the 727 client's `ClientProt` enum. */
        val CLIENT_PROTS = listOf(
            ProtSpec("PING", 0, 0),
            ProtSpec("PLAYER_OP6", 1, 3),
            ProtSpec("SOUND_EFFECT_MUSIC_ENDED", 2, 4),
            ProtSpec("NPC_EXAMINE", 3, 3),
            ProtSpec("IF_ON_IF", 4, 16),
            ProtSpec("WORLD_MAP_CLICK", 5, 4),
            ProtSpec("PLAYER_OP2", 6, 3),
            ProtSpec("FC_SET_RANK", 7, -1),
            ProtSpec("GROUND_ITEM_OP4", 8, 7),
            ProtSpec("IF_OP4", 9, 8),
            ProtSpec("SEND_PREFERENCES", 10, -1),
            ProtSpec("RESUME_HSLDIALOG", 11, 2),
            ProtSpec("REMOVE_IGNORE", 12, -1),
            ProtSpec("IF_ON_PLAYER", 13, 11),
            ProtSpec("QUICKCHAT_PRIVATE", 14, -1),
            ProtSpec("SEND_PRIVATE_MESSAGE", 15, -2),
            ProtSpec("NPC_OP2", 16, 3),
            ProtSpec("GRAND_EXCHANGE_ITEM_SELECT", 17, 2),
            ProtSpec("SONG_LOADED", 18, 4),
            ProtSpec("IF_OP6", 19, 8),
            ProtSpec("CHAT_SETFILTER", 20, 3),
            ProtSpec("IF_OP8", 21, 8),
            ProtSpec("IF_OP9", 22, 8),
            ProtSpec("IF_OP7", 23, 8),
            ProtSpec("GROUND_ITEM_OP1", 24, 7),
            ProtSpec("GROUND_ITEM_OP2", 25, 7),
            ProtSpec("ADD_FRIEND", 26, -1),
            ProtSpec("IF_OP2", 27, 8),
            ProtSpec("KEY_PRESS", 28, -2),
            ProtSpec("REMOVE_FRIEND", 29, -1),
            ProtSpec("CHAT_TYPE", 30, 1),
            ProtSpec("PLAYER_OP3", 31, 3),
            ProtSpec("OBJECT_OP4", 32, 9),
            ProtSpec("WALK", 33, 5),
            ProtSpec("ADD_IGNORE", 34, -1),
            ProtSpec("BUG_REPORT", 35, -2),
            ProtSpec("REFLECTION_CHECK", 36, -1),
            ProtSpec("PLAY_VORBIS", 37, 2),
            ProtSpec("OBJECT_OP3", 38, 9),
            ProtSpec("UNUSED_CLAN_OP", 39, -1),
            ProtSpec("MOVE_MOUSE", 40, -1),
            ProtSpec("IF_ON_NPC", 41, 11),
            ProtSpec("MINI_WALK", 42, 18),
            ProtSpec("GROUND_ITEM_OP5", 43, 7),
            ProtSpec("SEND_FPS", 44, 9),
            ProtSpec("CUTSCENE_FINISHED", 45, 1),
            ProtSpec("IF_ON_TILE", 46, 12),
            ProtSpec("REQUEST_WORLD_LIST", 47, 4),
            ProtSpec("OBJECT_OP5", 48, 9),
            ProtSpec("IF_CONTINUE", 49, 6),
            ProtSpec("NPC_OP3", 50, 3),
            ProtSpec("PLAYER_OP7", 51, 3),
            ProtSpec("EMAIL_VALIDATION_SUBMIT_CODE", 52, -1),
            ProtSpec("PLAYER_OP9", 53, 3),
            ProtSpec("GROUND_ITEM_OP3", 54, 7),
            ProtSpec("TRANSMITVAR_VERIFYID", 55, 4),
            ProtSpec("LOBBY_HYPERLINK", 56, -2),
            ProtSpec("MOUSE_BUTTON_CLICK", 57, 7),
            ProtSpec("RESUME_COUNTDIALOG", 58, 4),
            ProtSpec("MOUSE_CLICK", 59, 6),
            ProtSpec("CLOSE_INTERFACE", 60, 0),
            ProtSpec("GROUND_ITEM_EXAMINE", 61, 7),
            ProtSpec("CLIENT_FOCUS", 62, 1),
            ProtSpec("UNK_63", 63, 4),
            ProtSpec("QUICKCHAT_PUBLIC", 64, -1),
            ProtSpec("NPC_OP1", 65, 3),
            ProtSpec("PLAYER_OP1", 66, 3),
            ProtSpec("IF_ON_GROUND_ITEM", 67, 15),
            ProtSpec("IF_OP3", 68, 8),
            ProtSpec("RESUME_CLANFORUMQFCDIALOG", 69, -1),
            ProtSpec("PLAYER_OP10", 70, 3),
            ProtSpec("FC_JOIN", 71, -1),
            ProtSpec("IF_OP5", 72, 8),
            ProtSpec("OBJECT_EXAMINE", 73, 9),
            ProtSpec("IF_DRAG_ONTO_IF", 74, 16),
            ProtSpec("OBJECT_OP1", 75, 9),
            ProtSpec("REGION_LOADED_CONFIRM", 76, 0),
            ProtSpec("NPC_OP4", 77, 3),
            ProtSpec("MOVE_MOUSE_2", 78, -1),
            ProtSpec("ACCOUNT_CREATION_STAGE", 79, 1),
            ProtSpec("RESUME_NAMEDIALOG", 80, -1),
            ProtSpec("IF_OP10", 81, 8),
            ProtSpec("UNK_82", 82, 4),
            ProtSpec("MOVE_CAMERA", 83, 4),
            ProtSpec("SCREEN_SIZE", 84, 6),
            ProtSpec("COMMAND", 85, -1),
            ProtSpec("CHAT", 86, -1),
            ProtSpec("RESUME_TEXTDIALOG", 87, -1),
            ProtSpec("WRITE_PING", 88, 2),
            ProtSpec("PLAYER_OP4", 89, 3),
            ProtSpec("CLANCHANNEL_KICKUSER", 90, -1),
            ProtSpec("FC_KICK", 91, -1),
            ProtSpec("EMAIL_VALIDATION_ADD_NEW_ADDRESS", 92, -2),
            ProtSpec("OBJECT_OP2", 93, 9),
            ProtSpec("PLAYER_OP8", 94, 3),
            ProtSpec("NPC_OP5", 95, 3),
            ProtSpec("IF_OP1", 96, 8),
            ProtSpec("DEPRECATED_97_SERVERPACKET_52", 97, -1),
            ProtSpec("IF_ON_OBJECT", 98, 17),
            ProtSpec("EMAIL_VALIDATION_CHANGE_ADDRESS", 99, -2),
            ProtSpec("REPORT_ABUSE", 100, -1),
            ProtSpec("SEND_SIGN_UP_FORM", 101, -2),
            ProtSpec("CHECK_EMAIL_VALIDITY", 102, -2),
            ProtSpec("PLAYER_OP5", 103, 3),
        )
    }
}
