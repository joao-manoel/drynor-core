package org.darkan.core.net.prot.revision.rev727

import io.ktor.utils.io.*
import io.ktor.utils.io.core.remaining
import kotlinx.io.Buffer
import kotlinx.io.Source
import kotlinx.io.readByteArray
import org.darkan.core.clientwatch.ReflectionCheck
import org.darkan.core.clientwatch.ReflectionCheckType
import org.darkan.core.model.RegionSize
import org.darkan.core.net.prot.*
import org.darkan.core.net.prot.dto.CheckEmailReplyOpcode
import org.darkan.core.net.prot.dto.CreateAccountReplyOpcode
import org.darkan.core.net.prot.dto.InstanceLoadType
import org.darkan.core.net.prot.dto.WorldListEntry
import org.darkan.core.net.prot.dto.WorldListSnapshot
import org.darkan.core.net.prot.packet.*
import world.gregs.voidps.buffer.*
import world.gregs.voidps.type.Tile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.ObjectInputFilter
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import kotlin.io.use

/**
 * Port the client assumes for a world unless the world list marks it as custom.
 *
 * A world on this port omits its port from the list entry; anything else sets the custom-port
 * flag and appends the real port.
 */
private const val DEFAULT_WORLD_PORT = 43595

private const val WORLD_FLAG_CUSTOM_CACHE = 0x20

private const val WORLD_FLAG_CUSTOM_PORT = 0x40000000

private const val PLAYER_SLOTS = 2048

private const val LOGIN_PLAYER_BLOCK_BYTES = (30 + 18 * (PLAYER_SLOTS - 2) + 7) / 8

private val reflectionParamFilter = ObjectInputFilter.Config.createFilter(
    "java.lang.String;java.lang.Number;java.lang.Integer;java.lang.Long;java.lang.Short;java.lang.Byte;" +
        "java.lang.Float;java.lang.Double;java.lang.Boolean;java.lang.Character;maxdepth=4;!*"
)

