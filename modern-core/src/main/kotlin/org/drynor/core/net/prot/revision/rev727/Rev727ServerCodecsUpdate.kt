package org.drynor.core.net.prot.revision.rev727

import io.ktor.utils.io.*
import kotlinx.io.Source
import kotlinx.io.readByteArray
import org.drynor.core.net.prot.*
import org.drynor.core.net.prot.dto.ClanChannelChatter
import org.drynor.core.net.prot.dto.ClanSettingsMember
import org.drynor.core.net.prot.dto.ClanVarSetting
import org.drynor.core.net.prot.dto.GrandExchangeOffer
import org.drynor.core.net.prot.dto.InvSlotUpdate
import org.drynor.core.net.prot.dto.InvTooltip
import org.drynor.core.net.prot.packet.*
import world.gregs.voidps.buffer.*
import world.gregs.voidps.cache.secure.CRC

private const val INV_FLAG_NEGATIVE_KEY = 0x1
private const val INV_FLAG_TOOLTIPS = 0x2
private const val INV_FLAG_SPARSE_TOOLTIPS = 0x4
private const val MAX_TOOLTIP_SLOT = 0x7FFF shr 2

private fun smartSize(value: Int) = if (value < 128) 1 else 2

private val InvSlotUpdate.effectiveTooltip: InvTooltip
    get() = if (itemId == -1) InvTooltip.Clear else tooltip

private fun Source.readClanSettingsName(attr: Int): String {
    val hash = if ((attr and 0x1) != 0) readLong() else 0L
    return if ((attr and 0x2) != 0) readRSString() else hash.toRSString()
}

private fun Source.readClanVarSetting(): ClanVarSetting {
    val header = readInt()
    val key = header and 0x3FFFFFFF
    return when (val type = header ushr 30) {
        0 -> ClanVarSetting(key, intValue = readInt())
        1 -> ClanVarSetting(key, longValue = readLong())
        2 -> ClanVarSetting(key, stringValue = readRSString())
        else -> error("Unknown clan var type $type")
    }
}

