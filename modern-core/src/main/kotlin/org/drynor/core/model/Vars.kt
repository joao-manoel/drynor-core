package org.drynor.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.jvm.Transient as JvmTransient
import org.drynor.core.net.Session
import org.drynor.core.net.prot.ServerProt
import java.util.function.Consumer
import org.drynor.core.net.prot.packet.ClearVarps
import org.drynor.core.net.prot.packet.setVarcPacket
import org.drynor.core.net.prot.packet.setVarcStrPacket
import org.drynor.core.net.prot.packet.setVarpPacket
import org.drynor.core.cache.VarBitDefinitions
import org.drynor.core.gameval.Gameval
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Config
import world.gregs.voidps.cache.Index

@Serializable
class Vars(val vars: MutableMap<Int, Int> = HashMap()) {
    companion object {
        val BIT_MASKS = IntArray(32).apply {
            var value = 2
            for (i in 0 until 32) {
                this[i] = value - 1
                value *= 2
            }
        }
    }

    @Transient
    @JvmTransient
    private var modified: MutableSet<Int> = HashSet()
    @Transient
    @JvmTransient
    private lateinit var varpValues: IntArray
    @Transient
    @JvmTransient
    private lateinit var session: Session

    @Transient
    @JvmTransient
    private var packetSink: Consumer<ServerProt>? = null

    /** Bind an application-owned writer without depending on its socket implementation. */
    fun setPacketSink(sink: Consumer<ServerProt>): Vars {
        packetSink = sink
        return initWithoutSession()
    }

    fun setSession(session: Session): Vars {
        packetSink = null
        this.session = session
        varpValues = IntArray(varpTableSize())
        modified = HashSet()
        vars.forEach { (varId, value) -> setVar(varId, value) }
        return this
    }

    fun reattach(session: Session) {
        this.session = session
        varpValues.indices.filterTo(modified) { varpValues[it] != 0 }
    }

    /**
     * Initialize vars without a session for offline/tool usage.
     * This allows using getVar/setVar/getVarBit/setVarBit without network sync.
     */
    fun initWithoutSession(): Vars {
        varpValues = IntArray(varpTableSize())
        modified = HashSet()
        vars.forEach { (varId, value) -> setVar(varId, value) }
        return this
    }

    /**
     * Wide enough for every varp the catalogs name as well as every one the cache holds, so a varp
     * added to the source tree is writable against a cache packed before it existed.
     */
    private fun varpTableSize(): Int {
        val fromCache = Cache.get().lastFileId(Index.CONFIGS, Config.VARP) + 1
        val fromCatalog = (Gameval.allEntries(Gameval.VARP).keys.maxOrNull() ?: -1) + 1
        return maxOf(fromCache, fromCatalog)
    }

    @JvmOverloads
    fun setVar(id: Int, value: Int, forceSend: Boolean = false, save: Boolean = false) {
        if (forceSend)
            modified.add(id)
        if (id < 0 || id >= varpValues.size)
            return
        if (save)
            vars[id] = value
        if (varpValues[id] == value)
            return
        varpValues[id] = value
        modified.add(id)
    }

    fun saveVar(id: Int, value: Int) = setVar(id, value, forceSend = false, save = true)

    @JvmOverloads
    fun setVarBit(id: Int, value: Int, forceSend: Boolean = false, save: Boolean = false) {
        val defs = VarBitDefinitions.getDefs(id)
        if (defs.index < 0 || defs.index >= varpValues.size) return
        val bitLength = defs.endBit - defs.startBit
        if (bitLength !in BIT_MASKS.indices || defs.startBit !in 0..31) return
        val mask = BIT_MASKS[bitLength]

        val cappedValue = if (mask == -1) value else value.coerceIn(0, mask)
        val shiftedMask = mask shl defs.startBit
        val varpValue = (varpValues[defs.index] and shiftedMask.inv()) or ((cappedValue shl defs.startBit) and shiftedMask)

        if (save || varpValue != varpValues[defs.index])
            setVar(defs.index, varpValue, forceSend, save)
    }

    fun saveVarBit(id: Int, value: Int) = setVarBit(id, value, forceSend = false, save = true)

    /**
     * Reads a varp, treating anything outside the varp table as unset (0), for the same reason
     * [getVarBit] does: object and NPC transforms name varps straight out of the cache, and one
     * dangling reference coming into view would otherwise take region loading down with it.
     */
    fun getVar(id: Int): Int {
        if (id < 0 || id >= varpValues.size) return 0
        return varpValues[id]
    }

    /**
     * Reads a varbit, treating anything the cache cannot resolve as unset (0).
     *
     * This used to index straight through and throw. The cache genuinely contains
     * dangling references: 40 objects name a varbit past the end of the varbit table
     * (the table holds 2697 entries; object transforms reference ids as high as 2703).
     * Since object transforms are resolved during region loading, one of those objects
     * coming into view took the whole load path down with an
     * `ArrayIndexOutOfBoundsException` -- a hard crash caused purely by data, on a path
     * with no way to recover.
     *
     * 0 is the right answer rather than merely a safe one: it is what an unset var
     * reads as, so an unresolvable varbit selects the transform's default.
     */
    fun getVarBit(id: Int): Int {
        val defs = VarBitDefinitions.getDefs(id)
        if (defs.index < 0 || defs.index >= varpValues.size) return 0
        val bitLength = defs.endBit - defs.startBit
        if (bitLength < 0 || bitLength >= BIT_MASKS.size) return 0
        return (varpValues[defs.index] shr defs.startBit) and BIT_MASKS[bitLength]
    }

    fun bitFlagged(id: Int, bit: Int) = (varpValues[id] and (1 shl bit)) != 0

    fun syncVarsToClient() {
        modified.forEach { id ->
            if (id < 0 || id >= varpValues.size) return@forEach
            val packet = setVarpPacket(id, varpValues[id])
            packetSink?.accept(packet) ?: session.queuePacket(packet)
        }
        modified.clear()
    }

    suspend fun clearVars() {
        varpValues.fill(0)
        session.send(ClearVarps())
    }

    suspend fun setVarc(id: Int, value: Int) = session.send(setVarcPacket(id, value))
    suspend fun setVarcStr(id: Int, value: String) = session.send(setVarcStrPacket(id, value))
}