internal fun Codec.registerRev727MiscPackets() {
    // Reflection/clientwatch
    serverProt<ReflectionRequest>(opcode = 98, size = ProtSize.VarShort) { out ->
        out.writeByte(checks.size)
        out.writeInt(id)
        for (check in checks) {
            out.writeByte(check.type.ordinal)
            when (check.type) {
                ReflectionCheckType.GET_INT, ReflectionCheckType.SET_INT, ReflectionCheckType.GET_FIELD_MODIFIERS -> {
                    out.writeRSString(check.className)
                    out.writeRSString(check.methodName)
                    // The client reads this int unconditionally for SET_INT, so it
                    // must always be written or the rest of the packet desyncs.
                    if (check.type == ReflectionCheckType.SET_INT)
                        out.writeInt(check.fieldValue ?: 0)
                }
                ReflectionCheckType.GET_METHOD_RETURN_VALUE, ReflectionCheckType.GET_METHOD_MODIFIERS -> {
                    out.writeRSString(check.className)
                    out.writeRSString(check.methodName)
                    // The client reads exactly this many argument blobs back, so the
                    // two arrays must agree in length.
                    require(check.paramValues.size == check.paramTypes.size || check.type != ReflectionCheckType.GET_METHOD_RETURN_VALUE) {
                        "ReflectionCheck ${check.className}#${check.methodName}: paramValues (${check.paramValues.size}) must match paramTypes (${check.paramTypes.size})"
                    }
                    out.writeByte(check.paramTypes.size)
                    check.paramTypes.forEach { out.writeRSString(it) }
                    out.writeRSString(check.returnType ?: "void")
                    if (check.type == ReflectionCheckType.GET_METHOD_RETURN_VALUE) {
                        check.paramValues.forEach { param ->
                            ByteArrayOutputStream().use { bos ->
                                ObjectOutputStream(bos).use { oos ->
                                    oos.writeObject(param)
                                    val data = bos.toByteArray()
                                    out.writeInt(data.size)
                                    out.writeBytes(data)
                                }
                            }
                        }
                    }
                }
            }
        }
    }.decoder {
        val count = readUByte()
        val id = readInt()
        ReflectionRequest(id, Array(count) { readReflectionCheck() })
    }

    // Region/map building
    serverProt<BuildRegion>(opcode = 85, size = ProtSize.VarShort) { out ->
        if (localPlayerPid != null && localPlayerBaseTileHash != null && otherPlayerRegionIds != null) {
            out.writeLoginPlayerBlock(localPlayerPid, localPlayerBaseTileHash, otherPlayerRegionIds)
        }
        out.writeByte(mapSize.ordinal)
        out.writeShort(chunkX)
        out.writeShort(chunkY)
        out.writeBoolean(forceMapRefresh)
        xteas.forEach { keys ->
            keys.forEach { key -> out.writeInt(key) }
        }
    }.decoder {
        val bytes = readByteArray()
        bytes.source().readStandardRegion(null)
            ?: bytes.afterLoginPlayerBlock("BuildRegion")?.let { (block, body) -> body.readStandardRegion(block) }
            ?: error("BuildRegion payload of ${bytes.size} bytes matches neither the in-game nor the login layout")
    }

    serverProt<BuildInstancedRegion>(opcode = 51, size = ProtSize.VarShort) { out ->
        if (localPlayerPid != null && localPlayerBaseTileHash != null && otherPlayerRegionIds != null) {
            out.writeLoginPlayerBlock(localPlayerPid, localPlayerBaseTileHash, otherPlayerRegionIds)
        }
        out.writeBooleanInverse(forceMapRefresh)
        out.writeByteSubtract(instanceLoadType.index)
        out.writeByteAdd(mapSize.ordinal)
        out.writeShortAdd(chunkY)
        out.writeShort(chunkX)
        out.bitAccess {
            chunks.forEach {
                if (it == BuildInstancedRegion.ABSENT_CHUNK) writeBit(false)
                else {
                    writeBit(true)
                    writeBits(26, it)
                }
            }
        }
        xteas.forEach { keys ->
            keys.forEach { key -> out.writeInt(key) }
        }
    }.decoder {
        val bytes = readByteArray()
        bytes.source().readInstancedRegion(null)
            ?: bytes.afterLoginPlayerBlock("BuildInstancedRegion")?.let { (block, body) -> body.readInstancedRegion(block) }
            ?: error("BuildInstancedRegion payload of ${bytes.size} bytes matches neither the in-game nor the login layout")
    }

    // Camera protocols
    serverProt<CamLookAt>(opcode = 24, size = 6) { out ->
        out.writeByte(viewLocalX)
        out.writeShortAdd(viewZ shr 2)
        out.writeByteAdd(viewLocalY)
        out.writeByteInverse(speed1)
        out.writeByteSubtract(speed2)
    }.decoder {
        val viewLocalX = readUByte()
        val viewZ = readUShortAdd() shl 2
        val viewLocalY = readByteAdd() and 0xff
        val speed1 = readByteInverse() and 0xff
        val speed2 = readByteSubtract() and 0xff
        CamLookAt(viewLocalX, viewLocalY, viewZ, speed1, speed2)
    }

    serverProt<CamMoveTo>(opcode = 39, size = 6) { out ->
        out.writeByte(moveLocalY)
        out.writeByte(moveLocalX)
        out.writeByteSubtract(speed1)
        out.writeShortLittle(moveZ shr 2)
        out.writeByteAdd(speed2)
    }.decoder {
        val moveLocalY = readUByte()
        val moveLocalX = readUByte()
        val speed1 = readByteSubtract() and 0xff
        val moveZ = readUShortLittle() shl 2
        val speed2 = readByteAdd() and 0xff
        CamMoveTo(moveLocalX, moveLocalY, moveZ, speed1, speed2)
    }

    serverProt<CamRemoveRoof>(opcode = 40, size = 4) { out ->
        out.writeIntLittle(tile.id)
    }.decoder {
        CamRemoveRoof(Tile(readIntLittle()))
    }

    serverProt<CamResetHard>(opcode = 66)
    serverProt<CamResetSmooth>(opcode = 89)

    serverProt<CamShake>(opcode = 71, size = 6) { out ->
        out.writeByteSubtract(v2)
        out.writeShort(v4)
        out.writeByteInverse(slotId)
        out.writeByteInverse(v1)
        out.writeByteSubtract(v3)
    }.decoder {
        val v2 = readByteSubtract() and 0xff
        val v4 = readUShort()
        val slotId = readByteInverse() and 0xff
        val v1 = readByteInverse() and 0xff
        val v3 = readByteSubtract() and 0xff
        CamShake(slotId, v1, v2, v3, v4)
    }

    serverProt<CamForceAngle>(opcode = 118, size = 4) { out ->
        out.writeShortAdd(angleY)
        out.writeShortAddLittle(angleX)
    }.decoder {
        val angleY = readUShortAdd()
        CamForceAngle(readUShortAddLittle(), angleY)
    }

    // Login and system protocols
    serverProt<LogoutLobby>(opcode = 9)

    serverProt<LogoutFull>(opcode = 62)

    serverProt<OpenUrl>(opcode = 16, size = ProtSize.VarShort) { out ->
        val hasDisplayUrl = displayUrl != null
        out.writeBoolean(hasDisplayUrl)
        out.writeRSString(url)
        if (hasDisplayUrl) {
            out.writeRSString(displayUrl?.ifEmpty { url } ?: url)
        }
    }.decoder {
        val hasDisplayUrl = readBoolean()
        val url = readRSString()
        OpenUrl(url, if (hasDisplayUrl) readRSString() else null)
    }

    // OPEN_SOCIAL_NETWORK_URL: raw, un-terminated url; the packet length is the string length.
    serverProt<OpenSocialNetworkUrl>(opcode = 86, size = ProtSize.VarShort) { out ->
        out.writeBytes(Cp1252.encode(url))
    }.decoder {
        OpenSocialNetworkUrl(readRawString())
    }

    // WINDOW_CALL_WITH_ARGS: raw, un-terminated argument string; length comes from the packet length.
    serverProt<WindowCallWithArgs>(opcode = 156, size = ProtSize.VarShort) { out ->
        out.writeBytes(Cp1252.encode(args))
    }.decoder {
        WindowCallWithArgs(readRawString())
    }

    serverProt<DisplayNameChangeState>(opcode = 52, size = 1) { out ->
        out.writeByte(state)
    }.decoder {
        DisplayNameChangeState(readByte().toInt())
    }

    serverProt<WorldListPacket>(opcode = 103, size = ProtSize.VarShort) { out ->
        val entries = worlds.entries
        // Byte 0 is the client's "decode now" flag - when 0 the client only buffers
        // the chunk. Byte 1 is a decode-mode constant: the client bails out of
        // WorldDescriptor.decodeWorldList unless it is exactly 2.
        out.writeByte(if (refreshOnClient) 1 else 0)
        out.writeByte(2)
        if (!refreshOnlyPlayerCounts) {
            out.writeByte(1)
            out.writeSmart(entries.size)
            for (world in entries) {
                out.writeSmart(world.countryId)
                out.writeJagString(world.activity)
            }
            out.writeSmart(0)
            out.writeSmart(worlds.maxWorlds + 1)
            out.writeSmart(entries.size)
            for (world in entries) {
                out.writeSmart(world.number)
                out.writeByte(world.index)
                var flags = 0
                if (world.members) flags = flags or 0x1
                if (world.quickChat) flags = flags or 0x2
                if (world.botsAllowed) flags = flags or 0x4
                if (world.lootShare) flags = flags or 0x8
                if (world.highlighted) flags = flags or 0x10
                if (world.customCache) flags = flags or WORLD_FLAG_CUSTOM_CACHE
                val customPort = world.port != DEFAULT_WORLD_PORT
                if (customPort) flags = flags or 0x40000000

                out.writeInt(flags)
                out.writeJagString("")
                out.writeJagString(world.address)
                if (customPort) out.writeInt(world.port)
            }
            out.writeInt(worlds.revision)
        } else
            out.writeByte(0)
        for (world in entries) {
            out.writeSmart(world.number)
            out.writeShort(world.playersOnline)
        }
    }.decoder {
        val refreshOnClient = readUByte() == 1
        readUByte()
        if (readUByte() == 1) {
            val locations = List(readSmart()) { readSmart() to readJagString() }
            val firstWorld = readSmart()
            val maxWorlds = readSmart() - 1
            val worlds = List(readSmart()) { position -> readWorldListEntry(firstWorld, locations[position]) }
            val revision = readInt()
            val playerCounts = List(worlds.size) { readSmart() to readWorldPlayerCount() }.toMap()
            val entries = worlds.map { it.copy(playersOnline = playerCounts[it.number] ?: -1) }
            WorldListPacket(WorldListSnapshot(entries, maxWorlds, revision), refreshOnClient, refreshOnlyPlayerCounts = false)
        } else {
            val entries = buildList {
                while (!exhausted()) add(playerCountOnlyEntry(readSmart(), readWorldPlayerCount()))
            }
            WorldListPacket(WorldListSnapshot(entries, maxWorlds = 0, revision = 0), refreshOnClient, refreshOnlyPlayerCounts = true)
        }
    }

    serverProt<IdentifyHostName>(opcode = 147, size = 4) { out ->
        out.writeInt(ip)
    }.decoder {
        IdentifyHostName(readInt())
    }

    serverProt<UpdateRebootTimer>(opcode = 102, size = 2) { out ->
        out.writeShort(seconds)
    }.decoder {
        UpdateRebootTimer(readUShort())
    }

    serverProt<RequestFps>(opcode = 159, size = 8) { out ->
        out.writeInt(key)
        out.writeInt(flags)
    }.decoder {
        RequestFps(readInt(), readInt())
    }

    serverProt<UpdateSitesettingsCookie>(opcode = 91, size = ProtSize.VarByte) { out ->
        // Client no-ops this packet on non-applet builds. Send empty payload by default.
    }.decoder {
        readByteArray()
        UpdateSitesettingsCookie()
    }

    // Account protocols
    serverProt<CreateCheckEmailReply>(opcode = 1, size = 1) { out ->
        out.writeByte(code.value)
    }.decoder {
        val value = readUByte()
        CreateCheckEmailReply(CheckEmailReplyOpcode.entries.firstOrNull { it.value == value } ?: error("Unknown CreateCheckEmailReply code $value"))
    }

    serverProt<CreateAccountReply>(opcode = 87, size = 1) { out ->
        out.writeByte(code.value)
    }.decoder {
        val value = readUByte()
        CreateAccountReply(CreateAccountReplyOpcode.entries.firstOrNull { it.value == value } ?: error("Unknown CreateAccountReply code $value"))
    }

    // Miscellaneous protocols
    serverProt<ProcessDevConsoleCommand>(opcode = 2, size = ProtSize.VarByte) { out ->
        out.writeRSString(command)
    }.decoder {
        ProcessDevConsoleCommand(readRSString())
    }

    serverProt<RunScript>(opcode = 99, size = ProtSize.VarShort) { out ->
        val signature = buildString {
            for (i in params.lastIndex downTo 0) {
                when (params[i]) {
                    is String -> append('s')
                    is Int -> append('i')
                    else -> error("RunScript params must be String or Int, got: ${params[i]::class.simpleName}")
                }
            }
        }
        out.writeRSString(signature)
        for (param in params) {
            when (param) {
                is String -> out.writeRSString(param)
                is Int -> out.writeInt(param)
            }
        }
        out.writeInt(scriptId)
    }.decoder {
        val signature = readRSString()
        val params = signature.reversed().map<Any> { if (it == 's') readRSString() else readInt() }
        RunScript(readInt(), params.toTypedArray())
    }

    serverProt<SetCursor>(opcode = 153, size = ProtSize.VarByte) { out ->
        if (uptext != null) {
            out.writeRSString(uptext)
            out.writeShort(if (cursorId == -1) 65535 else cursorId)
        } else if (cursorId != -1) {
            out.writeShort(cursorId)
        }
    }.decoder {
        val size = remaining
        val uptext = if (size > 2) readRSString() else null
        val cursorId = if (size > 0) readUShort().takeIf { it != 65535 } ?: -1 else -1
        SetCursor(cursorId, uptext)
    }

    serverProt<Cutscene>(opcode = 110, size = ProtSize.VarShort) { out ->
        out.writeShort(cutsceneId)
        out.writeShort(mapXteas.size)
        for (keys in mapXteas) {
            for (key in keys) {
                out.writeInt(key)
            }
        }
        out.writeByte(data.size)
        out.writeBytes(data)
    }.decoder {
        val cutsceneId = readUShort()
        val mapXteas = Array(readUShort()) { IntArray(4) { readInt() } }
        Cutscene(cutsceneId, mapXteas, readByteArray(readUByte()))
    }

    serverProt<TriggerOnDialogAbort>(opcode = 146)

    serverProt<KeepAlive>(opcode = 29)

    serverProt<LoyaltyUpdate>(opcode = 32, size = 5) { out ->
        out.writeInt(balance)
        out.writeBooleanInverse(enabled)
    }.decoder {
        LoyaltyUpdate(readInt(), readBooleanInverse())
    }

    serverProt<JCoinsUpdate>(opcode = 75, size = 4) { out ->
        out.writeIntLittle(amount)
    }.decoder {
        JCoinsUpdate(readIntLittle())
    }

    serverProt<ApplyDebug>(opcode = 76, size = 2) { out ->
        out.writeShort(opcode)
    }.decoder {
        ApplyDebug(readUShort())
    }

    serverProt<QuickHopWorlds>(opcode = 58, size = ProtSize.VarByte) { out ->
        out.writeShort(port)
        out.writeRSString(host)
        out.writeBoolean(reconnecting)
    }.decoder {
        val port = readUShort()
        val host = readRSString()
        QuickHopWorlds(host, port, readBoolean())
    }

    serverProt<DebugServerTriggers>(opcode = 73, size = ProtSize.VarByte) { out ->
        out.writeShort(interfaceId)
        out.writeShort(fromComponent)
        out.writeShort(toComponent)
        for (trigger in triggers) {
            out.writeMedium(trigger)
        }
    }.decoder {
        val interfaceId = readUShort()
        val fromComponent = readUShort()
        val toComponent = readUShort()
        DebugServerTriggers(interfaceId, fromComponent, toComponent, IntArray(toComponent - fromComponent) { readUMedium() })
    }

    serverProt<ShowFaceHere>(opcode = 21, size = 1) { out ->
        out.writeBoolean(enabled)
    }.decoder {
        ShowFaceHere(readBoolean())
    }

    serverProt<MinimapFlag>(opcode = 70, size = 2) { out ->
        if (x < 0 || y < 0) {
            out.writeByteSubtract(255)
            out.writeByteSubtract(255)
        } else {
            out.writeByteSubtract(x)
            out.writeByteSubtract(y)
        }
    }.decoder {
        val x = readByteSubtract() and 0xff
        val y = readByteSubtract() and 0xff
        if (x == 255) MinimapFlag(-1, -1) else MinimapFlag(x, y)
    }

    serverProt<HintArrow>(opcode = 79, size = 14) { out ->
        val flags = ((iconIndex and 0x7) shl 5) or (targetType and 0x1F)
        out.writeByte(flags)
        if (targetType == 0) {
            out.writeBytes(ByteArray(13))
            return@serverProt
        }
        out.writeByte(arrowGraphic)
        if (targetType == 1 || targetType == 10) {
            out.writeShort(targetIndex)
            out.writeShort(denominator)
            out.writeInt(0) // unused 4 bytes
        } else {
            out.writeByte(plane)
            out.writeShort(x)
            out.writeShort(y)
            out.writeByte(height shr 2)
            out.writeShort(distance)
        }
        out.writeInt(modelId)
    }.decoder {
        val flags = readUByte()
        val iconIndex = flags shr 5
        val targetType = flags and 0x1F
        if (targetType == 0) {
            skip(13)
            return@decoder HintArrow(iconIndex, targetType)
        }
        val arrowGraphic = readUByte()
        if (targetType == 1 || targetType == 10) {
            val targetIndex = readUShort()
            val denominator = readUShort()
            skip(4)
            HintArrow(iconIndex, targetType, arrowGraphic, targetIndex = targetIndex, denominator = denominator, modelId = readInt())
        } else {
            val plane = readUByte()
            val x = readUShort()
            val y = readUShort()
            val height = readUByte() shl 2
            val distance = readUShort()
            HintArrow(iconIndex, targetType, arrowGraphic, plane, x, y, height, distance, modelId = readInt())
        }
    }

    serverProt<HintTrail>(opcode = 149, size = ProtSize.VarShort) { out ->
        out.writeByte(index)
        out.writeBigSmart(modelId)
        out.writeBytes(data)
    }.decoder {
        val index = readUByte()
        val modelId = readBigSmart()
        HintTrail(index, modelId, readByteArray())
    }

    serverProt<SetDrawOrder>(opcode = 151, size = 1) { out ->
        out.writeBooleanInverse(playerFirst)
    }.decoder {
        SetDrawOrder(readBooleanInverse())
    }

    serverProt<BlockMinimapState>(opcode = 154, size = 1) { out ->
        out.writeByte(state)
    }.decoder {
        BlockMinimapState(readUByte())
    }

    serverProt<ResetAllAnimations>(opcode = 122)

    // Audio protocols (non-zone)
    serverProt<VorbisSound>(opcode = 18, size = 8) { out ->
        out.writeShort(if (soundId == -1) 65535 else soundId)
        out.writeByte(repeat)
        out.writeShort(delay)
        out.writeByte(volume)
        out.writeShort(sampleRate)
    }.decoder {
        VorbisSound(readSoundId(), readUByte(), readUShort(), readUByte(), readUShort())
    }

    serverProt<VorbisSpeechSound>(opcode = 104, size = 6) { out ->
        out.writeShort(if (soundId == -1) 65535 else soundId)
        out.writeByte(repeat)
        out.writeShort(delay)
        out.writeByte(volume)
    }.decoder {
        VorbisSpeechSound(readSoundId(), readUByte(), readUShort(), readUByte())
    }

    serverProt<SoundSynth>(opcode = 136, size = 8) { out ->
        out.writeShort(if (soundId == -1) 65535 else soundId)
        out.writeByte(repeat)
        out.writeShort(delay)
        out.writeByte(volume)
        out.writeShort(sampleRate)
    }.decoder {
        SoundSynth(readSoundId(), readUByte(), readUShort(), readUByte(), readUShort())
    }

    serverProt<MusicEffect>(opcode = 60, size = 6) { out ->
        out.writeMediumReverseEnd(unused)
        out.writeByteSubtract(volume)
        out.writeShortLittle(if (musicId == -1) 65535 else musicId)
    }.decoder {
        val unused = readMediumReverseEnd()
        val volume = readByteSubtract() and 0xff
        MusicEffect(unused, volume, readUShortLittle().orMinusOne())
    }

    serverProt<MusicTrack>(opcode = 63, size = 4) { out ->
        out.writeByteAdd(delay)
        out.writeByteAdd(volume)
        out.writeShortAdd(if (musicId == -1) 65535 else musicId)
    }.decoder {
        val delay = readByteAdd() and 0xff
        val volume = readByteAdd() and 0xff
        MusicTrack(delay, volume, readUShortAdd().orMinusOne())
    }

    serverProt<PreloadSong>(opcode = 77, size = 2) { out ->
        out.writeShortAddLittle(if (songId == -1) 65535 else songId)
    }.decoder {
        PreloadSong(readUShortAddLittle().orMinusOne())
    }

    serverProt<StopVorbisStream>(opcode = 11, size = 2) { out ->
        out.writeShortAddLittle(soundId)
    }.decoder {
        StopVorbisStream(readUShortAddLittle())
    }

    serverProt<VorbisPreloadSound>(opcode = 17, size = 3) { out ->
        out.writeByte(priority)
        out.writeShortAdd(soundId)
    }.decoder {
        val priority = readUByte()
        VorbisPreloadSound(readUShortAdd(), priority)
    }

    serverProt<SetVorbisSound>(opcode = 106, size = 2) { out ->
        out.writeShort(soundId)
    }.decoder {
        SetVorbisSound(readUShort())
    }

    serverProt<SoundMixbussSetLevel>(opcode = 134, size = 2) { out ->
        out.writeByte(index)
        out.writeByteInverse(level)
    }.decoder {
        val index = readByte().toInt()
        SoundMixbussSetLevel(index, readByteInverse() and 0xff)
    }

    serverProt<ResetSounds>(opcode = 120)

    // Login details
    loginResponse<LobbyLoginDetails>(opcode = 2, size = ProtSize.VarByte) { out ->
        out.writeByte(crown)
        out.writeByte(0) // PLAYER_MOD_LEVEL
        out.writeByte(0) // USERDETAIL_QUICKCHAT_ONLY
        out.writeMedium(0)
        out.writeByte(0) // Gender.
        out.writeByte(0) // VERIFIED_EMAIL_ADDRESS
        out.writeByte(1)
        out.writeLong(Long.MAX_VALUE) // members subscription end
        out.write5(12L)
        out.writeByte(0x1) // 0x1 - if members, 0x2 - subscription
        out.writeInt(1) // jcoins?
        out.writeByte(1) // is loyalty member?
        out.writeInt(1) // loyalty points?
        out.writeShort(592) // recovery questions set date
        out.writeShort(0) // Messages add support for forum integration
        out.writeShort(0) // last logged in date
        out.writeInt(lastIpHash)
        out.writeRSString(worldLoginToken)
        out.writeByte(2) // email status (0 - no email, 1 - pending parental confirmation, 2 - pending confirmation, 3 - registered)
        out.writeShort(302)
        out.writeShort(1)
        out.writeByte(1)
        out.writeJagString(displayName)
        out.writeByte(1)
        out.writeInt(1)
        out.writeByte(0) // Removed on EOC revisions for some reason. idk
        out.writeShort(defaultWorldId) // default world id
        out.writeJagString(defaultWorldAddress) // default world address
    }.decoder {
        val crown = readUByte()
        skip(1 + 1 + 3 + 1 + 1 + 1 + 8 + 5 + 1 + 4 + 1 + 4 + 2 + 2 + 2)
        val lastIpHash = readInt()
        val worldLoginToken = readRSString()
        skip(1 + 2 + 2 + 1)
        val displayName = readJagString()
        skip(1 + 4 + 1)
        val defaultWorldId = readUShort().orMinusOne()
        LobbyLoginDetails(crown, displayName, lastIpHash, worldLoginToken, defaultWorldId, readJagString())
    }

    // World-login details blob (LobbyStage.LOBBY_TO_WORLD).
    // This intentionally uses opcode=2 (success) + VarByte length, matching the client's world-login handshake.
    loginResponse<WorldLoginDetails>(opcode = 2, size = ProtSize.VarByte) { out ->
        out.writeByte(rights)
        out.writeByte(modLevel)
        out.writeByte(if (quickChat) 1 else 0)
        out.writeByte(if (verifiedEmail) 1 else 0)
        out.writeByte(if (aBool7322) 1 else 0)
        out.writeByte(if (quickChatOnly) 1 else 0)
        out.writeShort(playerIndex)
        out.writeByte(if (members) 1 else 0)
        out.writeMedium(dob)
        out.writeByte(if (memberWorld) 1 else 0)
        out.writeRSString(worldName)
    }.decoder {
        WorldLoginDetails(
            rights = readUByte(),
            modLevel = readUByte(),
            quickChat = readBoolean(),
            verifiedEmail = readBoolean(),
            aBool7322 = readBoolean(),
            quickChatOnly = readBoolean(),
            playerIndex = readUShort(),
            members = readBoolean(),
            dob = readMedium(),
            memberWorld = readBoolean(),
            worldName = readRSString(),
        )
    }

    loginResponse<WorldReconnectDetails>(opcode = 15, size = ProtSize.VarShort) { out ->
        out.writeLoginPlayerBlock(localPlayerPid, localPlayerBaseTileHash, otherPlayerRegionIds)
    }.decoder {
        val localPlayerIndex = ProtDecodeContext.localPlayerIndex()
            ?: error("WorldReconnectDetails carries the login player block but no local player index is in the ProtDecodeContext")
        val block = readLoginPlayerBlock(localPlayerIndex)
        WorldReconnectDetails(block.localPlayerIndex, block.baseTileHash, block.regionIds)
    }
}