internal fun Codec.registerRev727UpdatePackets() {
    serverProt<PlayerUpdate>(opcode = 43, size = ProtSize.VarShort) { out ->
        out.writeBytes(data)
    }.decoder {
        PlayerUpdate(readByteArray())
    }

    serverProt<NpcUpdateLarge>(opcode = 47, size = ProtSize.VarShort) { out ->
        out.writeBytes(data)
    }.decoder {
        NpcUpdateLarge(readByteArray())
    }

    serverProt<NpcUpdate>(opcode = 6, size = ProtSize.VarShort) { out ->
        out.writeBytes(data)
    }.decoder {
        NpcUpdate(readByteArray())
    }

    serverProt<UpdateInvPartial>(opcode = 4, size = ProtSize.VarShort) { out ->
        val hasTooltips = updates.any { it.effectiveTooltip != InvTooltip.Clear }
        out.writeShort(containerId)
        out.writeByte((if (negativeKey) INV_FLAG_NEGATIVE_KEY else 0) or (if (hasTooltips) INV_FLAG_TOOLTIPS else 0))
        for (update in updates) {
            val tooltip = update.effectiveTooltip
            if (hasTooltips) {
                require(update.slot <= MAX_TOOLTIP_SLOT) { "slot ${update.slot} too large for tooltip mode" }
                val mode = when (tooltip) {
                    InvTooltip.Keep -> 0
                    InvTooltip.Clear -> 1
                    is InvTooltip.Set -> 2
                }
                out.writeSmart((update.slot shl 2) or mode)
            } else {
                out.writeSmart(update.slot)
            }
            if (update.itemId == -1) {
                out.writeShort(0)
            } else {
                out.writeShort(update.itemId + 1)
                if (update.amount >= 255) {
                    out.writeByte(255)
                    out.writeInt(update.amount)
                } else {
                    out.writeByte(update.amount)
                }
            }
            if (hasTooltips && tooltip is InvTooltip.Set) {
                out.writeRSString(tooltip.text)
            }
        }
    }.decoder {
        val containerId = readUShort()
        val flags = readUByte()
        val hasTooltips = (flags and INV_FLAG_TOOLTIPS) != 0
        val updates = mutableListOf<InvSlotUpdate>()
        while (!exhausted()) {
            val header = readSmart()
            val itemId = readUShort() - 1
            var amount = 0
            if (itemId != -1) {
                amount = readUByte()
                if (amount == 255) amount = readInt()
            }
            val tooltip = when (if (hasTooltips) header and 0x3 else 1) {
                1 -> InvTooltip.Clear
                2 -> InvTooltip.Set(readRSString())
                else -> InvTooltip.Keep
            }
            updates += InvSlotUpdate(if (hasTooltips) header ushr 2 else header, itemId, amount, tooltip)
        }
        UpdateInvPartial(containerId, (flags and INV_FLAG_NEGATIVE_KEY) != 0, updates.toTypedArray())
    }

    serverProt<UpdateInvFull>(opcode = 5, size = ProtSize.VarShort) { out ->
        val size = itemIds.size
        val tooltipSlots = tooltips?.let { texts -> (0 until size).filter { texts[it] != null && itemIds[it] != -1 } }.orEmpty()
        val bitmaskCost = (size + 7) shr 3
        val sparseCost = smartSize(tooltipSlots.size) + tooltipSlots.sumOf { smartSize(it) }
        val tooltipFlag = when {
            tooltipSlots.isEmpty() -> 0
            bitmaskCost <= sparseCost -> INV_FLAG_TOOLTIPS
            else -> INV_FLAG_SPARSE_TOOLTIPS
        }
        out.writeShort(containerId)
        out.writeByte((if (negativeKey) INV_FLAG_NEGATIVE_KEY else 0) or tooltipFlag)
        out.writeShort(size)
        for (i in itemIds.indices) {
            val amount = amounts[i]
            if (amount >= 255) {
                out.writeByteSubtract(255)
                out.writeIntInverseMiddle(amount)
            } else {
                out.writeByteSubtract(amount)
            }
            out.writeShortAddLittle(itemIds[i] + 1)
        }
        val texts = tooltips ?: return@serverProt
        when (tooltipFlag) {
            INV_FLAG_TOOLTIPS -> {
                val mask = ByteArray(bitmaskCost)
                for (slot in tooltipSlots) {
                    mask[slot shr 3] = (mask[slot shr 3].toInt() or (1 shl (slot and 7))).toByte()
                }
                out.writeBytes(mask)
                for (slot in tooltipSlots) {
                    out.writeRSString(texts[slot]!!)
                }
            }
            INV_FLAG_SPARSE_TOOLTIPS -> {
                out.writeSmart(tooltipSlots.size)
                for (slot in tooltipSlots) {
                    out.writeSmart(slot)
                    out.writeRSString(texts[slot]!!)
                }
            }
        }
    }.decoder {
        val containerId = readUShort()
        val flags = readUByte()
        val size = readUShort()
        val itemIds = IntArray(size)
        val amounts = IntArray(size)
        for (i in 0 until size) {
            var amount = readByteSubtract() and 0xFF
            if (amount == 255) amount = readIntInverseMiddle()
            amounts[i] = amount
            itemIds[i] = readUShortAddLittle() - 1
        }
        val tooltips = when {
            (flags and INV_FLAG_TOOLTIPS) != 0 -> {
                val mask = readByteArray((size + 7) shr 3)
                Array(size) { slot -> if ((mask[slot shr 3].toInt() shr (slot and 7) and 0x1) != 0) readRSString() else null }
            }
            (flags and INV_FLAG_SPARSE_TOOLTIPS) != 0 -> arrayOfNulls<String>(size).also { texts ->
                repeat(readSmart()) { texts[readSmart()] = readRSString() }
            }
            else -> null
        }
        UpdateInvFull(containerId, (flags and INV_FLAG_NEGATIVE_KEY) != 0, itemIds, amounts, tooltips)
    }

    serverProt<UpdateInvStopTransmit>(opcode = 42, size = 3) { out ->
        out.writeByteInverse(if (negativeKey) 1 else 0)
        out.writeShortLittle(containerId)
    }.decoder {
        val negativeKey = (readByteInverse() and INV_FLAG_NEGATIVE_KEY) != 0
        UpdateInvStopTransmit(readUShortLittle(), negativeKey)
    }

    serverProt<UpdateStat>(opcode = 140, size = 6) { out ->
        out.writeInt(xp)
        out.writeByte(skillId)
        out.writeByte(level)
    }.decoder {
        UpdateStat(xp = readInt(), skillId = readUByte(), level = readUByte())
    }

    serverProt<UpdateGESlot>(opcode = 57, size = 20) { out ->
        out.writeByte(slot)
        val offer = offer
        if (offer == null) {
            out.writeByte(0)
            out.writeBytes(ByteArray(18))
        } else {
            out.writeByte(offer.progress)
            out.writeShort(offer.itemId)
            out.writeInt(offer.price)
            out.writeInt(offer.amount)
            out.writeInt(offer.currentAmount)
            out.writeInt(offer.totalPrice)
        }
    }.decoder {
        val slot = readUByte()
        val progress = readUByte()
        if (progress == 0) {
            skip(18)
            return@decoder UpdateGESlot(slot, null)
        }
        UpdateGESlot(slot, GrandExchangeOffer(progress, readUShort(), readInt(), readInt(), readInt(), readInt()))
    }

    serverProt<UpdateUid192>(opcode = 90, size = 28) { out ->
        out.writeBytes(data)
        out.writeInt(CRC.calculate(data))
    }.decoder {
        val data = readByteArray(24)
        skip(4)
        UpdateUid192(data)
    }

    serverProt<ClanChannelDelta>(opcode = 28, size = ProtSize.VarShort) { out ->
        out.writeBoolean(main)
        out.writeBytes(data)
    }.decoder {
        ClanChannelDelta(readBoolean(), readByteArray())
    }

    serverProt<ClanChannelFull>(opcode = 49, size = ProtSize.VarShort) { out ->
        out.writeBoolean(main)
        if (clanName != null) {
            val chatterList = chatters ?: emptyArray()
            out.writeByte(2) // nameTypeFlag: names as string
            out.writeLong(4062231702422979939L) // fixed pointer UID
            out.writeLong(updateNum)
            out.writeRSString(clanName)
            out.writeByte(0) // unused
            out.writeByte(kickRank)
            out.writeByte(talkRank)
            out.writeShort(chatterList.size)
            for (chatter in chatterList) {
                out.writeRSString(chatter.displayName)
                out.writeByte(chatter.rank)
                out.writeShort(chatter.worldId)
            }
        }
    }.decoder {
        val main = readBoolean()
        if (exhausted()) return@decoder ClanChannelFull(main)
        val nameTypeFlag = readUByte()
        skip(8)
        val updateNum = readLong()
        val clanName = readRSString()
        skip(1)
        val kickRank = readByte().toInt()
        val talkRank = readByte().toInt()
        val chatters = Array(readUShort()) {
            val hash = if ((nameTypeFlag and 0x1) != 0) readLong() else 0L
            val displayName = if ((nameTypeFlag and 0x2) != 0) readRSString() else hash.toRSString()
            ClanChannelChatter(displayName, rank = readByte().toInt(), worldId = readUShort())
        }
        ClanChannelFull(main, clanName, updateNum, kickRank, talkRank, chatters)
    }

    serverProt<ClanSettingsDelta>(opcode = 46, size = ProtSize.VarShort) { out ->
        out.writeBoolean(main)
        out.writeBytes(data)
    }.decoder {
        ClanSettingsDelta(readBoolean(), readByteArray())
    }

    serverProt<ClanSettingsFull>(opcode = 137, size = ProtSize.VarShort) { out ->
        out.writeBoolean(main)
        if (clanName != null) {
            val memberList = members ?: emptyArray()
            val bannedList = bannedUsers ?: emptyArray()
            val settingsList = settings ?: emptyArray()

            out.writeByte(3) // version
            out.writeByte(2) // attr: names as string
            out.writeInt(updateCount)
            out.writeInt(0) // creationDay
            out.writeShort(memberList.size)
            out.writeByte(bannedList.size)
            out.writeRSString(clanName)
            out.writeByte(if (allowGuests) 1 else 0)
            out.writeByte(talkRank)
            out.writeByte(kickRank)
            out.writeByte(0) // lootshareRank
            out.writeByte(0) // coinShare

            for (member in memberList) {
                out.writeRSString(member.displayName)
                out.writeByte(member.rank)
                out.writeInt(member.flags)
            }

            for (banned in bannedList) {
                out.writeRSString(banned)
            }

            out.writeShort(settingsList.size)
            for (setting in settingsList) {
                when {
                    setting.stringValue != null -> {
                        out.writeInt((setting.key and 0x3FFFFFFF) or (2 shl 30))
                        out.writeRSString(setting.stringValue)
                    }
                    setting.longValue != null -> {
                        out.writeInt((setting.key and 0x3FFFFFFF) or (1 shl 30))
                        out.writeLong(setting.longValue)
                    }
                    else -> {
                        out.writeInt(setting.key and 0x3FFFFFFF)
                        out.writeInt(setting.intValue ?: 0)
                    }
                }
            }
        }
    }.decoder {
        val main = readBoolean()
        if (exhausted()) return@decoder ClanSettingsFull(main)
        val version = readUByte()
        require(version in 1..5) { "Unsupported clan settings version $version" }
        val attr = readUByte()
        val updateCount = readInt()
        skip(4)
        val memberCount = readUShort()
        val bannedCount = readUByte()
        val clanName = readRSString()
        if (version >= 4) skip(4)
        val allowGuests = readUByte() == 1
        val talkRank = readByte().toInt()
        val kickRank = readByte().toInt()
        skip(2)
        val members = Array(memberCount) {
            val displayName = readClanSettingsName(attr)
            val rank = readByte().toInt()
            val flags = if (version >= 2) readInt() else 0
            if (version >= 5) skip(2)
            ClanSettingsMember(displayName, rank, flags)
        }
        val bannedUsers = Array(bannedCount) { readClanSettingsName(attr) }
        val settings = if (version >= 3) Array(readUShort()) { readClanVarSetting() } else emptyArray()
        ClanSettingsFull(main, clanName, updateCount, allowGuests, talkRank, kickRank, members, bannedUsers, settings)
    }

    serverProt<ClanSettingsFullRaw>(opcode = 137, size = ProtSize.VarShort) { out ->
        out.writeByte(if (main) 1 else 0)
        if (data != null) {
            out.writeBytes(data)
        }
    }

    serverProt<RunEnergy>(opcode = 64, size = 1) { out ->
        out.writeByte(energy)
    }.decoder {
        RunEnergy(readUByte())
    }

    serverProt<PlayerWeight>(opcode = 14, size = 2) { out ->
        out.writeShort(weight)
    }.decoder {
        PlayerWeight(readShort().toInt())
    }

    serverProt<PlayerOption>(opcode = 111, size = ProtSize.VarByte) { out ->
        out.writeRSString(option)
        out.writeByteAdd(slot)
        out.writeShortAddLittle(cursor)
        out.writeByteInverse(if (top) 1 else 0)
    }.decoder {
        val option = readRSString()
        val slot = readByteAdd() and 0xFF
        val cursor = readUShortAddLittle().let { if (it == 65535) -1 else it }
        PlayerOption(option, slot, top = readBooleanInverse(), cursor = cursor)
    }

    serverProt<ReduceAttackPriority>(opcode = 61, size = 1) { out ->
        out.writeBooleanAdd(enabled)
    }.decoder {
        ReduceAttackPriority(readBooleanAdd())
    }

    serverProt<SetTarget>(opcode = 129, size = 2) { out ->
        out.writeShortLittle(target)
    }.decoder {
        SetTarget(readShortLittle())
    }

    serverProt<UpdateDob>(opcode = 157, size = 4) { out ->
        out.writeMedium(dob)
        out.writeBoolean(quickChatOnly)
    }.decoder {
        UpdateDob(readMedium(), readBoolean())
    }
}
