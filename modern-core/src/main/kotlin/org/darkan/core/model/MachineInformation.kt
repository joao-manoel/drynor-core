package org.darkan.core.model

import kotlinx.io.Source
import kotlinx.serialization.Serializable
import world.gregs.voidps.buffer.*
import world.gregs.voidps.buffer.write.Writer
import java.util.*

@Serializable
data class MachineInformation(
    var version: Int = 0,
    var operatingSystem: Int = 0,
    var x64os: Boolean = false,
    var osVendor: Int = 0,
    /** The client's vendor classification: 1 sun, 2 microsoft, 3 apple, 4 other. */
    var javaVendor: Int = 0,
    var javaBuild: Int = 0,
    var javaSubBuild: Int = 0,
    var javaUpdate: Int = 0,
    /** Human-readable form of the four fields above. */
    var javaVersion: String = "",
    var ram: Int = 0,
    var gpuModel: String? = null,
    var dwDirectXVersion: String? = null,
    var cpuFeatureFlags: IntArray = IntArray(3),
    var maxMem: Int = 0,
    var processors: Int = 0,
    var cpuCores: Int = 0,
    var cpuClock: Int = 0,
    var cpuType: String? = null,
    var cpuModelInfo: Int = 0,
    var cpuFamilyId: Int = 0,
    var cpuData: String? = null,
    var dxDriverMonth: Int = 0,
    var dxDriverYear: Int = 0
) {
    override fun hashCode(): Int {
        var result = Objects.hash(
            operatingSystem, x64os, osVendor,
            ram, gpuModel, dwDirectXVersion, maxMem, processors,
            cpuCores, cpuClock, cpuType, cpuModelInfo, cpuFamilyId, cpuData,
            dxDriverMonth, dxDriverYear
        )
        result = 31 * result + cpuFeatureFlags.contentHashCode()
        return result
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MachineInformation) return false

        return operatingSystem == other.operatingSystem &&
                x64os == other.x64os &&
                osVendor == other.osVendor &&
                javaVersion == other.javaVersion &&
                ram == other.ram &&
                gpuModel == other.gpuModel &&
                dwDirectXVersion == other.dwDirectXVersion &&
                maxMem == other.maxMem &&
                processors == other.processors &&
                cpuCores == other.cpuCores &&
                cpuClock == other.cpuClock &&
                cpuType == other.cpuType &&
                cpuModelInfo == other.cpuModelInfo &&
                cpuFamilyId == other.cpuFamilyId &&
                cpuData == other.cpuData &&
                dxDriverMonth == other.dxDriverMonth &&
                dxDriverYear == other.dxDriverYear &&
                cpuFeatureFlags.contentEquals(other.cpuFeatureFlags)
    }

    override fun toString(): String {
        return """
        MachineInformation(
            version = $version,
            operatingSystem = $operatingSystem,
            x64os = $x64os,
            osVendor = $osVendor,
            javaVersion = $javaVersion,
            ram = $ram,
            gpuModel = $gpuModel,
            dwDirectXVersion = $dwDirectXVersion,
            cpuFeatureFlags = ${cpuFeatureFlags.contentToString()},
            maxMem = $maxMem,
            processors = $processors,
            cpuCores = $cpuCores,
            cpuClock = $cpuClock,
            cpuType = $cpuType,
            cpuModelInfo = $cpuModelInfo,
            cpuFamilyId = $cpuFamilyId,
            cpuData = $cpuData,
            dxDriverMonth = $dxDriverMonth,
            dxDriverYear = $dxDriverYear
        )
    """.trimIndent()
    }

    fun write(writer: Writer) {
        writer.writeByte(version)
        writer.writeByte(operatingSystem)
        writer.writeByte(x64os)
        writer.writeByte(osVendor)
        writer.writeByte(javaVendor)
        writer.writeByte(javaBuild)
        writer.writeByte(javaSubBuild)
        writer.writeByte(javaUpdate)
        writer.writeByte(0)
        writer.writeShort(maxMem)
        writer.writeByte(processors)
        writer.writeMedium(ram)
        writer.writeShort(cpuClock)
        writer.writePrefixedString(gpuModel ?: "")
        writer.writePrefixedString("")
        writer.writePrefixedString(dwDirectXVersion ?: "")
        writer.writePrefixedString("")
        writer.writeByte(dxDriverMonth)
        writer.writeShort(dxDriverYear)
        writer.writePrefixedString(cpuType ?: "")
        writer.writePrefixedString(cpuData ?: "")
        writer.writeByte(cpuCores)
        writer.writeByte(cpuFamilyId)
        cpuFeatureFlags.forEach(writer::writeInt)
        writer.writeInt(cpuModelInfo)
    }

    companion object {
        fun parse(stream: Source): MachineInformation {
            val info = MachineInformation()
            info.version = stream.readUByte()
            info.operatingSystem = stream.readUByte()
            info.x64os = stream.readUByte() == 1
            info.osVendor = stream.readUByte()
            info.javaVendor = stream.readUByte()
            info.javaBuild = stream.readUByte()
            info.javaSubBuild = stream.readUByte()
            info.javaUpdate = stream.readUByte()
            info.javaVersion = "${info.javaBuild}.${info.javaSubBuild}.${info.javaUpdate}"
            stream.readBoolean() //always false hardcoded
            info.maxMem = stream.readUShort()
            info.processors = stream.readUByte()
            info.ram = stream.readUMedium()
            info.cpuClock = stream.readUShort()
            info.gpuModel = stream.readJagString()
            stream.readJagString() //always blank placeholder
            info.dwDirectXVersion = stream.readJagString()
            stream.readJagString() //always blank placeholder
            info.dxDriverMonth = stream.readUByte()
            info.dxDriverYear = stream.readUShort()
            info.cpuType = stream.readJagString()
            info.cpuData = stream.readJagString()
            info.cpuCores = stream.readUByte()
            info.cpuFamilyId = stream.readByte().toInt()
            for (i in info.cpuFeatureFlags.indices)
                info.cpuFeatureFlags[i] = stream.readInt()
            info.cpuModelInfo = stream.readInt()
            return info
        }
    }
}