package org.darkan.core.net.prot.revision.rev727

import io.ktor.utils.io.*
import org.darkan.core.net.prot.*
import org.darkan.core.net.prot.packet.*
import world.gregs.voidps.buffer.*

internal fun Codec.registerRev727VariablePackets() {
    serverProt<VarpSmall>(opcode = 115, size = 3) { out ->
        out.writeByte(value)
        out.writeShortAddLittle(id)
    }.decoder {
        val value = readByte().toInt()
        VarpSmall(readUShortAddLittle(), value)
    }

    serverProt<VarpLarge>(opcode = 8, size = 6) { out ->
        out.writeIntInverseMiddle(value)
        out.writeShortAddLittle(id)
    }.decoder {
        val value = readIntInverseMiddle()
        VarpLarge(readUShortAddLittle(), value)
    }

    serverProt<ClientSetVarcSmall>(opcode = 116, size = 3) { out ->
        out.writeByteSubtract(value)
        out.writeShortLittle(id)
    }.decoder {
        val value = readByteSubtract()
        ClientSetVarcSmall(readUShortLittle(), value)
    }

    serverProt<ClientSetVarcLarge>(opcode = 12, size = 6) { out ->
        out.writeShortLittle(id)
        out.writeIntInverseMiddle(value)
    }.decoder {
        val id = readUShortLittle()
        ClientSetVarcLarge(id, readIntInverseMiddle())
    }

    serverProt<VarbitSmall>(opcode = 68, size = 3) { out ->
        out.writeShort(id)
        out.writeByteAdd(value)
    }.decoder {
        val id = readUShort()
        VarbitSmall(id, readByteAdd())
    }

    serverProt<VarbitLarge>(opcode = 108, size = 6) { out ->
        out.writeIntInverseMiddle(value)
        out.writeShortAdd(id)
    }.decoder {
        val value = readIntInverseMiddle()
        VarbitLarge(readUShortAdd(), value)
    }

    serverProt<ClientSetVarcStrSmall>(opcode = 54, size = ProtSize.VarByte) { out ->
        out.writeRSString(value)
        out.writeShortAddLittle(id)
    }.decoder {
        val value = readRSString()
        ClientSetVarcStrSmall(readUShortAddLittle(), value)
    }

    serverProt<ClientSetVarcStrLarge>(opcode = 119, size = ProtSize.VarShort) { out ->
        out.writeShortLittle(id)
        out.writeRSString(value)
    }.decoder {
        val id = readUShortLittle()
        ClientSetVarcStrLarge(id, readRSString())
    }

    serverProt<VarclanSetLong>(opcode = 26, size = 10) { out ->
        out.writeShort(id)
        out.writeLong(value)
    }.decoder {
        val id = readUShort()
        VarclanSetLong(id, readLong())
    }

    serverProt<VarclanSetByte>(opcode = 123, size = 3) { out ->
        out.writeShort(id)
        out.writeByte(value)
    }.decoder {
        val id = readUShort()
        VarclanSetByte(id, readByte().toInt())
    }

    serverProt<VarclanSetInt>(opcode = 141, size = 6) { out ->
        out.writeShort(id)
        out.writeInt(value)
    }.decoder {
        val id = readUShort()
        VarclanSetInt(id, readInt())
    }

    serverProt<VarclanSetString>(opcode = 50, size = ProtSize.VarByte) { out ->
        out.writeShort(id)
        out.writeRSString(value)
    }.decoder {
        val id = readUShort()
        VarclanSetString(id, readRSString())
    }

    serverProt<VarclanEnable>(opcode = 45)

    serverProt<VarclanDisable>(opcode = 94)

    serverProt<ClearVarps>(opcode = 100)
}