private class LoginPlayerBlock(val localPlayerIndex: Int, val baseTileHash: Int, val regionIds: IntArray)

private fun ByteArray.source(): Source = Buffer().apply { write(this@source) }

private suspend fun ByteArray.afterLoginPlayerBlock(prot: String): Pair<LoginPlayerBlock, Source>? {
    if (size <= LOGIN_PLAYER_BLOCK_BYTES) return null
    val localPlayerIndex = ProtDecodeContext.localPlayerIndex()
        ?: error("$prot carries the login player block but no local player index is in the ProtDecodeContext")
    val body = source()
    return body.readLoginPlayerBlock(localPlayerIndex) to body
}

private fun Source.readLoginPlayerBlock(localPlayerIndex: Int): LoginPlayerBlock = bitAccess {
    val baseTileHash = readBits(30)
    val regionIds = IntArray(PLAYER_SLOTS)
    for (index in 1 until PLAYER_SLOTS)
        if (index != localPlayerIndex) regionIds[index] = readBits(18)
    if (localPlayerIndex < PLAYER_SLOTS) regionIds[localPlayerIndex] = Tile(baseTileHash).getRegionHash()
    LoginPlayerBlock(localPlayerIndex, baseTileHash, regionIds)
}

private suspend fun ByteWriteChannel.writeLoginPlayerBlock(localPlayerPid: Int, baseTileHash: Int, otherPlayerRegionIds: IntArray) = bitAccess {
    writeBits(30, baseTileHash)
    for (index in 1 until otherPlayerRegionIds.size) {
        if (index != localPlayerPid)
            writeBits(18, otherPlayerRegionIds[index])
    }
}

