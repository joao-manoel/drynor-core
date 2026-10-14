package org.darkan.core.net.prot.revision.rev727

import io.ktor.utils.io.*
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import org.darkan.core.model.ChatMessageType
import org.darkan.core.net.prot.ServerProt
import org.darkan.core.net.prot.assertServerRoundTrip
import org.darkan.core.net.prot.dto.FriendStatusUpdate
import org.darkan.core.net.prot.dto.FriendsChatPlayer
import org.darkan.core.net.prot.dto.IgnoreEntry
import org.darkan.core.net.prot.packet.AddIgnore
import org.darkan.core.net.prot.packet.ChatFilterSettings
import org.darkan.core.net.prot.packet.ChatFilterSettingsPrivateChat
import org.darkan.core.net.prot.packet.FriendStatus
import org.darkan.core.net.prot.packet.FriendsChatChannel
import org.darkan.core.net.prot.packet.GameMessage
import org.darkan.core.net.prot.packet.MessageClanChannel
import org.darkan.core.net.prot.packet.MessageFriendsChat
import org.darkan.core.net.prot.packet.MessagePlayerGroup
import org.darkan.core.net.prot.packet.MessagePrivate
import org.darkan.core.net.prot.packet.MessagePrivateEcho
import org.darkan.core.net.prot.packet.MessagePublic
import org.darkan.core.net.prot.packet.MessageQuickChatClanChannel
import org.darkan.core.net.prot.packet.MessageQuickChatFriendsChat
import org.darkan.core.net.prot.packet.MessageQuickChatPlayerGroup
import org.darkan.core.net.prot.packet.MessageQuickChatPrivate
import org.darkan.core.net.prot.packet.MessageQuickChatPrivateEcho
import org.darkan.core.net.prot.packet.UpdateFriendchatChannelSingleUser
import org.darkan.core.net.prot.packet.UpdateIgnoreList
import org.darkan.core.social.PublicChatMessage
import org.darkan.core.social.QuickChatMessage
import world.gregs.voidps.cache.secure.Huffman
import org.darkan.core.net.prot.ChatHuffman
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Rev727ServerMessagingDecoderTest {
    private val codec = register727()

    private val huffman = Huffman().load(
        ByteArray(256) { symbol ->
            when (symbol) {
                ' '.code -> 1
                'a'.code -> 8
                else -> 9
            }.toByte()
        }
    )

    private val longText = "a".repeat(150) + " the quick brown fox jumps over the lazy dog 0123456789 !?"
    private val qcData = byteArrayOf(0, 0, 0, 99, -1)

    private fun huffmanRoundTrip(prot: ServerProt, expected: ServerProt = prot) = runBlocking(ChatHuffman(huffman)) {
        val channel = ByteChannel()
        codec.encodeServerProt(prot, channel)
        channel.flushAndClose()
        val bytes = channel.readRemaining().readByteArray()
        val entry = codec.serverProts.getValue(prot::class)
        val source = Buffer().apply { write(bytes) }
        val decoded = codec.decode(entry, entry.opcode, source)
        assertTrue(source.exhausted(), "${prot::class.simpleName} left ${source.size} unread bytes")
        assertEquals(expected, decoded, "${prot::class.simpleName} round trip")
    }

    private fun qc(fileId: Int, data: ByteArray?, effects: Int = 0) = QuickChatMessage("", fileId, data, effects)

    @Test
    fun `synthetic huffman table compresses below one byte per character`() = runBlocking(ChatHuffman(huffman)) {
        val channel = ByteChannel()
        codec.encodeServerProt(MessagePrivateEcho("Bob", "a a a a a a a a"), channel)
        channel.flushAndClose()
        assertTrue(channel.readRemaining().readByteArray().size < "Bob".length + 1 + 15)
    }

    @Test
    fun `private message decodes names crown and huffman text past the timestamp`() {
        huffmanRoundTrip(MessagePrivate(2, "Alice", "Alice", "hello there"))
        huffmanRoundTrip(MessagePrivate(0, "Alice", "Old name", longText))
        huffmanRoundTrip(MessagePrivate(1, "Alice", "Alice", ""))
    }

    @Test
    fun `private message longer than the wire limit decodes truncated`() {
        val text = "b".repeat(300)
        huffmanRoundTrip(MessagePrivate(0, "Alice", "Alice", text), MessagePrivate(0, "Alice", "Alice", text.take(210)))
        huffmanRoundTrip(MessagePrivateEcho("Bob", text), MessagePrivateEcho("Bob", text.take(210)))
    }

    @Test
    fun `characters outside cp1252 decode as question marks`() {
        huffmanRoundTrip(MessagePrivateEcho("Bob", "hi 世 €"), MessagePrivateEcho("Bob", "hi ? €"))
    }

    @Test
    fun `private echo decodes recipient and huffman text`() {
        huffmanRoundTrip(MessagePrivateEcho("Bob", "see you"))
        huffmanRoundTrip(MessagePrivateEcho("", ""))
    }

    @Test
    fun `friends chat message decodes the chat name from its base37 long`() {
        huffmanRoundTrip(MessageFriendsChat(2, "Alice", "Al", "my chat", longText), MessageFriendsChat(2, "Alice", "Al", "my_chat", longText))
        huffmanRoundTrip(MessageFriendsChat(0, "Alice", "Alice", "Trent", "hi"), MessageFriendsChat(0, "Alice", "Alice", "trent", "hi"))
        huffmanRoundTrip(MessageFriendsChat(0, "Alice", "Alice", "", "hi"))
    }

    @Test
    fun `clan channel message decodes guest flag and huffman text`() {
        huffmanRoundTrip(MessageClanChannel(guest = false, crown = 1, displayName = "Alice", message = "clan hi"))
        huffmanRoundTrip(MessageClanChannel(guest = true, crown = 0, displayName = "Guest", message = longText))
    }

    @Test
    fun `player group message decodes names crown and huffman text`() {
        huffmanRoundTrip(MessagePlayerGroup(2, "Alice", "Alice", "group hi"))
        huffmanRoundTrip(MessagePlayerGroup(255, "Alice", "Bob", ""))
    }

    @Test
    fun `public chat decodes pid effects icon and huffman text`() {
        huffmanRoundTrip(MessagePublic(2047, 2, PublicChatMessage("hello world", 0x0203)))
        huffmanRoundTrip(MessagePublic(65535, 0, PublicChatMessage(longText, 0x7FFF)))
        huffmanRoundTrip(MessagePublic(1, 0, PublicChatMessage("", 0)))
    }

    @Test
    fun `public chat effects above fifteen bits are masked off`() {
        huffmanRoundTrip(MessagePublic(1, 1, PublicChatMessage("x", 0x8123)), MessagePublic(1, 1, PublicChatMessage("x", 0x0123)))
    }

    @Test
    fun `public quick chat decodes file id data and effects without the quick chat bit`() {
        codec.assertServerRoundTrip(
            MessagePublic(5, 1, QuickChatMessage("I have 99 attack", 1234, qcData, 0x1234)),
            MessagePublic(5, 1, qc(1234, qcData, 0x1234)),
        )
        codec.assertServerRoundTrip(MessagePublic(5, 0, QuickChatMessage("Hi", 65535)), MessagePublic(5, 0, qc(65535, null)))
    }

    @Test
    fun `quick chat private echo decodes recipient file id and trailing data`() {
        codec.assertServerRoundTrip(MessageQuickChatPrivateEcho("Bob", QuickChatMessage("text", 7, qcData)), MessageQuickChatPrivateEcho("Bob", qc(7, qcData)))
        codec.assertServerRoundTrip(MessageQuickChatPrivateEcho("Bob", QuickChatMessage("text", 7, ByteArray(0))), MessageQuickChatPrivateEcho("Bob", qc(7, null)))
    }

    @Test
    fun `quick chat private decodes past the file id timestamp`() {
        codec.assertServerRoundTrip(MessageQuickChatPrivate(2, "Alice", "Al", QuickChatMessage("x", 40000, qcData)), MessageQuickChatPrivate(2, "Alice", "Al", qc(40000, qcData)))
        codec.assertServerRoundTrip(MessageQuickChatPrivate(0, "Alice", "Alice", QuickChatMessage("x", 0)), MessageQuickChatPrivate(0, "Alice", "Alice", qc(0, null)))
    }

    @Test
    fun `quick chat friends chat decodes chat name and payload`() {
        codec.assertServerRoundTrip(
            MessageQuickChatFriendsChat("darkan", 1, "Alice", "Alice", QuickChatMessage("x", 300, qcData)),
            MessageQuickChatFriendsChat("darkan", 1, "Alice", "Alice", qc(300, qcData)),
        )
        codec.assertServerRoundTrip(
            MessageQuickChatFriendsChat("Big Chat", 0, "Alice", "Bob", QuickChatMessage("x", 1)),
            MessageQuickChatFriendsChat("big_chat", 0, "Alice", "Bob", qc(1, null)),
        )
    }

    @Test
    fun `quick chat clan channel decodes guest flag and payload`() {
        codec.assertServerRoundTrip(MessageQuickChatClanChannel(true, 1, "Alice", QuickChatMessage("x", 12, qcData)), MessageQuickChatClanChannel(true, 1, "Alice", qc(12, qcData)))
        codec.assertServerRoundTrip(MessageQuickChatClanChannel(false, 0, "Alice", QuickChatMessage("x", 12)), MessageQuickChatClanChannel(false, 0, "Alice", qc(12, null)))
    }

    @Test
    fun `quick chat player group decodes names crown and payload`() {
        codec.assertServerRoundTrip(MessageQuickChatPlayerGroup(2, "Alice", "Al", QuickChatMessage("x", 9, qcData)), MessageQuickChatPlayerGroup(2, "Alice", "Al", qc(9, qcData)))
        codec.assertServerRoundTrip(MessageQuickChatPlayerGroup(0, "Alice", "Alice", QuickChatMessage("x", 9)), MessageQuickChatPlayerGroup(0, "Alice", "Alice", qc(9, null)))
    }

    @Test
    fun `game message decodes type flags target and text`() {
        codec.assertServerRoundTrip(GameMessage(ChatMessageType.GAME, "Welcome to Darkan."))
        codec.assertServerRoundTrip(GameMessage(ChatMessageType.UNK_121, "", targetDisplayName = "Alice", effectFlags = -1))
        codec.assertServerRoundTrip(GameMessage(ChatMessageType.TRADE_REQUEST, "wishes to trade with you.", targetDisplayName = "", effectFlags = Int.MIN_VALUE))
    }

    @Test
    fun `game message longer than the wire limit decodes truncated`() {
        val text = "z".repeat(400)
        codec.assertServerRoundTrip(GameMessage(ChatMessageType.FILTERABLE, text), GameMessage(ChatMessageType.FILTERABLE, text.take(248)))
    }

    @Test
    fun `friend status decodes online and offline entries`() {
        val online = FriendStatusUpdate(true, "Alice", "OldAlice", 65535, -1, true, "World 1", 255, -1)
        val offline = FriendStatusUpdate(false, "Bob", "", 0, 127, false)
        codec.assertServerRoundTrip(FriendStatus(arrayOf(online, offline, online.copy(world = 1, friendsChatRank = 7, platform = 0, worldFlags = 0x10))))
        codec.assertServerRoundTrip(FriendStatus(emptyArray()))
    }

    @Test
    fun `friend status defaults the fields the wire omits`() {
        val sent = FriendStatusUpdate(false, "Bob", "", 0, 3, false, "Ignored", 4, 99)
        codec.assertServerRoundTrip(FriendStatus(arrayOf(sent)), FriendStatus(arrayOf(sent.copy(worldName = null, platform = -1, worldFlags = 0))))
        val nullName = FriendStatusUpdate(false, "Bob", "", 12, 3, false, null, 0, 0)
        codec.assertServerRoundTrip(FriendStatus(arrayOf(nullName)), FriendStatus(arrayOf(nullName.copy(worldName = ""))))
    }

    @Test
    fun `add ignore decodes both flags`() {
        codec.assertServerRoundTrip(AddIgnore("alice", "Alice"))
        codec.assertServerRoundTrip(AddIgnore("alice", "Alice", temporary = true))
        codec.assertServerRoundTrip(AddIgnore("alice", "Alice", editDisplayName = true))
        codec.assertServerRoundTrip(AddIgnore("", "", temporary = true, editDisplayName = true))
    }

    @Test
    fun `friends chat channel decodes an empty payload as clear`() {
        codec.assertServerRoundTrip(FriendsChatChannel(clear = true))
    }

    @Test
    fun `friends chat channel decodes owner rank and players`() {
        val players = arrayOf(
            FriendsChatPlayer("Alice", "Alice", 1, -1, "World 1"),
            FriendsChatPlayer("Bob", "OldBob", 65535, 127, ""),
            FriendsChatPlayer("Carl", "Carl", 0, 7, "Lobby"),
        )
        codec.assertServerRoundTrip(
            FriendsChatChannel(ownerDisplayName = "Owner", ownerUsername = "OldOwner", chatName = "Owner", minRankCanKick = -1, players = players),
            FriendsChatChannel(ownerDisplayName = "Owner", ownerUsername = "OldOwner", chatName = "owner", minRankCanKick = -1, players = players),
        )
        codec.assertServerRoundTrip(FriendsChatChannel(ownerDisplayName = "owner", chatName = "owner", minRankCanKick = 7, players = emptyArray()))
    }

    @Test
    fun `friends chat channel without a player list decodes null players`() {
        codec.assertServerRoundTrip(FriendsChatChannel(ownerDisplayName = "owner", ownerUsername = null, chatName = "my chat"), FriendsChatChannel(ownerDisplayName = "owner", chatName = "my_chat"))
    }

    @Test
    fun `single friends chat user decodes world name unless removed`() {
        codec.assertServerRoundTrip(UpdateFriendchatChannelSingleUser("Alice", "OldAlice", 65535, 127, "World 99"))
        codec.assertServerRoundTrip(UpdateFriendchatChannelSingleUser("Alice", "Alice", 1, -1, "World 1"))
        codec.assertServerRoundTrip(UpdateFriendchatChannelSingleUser("Alice", "Alice", 1, 0, null), UpdateFriendchatChannelSingleUser("Alice", "Alice", 1, 0, ""))
        codec.assertServerRoundTrip(UpdateFriendchatChannelSingleUser("Alice", "Alice", 1, -128, "gone"), UpdateFriendchatChannelSingleUser("Alice", "Alice", 1, -128, null))
    }

    @Test
    fun `ignore list decodes every entry`() {
        codec.assertServerRoundTrip(UpdateIgnoreList(arrayOf(IgnoreEntry("alice", "Alice"), IgnoreEntry("", ""), IgnoreEntry("bob", "Bob"))))
        codec.assertServerRoundTrip(UpdateIgnoreList(emptyArray()))
    }

    @Test
    fun `chat filter settings decode their transformed bytes`() {
        codec.assertServerRoundTrip(ChatFilterSettings(tradeFilter = 2, publicFilter = 1))
        codec.assertServerRoundTrip(ChatFilterSettings(tradeFilter = 0, publicFilter = 0))
        codec.assertServerRoundTrip(ChatFilterSettings(tradeFilter = 255, publicFilter = 255))
        codec.assertServerRoundTrip(ChatFilterSettingsPrivateChat(2))
        codec.assertServerRoundTrip(ChatFilterSettingsPrivateChat(255))
    }
}
