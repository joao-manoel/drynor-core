package org.darkan.core.net.prot

sealed class ProtSize {
    data class Fixed(val length: Int) : ProtSize()
    data object VarByte : ProtSize()
    data object VarShort : ProtSize()

    fun toInt() = when(this) {
        is Fixed -> length
        VarByte -> -1
        VarShort -> -2
    }
}