private fun Source.readXteas(): Array<IntArray> {
    val keys = mutableListOf<IntArray>()
    while (!exhausted()) keys += IntArray(4) { readInt() }
    return keys.toTypedArray()
}

private fun Source.readStandardRegion(block: LoginPlayerBlock?): BuildRegion? = try {
    val mapSize = RegionSize.entries.getOrNull(readUByte())
    val chunkX = readUShort()
    val chunkY = readUShort()
    val forceMapRefresh = readBoolean()
    val xteas = readXteas()
    val regionIds = mapSize?.let { BuildRegion.regionIds(chunkX, chunkY, it) }
    if (mapSize == null || regionIds == null || regionIds.size != xteas.size) null
    else BuildRegion(
        regionIds = regionIds,
        mapSize = mapSize,
        xteas = xteas,
        chunkX = chunkX,
        chunkY = chunkY,
        forceMapRefresh = forceMapRefresh,
        localPlayerPid = block?.localPlayerIndex,
        localPlayerBaseTileHash = block?.baseTileHash,
        otherPlayerRegionIds = block?.regionIds,
    )
} catch (_: EOFException) {
    null
}

private fun Source.readInstancedRegion(block: LoginPlayerBlock?): BuildInstancedRegion? = try {
    val forceMapRefresh = readBooleanInverse()
    val loadTypeIndex = readByteSubtract() and 0xff
    val mapSize = RegionSize.entries.getOrNull(readByteAdd() and 0xff)
    val chunkY = readUShortAdd()
    val chunkX = readUShort()
    val instanceLoadType = InstanceLoadType.entries.firstOrNull { it.index == loadTypeIndex }
    if (mapSize == null || instanceLoadType == null) null
    else {
        val span = mapSize.size shr 3
        val chunks = bitAccess { IntArray(4 * span * span) { if (readBit()) readBits(26) else BuildInstancedRegion.ABSENT_CHUNK } }
        val xteas = readXteas()
        if (BuildInstancedRegion.regionIds(chunks).size != xteas.size) null
        else BuildInstancedRegion(
            mapSize = mapSize,
            chunks = chunks,
            xteas = xteas,
            chunkX = chunkX,
            chunkY = chunkY,
            forceMapRefresh = forceMapRefresh,
            instanceLoadType = instanceLoadType,
            localPlayerPid = block?.localPlayerIndex,
            localPlayerBaseTileHash = block?.baseTileHash,
            otherPlayerRegionIds = block?.regionIds,
        )
    }
} catch (_: EOFException) {
    null
}

