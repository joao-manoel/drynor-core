package org.drynor.core.net.prot.revision.rev727

import io.ktor.utils.io.*
import kotlinx.io.Source
import kotlinx.io.readByteArray
import org.drynor.core.model.ChatMessageType
import org.drynor.core.net.prot.*
import org.drynor.core.net.prot.dto.FriendStatusUpdate
import org.drynor.core.net.prot.dto.FriendsChatPlayer
import org.drynor.core.net.prot.dto.IgnoreEntry
import org.drynor.core.net.prot.packet.*
import org.drynor.core.social.PublicChatMessage
import org.drynor.core.social.QuickChatMessage
import world.gregs.voidps.buffer.*

private suspend fun Source.readHuffmanMessage(): String {
    val length = readSmart()
    val compressed = readByteArray()
    if (length == 0) return ""
    return chatHuffman().decompress(compressed, length) ?: ""
}

private fun Source.readQuickChatMessage(effects: Int = 0): QuickChatMessage {
    val fileId = readUShort()
    val data = if (exhausted()) null else readByteArray()
    return QuickChatMessage("", fileId, data, effects)
}

internal fun Codec.registerRev727MessagingPackets() {
    serverProt<MessagePrivate>(opcode = 10, size = ProtSize.VarShort) { out ->
        out.writeName(displayName, quickResponseName)
        out.writeHashedMessageTimestamp(message.take(210))
        out.writeByte(crown)
        out.writeBytes(chatHuffman().compress(message.take(210)))
    }.decoder {
        val (displayName, quickResponseName) = readName()
        skipHashedMessageTimestamp()
        val crown = readUByte()
        MessagePrivate(crown, displayName, quickResponseName, readHuffmanMessage())
    }

    serverProt<MessageQuickChatPrivateEcho>(opcode = 22, size = ProtSize.VarByte) { out ->
        out.writeRSString(senderDisplayName)
        out.writeShort(message.fileId)
        message.data?.let { out.writeBytes(it) }
    }.decoder {
        MessageQuickChatPrivateEcho(readRSString(), readQuickChatMessage())
    }

    serverProt<MessageFriendsChat>(opcode = 25, size = ProtSize.VarByte) { out ->
        out.writeName(displayName, quickResponseName)
        out.writeLong(chatName)
        out.writeHashedMessageTimestamp(message.take(210))
        out.writeByte(crown)
        out.writeBytes(chatHuffman().compress(message.take(210)))
    }.decoder {
        val (displayName, quickResponseName) = readName()
        val chatName = readLong().toRSString()
        skipHashedMessageTimestamp()
        val crown = readUByte()
        MessageFriendsChat(crown, displayName, quickResponseName, chatName, readHuffmanMessage())
    }

    serverProt<MessageQuickChatPrivate>(opcode = 31, size = ProtSize.VarByte) { out ->
        out.writeName(displayName, quickResponseName)
        out.writeHashedQCMessageTimestamp(message.fileId)
        out.writeByte(crown)
        out.writeShort(message.fileId)
        message.data?.let { out.writeBytes(it) }
    }.decoder {
        val (displayName, quickResponseName) = readName()
        skipHashedMessageTimestamp()
        val crown = readUByte()
        MessageQuickChatPrivate(crown, displayName, quickResponseName, readQuickChatMessage())
    }

    serverProt<MessageClanChannel>(opcode = 81, size = ProtSize.VarByte) { out ->
        out.writeBoolean(!guest)
        out.writeRSString(displayName)
        out.writeHashedMessageTimestamp(message.take(210))
        out.writeByte(crown)
        out.writeBytes(chatHuffman().compress(message.take(210)))
    }.decoder {
        val guest = !readBoolean()
        val displayName = readRSString()
        skipHashedMessageTimestamp()
        val crown = readUByte()
        MessageClanChannel(guest, crown, displayName, readHuffmanMessage())
    }

    serverProt<MessageQuickChatPlayerGroup>(opcode = 83, size = ProtSize.VarByte) { out ->
        out.writeName(displayName, quickResponseName)
        out.writeByte(crown)
        out.writeShort(message.fileId)
        message.data?.let { out.writeBytes(it) }
    }.decoder {
        val (displayName, quickResponseName) = readName()
        val crown = readUByte()
        MessageQuickChatPlayerGroup(crown, displayName, quickResponseName, readQuickChatMessage())
    }

    serverProt<MessagePrivateEcho>(opcode = 92, size = ProtSize.VarShort) { out ->
        out.writeRSString(senderDisplayName)
        out.writeBytes(chatHuffman().compress(message.take(210)))
    }.decoder {
        MessagePrivateEcho(readRSString(), readHuffmanMessage())
    }

    serverProt<MessageQuickChatFriendsChat>(opcode = 142, size = ProtSize.VarByte) { out ->
        out.writeName(displayName, quickResponseName)
        out.writeLong(chatName)
        out.writeHashedQCMessageTimestamp(message.fileId)
        out.writeByte(crown)
        out.writeShort(message.fileId)
        message.data?.let { out.writeBytes(it) }
    }.decoder {
        val (displayName, quickResponseName) = readName()
        val chatName = readLong().toRSString()
        skipHashedMessageTimestamp()
        val crown = readUByte()
        MessageQuickChatFriendsChat(chatName, crown, displayName, quickResponseName, readQuickChatMessage())
    }

    serverProt<MessageQuickChatClanChannel>(opcode = 131, size = ProtSize.VarByte) { out ->
        out.writeBoolean(!guest)
        out.writeRSString(displayName)
        out.writeHashedQCMessageTimestamp(message.fileId)
        out.writeByte(crown)
        out.writeShort(message.fileId)
        message.data?.let { out.writeBytes(it) }
    }.decoder {
        val guest = !readBoolean()
        val displayName = readRSString()
        skipHashedMessageTimestamp()
        val crown = readUByte()
        MessageQuickChatClanChannel(guest, crown, displayName, readQuickChatMessage())
    }

    serverProt<MessagePlayerGroup>(opcode = 133, size = ProtSize.VarByte) { out ->
        out.writeName(displayName, quickResponseName)
        out.writeByte(crown)
        out.writeBytes(chatHuffman().compress(message.take(210)))
    }.decoder {
        val (displayName, quickResponseName) = readName()
        val crown = readUByte()
        MessagePlayerGroup(crown, displayName, quickResponseName, readHuffmanMessage())
    }

    serverProt<MessagePublic>(opcode = 152, size = ProtSize.VarByte) { out ->
        out.writeShort(pid)
        if (message is QuickChatMessage) {
            out.writeShort(message.effects or 0x8000)
            out.writeByte(messageIcon)
            out.writeShort(message.fileId)
            message.data?.let { out.writeBytes(it) }
        } else {
            out.writeShort(message.effects and 0x7FFF)
            out.writeByte(messageIcon)
            out.writeBytes(chatHuffman().compress(message.message.take(210)))
        }
    }.decoder {
        val pid = readUShort()
        val effects = readUShort()
        val messageIcon = readUByte()
        val message = if ((effects and 0x8000) != 0) {
            readQuickChatMessage(effects and 0x7FFF)
        } else {
            PublicChatMessage(readHuffmanMessage(), effects)
        }
        MessagePublic(pid, messageIcon, message)
    }

    serverProt<GameMessage>(opcode = 160, size = ProtSize.VarByte) { out ->
        var maskData = 0
        if (targetDisplayName != null) {
            maskData = maskData or 0x1
            //maskData |= 0x2;
        }
        out.writeSmart(type.value)
        out.writeInt(effectFlags)
        out.writeByte(maskData)
        if (targetDisplayName != null) {
            out.writeRSString(targetDisplayName)
            //stream.writeRSString(target.getDisplayName());
        }
        out.writeRSString(message.take(248))
    }.decoder {
        val type = ChatMessageType.forId(readSmart())
        val effectFlags = readInt()
        val maskData = readUByte()
        val targetDisplayName = if ((maskData and 0x1) != 0) readRSString() else null
        if ((maskData and 0x3) == 0x3) readRSString()
        GameMessage(type, readRSString(), targetDisplayName, effectFlags)
    }

    serverProt<FriendStatus>(opcode = 74, size = ProtSize.VarShort) { out ->
        for (update in updates) {
            out.writeBoolean(update.warnMessage)
            out.writeRSString(update.displayName)
            out.writeRSString(update.previousDisplayName)
            out.writeShort(update.world)
            out.writeByte(update.friendsChatRank)
            out.writeBoolean(update.referrer)
            if (update.world > 0) {
                out.writeRSString(update.worldName ?: "")
                out.writeByte(update.platform)
                out.writeInt(update.worldFlags)
            }
        }
    }.decoder {
        val updates = mutableListOf<FriendStatusUpdate>()
        while (!exhausted()) {
            val warnMessage = readBoolean()
            val displayName = readRSString()
            val previousDisplayName = readRSString()
            val world = readUShort()
            val friendsChatRank = readByte().toInt()
            val referrer = readBoolean()
            updates += if (world > 0) {
                FriendStatusUpdate(warnMessage, displayName, previousDisplayName, world, friendsChatRank, referrer, readRSString(), readUByte(), readInt())
            } else {
                FriendStatusUpdate(warnMessage, displayName, previousDisplayName, world, friendsChatRank, referrer)
            }
        }
        FriendStatus(updates.toTypedArray())
    }

    serverProt<FriendlistLoaded>(opcode = 101)

    serverProt<AddIgnore>(opcode = 138, size = ProtSize.VarByte) { out ->
        var flags = 0
        if (editDisplayName) flags = flags or 0x1
        if (temporary) flags = flags or 0x2
        out.writeByte(flags)
        out.writeRSString(username)
        out.writeRSString(displayName)
    }.decoder {
        val flags = readUByte()
        AddIgnore(readRSString(), readRSString(), temporary = (flags and 0x2) != 0, editDisplayName = (flags and 0x1) != 0)
    }

    serverProt<FriendsChatChannel>(opcode = 127, size = ProtSize.VarShort) { out ->
        if (clear) {
            return@serverProt
        }
        out.writeRSString(ownerDisplayName ?: "")
        val nameChanged = ownerUsername != null && ownerUsername != ownerDisplayName
        out.writeBoolean(nameChanged)
        if (nameChanged) {
            out.writeRSString(ownerUsername ?: ownerDisplayName ?: "")
        }
        out.writeLong(chatName ?: "")
        out.writeByte(minRankCanKick)
        if (players == null) {
            out.writeByte(255)
        } else {
            out.writeByte(players.size)
            for (player in players) {
                out.writeRSString(player.displayName)
                val changed = player.username != player.displayName
                out.writeBoolean(changed)
                if (changed) {
                    out.writeRSString(player.username)
                }
                out.writeShort(player.worldId)
                out.writeByte(player.rank)
                out.writeRSString(player.worldName)
            }
        }
    }.decoder {
        if (exhausted()) return@decoder FriendsChatChannel(clear = true)
        val ownerDisplayName = readRSString()
        val ownerUsername = if (readBoolean()) readRSString() else ownerDisplayName
        val chatName = readLong().toRSString()
        val minRankCanKick = readByte().toInt()
        val count = readUByte()
        val players = if (count == 255) null else Array(count) {
            val displayName = readRSString()
            val username = if (readBoolean()) readRSString() else displayName
            FriendsChatPlayer(displayName, username, worldId = readUShort(), rank = readByte().toInt(), worldName = readRSString())
        }
        FriendsChatChannel(false, ownerDisplayName, ownerUsername, chatName, minRankCanKick, players)
    }

    serverProt<UpdateFriendchatChannelSingleUser>(opcode = 19, size = ProtSize.VarByte) { out ->
        out.writeRSString(displayName)
        val changedName = username != displayName
        out.writeBoolean(changedName)
        if (changedName) {
            out.writeRSString(username)
        }
        out.writeShort(worldId)
        out.writeByte(rank)
        if (rank != -128) {
            out.writeRSString(worldName ?: "")
        }
    }.decoder {
        val displayName = readRSString()
        val username = if (readBoolean()) readRSString() else displayName
        val worldId = readUShort()
        val rank = readByte().toInt()
        UpdateFriendchatChannelSingleUser(displayName, username, worldId, rank, if (rank != -128) readRSString() else null)
    }

    serverProt<UpdateIgnoreList>(opcode = 97, size = ProtSize.VarShort) { out ->
        out.writeByte(ignores.size)
        for (ignore in ignores) {
            out.writeRSString(ignore.username)
            out.writeRSString(ignore.displayName)
        }
    }.decoder {
        UpdateIgnoreList(Array(readUByte()) { IgnoreEntry(readRSString(), readRSString()) })
    }

    serverProt<ChatFilterSettings>(opcode = 30, size = 2) { out ->
        out.writeByteInverse(tradeFilter)
        out.writeByteAdd(publicFilter)
    }.decoder {
        ChatFilterSettings(tradeFilter = readByteInverse() and 0xFF, publicFilter = readByteAdd() and 0xFF)
    }

    serverProt<ChatFilterSettingsPrivateChat>(opcode = 67, size = 1) { out ->
        out.writeByte(privateFilter)
    }.decoder {
        ChatFilterSettingsPrivateChat(readUByte())
    }

    serverProt<Pong>(opcode = 29)
}
