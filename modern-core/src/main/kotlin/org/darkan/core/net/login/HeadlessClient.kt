package org.darkan.core.net.login

import org.darkan.core.model.MachineInformation

object HeadlessClient {
    private const val SIGNATURE = "darkan-headless-client"
    private val FINGERPRINT_SIGNATURE = SIGNATURE.toByteArray(Charsets.US_ASCII)

    fun fingerprint(): ByteArray = FINGERPRINT_SIGNATURE.copyOf(24)

    fun isHeadless(fingerprint: ByteArray) =
        fingerprint.size >= FINGERPRINT_SIGNATURE.size && FINGERPRINT_SIGNATURE.indices.all { fingerprint[it] == FINGERPRINT_SIGNATURE[it] }

    fun machine() = MachineInformation(
        version = WorldLoginRequest.MACHINE_INFO_VERSION,
        gpuModel = SIGNATURE,
        cpuType = SIGNATURE,
        cpuData = SIGNATURE,
    )

    fun isHeadless(machine: MachineInformation) = machine.cpuType == SIGNATURE
}