private fun Source.readReflectionCheck(): ReflectionCheck {
    val type = ReflectionCheckType.entries[readUByte()]
    val className = readRSString()
    val methodName = readRSString()
    return when (type) {
        ReflectionCheckType.GET_INT, ReflectionCheckType.GET_FIELD_MODIFIERS -> ReflectionCheck(type, className, methodName)
        ReflectionCheckType.SET_INT -> ReflectionCheck(type, className, methodName, fieldValue = readInt())
        ReflectionCheckType.GET_METHOD_RETURN_VALUE, ReflectionCheckType.GET_METHOD_MODIFIERS -> {
            val paramTypes = Array(readUByte()) { readRSString() }
            val returnType = readRSString()
            val paramValues = if (type == ReflectionCheckType.GET_METHOD_RETURN_VALUE)
                Array(paramTypes.size) { deserializeReflectionParam(readByteArray(readInt())) }
            else emptyArray()
            ReflectionCheck(type, className, methodName, returnType, paramTypes, paramValues)
        }
    }
}

private fun deserializeReflectionParam(data: ByteArray): Any = try {
    ObjectInputStream(ByteArrayInputStream(data)).use {
        it.setObjectInputFilter(reflectionParamFilter)
        it.readObject()
    } ?: data
} catch (_: IOException) {
    data
} catch (_: ClassNotFoundException) {
    data
}

