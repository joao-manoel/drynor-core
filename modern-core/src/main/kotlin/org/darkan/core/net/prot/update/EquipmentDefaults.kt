package org.darkan.core.net.prot.update

import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index

/**
 * Equipment defaults loaded from cache (index 28, file 6).
 * The client reads customizableObjSlots from this same cache entry
 * to decode appearance blocks. The server must use an identical array.
 */
object EquipmentDefaults {
    lateinit var customizableObjSlots: IntArray
        private set
    var weaponSlot: Int = -1
        private set
    var shieldSlot: Int = -1
        private set

    fun load(cache: Cache) {
        val data = cache.data(Index.DEFAULTS, 6)
            ?: error("Missing equipment defaults (index ${Index.DEFAULTS}, file 6)")
        decode(BufferReader(data))
        check(::customizableObjSlots.isInitialized) { "customizableObjSlots not found in equipment defaults" }
    }

    private fun decode(buffer: BufferReader) {
        while (true) {
            when (val opcode = buffer.readUnsignedByte()) {
                0 -> return
                1 -> {
                    val count = buffer.readUnsignedByte()
                    customizableObjSlots = IntArray(count) { buffer.readUnsignedByte() }
                }
                3 -> shieldSlot = buffer.readUnsignedByte()
                4 -> weaponSlot = buffer.readUnsignedByte()
                5 -> repeat(buffer.readUnsignedByte()) { buffer.readUnsignedByte() }
                6 -> repeat(buffer.readUnsignedByte()) { buffer.readUnsignedByte() }
            }
        }
    }
}