private fun Source.readRawString(): String {
    val bytes = readByteArray()
    return String(CharArray(bytes.size) { Cp1252.decode(bytes[it]) })
}

private fun Source.readWorldListEntry(firstWorld: Int, location: Pair<Int, String>): WorldListEntry {
    val number = firstWorld + readSmart()
    val index = readUByte()
    val flags = readInt()
    readJagString()
    val address = readJagString()
    val port = if (flags and WORLD_FLAG_CUSTOM_PORT != 0) readInt() else DEFAULT_WORLD_PORT
    return WorldListEntry(
        number = number,
        index = index,
        countryId = location.first,
        activity = location.second,
        address = address,
        port = port,
        members = flags and 0x1 != 0,
        quickChat = flags and 0x2 != 0,
        botsAllowed = flags and 0x4 != 0,
        lootShare = flags and 0x8 != 0,
        highlighted = flags and 0x10 != 0,
        playersOnline = -1,
        customCache = flags and WORLD_FLAG_CUSTOM_CACHE != 0,
    )
}

private fun Source.readWorldPlayerCount(): Int = readUShort().orMinusOne()

private fun playerCountOnlyEntry(number: Int, playersOnline: Int) = WorldListEntry(
    number = number,
    index = 0,
    countryId = 0,
    activity = "",
    address = "",
    port = DEFAULT_WORLD_PORT,
    members = false,
    quickChat = false,
    botsAllowed = false,
    lootShare = false,
    highlighted = false,
    playersOnline = playersOnline,
)

private fun Source.readSoundId(): Int = readUShort().orMinusOne()

private fun Int.orMinusOne(): Int = if (this == 65535) -1 else this
